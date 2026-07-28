package com.deiby.visordocs.viewer.pdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.File
import java.io.IOException

/**
 * Envoltorio sobre [PdfRenderer], la API de PDF del sistema.
 *
 * Es el motor de respaldo, para dispositivos sin la SDK Extension que necesita
 * androidx.pdf. Solo entrega imagenes de pagina: no hay texto, ni busqueda, ni
 * enlaces, ni soporte de contrasena.
 *
 * [PdfRenderer] no es thread-safe y solo admite una pagina abierta a la vez, de ahi
 * el bloqueo en todas las operaciones.
 */
class LegacyPdfDocument private constructor(
    private val renderer: PdfRenderer,
    private val descriptor: ParcelFileDescriptor,
    /** Copia temporal en cache, si hubo que hacerla. Se borra al cerrar. */
    private val temporaryFile: File?,
) : Closeable {

    private val lock = Any()
    private var closed = false

    val pageCount: Int = renderer.pageCount

    /** Relacion ancho/alto de una pagina, para reservar su espacio antes de pintarla. */
    suspend fun aspectRatio(pageIndex: Int): Float = withContext(Dispatchers.IO) {
        synchronized(lock) {
            if (closed) return@synchronized DEFAULT_ASPECT_RATIO
            val page = renderer.openPage(pageIndex)
            try {
                if (page.height == 0) DEFAULT_ASPECT_RATIO
                else page.width.toFloat() / page.height.toFloat()
            } finally {
                page.close()
            }
        }
    }

    /** Rasteriza una pagina al ancho pedido. Devuelve null si el documento ya se cerro. */
    suspend fun renderPage(pageIndex: Int, targetWidthPx: Int): Bitmap? = withContext(Dispatchers.IO) {
        synchronized(lock) {
            if (closed) return@synchronized null
            val width = targetWidthPx.coerceIn(1, MAX_RENDER_WIDTH_PX)
            val page = renderer.openPage(pageIndex)
            try {
                val height = if (page.width == 0) {
                    width
                } else {
                    (page.height.toFloat() / page.width.toFloat() * width).toInt().coerceAtLeast(1)
                }
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                // PdfRenderer no pinta el fondo de la pagina; sin esto sale negro.
                bitmap.eraseColor(Color.WHITE)
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                bitmap
            } finally {
                page.close()
            }
        }
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            runCatching { renderer.close() }
            runCatching { descriptor.close() }
            temporaryFile?.delete()
        }
    }

    companion object {
        /** Proporcion de una hoja A4 vertical: se usa mientras no se conoce la real. */
        const val DEFAULT_ASPECT_RATIO = 0.7071f

        /** Tope de rasterizado. Evita bitmaps enormes en pantallas de alta densidad. */
        private const val MAX_RENDER_WIDTH_PX = 2048

        /**
         * Abre el documento.
         *
         * @throws SecurityException si el PDF esta protegido con contrasena.
         * @throws IOException si el archivo no se puede leer o esta danado.
         */
        suspend fun open(context: Context, uri: Uri): LegacyPdfDocument =
            withContext(Dispatchers.IO) {
                // Primero sin copiar: la mayoria de proveedores dan un descriptor navegable.
                openDirect(context, uri) ?: openFromCopy(context, uri)
            }

        /** Devuelve null si el descriptor no sirve, para que se reintente copiando. */
        private fun openDirect(context: Context, uri: Uri): LegacyPdfDocument? {
            val descriptor = runCatching {
                context.contentResolver.openFileDescriptor(uri, "r")
            }.getOrNull() ?: return null

            return try {
                LegacyPdfDocument(PdfRenderer(descriptor), descriptor, null)
            } catch (security: SecurityException) {
                runCatching { descriptor.close() }
                // Contrasena: copiar tampoco ayudaria, se propaga tal cual.
                throw security
            } catch (_: Throwable) {
                runCatching { descriptor.close() }
                null
            }
        }

        /**
         * Algunos proveedores (correo, apps de mensajeria) entregan un stream que no
         * permite saltar posiciones, y PdfRenderer necesita acceso aleatorio. En ese
         * caso se vuelca a la cache y se abre desde ahi.
         */
        private fun openFromCopy(context: Context, uri: Uri): LegacyPdfDocument {
            val file = File.createTempFile("visordocs_", ".pdf", context.cacheDir)
            try {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    file.outputStream().use { output -> input.copyTo(output) }
                } ?: throw IOException("No se pudo abrir el archivo.")

                val descriptor = ParcelFileDescriptor.open(
                    file,
                    ParcelFileDescriptor.MODE_READ_ONLY,
                )
                return try {
                    LegacyPdfDocument(PdfRenderer(descriptor), descriptor, file)
                } catch (throwable: Throwable) {
                    runCatching { descriptor.close() }
                    throw throwable
                }
            } catch (throwable: Throwable) {
                file.delete()
                throw throwable
            }
        }
    }
}
