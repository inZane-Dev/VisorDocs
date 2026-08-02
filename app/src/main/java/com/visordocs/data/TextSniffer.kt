package com.visordocs.data

import com.visordocs.model.DocumentType
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction

/**
 * Ultimo escalon de la deteccion: adivinar por el contenido si un archivo es texto.
 *
 * Hace falta porque un archivo sin extension y con MIME generico no deja ninguna otra
 * pista. Es el caso de lo que llega por mensajeria con nombres opacos, o de los
 * archivos de configuracion de toda la vida, que sencillamente no tienen extension.
 *
 * El criterio es deliberadamente **conservador**: ante la duda se prefiere decir "no se
 * que es" antes que mostrar un binario como si fuera texto, que llenaria la pantalla de
 * simbolos sin sentido. Por eso basta un solo byte sospechoso para descartarlo.
 *
 * Es una funcion pura sobre bytes, sin nada de Android, para poder probarla en la JVM.
 */
object TextSniffer {

    /** Cuantos bytes del principio se examinan. */
    const val SAMPLE_BYTES = 1024

    /**
     * Los unicos caracteres de control que aparecen de forma legitima en un texto:
     * tabulador, salto de linea, retorno de carro y avance de pagina.
     */
    private val ALLOWED_CONTROL = setOf(0x09, 0x0A, 0x0D, 0x0C)

    /**
     * Devuelve el formato deducido del contenido, o null si no parece texto.
     *
     * @param sample primeros bytes del archivo.
     * @param length cuantos de esos bytes son validos.
     */
    fun sniff(sample: ByteArray, length: Int = sample.size): DocumentType? {
        if (length <= 0) return null

        for (i in 0 until length) {
            val byte = sample[i].toInt() and 0xFF
            // Un byte cero es la senal mas fiable de que algo es binario: ningun texto
            // en UTF-8 lo contiene.
            if (byte == 0) return null
            if (byte < 0x20 && byte !in ALLOWED_CONTROL) return null
        }

        val text = decodeUtf8(sample, length) ?: return null
        if (text.isBlank()) return null

        return classify(text)
    }

    /**
     * Decodifica como UTF-8 estricto, o null si no lo es.
     *
     * La clave esta en `endOfInput = false`. La muestra corta el archivo por donde toque,
     * asi que lo normal es que el ultimo caracter multibyte quede partido; con ese
     * parametro el decodificador entiende que **faltan datos** y se detiene sin error,
     * mientras que una secuencia de verdad invalida si se reporta como tal.
     *
     * Esa distincion importa: recortar unos bytes del final "por si acaso" dejaria pasar
     * archivos binarios sin mas que amputarles la parte que delata que lo son.
     */
    private fun decodeUtf8(sample: ByteArray, length: Int): String? {
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)

        val input = ByteBuffer.wrap(sample, 0, length)
        val output = CharBuffer.allocate(length + 1)

        val result = runCatching { decoder.decode(input, output, false) }.getOrNull()
            ?: return null
        if (result.isError) return null

        output.flip()
        return output.toString()
    }

    /**
     * Distingue los formatos de texto que tienen visor propio.
     *
     * Se mira solo el principio del archivo, que es donde estos tres se declaran.
     */
    private fun classify(text: String): DocumentType {
        val head = text.trimStart()
        val lower = head.lowercase()

        return when {
            head.startsWith("{\\rtf") -> DocumentType.RTF
            lower.startsWith("<!doctype html") || lower.startsWith("<html") -> DocumentType.HTML
            // Un SVG suele abrir con la declaracion XML, asi que la etiqueta puede venir
            // unas lineas mas abajo.
            lower.contains("<svg") -> DocumentType.SVG
            lower.contains("<html") -> DocumentType.HTML
            else -> DocumentType.PLAIN_TEXT
        }
    }
}
