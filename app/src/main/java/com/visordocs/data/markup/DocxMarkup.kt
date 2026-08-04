package com.visordocs.data.markup

import com.visordocs.data.zip.ZipPackage
import com.visordocs.data.xml.attr
import com.visordocs.data.xml.isToggleOn
import com.visordocs.data.xml.parserFor
import com.visordocs.data.ooxml.relationships
import com.visordocs.data.ooxml.resolvePart
import org.xmlpull.v1.XmlPullParser

/**
 * Convierte el cuerpo de un .docx a HTML.
 *
 * Se lee `word/document.xml`, que es donde Word guarda el texto. El recorrido es en
 * streaming con XmlPullParser: nunca se construye un arbol completo, asi que un
 * documento largo no multiplica la memoria.
 *
 * Lo que se conserva: parrafos, encabezados, negrita, cursiva, subrayado, tachado,
 * super/subindice, alineacion, saltos de linea, listas y tablas.
 *
 * Lo que no: imagenes incrustadas, numeracion real de las listas (todas salen como
 * vinetas porque el formato guarda el estilo en `numbering.xml`, otro archivo con su
 * propia maquinaria), colores y fuentes concretas. Es un visor, no un editor.
 */
object DocxMarkup {

    private const val MAX_PARAGRAPHS = 20_000

    /** Word admite nueve niveles de lista; mas alla no se anida para no perder el hilo. */
    private const val MAX_LIST_DEPTH = 8

