package com.visordocs.data.markup

/**
 * Prepara un .svg para mostrarlo en el WebView.
 *
 * Un SVG es texto, no un mapa de bits: `BitmapFactory` no sabe decodificarlo, y por eso
 * antes acababa siempre en un error. El WebView, en cambio, lo dibuja de forma nativa,
 * asi que basta con incrustarlo.
 *
 * Se recorta todo lo que hay antes de `<svg`, porque la declaracion XML y el DOCTYPE no
 * son validos dentro del cuerpo de un documento HTML.
 *
 * El SVG entra tal cual, sin escapar: es la unica forma de que se dibuje. Lo que lo hace
 * seguro es el WebView que lo recibe, no una limpieza previa: no ejecuta JavaScript,
 * bloquea cualquier peticion de red y no tiene acceso a archivos. Un SVG con un script
 * dentro no hace nada, y uno que apunte a una imagen remota no llega a pedirla.
 */
object SvgMarkup {

    fun convert(svg: String): Markup {
        val start = svg.indexOf("<svg", ignoreCase = true)
        if (start < 0) return Markup.Empty

        val end = svg.lastIndexOf("</svg>", ignoreCase = true)
        val body = if (end > start) svg.substring(start, end + 6) else svg.substring(start)

        return Markup(body = "<div class=\"vector\">$body</div>")
    }
}
