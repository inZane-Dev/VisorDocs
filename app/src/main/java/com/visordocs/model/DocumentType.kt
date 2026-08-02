package com.visordocs.model

import androidx.annotation.StringRes
import com.visordocs.R

/**
 * Formatos que la app conoce.
 *
 * Reconocer un formato no implica poder mostrarlo: los binarios antiguos de Office
 * (.doc, .xls, .ppt) se detectan a proposito para poder dar un mensaje concreto que
 * sugiera convertirlos, en lugar de un "formato no reconocido" que no ayuda. Son
 * contenedores OLE2, no ZIP con XML, y quedan fuera del alcance del proyecto.
 *
 * Quien decide como se muestra cada uno es `DocumentRepository.load()`, que devuelve la
 * variante de `DocumentContent` correspondiente. Aqui no hay ninguna bandera de
 * "soportado": tenerla duplicaria esa decision en dos sitios que podrian discrepar.
 *
 * La etiqueta se guarda como id de recurso, no como texto: es texto de interfaz y debe
 * poder traducirse.
 */
enum class DocumentType(@get:StringRes val labelRes: Int) {
    PDF(R.string.format_pdf),

    // OOXML: Office moderno.
    WORD(R.string.format_word),
    EXCEL(R.string.format_excel),
    POWERPOINT(R.string.format_powerpoint),

    // OpenDocument: LibreOffice y OpenOffice.
    ODT(R.string.format_odt),
    ODS(R.string.format_ods),
    ODP(R.string.format_odp),

    // Binarios OLE2, sin visor a proposito.
    WORD_LEGACY(R.string.format_word_legacy),
    EXCEL_LEGACY(R.string.format_excel_legacy),
    POWERPOINT_LEGACY(R.string.format_powerpoint_legacy),

    RTF(R.string.format_rtf),
    EPUB(R.string.format_epub),

    /** Se muestra como pagina, no como codigo fuente. Va aparte de [PLAIN_TEXT]. */
    HTML(R.string.format_html),

    PLAIN_TEXT(R.string.format_plain_text),
    CSV(R.string.format_csv),
    IMAGE(R.string.format_image),

    /** SVG va aparte de [IMAGE]: es texto y lo dibuja el WebView, no BitmapFactory. */
    SVG(R.string.format_svg),

    UNKNOWN(R.string.format_unknown),
}
