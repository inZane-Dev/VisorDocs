package com.visordocs.ui.viewer

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import com.visordocs.R
import com.visordocs.ui.viewer.render.pdf.PdfEngineSupport

/**
 * Envio del documento a otra aplicacion.
 *
 * Hasta ahora el visor era un callejon sin salida: se abria un documento y no habia forma
 * de hacer nada mas con el. Estas dos acciones lo conectan con el resto del telefono sin
 * copiar el archivo a ninguna parte: se pasa el mismo `content://` que ya se tenia.
 */
object DocumentSharing {

    /**
     * Solo se puede compartir un `content://`.
     *
     * Pasar un `file://` a otra app lanza `FileUriExposedException` desde Android 7: el
     * sistema lo prohibe porque el destinatario no tendria permiso para leerlo. Los
     * `file://` llegan de gestores de archivos antiguos, asi que en esos casos la accion
     * simplemente no se ofrece, en lugar de ofrecerse y fallar.
     */
    fun canShare(uri: Uri?): Boolean = uri?.scheme == ContentResolver.SCHEME_CONTENT

    /** Abre el selector de "Compartir" con el documento actual. */
    fun share(context: Context, uri: Uri, displayName: String) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = context.contentResolver.getType(uri) ?: FALLBACK_MIME
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_TITLE, displayName)

            /*
             * El ClipData no es redundante con EXTRA_STREAM.
             *
             * Es lo que mira el selector del sistema para pintar la vista previa: sin el,
             * mostraba el ultimo tramo del URI —"128" para un content:// de la galeria—
             * en lugar del nombre del archivo. Ademas es el mecanismo por el que el
             * permiso de lectura viaja de verdad hasta la app que reciba el documento.
             */
            clipData = ClipData.newUri(context.contentResolver, displayName, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        launch(context, Intent.createChooser(intent, context.getString(R.string.share_document)))
    }

    /** Abre el documento con otra aplicacion (la que el usuario elija). */
    fun openWith(context: Context, uri: Uri) {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, context.contentResolver.getType(uri) ?: FALLBACK_MIME)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        launch(context, Intent.createChooser(intent, context.getString(R.string.open_with)))
    }

    /**
     * Un telefono puede no tener ninguna app capaz de recibir el documento. Se registra y
     * no pasa nada mas: hacer caer la app por querer compartir seria mucho peor.
     */
    private fun launch(context: Context, intent: Intent) {
        runCatching { context.startActivity(intent) }
            .onFailure { error ->
                if (error is ActivityNotFoundException) {
                    Log.i(PdfEngineSupport.TAG, "No hay ninguna app para esta accion")
                } else {
                    Log.w(PdfEngineSupport.TAG, "No se pudo lanzar la accion", error)
                }
            }
    }

    private const val FALLBACK_MIME = "application/octet-stream"
}
