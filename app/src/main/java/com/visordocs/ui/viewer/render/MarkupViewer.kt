package com.visordocs.ui.viewer.render

import android.annotation.SuppressLint
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.viewinterop.AndroidView
import com.visordocs.R
import java.io.ByteArrayInputStream

/**
 * Muestra un documento HTML dentro de un WebView.
 *
 * El WebView se usa como motor de maquetacion, no como navegador. Por eso va cerrado a
 * cal y canto:
 *
 * - JavaScript desactivado.
 * - Sin acceso a archivos ni a content providers.
 * - Se carga con `baseUrl` nulo, asi que el documento queda en un origen opaco y no
 *   puede resolver rutas relativas hacia nada del dispositivo.
 * - Toda navegacion y toda peticion de recursos se bloquean en [BlockEverything].
 *
 * Ese encierro es lo que permite mostrar sin miedo HTML que no ha generado la app: un
 * .html guardado del navegador o un .svg ajeno. Aunque traigan scripts o enlaces a
 * servidores, no se ejecuta nada y no sale ninguna peticion.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun WebDocumentViewer(
    html: String,
    modifier: Modifier = Modifier,
) {
    val background = MaterialTheme.colorScheme.surface.toArgb()

    AndroidView(
        modifier = modifier,
        factory = { context ->
            WebView(context).apply {
                settings.javaScriptEnabled = false
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                settings.domStorageEnabled = false
                settings.setSupportZoom(true)
                settings.builtInZoomControls = true
                settings.displayZoomControls = false
                settings.useWideViewPort = true
                settings.loadWithOverviewMode = true
                isVerticalScrollBarEnabled = true
                isHorizontalScrollBarEnabled = false
                setBackgroundColor(background)
                webViewClient = BlockEverything
            }
        },
        update = { webView ->
            // `update` corre en cada recomposicion; recargar siempre haria parpadear el
            // documento y perder la posicion de lectura. Solo se recarga si cambio.
            if (webView.tag != html) {
                webView.tag = html
                webView.setBackgroundColor(background)
                webView.loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
            }
        },
        onRelease = { webView ->
            webView.loadUrl("about:blank")
            webView.destroy()
        },
    )
}

/**
 * Muestra un fragmento HTML generado por la app, envuelto con el tema actual.
 *
 * El cuerpo ya viene analizado; aqui solo se le pone el envoltorio, que es barato.
 * Cambiar de modo claro a oscuro no vuelve a analizar el archivo.
 */
@Composable
fun MarkupViewer(
    body: String,
    truncated: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = rememberHtmlColors(dark = isSystemInDarkTheme())
    val notice = if (truncated) stringResource(R.string.office_truncated) else null
    val html = remember(body, colors, notice) { htmlDocument(body, colors, notice) }

    WebDocumentViewer(html = html, modifier = modifier)
}

private object BlockEverything : WebViewClient() {

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = true

    /**
     * Corta tambien las peticiones de recursos, no solo la navegacion.
     *
     * El HTML que genera la app no referencia nada externo, asi que no le quita nada. Lo
     * que cierra es el hueco de los documentos ajenos: un .svg o un .html pueden traer
     * `<img src="https://...">`, y sin esto el WebView lo pediria de verdad, filtrando al
     * servidor que el usuario abrio ese archivo.
     */
    override fun shouldInterceptRequest(
        view: WebView,
        request: WebResourceRequest,
    ): WebResourceResponse = WebResourceResponse(
        "text/plain",
        "utf-8",
        ByteArrayInputStream(ByteArray(0)),
    )
}
