package com.visordocs.data.markup

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.visordocs.data.zip.ZipParts
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Las tres limitaciones que quedaban documentadas y ya no lo estan: fechas de Excel,
 * numeracion real de listas e imagenes incrustadas.
 */
@RunWith(AndroidJUnit4::class)
class OfficeFidelityTest {

    private val sheetNs = "xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\""
    private val relNs = "xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\""
    private val wNs = "xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\""
    private val aNs = "xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\""

    /** Un PNG de 1x1 valido, para comprobar el incrustado sin depender de assets. */
    private val onePixelPng = byteArrayOf(
        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
    ).let { String(it, Charsets.ISO_8859_1) }

    // --------------------------------------------------------- fechas de Excel

    private fun xlsxWithDates(styles: String, rows: String) = XlsxMarkup.convert(
        zipPackage(
            ZipParts.Ooxml,
            "xl/workbook.xml" to
                "<workbook $sheetNs xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\">" +
                "<sheets><sheet name=\"D\" sheetId=\"1\" r:id=\"rId1\"/></sheets></workbook>",
            "xl/_rels/workbook.xml.rels" to
                "<Relationships $relNs><Relationship Id=\"rId1\" Target=\"worksheets/sheet1.xml\"/></Relationships>",
            "xl/styles.xml" to "<styleSheet $sheetNs>$styles</styleSheet>",
            "xl/worksheets/sheet1.xml" to "<worksheet $sheetNs><sheetData>$rows</sheetData></worksheet>",
        ),
        TEST_LABELS,
    )

    @Test
    fun elFormatoPredefinidoDeFechaConvierteElNumeroDeSerie() {
        // numFmtId 14 es el formato de fecha corta que trae Excel de serie.
        val result = xlsxWithDates(
            styles = "<cellXfs count=\"1\"><xf numFmtId=\"14\" applyNumberFormat=\"1\"/></cellXfs>",
            rows = "<row r=\"1\"><c r=\"A1\" s=\"0\"><v>45000</v></c></row>",
        )
        assertTrue(result.body.contains("15/03/2023"))
        assertFalse(result.body.contains(">45000<"))
    }

    @Test
    fun unFormatoPropioDeFechaTambienSeReconoce() {
        val result = xlsxWithDates(
            styles = "<numFmts><numFmt numFmtId=\"164\" formatCode=\"dd/mm/yyyy\"/></numFmts>" +
                "<cellXfs count=\"1\"><xf numFmtId=\"164\" applyNumberFormat=\"1\"/></cellXfs>",
            rows = "<row r=\"1\"><c r=\"A1\" s=\"0\"><v>45000</v></c></row>",
        )
        assertTrue(result.body.contains("15/03/2023"))
    }

    @Test
    fun unNumeroNormalSigueSiendoUnNumero() {
        val result = xlsxWithDates(
            styles = "<cellXfs count=\"1\"><xf numFmtId=\"0\"/></cellXfs>",
            rows = "<row r=\"1\"><c r=\"A1\" s=\"0\"><v>45000</v></c></row>",
        )
        assertTrue(result.body.contains("45000"))
        assertFalse(result.body.contains("15/03/2023"))
    }

    @Test
    fun unaLetraDentroDeComillasNoConvierteElFormatoEnFecha() {
        // El formato `0" dias"` lleva una `d` que es texto literal, no un marcador.
        val result = xlsxWithDates(
            styles = "<numFmts><numFmt numFmtId=\"165\" formatCode=\"0&quot; dias&quot;\"/></numFmts>" +
                "<cellXfs count=\"1\"><xf numFmtId=\"165\" applyNumberFormat=\"1\"/></cellXfs>",
            rows = "<row r=\"1\"><c r=\"A1\" s=\"0\"><v>45000</v></c></row>",
        )
        assertTrue(result.body.contains("45000"))
        assertFalse(result.body.contains("15/03/2023"))
    }

    // ------------------------------------------------------ numeracion de listas

    private fun docxWithList(numbering: String, body: String) = DocxMarkup.convert(
        zipPackage(
            ZipParts.Ooxml,
            "word/document.xml" to "<w:document $wNs><w:body>$body</w:body></w:document>",
            "word/numbering.xml" to "<w:numbering $wNs>$numbering</w:numbering>",
        ),
    )

    private fun listParagraph(text: String, numId: Int, level: Int = 0) =
        "<w:p><w:pPr><w:numPr><w:ilvl w:val=\"$level\"/><w:numId w:val=\"$numId\"/>" +
            "</w:numPr></w:pPr><w:r><w:t>$text</w:t></w:r></w:p>"

