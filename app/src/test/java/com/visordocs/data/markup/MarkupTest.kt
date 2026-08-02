package com.visordocs.data.markup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkupTest {

    @Test
    fun `escapa los cinco caracteres peligrosos`() {
        assertEquals("&amp;", "&".escapeHtml())
        assertEquals("&lt;", "<".escapeHtml())
        assertEquals("&gt;", ">".escapeHtml())
        assertEquals("&quot;", "\"".escapeHtml())
        assertEquals("&#39;", "'".escapeHtml())
    }

    @Test
    fun `un texto sin caracteres peligrosos se devuelve intacto`() {
        val original = "Informe trimestral con acentos: canon, accion"
        // Misma instancia: el atajo evita construir una copia cuando no hace falta.
        assertTrue(original === original.escapeHtml())
    }

    @Test
    fun `neutraliza un intento de inyeccion`() {
        val escaped = "<script>alert('x')</script>".escapeHtml()
        assertFalse(escaped.contains("<script>"))
        assertFalse(escaped.contains("'"))
        assertEquals("&lt;script&gt;alert(&#39;x&#39;)&lt;/script&gt;", escaped)
    }

    @Test
    fun `un ampersand no se escapa dos veces`() {
        // Si se escapara en cascada saldria &amp;amp;lt;
        assertEquals("&amp;lt;", "&lt;".escapeHtml())
    }

    @Test
    fun `un cuerpo en blanco cuenta como vacio`() {
        assertTrue(Markup("").isEmpty)
        assertTrue(Markup("   \n  ").isEmpty)
        assertTrue(Markup.Empty.isEmpty)
        assertFalse(Markup("<p>algo</p>").isEmpty)
    }
}
