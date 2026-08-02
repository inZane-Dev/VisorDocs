package com.visordocs.ui.viewer.render

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.visordocs.R

/**
 * Visor de texto plano: .txt, .log, .md, .json, .xml.
 *
 * Se muestra el contenido tal cual, en monoespaciado y sin ajuste de linea, porque en
 * estos formatos la alineacion de columnas suele ser significativa (un log, un JSON
 * indentado). De ahi el scroll horizontal ademas del vertical.
 *
 * El texto es seleccionable y copiable.
 */
@Composable
fun PlainTextViewer(
    text: String,
    truncated: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .horizontalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        SelectionContainer {
            Text(
                text = text,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
                softWrap = false,
            )
        }
        if (truncated) {
            Text(
                text = stringResource(R.string.office_truncated),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 24.dp),
            )
        }
    }
}
