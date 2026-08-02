package com.visordocs.data

import com.visordocs.model.DocumentType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TextSnifferTest {

    private fun sniff(text: String) = TextSniffer.sniff(text.toByteArray(Charsets.UTF_8))

    private fun sniff(vararg bytes: Int) =
        ByteArray(bytes.size) { bytes[it].toByte() }.let { TextSniffer.sniff(it) }

    // --------------------------------------------------------------- reconoce texto

    @Test
    fun `texto llano sin extension se reconoce`() {
        assertEquals(DocumentType.PLAIN_TEXT, sniff("Notas de la reunion del martes.\nPunto uno."))
    }

    @Test
    fun `texto con acentos y emoji sigue siendo texto`() {
        assertEquals(DocumentType.PLAIN_TEXT, sniff("Camión, ñandú, corazón 🚀"))
    }

    @Test
    fun `un archivo de configuracion sin extension se reconoce`() {
        assertEquals(DocumentType.PLAIN_TEXT, sniff("host=localhost\nport=8080\n\t# comentario"))
    }

    // --------------------------------------------------------------- rechaza binarios

    @Test
    fun `un byte cero descarta el archivo`() {
        // Es la senal mas fiable de binario: ningun texto UTF-8 contiene un cero.
        assertNull(sniff(0x48, 0x6F, 0x00, 0x6C, 0x61))
    }

    @Test
    fun `los caracteres de control descartan el archivo`() {
        assertNull(sniff(0x48, 0x6F, 0x07, 0x6C, 0x61))
    }

    @Test
    fun `una secuencia UTF-8 invalida descarta el archivo`() {
        // 0xC3 abre un caracter de dos bytes; 0x28 no es una continuacion valida.
        assertNull(sniff(0x41, 0xC3, 0x28, 0x42))
    }

    @Test
    fun `un archivo vacio no se considera texto`() {
        assertNull(TextSniffer.sniff(ByteArray(0)))
        assertNull(sniff("    \n  "))
    }

    @Test
    fun `los bytes de una imagen no se confunden con texto`() {
        // Cabecera PNG real.
        assertNull(sniff(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))
    }

    // --------------------------------------------------- tabuladores y saltos validos

    @Test
    fun `tabulador salto retorno y avance de pagina estan permitidos`() {
        assertEquals(DocumentType.PLAIN_TEXT, sniff("a\tb\nc\r\nde"))
    }

    // ------------------------------------------------------- distingue subformatos

    @Test
    fun `reconoce un RTF por su cabecera`() {
        assertEquals(DocumentType.RTF, sniff("{\\rtf1\\ansi Hola\\par }"))
    }

    @Test
    fun `reconoce una pagina HTML por el doctype`() {
        assertEquals(DocumentType.HTML, sniff("<!DOCTYPE html>\n<html><body>Hola</body></html>"))
    }

    @Test
    fun `reconoce una pagina HTML sin doctype`() {
        assertEquals(DocumentType.HTML, sniff("<html><body>Hola</body></html>"))
    }

    @Test
    fun `reconoce un SVG aunque la etiqueta venga tras la declaracion XML`() {
        val svg = "<?xml version=\"1.0\"?>\n<!DOCTYPE svg PUBLIC \"x\" \"y\">\n<svg><rect/></svg>"
        assertEquals(DocumentType.SVG, sniff(svg))
    }

    @Test
    fun `un XML cualquiera se queda en texto`() {
        // Sin marcador de SVG ni de HTML no hay motivo para tratarlo distinto.
        assertEquals(DocumentType.PLAIN_TEXT, sniff("<?xml version=\"1.0\"?><config><a>1</a></config>"))
    }

    // ------------------------------------------------------------ corte de la muestra

    @Test
    fun `un caracter multibyte partido al final no invalida la muestra`() {
        // El muestreo corta por donde toca; "ñ" ocupa dos bytes y aqui se parte.
        val partido = "Camión y mas texto".toByteArray(Charsets.UTF_8)
        val cortado = partido.copyOf(6)
        assertEquals(DocumentType.PLAIN_TEXT, TextSniffer.sniff(cortado))
    }

    @Test
    fun `respeta el parametro de longitud`() {
        val buffer = "texto".toByteArray(Charsets.UTF_8) + ByteArray(100)
        // Con la longitud correcta ignora el relleno de ceros del final.
        assertEquals(DocumentType.PLAIN_TEXT, TextSniffer.sniff(buffer, 5))
        // Sin ella, los ceros lo descartan.
        assertNull(TextSniffer.sniff(buffer))
    }
}
