package no.nav.tsm.sykmelding.template

import no.nav.tsm.sykmelding.utils.grayscale
import no.nav.tsm.sykmelding.utils.loadOpenCv
import no.nav.tsm.sykmelding.utils.toBufferedImage
import no.nav.tsm.sykmelding.utils.use
import org.apache.pdfbox.Loader
import org.apache.pdfbox.pdmodel.PDDocument
import org.opencv.core.Core.BORDER_CONSTANT
import org.opencv.core.Core.bitwise_and
import org.opencv.core.Core.bitwise_or
import org.opencv.core.Core.countNonZero
import org.opencv.core.DMatch
import org.opencv.core.KeyPoint
import org.opencv.core.Mat
import org.opencv.core.MatOfDMatch
import org.opencv.core.MatOfKeyPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.features.BFMatcher
import org.opencv.geometry.Geometry.findHomography
import org.opencv.imgproc.Imgproc.INTER_AREA
import org.opencv.imgproc.Imgproc.INTER_LINEAR
import org.opencv.imgproc.Imgproc.MORPH_OPEN
import org.opencv.imgproc.Imgproc.MORPH_RECT
import org.opencv.imgproc.Imgproc.THRESH_BINARY_INV
import org.opencv.imgproc.Imgproc.THRESH_OTSU
import org.opencv.imgproc.Imgproc.dilate
import org.opencv.imgproc.Imgproc.getStructuringElement
import org.opencv.imgproc.Imgproc.morphologyEx
import org.opencv.imgproc.Imgproc.resize
import org.opencv.imgproc.Imgproc.threshold
import org.opencv.imgproc.Imgproc.warpPerspective
import tools.jackson.module.kotlin.jacksonObjectMapper
import tools.jackson.module.kotlin.readValue
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

sealed interface TemplateMatchResult {
    /**
     * [templateMatch]: for each page of [template], the PDF page aligned to it — gray, at the template image's size,
     * warped with the matching homography — so the template's field coordinates apply to it directly for OCR.
     */
    data class Matched(val template: TemplateGroup,
                       val templateMatch: Map<Template, BufferedImage>
    ) : TemplateMatchResult
    data object NoMatch : TemplateMatchResult
    data class Error(val exception: Exception) : TemplateMatchResult
    data class TooManyPages(val pages: Int, val maxPages: Int) : TemplateMatchResult
}

class LoadedTemplate(
    val template: Template,
    val width: Int,
    val height: Int,
    /** In template image pixels (400 DPI), though found at [TemplateMatcher.featureDpi] with its methods' detector. */
    val keypoints: List<KeyPoint>,
    val descriptors: Mat,
    val lines: Mat,
) : AutoCloseable {
    override fun close() {
        descriptors.release(); lines.release()
    }
}

/**
 * Matches PDFs to [templates], by default every template in [library]: those packaged in this library's resources.
 *
 * Finds keypoints on the pages and the templates (at [featureDpi]) with [methods]' detector, matches their descriptors
 * by brute force and keeps the good matches with its filter, and fits a homography with its method. The form lines
 * must then line up. Production's methods ([MatchingMethods.PRODUCTION]) are SIFT, Lowe's ratio test and MAGSAC++,
 * which OpenCV 5 keeps in its main modules; the others are there to compare them with.
 *
 * The PDF's pages are rendered at [renderDpi]; the templates are always [PdfTemplateImages.DPI]. Features are found
 * on both scaled to [featureDpi], which can be no more than either. Whatever [renderDpi] is, a matched page is aligned
 * to the template image's size, so its field coordinates apply to it.
 *
 * A match agrees with the homography when it lies within [tolerance] template pixels of where the homography puts it;
 * given as null, [reprojectionTolerance] of [featureDpi], as production uses.
 */
