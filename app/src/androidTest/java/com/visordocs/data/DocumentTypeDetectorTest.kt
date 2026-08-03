package com.visordocs.data

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.visordocs.model.DocumentType
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Pruebas de la cascada de identificacion de formato.
 *
 * Es la pieza mas ramificada del proyecto y donde un fallo se ve directamente en
 * pantalla: un documento que no se reconoce, o que se abre con el visor equivocado.
 *
 * Corren en dispositivo porque el detector recibe un `ContentResolver`. Los archivos se
 * escriben en la cache y se pasan como `file://`, que el resolver sabe abrir; asi se
 * ejercita el camino real de lectura en lugar de un doble de pruebas.
 */
@RunWith(AndroidJUnit4::class)
class DocumentTypeDetectorTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val resolver get() = context.contentResolver

    /** Escribe el contenido en la cache y devuelve su URI. */
    private fun uriOf(fileName: String, bytes: ByteArray): Uri {
        val file = File(context.cacheDir, "detector_$fileName")
        file.writeBytes(bytes)
        return Uri.fromFile(file)
    }

    private fun detect(
        fileName: String = "muestra.bin",
        bytes: ByteArray = ByteArray(0),
        mimeHint: String? = null,
        displayName: String? = null,
    ): DocumentType = DocumentTypeDetector.detect(
        resolver = resolver,
        uri = uriOf(fileName, bytes),
        mimeHint = mimeHint,
        displayName = displayName,
    )

    // ------------------------------------------------- 1. el MIME de quien nos invoca

    @Test
    fun elMimeDeclaradoManda() {
        assertEquals(
            DocumentType.PDF,
            detect(mimeHint = "application/pdf", displayName = "cosa.txt"),
        )
    }

    @Test
    fun elMimeSeNormaliza() {
        // Mayusculas, espacios y el parametro de codificacion no deben estorbar.
        assertEquals(
            DocumentType.CSV,
            detect(mimeHint = "  TEXT/CSV; charset=utf-8  ", displayName = "sin-extension"),
        )
    }

    @Test
    fun elMimeConMacrosSeReconoce() {
        assertEquals(
            DocumentType.WORD,
            detect(mimeHint = "application/vnd.ms-word.document.macroenabled.12"),
        )
    }

    @Test
    fun losMimeGenericosDeImagenYTextoTienenRespaldo() {
        assertEquals(DocumentType.IMAGE, detect(mimeHint = "image/tiff"))
        assertEquals(DocumentType.PLAIN_TEXT, detect(mimeHint = "text/x-inventado"))
    }

    @Test
    fun elSvgNoSeTrataComoImagenDeMapaDeBits() {
        // Va antes que la regla generica `image/`: es texto y lo dibuja el WebView.
        assertEquals(DocumentType.SVG, detect(mimeHint = "image/svg+xml"))
    }

    @Test
    fun elHtmlNoSeTrataComoTextoPlano() {
        // Va antes que la regla generica `text/`, o se veria el codigo fuente.
        assertEquals(DocumentType.HTML, detect(mimeHint = "text/html"))
    }

    // ------------------------------------------------------------- 3. la extension

    @Test
    fun unMimeInutilCedeElPasoALaExtension() {
        // El caso de WhatsApp: entrega casi todo como octet-stream.
        assertEquals(
            DocumentType.EXCEL,
            detect(mimeHint = "application/octet-stream", displayName = "cuentas.xlsx"),
        )
    }

    @Test
    fun laExtensionNoDistingueMayusculas() {
        assertEquals(DocumentType.PDF, detect(displayName = "INFORME.PDF"))
    }

    @Test
    fun losNombresConVariosPuntosUsanLaUltimaExtension() {
        assertEquals(DocumentType.WORD, detect(displayName = "informe.final.v2.docx"))
    }

    @Test
    fun losBinariosAntiguosDeOfficeSeIdentificanParaPoderExplicarlos() {
        // No se pueden mostrar, pero reconocerlos permite dar un mensaje concreto en
        // lugar de un "formato no reconocido" que no ayuda a nadie.
        assertEquals(DocumentType.WORD_LEGACY, detect(displayName = "viejo.doc"))
        assertEquals(DocumentType.EXCEL_LEGACY, detect(displayName = "viejo.xls"))
        assertEquals(DocumentType.POWERPOINT_LEGACY, detect(displayName = "viejo.ppt"))
    }

    // --------------------------------------------------------- 4. los primeros bytes

    @Test
    fun sinMimeNiExtensionSeMiranLosPrimerosBytes() {
        assertEquals(
            DocumentType.PDF,
            detect(bytes = "%PDF-1.4\n...".toByteArray(), displayName = "sin-extension"),
        )
    }

    @Test
    fun seReconocenLasFirmasDeImagen() {
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        val gif = "GIF89a".toByteArray(Charsets.US_ASCII)
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte())

        assertEquals(DocumentType.IMAGE, detect(bytes = png, displayName = "sin-extension"))
        assertEquals(DocumentType.IMAGE, detect(bytes = gif, displayName = "sin-extension"))
        assertEquals(DocumentType.IMAGE, detect(bytes = jpeg, displayName = "sin-extension"))
    }

    @Test
    fun seReconoceElContenedorDeLasFotosModernas() {
        // HEIC: el tamano de caja ocupa 4 bytes, luego "ftyp" y luego la marca.
        val heic = byteArrayOf(0, 0, 0, 0x18) +
            "ftypheic".toByteArray(Charsets.US_ASCII) +
            ByteArray(8)
        assertEquals(DocumentType.IMAGE, detect(bytes = heic, displayName = "sin-extension"))
    }

    @Test
    fun unaMarcaDesconocidaDeEseContenedorNoSeDaPorImagen() {
        // Mismo contenedor, pero es un video: no debe acabar en el visor de imagenes.
        val mp4 = byteArrayOf(0, 0, 0, 0x18) +
            "ftypisom".toByteArray(Charsets.US_ASCII) +
            ByteArray(8)
        assertEquals(DocumentType.UNKNOWN, detect(bytes = mp4, displayName = "sin-extension"))
    }

    // -------------------------------------------- 4b. lo que va dentro de un ZIP

    @Test
    fun elOoxmlSeDistinguePorSuCarpetaRaiz() {
        assertEquals(
            DocumentType.WORD,
            detect(bytes = zip("word/document.xml" to "<x/>"), displayName = "sin-extension"),
        )
        assertEquals(
            DocumentType.EXCEL,
            detect(bytes = zip("xl/workbook.xml" to "<x/>"), displayName = "sin-extension"),
        )
        assertEquals(
            DocumentType.POWERPOINT,
            detect(bytes = zip("ppt/presentation.xml" to "<x/>"), displayName = "sin-extension"),
        )
    }

    @Test
    fun openDocumentYEpubSeDeclaranEnLaEntradaMimetype() {
        assertEquals(
            DocumentType.ODS,
            detect(
                bytes = zip("mimetype" to "application/vnd.oasis.opendocument.spreadsheet"),
                displayName = "sin-extension",
            ),
        )
        assertEquals(
            DocumentType.EPUB,
            detect(
                bytes = zip("mimetype" to "application/epub+zip"),
                displayName = "sin-extension",
            ),
        )
    }

    @Test
    fun unZipCorrienteNoSeConfundeConUnDocumento() {
        assertEquals(
            DocumentType.UNKNOWN,
            detect(bytes = zip("foto.png" to "no importa"), displayName = "sin-extension"),
        )
    }

    // ------------------------------------------------------- 5. el ultimo recurso

    @Test
    fun elTextoSinExtensionSeRescataPorSuContenido() {
        assertEquals(
            DocumentType.PLAIN_TEXT,
            detect(bytes = "Hola, esto es una nota.".toByteArray(), displayName = "sin-extension"),
        )
    }

    @Test
    fun elHtmlSinExtensionSeRescataPorSuContenido() {
        assertEquals(
            DocumentType.HTML,
            detect(
                bytes = "<!DOCTYPE html><html><body>Hola</body></html>".toByteArray(),
                displayName = "sin-extension",
            ),
        )
    }

    @Test
    fun unBinarioDesconocidoSeAdmiteComoDesconocido() {
        // Preferible a adivinar: la interfaz lo dice claramente en lugar de fallar.
        val binario = byteArrayOf(0x00, 0x01, 0x02, 0x03, 0x00, 0x7F, 0x00, 0x05)
        assertEquals(DocumentType.UNKNOWN, detect(bytes = binario, displayName = "sin-extension"))
    }

    @Test
    fun unArchivoVacioNoRevientaElDetector() {
        assertEquals(DocumentType.UNKNOWN, detect(bytes = ByteArray(0), displayName = "vacio"))
    }

    // ------------------------------------------------------------------ fixtures

    private fun zip(vararg entries: Pair<String, String>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            for ((name, content) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }
}
