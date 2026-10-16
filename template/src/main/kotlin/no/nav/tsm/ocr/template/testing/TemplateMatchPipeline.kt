package no.nav.tsm.ocr.template.testing

import org.bytedeco.opencv.global.opencv_core.CV_8UC1
import org.bytedeco.opencv.global.opencv_core.bitwise_and
import org.bytedeco.opencv.global.opencv_core.bitwise_or
import org.bytedeco.opencv.global.opencv_core.countNonZero
import org.bytedeco.opencv.global.opencv_imgproc.FILLED
import org.bytedeco.opencv.global.opencv_imgproc.LINE_8
import org.bytedeco.opencv.global.opencv_imgproc.MORPH_OPEN
import org.bytedeco.opencv.global.opencv_imgproc.MORPH_RECT
import org.bytedeco.opencv.global.opencv_imgproc.THRESH_BINARY_INV
import org.bytedeco.opencv.global.opencv_imgproc.THRESH_OTSU
import org.bytedeco.opencv.global.opencv_imgproc.dilate
import org.bytedeco.opencv.global.opencv_imgproc.getStructuringElement
import org.bytedeco.opencv.global.opencv_imgproc.morphologyEx
import org.bytedeco.opencv.global.opencv_imgproc.rectangle
import org.bytedeco.opencv.global.opencv_imgproc.threshold
import org.bytedeco.opencv.opencv_core.Mat
import org.bytedeco.opencv.opencv_core.Rect
import org.bytedeco.opencv.opencv_core.Scalar
import org.bytedeco.opencv.opencv_core.Size
import org.example.no.nav.tsm.ocr.template.template.PdfTemplateImages
import org.example.no.nav.tsm.ocr.template.template.Template
import java.awt.Color
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.awt.image.DataBufferByte
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import javax.imageio.ImageIO
import kotlin.math.roundToInt

internal data class BatchSettings(
    val detector: FeatureDetectorKind = FeatureDetectorKind.AKAZE,
    val maxPoints: Int = 20_000,
    val model: AlignmentModel = AlignmentModel.HOMOGRAPHY,
    val minLineFit: Double = DEFAULT_MIN_LINE_FIT,
) {
    val features: FeatureSettings get() = FeatureSettings(detector, maxPoints = maxPoints)
}

internal const val DEFAULT_MIN_LINE_FIT = 0.6

/** About 0.4 mm at 400 DPI. */
internal const val LINE_TOLERANCE = 6

internal enum class BatchStatus(val label: String) {
    MATCH("✓ Match"), NO_MATCH("✗ No match"), ERROR("Error");

    override fun toString() = label
}

/** One tested page. [inliers] of [matches] agreed on one transform (0 of 0 when unknown). */
internal data class PageOutcome(
    val page: Int,
    val status: BatchStatus,
    val message: String,
    val inliers: Int = 0,
    val matches: Int = 0,
    /** Share of the template's form lines found on the aligned page; null when the page was not aligned. */
    val lineFit: Double? = null,
) {
    val agreement: Double? get() = if (matches > 0) inliers.toDouble() / matches else null
}

/**
 * One file's outcome. [page] is the page shown in [preview]: the matching page, or else the page that came closest.
 * [preview] is a PNG: when [aligned], the aligned page (black) with the template's ink in red on top, [previewScale] ×
 * template pixels; otherwise the page at 400 DPI, [previewScale] × its pixels. [outcomes] lists every tested page.
 */
internal class BatchResult(
    val file: File,
    val status: BatchStatus,
    val message: String = "",
    val page: Int? = null,
    val inliers: Int = 0,
    val matches: Int = 0,
    val transform: DoubleArray? = null,
    val preview: ByteArray? = null,
    val previewScale: Double = 1.0,
    val millis: Long = 0,
    val outcomes: List<PageOutcome> = emptyList(),
    val lineFit: Double? = null,
    val aligned: Boolean = false,
) {
    val agreement: Double? get() = if (matches > 0) inliers.toDouble() / matches else null

    fun previewImage(): BufferedImage? = preview?.let { ImageIO.read(ByteArrayInputStream(it)) }
}

