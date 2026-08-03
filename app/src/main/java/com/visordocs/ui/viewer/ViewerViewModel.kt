package com.visordocs.ui.viewer

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.visordocs.data.DocumentRepository
import com.visordocs.data.markup.MarkupLabels
import com.visordocs.data.pdf.ProtectedPdfException
import com.visordocs.model.DocumentContent
import com.visordocs.model.DocumentRequest
import com.visordocs.model.DocumentSource
import com.visordocs.ui.viewer.render.pdf.PdfEngineSupport
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Estado de la pantalla del visor.
 *
 * [source] y [content] avanzan por separado a proposito: en cuanto se conoce el
 * nombre y el formato se puede pintar la barra superior, aunque el contenido tarde
 * todavia un segundo en analizarse.
 */
data class ViewerUiState(
    val request: DocumentRequest? = null,
    val source: DocumentSource? = null,
    val content: ContentState = ContentState.Loading,
    val merge: MergeState = MergeState(),
)

sealed interface ContentState {
    data object Loading : ContentState
    data object Failed : ContentState
    data class Ready(val content: DocumentContent) : ContentState
}

/** Un documento en la cola de union, con su nombre para poder mostrarlo. */
data class MergeDocument(val uri: Uri, val displayName: String)

/**
 * Union de PDF: documentos que se anadiran al que esta abierto.
 *
 * El documento abierto va siempre el primero y no aparece en [pending]; la lista son
 * solo los que se le suman.
 */
data class MergeState(
    val pending: List<MergeDocument> = emptyList(),
    val inProgress: Boolean = false,
    val outcome: MergeOutcome? = null,
) {
    /** Numero total de documentos que tendria el resultado. */
    val totalDocuments: Int get() = pending.size + 1
}

sealed interface MergeOutcome {
    /** [target] es donde quedo el archivo, para poder ofrecer abrirlo sin buscarlo. */
    data class Saved(val target: Uri) : MergeOutcome

    /**
     * [protectedDocument] distingue el PDF con contrasena del resto de fallos. Es el
     * unico caso en que la persona puede hacer algo al respecto, asi que merece un
     * mensaje propio en lugar del generico.
     */
    data class Failed(val protectedDocument: Boolean) : MergeOutcome
}

/**
 * Sostiene la carga del documento.
 *
 * Existe sobre todo por dos razones concretas:
 *
 * - El analisis de un .xlsx grande puede tardar; si viviera en el composable, cada
 *   recomposicion correria el riesgo de relanzarlo. Aqui se lanza una vez por
 *   documento y sobrevive a los cambios de configuracion.
 * - Antes cada visor repetia su propia maquina de estados (cargando / error / listo).
 *   Ahora hay una sola y los renderizadores solo pintan lo que reciben.
 */
class ViewerViewModel(private val repository: DocumentRepository) : ViewModel() {

    private val _uiState = MutableStateFlow(ViewerUiState())
    val uiState: StateFlow<ViewerUiState> = _uiState.asStateFlow()

    private var loadJob: Job? = null

    /**
     * Nombre que se muestra cuando ni el proveedor ni el URI dan uno.
     *
     * Llega desde la interfaz, traducido, porque la capa de datos no conoce los recursos
     * de Android. Se guarda para que anadir documentos a la cola tambien pueda usarlo.
     */
    private var fallbackName: String = ""

    fun open(request: DocumentRequest, labels: MarkupLabels, fallbackName: String) {
        this.fallbackName = fallbackName

        // Sin esta guarda, cada recomposicion volveria a analizar el mismo archivo.
        if (_uiState.value.request == request) return

        loadJob?.cancel()
        _uiState.value = ViewerUiState(request = request)

        // Si llegaron varios documentos compartidos a la vez, el resto entra en la cola
        // de union. Va antes de cargar para que la lista aparezca de inmediato.
        if (request.alsoMerge.isNotEmpty()) addToMerge(request.alsoMerge)

        loadJob = viewModelScope.launch {
            // Antes de nada se intenta conservar el acceso, para que el documento se
            // pueda reabrir si el sistema mata el proceso mientras esta en segundo plano.
            repository.retainAccess(request.uri)

            val source = repository.resolve(request.uri, request.mimeType, fallbackName)
            _uiState.update { it.copy(source = source) }

            val content = runCatching { repository.load(source, labels) }
            _uiState.update {
                it.copy(
                    content = content.fold(
                        onSuccess = { loaded -> ContentState.Ready(loaded) },
                        onFailure = { ContentState.Failed },
                    ),
                )
            }
        }
    }

