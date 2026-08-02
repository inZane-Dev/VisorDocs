package com.visordocs.data.pdf

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * Pruebas de la union de PDF.
 *
 * Los documentos de entrada se construyen aqui, byte a byte, en lugar de guardarlos como
 * assets: asi se ve exactamente que contiene cada uno y anadir un caso es escribir unas
 * lineas, no generar un binario con otra herramienta.
 */
@RunWith(AndroidJUnit4::class)
class PdfMergerTest {

    private val cacheDir get() = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir

    private fun merge(vararg documents: ByteArray): String {
        val out = ByteArrayOutputStream()
        PdfMerger.merge(documents.map { ByteArrayInputStream(it) }, out, cacheDir)
        return out.toByteArray().toString(Charsets.ISO_8859_1)
    }

    private fun pageCount(pdf: String) = Regex("/Type\\s*/Page[^s]").findAll(pdf).count()

    @Test
    fun unirDosDocumentosSumaSusPaginas() {
        val merged = merge(pdfWithPages("A", 3), pdfWithPages("B", 2))
        assertEquals(5, pageCount(merged))
    }

    @Test
    fun elTextoDeAmbosDocumentosSobrevive() {
        // Lo que distingue una union real de rasterizar: el texto sigue siendo texto.
        val merged = merge(pdfWithPages("Primero", 2), pdfWithPages("Segundo", 2))
        assertTrue(merged.contains("Primero 1"))
        assertTrue(merged.contains("Primero 2"))
        assertTrue(merged.contains("Segundo 1"))
        assertTrue(merged.contains("Segundo 2"))
    }

    @Test
    fun elOrdenDeEntradaSeRespeta() {
        val merged = merge(pdfWithPages("Uno", 1), pdfWithPages("Dos", 1))
        assertTrue(merged.indexOf("Uno 1") < merged.indexOf("Dos 1"))
    }

    @Test
    fun seAdmitenMasDeDosDocumentos() {
        val merged = merge(pdfWithPages("A", 1), pdfWithPages("B", 1), pdfWithPages("C", 2))
        assertEquals(4, pageCount(merged))
    }

    @Test
    fun elResultadoEsUnPdfValido() {
        val merged = merge(pdfWithPages("A", 1), pdfWithPages("B", 1))
        assertTrue(merged.startsWith("%PDF-"))
        assertTrue(merged.trimEnd().endsWith("%%EOF"))
        // Sin tabla de referencias cruzadas ningun lector podria abrirlo.
        assertTrue(merged.contains("startxref"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun unSoloDocumentoNoEsUnaUnion() {
        merge(pdfWithPages("A", 1))
    }

    // ------------------------------------------------------------------ fixtures

    /**
     * Construye un PDF valido con [pages] paginas, cada una con un texto reconocible.
     *
     * Las posiciones de la tabla `xref` se calculan sobre los bytes ya escritos; si no
     * cuadran, PDFBox rechaza el archivo, asi que el propio generador queda comprobado
     * por el hecho de que las pruebas pasen.
     */
    private fun pdfWithPages(label: String, pages: Int): ByteArray {
        val objects = LinkedHashMap<Int, String>()
        val pageIds = (0 until pages).map { 4 + it * 2 }
        val contentIds = pageIds.map { it + 1 }

        objects[1] = "<< /Type /Catalog /Pages 2 0 R >>"
        objects[2] = "<< /Type /Pages /Count $pages /Kids [${pageIds.joinToString(" ") { "$it 0 R" }}] >>"
        objects[3] = "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>"

        pageIds.forEachIndexed { index, pageId ->
            val stream = "BT\n/F1 24 Tf\n72 700 Td\n($label ${index + 1}) Tj\nET\n"
            objects[pageId] = "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] " +
                "/Resources << /Font << /F1 3 0 R >> >> /Contents ${contentIds[index]} 0 R >>"
            objects[contentIds[index]] = "<< /Length ${stream.length} >>\nstream\n$stream\nendstream"
        }

        val body = StringBuilder("%PDF-1.4\n")
        val offsets = LinkedHashMap<Int, Int>()
        objects.forEach { (id, content) ->
            offsets[id] = body.length
            body.append("$id 0 obj\n$content\nendobj\n")
        }

        val xrefStart = body.length
        val last = objects.keys.max()
        body.append("xref\n0 ${last + 1}\n0000000000 65535 f \n")
        for (id in 1..last) {
            val offset = offsets[id] ?: 0
            body.append(String.format("%010d 00000 n \n", offset))
        }
        body.append("trailer\n<< /Size ${last + 1} /Root 1 0 R >>\nstartxref\n$xrefStart\n%%EOF\n")

        return body.toString().toByteArray(Charsets.ISO_8859_1)
    }
}
