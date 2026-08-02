package com.visordocs.data.markup

import com.visordocs.data.xml.attr
import com.visordocs.data.xml.parserFor
import com.visordocs.data.zip.ZipPackage
import org.xmlpull.v1.XmlPullParser

/**
 * Convierte un .odp (presentacion de LibreOffice/OpenOffice) a HTML: una tarjeta por
 * diapositiva.
 *
 * Aqui el orden si es el del archivo: los `<draw:page>` aparecen en `content.xml` en el
 * orden de la presentacion, sin la indireccion por relaciones que necesita PowerPoint.
 *
 * El titulo se reconoce por el atributo `presentation:class="title"` del marco que lo
 * contiene, que es el equivalente de OpenDocument al marcador de posicion de OOXML.
 */
object OdpMarkup {

    private const val MAX_SLIDES = 500

    fun convert(pkg: ZipPackage, labels: MarkupLabels): Markup {
        val xml = pkg.text("content.xml") ?: return Markup.Empty

        val out = StringBuilder(8 * 1024)
        val parser = parserFor(xml)

        val images = EmbeddedImages(pkg)

        var slides = 0
        var truncated = false

        var title: String? = null
        var paragraphs = mutableListOf<String>()
        var pictures = mutableListOf<String>()
        var frameIsTitle = false
        var inFrame = false
        var buffer = StringBuilder()
        var inParagraph = false

        fun renderSlide() {
            slides++
            if (slides > MAX_SLIDES) {
                truncated = true
                return
            }
            out.append("<section class=\"slide\">\n")
            out.append("<div class=\"slide-number\">")
                .append(labels.slide.escapeHtml()).append(' ').append(slides)
                .append("</div>\n")
            title?.let { out.append("<h2>").append(it.escapeHtml()).append("</h2>\n") }
            paragraphs.forEach { out.append("<p>").append(it.escapeHtml()).append("</p>\n") }
            pictures.forEach { out.append(it).append('\n') }
            out.append("</section>\n")
        }

        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "draw:page" -> {
                        title = null
                        paragraphs = mutableListOf()
                        pictures = mutableListOf()
                    }

                    "draw:image" -> {
                        val tag = images.imgTag(parser.attr("href")?.removePrefix("./"))
                        if (tag.isNotEmpty()) pictures += tag
                    }

                    "draw:frame" -> {
                        inFrame = true
                        frameIsTitle = parser.attr("class")
                            ?.let { it == "title" || it == "subtitle" } == true
                    }

                    "text:p" -> if (inFrame) {
                        buffer = StringBuilder()
                        inParagraph = true
                    }

                    "text:line-break" -> if (inParagraph) buffer.append(' ')
                }

                XmlPullParser.TEXT -> if (inParagraph) {
                    parser.text?.let { buffer.append(it) }
                }

                XmlPullParser.END_TAG -> when (parser.name) {
                    "text:p" -> if (inParagraph) {
                        inParagraph = false
                        val text = buffer.toString().trim()
                        if (text.isNotEmpty()) {
                            if (frameIsTitle && title == null) title = text else paragraphs += text
                        }
                    }

                    "draw:frame" -> {
                        inFrame = false
                        frameIsTitle = false
                    }

                    "draw:page" -> renderSlide()
                }
            }
            if (truncated) break
            parser.next()
        }

        if (slides == 0) return Markup.Empty
        return Markup(body = out.toString(), truncated = truncated)
    }
}
