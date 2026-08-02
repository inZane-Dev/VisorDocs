package com.visordocs.data.markup

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.visordocs.data.zip.ZipParts
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OdfMarkupTest {

    private fun content(body: String, styles: String = "") = zipPackage(
        ZipParts.Odf,
        "content.xml" to
            "<?xml version=\"1.0\"?><office:document-content $ODF_NS>" +
            (if (styles.isEmpty()) "" else "<office:automatic-styles>$styles</office:automatic-styles>") +
            "<office:body>$body</office:body></office:document-content>",
    )

    // ------------------------------------------------------------------ ODT

    @Test
    fun odtTraduceEncabezadosConSuNivel() {
        val pkg = content(
            "<office:text><text:h text:outline-level=\"1\">Titulo</text:h>" +
                "<text:h text:outline-level=\"3\">Subtitulo</text:h></office:text>",
        )
        val body = OdtMarkup.convert(pkg).body
        assertTrue(body.contains("<h1>Titulo</h1>"))
        assertTrue(body.contains("<h3>Subtitulo</h3>"))
    }

    /**
     * LibreOffice no marca el formato en el texto: declara estilos con nombre y los
     * referencia. Sin leer esa tabla, la negrita se perderia por completo.
     */
    @Test
    fun odtAplicaElFormatoDeLaTablaDeEstilos() {
        val pkg = content(
            body = "<office:text><text:p>Va <text:span text:style-name=\"T1\">en negrita</text:span> aqui</text:p></office:text>",
            styles = "<style:style style:name=\"T1\" style:family=\"text\">" +
                "<style:text-properties fo:font-weight=\"bold\"/></style:style>",
        )
        val body = OdtMarkup.convert(pkg).body
        assertTrue(body.contains("<strong>en negrita</strong>"))
        assertTrue(body.contains("Va "))
        assertTrue(body.contains(" aqui"))
    }

    @Test
    fun odtIgnoraLosEstilosQueNoSonDeTexto() {
        val pkg = content(
            body = "<office:text><text:p><text:span text:style-name=\"P1\">llano</text:span></text:p></office:text>",
            // Family "paragraph": describe margenes, no formato de texto.
            styles = "<style:style style:name=\"P1\" style:family=\"paragraph\">" +
                "<style:text-properties fo:font-weight=\"bold\"/></style:style>",
        )
        assertFalse(OdtMarkup.convert(pkg).body.contains("<strong>"))
    }

    @Test
    fun odtConvierteListasYTablas() {
        val pkg = content(
            "<office:text>" +
                "<text:list><text:list-item><text:p>uno</text:p></text:list-item></text:list>" +
                "<table:table><table:table-row><table:table-cell><text:p>celda</text:p>" +
                "</table:table-cell></table:table-row></table:table>" +
                "</office:text>",
        )
        val body = OdtMarkup.convert(pkg).body
        assertTrue(body.contains("<li>"))
        assertTrue(body.contains("uno"))
        assertTrue(body.contains("<td>"))
        assertTrue(body.contains("celda"))
    }

    @Test
    fun odtEscapaElTexto() {
        val pkg = content("<office:text><text:p>&lt;script&gt;x&lt;/script&gt;</text:p></office:text>")
        val body = OdtMarkup.convert(pkg).body
        assertFalse(body.contains("<script>"))
        assertTrue(body.contains("&lt;script&gt;"))
    }

    // ------------------------------------------------------------------ ODS

    /**
     * La trampa del formato: una hoja declara miles de columnas vacias repetidas hasta
     * el final. Expandirlas generaria millones de celdas y agotaria la memoria.
     */
    @Test
    fun odsNoExpandeLasRepeticionesDeCeldasVacias() {
        val pkg = content(
            "<office:spreadsheet><table:table table:name=\"Datos\">" +
                "<table:table-row>" +
                "<table:table-cell office:value-type=\"string\"><text:p>Unico</text:p></table:table-cell>" +
                "<table:table-cell table:number-columns-repeated=\"16382\"/>" +
                "</table:table-row>" +
                "</table:table></office:spreadsheet>",
        )
        val body = OdsMarkup.convert(pkg, TEST_LABELS).body
        assertTrue(body.contains("Unico"))
        // Con una sola celda con contenido, la tabla debe quedarse en una columna.
        assertTrue(body.split("<td").size - 1 < 10)
    }

    @Test
    fun odsNoExpandeLasRepeticionesDeFilasVacias() {
        val pkg = content(
            "<office:spreadsheet><table:table table:name=\"Datos\">" +
                "<table:table-row><table:table-cell office:value-type=\"string\">" +
                "<text:p>Dato</text:p></table:table-cell></table:table-row>" +
                "<table:table-row table:number-rows-repeated=\"1048570\"/>" +
                "</table:table></office:spreadsheet>",
        )
        val body = OdsMarkup.convert(pkg, TEST_LABELS).body
        assertTrue(body.contains("Dato"))
        assertTrue(body.split("<tr>").size - 1 < 10)
    }

    @Test
    fun odsMuestraCadaHojaConSuNombre() {
        val pkg = content(
            "<office:spreadsheet>" +
                "<table:table table:name=\"Ventas\"><table:table-row><table:table-cell " +
                "office:value-type=\"string\"><text:p>A</text:p></table:table-cell></table:table-row></table:table>" +
                "<table:table table:name=\"Resumen\"><table:table-row><table:table-cell " +
                "office:value-type=\"string\"><text:p>B</text:p></table:table-cell></table:table-row></table:table>" +
                "</office:spreadsheet>",
        )
        val body = OdsMarkup.convert(pkg, TEST_LABELS).body
        assertTrue(body.contains("Ventas"))
        assertTrue(body.contains("Resumen"))
    }

    @Test
    fun odsAlineaLosNumerosALaDerecha() {
        val pkg = content(
            "<office:spreadsheet><table:table table:name=\"D\"><table:table-row>" +
                "<table:table-cell office:value-type=\"float\" office:value=\"1250.5\">" +
                "<text:p>1250.5</text:p></table:table-cell>" +
                "</table:table-row></table:table></office:spreadsheet>",
        )
        assertTrue(OdsMarkup.convert(pkg, TEST_LABELS).body.contains("class=\"num\""))
    }

    // ------------------------------------------------------------------ ODP

    @Test
    fun odpSeparaTituloDeContenidoPorSuMarcador() {
        val pkg = content(
            "<office:presentation><draw:page draw:name=\"p1\">" +
                "<draw:frame presentation:class=\"title\"><draw:text-box>" +
                "<text:p>Mi titulo</text:p></draw:text-box></draw:frame>" +
                "<draw:frame presentation:class=\"outline\"><draw:text-box>" +
                "<text:p>Una vineta</text:p></draw:text-box></draw:frame>" +
                "</draw:page></office:presentation>",
        )
        val body = OdpMarkup.convert(pkg, TEST_LABELS).body
        assertTrue(body.contains("<h2>Mi titulo</h2>"))
        assertTrue(body.contains("<p>Una vineta</p>"))
    }

    @Test
    fun odpNumeraLasDiapositivasEnOrden() {
        val page = { n: String ->
            "<draw:page draw:name=\"$n\"><draw:frame presentation:class=\"title\">" +
                "<draw:text-box><text:p>$n</text:p></draw:text-box></draw:frame></draw:page>"
        }
        val pkg = content("<office:presentation>${page("Uno")}${page("Dos")}</office:presentation>")
        val body = OdpMarkup.convert(pkg, TEST_LABELS).body
        assertTrue(body.contains("Diapositiva 1"))
        assertTrue(body.contains("Diapositiva 2"))
        assertTrue(body.indexOf("Uno") < body.indexOf("Dos"))
    }

    @Test
    fun sinContenidoDevuelveVacio() {
        val pkg = zipPackage(ZipParts.Odf, "meta.xml" to "<meta/>")
        assertTrue(OdtMarkup.convert(pkg).isEmpty)
        assertTrue(OdsMarkup.convert(pkg, TEST_LABELS).isEmpty)
        assertTrue(OdpMarkup.convert(pkg, TEST_LABELS).isEmpty)
    }
}
