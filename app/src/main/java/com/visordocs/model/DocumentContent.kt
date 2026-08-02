package com.visordocs.model

import android.graphics.Bitmap
import android.net.Uri

/**
 * El contenido de un documento, ya cargado y listo para pintar.
 *
 * Cada variante corresponde a **como se pinta**, no a que formato tenia el archivo:
 * un .docx, un .xlsx y un .csv acaban los tres en [Markup] porque los tres se
 * muestran igual. Asi la capa de interfaz solo conoce cuatro formas de pintar, no
 * once formatos.
 */
sealed interface DocumentContent {

    /**
     * El PDF no se carga aqui: los dos motores leen el [Uri] por su cuenta y hacen
     * su propia gestion de memoria pagina a pagina. Cargarlo antes solo duplicaria
     * el trabajo.
     */
    data class Pdf(val uri: Uri) : DocumentContent

    /** Fragmento HTML generado por la app. Sin `<html>` ni estilos: solo el cuerpo. */
    data class Markup(val body: String, val truncated: Boolean) : DocumentContent

    /**
     * Un documento HTML completo que viene del propio archivo, con sus estilos.
     *
     * Se distingue de [Markup] porque no se envuelve ni se le aplica el tema de la app:
     * la gracia de abrir un .html es verlo como la pagina que es. Quien lo contiene es el
     * WebView, que no ejecuta JavaScript ni deja salir ninguna peticion.
     */
    data class WebPage(val html: String) : DocumentContent

    data class PlainText(val text: String, val truncated: Boolean) : DocumentContent

    data class Picture(val bitmap: Bitmap) : DocumentContent

    /** El archivo se leyo bien, pero no hay nada que mostrar. */
    data object Empty : DocumentContent

    /** Formato reconocido para el que todavia no hay visor (.doc, .xls, .ppt). */
    data object Unsupported : DocumentContent

    /** No se pudo identificar el formato. */
    data object Unrecognized : DocumentContent
}
