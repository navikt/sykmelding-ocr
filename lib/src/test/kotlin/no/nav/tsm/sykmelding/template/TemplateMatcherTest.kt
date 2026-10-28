package no.nav.tsm.sykmelding.template

import org.apache.pdfbox.Loader
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.graphics.image.JPEGFactory
import org.apache.pdfbox.rendering.ImageType
import org.apache.pdfbox.rendering.PDFRenderer
import java.awt.Color
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class TemplateMatcherTest {
    @Test
    fun `a PDF in memory matches the packaged templates as the same PDF read from a file does`() {
        val bytes = requireNotNull(javaClass.getResourceAsStream("/test.pdf")) { "Missing /test.pdf" }.use { it.readBytes() }
        val file = File(requireNotNull(javaClass.getResource("/test.pdf")) { "Missing /test.pdf" }.toURI())
        TemplateMatcher().use { matcher ->
            val fromBytes = assertIs<TemplateMatchResult.Matched>(matcher.matchPdf(bytes))
            val fromFile = assertIs<TemplateMatchResult.Matched>(matcher.matchPdf(file))
            assertEquals(fromFile.template, fromBytes.template)
            assertEquals(fromFile.templateMatch.keys.toList(), fromBytes.templateMatch.keys.toList())
        }
    }

    @Test
    fun `bytes that are not a PDF are an error result`() {
        TemplateMatcher(templates = emptyList()).use { matcher ->
            assertIs<TemplateMatchResult.Error>(matcher.matchPdf("not a PDF".toByteArray()))
        }
    }

    @Test
    fun `a scanned copy, tilted and at a lower resolution, matches the same template as the original`() {
        val bytes = requireNotNull(javaClass.getResourceAsStream("/test.pdf")) { "Missing /test.pdf" }.use { it.readBytes() }
        TemplateMatcher().use { matcher ->
            val original = assertIs<TemplateMatchResult.Matched>(matcher.matchPdf(bytes))
            val scanned = assertIs<TemplateMatchResult.Matched>(matcher.matchPdf(scan(bytes)))
            assertEquals(original.template, scanned.template)
            assertEquals(original.templateMatch.keys.toList(), scanned.templateMatch.keys.toList())
        }
    }

    @Test
    fun `rendered and matched at lower resolutions, a PDF matches the same template, aligned to the template's size`() {
        val bytes = requireNotNull(javaClass.getResourceAsStream("/test.pdf")) { "Missing /test.pdf" }.use { it.readBytes() }
        val original = TemplateMatcher().use { assertIs<TemplateMatchResult.Matched>(it.matchPdf(bytes)) }
        TemplateMatcher(renderDpi = 200, featureDpi = 75).use { matcher ->
            assertEquals(200, matcher.renderDpi)
            assertEquals(75, matcher.featureDpi)
            val lower = assertIs<TemplateMatchResult.Matched>(matcher.matchPdf(bytes))
            assertEquals(original.template, lower.template)
            assertEquals(original.templateMatch.keys.toList(), lower.templateMatch.keys.toList())
            assertEquals(original.templateMatch.values.map { it.width to it.height }, lower.templateMatch.values.map { it.width to it.height })
        }
    }

    @Test
    fun `features are found at no more than the render resolution or the templates'`() {
        assertFailsWith<IllegalArgumentException> { TemplateMatcher(templates = emptyList(), renderDpi = 100, featureDpi = 150) }
        assertFailsWith<IllegalArgumentException> { TemplateMatcher(templates = emptyList(), renderDpi = 600, featureDpi = 500) }
        assertFailsWith<IllegalArgumentException> { TemplateMatcher(templates = emptyList(), featureDpi = 0) }
        TemplateMatcher(templates = emptyList(), renderDpi = 50, featureDpi = 50).close()
    }

    @Test
    fun `a match agrees with the homography within one feature pixel, but never less than 3 template pixels`() {
        assertEquals(4.0, TemplateMatcher.reprojectionTolerance(100), "production's")
        assertEquals(8.0, TemplateMatcher.reprojectionTolerance(50))
        // At 1 px (one pixel at 400 DPI), scans of the right form had only 9–33 % of their matches agree, so none matched.
        assertEquals(3.0, TemplateMatcher.reprojectionTolerance(400), "as the matcher was before feature DPI could be chosen")
        assertEquals(3.0, TemplateMatcher.reprojectionTolerance(200))
        TemplateMatcher(templates = emptyList(), featureDpi = 400).use { assertEquals(3.0, it.tolerance, "auto") }
        TemplateMatcher(templates = emptyList(), featureDpi = 400, tolerance = 1.5).use { assertEquals(1.5, it.tolerance, "as given") }
        assertFailsWith<IllegalArgumentException> { TemplateMatcher(templates = emptyList(), tolerance = 0.0) }
    }

    @Test
    fun `every keypoint detector, match filter and homography method can be matched with`() {
        val bytes = requireNotNull(javaClass.getResourceAsStream("/test.pdf")) { "Missing /test.pdf" }.use { it.readBytes() }
        val templates = TemplateLibrary.fromResources().list()
        val expected = TemplateMatcher(templates = templates).use { assertIs<TemplateMatchResult.Matched>(it.matchPdf(bytes)).template }
        val variations = KeypointDetector.entries.map { MatchingMethods(detector = it) } +
            MatchFilter.entries.map { MatchingMethods(filter = it) } +
            HomographyMethod.entries.map { MatchingMethods(homography = it) }
        for (methods in variations.distinct()) {
            TemplateMatcher(templates = listOf(expected), methods = methods).use { matcher ->
                assertEquals(methods, matcher.methods)
                val result = matcher.matchPdf(bytes)
                check(result !is TemplateMatchResult.Error) { "${methods.text}: ${(result as TemplateMatchResult.Error).exception}" }
                // ORB finds too few distinctive keypoints on these forms to match them.
                if (methods.detector != KeypointDetector.ORB) assertEquals(expected, assertIs<TemplateMatchResult.Matched>(result, methods.text).template)
            }
        }
    }

    /** [pdf] as a scanner might give it back: each page in gray at 150 DPI, tilted 1.5°, as a JPEG. */
    private fun scan(pdf: ByteArray): ByteArray = Loader.loadPDF(pdf).use { source ->
        PDDocument().use { copy ->
            for (index in 0 until source.numberOfPages) {
                val image = tilt(PDFRenderer(source).renderImageWithDPI(index, 150f, ImageType.GRAY), 1.5)
                val page = PDPage(source.getPage(index).mediaBox).also { copy.addPage(it) }
                PDPageContentStream(copy, page).use {
                    it.drawImage(JPEGFactory.createFromImage(copy, image, 0.6f), 0f, 0f, page.mediaBox.width, page.mediaBox.height)
                }
            }
            ByteArrayOutputStream().also { copy.save(it) }.toByteArray()
        }
    }

    private fun tilt(image: BufferedImage, degrees: Double): BufferedImage =
        BufferedImage(image.width, image.height, BufferedImage.TYPE_BYTE_GRAY).also { tilted ->
            tilted.createGraphics().apply {
                color = Color.WHITE
                fillRect(0, 0, tilted.width, tilted.height)
                setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
                rotate(Math.toRadians(degrees), image.width / 2.0, image.height / 2.0)
                drawImage(image, 0, 0, null)
                dispose()
            }
        }
}
