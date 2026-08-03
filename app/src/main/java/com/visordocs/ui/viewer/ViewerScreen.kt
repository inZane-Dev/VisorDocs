package com.visordocs.ui.viewer

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.visordocs.R
import com.visordocs.data.markup.MarkupLabels
import com.visordocs.model.DocumentContent
import com.visordocs.model.DocumentRequest
import com.visordocs.model.DocumentSource
import com.visordocs.ui.common.LoadingView
import com.visordocs.ui.common.MessageView
import com.visordocs.ui.common.formatFileSize
import com.visordocs.ui.viewer.render.MarkupViewer
import com.visordocs.ui.viewer.render.PictureViewer
import com.visordocs.ui.viewer.render.PlainTextViewer
import com.visordocs.ui.viewer.render.WebDocumentViewer
import com.visordocs.ui.viewer.render.pdf.JetpackPdfViewer
import com.visordocs.ui.viewer.render.pdf.LegacyPdfViewer
import com.visordocs.ui.viewer.render.pdf.PdfEngine
import com.visordocs.ui.viewer.render.pdf.PdfEngineSupport

/**
 * Pantalla de visualizacion.
 *
 * No carga nada por su cuenta: pide el documento al [ViewerViewModel] y se limita a
 * elegir el renderizador segun la forma del contenido. Anadir un formato nuevo no
 * toca este archivo salvo que traiga una forma de pintar que no exista todavia.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ViewerScreen(
    request: DocumentRequest,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val viewModel: ViewerViewModel = viewModel(factory = ViewerViewModel.factory(context))

    // Los convertidores insertan estos textos en el HTML; salen de recursos para que
    // la capa de datos no dependa de Android.
    val labels = MarkupLabels(
        sheet = stringResource(R.string.office_sheet),
        slide = stringResource(R.string.office_slide),
    )

    val fallbackName = stringResource(R.string.document_fallback_name)

    LaunchedEffect(request) { viewModel.open(request, labels, fallbackName) }
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    val snackbarHostState = remember { SnackbarHostState() }
    val savedMessage = stringResource(R.string.merge_saved)
    val failedMessage = stringResource(R.string.merge_failed)
    val protectedMessage = stringResource(R.string.merge_failed_protected)
    val openAction = stringResource(R.string.open_action)

    // El resultado de la union se cuenta una sola vez y se descarta, para que no vuelva
    // a aparecer al girar la pantalla.
    LaunchedEffect(state.merge.outcome) {
        when (val outcome = state.merge.outcome) {
            null -> return@LaunchedEffect

            // Guardar y no poder ver el resultado dejaba a la persona buscando el archivo
            // a mano. El aviso lleva ahora la accion de abrirlo.
            is MergeOutcome.Saved -> {
                val pressed = snackbarHostState.showMessageOnce(savedMessage, openAction)
                if (pressed) DocumentSharing.openWith(context, outcome.target)
            }

            is MergeOutcome.Failed -> snackbarHostState.showMessageOnce(
                if (outcome.protectedDocument) protectedMessage else failedMessage,
            )
        }
        viewModel.consumeMergeOutcome()
    }

    // Solo se ofrece unir cuando lo abierto es un PDF: es el unico formato en el que la
    // operacion tiene sentido.
    val isPdf = (state.content as? ContentState.Ready)?.content is DocumentContent.Pdf

    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            ViewerTopBar(
                source = state.source,
                onBack = onBack,
                actions = {
                    // Compartir vale para cualquier formato: se pasa el mismo URI que ya
                    // se recibio, sin copiar nada.
                    val source = state.source
                    if (source != null && DocumentSharing.canShare(source.uri)) {
                        IconButton(
                            onClick = {
                                DocumentSharing.share(context, source.uri, source.displayName)
                            },
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_share),
                                contentDescription = stringResource(R.string.share_document),
                            )
                        }
                    }

                    if (isPdf) {
                        PdfMergeActions(
                            merge = state.merge,
                            onAdd = viewModel::addToMerge,
                            onSave = viewModel::mergeInto,
                            suggestedName = mergedNameFor(
                                displayName = state.source?.displayName,
                                suffix = stringResource(R.string.merge_file_suffix),
                            ),
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            if (isPdf) {
                PdfMergePanel(
                    merge = state.merge,
                    openDocumentName = state.source?.displayName.orEmpty(),
                    onRemove = viewModel::removeFromMerge,
                    onMove = viewModel::moveInMerge,
                    onClear = viewModel::clearMerge,
                )
            }
            // `weight(1f)` y no `fillMaxSize()`: el documento ocupa lo que sobra despues
            // del panel de union. Con fillMaxSize pediria la altura entera de la columna
            // y el panel lo empujaria fuera de la pantalla al crecer la lista.
            Box(modifier = Modifier.weight(1f)) {
                ViewerContent(state)
            }
        }
    }
}

/**
 * Muestra el aviso descartando el anterior, para no encadenar mensajes viejos.
 *
 * Devuelve si se pulso la accion.
 */
