package com.visordocs.ui.viewer.render

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.WebView

/**
 * Arranca el motor del WebView antes de que haga falta.
 *
 * Abrir el primer documento de Office tardaba varios segundos, y no era culpa del
 * analisis del archivo: Android levanta el proceso aislado de Chromium la primera vez
 * que algo se carga en un WebView, y eso es lo que costaba. Del segundo documento en
 * adelante era inmediato.
 *
 * Aqui se paga ese coste al inicio, cuando el usuario todavia esta mirando la pantalla
 * de inicio y no espera nada. Se crea un WebView invisible, se le carga un documento
 * vacio y se destruye: suficiente para que el proceso quede en marcha.
 *
 * Va aplazado con `Handler.post` para no competir con el primer fotograma de la app, y
 * envuelto en `runCatching` porque en dispositivos donde el WebView esta desactivado o
 * actualizandose su constructor lanza excepcion; en ese caso simplemente no se
 * precalienta y todo sigue funcionando igual.
 */
object WebViewWarmup {

    private var done = false

    fun start(context: Context) {
        if (done) return
        done = true

        val appContext = context.applicationContext
        Handler(Looper.getMainLooper()).post {
            runCatching {
                WebView(appContext).apply {
                    settings.javaScriptEnabled = false
                    loadDataWithBaseURL(null, "<html></html>", "text/html", "utf-8", null)
                    // Destruirlo no apaga el proceso de Chromium, que es justo lo que
                    // interesa dejar caliente.
                    destroy()
                }
            }
        }
    }
}
