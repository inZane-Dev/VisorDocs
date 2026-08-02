package com.visordocs.data.markup

import com.visordocs.data.xml.attr
import com.visordocs.data.xml.parserFor
import com.visordocs.data.zip.ZipPackage
import org.xmlpull.v1.XmlPullParser

/**
 * Averigua si una lista de Word es de vinetas o numerada.
 *
 * El parrafo solo dice a que lista pertenece (`w:numId`) y en que nivel esta (`w:ilvl`);
 * el aspecto vive en otro archivo, `word/numbering.xml`. Y no de forma directa: el
 * `numId` apunta a una definicion concreta, que a su vez remite a una definicion
 * abstracta, y es ahi donde por fin figura el formato de cada nivel.
 *
 * Sin recorrer esa cadena no hay manera de distinguir una lista numerada de una con
 * vinetas, que es justo lo que pasaba antes: todas salian como vinetas.
 */
object WordNumbering {

    /** Como se marca cada nivel de una lista. */
    enum class Marker { BULLET, ORDERED }

    class Numbering(
        private val abstractByNum: Map<Int, Int>,
        private val markersByAbstract: Map<Int, Map<Int, Marker>>,
    ) {
        /**
         * Marcador del nivel [level] de la lista [numId].
         *
         * Ante cualquier duda se devuelve vineta: es lo que menos molesta si la
         * definicion falta o es rara, porque una lista numerada mostrada con vinetas se
         * sigue leyendo, mientras que numerar algo que no lo es induce a error.
         */
        fun markerFor(numId: Int?, level: Int): Marker {
            val abstractId = abstractByNum[numId] ?: return Marker.BULLET
            return markersByAbstract[abstractId]?.get(level) ?: Marker.BULLET
        }

        companion object {
            val Empty = Numbering(emptyMap(), emptyMap())
        }
    }

    fun read(pkg: ZipPackage): Numbering {
        val xml = pkg.text("word/numbering.xml") ?: return Numbering.Empty

        val abstractByNum = HashMap<Int, Int>()
        val markersByAbstract = HashMap<Int, MutableMap<Int, Marker>>()

        val parser = parserFor(xml)

        var currentAbstractId: Int? = null
        var currentLevel: Int? = null
        var currentNumId: Int? = null

        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "w:abstractNum" -> {
                        currentAbstractId = parser.attr("abstractNumId")?.toIntOrNull()
                        currentNumId = null
                    }

                    "w:lvl" -> currentLevel = parser.attr("ilvl")?.toIntOrNull()

                    "w:numFmt" -> {
                        val abstractId = currentAbstractId
                        val level = currentLevel
                        if (abstractId != null && level != null) {
                            val format = parser.attr("val").orEmpty()
                            markersByAbstract
                                .getOrPut(abstractId) { HashMap() }[level] = markerFor(format)
                        }
                    }

                    // <w:num> es la definicion concreta que referencian los parrafos.
                    "w:num" -> {
                        currentNumId = parser.attr("numId")?.toIntOrNull()
                        currentAbstractId = null
                    }

                    "w:abstractNumId" -> {
                        val numId = currentNumId
                        val target = parser.attr("val")?.toIntOrNull()
                        if (numId != null && target != null) abstractByNum[numId] = target
                    }
                }

                XmlPullParser.END_TAG -> when (parser.name) {
                    "w:lvl" -> currentLevel = null
                    "w:abstractNum" -> currentAbstractId = null
                    "w:num" -> currentNumId = null
                }
            }
            parser.next()
        }

        return Numbering(abstractByNum, markersByAbstract)
    }

    /**
     * Word tiene una decena de formatos numerados (arabigo, romano, letras...). Aqui solo
     * importa si lleva numero o no: el estilo concreto de la numeracion lo pone el
     * navegador con el `<ol>`, y reproducirlo exactamente no aporta a la lectura.
     */
    private fun markerFor(format: String): Marker = when (format) {
        "bullet", "none" -> Marker.BULLET
        else -> Marker.ORDERED
    }
}
