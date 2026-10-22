package no.nav.tsm.ocr.template

import no.nav.tsm.ocr.template.view.ImageComparisonView
import no.nav.tsm.sykmelding.ocr.PSM_MODE
import no.nav.tsm.sykmelding.ocr.ReferencesRectangle
import org.example.no.nav.tsm.ocr.template.template.Template
import org.example.no.nav.tsm.ocr.template.template.TemplateFiles
import org.junit.jupiter.api.io.TempDir
import java.awt.Color
import java.awt.Component
import java.awt.Container
import java.awt.Font
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

class PageNumberViewTest {
    @TempDir
    lateinit var directory: File

    @Test
    fun `requires a selected incoming match and reads only its pixels using single block mode`() {
        var status = ""
        val view = ImageComparisonView(
            findReferences = { _, crop -> listOf(ReferencesRectangle(100, 200, crop.width, crop.height)) },
            recognizeText = { crop, mode ->
                assertFalse(SwingUtilities.isEventDispatchThread())
                assertEquals(500, crop.width)
                assertEquals(100, crop.height)
                assertEquals(Color.BLUE.rgb, crop.getRGB(0, 0))
                assertEquals(PSM_MODE.PSM_SINGLE_BLOCK, mode)
                crop.setRGB(0, 0, Color.RED.rgb)
                "Sykmelding\nSide 2 av 3"
            },
            onStatus = { status = it },
        )
        val incoming = image(3307, 4677).apply { setRGB(100, 200, Color.BLUE.rgb) }
        val file = configuration()
        edt {
            assertFalse(readButton(view).isEnabled)
            view.loadConfiguration(file)
            references(view).selectedIndex = 0
            assertFalse(readButton(view).isEnabled)
            view.loadSecond(incoming)
            assertFalse(readButton(view).isEnabled)
            applyButton(view).doClick()
        }
        await { applyButton(view).isEnabled }
        edt {
            assertTrue(status.startsWith("Cannot align fields"), "Page reading must not require a valid field alignment")
            assertTrue(readButton(view).isEnabled)
            references(view).clearSelection()
            assertFalse(readButton(view).isEnabled)
            references(view).selectedIndex = 0
            readButton(view).doClick()
            assertFalse(readButton(view).isEnabled)
        }
        await { text(view).contains("Detected page: 2 of 3") }
        edt {
            assertTrue(text(view).contains("Recognized text:\nSykmelding\nSide 2 av 3"))
            assertEquals("Detected page: 2 of 3", status)
            assertTrue(readButton(view).isEnabled)
            assertEquals(Color.BLUE.rgb, incoming.getRGB(100, 200))
            references(view).clearSelection()
            assertFalse(text(view).contains("Detected page:"))
            assertFalse(status.startsWith("Detected page:"))
            assertFalse(readButton(view).isEnabled)
        }
    }

    @Test
    fun `missing invalid conflicting and failed OCR results are visible and can be retried`() {
        var result: String? = "No page label"
        var fail = false
        val view = ImageComparisonView(
            findReferences = { _, crop -> listOf(ReferencesRectangle(100, 200, crop.width, crop.height)) },
            recognizeText = { _, _ -> if (fail) error("Recognition failed") else result },
        )
        loadAndMatch(view)
        for ((ocr, expected) in listOf(
            "No page label" to "No page number found",
            "Side 4 av 3" to "Invalid page number",
            "Side 1 av 3\nSide 2 av 3" to "Conflicting page numbers",
        )) {
            edt {
                result = ocr
                readButton(view).doClick()
            }
            await { readButton(view).isEnabled }
            edt {
                assertTrue(text(view).contains(expected))
                assertTrue(text(view).contains("Recognized text:\n$ocr"))
                assertFalse(text(view).contains("Detected page:"))
            }
        }
        edt {
            result = null
            readButton(view).doClick()
        }
        await { text(view).contains("OCR returned no result") }
        edt {
            fail = true
            readButton(view).doClick()
        }
        await { text(view).contains("Could not read page number: Recognition failed") }
        edt {
            assertTrue(readButton(view).isEnabled)
            fail = false
            result = "Side 1 av 3"
            readButton(view).doClick()
        }
        await { text(view).contains("Detected page: 1 of 3") }
    }