    // ------------------------------------------------------------ union de PDF

    /**
     * Anade documentos a la cola de union. Se ignoran los repetidos y el ya abierto.
     *
     * El nombre se resuelve aqui, una sola vez, y no cada vez que se pinta la lista:
     * consultarlo implica preguntar al proveedor, que es trabajo de disco.
     */
    fun addToMerge(uris: List<Uri>) {
        if (uris.isEmpty()) return

        viewModelScope.launch {
            val yaEnCola = _uiState.value.merge.pending.map { it.uri }.toSet()
            val abierto = _uiState.value.source?.uri
            val nuevos = uris
                .filter { it != abierto && it !in yaEnCola }
                .distinct()
                .map { MergeDocument(uri = it, displayName = repository.displayName(it, fallbackName)) }

            if (nuevos.isEmpty()) return@launch

            // Se vuelve a filtrar contra el estado ACTUAL y no contra el que se leyo al
            // entrar: resolver los nombres es suspend, y en ese hueco puede haber
            // llegado otra tanda de documentos.
            _uiState.update { state ->
                val presentes = state.merge.pending.map { it.uri }.toSet()
                val añadir = nuevos.filter { it.uri !in presentes && it.uri != state.source?.uri }
                state.copy(merge = state.merge.copy(pending = state.merge.pending + añadir))
            }
        }
    }

    /** Quita un solo documento de la cola, sin tocar los demas. */
    fun removeFromMerge(uri: Uri) {
        _uiState.update { state ->
            state.copy(
                merge = state.merge.copy(pending = state.merge.pending.filterNot { it.uri == uri }),
            )
        }
    }

    /**
     * Mueve un documento una posicion, para corregir el orden sin rehacer la seleccion.
     *
     * [offset] es -1 para subir y +1 para bajar. Fuera de rango no hace nada, que es lo
     * que corresponde en el primero y el ultimo.
     */
    fun moveInMerge(uri: Uri, offset: Int) {
        _uiState.update { state ->
            val actual = state.merge.pending
            val desde = actual.indexOfFirst { it.uri == uri }
            val hasta = desde + offset
            if (desde < 0 || hasta !in actual.indices) return@update state

            val reordenados = actual.toMutableList().apply { add(hasta, removeAt(desde)) }
            state.copy(merge = state.merge.copy(pending = reordenados))
        }
    }

    fun clearMerge() {
        _uiState.update { it.copy(merge = MergeState()) }
    }

    /**
     * Escribe la union en [target], que el usuario acaba de elegir con el selector del
     * sistema. El documento abierto va primero y detras los anadidos, en su orden.
     */
    fun mergeInto(target: Uri) {
        val state = _uiState.value
        val first = state.source?.uri ?: return
        if (state.merge.pending.isEmpty() || state.merge.inProgress) return

        _uiState.update { it.copy(merge = it.merge.copy(inProgress = true, outcome = null)) }

        viewModelScope.launch {
            val result = runCatching {
                repository.mergePdfs(listOf(first) + state.merge.pending.map { it.uri }, target)
            }

            // Se registra la causa. No lleva nada del usuario —solo el tipo de fallo—,
            // asi que puede quedarse tambien en las versiones publicadas: sin esto, un
            // fallo al unir no deja ningun rastro que permita entender que paso.
            val error = result.exceptionOrNull()
            error?.let { Log.w(PdfEngineSupport.TAG, "No se pudo unir los PDF", it) }

            _uiState.update {
                it.copy(
                    merge = if (error == null) {
                        // Tras guardar, la cola se vacia: el trabajo ya esta hecho.
                        MergeState(outcome = MergeOutcome.Saved(target))
                    } else {
                        it.merge.copy(
                            inProgress = false,
                            outcome = MergeOutcome.Failed(
                                protectedDocument = error is ProtectedPdfException,
                            ),
                        )
                    },
                )
            }
        }
    }

    /** Se llama cuando la interfaz ya ha mostrado el aviso del resultado. */
    fun consumeMergeOutcome() {
        _uiState.update { it.copy(merge = it.merge.copy(outcome = null)) }
    }

    companion object {
        fun factory(context: Context): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                // applicationContext: el ViewModel vive mas que la Activity.
                val app = context.applicationContext
                ViewerViewModel(DocumentRepository(app.contentResolver, app.cacheDir))
            }
        }
    }
}
