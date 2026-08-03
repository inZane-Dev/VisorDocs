package com.visordocs.ui.viewer

import android.content.Context
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.visordocs.data.DocumentRepository
import com.visordocs.data.markup.MarkupLabels
import com.visordocs.data.pdf.pdfWithPages
import com.visordocs.model.DocumentRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Pruebas de la maquina de estados de la union.
 *
 * Es la parte con mas reglas de la pantalla —no repetir, no incluirse a si mismo,
 * reordenar sin salirse, vaciar, contar el resultado una sola vez— y no tenia ninguna
 * prueba: se comprobaba a mano tocando la pantalla, que es justo como se me colo una vez
 * unir el archivo equivocado.
 *
 * Usa el repositorio de verdad sobre archivos reales en la cache, servidos como `file://`.
 * No hay dobles de prueba: asi lo que se comprueba es el comportamiento completo, incluida
 * la resolucion del nombre que se muestra en la lista.
 */
@RunWith(AndroidJUnit4::class)
class ViewerViewModelTest {

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private lateinit var viewModel: ViewerViewModel

    private val labels = MarkupLabels(sheet = "Hoja", slide = "Diapositiva")

    @Before
    fun setUp() = onMain {
        viewModel = ViewerViewModel(
            DocumentRepository(context.contentResolver, context.cacheDir),
        )
    }

    // ------------------------------------------------------------ anadir a la cola

    @Test
    fun losDocumentosAnadidosLleganConSuNombre() {
        onMain { viewModel.addToMerge(listOf(pdfUri("anexo.pdf"), pdfUri("factura.pdf"))) }
        awaitPending(2)

        assertEquals(
            listOf("anexo.pdf", "factura.pdf"),
            merge().pending.map { it.displayName },
        )
    }

    @Test
    fun elMismoDocumentoNoEntraDosVeces() {
        val anexo = pdfUri("anexo.pdf")
        onMain { viewModel.addToMerge(listOf(anexo, anexo)) }
        awaitPending(1)

        onMain { viewModel.addToMerge(listOf(anexo)) }
        // Se le da margen para equivocarse antes de comprobar que no lo hizo.
        Thread.sleep(300)
        assertEquals(1, merge().pending.size)
    }

    @Test
    fun elDocumentoAbiertoNoSeAnadeASiMismo() {
        val abierto = pdfUri("abierto.pdf")
        onMain { viewModel.open(DocumentRequest(abierto), labels, "documento") }
        awaitSource()

        onMain { viewModel.addToMerge(listOf(abierto, pdfUri("otro.pdf"))) }
        awaitPending(1)

        assertEquals(listOf("otro.pdf"), merge().pending.map { it.displayName })
    }

    @Test
    fun elTotalCuentaTambienElDocumentoAbierto() {
        onMain { viewModel.addToMerge(listOf(pdfUri("a.pdf"), pdfUri("b.pdf"))) }
        awaitPending(2)

        assertEquals(3, merge().totalDocuments)
    }

    // ---------------------------------------------------------------- quitar y vaciar

    @Test
    fun sePuedeQuitarUnSoloDocumento() {
        val medio = pdfUri("medio.pdf")
        onMain { viewModel.addToMerge(listOf(pdfUri("uno.pdf"), medio, pdfUri("tres.pdf"))) }
        awaitPending(3)

        onMain { viewModel.removeFromMerge(medio) }

        assertEquals(listOf("uno.pdf", "tres.pdf"), merge().pending.map { it.displayName })
    }

    @Test
    fun vaciarDejaLaColaLimpia() {
        onMain { viewModel.addToMerge(listOf(pdfUri("uno.pdf"), pdfUri("dos.pdf"))) }
        awaitPending(2)

        onMain { viewModel.clearMerge() }

        assertTrue(merge().pending.isEmpty())
    }

    // ------------------------------------------------------------------- reordenar

    @Test
    fun subirYBajarCambianElOrden() {
        val tres = pdfUri("tres.pdf")
        onMain { viewModel.addToMerge(listOf(pdfUri("uno.pdf"), pdfUri("dos.pdf"), tres)) }
        awaitPending(3)

        onMain { viewModel.moveInMerge(tres, -1) }
        assertEquals(listOf("uno.pdf", "tres.pdf", "dos.pdf"), names())

        onMain { viewModel.moveInMerge(tres, 1) }
        assertEquals(listOf("uno.pdf", "dos.pdf", "tres.pdf"), names())
    }

    @Test
    fun moverFueraDeLaListaNoHaceNada() {
        val primero = pdfUri("uno.pdf")
        val ultimo = pdfUri("dos.pdf")
        onMain { viewModel.addToMerge(listOf(primero, ultimo)) }
        awaitPending(2)

        // Subir el primero y bajar el ultimo no tienen a donde ir.
        onMain { viewModel.moveInMerge(primero, -1) }
        onMain { viewModel.moveInMerge(ultimo, 1) }

        assertEquals(listOf("uno.pdf", "dos.pdf"), names())
    }

