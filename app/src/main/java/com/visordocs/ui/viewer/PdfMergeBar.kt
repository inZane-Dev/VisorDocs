package com.visordocs.ui.viewer

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.visordocs.R

/**
 * Tipos que se ofrecen al buscar documentos para unir.
 *
 * Solo PDF, y tambien `application/octet-stream` porque bastantes apps entregan los
 * archivos con ese MIME generico; sin el, el selector no mostraria PDF que llegaron por
 * mensajeria.
 */
private val MERGE_PICKER_TYPES = arrayOf("application/pdf", "application/octet-stream")

/**
 * Botones de union que se anaden a la barra superior cuando lo abierto es un PDF.
 *
 * El de guardar solo aparece cuando hay algo que unir: mostrarlo siempre invitaria a
 * pulsarlo para no conseguir nada.
 */
@Composable
fun PdfMergeActions(
    merge: MergeState,
    onAdd: (List<android.net.Uri>) -> Unit,
    onSave: (android.net.Uri) -> Unit,
    suggestedName: String,
) {
    val addLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris -> onAdd(uris) }

    // El selector de creacion deja al usuario elegir carpeta y nombre. Asi no hace falta
    // ningun permiso de almacenamiento, igual que en el resto de la app.
    val saveLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/pdf"),
    ) { uri -> if (uri != null) onSave(uri) }

    if (merge.inProgress) {
        CircularProgressIndicator(
            modifier = Modifier.size(24.dp).padding(end = 4.dp),
            strokeWidth = 2.dp,
        )
        return
    }

    IconButton(onClick = { addLauncher.launch(MERGE_PICKER_TYPES) }) {
        Icon(
            painter = painterResource(R.drawable.ic_add),
            contentDescription = stringResource(R.string.merge_add),
        )
    }

    if (merge.pending.isNotEmpty()) {
        IconButton(onClick = { saveLauncher.launch(suggestedName) }) {
            Icon(
                painter = painterResource(R.drawable.ic_save),
                contentDescription = stringResource(R.string.merge_save),
            )
        }
    }
}

/** Aviso de cuantos documentos se van a unir, con la opcion de deshacer la seleccion. */
@Composable
fun PdfMergeBanner(
    merge: MergeState,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (merge.pending.isEmpty() && !merge.inProgress) return

    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.secondaryContainer)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = if (merge.inProgress) {
                stringResource(R.string.merge_in_progress)
            } else {
                pluralStringResource(
                    R.plurals.merge_pending,
                    merge.totalDocuments,
                    merge.totalDocuments,
                )
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
        )
        if (!merge.inProgress) {
            TextButton(onClick = onClear) {
                Text(stringResource(R.string.merge_discard))
            }
        }
    }
}
