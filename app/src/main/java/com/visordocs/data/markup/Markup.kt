package com.visordocs.data.markup

/**
 * Resultado de convertir un documento a un fragmento HTML.
 *
 * Es solo el **cuerpo**: sin `<html>`, sin `<style>` y sin colores. La hoja de estilos
 * y el tema los pone la capa de interfaz al pintarlo, para que un cambio de modo
 * claro/oscuro no obligue a volver a analizar el archivo entero.
 */
data class Markup(val body: String, val truncated: Boolean = false) {

    val isEmpty: Boolean get() = body.isBlank()

    companion object {
        val Empty = Markup(body = "", truncated = false)
    }
}

/**
 * Textos que los convertidores necesitan insertar dentro del HTML.
 *
 * Se reciben desde la capa de interfaz en lugar de escribirlos aqui: son texto
 * visible y tienen que salir de `strings.xml` para poder traducirse. Esta capa no
 * conoce recursos de Android.
 */
data class MarkupLabels(
    val sheet: String,
    val slide: String,
)

internal fun String.escapeHtml(): String {
    val needsEscaping = any { it == '&' || it == '<' || it == '>' || it == '"' || it == '\'' }
    if (!needsEscaping) return this
    return buildString(length + 16) {
        for (c in this@escapeHtml) {
            when (c) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&#39;")
                else -> append(c)
            }
        }
    }
}
