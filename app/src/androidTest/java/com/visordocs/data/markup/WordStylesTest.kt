package com.visordocs.data.markup

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.visordocs.data.zip.ZipParts
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Colores que Word hereda de sus estilos.
 *
 * Es el caso normal, no el raro: casi ningun documento pinta el color en el propio texto.
 * Un titulo es azul porque lo dice "Heading1", y ese estilo suele heredar de otro.
 */
@RunWith(AndroidJUnit4::class)
class WordStylesTest {

    private val ns = "xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\""

    private fun docx(body: String, styles: String): Markup = DocxMarkup.convert(
        zipPackage(
            ZipParts.Ooxml,
            "word/styles.xml" to "<w:styles $ns>$styles</w:styles>",
            "word/document.xml" to "<w:document $ns><w:body>$body</w:body></w:document>",
        ),
    )

    private fun paragraph(styleId: String?, text: String, runProps: String = ""): String {
        val pStyle = styleId?.let { "<w:pPr><w:pStyle w:val=\"$it\"/></w:pPr>" }.orEmpty()
        return "<w:p>$pStyle<w:r>$runProps<w:t>$text</w:t></w:r></w:p>"
    }

    @Test
    fun elColorDelEstiloDeParrafoLlegaAlTexto() {
        val result = docx(
            body = paragraph("Titulo", "Encabezado azul"),
            styles = "<w:style w:styleId=\"Titulo\"><w:rPr><w:color w:val=\"1F4E79\"/></w:rPr></w:style>",
        )
        assertTrue("falta el azul del estilo: ${result.body}", result.body.contains("color:#1F4E79"))
    }

    @Test
    fun laHerenciaDeBasedOnSeResuelve() {
        // "Titulo2" no dice ningun color: lo saca de "Titulo1", del que deriva.
        val result = docx(
            body = paragraph("Titulo2", "Heredado"),
            styles = "<w:style w:styleId=\"Titulo1\"><w:rPr><w:color w:val=\"C00000\"/></w:rPr></w:style>" +
                "<w:style w:styleId=\"Titulo2\"><w:basedOn w:val=\"Titulo1\"/></w:style>",
        )
        assertTrue("no heredo el color: ${result.body}", result.body.contains("color:#C00000"))
    }

    @Test
    fun elEstiloHijoGanaAlPadre() {
        val result = docx(
            body = paragraph("Hijo", "Propio"),
            styles = "<w:style w:styleId=\"Padre\"><w:rPr><w:color w:val=\"C00000\"/></w:rPr></w:style>" +
                "<w:style w:styleId=\"Hijo\"><w:basedOn w:val=\"Padre\"/>" +
                "<w:rPr><w:color w:val=\"2E7D32\"/></w:rPr></w:style>",
        )
        assertTrue("deberia mandar el hijo: ${result.body}", result.body.contains("color:#2E7D32"))
        assertFalse("no deberia quedar el del padre", result.body.contains("color:#C00000"))
    }

    @Test
    fun loQueDeclaraElTramoGanaAlEstilo() {
        val result = docx(
            body = paragraph(
                "Titulo",
                "Directo",
                runProps = "<w:rPr><w:color w:val=\"2E7D32\"/></w:rPr>",
            ),
            styles = "<w:style w:styleId=\"Titulo\"><w:rPr><w:color w:val=\"C00000\"/></w:rPr></w:style>",
        )
        assertTrue("deberia mandar el tramo: ${result.body}", result.body.contains("color:#2E7D32"))
    }

    @Test
    fun elEstiloDeCaracterGanaAlDeParrafo() {
        val result = docx(
            body = "<w:p><w:pPr><w:pStyle w:val=\"Parrafo\"/></w:pPr>" +
                "<w:r><w:rPr><w:rStyle w:val=\"Enfasis\"/></w:rPr><w:t>Mixto</w:t></w:r></w:p>",
            styles = "<w:style w:styleId=\"Parrafo\"><w:rPr><w:color w:val=\"C00000\"/></w:rPr></w:style>" +
                "<w:style w:styleId=\"Enfasis\"><w:rPr><w:color w:val=\"2E7D32\"/></w:rPr></w:style>",
        )
        assertTrue("deberia mandar el de caracter: ${result.body}", result.body.contains("color:#2E7D32"))
    }

    @Test
    fun elResaltadoTambienSeHereda() {
        val result = docx(
            body = paragraph("Marcado", "Resaltado"),
            styles = "<w:style w:styleId=\"Marcado\"><w:rPr><w:highlight w:val=\"yellow\"/></w:rPr></w:style>",
        )
        assertTrue("falta el fondo amarillo: ${result.body}", result.body.contains("background:#FFFF00"))
        // Con fondo claro, la letra tiene que forzarse a negra para poder leerse.
        assertTrue("falta la letra legible: ${result.body}", result.body.contains("color:#000000"))
    }

    /**
     * `basedOn` es una referencia por nombre, y nada impide que dos estilos se apunten
     * mutuamente. Sin corte, abrir ese documento colgaria la app.
     */
    @Test
    fun unaHerenciaCircularNoCuelgaLaApp() {
        val result = docx(
            body = paragraph("A", "Texto"),
            styles = "<w:style w:styleId=\"A\"><w:basedOn w:val=\"B\"/></w:style>" +
                "<w:style w:styleId=\"B\"><w:basedOn w:val=\"A\"/></w:style>",
        )
        assertTrue(result.body.contains("Texto"))
    }

    @Test
    fun losValoresPorOmisionDelDocumentoSeAplican() {
        val result = docx(
            body = paragraph(null, "Sin estilo"),
            styles = "<w:docDefaults><w:rPrDefault><w:rPr>" +
                "<w:color w:val=\"CC0000\"/></w:rPr></w:rPrDefault></w:docDefaults>",
        )
        assertTrue("faltan los valores por omision: ${result.body}", result.body.contains("color:#CC0000"))
    }

    @Test
    fun unNegroHeredadoNoRompeElModoOscuro() {
        // Es lo que traen casi todas las plantillas, y aplicarlo pintaria negro sobre
        // negro en modo oscuro.
        val result = docx(
            body = paragraph("Normal", "Texto"),
            styles = "<w:style w:styleId=\"Normal\"><w:rPr><w:color w:val=\"000000\"/></w:rPr></w:style>",
        )
        assertFalse("el negro no debe aplicarse: ${result.body}", result.body.contains("color:#000000"))
    }

    @Test
    fun sinStylesXmlElDocumentoSigueAbriendose() {
        val result = DocxMarkup.convert(
            zipPackage(
                ZipParts.Ooxml,
                "word/document.xml" to
                    "<w:document $ns><w:body><w:p><w:r><w:t>Hola</w:t></w:r></w:p></w:body></w:document>",
            ),
        )
        assertTrue(result.body.contains("Hola"))
    }
}
