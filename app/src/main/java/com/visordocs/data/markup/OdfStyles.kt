package com.visordocs.data.markup

import com.visordocs.data.xml.attr
import com.visordocs.data.xml.parserFor
import org.xmlpull.v1.XmlPullParser

/** Formato de un tramo de texto en OpenDocument. */
data class OdfTextStyle(
    val bold: Boolean = false,
    val italic: Boolean = false,
    val underline: Boolean = false,
    val strike: Boolean = false,
) {
    val isPlain: Boolean get() = !bold && !italic && !underline && !strike
}

/**
 * Lee la tabla de estilos de un `content.xml` de OpenDocument.
 *
 * LibreOffice no marca el formato en el propio texto como hace Word. En su lugar
 * declara estilos con nombre en `<office:automatic-styles>` y luego los referencia:
 * `<text:span text:style-name="T3">`. Asi que para saber si algo va en negrita hay que
 * leer primero esa tabla.
 *
 * Solo interesan los estilos de familia `text`; los de parrafo y tabla describen
 * margenes y bordes, que este visor no reproduce.
 */
object OdfStyles {

    fun read(contentXml: String): Map<String, OdfTextStyle> {
        val styles = HashMap<String, OdfTextStyle>()
        val parser = parserFor(contentXml)

        var currentName: String? = null
        var currentFamily: String? = null
        var current = OdfTextStyle()

        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "style:style" -> {
                        currentName = parser.attr("name")
                        currentFamily = parser.attr("family")
                        current = OdfTextStyle()
                    }

                    "style:text-properties" -> current = OdfTextStyle(
                        bold = parser.attr("font-weight")?.let { it == "bold" || it == "bolder" } == true,
                        italic = parser.attr("font-style") == "italic",
                        // El subrayado y el tachado se declaran por su "estilo de linea";
                        // `none` significa desactivado.
                        underline = parser.attr("text-underline-style")
                            ?.let { it.isNotEmpty() && it != "none" } == true,
                        strike = parser.attr("text-line-through-style")
                            ?.let { it.isNotEmpty() && it != "none" } == true,
                    )
                }

                XmlPullParser.END_TAG -> if (parser.name == "style:style") {
                    val name = currentName
                    if (name != null && currentFamily == "text" && !current.isPlain) {
                        styles[name] = current
                    }
                    currentName = null
                    currentFamily = null
                }
            }
            parser.next()
        }
        return styles
    }

    /**
     * Envuelve el texto con el formato acumulado de los estilos activos.
     *
     * Los `<text:span>` se anidan, asi que el formato que se aplica es la union de
     * todos los que hay abiertos en ese momento.
     */
    fun wrap(text: String, active: List<OdfTextStyle>): String {
        if (active.isEmpty()) return text
        val bold = active.any { it.bold }
        val italic = active.any { it.italic }
        val underline = active.any { it.underline }
        val strike = active.any { it.strike }

        var html = text
        if (bold) html = "<strong>$html</strong>"
        if (italic) html = "<em>$html</em>"
        if (underline) html = "<u>$html</u>"
        if (strike) html = "<s>$html</s>"
        return html
    }
}
