package com.visordocs.data.markup

import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Resolucion de los colores de OOXML a algo que el WebView entienda.
 *
 * Un `<color>` de Office puede venir de cuatro formas distintas, y solo una es directa:
 *
 * - `rgb="FFCC0000"` — ARGB en hexadecimal. Los dos primeros digitos son la opacidad.
 * - `indexed="10"` — posicion en una paleta fija de 56 colores heredada de Excel 97.
 *   No aparece en el archivo: hay que conocerla.
 * - `theme="4" tint="-0.25"` — color del tema del documento, aclarado u oscurecido.
 * - `auto="1"` — "el que decida la aplicacion". Se ignora, para que mande el tema de
 *   la app.
 */
internal object OfficeColor {

    /**
     * Paleta indexada de Excel. El orden importa: la posicion ES el identificador.
     *
     * Las cuatro primeras entradas se repiten mas adelante por razones historicas, y las
     * dos ultimas (64 y 65) son "automatico", que aqui no se resuelve.
     */
    private val INDEXED = listOf(
        0x000000, 0xFFFFFF, 0xFF0000, 0x00FF00, 0x0000FF, 0xFFFF00, 0xFF00FF, 0x00FFFF,
        0x000000, 0xFFFFFF, 0xFF0000, 0x00FF00, 0x0000FF, 0xFFFF00, 0xFF00FF, 0x00FFFF,
        0x800000, 0x008000, 0x000080, 0x808000, 0x800080, 0x008080, 0xC0C0C0, 0x808080,
        0x9999FF, 0x993366, 0xFFFFCC, 0xCCFFFF, 0x660066, 0xFF8080, 0x0066CC, 0xCCCCFF,
        0x000080, 0xFF00FF, 0xFFFF00, 0x00FFFF, 0x800080, 0x800000, 0x008080, 0x0000FF,
        0x00CCFF, 0xCCFFFF, 0xCCFFCC, 0xFFFF99, 0x99CCFF, 0xFF99CC, 0xCC99FF, 0xFFCC99,
        0x3366FF, 0x33CCCC, 0x99CC00, 0xFFCC00, 0xFF9900, 0xFF6600, 0x666699, 0x969696,
        0x003366, 0x339966, 0x003300, 0x333300, 0x993300, 0x993366, 0x333399, 0x333333,
    )

    /**
     * Colores del tema por omision de Office.
     *
     * Se usan cuando el documento no trae `theme1.xml` o no se pudo leer. El orden es el
     * que indexa `theme=`, que NO es el del archivo: alli van `dk1, lt1, dk2, lt2...` y
     * aqui los dos primeros pares estan intercambiados. Confundirlos pinta el texto del
     * color del fondo.
     */
    private val DEFAULT_THEME = listOf(
        0xFFFFFF, 0x000000, 0xE7E6E6, 0x44546A,
        0x4472C4, 0xED7D31, 0xA5A5A5, 0xFFC000, 0x5B9BD5, 0x70AD47,
        0x0563C1, 0x954F72,
    )

    /** Un color ya resuelto, listo para CSS. */
    @JvmInline
    value class Rgb(val value: Int) {
        fun toCss(): String = String.format(Locale.ROOT, "#%06X", value and 0xFFFFFF)

        /**
         * Luminancia percibida, 0 (negro) a 1 (blanco).
         *
         * Los coeficientes no son iguales porque el ojo no ve todos los canales con la
         * misma intensidad: el verde pesa mas del doble que el rojo, y el azul apenas
         * cuenta.
         */
        val luminance: Float
            get() {
                val r = (value shr 16 and 0xFF) / 255f
                val g = (value shr 8 and 0xFF) / 255f
                val b = (value and 0xFF) / 255f
                return 0.299f * r + 0.587f * g + 0.114f * b
            }

        /** Negro o blanco, el que se lea encima de este color. */
        fun readableForeground(): Rgb = if (luminance > 0.55f) Rgb(0x000000) else Rgb(0xFFFFFF)

        /**
         * Si el color es casi negro o casi blanco.
         *
         * Esos dos casos son "el color de texto por omision" dicho de otra forma, y
         * conviene NO aplicarlos: dejando que mande el tema de la app, el documento se
         * sigue leyendo en modo oscuro. Aplicarlos pintaria texto negro sobre fondo negro.
         */
        val isNearDefault: Boolean
            get() = luminance < 0.12f || luminance > 0.92f
    }

    /**
     * Resuelve un `<color>` a partir de sus atributos.
     *
     * @param theme paleta del documento; si esta vacia se usa la de Office.
     */
    fun resolve(
        rgb: String?,
        indexed: String?,
        themeIndex: String?,
        tint: String?,
        theme: List<Int> = emptyList(),
    ): Rgb? {
        val base = when {
            rgb != null -> parseHex(rgb)
            indexed != null -> indexed.toIntOrNull()?.let { INDEXED.getOrNull(it) }
            themeIndex != null -> {
                val palette = theme.ifEmpty { DEFAULT_THEME }
                themeIndex.toIntOrNull()?.let { palette.getOrNull(it) }
            }
            else -> null
        } ?: return null

        val amount = tint?.toFloatOrNull() ?: 0f
        return Rgb(if (amount == 0f) base else applyTint(base, amount))
    }

