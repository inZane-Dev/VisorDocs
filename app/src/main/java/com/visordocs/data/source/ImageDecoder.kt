package com.visordocs.data.source

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import java.io.IOException

/**
 * Decodificacion de imagenes con submuestreo y correccion de orientacion.
 *
 * Una foto de 50 MP ocuparia unos 200 MB en memoria a tamano completo, muy por encima de
 * lo que el sistema concede a un proceso: decodificarla entera seria un fallo por falta
 * de memoria garantizado. `inSampleSize` hace que el propio decodificador la reduzca
 * mientras lee, sin llegar a construir el mapa de bits grande.
 *
 * Hacen falta dos pasadas y dos flujos, porque un `InputStream` de un content:// no se
 * puede rebobinar despues de leer la cabecera.
 */
object ImageDecoder {

    /** Lado maximo al que se reduce la imagen antes de mostrarla. */
    private const val MAX_DIMENSION = 2048

    fun decode(resolver: ContentResolver, uri: Uri): Bitmap {
        // Primera pasada: solo se leen las dimensiones, sin reservar memoria para
        // pixeles. Con inJustDecodeBounds activo decodeStream devuelve null siempre y
        // deja el resultado en outWidth/outHeight; ese null es lo normal, no un error.
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        val boundsStream = resolver.openInputStream(uri)
            ?: throw IOException("No se pudo abrir la imagen")
        boundsStream.use { BitmapFactory.decodeStream(it, null, bounds) }

        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            throw IOException("La imagen no se pudo interpretar")
        }

        var sampleSize = 1
        while (
            bounds.outWidth / sampleSize > MAX_DIMENSION ||
            bounds.outHeight / sampleSize > MAX_DIMENSION
        ) {
            sampleSize *= 2
        }

        // Segunda pasada: ahora si se decodifican los pixeles, ya reducidos.
        val options = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        val pixelStream = resolver.openInputStream(uri)
            ?: throw IOException("No se pudo abrir la imagen")
        val bitmap = pixelStream.use { BitmapFactory.decodeStream(it, null, options) }
            ?: throw IOException("La imagen esta danada o el formato no es compatible")

        return applyExifRotation(resolver, uri, bitmap)
    }

    /**
     * Corrige el giro segun los metadatos EXIF.
     *
     * Las camaras de movil casi nunca rotan los pixeles: guardan la foto tal como la
     * capto el sensor y anotan aparte en que posicion estaba el telefono. Sin leer ese
     * dato, una foto vertical se muestra tumbada.
     *
     * Si los metadatos no se pueden leer se devuelve la imagen tal cual: no vale la pena
     * perder la foto por no saber su orientacion.
     */
    private fun applyExifRotation(
        resolver: ContentResolver,
        uri: Uri,
        bitmap: Bitmap,
    ): Bitmap {
        val orientation = runCatching {
            resolver.openInputStream(uri)?.use { stream ->
                ExifInterface(stream).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL,
                )
            }
        }.getOrNull() ?: return bitmap

        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.postRotate(90f)
                matrix.postScale(-1f, 1f)
            }

            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.postRotate(270f)
                matrix.postScale(-1f, 1f)
            }

            else -> return bitmap
        }

        return runCatching {
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
                .also { rotated -> if (rotated != bitmap) bitmap.recycle() }
        }.getOrDefault(bitmap)
    }
}
