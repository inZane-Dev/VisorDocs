package com.visordocs.data.markup

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.visordocs.data.zip.ZipParts
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PptxMarkupTest {

    private val relNs = "xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\""

    private fun slide(title: String?, vararg bullets: String): String {
        val titleShape = title?.let {
            "<p:sp><p:nvSpPr><p:nvPr><p:ph type=\"title\"/></p:nvPr></p:nvSpPr>" +
                "<p:txBody><a:p><a:r><a:t>$it</a:t></a:r></a:p></p:txBody></p:sp>"
        }.orEmpty()
        val body = bullets.joinToString("") { "<a:p><a:r><a:t>$it</a:t></a:r></a:p>" }
        return "<p:sld xmlns:p=\"http://schemas.openxmlformats.org/presentationml/2006/main\" " +
            "xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\"><p:cSld><p:spTree>" +
            titleShape +
            "<p:sp><p:nvSpPr><p:nvPr><p:ph type=\"body\" idx=\"1\"/></p:nvPr></p:nvSpPr>" +
            "<p:txBody>$body</p:txBody></p:sp>" +
            "</p:spTree></p:cSld></p:sld>"
    }

    @Test
    fun sinDiapositivasDevuelveVacio() {
        val pkg = zipPackage(ZipParts.Ooxml, "docProps/app.xml" to "<props/>")
        assertTrue(PptxMarkup.convert(pkg, TEST_LABELS).isEmpty)
    }

    /**
     * El orden lo manda el `spine` de la presentacion, no el nombre del archivo. Aqui
     * `slide2.xml` va declarado primero a proposito.
     */
    @Test
    fun respetaElOrdenDeLaPresentacionNoElDelNombreDeArchivo() {
        val pkg = zipPackage(
            ZipParts.Ooxml,
            "ppt/presentation.xml" to
                "<p:presentation xmlns:p=\"http://schemas.openxmlformats.org/presentationml/2006/main\" " +
                "xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\">" +
                "<p:sldIdLst><p:sldId id=\"256\" r:id=\"rId2\"/><p:sldId id=\"257\" r:id=\"rId1\"/></p:sldIdLst>" +
                "</p:presentation>",
            "ppt/_rels/presentation.xml.rels" to
                "<Relationships $relNs>" +
                "<Relationship Id=\"rId1\" Target=\"slides/slide1.xml\"/>" +
                "<Relationship Id=\"rId2\" Target=\"slides/slide2.xml\"/></Relationships>",
            "ppt/slides/slide1.xml" to slide("Segunda en el orden"),
            "ppt/slides/slide2.xml" to slide("Primera en el orden"),
        )

        val body = PptxMarkup.convert(pkg, TEST_LABELS).body
        assertTrue(body.indexOf("Primera en el orden") < body.indexOf("Segunda en el orden"))
    }

    @Test
    fun sinRelacionesOrdenaPorNumeroNoAlfabeticamente() {
        // Alfabeticamente "slide10" iria antes que "slide2".
        val pkg = zipPackage(
            ZipParts.Ooxml,
            "ppt/slides/slide2.xml" to slide("Dos"),
            "ppt/slides/slide10.xml" to slide("Diez"),
        )
        val body = PptxMarkup.convert(pkg, TEST_LABELS).body
        assertTrue(body.indexOf("Dos") < body.indexOf("Diez"))
    }

    @Test
    fun elTituloSaleComoEncabezadoYLasVinetasComoParrafos() {
        val pkg = zipPackage(
            ZipParts.Ooxml,
            "ppt/slides/slide1.xml" to slide("Objetivos", "Punto uno", "Punto dos"),
        )
        val body = PptxMarkup.convert(pkg, TEST_LABELS).body
        assertTrue(body.contains("<h2>Objetivos</h2>"))
        assertTrue(body.contains("<p>Punto uno</p>"))
        assertTrue(body.contains("<p>Punto dos</p>"))
    }

    @Test
    fun cadaDiapositivaSeNumeraConSuEtiqueta() {
        val pkg = zipPackage(
            ZipParts.Ooxml,
            "ppt/slides/slide1.xml" to slide("Una"),
            "ppt/slides/slide2.xml" to slide("Otra"),
        )
        val body = PptxMarkup.convert(pkg, TEST_LABELS).body
        assertTrue(body.contains("Diapositiva 1"))
        assertTrue(body.contains("Diapositiva 2"))
    }
}
