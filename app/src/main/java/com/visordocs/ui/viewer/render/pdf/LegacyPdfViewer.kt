package com.visordocs.ui.viewer.render.pdf

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.visordocs.R
import com.visordocs.ui.common.LoadingView
import com.visordocs.ui.common.MessageView
import com.visordocs.ui.viewer.render.detectPinchZoom

private const val MIN_SCALE = 1f
private const val MAX_SCALE = 5f

private sealed interface LegacyPdfState {
    data object Loading : LegacyPdfState
    data object PasswordProtected : LegacyPdfState
    data class Failed(val reason: String?) : LegacyPdfState
    data class Ready(val document: LegacyPdfDocument) : LegacyPdfState
}

/**
 * Visor de respaldo, construido sobre la API [android.graphics.pdf.PdfRenderer] del
 * sistema. Funciona en cualquier dispositivo pero solo muestra imagenes de pagina:
 * no hay busqueda ni seleccion de texto, y no admite PDF con contrasena.
 *
 * Se usa unicamente cuando [PdfEngineSupport] determina que androidx.pdf no esta
 * disponible.
 */
@Composable
fun LegacyPdfViewer(
    uri: Uri,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current

    val state by produceState<LegacyPdfState>(LegacyPdfState.Loading, uri) {
        value = LegacyPdfState.Loading
        value = try {
            LegacyPdfState.Ready(LegacyPdfDocument.open(context, uri))
        } catch (_: SecurityException) {
            LegacyPdfState.PasswordProtected
        } catch (throwable: Throwable) {
            LegacyPdfState.Failed(throwable.message)
        }
    }

    // Libera el PdfRenderer al salir de la pantalla o al cambiar de documento.
    val readyState = state as? LegacyPdfState.Ready
    DisposableEffect(readyState) {
        onDispose { readyState?.document?.close() }
    }

    when (val current = state) {
        LegacyPdfState.Loading -> LoadingView(
            message = stringResource(R.string.loading_document),
            modifier = modifier,
        )

        LegacyPdfState.PasswordProtected -> MessageView(
            iconRes = R.drawable.ic_lock,
            title = stringResource(R.string.error_password_title),
            description = stringResource(R.string.error_password_legacy_body),
            modifier = modifier,
        )

        is LegacyPdfState.Failed -> MessageView(
            iconRes = R.drawable.ic_error,
            title = stringResource(R.string.error_open_title),
            description = current.reason ?: stringResource(R.string.error_open_body),
            modifier = modifier,
        )

        is LegacyPdfState.Ready -> PdfPageList(
            document = current.document,
            modifier = modifier,
        )
    }
}

@Composable
private fun PdfPageList(
    document: LegacyPdfDocument,
    modifier: Modifier = Modifier,
) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        val renderWidthPx = with(LocalDensity.current) { maxWidth.roundToPx() }
        val pageIndices = remember(document) { List(document.pageCount) { it } }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectPinchZoom { zoomChange, panChange ->
                        val newScale = (scale * zoomChange).coerceIn(MIN_SCALE, MAX_SCALE)
                        // Al volver al 100% se recentra, para no dejar la pagina fuera de vista.
                        offset = if (newScale <= MIN_SCALE) Offset.Zero else offset + panChange
                        scale = newScale
                    }
                }
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = offset.x
                    translationY = offset.y
                },
            contentPadding = PaddingValues(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(items = pageIndices, key = { it }) { pageIndex ->
                PdfPageItem(
                    document = document,
                    pageIndex = pageIndex,
                    renderWidthPx = renderWidthPx,
                )
            }
        }
    }
}

@Composable
private fun PdfPageItem(
    document: LegacyPdfDocument,
    pageIndex: Int,
    renderWidthPx: Int,
) {
    // Se consulta primero la proporcion para reservar el alto correcto y evitar
    // que la lista pegue saltos cuando cada pagina termina de rasterizarse.
    val aspectRatio by produceState(
        initialValue = LegacyPdfDocument.DEFAULT_ASPECT_RATIO,
        document,
        pageIndex,
    ) {
        value = runCatching { document.aspectRatio(pageIndex) }
            .getOrDefault(LegacyPdfDocument.DEFAULT_ASPECT_RATIO)
    }

    var bitmap by remember(document, pageIndex, renderWidthPx) {
        mutableStateOf<Bitmap?>(null)
    }

    // LazyColumn descarta los items fuera de pantalla, asi que los bitmaps de las
    // paginas lejanas quedan libres para el recolector de basura.
    LaunchedEffect(document, pageIndex, renderWidthPx) {
        bitmap = runCatching { document.renderPage(pageIndex, renderWidthPx) }.getOrNull()
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp)
            .aspectRatio(aspectRatio)
            .background(Color.White),
        contentAlignment = Alignment.Center,
    ) {
        val currentBitmap = bitmap
        if (currentBitmap != null) {
            Image(
                bitmap = currentBitmap.asImageBitmap(),
                contentDescription = stringResource(R.string.page_number, pageIndex + 1),
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            CircularProgressIndicator(modifier = Modifier.size(28.dp))
        }
    }
}
