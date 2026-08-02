package com.visordocs.data.markup

import com.visordocs.data.xml.attr
import com.visordocs.data.xml.parserFor
import com.visordocs.data.zip.ZipPackage
import org.xmlpull.v1.XmlPullParser

/**
 * Convierte un .ods (hoja de LibreOffice/OpenOffice) a HTML, una tabla por hoja.
 *
 * A diferencia de Excel, OpenDocument no usa una tabla de cadenas compartidas: el texto
 * de la celda esta ahi mismo, dentro de un `<text:p>`. En cambio si comprime de otra
 * forma, y esa es la trampa del formato: **repite** filas y columnas con
 * `table:number-rows-repeated` y `table:number-columns-repeated`.
 *
 * Una hoja con una sola celda rellena puede declarar 16384 columnas repetidas vacias
 * hasta el final. Expandirlas a ciegas generaria millones de celdas, asi que las
 * repeticiones solo se expanden cuando la celda tiene contenido, y con un tope.
 */
object OdsMarkup {

    private const val MAX_ROWS_PER_SHEET = 5_000
    private const val MAX_COLS = 128

    /** Tope de expansion de una repeticion con contenido. */
    private const val MAX_REPEAT = 128

    fun convert(pkg: ZipPackage, labels: MarkupLabels): Markup {
        val xml = pkg.text("content.xml") ?: return Markup.Empty

        val out = StringBuilder(16 * 1024)
        val parser = parserFor(xml)

        var sheets = 0
        var truncated = false

        // Estado de la hoja en curso.
        var rows = mutableListOf<List<String>>()
        var sheetName = ""
        var inSheet = false

        // Estado de la fila y celda en curso.
        var row = mutableListOf<String>()
        var rowRepeat = 1
        var cellRepeat = 1
        var cellText = StringBuilder()
        var inCell = false

        fun renderSheet() {
            // Se recortan las filas vacias del final: OpenDocument las declara hasta el
            // limite de la hoja.
            while (rows.isNotEmpty() && rows.last().all { it.isEmpty() }) {
                rows.removeAt(rows.size - 1)
            }
            if (rows.isEmpty()) return

            val columns = rows.maxOf { it.size }.coerceAtMost(MAX_COLS)
            out.append("<div class=\"section-title\">")
                .append(labels.sheet.escapeHtml())
                .append("</div>\n")
            out.append("<h2>").append(sheetName.escapeHtml()).append("</h2>\n")
            out.append("<div class=\"scroll-x\"><table>\n<tr><th class=\"ref\"></th>")
            for (c in 0 until columns) {
                out.append("<th class=\"ref\">").append(columnName(c)).append("</th>")
            }
            out.append("</tr>\n")

            rows.forEachIndexed { index, cells ->
                out.append("<tr><td class=\"rowref\">").append(index + 1).append("</td>")
                for (c in 0 until columns) {
                    val value = cells.getOrNull(c).orEmpty()
                    val numeric = value.isNotEmpty() &&
                        value.replace(',', '.').toDoubleOrNull() != null
                    out.append(if (numeric) "<td class=\"num\">" else "<td>")
                    out.append(value.escapeHtml()).append("</td>")
                }
                out.append("</tr>\n")
            }
            out.append("</table></div>\n")
            sheets++
        }

        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "table:table" -> {
                        inSheet = true
                        sheetName = parser.attr("name") ?: "Hoja ${sheets + 1}"
                        rows = mutableListOf()
                    }

                    "table:table-row" -> if (inSheet) {
                        row = mutableListOf()
                        rowRepeat = parser.attr("number-rows-repeated")?.toIntOrNull() ?: 1
                    }

                    "table:table-cell", "table:covered-table-cell" -> if (inSheet) {
                        inCell = true
                        cellText = StringBuilder()
                        cellRepeat = parser.attr("number-columns-repeated")?.toIntOrNull() ?: 1
                    }

                    "text:line-break" -> if (inCell) cellText.append(' ')
                }

                XmlPullParser.TEXT -> if (inCell) {
                    parser.text?.let { cellText.append(it) }
                }

                XmlPullParser.END_TAG -> when (parser.name) {
                    "table:table-cell", "table:covered-table-cell" -> if (inSheet) {
                        inCell = false
                        val value = cellText.toString().trim()
                        // Una repeticion de celdas vacias es relleno hasta el final de la
                        // hoja: se ignora. Solo se expande si hay algo que repetir.
                        val times = if (value.isEmpty()) 1 else cellRepeat.coerceIn(1, MAX_REPEAT)
                        repeat(times) { if (row.size < MAX_COLS) row.add(value) }
                    }

                    "table:table-row" -> if (inSheet) {
                        val isEmpty = row.all { it.isEmpty() }
                        val times = if (isEmpty) 1 else rowRepeat.coerceIn(1, MAX_REPEAT)
                        repeat(times) {
                            if (rows.size < MAX_ROWS_PER_SHEET) rows.add(row.toList())
                            else truncated = true
                        }
                    }

                    "table:table" -> if (inSheet) {
                        renderSheet()
                        inSheet = false
                    }
                }
            }
            parser.next()
        }

        if (sheets == 0) return Markup.Empty
        return Markup(body = out.toString(), truncated = truncated)
    }

    /** 0 -> A, 26 -> AA. */
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
