package no.nav.tsm.ocr.template

import no.nav.tsm.ocr.template.view.ImageComparisonView
import no.nav.tsm.ocr.template.view.isSykmeldingMarker
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
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SykmeldingDetectionTest {
    @TempDir
    lateinit var directory: File

    @Test
    fun `only a single uppercase A passes and unrecognized text is not guessed`() {
        for (text in listOf("A", " A\n", "\tA\r\n")) assertTrue(isSykmeldingMarker(text))
        for (text in listOf("B", "C", "D", " B\n")) assertFalse(isSykmeldingMarker(text))
        for (text in listOf("", " ", "a", "AB", "A B", "A\nA", "4", "A.", "text A")) {
            assertFailsWith<IllegalArgumentException>(text) { isSykmeldingMarker(text) }
        }
    }

    @Test
    fun `selected incoming crop is recognized with block mode and results clear on rematch`() {
        var recognized: String? = "A\n"
        val modes = mutableListOf<PSM_MODE>()
        var status = ""
        val view = ImageComparisonView(
            findReferences = { _, _ -> listOf(ReferencesRectangle(101, 150, 146, 142)) },
            recognizeText = { crop, mode ->
                assertFalse(SwingUtilities.isEventDispatchThread())
                modes.add(mode)
                assertEquals(146, crop.width)
                assertEquals(142, crop.height)
                if (mode == PSM_MODE.PSM_SINGLE_BLOCK) assertEquals(Color.BLUE.rgb, crop.getRGB(0, 0))
                crop.setRGB(0, 0, Color.RED.rgb)
                recognized
            },
            onStatus = { status = it },
        )
        val file = configuration()
        val incoming = image(3307, 4677).apply { setRGB(101, 150, Color.BLUE.rgb) }
        edt {
            assertFalse(detectButton(view).isEnabled)
            view.loadConfiguration(file)
            references(view).selectedIndex = 0
            assertFalse(detectButton(view).isEnabled)
            view.loadSecond(incoming)
            assertFalse(detectButton(view).isEnabled)
            applyButton(view).doClick()
        }
        await { detectButton(view).isEnabled }
        for ((result, expected) in listOf(
            "A\n" to "Sykmelding detected: A.",
            "B" to "Not a sykmelding: detected B",
            "C" to "Not a sykmelding: detected C",
            "D" to "Not a sykmelding: detected D",
            "AB" to "Document type undetermined",
            "" to "Document type undetermined",
        )) {
            edt {
                recognized = result
                modes.clear()
                detectButton(view).doClick()
                assertFalse(detectButton(view).isEnabled)
                assertFalse(button(view, "Read page number").isEnabled)
            }
            await { detectButton(view).isEnabled }
            edt {
                assertTrue(text(view).contains(expected), text(view))
                assertTrue(text(view).contains("Recognized text:\n$result"))
                assertEquals(Color.BLUE.rgb, incoming.getRGB(101, 150))
                assertEquals(
                    if (result.isBlank()) listOf(PSM_MODE.PSM_SINGLE_BLOCK, PSM_MODE.PSM_SINGLE_CHAR)
                    else listOf(PSM_MODE.PSM_SINGLE_BLOCK),
                    modes,
                )
            }
        }
        edt {
            recognized = null
            detectButton(view).doClick()
        }
        await { text(view).contains("Could not detect sykmelding: OCR returned no result") }
        edt {
            recognized = "A"
            detectButton(view).doClick()
        }
        await { text(view).contains("Sykmelding detected: A.") }
        edt {
            applyButton(view).doClick()
            assertFalse(text(view).contains("Sykmelding detected:"))
            assertFalse(detectButton(view).isEnabled)
        }
        await { detectButton(view).isEnabled }
        edt {
            detectButton(view).doClick()
        }
        await { text(view).contains("Sykmelding detected: A.") }
        edt {
            references(view).clearSelection()
            assertFalse(detectButton(view).isEnabled)
            assertFalse(text(view).contains("Sykmelding detected:"))
            assertFalse(status.startsWith("Sykmelding detected:"))
        }
    }

    @Test
    fun `native block OCR distinguishes A from B C and D in the matched incoming location`() {
        val file = configuration()
        val view = ImageComparisonView(
            findReferences = { _, _ -> listOf(ReferencesRectangle(101, 150, 146, 142)) },
        )
        edt { view.loadConfiguration(file) }
        for (letter in listOf("A", "B", "C", "D")) {
            val incoming = image(3307, 4677).apply {
                val graphics = createGraphics()
                try {
                    graphics.color = Color.BLACK
                    graphics.font = Font(Font.SANS_SERIF, Font.BOLD, 100)
                    graphics.drawString(letter, 130, 260)
                } finally {
                    graphics.dispose()
                }
            }
            edt {
                view.loadSecond(incoming)
                applyButton(view).doClick()
            }
            await { applyButton(view).isEnabled }
            edt {
                references(view).selectedIndex = 0
                detectButton(view).doClick()
            }
            await { detectButton(view).isEnabled }
            edt {
                val expected = if (letter == "A") "Sykmelding detected: A." else "Not a sykmelding: detected $letter"
                assertTrue(text(view).contains(expected), text(view))
            }
        }
    }

    @Test
    fun `late detection cannot restore a positive result after replacing the incoming document`() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val view = ImageComparisonView(
            findReferences = { _, _ -> listOf(ReferencesRectangle(101, 150, 146, 142)) },
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
                "A"
            },
        )
        val file = configuration()
        edt {
            view.loadConfiguration(file)
            view.loadSecond(image(3307, 4677))
            applyButton(view).doClick()
        }
        await { applyButton(view).isEnabled }
        edt {
            references(view).selectedIndex = 0
            detectButton(view).doClick()
        }
        assertTrue(started.await(10, TimeUnit.SECONDS))
        edt { view.loadSecond(image(3307, 4677)) }
        release.countDown()
        assertTrue(finished.await(10, TimeUnit.SECONDS))
        Thread.sleep(100)
        edt {
            assertFalse(text(view).contains("Sykmelding detected:"))
            assertFalse(detectButton(view).isEnabled)
        }
    }

    private fun configuration(): File {
        val template = directory.resolve("template.png")
        val reference = directory.resolve("referansepunkt-5.png")
        ImageIO.write(image(3307, 4677), "png", template)
        ImageIO.write(image(146, 142), "png", reference)
        return directory.resolve("configuration.json").also {
            TemplateFiles.write(it, Template("Marker", listOf(reference.absolutePath), "", emptyList(), template.absolutePath))
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

    private fun detectButton(view: ImageComparisonView) = button(view, "Detect sykmelding")
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
        error("Timed out waiting for sykmelding detection")
    }

    private fun descendants(container: Container): Sequence<Component> = sequence {
        for (component in container.components) {
            yield(component)
            if (component is Container) yieldAll(descendants(component))
        }
    }
}
