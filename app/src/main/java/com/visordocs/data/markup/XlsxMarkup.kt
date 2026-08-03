package com.visordocs.data.markup

import com.visordocs.data.zip.ZipPackage
import com.visordocs.data.xml.attr
import com.visordocs.data.xml.parserFor
import com.visordocs.data.ooxml.relationships
import com.visordocs.data.ooxml.resolvePart
import org.xmlpull.v1.XmlPullParser

/**
 * Convierte un .xlsx a HTML, una tabla por hoja.
 *
 * Excel no guarda el texto dentro de la celda. Para ahorrar espacio mete todas las
 * cadenas repetidas en `xl/sharedStrings.xml` y en la celda deja solo un indice, con
 * el atributo `t="s"`. Por eso hay que leer esa tabla antes que nada.
 *
 * Las fechas se resuelven leyendo `xl/styles.xml`: Excel las guarda como numeros y solo
 * el formato aplicado a la celda revela que son fechas (ver [ExcelNumberFormats]).
 */
object XlsxMarkup {

    private const val MAX_ROWS_PER_SHEET = 5_000
    private const val MAX_COLS = 128

    fun convert(pkg: ZipPackage, labels: MarkupLabels): Markup {
        val sharedStrings = readSharedStrings(pkg)
        val styles = ExcelNumberFormats.read(pkg)
        val colors = ExcelCellColors.read(pkg)
        val relations = pkg.relationships("xl/_rels/workbook.xml.rels")
        val sheets = readSheetList(pkg)

        val out = StringBuilder(16 * 1024)
        var rendered = 0
        var truncated = false

        for (sheet in sheets) {
            val path = relations[sheet.relationId]?.let { resolvePart(it, base = "xl/") }
                ?: pkg.names("xl/worksheets/sheet").getOrNull(rendered)
            val xml = path?.let { pkg.text(it) } ?: continue

            out.append("<div class=\"section-title\">")
                .append(labels.sheet.escapeHtml())
                .append("</div>\n")
            out.append("<h2>").append(sheet.name.escapeHtml()).append("</h2>\n")

            val table = renderSheet(xml, sharedStrings, styles, colors)
            out.append(table.body)
            if (table.truncated) truncated = true
            rendered++
        }

        if (rendered == 0) return Markup.Empty
        return Markup(body = out.toString(), truncated = truncated)
    }

    // ---------------------------------------------------------------- hojas

    private data class SheetRef(val name: String, val relationId: String?)

