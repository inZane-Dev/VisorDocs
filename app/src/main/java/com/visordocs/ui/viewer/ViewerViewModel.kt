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

/**
 * Union de PDF: documentos que se anadiran al que esta abierto.
 *
 * El documento abierto va siempre el primero y no aparece en [pending]; la lista son
 * solo los que se le suman.
 */
data class MergeState(
    val pending: List<Uri> = emptyList(),
    val inProgress: Boolean = false,
    val outcome: MergeOutcome? = null,
) {
    /** Numero total de documentos que tendria el resultado. */
    val totalDocuments: Int get() = pending.size + 1
}

sealed interface MergeOutcome {
    data object Saved : MergeOutcome
    data object Failed : MergeOutcome
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

    fun open(request: DocumentRequest, labels: MarkupLabels) {
        // Sin esta guarda, cada recomposicion volveria a analizar el mismo archivo.
        if (_uiState.value.request == request) return

        loadJob?.cancel()
        _uiState.value = ViewerUiState(request = request)

        loadJob = viewModelScope.launch {
            // Antes de nada se intenta conservar el acceso, para que el documento se
            // pueda reabrir si el sistema mata el proceso mientras esta en segundo plano.
            repository.retainAccess(request.uri)

            val source = repository.resolve(request.uri, request.mimeType)
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

    /** Anade documentos a la cola de union. Se ignoran los repetidos. */
    fun addToMerge(uris: List<Uri>) {
        if (uris.isEmpty()) return
        _uiState.update { state ->
            val abierto = state.source?.uri
            val nuevos = uris.filter { it != abierto && it !in state.merge.pending }
            state.copy(merge = state.merge.copy(pending = state.merge.pending + nuevos))
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
                repository.mergePdfs(listOf(first) + state.merge.pending, target)
            }

            // Se registra la causa. No lleva nada del usuario —solo el tipo de fallo—,
            // asi que puede quedarse tambien en las versiones publicadas: sin esto, un
            // fallo al unir no deja ningun rastro que permita entender que paso.
            result.exceptionOrNull()?.let { error ->
                Log.w(PdfEngineSupport.TAG, "No se pudo unir los PDF", error)
            }
            _uiState.update {
                it.copy(
                    merge = if (result.isSuccess) {
                        // Tras guardar, la cola se vacia: el trabajo ya esta hecho.
                        MergeState(outcome = MergeOutcome.Saved)
                    } else {
                        it.merge.copy(inProgress = false, outcome = MergeOutcome.Failed)
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
