package com.visordocs.data.pdf

/**
 * Construye un PDF valido con [pages] paginas, cada una con un texto reconocible.
 *
 * Se generan aqui en lugar de guardarlos como assets binarios: asi se ve exactamente que
 * contiene cada documento, y anadir un caso es escribir unas lineas en vez de fabricar un
 * archivo con otra herramienta.
 *
 * Las posiciones de la tabla `xref` se calculan sobre los bytes ya escritos; si no
 * cuadran, PDFBox rechaza el archivo, asi que el propio generador queda comprobado por el
 * hecho de que las pruebas pasen.
 */
internal fun pdfWithPages(label: String, pages: Int): ByteArray {
    val objects = LinkedHashMap<Int, String>()
    val pageIds = (0 until pages).map { 4 + it * 2 }
    val contentIds = pageIds.map { it + 1 }

    objects[1] = "<< /Type /Catalog /Pages 2 0 R >>"
    objects[2] = "<< /Type /Pages /Count $pages /Kids [${pageIds.joinToString(" ") { "$it 0 R" }}] >>"
    objects[3] = "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>"

    pageIds.forEachIndexed { index, pageId ->
        val stream = "BT\n/F1 24 Tf\n72 700 Td\n($label ${index + 1}) Tj\nET\n"
        objects[pageId] = "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] " +
            "/Resources << /Font << /F1 3 0 R >> >> /Contents ${contentIds[index]} 0 R >>"
        objects[contentIds[index]] = "<< /Length ${stream.length} >>\nstream\n$stream\nendstream"
    }

    val body = StringBuilder("%PDF-1.4\n")
    val offsets = LinkedHashMap<Int, Int>()
    objects.forEach { (id, content) ->
        offsets[id] = body.length
        body.append("$id 0 obj\n$content\nendobj\n")
    }

    val xrefStart = body.length
    val last = objects.keys.max()
    body.append("xref\n0 ${last + 1}\n0000000000 65535 f \n")
    for (id in 1..last) {
        val offset = offsets[id] ?: 0
        body.append(String.format("%010d 00000 n \n", offset))
    }
    body.append("trailer\n<< /Size ${last + 1} /Root 1 0 R >>\nstartxref\n$xrefStart\n%%EOF\n")

    return body.toString().toByteArray(Charsets.ISO_8859_1)
}
