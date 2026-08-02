package com.visordocs.data.markup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RtfMarkupTest {

    private fun rtf(body: String) = RtfMarkup.convert("{\\rtf1\\ansi\\ansicpg1252$body}")

    @Test
    fun `un archivo que no es RTF se considera vacio`() {
        assertTrue(RtfMarkup.convert("Hola, esto es texto suelto").isEmpty)
        assertTrue(RtfMarkup.convert("").isEmpty)
    }

    @Test
    fun `cada par se convierte en un parrafo`() {
        val result = rtf(" Primero\\par Segundo\\par ")
        assertTrue(result.body.contains("<p>Primero</p>"))
        assertTrue(result.body.contains("<p>Segundo</p>"))
    }

    @Test
    fun `el formato se limita a su grupo y no se derrama`() {
        val result = rtf(" antes {\\b negrita} despues\\par ")
        assertTrue(result.body.contains("<strong>negrita</strong>"))
        // Lo importante: "despues" queda fuera de la negrita.
        assertFalse(result.body.contains("<strong>negrita</strong> despues</strong>"))
        assertTrue(result.body.contains("despues"))
    }

    @Test
    fun `b0 desactiva la negrita sin necesidad de cerrar el grupo`() {
        val result = rtf(" \\b encendida\\b0  apagada\\par ")
        assertTrue(result.body.contains("<strong>encendida</strong>"))
        assertTrue(result.body.contains("apagada"))
    }

    @Test
    fun `los escapes hexadecimales se decodifican en la pagina ANSI`() {
        val result = rtf(" caf\\'e9 ni\\'f1o coraz\\'f3n\\par ")
        assertTrue(result.body.contains("café"))
        assertTrue(result.body.contains("niño"))
        assertTrue(result.body.contains("corazón"))
    }

    @Test
    fun `los escapes Unicode se decodifican y su repuesto ANSI se descarta`() {
        // \u233? es "e con acento"; la interrogacion es el caracter de repuesto.
        val result = rtf(" \\u233?l \\u225?rbol\\par ")
        assertTrue(result.body.contains("él árbol"))
        // Si el repuesto no se saltara, apareceria una interrogacion de mas.
        assertFalse(result.body.contains("é?l"))
    }

    @Test
    fun `las tablas de fuentes y colores no aparecen en la salida`() {
        val result = rtf(
            "{\\fonttbl{\\f0\\fswiss Arial;}}{\\colortbl ;\\red255\\green0\\blue0;}" +
                " Solo esto\\par ",
        )
        assertFalse(result.body.contains("Arial"))
        assertFalse(result.body.contains("red255"))
        assertTrue(result.body.contains("Solo esto"))
    }

    @Test
    fun `los destinos opcionales se descartan enteros`() {
        val result = rtf("{\\*\\generator VisorDocs;} Contenido\\par ")
        assertFalse(result.body.contains("VisorDocs;"))
        assertTrue(result.body.contains("Contenido"))
    }

    @Test
    fun `line produce un salto dentro del mismo parrafo`() {
        val result = rtf(" Primera\\line Segunda\\par ")
        assertTrue(result.body.contains("Primera<br>Segunda"))
    }

    @Test
    fun `el texto se escapa para que no pueda inyectar HTML`() {
        val result = rtf(" <script>alert(1)</script>\\par ")
        assertFalse(result.body.contains("<script>"))
        assertTrue(result.body.contains("&lt;script&gt;"))
    }

    @Test
    fun `las etiquetas abiertas se cierran al terminar el parrafo`() {
        val result = rtf(" \\b sin cerrar\\par siguiente\\par ")
        // Tantas aperturas como cierres: si no, el resto del documento heredaria negrita.
        assertEquals(
            result.body.split("<strong>").size,
            result.body.split("</strong>").size,
        )
    }
}
