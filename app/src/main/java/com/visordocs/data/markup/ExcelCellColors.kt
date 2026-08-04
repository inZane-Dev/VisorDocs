package com.visordocs.data.markup

import com.visordocs.data.xml.attr
import com.visordocs.data.xml.parserFor
import com.visordocs.data.zip.ZipPackage
import org.xmlpull.v1.XmlPullParser

/**
 * Colores de relleno y de letra de cada celda.
 *
 * La cadena es la misma que la de los formatos numericos: la celda trae `s="3"`, que es
 * una posicion en `<cellXfs>`, y esa entrada apunta con `fontId` y `fillId` a las listas
 * `<fonts>` y `<fills>` de `xl/styles.xml`.
 *
 * Dos trampas del formato:
 *
 * - `<fills>` empieza SIEMPRE con dos rellenos reservados (`none` y `gray125`) que Excel
 *   escribe aunque nadie los use. El primer relleno de verdad es el indice 2.
 * - Dentro de `patternFill`, el color que se ve es `fgColor`, no `bgColor`. `bgColor` solo
 *   pinta cuando el patron es una trama, que aqui se trata como relleno solido.
 */
internal object ExcelCellColors {

    /** Colores resueltos de una celda. Nulo significa "que mande el tema de la app". */
    data class CellStyle(val background: OfficeColor.Rgb?, val text: OfficeColor.Rgb?) {
        val isEmpty: Boolean get() = background == null && text == null

        /** `null` cuando la celda no aporta nada, para no ensuciar el HTML con estilos vacios. */
        fun toCssStyle(): String? {
            if (isEmpty) return null
            return buildString {
                background?.let { append("background:").append(it.toCss()).append(';') }
                text?.let { append("color:").append(it.toCss()).append(';') }
            }
        }
    }

    class Styles(private val byStyleIndex: List<CellStyle>) {

        fun styleOf(styleIndex: Int?): CellStyle =
            styleIndex?.let { byStyleIndex.getOrNull(it) } ?: Empty

        companion object {
            val Empty = CellStyle(background = null, text = null)
            val None = Styles(emptyList())
        }
    }

    fun read(pkg: ZipPackage): Styles {
        val xml = pkg.text("xl/styles.xml") ?: return Styles.None
        val theme = OfficeTheme.read(pkg, "xl/theme/theme1.xml")

        val fontColors = mutableListOf<OfficeColor.Rgb?>()
        val fillColors = mutableListOf<OfficeColor.Rgb?>()
        val cells = mutableListOf<CellStyle>()

        var section = Section.NONE
        // Un `<color>` puede aparecer dentro de una fuente o dentro de un relleno; hay que
        // saber a cual pertenece.
        var inFont = false
        var inPatternFill = false
        var fontColor: OfficeColor.Rgb? = null
        var fillColor: OfficeColor.Rgb? = null

        val parser = parserFor(xml)
        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "fonts" -> section = Section.FONTS
                    "fills" -> section = Section.FILLS
                    "cellXfs" -> section = Section.CELL_XFS

                    "font" -> if (section == Section.FONTS) {
                        inFont = true
                        fontColor = null
                    }

                    "patternFill" -> if (section == Section.FILLS) {
                        inPatternFill = true
                        // `none` es ausencia de relleno, no un color.
                        if (parser.attr("patternType") == "none") inPatternFill = false
                    }

                    "fgColor" -> if (inPatternFill) fillColor = parser.color(theme)

                    "color" -> if (inFont) fontColor = parser.color(theme)

                    "xf" -> if (section == Section.CELL_XFS) {
                        cells += resolveCell(parser, fontColors, fillColors)
                    }
                }

                XmlPullParser.END_TAG -> when (parser.name) {
                    "font" -> if (section == Section.FONTS) {
                        fontColors += fontColor
                        inFont = false
                    }

                    "fill" -> if (section == Section.FILLS) {
                        fillColors += fillColor
                        fillColor = null
                        inPatternFill = false
                    }

                    "fonts", "fills", "cellXfs" -> section = Section.NONE
                }
            }
            parser.next()
        }

        return if (cells.any { !it.isEmpty }) Styles(cells) else Styles.None
    }

    /**
     * Une la fuente y el relleno que le tocan a esta entrada de `<cellXfs>`.
     *
     * `applyFill` y `applyFont` en `false` significan que la celda hereda del estilo con
     * nombre y no aplica el suyo. Excel los omite muy a menudo, asi que la ausencia del
     * atributo se toma como "si aplica": exigirlo dejaria casi todo sin color.
     */
    private fun resolveCell(
        parser: XmlPullParser,
        fontColors: List<OfficeColor.Rgb?>,
        fillColors: List<OfficeColor.Rgb?>,
    ): CellStyle {
        val fillId = parser.attr("fillId")?.toIntOrNull()
        val fontId = parser.attr("fontId")?.toIntOrNull()
        val appliesFill = parser.attr("applyFill") != "0"
        val appliesFont = parser.attr("applyFont") != "0"

        val background = if (appliesFill) fillId?.let { fillColors.getOrNull(it) } else null
        val declaredText = if (appliesFont) fontId?.let { fontColors.getOrNull(it) } else null

        return when {
            // Con relleno, el texto tiene que leerse SI O SI encima. Si el color declarado
            // no contrasta con el fondo —o no hay— se sustituye por blanco o negro.
            background != null -> {
                val readable = declaredText
                    ?.takeIf { OfficeColor.contrast(it, background) >= MIN_CONTRAST }
                    ?: background.readableForeground()
                CellStyle(background = background, text = readable)
            }

            // Sin relleno, un texto casi negro o casi blanco es el color por omision
            // dicho de otra forma. No se aplica, para que el modo oscuro siga funcionando.
            declaredText != null && !declaredText.isNearDefault ->
                CellStyle(background = null, text = declaredText)

            else -> Styles.Empty
        }
    }

    private fun XmlPullParser.color(theme: List<Int>): OfficeColor.Rgb? {
        if (attr("auto") == "1") return null
        return OfficeColor.resolve(
            rgb = attr("rgb"),
            indexed = attr("indexed"),
            themeIndex = attr("theme"),
            tint = attr("tint"),
            theme = theme,
        )
    }

    private enum class Section { NONE, FONTS, FILLS, CELL_XFS }

    /** Diferencia de luminancia minima para dar por legible un texto sobre su fondo. */
    private const val MIN_CONTRAST = 0.35f
}
