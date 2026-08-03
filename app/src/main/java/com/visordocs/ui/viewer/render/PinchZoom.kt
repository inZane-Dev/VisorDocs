package com.visordocs.ui.viewer.render

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerInputScope

/**
 * Detecta zoom y arrastre solo cuando hay dos dedos o mas.
 *
 * `detectTransformGestures`, el de la libreria, consumiria tambien los gestos de un solo
 * dedo y dejaria sin scroll al contenedor que haya debajo. Al no consumir los eventos de
 * un solo puntero, estos llegan intactos a la lista o al scroll.
 *
 * Lo usan el visor de PDF propio y el de texto plano, que tienen el mismo problema:
 * quieren pellizco sin renunciar a desplazarse con un dedo.
 */
internal suspend fun PointerInputScope.detectPinchZoom(
    onTransform: (zoomChange: Float, panChange: Offset) -> Unit,
) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false)
        do {
            val event = awaitPointerEvent()
            if (event.changes.size >= 2) {
                val zoomChange = event.calculateZoom()
                val panChange = event.calculatePan()
                if (zoomChange != 1f || panChange != Offset.Zero) {
                    onTransform(zoomChange, panChange)
                    event.changes.forEach { it.consume() }
                }
            }
        } while (event.changes.any { it.pressed })
    }
}
