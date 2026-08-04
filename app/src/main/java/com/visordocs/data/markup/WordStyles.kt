package com.visordocs.data.markup

import com.visordocs.data.xml.attr
import com.visordocs.data.xml.parserFor
import com.visordocs.data.zip.ZipPackage
import org.xmlpull.v1.XmlPullParser

/**
 * Colores que un tramo de texto hereda de los estilos del documento.
 *
 * Casi ningun Word real pinta el color en el propio texto. Lo normal es que un titulo sea
 * azul porque el estilo "Heading1" lo dice, y ese estilo a su vez herede de otro. Leyendo
 * solo el `<w:rPr>` del tramo, un documento con plantilla corporativa sale entero en
 * negro.
 *
 * La resolucion va en cascada, de menos a mas prioridad:
 *
 * 1. `docDefaults` — lo que vale para todo el documento.
 * 2. Estilo de parrafo (`w:pStyle`), siguiendo su cadena de `w:basedOn`.
 * 3. Estilo de caracter (`w:rStyle`), con su propia cadena.
 * 4. Lo que el tramo declare directamente.
 *
 * Las cadenas de `basedOn` se resuelven una sola vez al abrir el documento y quedan ya
 * aplanadas, para no recorrerlas en cada tramo de texto.
 */
internal object WordStyles {

    /** Color de letra y resaltado que aporta un estilo. Nulo es "no dice nada". */
    data class RunColor(
        val text: OfficeColor.Rgb? = null,
        val highlight: OfficeColor.Rgb? = null,
    ) {
        val isEmpty: Boolean get() = text == null && highlight == null

        /** Combina con [other] dando prioridad a [other], que es el mas especifico. */
        fun overriddenBy(other: RunColor) = RunColor(
            text = other.text ?: text,
            highlight = other.highlight ?: highlight,
        )
    }

    class Resolved(
        private val byStyleId: Map<String, RunColor>,
        val defaults: RunColor,
    ) {
        fun of(styleId: String?): RunColor =
            styleId?.let { byStyleId[it] } ?: RunColor()

        val isEmpty: Boolean get() = byStyleId.isEmpty() && defaults.isEmpty

        companion object {
            val None = Resolved(emptyMap(), RunColor())
        }
    }

    fun read(pkg: ZipPackage): Resolved {
        val xml = pkg.text("word/styles.xml") ?: return Resolved.None
        val theme = OfficeTheme.read(pkg, "word/theme/theme1.xml")

        val own = HashMap<String, RunColor>()
        val basedOn = HashMap<String, String>()
        var defaults = RunColor()

        // Un <w:rPr> aparece tanto en docDefaults como dentro de cada <w:style>, y hay
        // que saber a quien pertenece el <w:color> que llega.
        var inDocDefaults = false
        var currentId: String? = null
        var inRunProps = false
        var current = RunColor()

        val parser = parserFor(xml)
        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "w:docDefaults" -> inDocDefaults = true

                    "w:style" -> {
                        currentId = parser.attr("styleId")
                        current = RunColor()
                    }

                    "w:basedOn" -> currentId?.let { id ->
                        parser.attr("val")?.let { basedOn[id] = it }
                    }

                    "w:rPr" -> inRunProps = true

                    "w:color" -> if (inRunProps) {
                        current = current.copy(text = parser.runColor(theme))
                    }

                    "w:highlight" -> if (inRunProps) {
                        current = current.copy(highlight = OfficeColor.highlight(parser.attr("val")))
                    }
                }

                XmlPullParser.END_TAG -> when (parser.name) {
                    "w:rPr" -> {
                        inRunProps = false
                        if (inDocDefaults && currentId == null) defaults = current
                    }

                    "w:docDefaults" -> {
                        inDocDefaults = false
                        current = RunColor()
                    }

                    "w:style" -> {
                        currentId?.let { if (!current.isEmpty) own[it] = current }
                        currentId = null
                        current = RunColor()
                    }
                }
            }
            parser.next()
        }

        return flatten(own, basedOn, defaults)
    }

    /**
     * Aplana las cadenas de `basedOn`, de la raiz hacia abajo.
     *
     * El limite de saltos no es paranoia: `basedOn` es una referencia por nombre y nada
     * impide que dos estilos se apunten mutuamente. Un documento asi colgaria la app al
     * abrirlo, y aqui simplemente deja de heredar.
     */
    private fun flatten(
        own: Map<String, RunColor>,
        basedOn: Map<String, String>,
        defaults: RunColor,
    ): Resolved {
        if (own.isEmpty() && defaults.isEmpty) return Resolved.None

        val resolved = HashMap<String, RunColor>(own.size)
        val ids = own.keys + basedOn.keys

        for (id in ids) {
            // Se recorre hasta la raiz y luego se aplica de arriba abajo, para que lo mas
            // especifico —el propio estilo— tenga la ultima palabra.
            val chain = ArrayDeque<String>()
            var cursor: String? = id
            var hops = 0
            while (cursor != null && hops < MAX_INHERITANCE_HOPS && cursor !in chain) {
                chain.addFirst(cursor)
                cursor = basedOn[cursor]
                hops++
            }

            var value = RunColor()
            for (step in chain) {
                own[step]?.let { value = value.overriddenBy(it) }
            }
            if (!value.isEmpty) resolved[id] = value
        }

        return Resolved(resolved, defaults)
    }

    /**
     * `w:val` trae el color en hexadecimal, o "auto" para dejarlo a la aplicacion. Si no
     * hay valor directo puede haber uno del tema, que Word escribe por nombre.
     */
    private fun XmlPullParser.runColor(theme: List<Int>): OfficeColor.Rgb? {
        val value = attr("val")
        if (value != null && value != "auto") {
            return OfficeColor.resolve(rgb = value, indexed = null, themeIndex = null, tint = null)
        }
        return OfficeColor.themeByName(attr("themeColor"), attr("themeTint"), theme)
    }

    private const val MAX_INHERITANCE_HOPS = 32
}
