package com.visordocs.ui.viewer.render.pdf

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.fragment.compose.AndroidFragment
import androidx.pdf.viewer.fragment.PdfViewerFragment

/**
 * Visor basado en androidx.pdf (Jetpack PDF Viewer).
 *
 * Aporta ya resuelto: zoom con pellizco, scroll continuo, busqueda de texto,
 * seleccion y copia, hipervinculos y PDF con contrasena.
 *
 * Es un Fragment, no un composable, asi que se incrusta con `AndroidFragment`.
 * Esto exige que la Activity anfitriona sea una `FragmentActivity` (ver MainActivity)
 * y que su tema derive de Material3 (ver res/values/themes.xml).
 *
 * Toda la dependencia con la libreria alpha queda contenida en este archivo: si su
 * API cambia, solo hay que tocar aqui.
 */
@Composable
fun JetpackPdfViewer(
    uri: Uri,
    modifier: Modifier = Modifier,
) {
    AndroidFragment<PdfViewerFragment>(modifier = modifier) { fragment ->
        // onUpdate se ejecuta en cada recomposicion; solo reasignar si cambio el
        // documento, porque asignar documentUri dispara una recarga completa.
        if (fragment.documentUri != uri) {
            fragment.documentUri = uri
        }
    }
}
