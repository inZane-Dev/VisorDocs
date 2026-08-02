package com.visordocs.data.ooxml

import com.visordocs.data.xml.attr
import com.visordocs.data.xml.parserFor
import com.visordocs.data.zip.ZipPackage
import org.xmlpull.v1.XmlPullParser

/**
 * Lee un archivo .rels y devuelve id de relacion -> destino.
 *
 * OOXML no referencia las partes por su ruta sino por un identificador (`rId3`) que se
 * resuelve en un archivo aparte. Es lo que permite que el orden de las hojas o de las
 * diapositivas no dependa del nombre de sus archivos.
 */
fun ZipPackage.relationships(path: String): Map<String, String> {
    val xml = text(path) ?: return emptyMap()
    val result = HashMap<String, String>()
    val parser = parserFor(xml)
    while (parser.eventType != XmlPullParser.END_DOCUMENT) {
        if (parser.eventType == XmlPullParser.START_TAG && parser.name == "Relationship") {
            val id = parser.attr("Id")
            val target = parser.attr("Target")
            if (id != null && target != null) result[id] = target
        }
        parser.next()
    }
    return result
}

/**
 * Normaliza el destino de una relacion a una ruta dentro del paquete.
 *
 * Los destinos son relativos a la carpeta del archivo que los declara: para
 * `xl/_rels/workbook.xml.rels` la base es `xl/`, y para
 * `ppt/_rels/presentation.xml.rels` es `ppt/`.
 */
fun resolvePart(target: String, base: String): String =
    when {
        target.startsWith("/") -> target.removePrefix("/")
        target.startsWith(base) -> target
        else -> base + target
    }
