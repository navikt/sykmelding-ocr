package no.nav.tsm.ocr.template

import java.awt.image.BufferedImage
import org.example.no.nav.tsm.ocr.template.ImagePanel
import org.example.no.nav.tsm.ocr.template.createDarkOverlay
import javax.swing.SwingUtilities
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ImageOverlayTest {
    @Test
    fun `right panel renders the replacement image`() {
        SwingUtilities.invokeAndWait {
            val panel = ImagePanel(BufferedImage(10, 10, BufferedImage.TYPE_INT_RGB))
            val aligned = BufferedImage(30, 20, BufferedImage.TYPE_INT_RGB)
            aligned.setRGB(15, 10, 0xff0000ff.toInt())
            panel.image = aligned
            panel.setSize(30, 20)
            val rendered = BufferedImage(30, 20, BufferedImage.TYPE_INT_RGB)
            val graphics = rendered.createGraphics()
            try {
                panel.paint(graphics)
            } finally {
                graphics.dispose()
            }
            assertEquals(0xff0000ff.toInt(), rendered.getRGB(15, 10))
        }
    }

    @Test
    fun `overlay is visible before processing and after resizing`() {
        SwingUtilities.invokeAndWait {
            val source = BufferedImage(10, 10, BufferedImage.TYPE_INT_RGB)
            val overlay = createDarkOverlay(source)
            val panel = ImagePanel(source, overlay)
            for (size in listOf(20, 40)) {
                panel.setSize(size, size)
                val rendered = BufferedImage(size, size, BufferedImage.TYPE_INT_RGB)
                val graphics = rendered.createGraphics()
                try {
                    panel.paint(graphics)
                } finally {
                    graphics.dispose()
                }
                assertEquals(0xffff0000.toInt(), rendered.getRGB(size / 2, size / 2))
            }
        }
    }

    @Test
    fun `red opacity decreases smoothly with brightness`() {
        val source = BufferedImage(4, 1, BufferedImage.TYPE_INT_ARGB)
        val pixels = intArrayOf(0xff000000.toInt(), 0xff7f7f7f.toInt(), 0xff808080.toInt(), 0xffffffff.toInt())
        pixels.forEachIndexed { x, pixel -> source.setRGB(x, 0, pixel) }

        val overlay = createDarkOverlay(source)

        assertEquals(4, overlay.width)
        assertEquals(1, overlay.height)
        assertEquals(0xffff0000.toInt(), overlay.getRGB(0, 0))
        assertEquals(0x80ff0000.toInt(), overlay.getRGB(1, 0))
        assertEquals(0x7fff0000, overlay.getRGB(2, 0))
        assertEquals(0, overlay.getRGB(3, 0))
        pixels.forEachIndexed { x, pixel -> assertEquals(pixel, source.getRGB(x, 0)) }
    }

    @Test
    fun `all opaque gray levels preserve their ink opacity`() {
        val source = BufferedImage(256, 1, BufferedImage.TYPE_INT_ARGB)
        for (gray in 0..255) {
            source.setRGB(gray, 0, 0xff000000.toInt() or (gray shl 16) or (gray shl 8) or gray)
        }

        val overlay = createDarkOverlay(source)

        for (gray in 0..255) {
            assertEquals(255 - gray, overlay.getRGB(gray, 0) ushr 24)
        }
    }

    @Test
    fun `faint lines in img_2 remain visible`() {
        val source = ImageIO.read(javaClass.getResource("/img_2.png"))
        val overlay = createDarkOverlay(source)
        var faintPixels = 0
        for (y in 0 until source.height) {
            for (x in 0 until source.width) {
                val pixel = source.getRGB(x, y)
                val red = (pixel ushr 16) and 0xff
                val green = (pixel ushr 8) and 0xff
                val blue = pixel and 0xff
                if (pixel ushr 24 == 255 && red == green && green == blue && red in 128..254) {
                    faintPixels++
                    assertEquals(255 - red, overlay.getRGB(x, y) ushr 24)
                }
            }
        }
        assertTrue(faintPixels > 100, "Fixture must include faint line pixels")
    }

    @Test
    fun `dark pixels preserve source transparency`() {
        val source = BufferedImage(1, 3, BufferedImage.TYPE_INT_ARGB)
        source.setRGB(0, 0, 0x00000000)
        source.setRGB(0, 1, 0x80000000.toInt())
        source.setRGB(0, 2, 0x80808080.toInt())

        val overlay = createDarkOverlay(source)

        assertEquals(0, overlay.getRGB(0, 0) ushr 24)
        assertEquals(0x80ff0000.toInt(), overlay.getRGB(0, 1))
        assertEquals(0x40ff0000, overlay.getRGB(0, 2))
    }
}