    // -------------------------------------------------------------------- resultado

    @Test
    fun unirEscribeElResultadoYVaciaLaCola() {
        onMain { viewModel.open(DocumentRequest(pdfUri("base.pdf", pages = 3)), labels, "documento") }
        awaitSource()
        onMain { viewModel.addToMerge(listOf(pdfUri("extra.pdf", pages = 2))) }
        awaitPending(1)

        val target = File(context.cacheDir, "vm_resultado.pdf").also { it.delete() }
        onMain { viewModel.mergeInto(Uri.fromFile(target)) }
        awaitOutcome()

        val outcome = merge().outcome
        assertTrue("se esperaba Saved, llego $outcome", outcome is MergeOutcome.Saved)
        assertTrue("el archivo deberia tener contenido", target.length() > 0)
        // Tras guardar, el trabajo esta hecho: la cola no debe quedar con restos.
        assertTrue(merge().pending.isEmpty())
    }

    @Test
    fun unDocumentoIlegibleDaResultadoFallido() {
        onMain { viewModel.open(DocumentRequest(pdfUri("base.pdf")), labels, "documento") }
        awaitSource()
        // No es un PDF: PDFBox no podra leerlo.
        onMain { viewModel.addToMerge(listOf(fileUri("roto.pdf", "esto no es un PDF".toByteArray()))) }
        awaitPending(1)

        onMain { viewModel.mergeInto(Uri.fromFile(File(context.cacheDir, "vm_fallido.pdf"))) }
        awaitOutcome()

        assertTrue(merge().outcome is MergeOutcome.Failed)
        // La cola se conserva para poder corregir y reintentar sin rehacer la seleccion.
        assertEquals(1, merge().pending.size)
    }

    @Test
    fun elResultadoSeCuentaUnaSolaVez() {
        onMain { viewModel.open(DocumentRequest(pdfUri("base.pdf")), labels, "documento") }
        awaitSource()
        onMain { viewModel.addToMerge(listOf(pdfUri("extra.pdf"))) }
        awaitPending(1)
        onMain { viewModel.mergeInto(Uri.fromFile(File(context.cacheDir, "vm_una_vez.pdf"))) }
        awaitOutcome()

        onMain { viewModel.consumeMergeOutcome() }

        // Si no se descartara, el aviso reaparaceria al girar la pantalla.
        assertNull(merge().outcome)
    }

    @Test
    fun sinDocumentoAbiertoNoSeIntentaUnir() {
        onMain { viewModel.addToMerge(listOf(pdfUri("extra.pdf"))) }
        awaitPending(1)

        onMain { viewModel.mergeInto(Uri.fromFile(File(context.cacheDir, "vm_sin_base.pdf"))) }
        Thread.sleep(300)

        assertNull(merge().outcome)
        assertTrue(!merge().inProgress)
    }

    // ------------------------------------------------------------------- utilidades

    private fun merge() = viewModel.uiState.value.merge

    private fun names() = merge().pending.map { it.displayName }

    /** El ViewModel usa el hilo principal; llamarlo desde el de pruebas seria incorrecto. */
    private fun onMain(block: () -> Unit) =
        InstrumentationRegistry.getInstrumentation().runOnMainSync(block)

    private fun pdfUri(name: String, pages: Int = 1): Uri =
        fileUri(name, pdfWithPages(name.substringBefore('.'), pages))

    private fun fileUri(name: String, bytes: ByteArray): Uri {
        val file = File(context.cacheDir, "vm_$name")
        file.writeBytes(bytes)
        // El nombre visible sale del path, asi que se prefija el archivo real y se expone
        // con el nombre limpio a traves de un directorio propio.
        val exposed = File(context.cacheDir, "vm_expuestos").apply { mkdirs() }
        val target = File(exposed, name)
        file.copyTo(target, overwrite = true)
        file.delete()
        return Uri.fromFile(target)
    }

    private fun awaitPending(size: Int) =
        await("se esperaban $size documentos en la cola") { merge().pending.size == size }

    private fun awaitSource() =
        await("el documento abierto no llego a resolverse") {
            viewModel.uiState.value.source != null
        }

    private fun awaitOutcome() =
        await("la union no llego a dar resultado") { merge().outcome != null }

    /**
     * El ViewModel trabaja con corrutinas, asi que el estado no cambia en el mismo
     * instante de la llamada. Se espera activamente en lugar de dormir un rato fijo: si
     * llega antes, la prueba no pierde tiempo; si no llega, falla con un motivo claro.
     */
    private fun await(reason: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(20)
        }
        fail(reason)
    }

    private companion object {
        const val TIMEOUT_MS = 5_000L
    }
}
