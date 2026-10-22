package no.nav.tsm.sykmelding.template

import no.nav.tsm.sykmelding.utils.grayscale
import no.nav.tsm.sykmelding.utils.toBufferedImage
import org.apache.pdfbox.Loader
import org.apache.pdfbox.pdmodel.PDDocument
import org.bytedeco.javacpp.PointerScope
import org.bytedeco.javacpp.indexer.FloatIndexer
import org.bytedeco.opencv.global.opencv_calib3d.RANSAC
import org.bytedeco.opencv.global.opencv_calib3d.findHomography
import org.bytedeco.opencv.global.opencv_core.BORDER_CONSTANT
import org.bytedeco.opencv.global.opencv_core.CV_32FC2
import org.bytedeco.opencv.global.opencv_core.NORM_HAMMING
import org.bytedeco.opencv.global.opencv_core.bitwise_and
import org.bytedeco.opencv.global.opencv_core.bitwise_or
import org.bytedeco.opencv.global.opencv_core.countNonZero
import org.bytedeco.opencv.global.opencv_imgproc.INTER_LINEAR
import org.bytedeco.opencv.global.opencv_imgproc.MORPH_OPEN
import org.bytedeco.opencv.global.opencv_imgproc.MORPH_RECT
import org.bytedeco.opencv.global.opencv_imgproc.THRESH_BINARY_INV
import org.bytedeco.opencv.global.opencv_imgproc.THRESH_OTSU
import org.bytedeco.opencv.global.opencv_imgproc.dilate
import org.bytedeco.opencv.global.opencv_imgproc.getStructuringElement
import org.bytedeco.opencv.global.opencv_imgproc.morphologyEx
import org.bytedeco.opencv.global.opencv_imgproc.threshold
import org.bytedeco.opencv.global.opencv_imgproc.warpPerspective
import org.bytedeco.opencv.global.opencv_xfeatures2d.matchGMS
import org.bytedeco.opencv.opencv_core.DMatchVector
import org.bytedeco.opencv.opencv_core.KeyPointVector
import org.bytedeco.opencv.opencv_core.Mat
import org.bytedeco.opencv.opencv_core.Point2f
import org.bytedeco.opencv.opencv_core.Scalar
import org.bytedeco.opencv.opencv_core.Size
import org.bytedeco.opencv.opencv_features2d.AKAZE
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
    val keypoints: KeyPointVector,
    val descriptors: Mat,
    val lines: Mat,
) : AutoCloseable {
    override fun close() {
        keypoints.close(); descriptors.close(); lines.close()
    }
}

/** Matches PDFs to [templates], by default every template in [library]. */
class TemplateMatcher(
    library: TemplateLibrary = TemplateLibrary(File("../templates")),
    private val maxPages: Int = DEFAULT_MAX_PAGES,
    private val templates: List<TemplateGroup> = library.list(),
) : AutoCloseable {
    private val objectMapper = jacksonObjectMapper()
    private val akaze = AKAZE.create()
    private val loaded: Map<TemplateGroup, List<LoadedTemplate>> = templates.associateWith { group -> group.pageFiles.map { load(it)} }

    fun matchPdf(pdf: File): TemplateMatchResult = try {
        match(pdf)
    } catch (e: Exception) {
        TemplateMatchResult.Error(e)
    }

    override fun close() {
        loaded.values.flatten().forEach { it.close() }
        akaze.close()
    }

    private fun match(pdf: File): TemplateMatchResult {
        val pages = Loader.loadPDF(pdf).use { document ->
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

        val nearest = DMatchVector()
        BFMatcher(NORM_HAMMING, false).match(template.descriptors, page.descriptors, nearest)
        val consistent = DMatchVector()
        matchGMS(Size(template.width, template.height), Size(page.gray.cols(), page.gray.rows()),
            template.keypoints, page.keypoints, nearest, consistent, true, false, 6.0)
        val matches = (0 until consistent.size()).map { consistent.get(it) }
        if (matches.size < MIN_MATCHES) return null

        val from = pointMat(matches.map { page.keypoints.get(it.trainIdx().toLong()).pt() })
        val to = pointMat(matches.map { template.keypoints.get(it.queryIdx().toLong()).pt() })
        val inlierMask = Mat()
        val homography = findHomography(from, to, RANSAC, 3.0, inlierMask, 2000, 0.99)
        if (homography.empty()) return null
        val inliers = countNonZero(inlierMask)
        if (inliers < MIN_INLIERS || inliers < matches.size * MIN_AGREEMENT) return null

        val aligned = Mat()
        warpPerspective(page.gray, aligned, homography, Size(template.width, template.height), INTER_LINEAR, BORDER_CONSTANT, Scalar(255.0))
        // Copied into Java memory here, before the scope frees the Mat.
        if (lineFit(template.lines, aligned) >= MIN_LINE_FIT) toBufferedImage(aligned) else null
    }

    private class PreparedPage(val gray: Mat, val keypoints: KeyPointVector, val descriptors: Mat) : AutoCloseable {
        override fun close() {
            gray.close(); keypoints.close(); descriptors.close()
        }
    }

    private fun prepare(image: BufferedImage): PreparedPage {
        val gray = grayscale(image)
        val keypoints = KeyPointVector()
        val descriptors = Mat()
        Mat().use { mask -> akaze.detectAndCompute(gray, mask, keypoints, descriptors) }
        return PreparedPage(gray, keypoints, descriptors)
    }

    private fun load(file: File): LoadedTemplate {
        val template = objectMapper.readValue<Template>(file.readText())
        val folder = File(file.parent)
        val image = ImageIO.read(folder.resolve(template.templateBilde))
        val keypoints = KeyPointVector()
        val descriptors = Mat()
        val lines = grayscale(image).use { gray ->
            Mat().use { mask -> akaze.detectAndCompute(gray, mask, keypoints, descriptors) }
            formLines(gray)
        }
        return LoadedTemplate(template, image.width, image.height, keypoints, descriptors, lines)
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
        (1..document.numberOfPages).map { page -> PdfTemplateImages.render(document, page) }

    companion object {
        const val DEFAULT_MAX_PAGES = 5
        private const val MIN_MATCHES = 8
        private const val MIN_INLIERS = 8
        private const val MIN_AGREEMENT = 0.4
        private const val MIN_LINE_FIT = 0.6
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
