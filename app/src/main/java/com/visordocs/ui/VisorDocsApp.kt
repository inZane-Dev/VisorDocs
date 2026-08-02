package com.visordocs.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.visordocs.model.DocumentRequest
import com.visordocs.ui.home.HomeScreen
import com.visordocs.ui.viewer.ViewerScreen

/**
 * Raiz de la interfaz.
 *
 * La navegacion es de dos estados (inicio / visor), asi que no hace falta una
 * libreria de navegacion. Si en el futuro se anaden recientes o ajustes, este es el
 * punto donde habria que meter Navigation Compose.
 */
@Composable
fun VisorDocsApp(
    incoming: DocumentRequest?,
    onIncomingHandled: () -> Unit,
) {
    // `rememberSaveable`, no `remember`: si el sistema mata el proceso en segundo plano,
    // al volver se reabre el documento en lugar de aparecer la pantalla de inicio.
    var openRequest by rememberSaveable(stateSaver = DocumentRequest.SAVER) {
        mutableStateOf<DocumentRequest?>(null)
    }

    // Documento llegado por intent ("Abrir con" o "Compartir").
    LaunchedEffect(incoming) {
        if (incoming != null) {
            openRequest = incoming
            onIncomingHandled()
        }
    }

    val current = openRequest

    // Atras desde el visor vuelve al inicio; desde el inicio sale de la app.
    BackHandler(enabled = current != null) { openRequest = null }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface),
    ) {
        if (current == null) {
            HomeScreen(onDocumentPicked = { uri -> openRequest = DocumentRequest(uri) })
        } else {
            ViewerScreen(
                request = current,
                onBack = { openRequest = null },
            )
        }
    }
}
