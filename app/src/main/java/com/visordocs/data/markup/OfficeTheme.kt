package com.visordocs.data.markup

import com.visordocs.data.xml.attr
import com.visordocs.data.xml.parserFor
import com.visordocs.data.zip.ZipPackage
import org.xmlpull.v1.XmlPullParser

/**
 * Paleta de colores del tema del documento.
 *
 * Word y Excel guardan el mismo archivo en rutas distintas, asi que el lector es comun.
 */
internal object OfficeTheme {

    private val SCHEME_ORDER = listOf(
        "dk1", "lt1", "dk2", "lt2",
        "accent1", "accent2", "accent3", "accent4", "accent5", "accent6",
        "hlink", "folHlink",
    )

    /**
     * Devuelve la paleta en el orden que espera [OfficeColor.resolve], o vacia si el
     * documento no trae tema (entonces se usa el de Office por omision).
     *
     * `theme1.xml` declara los colores como `dk1, lt1, dk2, lt2...`, pero la numeracion
     * que usan los documentos intercambia los dos primeros pares: el indice 0 es `lt1`.
     * Sin ese cambio, el texto sale del color del fondo y el fondo del color del texto.
     */
    fun read(pkg: ZipPackage, path: String): List<Int> {
        val xml = pkg.text(path) ?: return emptyList()

        val ordered = mutableListOf<Int>()
        var inScheme = false
        var pending: String? = null

        val parser = parserFor(xml)
        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> when (val name = parser.name) {
                    "clrScheme" -> inScheme = true

                    "srgbClr" -> if (inScheme && pending != null) {
                        parser.attr("val")?.toIntOrNull(16)?.let { ordered += it }
                        pending = null
                    }

                    // El color del sistema se declara por nombre ("windowText") y trae
                    // aparte el ultimo valor concreto que se le calculo.
                    "sysClr" -> if (inScheme && pending != null) {
                        parser.attr("lastClr")?.toIntOrNull(16)?.let { ordered += it }
                        pending = null
                    }

                    else -> if (inScheme && name in SCHEME_ORDER) pending = name
                }

                XmlPullParser.END_TAG -> if (parser.name == "clrScheme") inScheme = false
            }
            parser.next()
        }

        if (ordered.size < SCHEME_ORDER.size) return emptyList()

        // dk1, lt1, dk2, lt2 -> lt1, dk1, lt2, dk2
        return listOf(ordered[1], ordered[0], ordered[3], ordered[2]) + ordered.drop(4)
    }
}