    fun convert(pkg: ZipPackage): Markup {
        val xml = pkg.text("word/document.xml") ?: return Markup.Empty
        val numbering = WordNumbering.read(pkg)
        // Casi ningun documento pinta el color en el propio texto: lo hereda del estilo.
        val styles = WordStyles.read(pkg)
        val images = EmbeddedImages(pkg)
        // Las imagenes se referencian por id de relacion, igual que las hojas o las
        // diapositivas: el XML nunca nombra el archivo directamente.
        val relations = pkg.relationships("word/_rels/document.xml.rels")

        val out = StringBuilder(8 * 1024)
        val parser = parserFor(xml)

        // Estado del parrafo en curso.
        var paragraphStyle: String? = null
        var alignment: String? = null
        var isListItem = false
        var listNumId: Int? = null
        var listLevel = 0
        var inNumPr = false
        // Etiquetas de lista abiertas, una por nivel de anidamiento.
        val openLists = ArrayDeque<String>()
        var paragraphContent = StringBuilder()
        var paragraphs = 0
        var truncated = false

        // Estado del run en curso (un tramo de texto con formato homogeneo).
        var bold = false
        var italic = false
        var underline = false
        var strike = false
        var vertAlign: String? = null
        var runStyle: String? = null
        var textColor: OfficeColor.Rgb? = null
        var highlight: OfficeColor.Rgb? = null

        // Dentro de <w:rPr> las etiquetas w:b / w:i describen el formato. Fuera de el
        // esas mismas etiquetas pueden aparecer con otro significado, asi que se
        // vigila si estamos dentro del bloque de propiedades.
        var inRunProps = false

        fun closeLists(downTo: Int = 0) {
            while (openLists.size > downTo) {
                out.append("</").append(openLists.removeLast()).append(">\n")
            }
        }

        /** Deja abiertas exactamente [level] + 1 listas, siendo la mas interna de [tag]. */
        fun openListsTo(level: Int, tag: String) {
            closeLists(level + 1)
            // Si en este nivel ya hay una lista pero de otro tipo, se cierra y se abre la
            // que toca: Word permite pasar de vinetas a numeros dentro del mismo bloque.
            if (openLists.size == level + 1 && openLists.last() != tag) {
                out.append("</").append(openLists.removeLast()).append(">\n")
            }
            while (openLists.size < level + 1) {
                out.append("<").append(tag).append(">\n")
                openLists.addLast(tag)
            }
        }

        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "w:p" -> {
                        paragraphStyle = null
                        alignment = null
                        isListItem = false
                        listNumId = null
                        listLevel = 0
                        paragraphContent = StringBuilder()
                    }

                    "w:pStyle" -> paragraphStyle = parser.attr("val")
                    "w:jc" -> alignment = parser.attr("val")

                    "w:numPr" -> {
                        isListItem = true
                        inNumPr = true
                    }

                    // Solo dentro de <w:numPr>: estas dos etiquetas tambien aparecen en
                    // otros contextos donde no describen una lista.
                    "w:ilvl" -> if (inNumPr) {
                        listLevel = parser.attr("val")?.toIntOrNull()?.coerceIn(0, MAX_LIST_DEPTH) ?: 0
                    }

                    "w:numId" -> if (inNumPr) listNumId = parser.attr("val")?.toIntOrNull()

                    // El formato se reinicia al abrir el run, no al abrir <w:rPr>.
                    // Un run sin <w:rPr> es texto sin formato, y si no se limpiara aqui
                    // arrastraria el formato del run anterior: la negrita se derramaria
                    // sobre el texto siguiente.
                    "w:r" -> {
                        bold = false
                        italic = false
                        underline = false
                        strike = false
                        vertAlign = null
                        runStyle = null
                        textColor = null
                        highlight = null
                    }

                    "w:rPr" -> inRunProps = true

                    // Estilo de caracter: pesa mas que el del parrafo y menos que lo
                    // que el propio tramo declare.
                    "w:rStyle" -> if (inRunProps) runStyle = parser.attr("val")

                    "w:b" -> if (inRunProps) bold = parser.isToggleOn()
                    "w:i" -> if (inRunProps) italic = parser.isToggleOn()
                    "w:u" -> if (inRunProps) underline = parser.attr("val") != "none"
                    "w:strike" -> if (inRunProps) strike = parser.isToggleOn()
                    "w:vertAlign" -> if (inRunProps) vertAlign = parser.attr("val")

                    // El color declarado puede ser "auto", que significa "el que decida
                    // la aplicacion": ahi manda el tema y no se toca nada.
                    "w:color" -> if (inRunProps) textColor = parser.runColor()
                    "w:highlight" -> if (inRunProps) highlight = OfficeColor.highlight(parser.attr("val"))

                    "w:t" -> {
                        val text = parser.nextText()
                        if (text.isNotEmpty()) {
                            // De menos a mas especifico: valores del documento, estilo de
                            // parrafo, estilo de caracter y por ultimo lo que declare el
                            // propio tramo.
                            val effective = styles.defaults
                                .overriddenBy(styles.of(paragraphStyle))
                                .overriddenBy(styles.of(runStyle))
                                .overriddenBy(
                                    WordStyles.RunColor(text = textColor, highlight = highlight),
                                )

                            paragraphContent.append(
                                wrapRun(
                                    text.escapeHtml(),
                                    bold,
                                    italic,
                                    underline,
                                    strike,
                                    vertAlign,
                                    effective.text,
                                    effective.highlight,
                                ),
                            )
                        }
                        // nextText() ya consumio el END_TAG; continue evita avanzar de mas.
                        continue
                    }

                    "w:br" -> paragraphContent.append("<br>")
                    "w:tab" -> paragraphContent.append("&emsp;")

                    // <a:blip> es donde el dibujo apunta a su imagen.
                    "a:blip" -> {
                        val target = parser.attr("embed")?.let { relations[it] }
                        paragraphContent.append(images.imgTag(target?.let { resolvePart(it, "word/") }))
                    }

                    "w:tbl" -> {
                        closeLists()
                        out.append("<div class=\"scroll-x\"><table>\n")
                    }

                    "w:tr" -> out.append("<tr>")
                    "w:tc" -> out.append("<td>")
                }

