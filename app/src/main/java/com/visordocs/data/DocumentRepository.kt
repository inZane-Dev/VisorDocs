package com.visordocs.data

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import com.visordocs.data.markup.CsvMarkup
import com.visordocs.data.markup.DocxMarkup
import com.visordocs.data.markup.EpubMarkup
import com.visordocs.data.markup.Markup
import com.visordocs.data.markup.MarkupLabels
import com.visordocs.data.markup.OdpMarkup
import com.visordocs.data.markup.OdsMarkup
import com.visordocs.data.markup.OdtMarkup
import com.visordocs.data.markup.PptxMarkup
import com.visordocs.data.markup.RtfMarkup
import com.visordocs.data.markup.SvgMarkup
import com.visordocs.data.markup.XlsxMarkup
import com.visordocs.data.pdf.PdfMerger
import com.visordocs.data.source.ImageDecoder
import com.visordocs.data.source.TextFileReader
import com.visordocs.data.zip.ZipPackage
import com.visordocs.data.zip.ZipParts
import com.visordocs.model.DocumentContent
import com.visordocs.model.DocumentSource
import com.visordocs.model.DocumentType
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * Unico punto de la app que habla con el [ContentResolver].
 *
 * Todo lo demas trabaja con [DocumentSource] y [DocumentContent], que son datos
 * normales. Eso mantiene el resto del codigo libre de Android y hace que anadir un
 * formato sea tocar aqui, el detector y un convertidor: la interfaz no cambia mientras
 * el formato acabe en una de las formas de pintar que ya existen.
 *
 * Las dos operaciones son `suspend` y saltan a [Dispatchers.IO]: abrir un ZIP, recorrer
 * XML o decodificar una imagen tarda demasiado para el hilo principal.
 */
