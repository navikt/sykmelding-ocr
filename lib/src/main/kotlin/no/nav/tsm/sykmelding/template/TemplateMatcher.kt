package no.nav.tsm.sykmelding.template

import no.nav.tsm.sykmelding.utils.grayscale
import no.nav.tsm.sykmelding.utils.toBufferedImage
import org.apache.pdfbox.Loader
import org.apache.pdfbox.pdmodel.PDDocument
import org.bytedeco.javacpp.PointerScope
import org.bytedeco.javacpp.indexer.FloatIndexer
import org.bytedeco.opencv.global.opencv_calib3d.findHomography
import org.bytedeco.opencv.global.opencv_core.BORDER_CONSTANT
import org.bytedeco.opencv.global.opencv_core.CV_32FC2
import org.bytedeco.opencv.global.opencv_core.bitwise_and
import org.bytedeco.opencv.global.opencv_core.bitwise_or
import org.bytedeco.opencv.global.opencv_core.countNonZero
import org.bytedeco.opencv.global.opencv_imgproc.INTER_AREA
import org.bytedeco.opencv.global.opencv_imgproc.INTER_LINEAR
import org.bytedeco.opencv.global.opencv_imgproc.MORPH_OPEN
import org.bytedeco.opencv.global.opencv_imgproc.MORPH_RECT
import org.bytedeco.opencv.global.opencv_imgproc.THRESH_BINARY_INV
import org.bytedeco.opencv.global.opencv_imgproc.THRESH_OTSU
import org.bytedeco.opencv.global.opencv_imgproc.dilate
import org.bytedeco.opencv.global.opencv_imgproc.getStructuringElement
import org.bytedeco.opencv.global.opencv_imgproc.morphologyEx
import org.bytedeco.opencv.global.opencv_imgproc.resize
import org.bytedeco.opencv.global.opencv_imgproc.threshold
import org.bytedeco.opencv.global.opencv_imgproc.warpPerspective
import org.bytedeco.opencv.global.opencv_xfeatures2d.matchGMS
import org.bytedeco.opencv.opencv_core.DMatch
import org.bytedeco.opencv.opencv_core.DMatchVector
import org.bytedeco.opencv.opencv_core.DMatchVectorVector
import org.bytedeco.opencv.opencv_core.KeyPointVector
import org.bytedeco.opencv.opencv_core.Mat
import org.bytedeco.opencv.opencv_core.Point2f
import org.bytedeco.opencv.opencv_core.Scalar
import org.bytedeco.opencv.opencv_core.Size
import org.bytedeco.opencv.opencv_features2d.BFMatcher
import org.example.no.nav.tsm.ocr.template.template.PdfTemplateImages
import org.example.no.nav.tsm.ocr.template.template.Template
import org.example.no.nav.tsm.ocr.template.template.TemplateGroup
import org.example.no.nav.tsm.ocr.template.template.TemplateLibrary
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
    val keypoints: KeyPointVector,
    val descriptors: Mat,
    val lines: Mat,
) : AutoCloseable {
    override fun close() {
        keypoints.close(); descriptors.close(); lines.close()
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
    private fun matchesPage(page: PreparedPage, template: LoadedTemplate): BufferedImage? = PointerScope().use {
        if (page.descriptors.empty() || template.descriptors.empty()) return null

        // PROSAC and RHO try the closest matches first, so they get them sorted; the others as they come, as before.
        val matches = goodMatches(page, template).let { found -> if (methods.homography.sortsMatches) found.sortedBy { it.distance() } else found }
        if (matches.size < MIN_MATCHES) return null

        val from = pointMat(matches.map { page.keypoints.get(it.trainIdx().toLong()).pt() })
        val to = pointMat(matches.map { template.keypoints.get(it.queryIdx().toLong()).pt() })
        val inlierMask = Mat()
        val homography = findHomography(from, to, methods.homography.flag, tolerance, inlierMask, 2000, 0.99)
        if (homography.empty()) return null
        val inliers = countNonZero(inlierMask)
        if (inliers < MIN_INLIERS || inliers < matches.size * MIN_AGREEMENT) return null

        val aligned = Mat()
        warpPerspective(page.gray, aligned, homography, Size(template.width, template.height), INTER_LINEAR, BORDER_CONSTANT, Scalar(255.0))
        // Copied into Java memory here, before the scope frees the Mat.
        if (lineFit(template.lines, aligned) >= MIN_LINE_FIT) toBufferedImage(aligned) else null
    }

    /**
     * The matches of [template]'s descriptors (query) to [page]'s (train) that [methods]' filter keeps. Called in the
     * caller's pointer scope, which frees what is made here.
     */
    private fun goodMatches(page: PreparedPage, template: LoadedTemplate): List<DMatch> {
        val norm = methods.detector.norm
        return when (methods.filter) {
            // Keep a match only when it is clearly closer than the second best, which drops the ambiguous ones that
            // repeated letters and boxes give.
            MatchFilter.RATIO_TEST -> {
                val candidates = DMatchVectorVector()
                BFMatcher(norm, false).knnMatch(template.descriptors, page.descriptors, candidates, 2)
                (0 until candidates.size()).map { candidates.get(it) }
                    .filter { it.size() == 2L && it.get(0).distance() < RATIO * it.get(1).distance() }
                    .map { it.get(0) }
            }
            MatchFilter.CROSS_CHECK -> DMatchVector().also { BFMatcher(norm, true).match(template.descriptors, page.descriptors, it) }.toList()
            MatchFilter.GMS -> {
                val nearest = DMatchVector()
                BFMatcher(norm, false).match(template.descriptors, page.descriptors, nearest)
                val consistent = DMatchVector()
                matchGMS(Size(template.width, template.height), Size(page.gray.cols(), page.gray.rows()),
                    template.keypoints, page.keypoints, nearest, consistent, true, false, GMS_THRESHOLD)
                consistent.toList()
            }
        }
    }

    private fun DMatchVector.toList(): List<DMatch> = (0 until size()).map { get(it) }

    private class PreparedPage(val gray: Mat, val keypoints: KeyPointVector, val descriptors: Mat) : AutoCloseable {
        override fun close() {
            gray.close(); keypoints.close(); descriptors.close()
        }
    }

    private fun prepare(image: BufferedImage): PreparedPage {
        val gray = grayscale(image)
        val keypoints = KeyPointVector()
        val descriptors = Mat()
        detectFeatures(gray, renderDpi, keypoints, descriptors)
        return PreparedPage(gray, keypoints, descriptors)
    }

    private fun load(file: File): LoadedTemplate {
        val template = objectMapper.readValue<Template>(file.readText())
        val folder = File(file.parent)
        val image = ImageIO.read(folder.resolve(template.templateBilde))
        val keypoints = KeyPointVector()
        val descriptors = Mat()
        val lines = grayscale(image).use { gray ->
            detectFeatures(gray, PdfTemplateImages.DPI, keypoints, descriptors)
            formLines(gray)
        }
        return LoadedTemplate(template, image.width, image.height, keypoints, descriptors, lines)
    }

    /**
     * [methods]' keypoints [keypoints] and [descriptors] of [gray] (at [dpi]), found at [featureDpi] and given in
     * [gray]'s pixels. A form's lines and print are plain at that size; at 400 DPI SIFT, which doubles the image
     * first, would take many times the time and gigabytes of memory per page. A detector of its own for each call, as
     * pages are prepared on several threads at once.
     */
    private fun detectFeatures(gray: Mat, dpi: Int, keypoints: KeyPointVector, descriptors: Mat) {
        val scale = featureDpi.toDouble() / dpi
        if (scale == 1.0) return detect(gray, keypoints, descriptors)
        Mat().use { small ->
            resize(gray, small, Size(), scale, scale, INTER_AREA)
            detect(small, keypoints, descriptors)
        }
        // Pixel centres: pixel i of the small image covers pixels i/scale .. (i+1)/scale of the page.
        for (i in 0 until keypoints.size()) {
            val keypoint = keypoints.get(i)
            val pt = keypoint.pt() // the keypoint's own point, changed in place
            pt.x(((pt.x() + 0.5) / scale - 0.5).toFloat())
            pt.y(((pt.y() + 0.5) / scale - 0.5).toFloat())
            keypoint.size((keypoint.size() / scale).toFloat())
        }
    }

    private fun detect(image: Mat, keypoints: KeyPointVector, descriptors: Mat) = methods.detector.create().use { detector ->
        Mat().use { mask -> detector.detectAndCompute(image, mask, keypoints, descriptors) }
    }

    private fun pointMat(points: List<Point2f>): Mat = Mat(points.size, 1, CV_32FC2).apply {
        createIndexer<FloatIndexer>().use { indexer ->
            points.forEachIndexed { i, p ->
                indexer.put(i.toLong(), 0, 0, p.x())
                indexer.put(i.toLong(), 0, 1, p.y())
            }
        }
    }

    private fun pdfToImages(document: PDDocument): List<BufferedImage> =
        (1..document.numberOfPages).map { page -> PdfTemplateImages.render(document, page, renderDpi) }

    companion object {
        const val DEFAULT_MAX_PAGES = 5
        /** The resolution features are found at, unless another is given: the pages scaled down to it. */
        const val DEFAULT_FEATURE_DPI = 100
        /** Lowe's ratio: the best match must be closer than this share of the second best. */
        private const val RATIO = 0.75f
        /** GMS's threshold factor: higher keeps fewer matches. */
        private const val GMS_THRESHOLD = 6.0
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

internal fun formLines(gray: Mat): Mat {
    val length = maxOf(40, minOf(gray.cols(), gray.rows()) / 40)
    Mat().use { ink ->
        threshold(gray, ink, 0.0, 255.0, THRESH_BINARY_INV or THRESH_OTSU)
        Mat().use { horizontal ->
            Mat().use { vertical ->
                getStructuringElement(MORPH_RECT, Size(length, 1)).use { morphologyEx(ink, horizontal, MORPH_OPEN, it) }
                getStructuringElement(MORPH_RECT, Size(1, length)).use { morphologyEx(ink, vertical, MORPH_OPEN, it) }
                return Mat().also { bitwise_or(horizontal, vertical, it) }
            }
        }
    }
}
