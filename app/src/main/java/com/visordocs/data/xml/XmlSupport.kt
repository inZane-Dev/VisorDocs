package com.visordocs.data.xml

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.StringReader

/**
 * Parser XML sobre una parte de un contenedor.
 *
 * Los espacios de nombres se dejan **desactivados** a proposito: asi los nombres de
 * etiqueta llegan tal cual aparecen en el archivo (`w:p`, `a:t`, `text:h`), que es como
 * los escriben Word, Excel, PowerPoint y LibreOffice. Activarlos obligaria a resolver
 * prefijos sin ganar nada, porque cada formato usa siempre los mismos.
 */
fun parserFor(xml: String): XmlPullParser =
    Xml.newPullParser().apply {
        setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        setInput(StringReader(xml))
    }

/** Valor de un atributo sin tener en cuenta el prefijo de espacio de nombres. */
fun XmlPullParser.attr(local: String): String? {
    for (i in 0 until attributeCount) {
        val name = getAttributeName(i)
        if (name == local || name.substringAfter(':') == local) return getAttributeValue(i)
    }
    return null
}

/**
 * Interpreta los interruptores de OOXML (`w:b`, `w:i`...). La etiqueta presente
 * significa "activado" salvo que traiga `val` explicitamente a 0/false.
 */
fun XmlPullParser.isToggleOn(): Boolean =
    when (attr("val")?.lowercase()) {
        "0", "false", "off" -> false
        else -> true
    }