private suspend fun SnackbarHostState.showMessageOnce(
    message: String,
    actionLabel: String? = null,
): Boolean {
    currentSnackbarData?.dismiss()
    return showSnackbar(message, actionLabel) == SnackbarResult.ActionPerformed
}

/**
 * Propone "informe-unido.pdf" a partir de "informe.pdf".
 *
 * El sufijo llega traducido desde los recursos: es parte del nombre que vera el usuario
 * en el selector de guardado, no un detalle interno.
 */
private fun mergedNameFor(displayName: String?, suffix: String): String {
    val base = displayName?.substringBeforeLast('.')?.takeIf { it.isNotBlank() }
        ?: return "$suffix.pdf"

    // Unir dos veces seguidas producia "informe-unido-unido.pdf". Si el nombre ya acaba
    // en el sufijo, no se vuelve a anadir.
    if (base.endsWith("-$suffix", ignoreCase = true)) return "$base.pdf"

    return "$base-$suffix.pdf"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ViewerTopBar(
    source: DocumentSource?,
    onBack: () -> Unit,
    actions: @Composable RowScope.() -> Unit = {},
) {
    TopAppBar(
        actions = actions,
        title = {
            Column {
                Text(
                    text = source?.displayName?.takeIf { it.isNotBlank() }
                        ?: stringResource(R.string.loading),
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (source != null) {
                    val details = listOfNotNull(
                        stringResource(source.type.labelRes),
                        formatFileSize(source.sizeBytes),
                    ).joinToString(" - ")
                    Text(
                        text = details,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        },
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(
                    painter = painterResource(R.drawable.ic_arrow_back),
                    contentDescription = stringResource(R.string.back),
                )
            }
        },
    )
}

@Composable
private fun ViewerContent(state: ViewerUiState) {
    when (val content = state.content) {
        ContentState.Loading -> LoadingView(
            message = stringResource(R.string.loading_document),
        )

        ContentState.Failed -> MessageView(
            iconRes = R.drawable.ic_error,
            title = stringResource(R.string.error_open_title),
            description = stringResource(R.string.error_open_body),
        )

        is ContentState.Ready -> DocumentView(
            content = content.content,
            typeLabel = state.source?.let { stringResource(it.type.labelRes) }.orEmpty(),
        )
    }
}

@Composable
private fun DocumentView(content: DocumentContent, typeLabel: String) {
    val fill = Modifier.fillMaxSize()

    when (content) {
        // El PDF tiene dos motores; cual se usa lo decide el dispositivo.
        is DocumentContent.Pdf -> when (PdfEngineSupport.engine) {
            PdfEngine.JETPACK -> JetpackPdfViewer(uri = content.uri, modifier = fill)
            PdfEngine.LEGACY -> LegacyPdfViewer(uri = content.uri, modifier = fill)
        }

        is DocumentContent.Markup -> MarkupViewer(
            body = content.body,
            truncated = content.truncated,
            modifier = fill,
        )

        // Una pagina trae sus propios estilos: se muestra tal cual, sin envolverla.
        is DocumentContent.WebPage -> WebDocumentViewer(html = content.html, modifier = fill)

        is DocumentContent.PlainText -> PlainTextViewer(
            text = content.text,
            truncated = content.truncated,
            modifier = fill,
        )

        is DocumentContent.Picture -> PictureViewer(bitmap = content.bitmap, modifier = fill)

        DocumentContent.Empty -> MessageView(
            iconRes = R.drawable.ic_document,
            title = stringResource(R.string.empty_document_title),
            description = stringResource(R.string.office_empty),
        )

        DocumentContent.Unsupported -> MessageView(
            iconRes = R.drawable.ic_document,
            title = stringResource(R.string.unsupported_format_title, typeLabel),
            description = stringResource(R.string.unsupported_format_body),
        )

        DocumentContent.Unrecognized -> MessageView(
            iconRes = R.drawable.ic_error,
            title = stringResource(R.string.unknown_format_title),
            description = stringResource(R.string.unknown_format_body),
        )
    }
}
