package no.nav.tsm.ocr.template

import java.awt.Point
import java.awt.event.MouseEvent
import java.awt.image.BufferedImage
import javax.imageio.ImageIO
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import no.nav.tsm.sykmelding.ocr.Ocr
import org.example.no.nav.tsm.ocr.template.ImagePanel
import org.example.no.nav.tsm.ocr.template.createDarkOverlay

class ImageSelectionTest {
    @Test
    fun `selection accounts for scaling centering and reverse dragging`() {
        SwingUtilities.invokeAndWait {
            val source = BufferedImage(400, 200, BufferedImage.TYPE_INT_RGB)
            for (y in 0 until source.height) {
                for (x in 0 until source.width) source.setRGB(x, y, (x shl 8) or y)
            }
            val panel = ImagePanel(source).apply { setSize(200, 200) }
            for ((start, end) in listOf(Point(25, 60) to Point(75, 90), Point(75, 90) to Point(25, 60))) {
                var crop: BufferedImage? = null
                panel.selectArea({ crop = it }, { error("Unexpected invalid selection") })
                drag(panel, start, end)

                val selected = assertNotNull(crop)
                assertEquals(100, selected.width)
                assertEquals(60, selected.height)
                assertEquals(source.getRGB(50, 20), selected.getRGB(0, 0))
                assertEquals(source.getRGB(149, 79), selected.getRGB(99, 59))
                assertFalse(panel.isSelecting)
            }
        }
    }

    @Test
    fun `selection is clipped to the picture and excludes the overlay`() {
        SwingUtilities.invokeAndWait {
            val source = BufferedImage(400, 200, BufferedImage.TYPE_INT_RGB)
            val panel = ImagePanel(source, createDarkOverlay(source)).apply { setSize(200, 200) }
            var crop: BufferedImage? = null
            panel.selectArea({ crop = it }, { error("Unexpected invalid selection") })

            drag(panel, Point(100, 100), Point(300, 300))

            val selected = assertNotNull(crop)
            assertEquals(200, selected.width)
            assertEquals(100, selected.height)
            assertEquals(0xff000000.toInt(), selected.getRGB(0, 0))
        }
    }

    @Test
    fun `selection requires OCR mode and rejects clicks and letterbox starts`() {
        SwingUtilities.invokeAndWait {
            val panel = ImagePanel(BufferedImage(400, 200, BufferedImage.TYPE_INT_RGB))
                .apply { setSize(200, 200) }
            var crop: BufferedImage? = null
            var invalid = 0
            drag(panel, Point(10, 60), Point(50, 90))
            panel.selectArea({ crop = it }, { invalid++ })

            drag(panel, Point(10, 10), Point(50, 90))
            drag(panel, Point(20, 70), Point(20, 70))
            assertNull(crop)
            assertEquals(2, invalid)
            assertTrue(panel.isSelecting)

            panel.cancelSelection()
            drag(panel, Point(10, 60), Point(50, 90))
            assertNull(crop)
            assertFalse(panel.isSelecting)
        }
    }

    @Test
    fun `selection uses the current aligned image dimensions`() {
        SwingUtilities.invokeAndWait {
            val panel = ImagePanel(BufferedImage(400, 200, BufferedImage.TYPE_INT_RGB))
                .apply { setSize(200, 200) }
            var crop: BufferedImage? = null
            panel.selectArea({ crop = it }, { error("Unexpected invalid selection") })
            panel.image = BufferedImage(100, 200, BufferedImage.TYPE_INT_RGB)
            assertFalse(panel.isSelecting)
            panel.selectArea({ crop = it }, { error("Unexpected invalid selection") })

            drag(panel, Point(60, 20), Point(90, 80))

            assertEquals(30, assertNotNull(crop).width)
            assertEquals(60, assertNotNull(crop).height)
        }
    }

    @Test
    fun `selection crops the replacement image when alignment enlarges it`() {
        SwingUtilities.invokeAndWait {
            val original = BufferedImage(100, 80, BufferedImage.TYPE_3BYTE_BGR)
            val aligned = BufferedImage(400, 300, BufferedImage.TYPE_3BYTE_BGR)
            aligned.setRGB(200, 200, 0xff0000ff.toInt())
            val panel = ImagePanel(original).apply {
                image = aligned
                setSize(200, 150)
            }
            var crop: BufferedImage? = null
            panel.selectArea({ crop = it }, { error("Unexpected invalid selection") })

            drag(panel, Point(100, 100), Point(250, 200))

            val selected = assertNotNull(crop)
            assertEquals(200, selected.width)
            assertEquals(100, selected.height)
            assertEquals(aligned.getRGB(200, 200), selected.getRGB(0, 0))
        }
    }

    @Test
    fun `selected subpicture can be recognized by OCR`() {
        val source = ImageIO.read(javaClass.getResource("/test.png"))
        val canvas = BufferedImage(source.width * 2, source.height * 2, BufferedImage.TYPE_INT_RGB)
        val graphics = canvas.createGraphics()
        try {
            graphics.color = java.awt.Color.WHITE
            graphics.fillRect(0, 0, canvas.width, canvas.height)
            graphics.drawImage(source, source.width, source.height, null)
        } finally {
            graphics.dispose()
        }
        var crop: BufferedImage? = null
        SwingUtilities.invokeAndWait {
            val panel = ImagePanel(canvas).apply { setSize(canvas.width, canvas.height) }
            panel.selectArea({ crop = it }, { error("Unexpected invalid selection") })
            drag(panel, Point(source.width, source.height), Point(canvas.width, canvas.height))
        }

        assertEquals("Test\n", Ocr().parse(assertNotNull(crop)))
    }

    private fun drag(panel: ImagePanel, start: Point, end: Point) {
        panel.dispatchEvent(MouseEvent(panel, MouseEvent.MOUSE_PRESSED, 0, 0, start.x, start.y, 1, false, MouseEvent.BUTTON1))
        panel.dispatchEvent(MouseEvent(panel, MouseEvent.MOUSE_DRAGGED, 0, MouseEvent.BUTTON1_DOWN_MASK, end.x, end.y, 0, false, MouseEvent.NOBUTTON))
        panel.dispatchEvent(MouseEvent(panel, MouseEvent.MOUSE_RELEASED, 0, 0, end.x, end.y, 1, false, MouseEvent.BUTTON1))
    }
}
