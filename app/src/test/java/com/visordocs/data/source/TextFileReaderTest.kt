package com.visordocs.data.source

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

class TextFileReaderTest {

    private fun read(bytes: ByteArray, charset: java.nio.charset.Charset = Charsets.UTF_8) =
        TextFileReader.read(ByteArrayInputStream(bytes), charset)

    @Test
    fun `lee texto UTF-8 con acentos`() {
        val result = read("café con leche ñ".toByteArray(Charsets.UTF_8))
        assertEquals("café con leche ñ", result.text)
        assertFalse(result.truncated)
    }

    @Test
    fun `descarta el BOM de UTF-8`() {
        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
        val result = read(bom + "Hola".toByteArray(Charsets.UTF_8))
        // Sin quitarlo, apareceria un simbolo raro al principio de la primera linea.
        assertEquals("Hola", result.text)
    }

    @Test
    fun `un archivo vacio no falla`() {
        val result = read(ByteArray(0))
        assertEquals("", result.text)
        assertFalse(result.truncated)
    }

    @Test
    fun `marca como truncado lo que pasa del tope`() {
        // 4 MB es el limite; se pasa por poco para comprobar la bandera.
        val big = ByteArray(4 * 1024 * 1024 + 100) { 'a'.code.toByte() }
        val result = read(big)
        assertTrue(result.truncated)
        assertEquals(4 * 1024 * 1024, result.text.length)
    }

    @Test
    fun `justo en el limite no se marca truncado`() {
        val exact = ByteArray(4 * 1024 * 1024) { 'b'.code.toByte() }
        val result = read(exact)
        assertFalse(result.truncated)
    }

    @Test
    fun `en Latin-1 los bytes altos se conservan uno a uno`() {
        // Un RTF antiguo puede traer 0xE9 literal. Leido como UTF-8 seria basura;
        // en Latin-1 se conserva como "e con acento" y el convertidor puede usarlo.
        val result = read(byteArrayOf(0x63, 0x61, 0x66, 0xE9.toByte()), Charsets.ISO_8859_1)
        assertEquals("café", result.text)
    }

    @Test
    fun `el mismo byte alto leido como UTF-8 se pierde`() {
        // Documenta por que RTF necesita Latin-1: no es una preferencia, es correccion.
        val result = read(byteArrayOf(0x63, 0x61, 0x66, 0xE9.toByte()), Charsets.UTF_8)
        assertFalse(result.text == "café")
    }
}
