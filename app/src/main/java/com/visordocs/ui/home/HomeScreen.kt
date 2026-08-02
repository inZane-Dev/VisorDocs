package com.visordocs.ui.home

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.visordocs.R
import com.visordocs.ui.viewer.render.pdf.PdfEngineSupport

/**
 * Tipos que se ofrecen en el selector del sistema.
 *
 * Se incluyen los binarios antiguos de Office aunque no tengan visor: asi el usuario
 * recibe un mensaje concreto que le sugiere convertirlos, en lugar de no poder ni
 * elegirlos y quedarse sin saber por que.
 */
private val PICKER_MIME_TYPES = arrayOf(
    "application/pdf",
    "application/msword",
    "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
    "application/vnd.ms-excel",
    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
    "application/vnd.ms-powerpoint",
    "application/vnd.openxmlformats-officedocument.presentationml.presentation",
    "application/vnd.oasis.opendocument.text",
    "application/vnd.oasis.opendocument.spreadsheet",
    "application/vnd.oasis.opendocument.presentation",
    "application/rtf",
    "application/epub+zip",
    "text/*",
    "image/*",
)

@Composable
fun HomeScreen(
    onDocumentPicked: (Uri) -> Unit,
    modifier: Modifier = Modifier,
) {
    // No se pide `takePersistableUriPermission`. El permiso que concede el selector dura
    // lo que dura la tarea, que es de sobra para abrir el documento a continuacion.
    // Pedirlo persistente acumularia una concesion por cada archivo abierto, sin
    // liberarla nunca; el sistema limita cuantas puede tener una app y al pasarse lanza
    // excepcion. Solo tendria sentido si hubiera historial de recientes.
    val picker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) onDocumentPicked(uri)
    }

    Column(
        modifier = modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_document),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(72.dp),
        )
        Text(
            text = stringResource(R.string.app_name),
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(top = 20.dp),
        )
        Text(
            text = stringResource(R.string.home_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 10.dp),
        )
        Button(
            onClick = { picker.launch(PICKER_MIME_TYPES) },
            modifier = Modifier.padding(top = 32.dp),
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_folder_open),
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Text(
                text = stringResource(R.string.open_document),
                modifier = Modifier.padding(start = 10.dp),
            )
        }

        // Diagnostico: deja a la vista que motor de PDF quedo activo en este telefono.
        Text(
            text = stringResource(R.string.pdf_engine_label, PdfEngineSupport.engine.name),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 40.dp),
        )
    }
}
