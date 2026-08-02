package com.visordocs.data.markup

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CsvMarkupTest {

    @Test
    fun `un CSV vacio no produce tabla`() {
        assertTrue(CsvMarkup.convert("").isEmpty)
        assertTrue(CsvMarkup.convert("   \n  ").isEmpty)
    }

    @Test
    fun `la primera fila se marca como cabecera`() {
        val result = CsvMarkup.convert("Nombre,Edad\nAna,34")
        assertTrue(result.body.contains("<th>Nombre</th>"))
        assertTrue(result.body.contains("<th>Edad</th>"))
        assertTrue(result.body.contains("<td>Ana</td>"))
    }

    @Test
    fun `detecta el punto y coma que usa Excel en espanol`() {
        val result = CsvMarkup.convert("Region;Ventas\nNorte;1200")
        assertTrue(result.body.contains("<th>Region</th>"))
        assertTrue(result.body.contains("<th>Ventas</th>"))
        // Con el separador mal detectado, todo caeria en una sola celda.
        assertFalse(result.body.contains("Region;Ventas"))
    }

    @Test
    fun `detecta el tabulador`() {
        val result = CsvMarkup.convert("A\tB\n1\t2")
        assertTrue(result.body.contains("<th>A</th>"))
        assertTrue(result.body.contains("<th>B</th>"))
    }

    @Test
    fun `un separador dentro de comillas no parte el campo`() {
        val result = CsvMarkup.convert("Nombre;Nota\n\"Garcia, Ana\";\"Bien; muy bien\"")
        assertTrue(result.body.contains("Garcia, Ana"))
        assertTrue(result.body.contains("Bien; muy bien"))
    }

    @Test
    fun `las comillas escapadas se reducen a una`() {
        val result = CsvMarkup.convert("Nombre\n\"Lopez \"\"Pepe\"\" Ruiz\"")
        assertTrue(result.body.contains("Lopez \"Pepe\" Ruiz") || result.body.contains("Lopez &quot;Pepe&quot; Ruiz"))
    }

    @Test
    fun `un salto de linea dentro de comillas no parte la fila`() {
        val result = CsvMarkup.convert("Campo\n\"linea uno\nlinea dos\"")
        // Dos filas en total: la cabecera y el campo multilinea.
        assertTrue(result.body.split("<tr>").size - 1 == 2)
    }

    @Test
    fun `los numeros se alinean a la derecha y los textos no`() {
        val result = CsvMarkup.convert("Concepto,Importe\nTotal,1250.75")
        assertTrue(result.body.contains("<td class=\"num\">1250.75</td>"))
        assertTrue(result.body.contains("<td>Total</td>"))
    }

    @Test
    fun `la cabecera nunca se trata como numero`() {
        val result = CsvMarkup.convert("2024,2025\n10,20")
        assertTrue(result.body.contains("<th>2024</th>"))
    }

    @Test
    fun `el contenido se escapa`() {
        val result = CsvMarkup.convert("Campo\n<img src=x onerror=alert(1)>")
        assertFalse(result.body.contains("<img"))
        assertTrue(result.body.contains("&lt;img"))
    }

    @Test
    fun `un origen truncado se propaga al resultado`() {
        val result = CsvMarkup.convert("A,B\n1,2", sourceTruncated = true)
        assertTrue(result.truncated)
    }
}
