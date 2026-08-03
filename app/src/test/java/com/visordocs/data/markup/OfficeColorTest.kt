package com.visordocs.data.markup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pruebas de la resolucion de color de OOXML.
 *
 * Es codigo con muchas reglas que no se ven en el archivo: paletas que hay que conocer de
 * memoria, un orden de tema que esta intercambiado y un aclarado que se hace en otro
 * espacio de color. Cada una de esas se equivoca en silencio.
 */
class OfficeColorTest {

    private fun rgb(value: String?) =
        OfficeColor.resolve(rgb = value, indexed = null, themeIndex = null, tint = null)

    // ------------------------------------------------------------------- hexadecimal

    @Test
    fun seDescartaLaOpacidadDelArgb() {
        // Office escribe ocho digitos: los dos primeros son la opacidad, no el rojo.
        assertEquals("#CC0000", rgb("FFCC0000")?.toCss())
    }

    @Test
    fun tambienSeAceptanSeisDigitos() {
        assertEquals("#2E7D32", rgb("2E7D32")?.toCss())
    }

    @Test
    fun unValorIlegibleNoInventaUnColor() {
        assertNull(rgb("no-es-un-color"))
        assertNull(rgb(""))
    }

    // ---------------------------------------------------------------- paleta indexada

    @Test
    fun laPaletaIndexadaResuelveSusPosicionesFijas() {
        val red = OfficeColor.resolve(rgb = null, indexed = "2", themeIndex = null, tint = null)
        assertEquals("#FF0000", red?.toCss())
    }

    @Test
    fun unIndiceFueraDeLaPaletaNoDevuelveColor() {
        assertNull(OfficeColor.resolve(rgb = null, indexed = "200", themeIndex = null, tint = null))
    }

    // ------------------------------------------------------------------------ tema

    @Test
    fun elTemaDelDocumentoManda() {
        // Paleta ya reordenada, tal y como la entrega quien lee theme1.xml.
        val theme = listOf(0xFFFFFF, 0x000000, 0xEEEEEE, 0x111111, 0x123456)
        val accent = OfficeColor.resolve(
            rgb = null, indexed = null, themeIndex = "4", tint = null, theme = theme,
        )
        assertEquals("#123456", accent?.toCss())
    }

    @Test
    fun sinTemaEnElArchivoSeUsaElDeOfficePorOmision() {
        val accent1 = OfficeColor.resolve(rgb = null, indexed = null, themeIndex = "4", tint = null)
        assertEquals("#4472C4", accent1?.toCss())
    }

    @Test
    fun elTintePositivoAclaraYElNegativoOscurece() {
        val base = rgb("4472C4")!!
        val lighter = OfficeColor.resolve(
            rgb = "4472C4", indexed = null, themeIndex = null, tint = "0.4",
        )!!
        val darker = OfficeColor.resolve(
            rgb = "4472C4", indexed = null, themeIndex = null, tint = "-0.4",
        )!!

        assertTrue("aclarar debe subir la luminancia", lighter.luminance > base.luminance)
        assertTrue("oscurecer debe bajarla", darker.luminance < base.luminance)
    }

    @Test
    fun elTinteConservaElTono() {
        // Aclarar un azul debe dar un azul mas claro, no un gris: por eso se opera sobre
        // la luminosidad en HSL y no subiendo los tres canales por igual.
        val lighter = OfficeColor.resolve(
            rgb = "0000FF", indexed = null, themeIndex = null, tint = "0.5",
        )!!.value
        val r = lighter shr 16 and 0xFF
        val b = lighter and 0xFF
        assertTrue("el azul debe seguir dominando (r=$r, b=$b)", b > r)
    }

    // -------------------------------------------------------------------- legibilidad

    @Test
    fun sobreUnFondoClaroSeEligeTextoNegro() {
        assertEquals("#000000", rgb("FFF176")!!.readableForeground().toCss())
    }

    @Test
    fun sobreUnFondoOscuroSeEligeTextoBlanco() {
        assertEquals("#FFFFFF", rgb("2E7D32")!!.readableForeground().toCss())
    }

    /**
     * Esta es la regla que hace que el modo oscuro siga funcionando: un texto casi negro
     * o casi blanco es "el color por omision" escrito de otra forma, y aplicarlo pintaria
     * texto negro sobre fondo negro.
     */
    @Test
    fun losColoresCasiNegroYCasiBlancoSeTratanComoAusenciaDeColor() {
        assertTrue(rgb("000000")!!.isNearDefault)
        assertTrue(rgb("111111")!!.isNearDefault)
        assertTrue(rgb("FFFFFF")!!.isNearDefault)
        assertTrue(rgb("FAFAFA")!!.isNearDefault)
    }

    @Test
    fun unColorDeVerdadSiSeAplica() {
        assertFalse(rgb("CC0000")!!.isNearDefault)
        assertFalse(rgb("2E7D32")!!.isNearDefault)
        assertFalse(rgb("4472C4")!!.isNearDefault)
    }

    // ---------------------------------------------------------------------- resaltado

    @Test
    fun elResaltadoDeWordSeResuelvePorNombre() {
        assertEquals("#FFFF00", OfficeColor.highlight("yellow")?.toCss())
        assertEquals("#00FFFF", OfficeColor.highlight("cyan")?.toCss())
    }

    @Test
    fun sinResaltadoNoHayColor() {
        assertNull(OfficeColor.highlight("none"))
        assertNull(OfficeColor.highlight(null))
        assertNull(OfficeColor.highlight("inventado"))
    }

    @Test
    fun elTemaDeWordSeResuelvePorNombreYNoPorIndice() {
        // Word escribe w:themeColor="accent1"; las hojas de calculo escriben theme="4".
        assertEquals("#4472C4", OfficeColor.themeByName("accent1", null)?.toCss())
        assertNull(OfficeColor.themeByName("noExiste", null))
    }
}
