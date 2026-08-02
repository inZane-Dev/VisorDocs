package com.visordocs.data.zip

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipInputStream

/**
 * Un contenedor ZIP con partes de texto, ya leido a memoria.
 *
 * Lo comparten los tres familias de formatos que la app abre asi: OOXML
 * (docx/xlsx/pptx), OpenDocument (odt/ods/odp) y EPUB. Los tres son ZIP con XML por
 * dentro, y los tres necesitan saltar entre partes: el XML principal, la tabla de
 * estilos, el indice... Como `ZipInputStream` es estrictamente secuencial, la unica
 * forma de hacerlo es cargarlas antes.
 *
 * Para que eso sea seguro hay tres limites: numero de entradas, tamano por entrada y
 * tamano total. Sin ellos un ZIP manipulado (unos pocos KB que se descomprimen en
 * gigas) podria agotar la memoria del proceso.
 *
 * El filtro [keep] decide que partes se conservan. Siempre se descartan las imagenes
 * y los recursos binarios: no se renderizan todavia y son lo que mas pesa.
 */
class ZipPackage private constructor(private val entries: Map<String, ByteArray>) {

    fun text(name: String): String? = entries[name]?.toString(Charsets.UTF_8)

    fun bytes(name: String): ByteArray? = entries[name]

    /** Nombres de parte que empiezan por [prefix], en orden alfabetico. */
    fun names(prefix: String): List<String> =
        entries.keys.filter { it.startsWith(prefix) }.sorted()

    companion object {
        private const val MAX_ENTRIES = 2048

        /**
         * Los limites se fijan pensando en el **pico** de memoria, no en los bytes del
         * ZIP. Cada parte se guarda como bytes y luego [text] la convierte a `String`,
         * que en Java es UTF-16: eso **duplica** su tamano. Sumando el `StringBuilder`
         * del HTML de salida, un documento cerca del limite puede llegar a ocupar unas
         * tres veces estas cifras.
         *
         * De ahi que sean bastante mas bajas de lo que parecería necesario: 8 MB de XML
         * son unas 2000 paginas de texto, y en un movil de gama baja el proceso solo
         * dispone de unos 192 MB de heap.
         */
        private const val MAX_ENTRY_BYTES = 8 * 1024 * 1024
        private const val MAX_TOTAL_BYTES = 24 * 1024 * 1024

        fun read(stream: InputStream, keep: (String) -> Boolean): ZipPackage {
            val entries = HashMap<String, ByteArray>()
            var total = 0L

            stream.use { input ->
                ZipInputStream(input.buffered()).use { zip ->
                    var inspected = 0
                    var entry = zip.nextEntry
                    while (entry != null && inspected < MAX_ENTRIES) {
                        val name = entry.name
                        if (!entry.isDirectory && keep(name)) {
                            val data = zip.readAtMost(MAX_ENTRY_BYTES)
                            if (data != null && total + data.size <= MAX_TOTAL_BYTES) {
                                entries[name] = data
                                total += data.size
                            }
                        }
                        inspected++
                        entry = zip.nextEntry
                    }
                }
            }

            if (entries.isEmpty()) {
                throw IOException("El archivo no contiene partes de texto legibles")
            }
            return ZipPackage(entries)
        }

        /** Lee la entrada actual, o null si supera [limit] bytes. */
        private fun ZipInputStream.readAtMost(limit: Int): ByteArray? {
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(16 * 1024)
            while (true) {
                val read = read(buffer)
                if (read <= 0) break
                if (out.size() + read > limit) return null
                out.write(buffer, 0, read)
            }
            return out.toByteArray()
        }
    }
}

/** Que partes conserva cada formato. */
object ZipParts {

    /**
     * Formatos de imagen que se incrustan en el documento.
     *
     * Solo los que el navegador dibuja de forma nativa: una imagen se convierte en un
     * `data:` URI y se entrega tal cual al WebView, sin decodificarla ni reescribirla.
     * Los formatos vectoriales propios de Office (EMF, WMF) se quedan fuera porque
     * ningun navegador los entiende.
     */
    private val IMAGE_SUFFIXES = listOf(".png", ".jpg", ".jpeg", ".gif", ".webp", ".bmp", ".svg")

    private fun isImage(name: String) = IMAGE_SUFFIXES.any { name.endsWith(it, true) }

    /** OOXML: el XML de contenido, las relaciones y las imagenes incrustadas. */
    val Ooxml: (String) -> Boolean = { name ->
        name.endsWith(".xml", true) || name.endsWith(".rels", true) || isImage(name)
    }

    /** OpenDocument: `content.xml`, `styles.xml` y las imagenes de `Pictures/`. */
    val Odf: (String) -> Boolean = { name -> name.endsWith(".xml", true) || isImage(name) }

    /**
     * EPUB: ademas del XML hay que quedarse con el `.opf` (el indice) y los capitulos,
     * que llegan como `.xhtml` o `.html`.
     */
    val Epub: (String) -> Boolean = { name ->
        name.endsWith(".xml", true) || name.endsWith(".opf", true) ||
            name.endsWith(".xhtml", true) || name.endsWith(".html", true) ||
            name.endsWith(".htm", true) || name.endsWith(".ncx", true)
    }
}
