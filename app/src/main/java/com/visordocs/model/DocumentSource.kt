package com.visordocs.model

import android.net.Uri
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver

/**
 * Lo que otra app nos pide abrir.
 *
 * El MIME es solo una pista: muchas apps mandan `application/octet-stream`. Quien
 * decide el formato de verdad es `DocumentTypeDetector`.
 */
data class DocumentRequest(
    val uri: Uri,
    val mimeType: String? = null,
) {
    companion object {
        /**
         * Permite guardar la peticion cuando el sistema mata el proceso en segundo plano.
         *
         * Se serializa a mano con [listSaver] en lugar de hacer la clase `Parcelable`:
         * son dos cadenas y evita meter el plugin de parcelize en el proyecto solo para
         * esto.
         */
        val SAVER: Saver<DocumentRequest?, Any> = listSaver(
            save = { request ->
                if (request == null) emptyList() else listOf(request.uri.toString(), request.mimeType.orEmpty())
            },
            restore = { saved ->
                val values = saved.filterIsInstance<String>()
                if (values.size < 2) {
                    null
                } else {
                    DocumentRequest(
                        uri = Uri.parse(values[0]),
                        mimeType = values[1].ifEmpty { null },
                    )
                }
            },
        )
    }
}

/** Un documento ya identificado, listo para cargar. */
data class DocumentSource(
    val uri: Uri,
    val displayName: String,
    val sizeBytes: Long?,
    val type: DocumentType,
)
