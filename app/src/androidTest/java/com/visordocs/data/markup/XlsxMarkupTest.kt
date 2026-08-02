package com.visordocs.data.markup

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.visordocs.data.zip.ZipParts
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class XlsxMarkupTest {

    private val sheetNs = "xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\""
    private val relNs = "xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\""

    private fun xlsx(rows: String, shared: List<String> = emptyList()): Markup {
        val si = shared.joinToString("") { "<si><t>$it</t></si>" }
        return XlsxMarkup.convert(
            zipPackage(
                ZipParts.Ooxml,
                "xl/workbook.xml" to
                    "<workbook $sheetNs xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\">" +
                    "<sheets><sheet name=\"Datos\" sheetId=\"1\" r:id=\"rId1\"/></sheets></workbook>",
                "xl/_rels/workbook.xml.rels" to
                    "<Relationships $relNs><Relationship Id=\"rId1\" Target=\"worksheets/sheet1.xml\"/></Relationships>",
                "xl/sharedStrings.xml" to "<sst $sheetNs>$si</sst>",
                "xl/worksheets/sheet1.xml" to "<worksheet $sheetNs><sheetData>$rows</sheetData></worksheet>",
            ),
            TEST_LABELS,
        )
    }

    @Test
    fun sinHojasDevuelveVacio() {
        val pkg = zipPackage(
            ZipParts.Ooxml,
            "xl/workbook.xml" to "<workbook $sheetNs><sheets/></workbook>",
        )
        assertTrue(XlsxMarkup.convert(pkg, TEST_LABELS).isEmpty)
    }

    @Test
    fun resuelveLasCadenasCompartidas() {
        val result = xlsx(
            "<row r=\"1\"><c r=\"A1\" t=\"s\"><v>0</v></c><c r=\"B1\" t=\"s\"><v>1</v></c></row>",
            shared = listOf("Producto", "Precio"),
        )
        assertTrue(result.body.contains("Producto"))
        assertTrue(result.body.contains("Precio"))
        // El indice crudo no debe aparecer como si fuera el contenido.
        assertFalse(result.body.contains("<td>0</td>"))
    }

    /**
     * Regresion del fallo real: se numeraban las filas por orden de aparicion, y como
     * Excel omite las vacias, lo que era la fila 6 aparecia como la 5 y las referencias
     * del usuario dejaban de cuadrar.
     */
    @Test
    fun respetaElNumeroDeFilaRealAunqueHayaHuecos() {
        val result = xlsx(
            "<row r=\"1\"><c r=\"A1\" t=\"inlineStr\"><is><t>arriba</t></is></c></row>" +
                "<row r=\"6\"><c r=\"A6\" t=\"inlineStr\"><is><t>abajo</t></is></c></row>",
            )
        val filas = result.body.split("<td class=\"rowref\">")
        // Deben existir las etiquetas de fila 1 a 6, no solo dos filas seguidas.
        assertTrue(result.body.contains(">6</td>"))
        assertTrue(filas.size - 1 == 6)
    }

    @Test
    fun laColumnaSeDeduceDeLaReferenciaDeCelda() {
        // Sin leer "C1" el dato caeria en la primera columna.
        val result = xlsx(
            "<row r=\"1\"><c r=\"C1\" t=\"inlineStr\"><is><t>tercera</t></is></c></row>",
        )
        assertTrue(result.body.contains("<th class=\"ref\">C</th>"))
        assertTrue(result.body.contains("<td></td><td></td><td>tercera</td>"))
    }

    @Test
    fun losNumerosSeAlineanALaDerecha() {
        val result = xlsx("<row r=\"1\"><c r=\"A1\"><v>1250.75</v></c></row>")
        assertTrue(result.body.contains("<td class=\"num\">1250.75</td>"))
    }

    @Test
    fun losBooleanosSeMuestranComoPalabras() {
        val result = xlsx("<row r=\"1\"><c r=\"A1\" t=\"b\"><v>1</v></c><c r=\"B1\" t=\"b\"><v>0</v></c></row>")
        assertTrue(result.body.contains("VERDADERO"))
        assertTrue(result.body.contains("FALSO"))
    }

    @Test
    fun elNombreDeLaHojaSeMuestraYSeEscapa() {
        val result = xlsx("<row r=\"1\"><c r=\"A1\"><v>1</v></c></row>")
        assertTrue(result.body.contains("Datos"))
        assertTrue(result.body.contains("Hoja"))
    }

    @Test
    fun elContenidoDeLaCeldaSeEscapa() {
        val result = xlsx(
            "<row r=\"1\"><c r=\"A1\" t=\"inlineStr\"><is><t>&lt;b&gt;x&lt;/b&gt;</t></is></c></row>",
        )
        assertFalse(result.body.contains("<b>x</b>"))
        assertTrue(result.body.contains("&lt;b&gt;"))
    }
}
