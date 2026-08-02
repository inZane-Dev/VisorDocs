package com.visordocs.data

import android.content.ContentResolver
import android.net.Uri
import com.visordocs.model.DocumentType
import java.util.Locale
import java.util.zip.ZipInputStream

/**
 * Averigua el formato de un documento.
 *
 * No basta con mirar el MIME type: WhatsApp y varios gestores de archivos entregan todo
 * como `application/octet-stream`. Por eso se intenta en cascada, de la senal mas barata
 * a la mas fiable.
 */
object DocumentTypeDetector {

    private val BY_MIME: Map<String, DocumentType> = mapOf(
        "application/pdf" to DocumentType.PDF,
        "application/x-pdf" to DocumentType.PDF,
        "application/acrobat" to DocumentType.PDF,
        "application/vnd.pdf" to DocumentType.PDF,
        "text/pdf" to DocumentType.PDF,

        "application/msword" to DocumentType.WORD_LEGACY,
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document" to DocumentType.WORD,
        "application/vnd.ms-word.document.macroenabled.12" to DocumentType.WORD,
        "application/vnd.ms-excel" to DocumentType.EXCEL_LEGACY,
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" to DocumentType.EXCEL,
        "application/vnd.ms-excel.sheet.macroenabled.12" to DocumentType.EXCEL,
        "application/vnd.ms-powerpoint" to DocumentType.POWERPOINT_LEGACY,
        "application/vnd.openxmlformats-officedocument.presentationml.presentation" to DocumentType.POWERPOINT,
        "application/vnd.ms-powerpoint.presentation.macroenabled.12" to DocumentType.POWERPOINT,

        "application/vnd.oasis.opendocument.text" to DocumentType.ODT,
        "application/vnd.oasis.opendocument.spreadsheet" to DocumentType.ODS,
        "application/vnd.oasis.opendocument.presentation" to DocumentType.ODP,

        "application/rtf" to DocumentType.RTF,
        "text/rtf" to DocumentType.RTF,
        "application/epub+zip" to DocumentType.EPUB,

        "text/csv" to DocumentType.CSV,
        "text/comma-separated-values" to DocumentType.CSV,
        "text/tab-separated-values" to DocumentType.CSV,

        // Antes que la regla generica `text/`, o acabaria mostrandose como codigo fuente.
        "text/html" to DocumentType.HTML,
        "application/xhtml+xml" to DocumentType.HTML,

        // El SVG tiene que resolverse aqui, antes de la regla generica `image/`: es texto
        // y lo dibuja el WebView, no el decodificador de mapas de bits.
        "image/svg+xml" to DocumentType.SVG,
        "image/svg" to DocumentType.SVG,
    )

    private val BY_EXTENSION: Map<String, DocumentType> = buildMap {
        put("pdf", DocumentType.PDF)

        // OOXML, incluidas las variantes con macros: por dentro son el mismo ZIP.
        put("docx", DocumentType.WORD)
        put("docm", DocumentType.WORD)
        put("xlsx", DocumentType.EXCEL)
        put("xlsm", DocumentType.EXCEL)
        put("pptx", DocumentType.POWERPOINT)
        put("pptm", DocumentType.POWERPOINT)

        put("odt", DocumentType.ODT)
        put("ods", DocumentType.ODS)
        put("odp", DocumentType.ODP)
        put("fodt", DocumentType.ODT)

        put("doc", DocumentType.WORD_LEGACY)
        put("xls", DocumentType.EXCEL_LEGACY)
        put("ppt", DocumentType.POWERPOINT_LEGACY)

        put("rtf", DocumentType.RTF)
        put("epub", DocumentType.EPUB)
        put("csv", DocumentType.CSV)
        put("tsv", DocumentType.CSV)
        put("svg", DocumentType.SVG)

        listOf("html", "htm", "xhtml").forEach { put(it, DocumentType.HTML) }

        // Imagenes. HEIC/HEIF es el formato por defecto de las camaras modernas; AVIF
        // solo lo decodifica Android 12 o superior, y en versiones anteriores el visor
        // avisa de que no se pudo abrir en lugar de fallar en silencio.
        listOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "heif", "avif")
            .forEach { put(it, DocumentType.IMAGE) }

