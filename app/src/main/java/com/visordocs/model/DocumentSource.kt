package com.visordocs.model

import android.net.Uri
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.core.net.toUri

/**
 * Lo que otra app nos pide abrir.
 *
 * El MIME es solo una pista: muchas apps mandan `application/octet-stream`. Quien
 * decide el formato de verdad es `DocumentTypeDetector`.
 *
 * [alsoMerge] solo llega cuando se comparten VARIOS documentos a la vez
 * (`ACTION_SEND_MULTIPLE`): se abre el primero y el resto entra directamente en la cola
 * de union, que es lo que espera quien acaba de seleccionar cinco PDF y compartirlos.
 */
data class DocumentRequest(
    val uri: Uri,
    val mimeType: String? = null,
    val alsoMerge: List<Uri> = emptyList(),
) {
    companion object {
        /**
         * Permite guardar la peticion cuando el sistema mata el proceso en segundo plano.
         *
         * Se serializa a mano con [listSaver] en lugar de hacer la clase `Parcelable`:
         * son cadenas y evita meter el plugin de parcelize en el proyecto solo para esto.
         *
         * El formato es [uri, mime, ...resto], asi que anadir documentos a unir no rompe
         * lo ya guardado: una peticion antigua de dos elementos se sigue leyendo bien.
         */
        val SAVER: Saver<DocumentRequest?, Any> = listSaver(
            save = { request ->
                if (request == null) {
                    emptyList()
                } else {
                    listOf(request.uri.toString(), request.mimeType.orEmpty()) +
                        request.alsoMerge.map { it.toString() }
                }
            },
            restore = { saved ->
                val values = saved.filterIsInstance<String>()
                if (values.size < 2) {
                    null
                } else {
                    DocumentRequest(
                        uri = values[0].toUri(),
                        mimeType = values[1].ifEmpty { null },
                        alsoMerge = values.drop(2).map { it.toUri() },
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
