package com.visordocs.data.markup

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.visordocs.data.zip.ZipParts
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Colores de las celdas de Excel, de punta a punta.
 *
 * La cadena tiene tres saltos —la celda apunta a `<cellXfs>`, esa entrada apunta a
 * `<fills>` y `<fonts>`, y el color puede venir en cuatro notaciones— y ninguno de ellos
 * avisa cuando se resuelve mal: simplemente sale otro color, o ninguno.
 */
@RunWith(AndroidJUnit4::class)
class XlsxColorsTest {

    private val ns = "xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\""
    private val relNs = "xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\""

    /**
     * Estilos con tres entradas en `<cellXfs>`:
     * 0 = sin nada, 1 = relleno verde con letra blanca, 2 = letra roja sin relleno.
     *
     * Los dos primeros `<fill>` son los reservados que Excel escribe siempre, asi que el
     * verde queda en la posicion 2. Equivocarse ahi es el fallo clasico del formato.
     */
    private val styles = """
        <styleSheet $ns>
          <fonts count="3">
            <font><color theme="1"/></font>
            <font><color rgb="FFFFFFFF"/></font>
            <font><color rgb="FFCC0000"/></font>
          </fonts>
          <fills count="3">
            <fill><patternFill patternType="none"/></fill>
            <fill><patternFill patternType="gray125"/></fill>
            <fill><patternFill patternType="solid"><fgColor rgb="FF2E7D32"/></patternFill></fill>
          </fills>
          <cellXfs count="3">
            <xf numFmtId="0" fontId="0" fillId="0"/>
            <xf numFmtId="0" fontId="1" fillId="2" applyFont="1" applyFill="1"/>
            <xf numFmtId="0" fontId="2" fillId="0" applyFont="1"/>
          </cellXfs>
        </styleSheet>
    """.trimIndent()

    private fun xlsx(rows: String, stylesXml: String = styles): Markup = XlsxMarkup.convert(
        zipPackage(
            ZipParts.Ooxml,
            "xl/workbook.xml" to
                "<workbook $ns xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\">" +
                "<sheets><sheet name=\"Datos\" sheetId=\"1\" r:id=\"rId1\"/></sheets></workbook>",
            "xl/_rels/workbook.xml.rels" to
                "<Relationships $relNs><Relationship Id=\"rId1\" Target=\"worksheets/sheet1.xml\"/></Relationships>",
            "xl/styles.xml" to stylesXml,
            "xl/worksheets/sheet1.xml" to "<worksheet $ns><sheetData>$rows</sheetData></worksheet>",
        ),
        TEST_LABELS,
    )

    @Test
    fun elRellenoDeLaCeldaLlegaAlHtml() {
        val result = xlsx(
            "<row r=\"1\"><c r=\"A1\" s=\"1\" t=\"inlineStr\"><is><t>Cabecera</t></is></c></row>",
        )
        assertTrue("falta el fondo verde: ${result.body}", result.body.contains("background:#2E7D32"))
    }

    @Test
    fun laLetraDeclaradaSeRespetaSiContrastaConElFondo() {
        val result = xlsx(
            "<row r=\"1\"><c r=\"A1\" s=\"1\" t=\"inlineStr\"><is><t>Cabecera</t></is></c></row>",
        )
        assertTrue("falta la letra blanca: ${result.body}", result.body.contains("color:#FFFFFF"))
    }

    @Test
    fun elColorDeLetraSinRellenoTambienSeAplica() {
        val result = xlsx(
            "<row r=\"1\"><c r=\"A1\" s=\"2\" t=\"inlineStr\"><is><t>Alerta</t></is></c></row>",
        )
        assertTrue("falta el rojo: ${result.body}", result.body.contains("color:#CC0000"))
        assertFalse("no deberia poner fondo", result.body.contains("background:"))
    }

    @Test
    fun unaCeldaSinEstiloNoLlevaColorNinguno() {
        val result = xlsx(
            "<row r=\"1\"><c r=\"A1\" s=\"0\" t=\"inlineStr\"><is><t>Normal</t></is></c></row>",
        )
        assertFalse("no deberia llevar estilo: ${result.body}", result.body.contains("style=\""))
    }

    /**
     * La regla que mantiene vivo el modo oscuro: un texto declarado negro es el color por
     * omision escrito de otra forma. Si se aplicara, en modo oscuro seria negro sobre
     * negro.
     */
    @Test
    fun elTextoNegroDeclaradoNoSeAplicaParaNoRomperElModoOscuro() {
        val negro = """
            <styleSheet $ns>
              <fonts count="1"><font><color rgb="FF000000"/></font></fonts>
              <fills count="2">
                <fill><patternFill patternType="none"/></fill>
                <fill><patternFill patternType="gray125"/></fill>
              </fills>
              <cellXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" applyFont="1"/></cellXfs>
            </styleSheet>
        """.trimIndent()

        val result = xlsx(
            "<row r=\"1\"><c r=\"A1\" s=\"0\" t=\"inlineStr\"><is><t>Texto</t></is></c></row>",
            stylesXml = negro,
        )
        assertFalse("el negro no debe aplicarse: ${result.body}", result.body.contains("color:#000000"))
    }

    /**
     * Con relleno, el texto tiene que leerse encima si o si. Un documento que pide letra
     * negra sobre fondo casi negro es un documento mal hecho, y el visor lo corrige en
     * lugar de mostrar un rectangulo ilegible.
     */
    @Test
    fun siLaLetraNoContrastaConSuFondoSeSustituyePorUnaLegible() {
        val malo = """
            <styleSheet $ns>
              <fonts count="1"><font><color rgb="FF000000"/></font></fonts>
              <fills count="3">
                <fill><patternFill patternType="none"/></fill>
                <fill><patternFill patternType="gray125"/></fill>
                <fill><patternFill patternType="solid"><fgColor rgb="FF111111"/></patternFill></fill>
              </fills>
              <cellXfs count="1">
                <xf numFmtId="0" fontId="0" fillId="2" applyFont="1" applyFill="1"/>
              </cellXfs>
            </styleSheet>
        """.trimIndent()

        val result = xlsx(
            "<row r=\"1\"><c r=\"A1\" s=\"0\" t=\"inlineStr\"><is><t>Texto</t></is></c></row>",
            stylesXml = malo,
        )
        assertTrue("falta el fondo", result.body.contains("background:#111111"))
        assertTrue("deberia forzar blanco: ${result.body}", result.body.contains("color:#FFFFFF"))
    }

    @Test
    fun sinStylesXmlElDocumentoSigueAbriendose() {
        val result = XlsxMarkup.convert(
            zipPackage(
                ZipParts.Ooxml,
                "xl/workbook.xml" to
                    "<workbook $ns xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\">" +
                    "<sheets><sheet name=\"Datos\" sheetId=\"1\" r:id=\"rId1\"/></sheets></workbook>",
                "xl/_rels/workbook.xml.rels" to
                    "<Relationships $relNs><Relationship Id=\"rId1\" Target=\"worksheets/sheet1.xml\"/></Relationships>",
                "xl/worksheets/sheet1.xml" to
                    "<worksheet $ns><sheetData><row r=\"1\">" +
                    "<c r=\"A1\" t=\"inlineStr\"><is><t>Hola</t></is></c></row></sheetData></worksheet>",
            ),
            TEST_LABELS,
        )
        assertTrue(result.body.contains("Hola"))
    }
}
