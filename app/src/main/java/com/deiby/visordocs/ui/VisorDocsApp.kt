package com.deiby.visordocs.ui

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.deiby.visordocs.IncomingDocument
import com.deiby.visordocs.ui.home.HomeScreen
import com.deiby.visordocs.ui.viewer.ViewerScreen

/**
 * Raiz de la interfaz.
 *
 * La navegacion es de dos estados (inicio / visor), asi que no hace falta una
 * libreria de navegacion. Si en el futuro se anaden recientes o ajustes, este es el
 * punto donde habria que meter Navigation Compose.
 */
@Composable
fun VisorDocsApp(
    incoming: IncomingDocument?,
    onIncomingHandled: () -> Unit,
) {
    var openUri by remember { mutableStateOf<Uri?>(null) }
    var openMimeHint by remember { mutableStateOf<String?>(null) }

    // Documento llegado por intent ("Abrir con" o "Compartir").
    LaunchedEffect(incoming) {
        if (incoming != null) {
            openUri = incoming.uri
            openMimeHint = incoming.mimeType
            onIncomingHandled()
        }
    }

    val currentUri = openUri

    // Atras desde el visor vuelve al inicio; desde el inicio sale de la app.
    BackHandler(enabled = currentUri != null) {
        openUri = null
        openMimeHint = null
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface),
    ) {
        if (currentUri == null) {
            HomeScreen(
                onDocumentPicked = { uri ->
                    openUri = uri
                    openMimeHint = null
                },
            )
        } else {
            ViewerScreen(
                uri = currentUri,
                mimeHint = openMimeHint,
                onBack = {
                    openUri = null
                    openMimeHint = null
                },
            )
        }
    }
}
