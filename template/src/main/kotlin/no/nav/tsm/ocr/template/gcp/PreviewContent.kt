package no.nav.tsm.ocr.template.gcp

import org.apache.pdfbox.Loader
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject
import org.apache.pdfbox.rendering.ImageType
import org.apache.pdfbox.rendering.PDFRenderer
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.time.Instant
import javax.imageio.ImageIO
import kotlin.math.roundToInt

/** What the preview shows for a downloaded file. Built off the EDT. */
sealed interface PreviewContent {
    class Pdf(val info: PdfInfo, val firstPage: BufferedImage) : PreviewContent
    class Image(val image: BufferedImage) : PreviewContent
    class Text(val text: String, val truncated: Boolean) : PreviewContent
    class Unsupported(val reason: String) : PreviewContent

    companion object {
        /** Screen preview only; template work renders at 400 DPI (see PdfTemplateImages). */
        const val PDF_DPI = 120f
        private const val MAX_TEXT_BYTES = 2 * 1024 * 1024
        private val TEXT_EXTENSIONS = setOf("xml", "json", "txt", "csv", "log")

        fun of(file: BucketFile, bytes: ByteArray): PreviewContent = when {
            isPdf(bytes) -> Pdf(PdfInfo.read(bytes), renderPdfPage(bytes, 0))
            isText(file) -> Text(String(bytes, 0, minOf(bytes.size, MAX_TEXT_BYTES), Charsets.UTF_8), bytes.size > MAX_TEXT_BYTES)
            else -> ImageIO.read(ByteArrayInputStream(bytes))?.let(::Image)
                ?: Unsupported("No preview for ${file.contentType ?: "this file type"}.")
        }

        fun renderPdfPage(bytes: ByteArray, pageIndex: Int, dpi: Float = PDF_DPI): BufferedImage = Loader.loadPDF(bytes).use { document ->
            require(pageIndex in 0 until document.numberOfPages) { "Invalid page ${pageIndex + 1}" }
            PDFRenderer(document).renderImageWithDPI(pageIndex, dpi, ImageType.RGB)
        }

        internal fun isPdf(bytes: ByteArray): Boolean {
            // The header must start within the first 1024 bytes (ISO 32000-1, 7.5.2 / Acrobat implementation note).
            val head = String(bytes, 0, minOf(bytes.size, 1024), Charsets.ISO_8859_1)
            return "%PDF-" in head
        }

        private fun isText(file: BucketFile): Boolean {
            val type = file.contentType.orEmpty().lowercase()
            return file.extension in TEXT_EXTENSIONS || type.startsWith("text/") || type.endsWith("xml") || type.endsWith("json")
        }
    }
}

data class PdfInfo(
    val pages: List<PdfPageInfo>,
    val version: Float,
    val encrypted: Boolean,
    val title: String?,
    val author: String?,
    val creator: String?,
    val producer: String?,
    val created: Instant?,
    val modified: Instant?,
) {
    companion object {
        fun read(bytes: ByteArray): PdfInfo = Loader.loadPDF(bytes).use { document ->
            val information = document.documentInformation
            PdfInfo(
                pages = document.pages.map(PdfPageInfo::of),
                version = document.version,
                encrypted = document.isEncrypted,
                title = information?.title?.takeIf(String::isNotBlank),
                author = information?.author?.takeIf(String::isNotBlank),
                creator = information?.creator?.takeIf(String::isNotBlank),
                producer = information?.producer?.takeIf(String::isNotBlank),
                created = information?.creationDate?.toInstant(),
                modified = information?.modificationDate?.toInstant(),
            )
        }
    }
}

/** Page size in millimetres, and the largest embedded image — for scans, its pixel size and approximate DPI. */
data class PdfPageInfo(val widthMm: Int, val heightMm: Int, val rotation: Int, val scanWidth: Int?, val scanHeight: Int?, val scanDpi: Int?) {
    val description: String
        get() = "$widthMm × $heightMm mm" + (if (rotation != 0) ", rotated $rotation°" else "") +
            (if (scanWidth != null && scanHeight != null) ", image $scanWidth × $scanHeight px" else "") +
            (scanDpi?.let { " ≈ $it DPI" } ?: "")

    companion object {
        private const val MM_PER_POINT = 25.4 / 72

        fun of(page: PDPage): PdfPageInfo {
            val box = page.cropBox
            val image = runCatching {
                val resources = page.resources ?: return@runCatching null
                resources.xObjectNames.mapNotNull { resources.getXObject(it) as? PDImageXObject }.maxByOrNull { it.width.toLong() * it.height }
            }.getOrNull()
            // Assumes the scan fills the page, which holds for scanned forms. Comparing the long sides
            // also holds when the page or the image is drawn rotated.
            val longSideInches = maxOf(box.width, box.height) / 72.0
            return PdfPageInfo(
                widthMm = (box.width * MM_PER_POINT).roundToInt(),
                heightMm = (box.height * MM_PER_POINT).roundToInt(),
                rotation = page.rotation,
                scanWidth = image?.width,
                scanHeight = image?.height,
                scanDpi = image?.takeIf { longSideInches > 0 }?.let { (maxOf(it.width, it.height) / longSideInches).roundToInt() },
            )
        }
    }
}
