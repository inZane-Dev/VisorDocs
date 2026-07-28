package com.deiby.visordocs.ui.home

import android.content.Intent
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.deiby.visordocs.R
import com.deiby.visordocs.viewer.pdf.PdfEngineSupport

/**
 * Tipos que se ofrecen en el selector del sistema.
 *
 * Se incluyen los de Office aunque todavia no tengan visor: asi el usuario recibe
 * un mensaje concreto de "formato aun no soportado" en lugar de no poder ni elegirlos.
 */
private val PICKER_MIME_TYPES = arrayOf(
    "application/pdf",
    "application/msword",
    "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
    "application/vnd.ms-excel",
    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
    "application/vnd.ms-powerpoint",
    "application/vnd.openxmlformats-officedocument.presentationml.presentation",
)

@Composable
fun HomeScreen(
    onDocumentPicked: (Uri) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current

    val picker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            // Conservar el permiso de lectura por si el proceso se reinicia.
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
            onDocumentPicked(uri)
        }
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
