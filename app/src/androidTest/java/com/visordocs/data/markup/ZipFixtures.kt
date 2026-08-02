package com.visordocs.data.markup

import com.visordocs.data.zip.ZipPackage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Construye paquetes ZIP en memoria para las pruebas.
 *
 * Se generan aqui en lugar de guardarlos como assets binarios: asi el contenido exacto
 * que se esta probando queda a la vista en el propio test, y un caso nuevo es una cadena
 * mas, no un archivo que hay que abrir con otra herramienta para saber que contiene.
 */
internal fun zipPackage(
    keep: (String) -> Boolean,
    vararg entries: Pair<String, String>,
): ZipPackage {
    val out = ByteArrayOutputStream()
    ZipOutputStream(out).use { zip ->
        for ((name, content) in entries) {
            zip.putNextEntry(ZipEntry(name))
            zip.write(content.toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
    }
    return ZipPackage.read(ByteArrayInputStream(out.toByteArray()), keep)
}

internal val TEST_LABELS = MarkupLabels(sheet = "Hoja", slide = "Diapositiva")

internal const val ODF_NS =
    "xmlns:office=\"urn:oasis:names:tc:opendocument:xmlns:office:1.0\" " +
        "xmlns:text=\"urn:oasis:names:tc:opendocument:xmlns:text:1.0\" " +
        "xmlns:table=\"urn:oasis:names:tc:opendocument:xmlns:table:1.0\" " +
        "xmlns:draw=\"urn:oasis:names:tc:opendocument:xmlns:drawing:1.0\" " +
        "xmlns:style=\"urn:oasis:names:tc:opendocument:xmlns:style:1.0\" " +
        "xmlns:fo=\"urn:oasis:names:tc:opendocument:xmlns:xsl-fo-compatible:1.0\" " +
        "xmlns:presentation=\"urn:oasis:names:tc:opendocument:xmlns:presentation:1.0\""