/**
 * Computed once per run and shared read-only by parallel workers: the template's features (detected once instead of
 * for every page), its form lines, and the template drawn at preview size. Close it when the run is over.
 */
internal class TemplateCache(reference: BufferedImage, settings: FeatureSettings) : AutoCloseable {
    val features: FeatureSet = detectPageFeatures(reference, settings)
    val lines: Mat = templateLines(reference)
    val previewScale: Double = previewScale(reference.width)
    val preview: BufferedImage = resized(reference, previewScale)

    override fun close() { features.close(); lines.close() }
}

/** A document page at 400 DPI and its features, shared read-only by every template page tested against it. */
internal class PreparedPage(val image: BufferedImage, val features: FeatureSet) : AutoCloseable {
    override fun close() = features.close()

    companion object {
        /** As in "Test template": images use their DPI metadata (400 when missing); PDFs are rendered at 400 DPI. */
        fun load(file: File, page: Int, settings: FeatureSettings): PreparedPage {
            val input = TestInput.load(file, page)
            val image = input.at400Dpi(if (input.isPdf) 400.0 else input.dpi ?: 400.0)
            return PreparedPage(image, detectPageFeatures(image, settings))
        }
    }
}

/**
 * One file's pages prepared for alignment: each page is rendered and its features detected at most once, however
 * many template pages it is tested against. Safe to use from several threads. [release] a page once no test uses
 * it any more; [close] releases the rest.
 */
internal class DocumentPages(private val file: File, private val settings: FeatureSettings) : AutoCloseable {
    private val pages = ConcurrentHashMap<Int, Lazy<PreparedPage>>()

    /** Throws when the page cannot be read; the next call tries again. */
    fun get(page: Int): PreparedPage = pages.computeIfAbsent(page) { lazy { PreparedPage.load(file, page, settings) } }.value

    fun release(page: Int) {
        pages.remove(page)?.takeIf { it.isInitialized() }?.value?.close()
    }

    override fun close() = pages.keys.toList().forEach(::release)
}

internal fun pageCount(file: File): Int = if (file.extension.equals("pdf", true)) PdfTemplateImages.pageCount(file) else 1

/**
 * Tests one page against a template. Safe to call from several threads with the same [cache], [pages] and [texts]. Pass
 * the same [pages] and [texts], both [file]'s, for one file tested against several templates, so each page is rendered,
 * has its features detected and is read for its required text once.
 */
internal fun testTemplatePage(template: Template, reference: BufferedImage, file: File, page: Int, settings: BatchSettings,
                              pages: DocumentPages, cache: TemplateCache? = null): BatchResult {
    val started = System.nanoTime()
    fun elapsed() = (System.nanoTime() - started) / 1_000_000
    val tested = testPage(template, reference, page, settings, pages, cache)
    return BatchResult(
        file = file,
        status = tested.outcome.status,
        message = tested.outcome.message,
        page = page,
        inliers = tested.outcome.inliers,
        matches = tested.outcome.matches,
        transform = tested.transform,
        preview = tested.preview,
        previewScale = tested.previewScale,
        millis = elapsed(),
        outcomes = listOf(tested.outcome),
        lineFit = tested.outcome.lineFit,
        aligned = tested.aligned,
    )
}

private class PageTest(val outcome: PageOutcome, val transform: DoubleArray? = null, val preview: ByteArray? = null,
                       val previewScale: Double = 1.0, val aligned: Boolean = false)

/** Aligns [page] to the template, unless [text] says it lacks the template's required text. */
private fun testPage(template: Template, reference: BufferedImage, page: Int, settings: BatchSettings, pages: DocumentPages,
                     cache: TemplateCache?): PageTest = try {
    alignPage(template, reference, pages.get(page), page, settings, cache).let {
       PageTest(it.outcome.copy(message = "${it.outcome.message}}"), it.transform, it.preview,
            it.previewScale, it.aligned)
    }
} catch (error: Exception) {
    PageTest(PageOutcome(page, BatchStatus.ERROR, error.message ?: error.toString()))
}