        // Texto plano: documentos, configuracion y codigo fuente. Todos se muestran
        // igual, asi que anadir una extension aqui es todo lo que hace falta.
        listOf(
            "txt", "text", "md", "markdown", "log", "json", "xml", "yaml", "yml",
            "ini", "conf", "cfg", "properties", "env", "toml", "csv2",
            "kt", "kts", "java", "py", "js", "mjs", "ts", "tsx", "jsx",
            "c", "h", "cpp", "hpp", "cc", "cs", "go", "rs", "rb", "php", "swift", "m",
            "sh", "bash", "zsh", "bat", "ps1", "sql", "gradle", "pro", "cmake",
            "srt", "vtt", "tex", "diff", "patch", "gitignore",
        ).forEach { put(it, DocumentType.PLAIN_TEXT) }
    }

    /** Firma de los PDF: los primeros bytes son siempre `%PDF`. */
    private val PDF_MAGIC = byteArrayOf(0x25, 0x50, 0x44, 0x46)

    /** Firma de un ZIP, contenedor real de OOXML, OpenDocument y EPUB. */
    private val ZIP_MAGIC = byteArrayOf(0x50, 0x4B, 0x03, 0x04)

    private val PNG_MAGIC = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)
    private val GIF_MAGIC = byteArrayOf(0x47, 0x49, 0x46, 0x38)
    private val JPEG_MAGIC = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())

    /**
     * Marcas de formato de la familia ISO-BMFF, que es la que usan HEIC, HEIF y AVIF.
     * Aparecen justo detras de la palabra `ftyp`, en el byte 8.
     */
    private val ISO_BMFF_IMAGE_BRANDS = setOf(
        "heic", "heix", "heim", "heis", "hevc", "hevx", "mif1", "msf1", "avif", "avis",
    )

    /**
     * Se lee mas de lo que necesitan las firmas binarias (que caben en 16 bytes) porque
     * la misma muestra sirve para el analisis de texto de [TextSniffer], que necesita
     * bastante mas para decidir con criterio.
     */
    private const val HEADER_BYTES = TextSniffer.SAMPLE_BYTES

    private const val MAX_ZIP_ENTRIES_TO_INSPECT = 20

    fun detect(
        resolver: ContentResolver,
        uri: Uri,
        mimeHint: String?,
        displayName: String?,
    ): DocumentType {
        // 1. El MIME que declaro la app que nos invoco.
        fromMime(mimeHint)?.let { return it }

        // 2. El MIME que resuelve el propio ContentProvider.
        fromMime(runCatching { resolver.getType(uri) }.getOrNull())?.let { return it }

        // 3. La extension del nombre real del archivo.
        fromExtension(displayName)?.let { return it }
        fromExtension(uri.lastPathSegment)?.let { return it }

        // 4. Ultimo recurso: leer los primeros bytes del archivo.
        return fromMagicBytes(resolver, uri)
    }

    private fun fromMime(mime: String?): DocumentType? {
        val normalized = mime?.trim()?.lowercase(Locale.ROOT)?.substringBefore(';') ?: return null
        BY_MIME[normalized]?.let { return it }
        return when {
            normalized.startsWith("image/") -> DocumentType.IMAGE
            normalized.startsWith("text/") -> DocumentType.PLAIN_TEXT
            else -> null
        }
    }

    private fun fromExtension(name: String?): DocumentType? {
        if (name.isNullOrBlank() || !name.contains('.')) return null
        val extension = name.substringAfterLast('.').lowercase(Locale.ROOT)
        return BY_EXTENSION[extension]
    }

    private fun fromMagicBytes(resolver: ContentResolver, uri: Uri): DocumentType {
        val header = ByteArray(HEADER_BYTES)
        val read = runCatching {
            resolver.openInputStream(uri)?.use { it.read(header, 0, HEADER_BYTES) }
        }.getOrNull() ?: return DocumentType.UNKNOWN

        if (read < 3) return DocumentType.UNKNOWN
        if (header.copyOf(3).contentEquals(JPEG_MAGIC)) return DocumentType.IMAGE
        if (read < 4) return DocumentType.UNKNOWN

        header.copyOf(4).let { first4 ->
            when {
                first4.contentEquals(PDF_MAGIC) -> return DocumentType.PDF
                first4.contentEquals(PNG_MAGIC) -> return DocumentType.IMAGE
                first4.contentEquals(GIF_MAGIC) -> return DocumentType.IMAGE
                first4.contentEquals(ZIP_MAGIC) -> return detectZipFormat(resolver, uri)
            }
        }

        // HEIC/HEIF/AVIF: contenedor ISO-BMFF. El tamano de caja ocupa los 4 primeros
        // bytes, luego viene "ftyp" y luego la marca concreta del formato.
        if (read >= 12) {
            val box = String(header, 4, 4, Charsets.US_ASCII)
            if (box == "ftyp") {
                val brand = String(header, 8, 4, Charsets.US_ASCII).lowercase(Locale.ROOT)
                if (brand in ISO_BMFF_IMAGE_BRANDS) return DocumentType.IMAGE
            }
        }

        // Nada de lo anterior ha encajado. Queda mirar si el contenido es texto, que es
        // lo que rescata a los archivos sin extension ni MIME util.
        return TextSniffer.sniff(header, read) ?: DocumentType.UNKNOWN
    }

    /**
     * Distingue los formatos que van dentro de un ZIP.
     *
     * OOXML se reconoce por su carpeta raiz (`word/`, `xl/`, `ppt/`). OpenDocument y
     * EPUB, en cambio, declaran su tipo en una entrada `mimetype` que por norma es la
     * primera del archivo y va sin comprimir.
     *
     * Se recorren solo las primeras entradas: todos estos marcadores aparecen al
     * principio.
     */
    private fun detectZipFormat(resolver: ContentResolver, uri: Uri): DocumentType {
        return runCatching {
            resolver.openInputStream(uri)?.use { input ->
                ZipInputStream(input).use { zip ->
                    var inspected = 0
                    var entry = zip.nextEntry
                    while (entry != null && inspected < MAX_ZIP_ENTRIES_TO_INSPECT) {
                        val name = entry.name
                        when {
                            name.startsWith("word/") -> return@use DocumentType.WORD
                            name.startsWith("xl/") -> return@use DocumentType.EXCEL
                            name.startsWith("ppt/") -> return@use DocumentType.POWERPOINT

                            name == "mimetype" -> {
                                val declared = zip.readBytes().toString(Charsets.US_ASCII).trim()
                                fromMime(declared)?.let { return@use it }
                            }

                            // Respaldo por si `mimetype` no estuviera o llegara vacio.
                            name == "content.xml" -> return@use DocumentType.ODT
                            name.endsWith(".opf") -> return@use DocumentType.EPUB
                        }
                        inspected++
                        entry = zip.nextEntry
                    }
                    DocumentType.UNKNOWN
                }
            }
        }.getOrNull() ?: DocumentType.UNKNOWN
    }
}
