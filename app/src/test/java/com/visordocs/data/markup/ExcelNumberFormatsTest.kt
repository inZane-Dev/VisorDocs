package com.visordocs.data.markup

import com.visordocs.data.markup.ExcelNumberFormats.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ExcelNumberFormatsTest {

    @Test
    fun `un numero sin formato de fecha se deja tal cual`() {
        assertNull(ExcelNumberFormats.format(1250.75, Kind.PLAIN))
    }

    /**
     * El origen del calendario de Excel es el 30/12/1899 y no el 31, porque Excel cuenta
     * 1900 como bisiesto por compatibilidad con Lotus. Si esto falla, todas las fechas
     * saldrian corridas un dia.
     */
    @Test
    fun `el serial 1 es el 1 de enero de 1900`() {
        assertEquals("01/01/1900", ExcelNumberFormats.format(1.0, Kind.DATE))
    }

    @Test
    fun `convierte un serial moderno a su fecha`() {
        // 45000 = 15/03/2023, comprobado contra Excel.
        assertEquals("15/03/2023", ExcelNumberFormats.format(45000.0, Kind.DATE))
    }

    @Test
    fun `la parte decimal es la hora del dia`() {
        // 0,5 es el mediodia.
        assertEquals("15/03/2023 12:00", ExcelNumberFormats.format(45000.5, Kind.DATE_TIME))
    }

    @Test
    fun `el formato de solo hora omite la fecha`() {
        assertEquals("06:00", ExcelNumberFormats.format(45000.25, Kind.TIME))
    }

    @Test
    fun `un serial negativo no se interpreta como fecha`() {
        // En Excel no existen fechas anteriores al origen.
        assertNull(ExcelNumberFormats.format(-5.0, Kind.DATE))
    }

    @Test
    fun `un serial desmesurado se muestra como numero`() {
        // Casi siempre es un dato mal etiquetado, no una fecha del ano 12000.
        assertNull(ExcelNumberFormats.format(99_999_999.0, Kind.DATE))
    }

    @Test
    fun `distingue un dia entero de uno con hora`() {
        assert(ExcelNumberFormats.isWholeDay(45000.0))
        assert(!ExcelNumberFormats.isWholeDay(45000.5))
    }

    // ------------------------------------------------------ lectura de estilos

    @Test
    fun `sin styles el libro no tiene formatos de fecha`() {
        val styles = ExcelNumberFormats.Styles.Empty
        assertEquals(Kind.PLAIN, styles.kindOf(0))
        assertEquals(Kind.PLAIN, styles.kindOf(null))
        assertEquals(Kind.PLAIN, styles.kindOf(99))
    }
}