private fun alignPage(template: Template, reference: BufferedImage, prepared: PreparedPage, page: Int, settings: BatchSettings,
                      cache: TemplateCache?): PageTest = TemplateTestSession(template, reference, prepared).use { session ->
    session.detect(settings.features, cache?.features)
    try {
        session.match(settings.model)
    } catch (rejected: IllegalStateException) {
        val scale = previewScale(session.scaled.width)
        val counts = rejected as? AlignmentRejected
        return@use PageTest(PageOutcome(page, BatchStatus.NO_MATCH, rejected.message ?: "Alignment rejected.",
            counts?.inliers ?: 0, counts?.matches ?: 0), preview = png(resized(session.scaled, scale)), previewScale = scale)
    }
    checkAligned(session, reference, settings, cache, page)
}

private fun checkAligned(session: TemplateTestSession, reference: BufferedImage, settings: BatchSettings, cache: TemplateCache?, page: Int): PageTest {
    val inspection = requireNotNull(session.inspection)
    session.align()
    val aligned = requireNotNull(session.aligned)
    val fit = cache?.let { lineFit(it.lines, aligned) } ?: templateLines(reference).use { lineFit(it, aligned) }
    val scale = cache?.previewScale ?: previewScale(reference.width)
    val templatePreview = cache?.preview ?: resized(reference, scale)
    val preview = png(alignmentPreview(templatePreview, aligned, scale))
    val fits = fit >= settings.minLineFit
    val message = if (fits) inspection.description else ("The features agree (${inspection.inliers} / ${inspection.matches.size}), but the " +
        "form lines do not line up: only %.0f %% of the template's lines are on the aligned page (at least %.0f %% needed).")
        .format(100 * fit, 100 * settings.minLineFit)
    return PageTest(PageOutcome(page, if (fits) BatchStatus.MATCH else BatchStatus.NO_MATCH, message, inspection.inliers, inspection.matches.size, fit),
        inspection.transform, preview, scale, aligned = true)
}

/** Previews are at most this wide: half the width of a 400-DPI A4 page, so 1 preview pixel ≈ 2 page pixels. */
internal const val PREVIEW_WIDTH = 1600

private fun previewScale(width: Int) = minOf(1.0, PREVIEW_WIDTH.toDouble() / width)

/**
 * The aligned page in black and grey with the template's ink ([templatePreview], already at preview size) painted
 * red on top: where they coincide the lines are dark red, and doubled red/black lines show misalignment.
 */
internal fun alignmentPreview(templatePreview: BufferedImage, aligned: BufferedImage, scale: Double): BufferedImage {
    val page = resized(aligned, scale)
    for (y in 0 until minOf(page.height, templatePreview.height)) for (x in 0 until minOf(page.width, templatePreview.width)) {
        val ink = templatePreview.getRGB(x, y).let { ((it shr 16 and 255) * 299 + (it shr 8 and 255) * 587 + (it and 255) * 114) / 1000 }
        if (ink >= 160) continue
        // Darker template ink is painted more strongly.
        val alpha = 0.75 * (160 - ink) / 160.0
        val pixel = page.getRGB(x, y)
        fun mix(channel: Int, target: Int) = (channel * (1 - alpha) + target * alpha).roundToInt()
        page.setRGB(x, y, (mix(pixel shr 16 and 255, 230) shl 16) or (mix(pixel shr 8 and 255, 20) shl 8) or mix(pixel and 255, 20))
    }
    return page
}

private fun resized(image: BufferedImage, scale: Double): BufferedImage {
    val width = (image.width * scale).roundToInt().coerceAtLeast(1)
    val height = (image.height * scale).roundToInt().coerceAtLeast(1)
    return BufferedImage(width, height, BufferedImage.TYPE_INT_RGB).apply {
        createGraphics().let { g ->
            try {
                g.color = Color.WHITE
                g.fillRect(0, 0, width, height)
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
                g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
                g.drawImage(image, 0, 0, width, height, null)
            } finally { g.dispose() }
        }
    }
}

/**
 * The template's ruled form lines: long horizontal and vertical strokes of ink. The caller owns the result.
 */
internal fun templateLines(reference: BufferedImage): Mat = formLines(reference)

