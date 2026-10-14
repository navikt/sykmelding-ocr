package no.nav.tsm.sykmelding.template

import no.nav.tsm.sykmelding.utils.grayscale
import org.apache.pdfbox.Loader
import org.apache.pdfbox.pdmodel.PDDocument
import org.bytedeco.javacpp.indexer.FloatIndexer
import org.bytedeco.opencv.global.opencv_calib3d.RANSAC
import org.bytedeco.opencv.global.opencv_calib3d.findHomography
import org.bytedeco.opencv.global.opencv_core.CV_32FC2
import org.bytedeco.opencv.global.opencv_core.NORM_HAMMING
import org.bytedeco.opencv.global.opencv_core.countNonZero
import org.bytedeco.opencv.opencv_core.DMatchVectorVector
import org.bytedeco.opencv.opencv_core.KeyPointVector
import org.bytedeco.opencv.opencv_core.Mat
import org.bytedeco.opencv.opencv_core.Point2f
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
    data class Matched(val template: TemplateGroup,
            val templateMatch: Map<Template, BufferedImage>
        ) : TemplateMatchResult
    data object NoMatch : TemplateMatchResult
    data class Error(val exception: Exception) : TemplateMatchResult
    data class TooManyPages(val pages: Int, val maxPages: Int) : TemplateMatchResult
}

class LoadedTemplate(
    val template: Template,
    val bufferedImage: BufferedImage,
    val referenceImages: List<BufferedImage> = emptyList(),
)

class TemplateMatcher(
    private val library: TemplateLibrary = TemplateLibrary(File("../templates")),
    private val maxPages: Int = DEFAULT_MAX_PAGES,
) {
    private val objectMapper = jacksonObjectMapper()
    private val templates: List<TemplateGroup>

    init {
        templates = library.list()
    }

    fun matchPdf(pdf: File): TemplateMatchResult = try {
        match(pdf)
    } catch (e: Exception) {
        TemplateMatchResult.Error(e)
    }

    private fun matchWithRefPoints(pdf: File): TemplateMatchResult {
        val pages = Loader.loadPDF(pdf).use { document ->
            if (document.numberOfPages > maxPages) return TemplateMatchResult.TooManyPages(document.numberOfPages, maxPages)

            pdfToImages(document)
        }

        for(template in templates) {
            val matched = matchesTemplateFromReferenceImages(template, pages)
            if (matched is TemplateMatchResult.Matched) return TemplateMatchResult.NoMatch
        }

        return TemplateMatchResult.NoMatch

    }


    private fun match(pdf: File): TemplateMatchResult {
        val pages = Loader.loadPDF(pdf).use { document ->
            if (document.numberOfPages > maxPages) return TemplateMatchResult.TooManyPages(document.numberOfPages, maxPages)

            pdfToImages(document)
        }

        for (template in templates) {
            val matched = matchesTemplate(template, pages)
            if (matched is TemplateMatchResult.Matched) return matched
        }

        return TemplateMatchResult.NoMatch
    }
    private fun matchesTemplateFromReferenceImages(
        template: TemplateGroup,
        pages: List<BufferedImage>
    ): TemplateMatchResult {
        val templateImages = template.pageFiles.map {
            val thisTemmplate = objectMapper.readValue<Template>(it.readText())
            val templateBildet = ImageIO.read(File(it.parent).resolve(thisTemmplate.templateBilde).inputStream())
            val referenceImages = thisTemmplate.referansepunkter.map { ref ->
                ImageIO.read(File(it.parent).resolve(ref))
            }
            LoadedTemplate(template = thisTemmplate, templateBildet, referenceImages)
        }

        val refimageMatches = templateImages.associate { template ->
            val page =
                pages.firstOrNull { page -> matchesRefImage(page, template) } ?: return TemplateMatchResult.NoMatch
            template.template to page
        }

        return TemplateMatchResult.Matched(template, refimageMatches)
    }

    private fun matchesRefImage(
        page: BufferedImage,
        template: LoadedTemplate
    ): Boolean {
        TODO("Not yet implemented")
    }

    private fun matchesTemplate(template: TemplateGroup, pages: List<BufferedImage>): TemplateMatchResult {
        val templateImages = template.pageFiles.map {
            val thisTemmplate = objectMapper.readValue<Template>(it.readText())
            val templateBildet = ImageIO.read(File( it.parent).resolve(thisTemmplate.templateBilde).inputStream())
            LoadedTemplate(template = thisTemmplate, templateBildet)
        }

        val templateMatch = templateImages.associate { templateImage ->
            val page = pages.firstOrNull { page -> matchesPage(page, templateImage) } ?: return TemplateMatchResult.NoMatch
            templateImage.template to page
        }

        return TemplateMatchResult.Matched(template, templateMatch)
    }

    /** Whether the PDF [page] is the same form page as [templateImage]. */
    private fun matchesPage(page: BufferedImage, templateImage: LoadedTemplate): Boolean {
        val pageGray = grayscale(page)
        val templateGray = grayscale(templateImage.bufferedImage)

        val akaze = AKAZE.create()
        val pageKeypoints = KeyPointVector()
        val pageDescriptors = Mat()
        val templateKeypoints = KeyPointVector()
        val templateDescriptors = Mat()
        akaze.detectAndCompute(pageGray, Mat(), pageKeypoints, pageDescriptors)
        akaze.detectAndCompute(templateGray, Mat(), templateKeypoints, templateDescriptors)
        if (pageDescriptors.empty() || templateDescriptors.empty()) return false

        val knn = DMatchVectorVector()
        BFMatcher(NORM_HAMMING, false).knnMatch(templateDescriptors, pageDescriptors, knn, 2)
        val good = (0 until knn.size()).map { knn.get(it) }
            .filter { it.size() == 2L && it.get(0).distance() < 0.75f * it.get(1).distance() }
            .map { it.get(0) }
        if (good.size < 10) return false

        // 4. Do the good matches agree on one page → template transform? (RANSAC homography)
        val from = pointMat(good.map { pageKeypoints.get(it.trainIdx().toLong()).pt() })
        val to = pointMat(good.map { templateKeypoints.get(it.queryIdx().toLong()).pt() })
        val inlierMask = Mat()
        val homography = findHomography(from, to, RANSAC, 3.0, inlierMask, 2000, 0.99)
        if (homography.empty()) return false
        val inliers = countNonZero(inlierMask)

        // 5. Enough matches agree → same form page
        return (inliers >= 20 && inliers >= good.size * 0.4)

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
    }
}
