package com.visordocs.ui.viewer

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.text.style.TextOverflow
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

/** Altura maxima de la lista. Por encima, se desplaza en lugar de comerse el documento. */
private val MAX_LIST_HEIGHT = 200.dp

/**
 * Botones de union que se anaden a la barra superior cuando lo abierto es un PDF.
 *
 * El de guardar solo aparece cuando hay algo que unir: mostrarlo siempre invitaria a
 * pulsarlo para no conseguir nada.
 */
@Composable
fun PdfMergeActions(
    merge: MergeState,
    onAdd: (List<Uri>) -> Unit,
    onSave: (Uri) -> Unit,
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

/**
 * Lista de lo que se va a unir, en el orden en que quedara.
 *
 * Antes esto era una sola linea con el numero de documentos, y era una cola ciega:
 * elegir mal el tercero obligaba a descartar los tres y empezar de nuevo. Ahora se ve
 * cada nombre y se puede quitar o mover uno solo.
 */
@Composable
fun PdfMergePanel(
    merge: MergeState,
    openDocumentName: String,
    onRemove: (Uri) -> Unit,
    onMove: (Uri, Int) -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (merge.pending.isEmpty() && !merge.inProgress) return

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 4.dp),
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
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            if (!merge.inProgress) {
                TextButton(onClick = onClear) {
                    Text(stringResource(R.string.merge_discard_all))
                }
            }
        }

        if (!merge.inProgress) {
            HorizontalDivider(color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.2f))

            LazyColumn(modifier = Modifier.heightIn(max = MAX_LIST_HEIGHT)) {
                // El documento abierto encabeza siempre la union y no se puede quitar
                // desde aqui: quitarlo seria cerrar el documento, no editar la cola.
                item {
                    MergeRow(
                        position = 1,
                        name = openDocumentName,
                        note = stringResource(R.string.merge_open_document),
                        controls = null,
                    )
                }

                items(merge.pending, key = { it.uri }) { document ->
                    val index = merge.pending.indexOf(document)
                    MergeRow(
                        position = index + 2,
                        name = document.displayName,
                        note = null,
                        controls = MergeRowControls(
                            canMoveUp = index > 0,
                            canMoveDown = index < merge.pending.lastIndex,
                            onUp = { onMove(document.uri, -1) },
                            onDown = { onMove(document.uri, 1) },
                            onRemove = { onRemove(document.uri) },
                        ),
                    )
                }
            }
        }
    }
}

private class MergeRowControls(
    val canMoveUp: Boolean,
    val canMoveDown: Boolean,
    val onUp: () -> Unit,
    val onDown: () -> Unit,
    val onRemove: () -> Unit,
)

@Composable
private fun MergeRow(
    position: Int,
    name: String,
    note: String?,
    controls: MergeRowControls?,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "$position.",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.padding(end = 10.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = name,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (note != null) {
                Text(
                    text = note,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f),
                )
            }
        }

        if (controls != null) {
            SmallIcon(
                iconRes = R.drawable.ic_arrow_up,
                descriptionRes = R.string.merge_move_up,
                enabled = controls.canMoveUp,
                onClick = controls.onUp,
            )
            SmallIcon(
                iconRes = R.drawable.ic_arrow_down,
                descriptionRes = R.string.merge_move_down,
                enabled = controls.canMoveDown,
                onClick = controls.onDown,
            )
            SmallIcon(
                iconRes = R.drawable.ic_close,
                descriptionRes = R.string.merge_remove_one,
                enabled = true,
                onClick = controls.onRemove,
            )
        }
    }
}

@Composable
private fun SmallIcon(
    iconRes: Int,
    descriptionRes: Int,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(36.dp)) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = stringResource(descriptionRes),
            modifier = Modifier.size(18.dp),
            // Atenuado en lugar de oculto: si los botones desaparecieran en el primero y
            // el ultimo, el resto se desplazaria y se pulsaria el que no era.
            tint = MaterialTheme.colorScheme.onSecondaryContainer
                .copy(alpha = if (enabled) 1f else 0.3f),
        )
    }
}
