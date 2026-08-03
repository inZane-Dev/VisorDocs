package com.visordocs

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.mutableStateOf
import androidx.fragment.app.FragmentActivity
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.visordocs.model.DocumentRequest
import com.visordocs.ui.VisorDocsApp
import com.visordocs.ui.theme.VisorDocsTheme
import com.visordocs.ui.viewer.render.WebViewWarmup
import com.visordocs.ui.viewer.render.pdf.PdfEngineSupport

/**
 * Unica Activity de la app.
 *
 * Su unica responsabilidad es traducir los Intent entrantes a un [DocumentRequest] y
 * montar la interfaz. Nada de logica de documentos: de eso se encarga la capa de datos.
 *
 * Hereda de [FragmentActivity] (y no de ComponentActivity) porque el visor de
 * androidx.pdf es un Fragment y necesita un FragmentManager para incrustarse.
 */
class MainActivity : FragmentActivity() {

    private val incomingDocument = mutableStateOf<DocumentRequest?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Queda en logcat que motor de PDF soporta este dispositivo.
        // Ver con: adb logcat -s VisorDocs
        PdfEngineSupport.logDiagnostics()

        // Se adelanta el arranque del motor del WebView para que el primer documento de
        // Office no cargue con varios segundos de retraso.
        WebViewWarmup.start(this)

        // PDFBox necesita esto antes de usarse: carga desde los assets los recursos que
        // en la version de escritorio vendrian del sistema (mapas de codificacion y
        // fuentes base). Sin ello, unir PDF falla al leer el primer documento.
        PDFBoxResourceLoader.init(applicationContext)

        enableEdgeToEdge()
        incomingDocument.value = intent?.toDocumentRequest()

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
     * La Activity es `singleTop`: si ya esta abierta y el usuario toca otro documento,
     * el sistema reutiliza esta instancia y avisa por aqui en vez de llamar a onCreate.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        incomingDocument.value = intent.toDocumentRequest()
    }
}

private fun Intent.toDocumentRequest(): DocumentRequest? {
    // Compartir VARIOS documentos a la vez: se abre el primero y los demas se dejan
    // preparados para unir, que es lo unico que se puede hacer con un grupo de PDF.
    if (action == Intent.ACTION_SEND_MULTIPLE) {
        val uris = extraStreamUris()
        val first = uris.firstOrNull() ?: return null
        return DocumentRequest(uri = first, mimeType = type, alsoMerge = uris.drop(1))
    }

    val uri = when (action) {
        Intent.ACTION_VIEW -> data
        Intent.ACTION_SEND -> extraStreamUri()
        else -> null
    }

    // Queda registrado que envia cada app. Sirve para ajustar los intent-filter del
    // manifest cuando VisorDocs no aparece en "Abrir con" desde alguna app concreta.
    // Ver con: adb logcat -s VisorDocs
    //
    // Solo en compilaciones de depuracion: la ruta del URI suele incluir el nombre del
    // archivo, y eso puede ser informacion personal ("Analitica_medica.pdf"). En una
    // version publicada no tiene por que quedar rastro de que documentos se abrieron.
    if (BuildConfig.DEBUG) {
        Log.i(
            PdfEngineSupport.TAG,
            "Intent recibido: action=$action, type=$type, uri=$uri, " +
                "authority=${uri?.authority}, path=${uri?.path}",
        )
    }

    if (uri == null) return null

    // `type` es solo una pista: muchas apps mandan application/octet-stream.
    // DocumentTypeDetector se encarga de confirmarlo.
    return DocumentRequest(uri = uri, mimeType = type)
}

@Suppress("DEPRECATION")
private fun Intent.extraStreamUri(): Uri? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
    } else {
        getParcelableExtra(Intent.EXTRA_STREAM) as? Uri
    }

@Suppress("DEPRECATION")
private fun Intent.extraStreamUris(): List<Uri> =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
    } else {
        getParcelableArrayListExtra(Intent.EXTRA_STREAM)
    }.orEmpty()
