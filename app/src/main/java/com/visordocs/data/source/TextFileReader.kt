package com.visordocs.data.source

import java.io.InputStream
import java.nio.charset.Charset

/**
 * Lectura de archivos de texto plano.
 *
 * Con un tope de tamano: un .log de varios cientos de megas metido en un `String`
 * tumbaria el proceso. Al superarlo se devuelve lo leido y se marca como truncado,
 * que es mas util que negarse a abrirlo.
 */
object TextFileReader {

    private const val MAX_BYTES = 4 * 1024 * 1024

    data class Result(val text: String, val truncated: Boolean)

    /**
     * @param charset con que se interpretan los bytes. UTF-8 sirve para texto, CSV y
     *   SVG. RTF necesita [Charsets.ISO_8859_1]: es un formato de 8 bits, y decodificarlo
     *   como UTF-8 destrozaria cualquier byte alto literal en lugar de dejarlo pasar
     *   intacto hasta el convertidor.
     */
    fun read(stream: InputStream, charset: Charset = Charsets.UTF_8): Result {
        val bytes = stream.use { input ->
            val buffer = ByteArray(MAX_BYTES + 1)
            var total = 0
            while (total < buffer.size) {
                val read = input.read(buffer, total, buffer.size - total)
                if (read <= 0) break
                total += read
            }
            buffer.copyOf(total)
        }

        val truncated = bytes.size > MAX_BYTES
        val usable = if (truncated) bytes.copyOf(MAX_BYTES) else bytes

        // Se quita el BOM de UTF-8 si viene: en pantalla apareceria como un simbolo raro.
        val start = if (
            usable.size >= 3 &&
            usable[0] == 0xEF.toByte() &&
            usable[1] == 0xBB.toByte() &&
            usable[2] == 0xBF.toByte()
        ) 3 else 0

        return Result(
            text = String(usable, start, usable.size - start, charset),
            truncated = truncated,
        )
    }
}
