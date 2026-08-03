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
}