                XmlPullParser.END_TAG -> when (parser.name) {
                    "w:rPr" -> inRunProps = false
                    "w:numPr" -> inNumPr = false

                    "w:p" -> {
                        paragraphs++
                        if (paragraphs > MAX_PARAGRAPHS) {
                            truncated = true
                        } else {
                            val content = paragraphContent.toString()
                            if (isListItem) {
                                val tag = when (numbering.markerFor(listNumId, listLevel)) {
                                    WordNumbering.Marker.ORDERED -> "ol"
                                    WordNumbering.Marker.BULLET -> "ul"
                                }
                                openListsTo(listLevel, tag)
                                out.append("<li>").append(content.ifBlank { "&nbsp;" }).append("</li>\n")
                            } else {
                                closeLists()
                                out.append(paragraphHtml(content, paragraphStyle, alignment))
                            }
                        }
                    }

                    "w:tc" -> out.append("</td>")
                    "w:tr" -> out.append("</tr>\n")
                    "w:tbl" -> out.append("</table></div>\n")
                }
            }
            if (paragraphs > MAX_PARAGRAPHS) break
            parser.next()
        }

        closeLists()
        return Markup(body = out.toString(), truncated = truncated)
    }

    private fun wrapRun(
        text: String,
        bold: Boolean,
        italic: Boolean,
        underline: Boolean,
        strike: Boolean,
        vertAlign: String?,
        textColor: OfficeColor.Rgb?,
        highlight: OfficeColor.Rgb?,
    ): String {
        var html = text
        if (bold) html = "<strong>$html</strong>"
        if (italic) html = "<em>$html</em>"
        if (underline) html = "<u>$html</u>"
        if (strike) html = "<s>$html</s>"
        when (vertAlign) {
            "superscript" -> html = "<sup>$html</sup>"
            "subscript" -> html = "<sub>$html</sub>"
        }

        colorStyle(textColor, highlight)?.let { style ->
            html = "<span style=\"$style\">$html</span>"
        }
        return html
    }

    /**
     * Estilo CSS del tramo, o `null` si el documento no aporta color.
     *
     * Con resaltado, el texto lleva SIEMPRE un color explicito: el resaltado de Word es
     * un fondo solido y claro (amarillo, cian), asi que sin fijar la letra el tema oscuro
     * la pintaria blanca sobre amarillo y no se leeria nada.
     *
     * Sin resaltado, un color casi negro o casi blanco no se aplica: es el color por
     * omision escrito de otra forma, y respetarlo romperia el modo oscuro.
     */
    private fun colorStyle(textColor: OfficeColor.Rgb?, highlight: OfficeColor.Rgb?): String? {
        if (highlight != null) {
            val readable = textColor
                ?.takeIf { OfficeColor.contrast(it, highlight) >= MIN_CONTRAST }
                ?: highlight.readableForeground()
            return "background:${highlight.toCss()};color:${readable.toCss()};"
        }

        if (textColor != null && !textColor.isNearDefault) {
            return "color:${textColor.toCss()};"
        }
        return null
    }

    /**
     * `<w:color w:val="auto"/>` significa "que decida la aplicacion", y ahi manda el tema.
     *
     * Cuando no hay valor directo puede haber uno del tema, que Word escribe por NOMBRE
     * (`w:themeColor="accent1"`) y no por indice como las hojas de calculo.
     */
    private fun XmlPullParser.runColor(): OfficeColor.Rgb? {
        val value = attr("val")
        if (value != null && value != "auto") {
            return OfficeColor.resolve(rgb = value, indexed = null, themeIndex = null, tint = null)
        }
        return OfficeColor.themeByName(attr("themeColor"), attr("themeTint"))
    }

    /** Diferencia de luminancia minima para dar por legible un texto sobre su fondo. */
    private const val MIN_CONTRAST = 0.35f

    /**
     * Traduce el estilo de parrafo de Word a una etiqueta HTML.
     *
     * Los identificadores de estilo suelen venir en ingles ("Heading1") incluso en
     * un Word en espanol, pero no siempre: algunas versiones guardan "Ttulo1". Se
     * aceptan las dos formas.
     */
    private fun paragraphHtml(content: String, style: String?, alignment: String?): String {
        val cssClass = when (alignment) {
            "center" -> " class=\"center\""
            "right", "end" -> " class=\"right\""
            "both", "distribute" -> " class=\"justify\""
            else -> ""
        }

        if (content.isBlank()) return "<p$cssClass>&nbsp;</p>\n"

        val normalized = style?.lowercase()?.replace(" ", "") ?: ""
        val level = when {
            normalized.startsWith("heading") -> normalized.removePrefix("heading").toIntOrNull()
            normalized.startsWith("ttulo") -> normalized.removePrefix("ttulo").toIntOrNull()
            normalized.startsWith("titulo") -> normalized.removePrefix("titulo").toIntOrNull()
            else -> null
        }

        return when {
            normalized == "title" -> "<h1$cssClass>$content</h1>\n"
            normalized == "subtitle" -> "<h3$cssClass>$content</h3>\n"
            level != null && level in 1..6 -> "<h$level$cssClass>$content</h$level>\n"
            normalized.contains("quote") -> "<blockquote$cssClass>$content</blockquote>\n"
            else -> "<p$cssClass>$content</p>\n"
        }
    }
}
