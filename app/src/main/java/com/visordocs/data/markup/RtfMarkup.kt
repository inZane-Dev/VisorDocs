package com.visordocs.data.markup

import java.nio.charset.Charset

/**
 * Convierte un .rtf a HTML.
 *
 * RTF no es XML: es un lenguaje de marcado con grupos entre llaves y "palabras de
 * control" precedidas de barra invertida (`\b` negrita, `\par` fin de parrafo). El
 * formato se hereda por grupo, asi que hay que llevar una pila: al abrir `{` se guarda
 * el estado y al cerrar `}` se restaura.
 *
 * Buena parte del archivo es informacion que no se muestra: tablas de fuentes y
 * colores, hojas de estilo, metadatos e imagenes incrustadas. Esos "destinos" se
 * saltan enteros, con su grupo, en cuanto se reconocen.
 *
 * Se conserva: parrafos, negrita, cursiva, subrayado, tachado, saltos de linea,
 * tabulaciones y los acentos (tanto en escape hexadecimal como en Unicode). No se
 * conservan imagenes, tablas con su cuadricula, colores ni fuentes.
 */
object RtfMarkup {

    private const val MAX_PARAGRAPHS = 20_000

    /**
     * Los escapes `\'hh` van en la pagina de codigos del documento. Windows-1252 es la
     * que usa casi todo RTF generado en occidente; si no estuviera disponible se cae a
     * Latin-1, que coincide en el rango alto salvo en unos pocos simbolos.
     */
    private val ANSI: Charset = runCatching { Charset.forName("windows-1252") }
        .getOrElse { Charsets.ISO_8859_1 }

    /** Destinos cuyo contenido no se muestra nunca. */
    private val SKIPPED_DESTINATIONS = setOf(
        "fonttbl", "colortbl", "stylesheet", "info", "pict", "object", "objdata",
        "header", "headerl", "headerr", "headerf", "footer", "footerl", "footerr", "footerf",
        "footnote", "annotation", "themedata", "colorschememapping", "datastore",
        "latentstyles", "listtable", "listoverridetable", "filetbl", "xmlnstbl",
        "rsidtbl", "generator", "fldinst", "docvar", "mmath", "template",
    )

    private data class Format(
        val bold: Boolean = false,
        val italic: Boolean = false,
        val underline: Boolean = false,
        val strike: Boolean = false,
    )

