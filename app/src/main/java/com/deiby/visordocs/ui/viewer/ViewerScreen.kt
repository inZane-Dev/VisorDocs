package com.deiby.visordocs.ui.viewer

import android.net.Uri
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import com.deiby.visordocs.R
import com.deiby.visordocs.core.DocumentSource
import com.deiby.visordocs.core.DocumentType
import com.deiby.visordocs.core.formatFileSize
import com.deiby.visordocs.core.resolveDocumentSource
import com.deiby.visordocs.ui.components.LoadingView
import com.deiby.visordocs.ui.components.MessageView
import com.deiby.visordocs.viewer.pdf.JetpackPdfViewer
import com.deiby.visordocs.viewer.pdf.LegacyPdfViewer
import com.deiby.visordocs.viewer.pdf.PdfEngine
import com.deiby.visordocs.viewer.pdf.PdfEngineSupport

/**
 * Pantalla de visualizacion.
 *
 * Identifica el documento y elige el visor adecuado. Este `when` sobre
 * [DocumentType] es el unico punto que hay que ampliar para anadir los formatos de
 * Office en las siguientes fases: ni la navegacion ni el manejo de intents cambian.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ViewerScreen(
    uri: Uri,
    mimeHint: String?,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current

    val source by produceState<DocumentSource?>(null, uri, mimeHint) {
        value = null
        value = runCatching { resolveDocumentSource(context, uri, mimeHint) }
            .getOrElse {
                DocumentSource(
                    uri = uri,
                    displayName = uri.lastPathSegment.orEmpty(),
                    sizeBytes = null,
                    type = DocumentType.UNKNOWN,
                )
            }
    }

    val document = source

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = document?.displayName?.takeIf { it.isNotBlank() }
                                ?: stringResource(R.string.loading),
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (document != null) {
                            val details = listOfNotNull(
                                document.type.label,
                                formatFileSize(document.sizeBytes),
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
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            when {
                document == null -> LoadingView(
                    message = stringResource(R.string.loading_document),
                )

                document.type == DocumentType.PDF -> when (PdfEngineSupport.engine) {
                    PdfEngine.JETPACK -> JetpackPdfViewer(
                        uri = document.uri,
                        modifier = Modifier.fillMaxSize(),
                    )

                    PdfEngine.LEGACY -> LegacyPdfViewer(
                        uri = document.uri,
                        modifier = Modifier.fillMaxSize(),
                    )
                }

                document.type == DocumentType.UNKNOWN -> MessageView(
                    iconRes = R.drawable.ic_error,
                    title = stringResource(R.string.unknown_format_title),
                    description = stringResource(R.string.unknown_format_body),
                )

                else -> MessageView(
                    iconRes = R.drawable.ic_document,
                    title = stringResource(R.string.unsupported_format_title, document.type.label),
                    description = stringResource(R.string.unsupported_format_body),
                )
            }
        }
    }
}
