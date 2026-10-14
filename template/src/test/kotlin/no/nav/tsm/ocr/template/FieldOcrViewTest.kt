package no.nav.tsm.ocr.template

import no.nav.tsm.sykmelding.utils.FieldTransform
import no.nav.tsm.ocr.template.view.ImageComparisonView
import no.nav.tsm.sykmelding.utils.ImagePoint
import no.nav.tsm.sykmelding.ocr.PSM_MODE
import no.nav.tsm.sykmelding.ocr.ReferencesRectangle
import org.example.no.nav.tsm.ocr.template.template.Felter
import org.example.no.nav.tsm.ocr.template.template.Template
import org.example.no.nav.tsm.ocr.template.template.TemplateFiles
import org.junit.jupiter.api.io.TempDir
import java.awt.Color
import java.awt.Component
import java.awt.Container
import java.awt.Font
import java.awt.geom.AffineTransform
import java.awt.image.BufferedImage
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO
import javax.swing.JButton
import javax.swing.JList
import javax.swing.JTextArea
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FieldOcrViewTest {
    @TempDir
    lateinit var directory: File
    private val field = Felter(300, 400, 400, 100, "Value", "value")
    private val transform = FieldTransform(1.05, 0.02, 20.0, 30.0, c = 0.04, d = 0.95)

    @Test
    fun `field OCR requires applied configuration and a selected field and receives the adjusted crop`() {
        var status = ""
        val view = ImageComparisonView(
            findReferences = ::match,
            recognizeText = { crop, mode ->
                assertFalse(SwingUtilities.isEventDispatchThread())
                assertEquals(PSM_MODE.PSM_SINGLE_BLOCK, mode)
                assertEquals(400, crop.width)
                assertEquals(100, crop.height)
                assertEquals(Color.BLUE.rgb, crop.getRGB(200, 50))
                crop.setRGB(200, 50, Color.RED.rgb)
                "12345\nSecond line\n"
            },
            onStatus = { status = it },
        )
        val incoming = incoming(Color.BLUE)
        val file = configuration()
        edt {
            assertFalse(readButton(view).isEnabled)
            view.loadConfiguration(file)
            fields(view).selectedIndex = 0
            view.loadSecond(incoming)
            assertFalse(readButton(view).isEnabled)
            button(view, "Apply configuration").doClick()
            assertFalse(readButton(view).isEnabled)
            button(view, "Apply reference images").doClick()
        }
        await { button(view, "Apply configuration").isEnabled }
        edt {
            assertFalse(readButton(view).isEnabled)
            button(view, "Apply configuration").doClick()
            assertTrue(readButton(view).isEnabled)
            fields(view).clearSelection()
            assertFalse(readButton(view).isEnabled)
            fields(view).selectedIndex = 0
            readButton(view).doClick()
            assertFalse(readButton(view).isEnabled)
            assertFalse(button(view, "Read page number").isEnabled)
            assertFalse(button(view, "Detect sykmelding").isEnabled)
        }
        await { text(view).contains("Field read: Value (value)") }
        edt {
            assertTrue(text(view).contains("Recognized text:\n12345\nSecond line\n"))
            assertEquals("Field read: Value (value)", status)
            assertTrue(readButton(view).isEnabled)
            val center = transform.map(ImagePoint(500.0, 450.0))
            assertEquals(Color.BLUE.rgb, incoming.getRGB(center.x.toInt(), center.y.toInt()))
            fields(view).selectedIndex = 1
            assertFalse(text(view).contains("12345"))
            assertFalse(status.startsWith("Field read:"))
        }
    }

    @Test
    fun `blank failed null and out of bounds OCR are explicit and allow retry`() {
        var result: String? = ""
        var failure = false
        val view = ImageComparisonView(
            findReferences = ::match,
            recognizeText = { _, _ -> if (failure) error("Recognition failed") else result },
        )
        loadApplied(view)
        edt { readButton(view).doClick() }
        await { text(view).contains("No text found in field 'Value'") }
        edt {
            failure = true
            readButton(view).doClick()
        }
        await { text(view).contains("Could not read field: Recognition failed") }
        edt {
            failure = false
            result = null
            readButton(view).doClick()
        }
        await { text(view).contains("Could not read field: OCR returned no result") }
        edt {
            result = "OK"
            fields(view).selectedIndex = 1
            readButton(view).doClick()
        }
        await { text(view).contains("The selected field extends outside the incoming document") }
        edt {
            fields(view).selectedIndex = 0
            readButton(view).doClick()
        }
        await { text(view).contains("Recognized text:\nOK") }
    }

    @Test
    fun `alignment and document changes clear field values and require applying configuration again`() {
        val view = ImageComparisonView(findReferences = ::match, recognizeText = { _, _ -> "12345" })
        loadApplied(view)
        val changes: List<() -> Unit> = listOf(
            { button(view, "Apply reference images").doClick() },
            { view.resetSecondImage() },
            { button(view, "Match incoming size to template").doClick() },
            { view.loadSecond(incoming(Color.BLUE)) },
            { view.loadConfiguration(directory.resolve("config.json")) },
        )
        for (change in changes) {
            edt { readButton(view).doClick() }
            await { text(view).contains("Recognized text:\n12345") }
            edt {
                change()
                assertFalse(readButton(view).isEnabled)
                assertFalse(text(view).contains("12345"))
            }
            await { button(view, "Apply reference images").isEnabled }
            edt { button(view, "Apply reference images").doClick() }
            await { button(view, "Apply configuration").isEnabled }
            edt {
                fields(view).selectedIndex = 0
                assertFalse(readButton(view).isEnabled)
                button(view, "Apply configuration").doClick()
                assertTrue(readButton(view).isEnabled)
            }
        }
    }

    @Test
    fun `late field OCR cannot restore a value after field selection changes`() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val view = ImageComparisonView(findReferences = ::match, recognizeText = { _, _ ->
            started.countDown()
            var waiting = true
            while (waiting) {
                try {
                    check(release.await(10, TimeUnit.SECONDS))
                    waiting = false
                } catch (_: InterruptedException) {
                    // Native OCR may finish despite cancellation.
                }
            }
            finished.countDown()
            "Stale value"
        })
        loadApplied(view)
        edt { readButton(view).doClick() }
        assertTrue(started.await(10, TimeUnit.SECONDS))
        edt { fields(view).selectedIndex = 1 }
        release.countDown()
        assertTrue(finished.await(10, TimeUnit.SECONDS))
        Thread.sleep(100)
        edt { assertFalse(text(view).contains("Stale value")) }
    }

    @Test
    fun `native OCR reads text after rectifying a rotated sheared and scaled incoming field`() {
        val view = ImageComparisonView(findReferences = ::match)
        loadApplied(view, incoming(Color.WHITE, "Test 42"))
        edt { readButton(view).doClick() }
        await { readButton(view).isEnabled }
        edt { assertTrue(text(view).contains("Recognized text:\nTest 42"), text(view)) }
    }

    private fun loadApplied(view: ImageComparisonView, incoming: BufferedImage = incoming(Color.BLUE)) {
        val file = configuration()
        edt {
            view.loadConfiguration(file)
            view.loadSecond(incoming)
            button(view, "Apply reference images").doClick()
        }
        await { button(view, "Apply configuration").isEnabled }
        edt {
            fields(view).selectedIndex = 0
            button(view, "Apply configuration").doClick()
            assertTrue(readButton(view).isEnabled)
        }
    }

    private fun match(page: BufferedImage, crop: BufferedImage): List<ReferencesRectangle> {
        val center = when (crop.width) {
            10 -> ImagePoint(500.0, 500.0)
            20 -> ImagePoint(2200.0, 500.0)
            else -> ImagePoint(500.0, 3000.0)
        }
        val target = if (page.getRGB(0, 0) == Color.BLACK.rgb) transform.map(center) else center
        return listOf(ReferencesRectangle(target.x.toInt() - crop.width / 2, target.y.toInt() - crop.height / 2,
            crop.width, crop.height))
    }

    private fun configuration(): File {
        val template = directory.resolve("template.png")
        ImageIO.write(image(3307, 4677), "png", template)
        val refs = listOf(10, 20, 30).map { width ->
            directory.resolve("ref-$width.png").also { ImageIO.write(image(width, 10), "png", it) }.absolutePath
        }
        return directory.resolve("config.json").also {
            TemplateFiles.write(it, Template("Fields", "", listOf(field,
                Felter(3200, 100, 100, 50, "Outside", "outside")), template.absolutePath, referansepunkter = refs))
        }
    }

    private fun incoming(color: Color, label: String? = null) = image(3307, 4677).apply {
        setRGB(0, 0, Color.BLACK.rgb)
        val graphics = createGraphics()
        try {
            graphics.transform = AffineTransform(transform.a, transform.b, transform.c, transform.d, transform.tx, transform.ty)
            graphics.color = color
            graphics.fillRect(field.x, field.y, field.bredde, field.hoyde)
            if (label != null) {
                graphics.color = Color.BLACK
                graphics.font = Font(Font.SANS_SERIF, Font.PLAIN, 36)
                graphics.drawString(label, field.x + 20, field.y + 60)
            }
        } finally {
            graphics.dispose()
        }
    }

    private fun image(width: Int, height: Int) =
        BufferedImage(width, height, BufferedImage.TYPE_INT_RGB).apply {
            val graphics = createGraphics()
            try {
                graphics.color = Color.WHITE
                graphics.fillRect(0, 0, width, height)
            } finally {
                graphics.dispose()
            }
        }

    private fun readButton(view: ImageComparisonView) = button(view, "Read field")
    private fun button(view: ImageComparisonView, text: String) =
        descendants(view).filterIsInstance<JButton>().single { it.text == text }
    private fun fields(view: ImageComparisonView) = descendants(view).filterIsInstance<JList<*>>().first()
    private fun text(view: ImageComparisonView) = descendants(view).filterIsInstance<JTextArea>().joinToString("\n") { it.text }
    private fun edt(action: () -> Unit) = SwingUtilities.invokeAndWait(action)

    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
        while (System.nanoTime() < deadline) {
            var ready = false
            edt { ready = condition() }
            if (ready) return
            Thread.sleep(20)
        }
        error("Timed out waiting for field OCR")
    }

    private fun descendants(container: Container): Sequence<Component> = sequence {
        for (component in container.components) {
            yield(component)
            if (component is Container) yieldAll(descendants(component))
        }
    }
}