/**
 * Share of [templateLines] pixels that have a line pixel of the aligned page within [LINE_TOLERANCE] pixels:
 * close to 1 when the form lines coincide, low when the page is aligned wrongly or is another form.
 */
internal fun lineFit(templateLines: Mat, aligned: BufferedImage): Double = grayMat(aligned).use { lineFit(templateLines, it) }

/** As [lineFit], for the aligned page as an 8-bit gray Mat, e.g. straight from warpPerspective. */
internal fun lineFit(templateLines: Mat, alignedGray: Mat): Double {
    val total = countNonZero(templateLines)
    if (total == 0) return 1.0
    formLines(alignedGray).use { page ->
        Mat().use { near ->
            getStructuringElement(MORPH_RECT, Size(2 * LINE_TOLERANCE + 1, 2 * LINE_TOLERANCE + 1)).use { dilate(page, near, it) }
            Mat().use { both ->
                bitwise_and(templateLines, near, both)
                return countNonZero(both).toDouble() / total
            }
        }
    }
}

private fun formLines(image: BufferedImage, exclusions: List<Rectangle> = emptyList()): Mat =
    grayMat(image).use { formLines(it, exclusions) }

/** Ink (Otsu) opened with long thin kernels: strokes at least about 5 mm long at 400 DPI, so text drops out. [gray] is 8-bit, one channel. */
private fun formLines(gray: Mat, exclusions: List<Rectangle> = emptyList()): Mat {
    val length = maxOf(40, minOf(gray.cols(), gray.rows()) / 40)
    Mat().use { ink ->
        threshold(gray, ink, 0.0, 255.0, THRESH_BINARY_INV or THRESH_OTSU)
        Scalar(0.0).use { black ->
            exclusions.forEach { region -> Rect(region.x, region.y, region.width, region.height).use { rectangle(ink, it, black, FILLED, LINE_8, 0) } }
        }
        Mat().use { horizontal ->
            Mat().use { vertical ->
                getStructuringElement(MORPH_RECT, Size(length, 1)).use { morphologyEx(ink, horizontal, MORPH_OPEN, it) }
                getStructuringElement(MORPH_RECT, Size(1, length)).use { morphologyEx(ink, vertical, MORPH_OPEN, it) }
                return Mat().also { bitwise_or(horizontal, vertical, it) }
            }
        }
    }
}

/** [image] as an 8-bit gray Mat, drawn on white so transparency does not count as ink. The caller owns the result. */
private fun grayMat(image: BufferedImage): Mat {
    val gray = BufferedImage(image.width, image.height, BufferedImage.TYPE_BYTE_GRAY).also { copy ->
        copy.createGraphics().let { g -> try { g.color = Color.WHITE; g.fillRect(0, 0, copy.width, copy.height); g.drawImage(image, 0, 0, null) } finally { g.dispose() } }
    }
    val bytes = (gray.raster.dataBuffer as DataBufferByte).data
    return Mat(gray.height, gray.width, CV_8UC1).also { it.data().put(bytes, 0, bytes.size) }
}

private fun png(image: BufferedImage): ByteArray = ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()

/** [fields] in preview pixels. */
internal fun previewBoxes(fields: List<Rectangle>, scale: Double): List<Rectangle> = fields.map {
    Rectangle((it.x * scale).roundToInt(), (it.y * scale).roundToInt(), (it.width * scale).roundToInt(), (it.height * scale).roundToInt())
}

/**
 * Default number of page/template tests at once: half the cores, one per 8 GB of memory, at most 4. AKAZE already
 * spreads one page over several cores, and each run on a 400-DPI page needs a few hundred MB of heap and native memory.
 */
internal val DEFAULT_PARALLEL: Int = run {
    val memoryGb = (java.lang.management.ManagementFactory.getOperatingSystemMXBean() as? com.sun.management.OperatingSystemMXBean)
        ?.totalMemorySize?.div(1L shl 30) ?: 8
    minOf(Runtime.getRuntime().availableProcessors() / 2, (memoryGb / 8).toInt(), 4).coerceAtLeast(1)
}
