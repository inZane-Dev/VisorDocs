package com.visordocs.ui.viewer.render.pdf

import android.os.Build
import android.os.ext.SdkExtensions
import android.util.Log

/** Motor de renderizado de PDF que se va a usar. */
enum class PdfEngine {
    /** androidx.pdf: incluye busqueda, seleccion de texto, enlaces y contrasenas. */
    JETPACK,

    /** android.graphics.pdf.PdfRenderer: siempre disponible, pero solo imagenes de pagina. */
    LEGACY,
}

/**
 * Decide que motor de PDF puede usar el dispositivo.
 *
 * androidx.pdf se apoya en `PdfRendererPreV`, que llega en un modulo actualizable
 * (SDK Extension) y no en la imagen base del sistema. Por eso no basta con mirar
 * `Build.VERSION.SDK_INT`: hay telefonos con Android 11-14 que tienen la extension
 * y otros que no. Se comprueba en tiempo de ejecucion y, si falta, se cae al motor
 * propio en lugar de fallar.
 */
object PdfEngineSupport {

    const val TAG = "VisorDocs"

    /** Version minima de la SDK Extension de Android S que necesita androidx.pdf. */
    private const val REQUIRED_S_EXTENSION_VERSION = 13

    /**
     * Poner en `true` para forzar el motor propio y descartar androidx.pdf.
     * Util para comparar ambos visores o si la version alpha de androidx.pdf falla.
     */
    const val FORCE_LEGACY_ENGINE = false

    /** Version de la SDK Extension de S presente en el dispositivo (0 si no aplica). */
    val sdkExtensionVersion: Int
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching { SdkExtensions.getExtensionVersion(Build.VERSION_CODES.S) }.getOrDefault(0)
        } else {
            0
        }

    val engine: PdfEngine
        get() = when {
            FORCE_LEGACY_ENGINE -> PdfEngine.LEGACY
            // Android 15+ trae la API completa en la imagen base.
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM -> PdfEngine.JETPACK
            sdkExtensionVersion >= REQUIRED_S_EXTENSION_VERSION -> PdfEngine.JETPACK
            else -> PdfEngine.LEGACY
        }

    /**
     * Deja el diagnostico en logcat. Filtrar con:
     * `adb logcat -s VisorDocs`
     */
    fun logDiagnostics() {
        Log.i(
            TAG,
            "Android SDK=${Build.VERSION.SDK_INT}, " +
                "SDK Extension S=$sdkExtensionVersion (se necesita >= $REQUIRED_S_EXTENSION_VERSION), " +
                "motor PDF=$engine",
        )
    }
}
