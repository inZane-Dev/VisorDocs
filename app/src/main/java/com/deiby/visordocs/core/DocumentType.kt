package com.deiby.visordocs.core

import android.content.ContentResolver
import android.net.Uri
import java.util.Locale
import java.util.zip.ZipInputStream

/**
 * Formatos que la app conoce.
 *
 * [supported] indica si ya existe un visor implementado. Los formatos con `false`
 * se detectan igual, para poder mostrar un mensaje concreto ("todavia no soportado")
 * en lugar de un error generico. Al implementar cada fase basta con cambiar la
 * bandera y anadir la rama correspondiente en `ViewerScreen`.
 */
enum class DocumentType(val label: String, val supported: Boolean) {
    PDF("PDF", true),
    WORD("Documento de Word", false),
    EXCEL("Hoja de calculo de Excel", false),
    POWERPOINT("Presentacion de PowerPoint", false),
    PLAIN_TEXT("Archivo de texto", false),
    IMAGE("Imagen", false),
    UNKNOWN("Formato desconocido", false),
}

/**
 * Averigua el formato de un documento.
 *
 * No basta con mirar el MIME type: WhatsApp y varios gestores de archivos entregan
 * todo como `application/octet-stream`. Por eso se intenta en cascada, de la senal
 * mas barata a la mas fiable.
 */
object DocumentTypeDetector {

    private val BY_MIME: Map<String, DocumentType> = mapOf(
        "application/pdf" to DocumentType.PDF,
        "application/x-pdf" to DocumentType.PDF,
        "application/msword" to DocumentType.WORD,
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document" to DocumentType.WORD,
        "application/vnd.ms-excel" to DocumentType.EXCEL,
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" to DocumentType.EXCEL,
        "application/vnd.ms-powerpoint" to DocumentType.POWERPOINT,
        "application/vnd.openxmlformats-officedocument.presentationml.presentation" to DocumentType.POWERPOINT,
    )

    private val BY_EXTENSION: Map<String, DocumentType> = mapOf(
        "pdf" to DocumentType.PDF,
        "doc" to DocumentType.WORD,
        "docx" to DocumentType.WORD,
        "rtf" to DocumentType.WORD,
        "xls" to DocumentType.EXCEL,
        "xlsx" to DocumentType.EXCEL,
        "ppt" to DocumentType.POWERPOINT,
        "pptx" to DocumentType.POWERPOINT,
        "txt" to DocumentType.PLAIN_TEXT,
        "md" to DocumentType.PLAIN_TEXT,
        "csv" to DocumentType.PLAIN_TEXT,
        "log" to DocumentType.PLAIN_TEXT,
        "jpg" to DocumentType.IMAGE,
        "jpeg" to DocumentType.IMAGE,
        "png" to DocumentType.IMAGE,
        "webp" to DocumentType.IMAGE,
        "gif" to DocumentType.IMAGE,
    )

    /** Firma de los PDF: los primeros bytes son siempre `%PDF`. */
    private val PDF_MAGIC = byteArrayOf(0x25, 0x50, 0x44, 0x46)

    /** Firma de un ZIP, que es el contenedor real de docx/xlsx/pptx. */
    private val ZIP_MAGIC = byteArrayOf(0x50, 0x4B, 0x03, 0x04)

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
        val header = ByteArray(4)
        val read = runCatching {
            resolver.openInputStream(uri)?.use { it.read(header, 0, 4) }
        }.getOrNull() ?: return DocumentType.UNKNOWN

        if (read < 4) return DocumentType.UNKNOWN

        return when {
            header.contentEquals(PDF_MAGIC) -> DocumentType.PDF
            header.contentEquals(ZIP_MAGIC) -> detectOoxml(resolver, uri)
            else -> DocumentType.UNKNOWN
        }
    }

    /**
     * Los tres formatos OOXML son ZIP con la misma firma; se distinguen por la
     * carpeta raiz que contienen. Se recorren solo las primeras entradas porque
     * el marcador aparece siempre al principio del archivo.
     */
    private fun detectOoxml(resolver: ContentResolver, uri: Uri): DocumentType {
        return runCatching {
            resolver.openInputStream(uri)?.use { input ->
                ZipInputStream(input).use { zip ->
                    var inspected = 0
                    var entry = zip.nextEntry
                    while (entry != null && inspected < MAX_ZIP_ENTRIES_TO_INSPECT) {
                        val entryName = entry.name
                        when {
                            entryName.startsWith("word/") -> return@use DocumentType.WORD
                            entryName.startsWith("xl/") -> return@use DocumentType.EXCEL
                            entryName.startsWith("ppt/") -> return@use DocumentType.POWERPOINT
                        }
                        inspected++
                        entry = zip.nextEntry
                    }
                    DocumentType.UNKNOWN
                }
            }
        }.getOrNull() ?: DocumentType.UNKNOWN
    }

    private const val MAX_ZIP_ENTRIES_TO_INSPECT = 20
}