    @Test
    fun unaListaNumeradaSaleComoOl() {
        val result = docxWithList(
            numbering = "<w:abstractNum w:abstractNumId=\"0\"><w:lvl w:ilvl=\"0\">" +
                "<w:numFmt w:val=\"decimal\"/></w:lvl></w:abstractNum>" +
                "<w:num w:numId=\"1\"><w:abstractNumId w:val=\"0\"/></w:num>",
            body = listParagraph("uno", numId = 1) + listParagraph("dos", numId = 1),
        )
        assertTrue(result.body.contains("<ol>"))
        assertFalse(result.body.contains("<ul>"))
        assertTrue(result.body.contains("<li>uno</li>"))
    }

    @Test
    fun unaListaDeVinetasSigueSaliendoComoUl() {
        val result = docxWithList(
            numbering = "<w:abstractNum w:abstractNumId=\"0\"><w:lvl w:ilvl=\"0\">" +
                "<w:numFmt w:val=\"bullet\"/></w:lvl></w:abstractNum>" +
                "<w:num w:numId=\"1\"><w:abstractNumId w:val=\"0\"/></w:num>",
            body = listParagraph("punto", numId = 1),
        )
        assertTrue(result.body.contains("<ul>"))
        assertFalse(result.body.contains("<ol>"))
    }

    @Test
    fun sinDefinicionDeNumeracionSeAsumeVineta() {
        // Es lo que menos molesta: numerar algo que no lo es induce a error.
        val result = docxWithList(numbering = "", body = listParagraph("suelto", numId = 7))
        assertTrue(result.body.contains("<ul>"))
    }

    @Test
    fun lasListasAnidadasSeAbrenYCierranPorNivel() {
        val result = docxWithList(
            numbering = "<w:abstractNum w:abstractNumId=\"0\">" +
                "<w:lvl w:ilvl=\"0\"><w:numFmt w:val=\"bullet\"/></w:lvl>" +
                "<w:lvl w:ilvl=\"1\"><w:numFmt w:val=\"bullet\"/></w:lvl></w:abstractNum>" +
                "<w:num w:numId=\"1\"><w:abstractNumId w:val=\"0\"/></w:num>",
            body = listParagraph("padre", numId = 1, level = 0) +
                listParagraph("hijo", numId = 1, level = 1) +
                listParagraph("otro padre", numId = 1, level = 0),
        )
        // Dos aperturas y dos cierres: la exterior y la anidada.
        assertTrue(result.body.split("<ul>").size - 1 == 2)
        assertTrue(result.body.split("</ul>").size - 1 == 2)
    }

    @Test
    fun unParrafoNormalCierraLaLista() {
        val result = docxWithList(
            numbering = "",
            body = listParagraph("elemento", numId = 1) +
                "<w:p><w:r><w:t>Parrafo suelto</w:t></w:r></w:p>",
        )
        assertTrue(result.body.indexOf("</ul>") < result.body.indexOf("Parrafo suelto"))
    }

    // ---------------------------------------------------- imagenes incrustadas

    @Test
    fun unaImagenDeWordSeIncrustaComoDataUri() {
        val result = DocxMarkup.convert(
            zipPackage(
                ZipParts.Ooxml,
                "word/document.xml" to
                    "<w:document $wNs $aNs><w:body><w:p><w:r><w:drawing>" +
                    "<a:blip r:embed=\"rId5\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"/>" +
                    "</w:drawing></w:r></w:p></w:body></w:document>",
                "word/_rels/document.xml.rels" to
                    "<Relationships $relNs><Relationship Id=\"rId5\" Target=\"media/image1.png\"/></Relationships>",
                "word/media/image1.png" to onePixelPng,
            ),
        )
        assertTrue(result.body.contains("<img src=\"data:image/png;base64,"))
    }

    @Test
    fun unaImagenQueNoExisteNoRompeElDocumento() {
        val result = DocxMarkup.convert(
            zipPackage(
                ZipParts.Ooxml,
                "word/document.xml" to
                    "<w:document $wNs $aNs><w:body><w:p><w:r><w:drawing>" +
                    "<a:blip r:embed=\"rId9\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"/>" +
                    "</w:drawing><w:t>Texto</w:t></w:r></w:p></w:body></w:document>",
                "word/_rels/document.xml.rels" to "<Relationships $relNs/>",
            ),
        )
        assertFalse(result.body.contains("<img"))
        assertTrue(result.body.contains("Texto"))
    }

    @Test
    fun unaImagenDeOpenDocumentSeIncrusta() {
        val result = OdtMarkup.convert(
            zipPackage(
                ZipParts.Odf,
                "content.xml" to
                    "<office:document-content $ODF_NS><office:body><office:text><text:p>" +
                    "<draw:frame><draw:image xlink:href=\"Pictures/foto.png\" " +
                    "xmlns:xlink=\"http://www.w3.org/1999/xlink\"/></draw:frame>" +
                    "</text:p></office:text></office:body></office:document-content>",
                "Pictures/foto.png" to onePixelPng,
            ),
        )
        assertTrue(result.body.contains("<img src=\"data:image/png;base64,"))
    }
}
