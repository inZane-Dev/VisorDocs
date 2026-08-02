package com.visordocs.data.markup

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.visordocs.data.zip.ZipParts
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DocxMarkupTest {

    private fun docx(body: String) = DocxMarkup.convert(
        zipPackage(
            ZipParts.Ooxml,
            "word/document.xml" to
                "<?xml version=\"1.0\"?><w:document " +
                "xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\">" +
                "<w:body>$body</w:body></w:document>",
        ),
    )

    private fun run(text: String, props: String = "") =
        "<w:r>${if (props.isEmpty()) "" else "<w:rPr>$props</w:rPr>"}<w:t>$text</w:t></w:r>"

    @Test
    fun sinParteDeDocumentoDevuelveVacio() {
        val pkg = zipPackage(ZipParts.Ooxml, "docProps/app.xml" to "<props/>")
        assertTrue(DocxMarkup.convert(pkg).isEmpty)
    }

    /**
     * Regresion del fallo real: el formato se reiniciaba al abrir `<w:rPr>`, asi que un
     * run sin propiedades heredaba el del anterior y el tachado se comia media frase.
     */
    @Test
    fun elFormatoNoSeDerramaAlRunSiguiente() {
        val result = docx(
            "<w:p>" +
                run("tachado", "<w:strike/>") +
                run(". Texto normal") +
                "</w:p>",
        )
        assertTrue(result.body.contains("<s>tachado</s>"))
        assertFalse(result.body.contains("<s>tachado. Texto normal</s>"))
        assertTrue(result.body.contains(". Texto normal"))
    }

    @Test
    fun laComaTrasUnaNegritaNoSaleEnNegrita() {
        val result = docx("<w:p>" + run("negrita", "<w:b/>") + run(", resto") + "</w:p>")
        assertTrue(result.body.contains("<strong>negrita</strong>"))
        assertFalse(result.body.contains("<strong>negrita, resto</strong>"))
    }

    @Test
    fun losEstilosDeEncabezadoSeTraducenASuNivel() {
        val result = docx(
            "<w:p><w:pPr><w:pStyle w:val=\"Title\"/></w:pPr>" + run("Titulo") + "</w:p>" +
                "<w:p><w:pPr><w:pStyle w:val=\"Heading2\"/></w:pPr>" + run("Nivel dos") + "</w:p>",
        )
        assertTrue(result.body.contains("<h1>Titulo</h1>"))
        assertTrue(result.body.contains("<h2>Nivel dos</h2>"))
    }

    @Test
    fun aceptaLosEstilosEnEspanolDeAlgunasVersionesDeWord() {
        val result = docx("<w:p><w:pPr><w:pStyle w:val=\"Ttulo3\"/></w:pPr>" + run("Tres") + "</w:p>")
        assertTrue(result.body.contains("<h3>Tres</h3>"))
    }

    @Test
    fun bConValorCeroNoActivaLaNegrita() {
        val result = docx("<w:p>" + run("normal", "<w:b w:val=\"0\"/>") + "</w:p>")
        assertFalse(result.body.contains("<strong>"))
    }

    @Test
    fun losElementosDeListaSeAgrupanEnUnaSolaLista() {
        val numbering = "<w:pPr><w:numPr><w:ilvl w:val=\"0\"/><w:numId w:val=\"1\"/></w:numPr></w:pPr>"
        val result = docx(
            "<w:p>$numbering${run("uno")}</w:p><w:p>$numbering${run("dos")}</w:p>",
        )
        assertTrue(result.body.contains("<li>uno</li>"))
        assertTrue(result.body.contains("<li>dos</li>"))
        // Una apertura de lista, no una por elemento.
        assertTrue(result.body.split("<ul>").size - 1 == 1)
    }

    @Test
    fun laAlineacionSeTrasladaAUnaClaseCss() {
        val result = docx(
            "<w:p><w:pPr><w:jc w:val=\"center\"/></w:pPr>" + run("centrado") + "</w:p>",
        )
        assertTrue(result.body.contains("class=\"center\""))
    }

    @Test
    fun lasTablasSeEnvuelvenParaPoderDesplazarlas() {
        val result = docx(
            "<w:tbl><w:tr><w:tc><w:p>" + run("celda") + "</w:p></w:tc></w:tr></w:tbl>",
        )
        assertTrue(result.body.contains("scroll-x"))
        assertTrue(result.body.contains("<td>"))
        assertTrue(result.body.contains("celda"))
    }

    @Test
    fun elTextoDelDocumentoSeEscapa() {
        val result = docx("<w:p>" + run("&lt;script&gt;alert(1)&lt;/script&gt;") + "</w:p>")
        assertFalse(result.body.contains("<script>"))
        assertTrue(result.body.contains("&lt;script&gt;"))
    }
}