class DocumentRepository(
    private val resolver: ContentResolver,
    /** Carpeta de trabajo para operaciones que no caben en memoria, como unir PDF. */
    private val cacheDir: File,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    /**
     * Conserva el acceso al documento actual y suelta el de los anteriores.
     *
     * Hace falta para que el documento se pueda reabrir si el sistema mata el proceso en
     * segundo plano: sin un permiso persistente, al volver el `content://` ya no seria
     * legible y el visor solo podria mostrar un error.
     *
     * La clave esta en soltar los demas. Pedir el permiso y no liberarlo nunca —que es lo
     * que hacia antes— acumula una concesion por cada archivo abierto, y el sistema
     * limita cuantas puede tener una app: al pasarse, lanza excepcion. Asi se mantiene
     * siempre en una.
     *
     * Muchos URI no admiten permiso persistente (los que llegan por "Abrir con" desde
     * mensajeria, por ejemplo). En esos casos falla en silencio, que es lo correcto: el
     * documento se abre igual, solo que no sobrevivira a la muerte del proceso.
     */
    fun retainAccess(uri: Uri) {
        runCatching {
            resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching {
            resolver.persistedUriPermissions
                .filter { it.uri != uri }
                .forEach { held ->
                    runCatching {
                        resolver.releasePersistableUriPermission(
                            held.uri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION,
                        )
                    }
                }
        }
    }

    /**
     * Solo el nombre visible, sin abrir el archivo ni mirar su contenido.
     *
     * Lo usa la cola de union para poder listar lo que se va a unir. Identificar el
     * formato de cada documento ahi seria trabajo tirado: ya se sabe que son PDF porque
     * el selector solo ofrecio PDF.
     */
    suspend fun displayName(uri: Uri, fallbackName: String): String = withContext(dispatcher) {
        queryNameAndSize(uri).first ?: uri.lastPathSegment ?: fallbackName
    }

    /** Averigua nombre, tamano y formato. Nunca falla: en el peor caso, tipo desconocido. */
    suspend fun resolve(
        uri: Uri,
        mimeHint: String?,
        fallbackName: String,
    ): DocumentSource = withContext(dispatcher) {
        val (displayName, sizeBytes) = queryNameAndSize(uri)
        val resolvedName = displayName ?: uri.lastPathSegment ?: fallbackName

        DocumentSource(
            uri = uri,
            displayName = resolvedName,
            sizeBytes = sizeBytes,
            type = runCatching {
                DocumentTypeDetector.detect(resolver, uri, mimeHint, resolvedName)
            }.getOrDefault(DocumentType.UNKNOWN),
        )
    }

    /**
     * Carga el contenido. Lanza excepcion si el archivo esta danado o no se puede leer;
     * los casos previstos (formato sin visor, documento vacio) se devuelven como
     * variantes de [DocumentContent], no como error.
     */
    suspend fun load(
        source: DocumentSource,
        labels: MarkupLabels,
    ): DocumentContent = withContext(dispatcher) {
        val uri = source.uri

        when (source.type) {
            DocumentType.PDF -> DocumentContent.Pdf(uri)

            // OOXML.
            DocumentType.WORD -> DocxMarkup.convert(ooxml(uri)).toContent()
            DocumentType.EXCEL -> XlsxMarkup.convert(ooxml(uri), labels).toContent()
            DocumentType.POWERPOINT -> PptxMarkup.convert(ooxml(uri), labels).toContent()

            // OpenDocument.
            DocumentType.ODT -> OdtMarkup.convert(odf(uri)).toContent()
            DocumentType.ODS -> OdsMarkup.convert(odf(uri), labels).toContent()
            DocumentType.ODP -> OdpMarkup.convert(odf(uri), labels).toContent()

            DocumentType.EPUB -> EpubMarkup.convert(epub(uri)).toContent()

            // RTF se lee en Latin-1, no en UTF-8: es un formato de 8 bits.
            DocumentType.RTF -> RtfMarkup.convert(
                TextFileReader.read(open(uri), Charsets.ISO_8859_1).text,
            ).toContent()

            DocumentType.SVG -> SvgMarkup.convert(readText(uri).text).toContent()

            // El HTML se entrega tal cual, con sus propios estilos: la gracia de abrir
            // una pagina guardada es verla como pagina, no como codigo fuente. No se
            // sanea porque quien lo contiene es el WebView, que no ejecuta JavaScript ni
            // deja salir ninguna peticion de red.
            DocumentType.HTML -> {
                val loaded = readText(uri)
                if (loaded.text.isBlank()) {
                    DocumentContent.Empty
                } else {
                    DocumentContent.WebPage(loaded.text)
                }
            }

            DocumentType.CSV -> {
                val loaded = readText(uri)
                CsvMarkup.convert(loaded.text, loaded.truncated).toContent()
            }

            DocumentType.PLAIN_TEXT -> {
                val loaded = readText(uri)
                if (loaded.text.isEmpty()) {
                    DocumentContent.Empty
                } else {
                    DocumentContent.PlainText(loaded.text, loaded.truncated)
                }
            }

            DocumentType.IMAGE -> DocumentContent.Picture(ImageDecoder.decode(resolver, uri))

            DocumentType.UNKNOWN -> DocumentContent.Unrecognized

            DocumentType.WORD_LEGACY,
            DocumentType.EXCEL_LEGACY,
            DocumentType.POWERPOINT_LEGACY,
            -> DocumentContent.Unsupported
        }
    }

    /**
     * Une varios PDF en [target], en el orden recibido.
     *
     * Si algo falla a mitad, el archivo de destino queda incompleto. No se intenta
     * deshacer: lo creo el usuario con el selector del sistema y borrarlo por nuestra
     * cuenta seria tocar algo que no nos pertenece. El error se propaga para que la
     * interfaz lo cuente.
     */
    suspend fun mergePdfs(sources: List<Uri>, target: Uri): Unit = withContext(dispatcher) {
        val streams = sources.map { open(it) }
        try {
            val out = resolver.openOutputStream(target)
                ?: throw IOException("No se pudo escribir en el destino")
            out.use { PdfMerger.merge(streams, it, cacheDir) }
        } finally {
            // PDFBox cierra los flujos que consume, pero si fallo antes de llegar a
            // usarlos quedarian abiertos.
            streams.forEach { runCatching { it.close() } }
        }
    }

    /**
     * Nombre y tamano segun el proveedor. Devuelve nulos en lugar de fallar: hay
     * proveedores que no responden a esta consulta, y un documento sin nombre se abre
     * igual de bien.
     */
    private fun queryNameAndSize(uri: Uri): Pair<String?, Long?> {
        var displayName: String? = null
        var sizeBytes: Long? = null

        // Los content:// exponen nombre y tamano via OpenableColumns.
        runCatching {
            resolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                null,
                null,
                null,
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIndex >= 0 && !cursor.isNull(nameIndex)) {
                        displayName = cursor.getString(nameIndex)
                    }
                    val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) {
                        sizeBytes = cursor.getLong(sizeIndex)
                    }
                }
            }
        }

        // Los file:// no responden a esa consulta; se lee del propio path.
        if (displayName == null && uri.scheme == "file") {
            uri.path?.let { path ->
                val file = File(path)
                displayName = file.name
                if (file.exists()) sizeBytes = file.length()
            }
        }

        return displayName to sizeBytes
    }

    private fun open(uri: Uri) =
        resolver.openInputStream(uri) ?: throw IOException("No se pudo abrir el archivo")

    private fun readText(uri: Uri) = TextFileReader.read(open(uri))

    private fun ooxml(uri: Uri) = ZipPackage.read(open(uri), ZipParts.Ooxml)
    private fun odf(uri: Uri) = ZipPackage.read(open(uri), ZipParts.Odf)
    private fun epub(uri: Uri) = ZipPackage.read(open(uri), ZipParts.Epub)

    private fun Markup.toContent(): DocumentContent =
        if (isEmpty) DocumentContent.Empty else DocumentContent.Markup(body, truncated)
}