class TemplateMatcher(
    library: TemplateLibrary = TemplateLibrary.fromResources(),
    private val maxPages: Int = DEFAULT_MAX_PAGES,
    private val templates: List<TemplateGroup> = library.list(),
    val renderDpi: Int = PdfTemplateImages.DPI,
    val featureDpi: Int = DEFAULT_FEATURE_DPI,
    val methods: MatchingMethods = MatchingMethods.PRODUCTION,
    tolerance: Double? = null,
) : AutoCloseable {
    init {
        require(renderDpi > 0) { "renderDpi must be positive: $renderDpi" }
        require(featureDpi > 0) { "featureDpi must be positive: $featureDpi" }
        require(featureDpi <= renderDpi) { "featureDpi ($featureDpi) can be no more than renderDpi ($renderDpi)" }
        require(featureDpi <= PdfTemplateImages.DPI) { "featureDpi ($featureDpi) can be no more than the templates' ${PdfTemplateImages.DPI} DPI" }
        require(tolerance == null || (tolerance > 0 && tolerance.isFinite())) { "tolerance must be positive: $tolerance" }
        loadOpenCv()
    }

    /**
     * How far, in template pixels (400 DPI), a match may be from where the homography puts it and still agree with it
     * (be an inlier): the tolerance given, or [reprojectionTolerance] of [featureDpi].
     */
    val tolerance: Double = tolerance ?: reprojectionTolerance(featureDpi)
    private val objectMapper = jacksonObjectMapper()
    private val loaded: Map<TemplateGroup, List<LoadedTemplate>> = templates.associateWith { group -> group.pageFiles.map { load(it)} }

    fun matchPdf(pdf: File): TemplateMatchResult = matchDocument { Loader.loadPDF(pdf) }

    /** As [matchPdf], for a PDF in memory; PDFBox reads it without temporary files. */
    fun matchPdf(pdf: ByteArray): TemplateMatchResult = matchDocument { Loader.loadPDF(pdf) }

    override fun close() {
        loaded.values.flatten().forEach { it.close() }
    }

    private fun matchDocument(open: () -> PDDocument): TemplateMatchResult = try {
        match(open)
    } catch (e: Exception) {
        TemplateMatchResult.Error(e)
    }

    private fun match(open: () -> PDDocument): TemplateMatchResult {
        val pages = open().use { document ->
            if (document.numberOfPages > maxPages) return TemplateMatchResult.TooManyPages(document.numberOfPages, maxPages)

            pdfToImages(document)
        }

        val prepared = pages.map { prepare(it) }
        try {
            for (template in templates) {
                val matched = matchesTemplate(template, prepared)
                if (matched is TemplateMatchResult.Matched) return matched
            }
            return TemplateMatchResult.NoMatch
        } finally {
            prepared.forEach { it.close() }
        }
    }

    private fun matchesTemplate(template: TemplateGroup, pages: List<PreparedPage>): TemplateMatchResult {
        val templateMatch = loaded.getValue(template).associate { templatePage ->
            val aligned = pages.firstNotNullOfOrNull { page -> matchesPage(page, templatePage) } ?: return TemplateMatchResult.NoMatch
            templatePage.template to aligned
        }

        return TemplateMatchResult.Matched(template, templateMatch)
    }

    /** The PDF [page] aligned to [template] (gray, template size) if it is the same form page; null if not. */
    private fun matchesPage(page: PreparedPage, template: LoadedTemplate): BufferedImage? {
        if (page.descriptors.empty() || template.descriptors.empty()) return null

        // PROSAC and RHO try the closest matches first, so they get them sorted; the others as they come, as before.
        val matches = goodMatches(page, template).let { found -> if (methods.homography.sortsMatches) found.sortedBy { it.distance } else found }
        if (matches.size < MIN_MATCHES) return null

        val (homography, inliers) = Mat().use { inlierMask ->
            val found = pointMat(matches.map { page.keypoints[it.trainIdx].pt }).use { from ->
                pointMat(matches.map { template.keypoints[it.queryIdx].pt }).use { to ->
                    findHomography(from, to, methods.homography.flag, tolerance, inlierMask, 2000, 0.99)
                }
            }
            found to if (found.empty()) 0 else countNonZero(inlierMask)
        }
        return homography.use {
            if (homography.empty() || inliers < MIN_INLIERS || inliers < matches.size * MIN_AGREEMENT) return null
            Mat().use { aligned ->
                warpPerspective(page.gray, aligned, homography, Size(template.width.toDouble(), template.height.toDouble()),
                    INTER_LINEAR, BORDER_CONSTANT, Scalar(255.0))
                // Copied into Java memory here, before the Mat is freed.
                if (lineFit(template.lines, aligned) >= MIN_LINE_FIT) toBufferedImage(aligned) else null
            }
        }
    }

    /** The matches of [template]'s descriptors (query) to [page]'s (train) that [methods]' filter keeps. */
    private fun goodMatches(page: PreparedPage, template: LoadedTemplate): List<DMatch> {
        val norm = methods.detector.norm
        return when (methods.filter) {
            // Keep a match only when it is clearly closer than the second best, which drops the ambiguous ones that
            // repeated letters and boxes give.
            MatchFilter.RATIO_TEST -> {
                val candidates = mutableListOf<MatOfDMatch>()
                BFMatcher(norm, false).knnMatch(template.descriptors, page.descriptors, candidates, 2)
                candidates.map { pair -> pair.use { it.toArray() } }
                    .filter { it.size == 2 && it[0].distance < RATIO * it[1].distance }
                    .map { it[0] }
            }
            MatchFilter.CROSS_CHECK -> MatOfDMatch().use { BFMatcher(norm, true).match(template.descriptors, page.descriptors, it); it.toList() }
        }
    }

    private class PreparedPage(val gray: Mat, val keypoints: List<KeyPoint>, val descriptors: Mat) : AutoCloseable {
        override fun close() {
            gray.release(); descriptors.release()
        }
    }

    private fun prepare(image: BufferedImage): PreparedPage {
        val gray = grayscale(image)
        val descriptors = Mat()
        val keypoints = detectFeatures(gray, renderDpi, descriptors)
        return PreparedPage(gray, keypoints, descriptors)
    }

    private fun load(file: File): LoadedTemplate {
        val template = objectMapper.readValue<Template>(file.readText())
        val folder = File(file.parent)
        val image = ImageIO.read(folder.resolve(template.templateBilde))
        val descriptors = Mat()
        return grayscale(image).use { gray ->
            val keypoints = detectFeatures(gray, PdfTemplateImages.DPI, descriptors)
            LoadedTemplate(template, image.width, image.height, keypoints, descriptors, formLines(gray))
        }
    }

    /**
     * [methods]' keypoints of [gray] (at [dpi]), with their [descriptors], found at [featureDpi] and given in [gray]'s
     * pixels. A form's lines and print are plain at that size; at 400 DPI SIFT, which doubles the image first, would
     * take many times the time and gigabytes of memory per page. A detector of its own for each call, as pages are
     * prepared on several threads at once.
     */
    private fun detectFeatures(gray: Mat, dpi: Int, descriptors: Mat): List<KeyPoint> {
        val scale = featureDpi.toDouble() / dpi
        if (scale == 1.0) return detect(gray, descriptors)
        val keypoints = Mat().use { small ->
            resize(gray, small, Size(), scale, scale, INTER_AREA)
            detect(small, descriptors)
        }
        // Pixel centres: pixel i of the small image covers pixels i/scale .. (i+1)/scale of the page.
        for (keypoint in keypoints) {
            keypoint.pt.x = (keypoint.pt.x + 0.5) / scale - 0.5
            keypoint.pt.y = (keypoint.pt.y + 0.5) / scale - 0.5
            keypoint.size = (keypoint.size / scale).toFloat()
        }
        return keypoints
    }

    private fun detect(image: Mat, descriptors: Mat): List<KeyPoint> = MatOfKeyPoint().use { keypoints ->
        Mat().use { mask -> methods.detector.create().detectAndCompute(image, mask, keypoints, descriptors) }
        keypoints.toList()
    }

    private fun pointMat(points: List<Point>): MatOfPoint2f = MatOfPoint2f(*points.toTypedArray())

    private fun pdfToImages(document: PDDocument): List<BufferedImage> =
        (1..document.numberOfPages).map { page -> PdfTemplateImages.render(document, page, renderDpi) }

    companion object {
        const val DEFAULT_MAX_PAGES = 5
        /** The resolution features are found at, unless another is given: the pages scaled down to it. */
        const val DEFAULT_FEATURE_DPI = 100
        /** Lowe's ratio: the best match must be closer than this share of the second best. */
        private const val RATIO = 0.75f
        private const val MIN_MATCHES = 8
        private const val MIN_INLIERS = 8
        private const val MIN_AGREEMENT = 0.4
        private const val MIN_LINE_FIT = 0.6
        /**
         * In template pixels (400 DPI). A scanned page is never exactly a homography of the form: paper and scanner
         * bend it by a few pixels, so however finely keypoints are found, a tighter tolerance leaves too few matches
         * agreeing with the homography for even the right page. The matcher before features could be found at other
         * resolutions used this, at 400 DPI.
         */
        private const val MIN_REPROJECTION_ERROR = 3.0

        /**
         * How far, in template pixels, a match may be from where the homography puts it and still agree with it, unless
         * another tolerance is given: one pixel at [featureDpi], where the keypoints are found, but at least
         * [MIN_REPROJECTION_ERROR]. 4 at production's 100 DPI.
         */
        fun reprojectionTolerance(featureDpi: Int): Double =
            maxOf(MIN_REPROJECTION_ERROR, PdfTemplateImages.DPI.toDouble() / featureDpi)
    }
}
private const val LINE_TOLERANCE = 6

