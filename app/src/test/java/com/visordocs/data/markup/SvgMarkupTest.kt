package com.visordocs.data.markup

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SvgMarkupTest {

    @Test
    fun `la declaracion XML y el DOCTYPE se recortan`() {
        val svg = """
            <?xml version="1.0" encoding="UTF-8"?>
            <!DOCTYPE svg PUBLIC "-//W3C//DTD SVG 1.1//EN" "http://www.w3.org/svg11.dtd">
            <svg xmlns="http://www.w3.org/2000/svg"><rect width="10" height="10"/></svg>
        """.trimIndent()

        val result = SvgMarkup.convert(svg)
        // Ni la declaracion ni el DOCTYPE son validos dentro del cuerpo de un HTML.
        assertFalse(result.body.contains("<?xml"))
        assertFalse(result.body.contains("<!DOCTYPE"))
        assertTrue(result.body.contains("<svg"))
        assertTrue(result.body.contains("<rect"))
    }

    @Test
    fun `el SVG se envuelve para poder ajustarlo por CSS`() {
        val result = SvgMarkup.convert("<svg><circle r=\"5\"/></svg>")
        assertTrue(result.body.startsWith("<div class=\"vector\">"))
        assertTrue(result.body.endsWith("</div>"))
    }

    @Test
    fun `un archivo sin etiqueta svg se considera vacio`() {
        assertTrue(SvgMarkup.convert("no soy un svg").isEmpty)
        assertTrue(SvgMarkup.convert("").isEmpty)
    }

    @Test
    fun `se descarta lo que venga despues del cierre`() {
        val result = SvgMarkup.convert("<svg><rect/></svg><p>fuera</p>")
        assertFalse(result.body.contains("fuera"))
    }
}
