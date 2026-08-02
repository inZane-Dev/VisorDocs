package com.visordocs.data.markup

import com.visordocs.data.xml.attr
import com.visordocs.data.xml.parserFor
import com.visordocs.data.zip.ZipPackage
import org.xmlpull.v1.XmlPullParser

/**
 * Convierte un .epub a HTML.
 *
 * Un EPUB es un ZIP con capitulos en XHTML, pero llegar a ellos son tres saltos:
 *
 *  1. `META-INF/container.xml` dice donde esta el archivo de paquete (`.opf`).
 *  2. El `.opf` tiene un `<manifest>` con todos los recursos y un `<spine>` con el
 *     **orden de lectura**, que referencia los recursos por id.
 *  3. Cada capitulo es un XHTML con sus rutas relativas a la carpeta del `.opf`.
 *
 * El XHTML de cada capitulo no se inserta tal cual: se pasa por [sanitize], que
 * reescribe solo las etiquetas estructurales conocidas y descarta el resto. Asi no
 * llegan al WebView ni scripts, ni estilos del libro, ni referencias externas.
 */
object EpubMarkup {

    private const val MAX_CHAPTERS = 500

    fun convert(pkg: ZipPackage): Markup {
        val opfPath = findOpfPath(pkg) ?: return Markup.Empty
        val opf = pkg.text(opfPath) ?: return Markup.Empty

        // Las rutas del manifiesto son relativas a la carpeta del .opf.
        val base = opfPath.substringBeforeLast('/', "").let { if (it.isEmpty()) "" else "$it/" }
        val (manifest, spine) = readOpf(opf)

        val chapters = spine.mapNotNull { id -> manifest[id]?.let { base + it } }
            .ifEmpty {
                // Sin spine legible se recurre al orden alfabetico de los XHTML.
                pkg.names("").filter {
                    it.endsWith(".xhtml", true) || it.endsWith(".html", true)
                }
            }

        if (chapters.isEmpty()) return Markup.Empty

        val out = StringBuilder(32 * 1024)
        val truncated = chapters.size > MAX_CHAPTERS

        for (path in chapters.take(MAX_CHAPTERS)) {
            val xhtml = pkg.text(normalize(path)) ?: continue
            val body = sanitize(xhtml)
            if (body.isNotBlank()) out.append(body)
        }

        if (out.isBlank()) return Markup.Empty
        return Markup(body = out.toString(), truncated = truncated)
    }

    private fun findOpfPath(pkg: ZipPackage): String? {
        val container = pkg.text("META-INF/container.xml")
        if (container != null) {
            val parser = parserFor(container)
            while (parser.eventType != XmlPullParser.END_DOCUMENT) {
                if (parser.eventType == XmlPullParser.START_TAG && parser.name == "rootfile") {
                    parser.attr("full-path")?.let { return it }
                }
                parser.next()
            }
        }
        // Algunos EPUB mal empaquetados no traen container.xml.
        return pkg.names("").firstOrNull { it.endsWith(".opf", true) }
    }

    /** Devuelve el manifiesto (id -> ruta) y el orden de lectura del spine. */
    private fun readOpf(opf: String): Pair<Map<String, String>, List<String>> {
        val manifest = HashMap<String, String>()
        val spine = mutableListOf<String>()
        val parser = parserFor(opf)

        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType == XmlPullParser.START_TAG) {
                when (parser.name.substringAfter(':')) {
                    "item" -> {
                        val id = parser.attr("id")
                        val href = parser.attr("href")
                        val type = parser.attr("media-type").orEmpty()
                        // Solo los documentos de contenido; las imagenes y las fuentes no.
                        if (id != null && href != null &&
                            (type.contains("xhtml") || type.contains("html") || type.isEmpty())
                        ) {
                            manifest[id] = href
                        }
                    }

                    "itemref" -> parser.attr("idref")?.let { spine += it }
                }
            }
            parser.next()
        }
        return manifest to spine
    }

    /** Resuelve los `../` que pueden aparecer en las rutas del manifiesto. */
    private fun normalize(path: String): String {
        val parts = ArrayDeque<String>()
        for (segment in path.split('/')) {
            when (segment) {
                "", "." -> Unit
                ".." -> if (parts.isNotEmpty()) parts.removeLast()
                else -> parts.addLast(segment)
            }
        }
        return parts.joinToString("/")
    }

    /**
     * Reescribe un XHTML dejando solo etiquetas estructurales.
     *
     * Es una lista blanca, no una lista negra: lo que no esta reconocido se descarta,
     * incluidos todos los atributos. Con eso desaparecen de golpe los scripts, las
     * hojas de estilo del libro, las imagenes y cualquier referencia a un servidor.
     *
     * Si el XHTML no es XML bien formado (ocurre en EPUB antiguos), el parser falla y se
     * devuelve cadena vacia para que ese capitulo se salte en lugar de tumbar el libro.
     */
    private fun sanitize(xhtml: String): String = runCatching {
        val out = StringBuilder()
        val parser = parserFor(xhtml)

        var inBody = false
        var skipDepth = 0

        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            val name = parser.name?.substringAfter(':')?.lowercase()

            when (parser.eventType) {
                XmlPullParser.START_TAG -> when {
                    name == "body" -> inBody = true
                    // El contenido de estas etiquetas se descarta entero.
                    name in DROP_WITH_CONTENT -> skipDepth++
                    inBody && skipDepth == 0 && name in ALLOWED -> out.append('<').append(name).append('>')
                }

                XmlPullParser.TEXT -> if (inBody && skipDepth == 0) {
                    parser.text?.let { if (it.isNotEmpty()) out.append(it.escapeHtml()) }
                }

                XmlPullParser.END_TAG -> when {
                    name == "body" -> inBody = false
                    name in DROP_WITH_CONTENT -> if (skipDepth > 0) skipDepth--
                    inBody && skipDepth == 0 && name in ALLOWED ->
                        if (name !in VOID) out.append("</").append(name).append('>')
                }
            }
            parser.next()
        }
        out.toString()
    }.getOrDefault("")

    private val ALLOWED = setOf(
        "p", "div", "span", "br", "hr",
        "h1", "h2", "h3", "h4", "h5", "h6",
        "ul", "ol", "li", "dl", "dt", "dd",
        "em", "i", "strong", "b", "u", "s", "sub", "sup", "small", "code", "pre",
        "blockquote", "table", "thead", "tbody", "tr", "th", "td", "caption", "section",
    )

    private val VOID = setOf("br", "hr")

    private val DROP_WITH_CONTENT = setOf("script", "style", "head", "svg", "iframe", "object")
}
