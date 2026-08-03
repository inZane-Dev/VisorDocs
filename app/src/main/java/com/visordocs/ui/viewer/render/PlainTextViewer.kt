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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.visordocs.R

/** Limites del pellizco. Por debajo no se lee; por encima cabe una palabra por linea. */
private const val MIN_TEXT_SCALE = 0.6f
private const val MAX_TEXT_SCALE = 4f

/**
 * Visor de texto plano: .txt, .log, .md, .json, .xml y codigo.
 *
 * Se muestra el contenido tal cual, en monoespaciado y sin ajuste de linea, porque en
 * estos formatos la alineacion de columnas suele ser significativa (un log, un JSON
 * indentado). De ahi el scroll horizontal ademas del vertical.
 *
 * El texto es seleccionable y copiable, y se puede agrandar con dos dedos.
 *
 * El pellizco cambia el TAMANO DE LETRA, no la escala del dibujo. Escalar con
 * `graphicsLayer`, que es lo barato, daria un texto borroso al ampliarlo y obligaria a
 * arrastrar en las dos direcciones; cambiando la tipografia, cada tamano se dibuja nitido
 * y las lineas se recolocan solas dentro del scroll que ya existe.
 */
@Composable
fun PlainTextViewer(
    text: String,
    truncated: Boolean,
    modifier: Modifier = Modifier,
) {
    // `rememberSaveable`: al girar la pantalla se conserva el tamano elegido.
    var scale by rememberSaveable { mutableFloatStateOf(1f) }

    val baseStyle = MaterialTheme.typography.bodySmall
    val fontSize = baseStyle.fontSize * scale
    // La altura de linea acompana al tamano; si no, las lineas se solaparian al ampliar.
    val lineHeight = (baseStyle.fontSize.value * scale * 1.35f).sp

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .horizontalScroll(rememberScrollState())
            // Solo actua con dos dedos, para no robarle el scroll de un dedo al Column.
            .pointerInput(Unit) {
                detectPinchZoom { zoomChange, _ ->
                    scale = (scale * zoomChange).coerceIn(MIN_TEXT_SCALE, MAX_TEXT_SCALE)
                }
            }
            .padding(16.dp),
    ) {
        SelectionContainer {
            Text(
                text = text,
                fontFamily = FontFamily.Monospace,
                style = baseStyle,
                fontSize = fontSize,
                lineHeight = lineHeight,
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