internal fun lineFit(templateLines: Mat, alignedGray: Mat): Double {
    val total = countNonZero(templateLines)
    if (total == 0) return 1.0
    return formLines(alignedGray).use { page ->
        Mat().use { near ->
            getStructuringElement(MORPH_RECT, Size(2.0 * LINE_TOLERANCE + 1, 2.0 * LINE_TOLERANCE + 1)).use { dilate(page, near, it) }
            Mat().use { both ->
                bitwise_and(templateLines, near, both)
                countNonZero(both).toDouble() / total
            }
        }
    }
}

internal fun formLines(gray: Mat): Mat {
    val length = maxOf(40, minOf(gray.cols(), gray.rows()) / 40).toDouble()
    return Mat().use { ink ->
        threshold(gray, ink, 0.0, 255.0, THRESH_BINARY_INV or THRESH_OTSU)
        Mat().use { horizontal ->
            Mat().use { vertical ->
                getStructuringElement(MORPH_RECT, Size(length, 1.0)).use { morphologyEx(ink, horizontal, MORPH_OPEN, it) }
                getStructuringElement(MORPH_RECT, Size(1.0, length)).use { morphologyEx(ink, vertical, MORPH_OPEN, it) }
                Mat().also { bitwise_or(horizontal, vertical, it) }
            }
        }
    }
}
