package com.visordocs.data.zip

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ZipPackageTest {

    private fun zipOf(vararg entries: Pair<String, String>): ByteArrayInputStream {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            for ((name, content) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
        return ByteArrayInputStream(out.toByteArray())
    }

    @Test
    fun `lee las partes que pasan el filtro`() {
        val pkg = ZipPackage.read(
            zipOf("word/document.xml" to "<w:p/>", "docProps/app.xml" to "<props/>"),
            ZipParts.Ooxml,
        )
        assertEquals("<w:p/>", pkg.text("word/document.xml"))
        assertEquals("<props/>", pkg.text("docProps/app.xml"))
    }

    @Test
    fun `descarta las partes que no pasan el filtro`() {
        val pkg = ZipPackage.read(
            zipOf(
                "word/document.xml" to "<w:p/>",
                "word/embeddings/objeto.bin" to "binario",
                "word/fontTable.bin" to "fuentes",
            ),
            ZipParts.Ooxml,
        )
        // Los objetos incrustados y las fuentes no se muestran: no deben ocupar memoria.
        assertNull(pkg.text("word/embeddings/objeto.bin"))
        assertNull(pkg.text("word/fontTable.bin"))
    }

    @Test
    fun `conserva las imagenes para poder incrustarlas`() {
        val pkg = ZipPackage.read(
            zipOf("word/document.xml" to "<w:p/>", "word/media/foto.png" to "pixeles"),
            ZipParts.Ooxml,
        )
        // Se muestran incrustadas como data URI, asi que hacen falta sus bytes.
        assertEquals("pixeles", pkg.bytes("word/media/foto.png")?.toString(Charsets.UTF_8))
    }

    @Test
    fun `el filtro de EPUB acepta xhtml y opf`() {
        val pkg = ZipPackage.read(
            zipOf("OEBPS/content.opf" to "<package/>", "OEBPS/cap1.xhtml" to "<html/>"),
            ZipParts.Epub,
        )
        assertEquals("<package/>", pkg.text("OEBPS/content.opf"))
        assertEquals("<html/>", pkg.text("OEBPS/cap1.xhtml"))
    }

    @Test
    fun `el filtro de OOXML rechaza el xhtml de un EPUB`() {
        // Confirma que los filtros no son intercambiables.
        assertTrue(ZipParts.Ooxml("cap1.xhtml").not())
        assertTrue(ZipParts.Epub("cap1.xhtml"))
    }

    @Test
    fun `names devuelve las partes de un prefijo ordenadas`() {
        val pkg = ZipPackage.read(
            zipOf(
                "ppt/slides/slide2.xml" to "b",
                "ppt/slides/slide1.xml" to "a",
                "ppt/presentation.xml" to "p",
            ),
            ZipParts.Ooxml,
        )
        assertEquals(
            listOf("ppt/slides/slide1.xml", "ppt/slides/slide2.xml"),
            pkg.names("ppt/slides/"),
        )
    }

    @Test(expected = IOException::class)
    fun `un ZIP sin partes utiles falla en lugar de devolver algo vacio`() {
        // Mejor un error claro que un documento en blanco sin explicacion.
        ZipPackage.read(zipOf("datos.bin" to "binario"), ZipParts.Ooxml)
    }

    @Test
    fun `una parte que pasa del tope por entrada se descarta sin tumbar el resto`() {
        val enorme = "x".repeat(9 * 1024 * 1024)
        val pkg = ZipPackage.read(
            zipOf("word/document.xml" to "<w:p/>", "word/enorme.xml" to enorme),
            ZipParts.Ooxml,
        )
        assertEquals("<w:p/>", pkg.text("word/document.xml"))
        // Se ignora la parte desmesurada, pero el documento sigue abriendose.
        assertNull(pkg.text("word/enorme.xml"))
    }
}
