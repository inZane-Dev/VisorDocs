package com.visordocs.ui.viewer.render

import android.graphics.Color
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.toArgb

/**
 * Colores del documento HTML, tomados del tema de Compose.
 *
 * Viven en la capa de interfaz, no en la de datos: el convertidor produce solo el
 * cuerpo del documento, y el aspecto se decide aqui. Gracias a eso, cambiar de modo
 * claro a oscuro no obliga a volver a analizar el archivo.
 */
data class HtmlColors(
    val dark: Boolean,
    val background: String,
    val onBackground: String,
    val surface: String,
    val border: String,
    val subtle: String,
    val muted: String,
    val accent: String,
)

@Composable
fun rememberHtmlColors(dark: Boolean): HtmlColors {
    val scheme = MaterialTheme.colorScheme
    return remember(scheme, dark) {
        HtmlColors(
            dark = dark,
            background = scheme.surface.toCss(),
            onBackground = scheme.onSurface.toCss(),
            surface = scheme.surfaceVariant.copy(alpha = 0.35f).toCssOver(scheme.surface),
            border = scheme.outlineVariant.toCss(),
            subtle = scheme.surfaceVariant.toCss(),
            muted = scheme.onSurfaceVariant.toCss(),
            accent = scheme.primary.toCss(),
        )
    }
}

private fun androidx.compose.ui.graphics.Color.toCss(): String =
    String.format("#%06X", 0xFFFFFF and toArgb())

/**
 * Mezcla un color translucido sobre un fondo opaco.
 *
 * El CSS de las tarjetas necesita un color solido: usar rgba() sobre un fondo que ya
 * puede venir de Material You daria resultados distintos segun el tema.
 */
private fun androidx.compose.ui.graphics.Color.toCssOver(
    background: androidx.compose.ui.graphics.Color,
): String {
    val a = alpha
    val r = (red * a + background.red * (1 - a)).coerceIn(0f, 1f)
    val g = (green * a + background.green * (1 - a)).coerceIn(0f, 1f)
    val b = (blue * a + background.blue * (1 - a)).coerceIn(0f, 1f)
    val argb = Color.rgb((r * 255).toInt(), (g * 255).toInt(), (b * 255).toInt())
    return String.format("#%06X", 0xFFFFFF and argb)
}

/**
 * Envuelve el cuerpo generado por un convertidor en un documento HTML completo.
 *
 * El CSS va incrustado y no hay ni scripts ni recursos externos: el WebView carga
 * esto con `baseUrl` nulo y JavaScript desactivado, asi que el documento no puede
 * hacer ninguna peticion de red.
 */
fun htmlDocument(
    body: String,
    colors: HtmlColors,
    notice: String? = null,
): String {
    val noticeHtml = notice?.let { "<p class=\"notice\">$it</p>" }.orEmpty()
    return """<!DOCTYPE html>
<html lang="es">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<style>
:root { color-scheme: ${if (colors.dark) "dark" else "light"}; }
html, body { margin: 0; padding: 0; background: ${colors.background}; color: ${colors.onBackground}; }
body {
  font-family: -apple-system, Roboto, "Segoe UI", sans-serif;
  font-size: 16px;
  line-height: 1.6;
  padding: 16px 16px 64px;
  overflow-wrap: break-word;
}
h1, h2, h3, h4, h5, h6 { line-height: 1.3; margin: 1.4em 0 0.5em; }
h1 { font-size: 1.7em; }
h2 { font-size: 1.4em; }
h3 { font-size: 1.2em; }
h4, h5, h6 { font-size: 1.05em; }
p { margin: 0 0 0.8em; }
ul, ol { margin: 0 0 0.8em; padding-left: 1.6em; }
a { color: ${colors.accent}; }
blockquote {
  margin: 0 0 1em; padding-left: 14px;
  border-left: 3px solid ${colors.border}; color: ${colors.muted};
}

/* Las tablas anchas se desplazan dentro de su caja: el cuerpo nunca scrollea en horizontal. */
.scroll-x { overflow-x: auto; -webkit-overflow-scrolling: touch; margin: 0 0 1.2em; }
table { border-collapse: collapse; font-size: 0.92em; }
th, td {
  border: 1px solid ${colors.border};
  padding: 6px 10px;
  text-align: left;
  vertical-align: top;
  white-space: pre-wrap;
}
th { background: ${colors.subtle}; font-weight: 600; position: sticky; top: 0; }
td.num { text-align: right; font-variant-numeric: tabular-nums; }

/* Cabeceras de hoja de calculo (columna A, B, C... y numero de fila). */
th.ref { background: ${colors.subtle}; color: ${colors.muted}; font-weight: 500; text-align: center; }
td.rowref { background: ${colors.subtle}; color: ${colors.muted}; text-align: center; font-size: 0.85em; }

/* Diapositivas de PowerPoint. */
.slide {
  border: 1px solid ${colors.border};
  border-radius: 12px;
  padding: 20px;
  margin: 0 0 18px;
  background: ${colors.surface};
}
.slide-number { color: ${colors.muted}; font-size: 0.8em; text-transform: uppercase; letter-spacing: 0.06em; }
.slide h2 { margin: 4px 0 12px; font-size: 1.25em; }
.slide p { margin: 0 0 0.5em; }

/* SVG: se ajusta al ancho disponible sin deformarse ni desbordar. */
.vector { margin: 0 0 1.2em; text-align: center; }
.vector svg { max-width: 100%; height: auto; }

/* EPUB: los capítulos vienen uno detrás de otro; se separan visualmente. */
img, svg { max-width: 100%; height: auto; }

.section-title { color: ${colors.muted}; font-size: 0.8em; text-transform: uppercase; letter-spacing: 0.06em; margin: 1.6em 0 0.4em; }
.notice { color: ${colors.muted}; font-size: 0.88em; font-style: italic; margin: 1.5em 0 0; }
.center { text-align: center; }
.right { text-align: right; }
.justify { text-align: justify; }
</style>
</head>
<body>
$body
$noticeHtml
</body>
</html>"""
}
