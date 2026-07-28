package com.deiby.visordocs

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.mutableStateOf
import androidx.fragment.app.FragmentActivity
import com.deiby.visordocs.ui.VisorDocsApp
import com.deiby.visordocs.ui.theme.VisorDocsTheme
import com.deiby.visordocs.viewer.pdf.PdfEngineSupport

/** Documento que llega desde otra app. */
data class IncomingDocument(
    val uri: Uri,
    val mimeType: String?,
)

/**
 * Unica Activity de la app.
 *
 * Hereda de [FragmentActivity] (y no de ComponentActivity) porque el visor de
 * androidx.pdf es un Fragment y necesita un FragmentManager para incrustarse.
 */
class MainActivity : FragmentActivity() {

    private val incomingDocument = mutableStateOf<IncomingDocument?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Queda en logcat que motor de PDF soporta este dispositivo.
        // Ver con: adb logcat -s VisorDocs
        PdfEngineSupport.logDiagnostics()

        enableEdgeToEdge()
        incomingDocument.value = intent?.toIncomingDocument()

        setContent {
            VisorDocsTheme {
                VisorDocsApp(
                    incoming = incomingDocument.value,
                    onIncomingHandled = { incomingDocument.value = null },
                )
            }
        }
    }

    /**
     * La Activity es `singleTop`: si ya esta abierta y el usuario toca otro PDF, el
     * sistema reutiliza esta instancia y avisa por aqui en vez de llamar a onCreate.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        incomingDocument.value = intent.toIncomingDocument()
    }
}

private fun Intent.toIncomingDocument(): IncomingDocument? {
    val uri = when (action) {
        Intent.ACTION_VIEW -> data
        Intent.ACTION_SEND -> extraStreamUri()
        else -> null
    } ?: return null

    // `type` es solo una pista: muchas apps mandan application/octet-stream.
    // DocumentTypeDetector se encarga de confirmarlo.
    return IncomingDocument(uri = uri, mimeType = type)
}

@Suppress("DEPRECATION")
private fun Intent.extraStreamUri(): Uri? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
    } else {
        getParcelableExtra(Intent.EXTRA_STREAM) as? Uri
    }
