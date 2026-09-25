package no.nav.tsm.ocr.template

import java.awt.Color
import java.awt.RenderingHints
import java.awt.geom.AffineTransform
import java.awt.image.BufferedImage
import javax.imageio.ImageIO
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.example.no.nav.tsm.ocr.template.alignImages
import org.example.no.nav.tsm.ocr.template.createDarkOverlay

class ImageAlignmentTest {
    @Test
    fun `rotated form aligns to the template`() {
        val reference = ImageIO.read(javaClass.getResource("/img_2.png"))
        val target = ImageIO.read(javaClass.getResource("/img.png"))

        val result = alignImages(reference, target)

        assertEquals(target.width, result.image.width)
        assertEquals(target.height, result.image.height)
        val score = overlap(createDarkOverlay(target), result.image)
        assertTrue(score > 0.9, "Expected the form lines to align, template coverage was $score")
    }

    @Test
    fun `overlay is aligned to the supplied left image`() {
        assertAlignedOverlay("/img_3.png")
    }

    @Test
    fun `upside down image overlay is aligned to the supplied left image`() {
        assertAlignedOverlay("/img_4.png")
    }

    private fun assertAlignedOverlay(resource: String) {
        val reference = ImageIO.read(javaClass.getResource(resource))
        val target = ImageIO.read(javaClass.getResource("/img.png"))

        val result = alignImages(reference, target)
        val aligned = result.overlay

        assertEquals(target.width, result.image.width)
        assertEquals(target.height, result.image.height)
        assertEquals(target.width, aligned.width)
        assertEquals(target.height, aligned.height)
        val score = overlap(aligned, target)
        assertTrue(score > 0.8, "Expected aligned reference lines, overlap was $score")
        assertEquals(0, aligned.getRGB(0, 0), "Warped border must be transparent")
        assertEquals(Color.WHITE.rgb, result.image.getRGB(0, 0), "Image border must be white")
        val expectedOverlay = createDarkOverlay(result.image)
        assertTrue(
            expectedOverlay.getRGB(0, 0, target.width, target.height, null, 0, target.width)
                .contentEquals(aligned.getRGB(0, 0, target.width, target.height, null, 0, target.width)),
            "Both views must use the same aligned image"
        )
    }

    @Test
    fun `alignment recovers translation rotation and scale without changing the images`() {
        val reference = featureImage()
        val target = BufferedImage(900, 700, BufferedImage.TYPE_INT_RGB)
        val transform = AffineTransform().apply {
            translate(100.0, 60.0)
            rotate(Math.toRadians(7.0))
            scale(1.2, 1.2)
        }
        target.createGraphics().let { graphics ->
            try {
                graphics.color = Color.WHITE
                graphics.fillRect(0, 0, target.width, target.height)
                graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
                graphics.drawImage(reference, transform, null)
            } finally {
                graphics.dispose()
            }
        }
        val referenceBefore = reference.getRGB(0, 0, reference.width, reference.height, null, 0, reference.width)
        val targetBefore = target.getRGB(0, 0, target.width, target.height, null, 0, target.width)

        val result = alignImages(reference, target)
        val aligned = result.overlay
        val marker = transform.transform(java.awt.geom.Point2D.Double(470.0, 315.0), null)
        assertEquals(Color.BLUE.rgb, result.image.getRGB(marker.x.toInt(), marker.y.toInt()))

        val score = overlap(aligned, target)
        assertTrue(score > 0.9, "Expected recovered transform, overlap was $score")
        assertTrue(referenceBefore.contentEquals(reference.getRGB(0, 0, reference.width, reference.height, null, 0, reference.width)))
        assertTrue(targetBefore.contentEquals(target.getRGB(0, 0, target.width, target.height, null, 0, target.width)))
    }

    @Test
    fun `featureless images report alignment failure`() {
        val blank = BufferedImage(100, 100, BufferedImage.TYPE_INT_RGB)

        assertFailsWith<IllegalStateException> { alignImages(blank, blank) }
    }

    private fun featureImage(): BufferedImage {
        val image = BufferedImage(600, 450, BufferedImage.TYPE_3BYTE_BGR)
        val random = Random(42)
        val graphics = image.createGraphics()
        try {
            graphics.color = Color.WHITE
            graphics.fillRect(0, 0, image.width, image.height)
            graphics.color = Color.BLACK
            repeat(80) {
                val x = random.nextInt(30, 550)
                val y = random.nextInt(30, 400)
                graphics.drawOval(x, y, random.nextInt(5, 30), random.nextInt(5, 30))
                graphics.drawString(it.toString(), x, y)
            }
            graphics.color = Color.BLUE
            graphics.fillRect(450, 300, 40, 30)
        } finally {
            graphics.dispose()
        }
        return image
    }

    private fun overlap(overlay: BufferedImage, target: BufferedImage): Double {
        val targetInk = createDarkOverlay(target)
        var pixels = 0
        var matching = 0
        for (y in 0 until overlay.height) {
            for (x in 0 until overlay.width) {
                // Measure alignment on strong ink, not newly preserved faint edges and shadows.
                if (overlay.getRGB(x, y) ushr 24 < 128) continue
                pixels++
                val nearby = (maxOf(0, y - 3)..minOf(target.height - 1, y + 3)).any { ty ->
                    (maxOf(0, x - 3)..minOf(target.width - 1, x + 3)).any { tx ->
                        targetInk.getRGB(tx, ty) ushr 24 >= 128
                    }
                }
                if (nearby) matching++
            }
        }
        assertTrue(pixels > 100, "Aligned overlay must not be empty")
        return matching.toDouble() / pixels
    }
}
