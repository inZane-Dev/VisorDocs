package com.deiby.visordocs.core

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Un documento listo para abrir, ya identificado. */
data class DocumentSource(
    val uri: Uri,
    val displayName: String,
    val sizeBytes: Long?,
    val type: DocumentType,
)

/**
 * Resuelve nombre, tamano y formato de un [Uri].
 *
 * Toca disco y puede abrir el archivo para leer su cabecera, asi que se ejecuta
 * fuera del hilo principal.
 */
suspend fun resolveDocumentSource(
    context: Context,
    uri: Uri,
    mimeHint: String? = null,
): DocumentSource = withContext(Dispatchers.IO) {
    val resolver = context.contentResolver
    var displayName: String? = null
    var sizeBytes: Long? = null

    // Los content:// exponen nombre y tamano via OpenableColumns.
    runCatching {
        resolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
            null,
            null,
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex >= 0 && !cursor.isNull(nameIndex)) {
                    displayName = cursor.getString(nameIndex)
                }
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) {
                    sizeBytes = cursor.getLong(sizeIndex)
                }
            }
        }
    }

    // Los file:// no responden a esa consulta; se lee del propio path.
    if (displayName == null && uri.scheme == "file") {
        uri.path?.let { path ->
            val file = File(path)
            displayName = file.name
            if (file.exists()) sizeBytes = file.length()
        }
    }

    val resolvedName = displayName ?: uri.lastPathSegment ?: "documento"

    DocumentSource(
        uri = uri,
        displayName = resolvedName,
        sizeBytes = sizeBytes,
        type = DocumentTypeDetector.detect(resolver, uri, mimeHint, resolvedName),
    )
}

/** Formatea un tamano en bytes de forma legible, o null si se desconoce. */
fun formatFileSize(bytes: Long?): String? {
    if (bytes == null || bytes < 0) return null
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return String.format("%.0f KB", kb)
    val mb = kb / 1024.0
    if (mb < 1024) return String.format("%.1f MB", mb)
    return String.format("%.1f GB", mb / 1024.0)
}
