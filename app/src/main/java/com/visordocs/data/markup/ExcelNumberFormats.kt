package com.visordocs.data.markup

import com.visordocs.data.xml.attr
import com.visordocs.data.xml.parserFor
import com.visordocs.data.zip.ZipPackage
import org.xmlpull.v1.XmlPullParser
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.absoluteValue

/**
 * Traduce los numeros de serie de Excel a fechas legibles.
 *
 * Excel no guarda fechas: guarda numeros. El 15/03/2023 es el 45000, y lo que lo
 * convierte en fecha a los ojos del usuario es el **formato numerico** aplicado a la
 * celda. Sin leerlo, una hoja de fechas se ve como una columna de cinco cifras.
 *
 * La cadena es indirecta: la celda tiene `s="3"`, que apunta a la posicion 3 de
 * `<cellXfs>` en `xl/styles.xml`, que a su vez tiene un `numFmtId`. Ese id puede ser uno
 * de los predefinidos por Excel o uno propio declarado en `<numFmts>`.
 */
object ExcelNumberFormats {

    /**
     * Ids de formato predefinidos de Excel que son fechas u horas.
     *
     * No aparecen declarados en ningun sitio del archivo: son parte de la especificacion
     * y hay que conocerlos. Los 14-22 son fechas y horas; los 45-47, duraciones.
     */
    private val BUILT_IN_DATE_IDS = (14..22).toSet() + setOf(45, 46, 47)

    /** Solo hora, sin parte de fecha. */
    private val BUILT_IN_TIME_ONLY_IDS = setOf(18, 19, 20, 21, 45, 46, 47)

    /**
     * El origen del calendario de Excel, con su fallo historico incluido.
     *
     * Excel considera 1900 bisiesto para mantener compatibilidad con Lotus 1-2-3, asi que
     * cree en un 29 de febrero de 1900 que nunca existio. Eso parte el calendario en dos:
     *
     * - Series 1 a 59 (hasta el 28/02/1900): el origen real es el **31** de diciembre.
     * - Serie 60: el dia fantasma. No es una fecha, asi que se muestra como numero.
     * - Series 61 en adelante: el origen es el **30** de diciembre, un dia antes, para
     *   compensar el dia de mas que Excel cuenta.
     *
     * Casi todo el mundo usa solo el tercer caso, pero ignorar los otros dos desplazaria
     * un dia las fechas de 1900.
     */
    private val EXCEL_EPOCH: LocalDate = LocalDate.of(1899, 12, 30)

    /** Primera serie a partir de la cual el desfase del dia fantasma ya esta aplicado. */
    private const val FIRST_SERIAL_AFTER_PHANTOM_DAY = 61L

    private val DATE_FORMAT = DateTimeFormatter.ofPattern("dd/MM/yyyy")
    private val DATE_TIME_FORMAT = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")
    private val TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm")

    /** Que hacer con el numero de una celda, segun el estilo que tenga aplicado. */
    enum class Kind { PLAIN, DATE, DATE_TIME, TIME }

    /**
     * Estilos del libro: para cada posicion de `<cellXfs>`, que tipo de numero es.
     *
     * Se resuelve una sola vez por documento y luego se consulta por indice.
     */
    class Styles(private val kindByStyleIndex: List<Kind>) {

        fun kindOf(styleIndex: Int?): Kind =
            styleIndex?.let { kindByStyleIndex.getOrNull(it) } ?: Kind.PLAIN

        companion object {
            val Empty = Styles(emptyList())
        }
    }

    fun read(pkg: ZipPackage): Styles {
        val xml = pkg.text("xl/styles.xml") ?: return Styles.Empty

        val customFormats = HashMap<Int, String>()
        val kinds = mutableListOf<Kind>()

        // <cellXfs> es la lista que indexan las celdas. Hay otra lista casi identica,
        // <cellStyleXfs>, que describe estilos con nombre y no interesa aqui.
        var inCellXfs = false

        val parser = parserFor(xml)
        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "numFmt" -> {
                        val id = parser.attr("numFmtId")?.toIntOrNull()
                        val code = parser.attr("formatCode")
                        if (id != null && code != null) customFormats[id] = code
                    }

                    "cellXfs" -> inCellXfs = true

                    "xf" -> if (inCellXfs) {
                        val id = parser.attr("numFmtId")?.toIntOrNull()
                        kinds += kindFor(id, customFormats)
                    }
                }