    fun convert(rtf: String): Markup {
        if (!rtf.trimStart().startsWith("{\\rtf")) return Markup.Empty

        val out = StringBuilder(8 * 1024)
        val paragraph = StringBuilder()

        val stack = ArrayDeque<Format>()
        var format = Format()
        var openFormat = Format()

        var depth = 0
        // Profundidad del grupo que se esta saltando, o null si no se salta nada.
        var skipDepth: Int? = null
        // Cuantos caracteres de repuesto sigue a un \uN (lo fija \ucN).
        var unicodeSkip = 1

        var paragraphs = 0
        var truncated = false
        var i = 0

        fun skipping() = skipDepth != null

        /** Ajusta las etiquetas abiertas para que reflejen [format]. */
        fun syncFormat() {
            if (format == openFormat) return
            // Se cierran en orden inverso al de apertura.
            if (openFormat.strike) paragraph.append("</s>")
            if (openFormat.underline) paragraph.append("</u>")
            if (openFormat.italic) paragraph.append("</em>")
            if (openFormat.bold) paragraph.append("</strong>")
            if (format.bold) paragraph.append("<strong>")
            if (format.italic) paragraph.append("<em>")
            if (format.underline) paragraph.append("<u>")
            if (format.strike) paragraph.append("<s>")
            openFormat = format
        }

        fun appendText(text: String) {
            if (skipping() || text.isEmpty()) return
            syncFormat()
            paragraph.append(text.escapeHtml())
        }

        fun flushParagraph() {
            // Se cierran las etiquetas antes de cortar el parrafo, o quedarian abiertas.
            val closing = StringBuilder()
            if (openFormat.strike) closing.append("</s>")
            if (openFormat.underline) closing.append("</u>")
            if (openFormat.italic) closing.append("</em>")
            if (openFormat.bold) closing.append("</strong>")
            paragraph.append(closing)
            openFormat = Format()

            val content = paragraph.toString()
            paragraph.setLength(0)
            if (content.isBlank()) return

            paragraphs++
            if (paragraphs > MAX_PARAGRAPHS) truncated = true else out.append("<p>$content</p>\n")
        }

        while (i < rtf.length) {
            if (truncated) break
            when (val c = rtf[i]) {
                '{' -> {
                    stack.addLast(format)
                    depth++
                    i++
                }

                '}' -> {
                    if (skipDepth != null && depth <= skipDepth!!) skipDepth = null
                    if (stack.isNotEmpty()) format = stack.removeLast()
                    depth--
                    i++
                }

                '\\' -> {
                    i++
                    if (i >= rtf.length) break
                    val next = rtf[i]

                    when {
                        // Escape hexadecimal: \'e1 es "a con acento" en la pagina ANSI.
                        next == '\'' -> {
                            val hex = rtf.substring(i + 1, minOf(i + 3, rtf.length))
                            val code = hex.toIntOrNull(16)
                            if (code != null) {
                                appendText(String(byteArrayOf(code.toByte()), ANSI))
                            }
                            i += 3
                        }

                        // Marca de "destino opcional": si no se reconoce, se ignora entero.
                        next == '*' -> {
                            if (skipDepth == null) skipDepth = depth
                            i++
                        }

                        !next.isLetter() -> {
                            when (next) {
                                '\\', '{', '}' -> appendText(next.toString())
                                '~' -> appendText(" ")
                                '\n', '\r' -> flushParagraph()
                            }
                            i++
                        }

                        else -> {
                            // Palabra de control: letras, parametro numerico opcional y un
                            // espacio que actua de separador y no es texto.
                            val start = i
                            while (i < rtf.length && rtf[i].isLetter()) i++
                            val word = rtf.substring(start, i)

                            val paramStart = i
                            if (i < rtf.length && (rtf[i] == '-' || rtf[i].isDigit())) {
                                i++
                                while (i < rtf.length && rtf[i].isDigit()) i++
                            }
                            val param = rtf.substring(paramStart, i).toIntOrNull()
                            if (i < rtf.length && rtf[i] == ' ') i++

                            when (word) {
                                in SKIPPED_DESTINATIONS -> if (skipDepth == null) skipDepth = depth

                                "par", "row" -> if (!skipping()) flushParagraph()
                                "line" -> if (!skipping()) { syncFormat(); paragraph.append("<br>") }
                                "tab" -> appendText("\t")
                                "cell" -> appendText("  ")
                                "emdash" -> appendText("—")
                                "endash" -> appendText("–")
                                "lquote" -> appendText("‘")
                                "rquote" -> appendText("’")
                                "ldblquote" -> appendText("“")
                                "rdblquote" -> appendText("”")
                                "bullet" -> appendText("•")

                                "b" -> format = format.copy(bold = param != 0)
                                "i" -> format = format.copy(italic = param != 0)
                                "strike" -> format = format.copy(strike = param != 0)
                                "ul" -> format = format.copy(underline = param != 0)
                                "ulnone" -> format = format.copy(underline = false)
                                "plain" -> format = Format()

                                "uc" -> unicodeSkip = (param ?: 1).coerceIn(0, 10)

                                "u" -> {
                                    // El parametro es un entero de 16 bits con signo.
                                    val code = param?.let { if (it < 0) it + 0x10000 else it }
                                    if (code != null && code in 1..0x10FFFF) {
                                        appendText(String(Character.toChars(code)))
                                    }
                                    // Detras viene una version ANSI del mismo caracter,
                                    // para lectores antiguos; hay que saltarla.
                                    var skipped = 0
                                    while (skipped < unicodeSkip && i < rtf.length) {
                                        if (rtf[i] == '\\' && i + 1 < rtf.length && rtf[i + 1] == '\'') {
                                            i += 4
                                        } else {
                                            i++
                                        }
                                        skipped++
                                    }
                                }
                            }
                        }
                    }
                }

                // Los saltos de linea del propio archivo no son texto en RTF.
                '\r', '\n' -> i++

                else -> {
                    appendText(c.toString())
                    i++
                }
            }
        }

        flushParagraph()
        return Markup(body = out.toString(), truncated = truncated)
    }
}
