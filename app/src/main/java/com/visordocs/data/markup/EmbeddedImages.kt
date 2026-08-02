package com.visordocs.data.markup

import android.util.Base64
import com.visordocs.data.zip.ZipPackage

/**
 * Convierte una imagen incrustada en el documento en algo que el WebView pueda dibujar.
 *
 * No se guardan archivos ni se sirven por HTTP: la imagen se codifica en base64 y se
 * incrusta en el propio HTML como `data:` URI. Es lo unico compatible con como esta
 * montado el visor, donde el WebView tiene el acceso a archivos cerrado y bloquea
 * cualquier peticion de red.
 *
 * El precio es la memoria: base64 infla los datos un 33 %, y ademas el resultado es un
 * `String` en UTF-16, que vuelve a duplicar. Una imagen de 1 MB acaba ocupando cerca de
 * 3 MB en el documento final. De ahi que los limites sean deliberadamente ajustados y
 * que se lleve una cuenta del total: mas vale un documento con parte de sus imagenes que
 * un cierre por falta de memoria.
 */
class EmbeddedImages(private val pkg: ZipPackage) {

    /** Tope por imagen. Una foto de portada tipica ronda los 200-500 KB. */
    private val maxImageBytes = 1_500_000

    /** Tope acumulado para todo el documento. */
    private val maxTotalBytes = 6_000_000

    private var usedBytes = 0

    /**
     * Devuelve la etiqueta `<img>` de la parte indicada, o cadena vacia si no se puede
     * incrustar (no existe, es demasiado grande o ya se agoto el presupuesto).
     */
    fun imgTag(path: String?): String {
        if (path == null) return ""
        val data = pkg.bytes(path) ?: return ""
        if (data.size > maxImageBytes) return ""
        if (usedBytes + data.size > maxTotalBytes) return ""

        usedBytes += data.size
        val mime = mimeFor(path)
        val base64 = Base64.encodeToString(data, Base64.NO_WRAP)
        return "<img src=\"data:$mime;base64,$base64\" alt=\"\">"
    }

    private fun mimeFor(path: String): String {
        val lower = path.lowercase()
        return when {
            lower.endsWith(".png") -> "image/png"
            lower.endsWith(".gif") -> "image/gif"
            lower.endsWith(".webp") -> "image/webp"
            lower.endsWith(".bmp") -> "image/bmp"
            lower.endsWith(".svg") -> "image/svg+xml"
            else -> "image/jpeg"
        }
    }
}