                XmlPullParser.END_TAG -> if (parser.name == "cellXfs") inCellXfs = false
            }
            parser.next()
        }

        return Styles(kinds)
    }

    private fun kindFor(numFmtId: Int?, customFormats: Map<Int, String>): Kind {
        if (numFmtId == null) return Kind.PLAIN

        customFormats[numFmtId]?.let { return kindForCode(it) }

        return when {
            numFmtId in BUILT_IN_TIME_ONLY_IDS -> Kind.TIME
            numFmtId in BUILT_IN_DATE_IDS -> Kind.DATE
            else -> Kind.PLAIN
        }
    }

    /**
     * Deduce el tipo mirando el codigo de formato.
     *
     * Se ignora lo que va entre comillas: un formato como `0" dias"` lleva una `d` que es
     * texto literal, no un marcador de dia, y sin filtrarla se tomaria por una fecha.
     */
    private fun kindForCode(code: String): Kind {
        val markers = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < code.length) {
            val c = code[i]
            when {
                c == '"' -> inQuotes = !inQuotes
                // La barra invertida escapa el caracter siguiente.
                c == '\\' -> i++
                // Los corchetes encierran condiciones y colores, no formato de fecha,
                // salvo las duraciones [h] [m] [s].
                !inQuotes -> markers.append(c.lowercaseChar())
            }
            i++
        }

        val text = markers.toString()
        val hasDate = text.any { it == 'y' || it == 'd' } || text.contains("mmm")
        val hasTime = text.any { it == 'h' || it == 's' }

        return when {
            hasDate && hasTime -> Kind.DATE_TIME
            hasDate -> Kind.DATE
            hasTime -> Kind.TIME
            else -> Kind.PLAIN
        }
    }

    /**
     * Convierte el numero de serie al texto que corresponda, o null si no procede.
     *
     * Se rechazan los valores negativos y los desmesurados: en Excel no existen fechas
     * anteriores al origen, y un numero enorme con formato de fecha es casi siempre un
     * dato mal etiquetado que es mejor mostrar tal cual.
     */
    fun format(serial: Double, kind: Kind): String? {
        if (kind == Kind.PLAIN) return null
        if (serial < 0 || serial > 2_958_465) return null

        val days = serial.toLong()
        // La parte decimal es la fraccion del dia: 0,5 es el mediodia.
        val secondsOfDay = ((serial - days) * 86_400).toLong().coerceIn(0, 86_399)

        // Una serie de solo hora (kind TIME) puede venir sin parte de dia; ahi el 0 es
        // valido y solo cuenta la fraccion.
        val dayOffset = when {
            days >= FIRST_SERIAL_AFTER_PHANTOM_DAY -> days
            days in 1..59 -> days + 1
            days == 0L && kind == Kind.TIME -> 1
            // El dia 60 no existe, y el 0 no es una fecha.
            else -> return null
        }

        return runCatching {
            val date = EXCEL_EPOCH.plusDays(dayOffset)
            when (kind) {
                Kind.DATE -> date.format(DATE_FORMAT)
                Kind.TIME -> LocalDateTime.of(date, java.time.LocalTime.ofSecondOfDay(secondsOfDay))
                    .format(TIME_FORMAT)

                Kind.DATE_TIME -> LocalDateTime.of(
                    date,
                    java.time.LocalTime.ofSecondOfDay(secondsOfDay),
                ).format(DATE_TIME_FORMAT)

                Kind.PLAIN -> null
            }
        }.getOrNull()
    }

    /** Un numero de serie sin parte decimal significativa representa un dia entero. */
    internal fun isWholeDay(serial: Double): Boolean =
        (serial - serial.toLong()).absoluteValue < 1e-9
}
