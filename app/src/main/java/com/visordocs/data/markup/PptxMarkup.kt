package com.visordocs.data.markup

import com.visordocs.data.zip.ZipPackage
import com.visordocs.data.xml.attr
import com.visordocs.data.xml.parserFor
import com.visordocs.data.ooxml.relationships
import com.visordocs.data.ooxml.resolvePart
import org.xmlpull.v1.XmlPullParser

/**
 * Convierte un .pptx a HTML: una tarjeta por diapositiva.
 *
 * Se extrae el texto de cada forma (`p:sp`) distinguiendo el titulo por su marcador
 * de posicion (`p:ph type="title"`). El orden real de las diapositivas esta en
 * `ppt/presentation.xml` y se resuelve por relaciones, porque el orden de los
 * archivos `slide1.xml`, `slide2.xml`... no tiene por que ser el de la presentacion.
 *
 * Esto es una aproximacion legible, no una reproduccion: no se dibujan imagenes,
 * fondos, posiciones ni animaciones.
 */
object PptxMarkup {

    private const val MAX_SLIDES = 500

    fun convert(pkg: ZipPackage, labels: MarkupLabels): Markup {
        val slidePaths = orderedSlidePaths(pkg)
        if (slidePaths.isEmpty()) return Markup.Empty

        val out = StringBuilder(8 * 1024)
        val truncated = slidePaths.size > MAX_SLIDES

        val images = EmbeddedImages(pkg)

        slidePaths.take(MAX_SLIDES).forEachIndexed { index, path ->
            val xml = pkg.text(path) ?: return@forEachIndexed
            // Cada diapositiva tiene su propio archivo de relaciones, con las imagenes
            // que usa. `ppt/slides/slide1.xml` -> `ppt/slides/_rels/slide1.xml.rels`.
            val relsPath = path.substringBeforeLast('/') + "/_rels/" +
                path.substringAfterLast('/') + ".rels"
            out.append(renderSlide(xml, index + 1, labels, images, pkg.relationships(relsPath)))
        }

        return Markup(body = out.toString(), truncated = truncated)
    }

    /**
     * Devuelve las rutas de las diapositivas en el orden de la presentacion.
     *
     * Si las relaciones no se pueden leer se recurre a ordenar por el numero del
     * nombre del archivo: hay que hacerlo numericamente, porque alfabeticamente
     * "slide10" iria antes que "slide2".
     */
    private fun orderedSlidePaths(pkg: ZipPackage): List<String> {
        val relations = pkg.relationships("ppt/_rels/presentation.xml.rels")
        val presentation = pkg.text("ppt/presentation.xml")

        if (presentation != null && relations.isNotEmpty()) {
            val ordered = mutableListOf<String>()
            val parser = parserFor(presentation)
            while (parser.eventType != XmlPullParser.END_DOCUMENT) {
                if (parser.eventType == XmlPullParser.START_TAG && parser.name == "p:sldId") {
                    val target = parser.attr("id")?.let { relations[it] }
                        ?: parser.attr("r:id")?.let { relations[it] }
                    if (target != null) ordered += resolvePart(target, base = "ppt/")
                }
                parser.next()
            }
            if (ordered.isNotEmpty()) return ordered
        }

        return pkg.names("ppt/slides/slide")
            .filter { it.endsWith(".xml") }
            .sortedBy { path ->
                path.substringAfterLast("slide").substringBefore(".xml").toIntOrNull()
                    ?: Int.MAX_VALUE
            }
    }

    private fun renderSlide(
        xml: String,
        number: Int,
        labels: MarkupLabels,
        images: EmbeddedImages,
        relations: Map<String, String>,
    ): String {
        var title: String? = null
        val paragraphs = mutableListOf<String>()
        val pictures = mutableListOf<String>()

        val parser = parserFor(xml)
        var placeholder: String? = null
        var inShape = false
        var shapeParagraphs = mutableListOf<String>()
        var currentParagraph = StringBuilder()

        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "p:sp" -> {
                        inShape = true
                        placeholder = null
                        shapeParagraphs = mutableListOf()
                    }

                    "p:ph" -> placeholder = parser.attr("type")
                    "a:p" -> currentParagraph = StringBuilder()

                    "a:t" -> {
                        currentParagraph.append(parser.nextText())
                        continue
                    }

                    "a:br" -> currentParagraph.append(' ')

                    "a:blip" -> {
                        val target = parser.attr("embed")?.let { relations[it] }
                        val tag = images.imgTag(target?.let { resolvePart(it, "ppt/") })
                        if (tag.isNotEmpty()) pictures += tag
                    }
                }

                XmlPullParser.END_TAG -> when (parser.name) {
                    "a:p" -> {
                        val text = currentParagraph.toString().trim()
                        if (text.isNotEmpty() && inShape) shapeParagraphs += text
                    }

                    "p:sp" -> {
                        val isTitle = placeholder == "title" || placeholder == "ctrTitle"
                        if (isTitle && title == null && shapeParagraphs.isNotEmpty()) {
                            title = shapeParagraphs.joinToString(" ")
                        } else {
                            paragraphs += shapeParagraphs
                        }
                        inShape = false
                    }
                }
            }
            parser.next()
        }

        val out = StringBuilder()
        out.append("<section class=\"slide\">\n")
        out.append("<div class=\"slide-number\">")
            .append(labels.slide.escapeHtml()).append(' ').append(number)
            .append("</div>\n")
        title?.let { out.append("<h2>").append(it.escapeHtml()).append("</h2>\n") }
        paragraphs.forEach { out.append("<p>").append(it.escapeHtml()).append("</p>\n") }
        // Las imagenes van al final de la tarjeta: sin las posiciones originales,
        // intercalarlas donde aparecen en el XML no reproduce nada y estorba la lectura.
        pictures.forEach { out.append(it).append('\n') }
        out.append("</section>\n")
        return out.toString()
    }
}
