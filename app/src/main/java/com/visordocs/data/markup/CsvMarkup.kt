package com.visordocs.data.markup

/**
 * Convierte CSV a una tabla HTML.
 *
 * El separador se detecta solo. No siempre es la coma: Excel en configuracion
 * espanola exporta con punto y coma, porque la coma ya se usa como decimal.
 *
 * Se respeta el entrecomillado del formato: un campo entre comillas puede contener
 * separadores, saltos de linea y comillas escapadas como `""`.
 */
object CsvMarkup {

    private const val MAX_ROWS = 5_000
    private const val MAX_COLS = 128

    fun convert(csv: String, sourceTruncated: Boolean = false): Markup {
        if (csv.isBlank()) return Markup.Empty

        val rows = parse(csv, detectDelimiter(csv))
        if (rows.isEmpty()) return Markup.Empty

        val truncated = sourceTruncated || rows.size > MAX_ROWS
        val visible = rows.take(MAX_ROWS)
        val columns = visible.maxOf { it.size }.coerceAtMost(MAX_COLS)

        val out = StringBuilder(16 * 1024)
        out.append("<div class=\"scroll-x\"><table>\n")

        // La primera fila se trata como cabecera: es lo habitual en un CSV.
        visible.forEachIndexed { index, row ->
            out.append("<tr>")
            val tag = if (index == 0) "th" else "td"
            for (c in 0 until columns) {
                val value = row.getOrNull(c).orEmpty()
                val numeric = index > 0 && value.isNotEmpty() &&
                    value.replace(',', '.').toDoubleOrNull() != null
                out.append(if (numeric) "<td class=\"num\">" else "<$tag>")
                out.append(value.escapeHtml())
                out.append(if (numeric) "</td>" else "</$tag>")
            }
            out.append("</tr>\n")
        }
        out.append("</table></div>\n")

        return Markup(body = out.toString(), truncated = truncated)
    }

    private fun detectDelimiter(csv: String): Char {
        val sample = csv.lineSequence().take(20).joinToString("\n")
        return listOf(',', ';', '\t', '|')
            .maxByOrNull { candidate -> sample.count { it == candidate } }
            ?.takeIf { candidate -> sample.any { it == candidate } }
            ?: ','
    }

    private fun parse(csv: String, delimiter: Char): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        var row = mutableListOf<String>()
        val field = StringBuilder()
        var inQuotes = false
        var i = 0

        fun endField() {
            row.add(field.toString())
            field.setLength(0)
        }

        fun endRow() {
            endField()
            // Se descartan las lineas completamente vacias.
            if (row.size > 1 || row.firstOrNull()?.isNotEmpty() == true) rows.add(row)
            row = mutableListOf()
        }

        while (i < csv.length) {
            val c = csv[i]
            when {
                inQuotes && c == '"' && i + 1 < csv.length && csv[i + 1] == '"' -> {
                    field.append('"')
                    i++
                }

                c == '"' -> inQuotes = !inQuotes
                !inQuotes && c == delimiter -> endField()
                !inQuotes && (c == '\n' || c == '\r') -> {
                    // \r\n cuenta como un solo final de linea.
                    if (c == '\r' && i + 1 < csv.length && csv[i + 1] == '\n') i++
                    endRow()
                }

                else -> field.append(c)
            }
            i++
            if (rows.size > MAX_ROWS) break
        }
        if (field.isNotEmpty() || row.isNotEmpty()) endRow()

        return rows
    }
}