    @Test
    fun `rematching resizing and replacing the document clear the page result`() {
        val view = ImageComparisonView(
            findReferences = { _, crop -> listOf(ReferencesRectangle(100, 200, crop.width, crop.height)) },
            recognizeText = { _, _ -> "Side 2 av 3" },
        )
        loadAndMatch(view)
        val changes: List<() -> Unit> = listOf(
            { applyButton(view).doClick() },
            { button(view, "Match incoming size to template").doClick() },
            { view.resetSecondImage() },
            { view.loadSecond(image(3307, 4677)) },
            { view.loadConfiguration(directory.resolve("configuration.json")) },
        )
        for (change in changes) {
            edt { readButton(view).doClick() }
            await { text(view).contains("Detected page: 2 of 3") }
            edt {
                change()
                assertFalse(text(view).contains("Detected page:"))
                assertFalse(readButton(view).isEnabled)
            }
            if (change !== changes.last()) {
                await { applyButton(view).isEnabled }
                edt {
                    references(view).selectedIndex = 0
                    applyButton(view).doClick()
                }
                await { readButton(view).isEnabled }
            }
        }
    }

    @Test
    fun `late OCR results cannot restore a page number after changing selection`() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val view = ImageComparisonView(
            findReferences = { _, crop -> listOf(ReferencesRectangle(100, 200, crop.width, crop.height)) },
            recognizeText = { _, _ ->
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
                "Side 2 av 3"
            },
        )
        loadAndMatch(view)
        edt { readButton(view).doClick() }
        assertTrue(started.await(10, TimeUnit.SECONDS))
        edt { references(view).clearSelection() }
        release.countDown()
        assertTrue(finished.await(10, TimeUnit.SECONDS))
        Thread.sleep(100)
        edt {
            assertFalse(text(view).contains("Detected page:"))
            assertFalse(readButton(view).isEnabled)
        }
    }

    @Test
    fun `native block OCR reads a synthetic page label from the incoming reference`() {
        val view = ImageComparisonView(
            findReferences = { _, crop -> listOf(ReferencesRectangle(100, 200, crop.width, crop.height)) },
        )
        val incoming = image(3307, 4677).apply {
            val graphics = createGraphics()
            try {
                graphics.color = Color.BLACK
                graphics.font = Font(Font.SANS_SERIF, Font.PLAIN, 36)
                graphics.drawString("Side 2 av 3", 125, 260)
            } finally {
                graphics.dispose()
            }
        }
        loadAndMatch(view, incoming)
        edt { readButton(view).doClick() }
        await { readButton(view).isEnabled }
        edt { assertTrue(text(view).contains("Detected page: 2 of 3"), text(view)) }
    }

    private fun loadAndMatch(view: ImageComparisonView, incoming: BufferedImage = image(3307, 4677)) {
        val file = configuration()
        edt {
            view.loadConfiguration(file)
            view.loadSecond(incoming)
            applyButton(view).doClick()
        }
        await { applyButton(view).isEnabled }
        edt { references(view).selectedIndex = 0 }
        await { readButton(view).isEnabled }
    }

    private fun configuration(): File {
        val template = directory.resolve("template.png")
        val reference = directory.resolve("reference.png")
        ImageIO.write(image(3307, 4677), "png", template)
        ImageIO.write(image(500, 100), "png", reference)
        return directory.resolve("configuration.json").also {
            TemplateFiles.write(it, Template("Page number", "", emptyList(), template.absolutePath,
                referansepunkter = listOf(reference.absolutePath)))
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

    private fun readButton(view: ImageComparisonView) = button(view, "Read page number")
    private fun applyButton(view: ImageComparisonView) = button(view, "Apply reference images")
    private fun button(view: ImageComparisonView, text: String) =
        descendants(view).filterIsInstance<JButton>().single { it.text == text }
    private fun references(view: ImageComparisonView) = descendants(view).filterIsInstance<JList<*>>().last()
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
        error("Timed out waiting for page OCR")
    }

    private fun descendants(container: Container): Sequence<Component> = sequence {
        for (component in container.components) {
            yield(component)
            if (component is Container) yieldAll(descendants(component))
        }
    }
}
