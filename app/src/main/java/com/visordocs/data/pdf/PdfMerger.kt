package com.visordocs.data.pdf

import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.multipdf.PDFMergerUtility
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * Alguno de los documentos esta cifrado y pide contrasena.
 *
 * Se traduce aqui, en la capa de datos, para que la interfaz pueda dar un mensaje
 * concreto sin conocer PDFBox: sin esto, un PDF protegido daba el mismo "no se pudo unir"
 * que un archivo corrupto, y la persona no tenia forma de saber que el problema era otro
 * ni que podia resolverlo.
 */
class ProtectedPdfException(cause: Throwable) : IOException(cause)

/**
 * Une varios PDF en uno solo.
 *
 * Android no puede hacer esto por su cuenta: `PdfRenderer` sabe leer un PDF y
 * `PdfDocument` sabe escribir uno nuevo dibujando encima, pero no existe forma de copiar
 * una pagina de un documento a otro. La alternativa sin librerias seria rasterizar cada
 * pagina a imagen, y el resultado perderia el texto: nada de buscar ni seleccionar, y un
 * archivo mucho mas pesado.
 *
 * PDFBox trabaja sobre la estructura interna del formato, asi que la union conserva el
 * texto, los enlaces y la calidad vectorial de los originales.
 */
object PdfMerger {

    /**
     * Por encima de este tamano, PDFBox deja de trabajar en memoria y usa archivos
     * temporales. Sin ese limite, unir varios documentos grandes agotaria el heap del
     * proceso; con el, el coste se traslada al disco de la cache.
     */
    private const val MAX_MEMORY_BYTES = 32L * 1024 * 1024

    /**
     * @param sources documentos a unir, en el orden en que quedaran.
     * @param target donde se escribe el resultado.
     * @param tempDir carpeta para los archivos temporales de PDFBox.
     *
     * Los flujos de entrada los cierra PDFBox; el de salida se cierra aqui.
     */
    @Throws(IOException::class)
    fun merge(sources: List<InputStream>, target: OutputStream, tempDir: File) {
        require(sources.size >= 2) { "Hacen falta al menos dos documentos para unir" }

        val merger = PDFMergerUtility()
        merger.destinationStream = target
        sources.forEach { merger.addSource(it) }

        val memory = MemoryUsageSetting.setupMixed(MAX_MEMORY_BYTES).setTempDir(tempDir)
        try {
            merger.mergeDocuments(memory)
        } catch (password: InvalidPasswordException) {
            throw ProtectedPdfException(password)
        }
    }
}