    /** `FFCC0000` (ARGB) o `CC0000` (RGB). Se descarta la opacidad: el WebView pinta opaco. */
    private fun parseHex(value: String): Int? {
        val clean = value.trim().removePrefix("#")
        val hex = when (clean.length) {
            8 -> clean.substring(2)
            6 -> clean
            else -> return null
        }
        return hex.toIntOrNull(16)
    }

    /**
     * Aclara u oscurece un color, como hace Office con `tint`.
     *
     * Se opera sobre la luminosidad en HSL y no sobre los canales RGB directamente: subir
     * los tres canales por igual lava el color y lo vuelve grisaceo, mientras que mover la
     * luminosidad conserva el tono. Es la diferencia entre un "azul claro" y un "azul
     * desvaido".
     *
     * Negativo oscurece, positivo aclara, en el rango -1..1.
     */
    private fun applyTint(color: Int, tint: Float): Int {
        val (h, s, l) = toHsl(color)
        val newL = if (tint < 0) {
            l * (1 + tint)
        } else {
            l * (1 - tint) + tint
        }
        return fromHsl(h, s, newL.coerceIn(0f, 1f))
    }

    private fun toHsl(color: Int): Triple<Float, Float, Float> {
        val r = (color shr 16 and 0xFF) / 255f
        val g = (color shr 8 and 0xFF) / 255f
        val b = (color and 0xFF) / 255f

        val max = maxOf(r, g, b)
        val min = minOf(r, g, b)
        val l = (max + min) / 2f
        if (max == min) return Triple(0f, 0f, l)

        val d = max - min
        val s = if (l > 0.5f) d / (2f - max - min) else d / (max + min)
        val h = when (max) {
            r -> ((g - b) / d + if (g < b) 6f else 0f)
            g -> ((b - r) / d + 2f)
            else -> ((r - g) / d + 4f)
        } / 6f
        return Triple(h, s, l)
    }

    private fun fromHsl(h: Float, s: Float, l: Float): Int {
        if (s == 0f) {
            val v = (l * 255).roundToInt().coerceIn(0, 255)
            return (v shl 16) or (v shl 8) or v
        }
        val q = if (l < 0.5f) l * (1 + s) else l + s - l * s
        val p = 2 * l - q
        val r = hueToChannel(p, q, h + 1f / 3f)
        val g = hueToChannel(p, q, h)
        val b = hueToChannel(p, q, h - 1f / 3f)
        return (channel(r) shl 16) or (channel(g) shl 8) or channel(b)
    }

    private fun hueToChannel(p: Float, q: Float, tRaw: Float): Float {
        var t = tRaw
        if (t < 0) t += 1f
        if (t > 1) t -= 1f
        return when {
            t < 1f / 6f -> p + (q - p) * 6f * t
            t < 1f / 2f -> q
            t < 2f / 3f -> p + (q - p) * (2f / 3f - t) * 6f
            else -> p
        }
    }

    private fun channel(value: Float): Int = (value * 255).roundToInt().coerceIn(0, 255)

    /**
     * Nombres del tema en Word.
     *
     * Word no usa el indice numerico de las hojas de calculo: escribe el nombre
     * (`w:themeColor="accent1"`). Se traduce a la misma posicion que usa [resolve].
     */
    private val THEME_BY_NAME = mapOf(
        "light1" to 0, "background1" to 0,
        "dark1" to 1, "text1" to 1,
        "light2" to 2, "background2" to 2,
        "dark2" to 3, "text2" to 3,
        "accent1" to 4, "accent2" to 5, "accent3" to 6,
        "accent4" to 7, "accent5" to 8, "accent6" to 9,
        "hyperlink" to 10, "followedHyperlink" to 11,
    )

    /** Resuelve un color del tema nombrado, como los que escribe Word. */
    fun themeByName(name: String?, tint: String?, theme: List<Int> = emptyList()): Rgb? {
        val index = THEME_BY_NAME[name] ?: return null
        return resolve(
            rgb = null,
            indexed = null,
            themeIndex = index.toString(),
            tint = tint,
            theme = theme,
        )
    }

    /** Nombres de resaltado de Word (`<w:highlight w:val="yellow"/>`). */
    private val HIGHLIGHTS = mapOf(
        "black" to 0x000000, "blue" to 0x0000FF, "cyan" to 0x00FFFF,
        "darkBlue" to 0x000080, "darkCyan" to 0x008080, "darkGray" to 0x808080,
        "darkGreen" to 0x008000, "darkMagenta" to 0x800080, "darkRed" to 0x800000,
        "darkYellow" to 0x808000, "green" to 0x00FF00, "lightGray" to 0xC0C0C0,
        "magenta" to 0xFF00FF, "red" to 0xFF0000, "white" to 0xFFFFFF,
        "yellow" to 0xFFFF00,
    )

    fun highlight(name: String?): Rgb? {
        if (name == null || name == "none") return null
        return HIGHLIGHTS[name]?.let { Rgb(it) }
    }

    /** Diferencia de luminancia entre dos colores, para decidir si uno se lee sobre el otro. */
    fun contrast(a: Rgb, b: Rgb): Float = abs(a.luminance - b.luminance)
}
