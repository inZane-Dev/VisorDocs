package com.visordocs.data.markup

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.visordocs.data.zip.ZipParts
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EpubMarkupTest {

    private val container =
        "<?xml version=\"1.0\"?><container version=\"1.0\" " +
            "xmlns=\"urn:oasis:names:tc:opendocument:xmlns:container\"><rootfiles>" +
            "<rootfile full-path=\"OEBPS/content.opf\" media-type=\"application/oebps-package+xml\"/>" +
            "</rootfiles></container>"

    private fun opf(spine: String, manifest: String) =
        "<?xml version=\"1.0\"?><package xmlns=\"http://www.idpf.org/2007/opf\" version=\"3.0\">" +
            "<manifest>$manifest</manifest><spine>$spine</spine></package>"

    private fun chapter(body: String) =
        "<?xml version=\"1.0\"?><html xmlns=\"http://www.w3.org/1999/xhtml\">" +
            "<head><title>t</title></head><body>$body</body></html>"

    @Test
    fun sinIndiceDevuelveVacio() {
        val pkg = zipPackage(ZipParts.Epub, "mimetype.xml" to "<x/>")
        assertTrue(EpubMarkup.convert(pkg).isEmpty)
    }

    /** El orden de lectura lo manda el spine, no el nombre del archivo. */
    @Test
    fun respetaElOrdenDelSpine() {
        val pkg = zipPackage(
            ZipParts.Epub,
            "META-INF/container.xml" to container,
            "OEBPS/content.opf" to opf(
                spine = "<itemref idref=\"b\"/><itemref idref=\"a\"/>",
                manifest = "<item id=\"a\" href=\"a.xhtml\" media-type=\"application/xhtml+xml\"/>" +
                    "<item id=\"b\" href=\"b.xhtml\" media-type=\"application/xhtml+xml\"/>",
            ),
            "OEBPS/a.xhtml" to chapter("<h1>Segundo</h1>"),
            "OEBPS/b.xhtml" to chapter("<h1>Primero</h1>"),
        )
        val body = EpubMarkup.convert(pkg).body
        assertTrue(body.indexOf("Primero") < body.indexOf("Segundo"))
    }

    /**
     * El saneado es una lista blanca: lo que no se reconoce se descarta con sus
     * atributos. Asi no llegan al WebView ni scripts ni estilos del libro.
     */
    @Test
    fun descartaScriptsEstilosYAtributos() {
        val pkg = zipPackage(
            ZipParts.Epub,
            "META-INF/container.xml" to container,
            "OEBPS/content.opf" to opf(
                spine = "<itemref idref=\"a\"/>",
                manifest = "<item id=\"a\" href=\"a.xhtml\" media-type=\"application/xhtml+xml\"/>",
            ),
            "OEBPS/a.xhtml" to
                "<?xml version=\"1.0\"?><html xmlns=\"http://www.w3.org/1999/xhtml\">" +
                "<head><style>body{color:red}</style><script>alert(1)</script></head>" +
                "<body><p class=\"peligro\" onclick=\"robar()\">Texto legitimo</p></body></html>",
        )
        val body = EpubMarkup.convert(pkg).body

        assertFalse(body.contains("alert"))
        assertFalse(body.contains("color:red"))
        assertFalse(body.contains("onclick"))
        assertFalse(body.contains("peligro"))
        assertTrue(body.contains("<p>Texto legitimo</p>"))
    }

    @Test
    fun conservaLaEstructuraDeLosCapitulos() {
        val pkg = zipPackage(
            ZipParts.Epub,
            "META-INF/container.xml" to container,
            "OEBPS/content.opf" to opf(
                spine = "<itemref idref=\"a\"/>",
                manifest = "<item id=\"a\" href=\"a.xhtml\" media-type=\"application/xhtml+xml\"/>",
            ),
            "OEBPS/a.xhtml" to chapter(
                "<h1>Capitulo</h1><p>Con <strong>negrita</strong> y <em>cursiva</em>.</p>" +
                    "<ul><li>uno</li></ul><blockquote>cita</blockquote>",
            ),
        )
        val body = EpubMarkup.convert(pkg).body
        assertTrue(body.contains("<h1>Capitulo</h1>"))
        assertTrue(body.contains("<strong>negrita</strong>"))
        assertTrue(body.contains("<em>cursiva</em>"))
        assertTrue(body.contains("<li>uno</li>"))
        assertTrue(body.contains("<blockquote>cita</blockquote>"))
    }

    @Test
    fun unCapituloMalFormadoNoTumbaElLibro() {
        val pkg = zipPackage(
            ZipParts.Epub,
            "META-INF/container.xml" to container,
            "OEBPS/content.opf" to opf(
                spine = "<itemref idref=\"malo\"/><itemref idref=\"bueno\"/>",
                manifest = "<item id=\"malo\" href=\"malo.xhtml\" media-type=\"application/xhtml+xml\"/>" +
                    "<item id=\"bueno\" href=\"bueno.xhtml\" media-type=\"application/xhtml+xml\"/>",
            ),
            "OEBPS/malo.xhtml" to "<html><body><p>sin cerrar",
            "OEBPS/bueno.xhtml" to chapter("<p>Capitulo intacto</p>"),
        )
        // El capitulo roto se salta; el resto del libro sigue leyendose.
        assertTrue(EpubMarkup.convert(pkg).body.contains("Capitulo intacto"))
    }
}