    private fun readSheetList(pkg: ZipPackage): List<SheetRef> {
        val xml = pkg.text("xl/workbook.xml") ?: return emptyList()
        val sheets = mutableListOf<SheetRef>()
        val parser = parserFor(xml)
        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType == XmlPullParser.START_TAG && parser.name == "sheet") {
                sheets += SheetRef(
                    name = parser.attr("name") ?: "Hoja ${sheets.size + 1}",
                    relationId = parser.attr("id"),
                )
            }
            parser.next()
        }
        return sheets
    }

    // ---------------------------------------------------------------- celdas

    private data class Cell(
        val text: String,
        val numeric: Boolean,
        val color: ExcelCellColors.CellStyle = ExcelCellColors.Styles.Empty,
    )

    private fun renderSheet(
        xml: String,
        sharedStrings: List<String>,
        styles: ExcelNumberFormats.Styles,
        colors: ExcelCellColors.Styles,
    ): Markup {
        // Se indexa por el numero de fila real, el que trae el atributo `r`. Excel
        // omite las filas vacias del archivo, asi que numerarlas por orden de
        // aparicion desplazaria los datos: lo que en Excel es la fila 10 apareceria
        // como la 7, y las referencias del usuario dejarian de cuadrar.
        val rowsByNumber = LinkedHashMap<Int, Map<Int, Cell>>()
        var maxRowNumber = 0
        var maxCol = 0
        var truncated = false

        val parser = parserFor(xml)
        var currentRow: MutableMap<Int, Cell>? = null
        var currentRowNumber = 0
        var cellCol = 0
        var cellType: String? = null
        var cellStyle: Int? = null
        var cellValue: String? = null

        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "row" -> {
                        currentRow = LinkedHashMap()
                        currentRowNumber = parser.attr("r")?.toIntOrNull() ?: (maxRowNumber + 1)
                    }

                    "c" -> {
                        cellCol = columnIndex(parser.attr("r"))
                        cellType = parser.attr("t")
                        // `s` apunta al estilo, que es lo unico que distingue una fecha
                        // de un numero cualquiera.
                        cellStyle = parser.attr("s")?.toIntOrNull()
                        cellValue = null
                    }

                    // <v> en celda normal; <t> dentro de <is> en celda con texto en linea.
                    "v", "t" -> {
                        cellValue = (cellValue ?: "") + parser.nextText()
                        continue
                    }
                }

                XmlPullParser.END_TAG -> when (parser.name) {
                    "c" -> {
                        val row = currentRow
                        val raw = cellValue
                        if (row != null && raw != null && cellCol in 0 until MAX_COLS) {
                            val kind = styles.kindOf(cellStyle)
                            resolveCell(raw, cellType, kind, sharedStrings)?.let { cell ->
                                row[cellCol] = cell.copy(color = colors.styleOf(cellStyle))
                                if (cellCol + 1 > maxCol) maxCol = cellCol + 1
                            }
                        }
                        cellValue = null
                        cellStyle = null
                    }

                    "row" -> {
                        currentRow?.let { row ->
                            when {
                                currentRowNumber > MAX_ROWS_PER_SHEET -> truncated = true
                                row.isNotEmpty() -> {
                                    rowsByNumber[currentRowNumber] = row
                                    if (currentRowNumber > maxRowNumber) maxRowNumber = currentRowNumber
                                }
                            }
                        }
                        currentRow = null
                    }
                }
            }
            if (truncated) break
            parser.next()
        }

        if (rowsByNumber.isEmpty()) return Markup.Empty

        val out = StringBuilder()
        out.append("<div class=\"scroll-x\"><table>\n<tr><th class=\"ref\"></th>")
        for (c in 0 until maxCol) {
            out.append("<th class=\"ref\">").append(columnName(c)).append("</th>")
        }
        out.append("</tr>\n")

        for (rowNumber in 1..maxRowNumber) {
            val row = rowsByNumber[rowNumber]
            out.append("<tr><td class=\"rowref\">").append(rowNumber).append("</td>")
            for (c in 0 until maxCol) {
                val cell = row?.get(c)
                if (cell == null) {
                    out.append("<td></td>")
                } else {
                    out.append("<td")
                    if (cell.numeric) out.append(" class=\"num\"")
                    cell.color.toCssStyle()?.let { out.append(" style=\"").append(it).append('"') }
                    out.append('>')
                    out.append(cell.text.escapeHtml()).append("</td>")
                }
            }
            out.append("</tr>\n")
        }
        out.append("</table></div>\n")

        return Markup(body = out.toString(), truncated = truncated)
    }

    private fun resolveCell(
        raw: String,
        type: String?,
        kind: ExcelNumberFormats.Kind,
        sharedStrings: List<String>,
    ): Cell? {
        val text = when (type) {
            "s" -> raw.trim().toIntOrNull()?.let { sharedStrings.getOrNull(it) } ?: return null
            "b" -> if (raw.trim() == "1") "VERDADERO" else "FALSO"
            // inlineStr y str (resultado de formula) ya traen el texto literal.
            else -> raw
        }
        if (text.isEmpty()) return null

        val number = if (type == null) text.toDoubleOrNull() else null

        // Una fecha se sigue alineando a la derecha: es un numero disfrazado, y en una
        // hoja de calculo se lee mejor en columna.
        if (number != null) {
            ExcelNumberFormats.format(number, kind)?.let { return Cell(it, numeric = true) }
        }

        return Cell(text, numeric = number != null)
    }

    private fun readSharedStrings(pkg: ZipPackage): List<String> {
        val xml = pkg.text("xl/sharedStrings.xml") ?: return emptyList()
        val strings = mutableListOf<String>()
        val parser = parserFor(xml)
        var current: StringBuilder? = null

        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "si" -> current = StringBuilder()
                    // Una entrada puede partirse en varios <r> con formatos distintos;
                    // se concatenan todos sus <t>.
                    "t" -> {
                        val text = parser.nextText()
                        current?.append(text)
                        continue
                    }
                }

                XmlPullParser.END_TAG -> if (parser.name == "si") {
                    strings += (current?.toString() ?: "")
                    current = null
                }
            }
            parser.next()
        }
        return strings
    }

    /** Convierte "A1" o "BC27" en el indice de columna de base 0. */
    private fun columnIndex(ref: String?): Int {
        if (ref.isNullOrEmpty()) return 0
        var index = 0
        for (c in ref) {
            if (c !in 'A'..'Z') break
            index = index * 26 + (c - 'A' + 1)
        }
        return (index - 1).coerceAtLeast(0)
    }

    /** El inverso: 0 -> A, 26 -> AA. */
    private fun columnName(index: Int): String {
        var n = index
        val sb = StringBuilder()
        while (true) {
            sb.insert(0, ('A' + n % 26))
            n = n / 26 - 1
            if (n < 0) break
        }
        return sb.toString()
    }
}
