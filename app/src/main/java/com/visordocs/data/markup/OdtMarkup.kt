package com.visordocs.data.markup

import com.visordocs.data.xml.attr
import com.visordocs.data.xml.parserFor
import com.visordocs.data.zip.ZipPackage
import org.xmlpull.v1.XmlPullParser

/**
 * Convierte un .odt (documento de LibreOffice/OpenOffice) a HTML.
 *
 * OpenDocument tambien es un ZIP con XML, pero **no** comparte esquema con OOXML: el
 * texto vive en `content.xml` con etiquetas `text:p` y `text:h` en lugar de `w:p`, y el
 * formato se referencia por nombre de estilo en vez de marcarse en el propio tramo
 * (ver [OdfStyles]). Por eso necesita un convertidor aparte y no se puede reutilizar
 * [DocxMarkup].
 *
 * Se conserva: encabezados con su nivel, negrita, cursiva, subrayado, tachado, listas
 * (incluidas anidadas) y tablas. No se conservan imagenes ni colores.
 */
object OdtMarkup {

    private const val MAX_PARAGRAPHS = 20_000

    fun convert(pkg: ZipPackage): Markup {
        val xml = pkg.text("content.xml") ?: return Markup.Empty
        val styles = OdfStyles.read(xml)
        val images = EmbeddedImages(pkg)

        val out = StringBuilder(8 * 1024)
        val parser = parserFor(xml)

        // Solo se convierte lo que hay dentro de <office:text>; antes viene la tabla
        // de estilos, que no debe acabar en la salida.
        var inBody = false
        var listDepth = 0
        var paragraphs = 0
        var truncated = false

        // Tramo de texto en curso y estilos de span abiertos.
        var buffer = StringBuilder()
        var heading: Int? = null
        var inParagraph = false
        val spanStack = ArrayDeque<OdfTextStyle>()

        fun flushParagraph() {
            if (!inParagraph) return
            inParagraph = false
            paragraphs++
            if (paragraphs > MAX_PARAGRAPHS) {
                truncated = true
                return
            }
            val content = buffer.toString()
            val level = heading
            out.append(
                when {
                    level != null && level in 1..6 -> "<h$level>${content.ifBlank { "&nbsp;" }}</h$level>\n"
                    listDepth > 0 -> "<li>${content.ifBlank { "&nbsp;" }}</li>\n"
                    content.isBlank() -> "<p>&nbsp;</p>\n"
                    else -> "<p>$content</p>\n"
                },
            )
            heading = null
        }

        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "office:text" -> inBody = true

                    "text:h", "text:p" -> if (inBody) {
                        buffer = StringBuilder()
                        inParagraph = true
                        heading = if (parser.name == "text:h") {
                            parser.attr("outline-level")?.toIntOrNull() ?: 1
                        } else {
                            null
                        }
                    }

                    "text:span" -> if (inBody) {
                        spanStack.addLast(
                            parser.attr("style-name")?.let { styles[it] } ?: OdfTextStyle(),
                        )
                    }

                    "text:list" -> if (inBody) {
                        flushParagraph()
                        listDepth++
                        out.append("<ul>\n")
                    }

                    "text:line-break" -> if (inBody) buffer.append("<br>")
                    "text:tab" -> if (inBody) buffer.append("&emsp;")

                    // OpenDocument nombra la imagen directamente, sin la indireccion por
                    // relaciones que usa OOXML.
                    "draw:image" -> if (inBody) {
                        buffer.append(images.imgTag(parser.attr("href")?.removePrefix("./")))
                    }

                    // <text:s/> son espacios consecutivos, que el XML no puede llevar
                    // literalmente sin que el navegador los colapse.
                    "text:s" -> if (inBody) {
                        val count = parser.attr("c")?.toIntOrNull() ?: 1
                        repeat(count.coerceIn(1, 200)) { buffer.append("&nbsp;") }
                    }

                    "table:table" -> if (inBody) {
                        flushParagraph()
                        out.append("<div class=\"scroll-x\"><table>\n")
                    }

                    "table:table-row" -> if (inBody) out.append("<tr>")
                    "table:table-cell" -> if (inBody) out.append("<td>")
                }

                XmlPullParser.TEXT -> if (inBody && inParagraph) {
                    val text = parser.text
                    if (!text.isNullOrEmpty()) {
                        buffer.append(OdfStyles.wrap(text.escapeHtml(), spanStack.toList()))
                    }
                }

                XmlPullParser.END_TAG -> when (parser.name) {
                    "office:text" -> inBody = false
                    "text:h", "text:p" -> if (inBody) flushParagraph()
                    "text:span" -> if (inBody && spanStack.isNotEmpty()) spanStack.removeLast()

                    "text:list" -> if (inBody) {
                        flushParagraph()
                        if (listDepth > 0) listDepth--
                        out.append("</ul>\n")
                    }

                    "table:table-cell" -> if (inBody) {
                        flushParagraph()
                        out.append("</td>")
                    }

                    "table:table-row" -> if (inBody) out.append("</tr>\n")
                    "table:table" -> if (inBody) out.append("</table></div>\n")
                }
            }
            if (truncated) break
            parser.next()
        }

        return Markup(body = out.toString(), truncated = truncated)
    }
}
