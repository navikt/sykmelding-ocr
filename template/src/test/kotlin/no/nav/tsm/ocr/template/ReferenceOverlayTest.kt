package no.nav.tsm.ocr.template

import no.nav.tsm.ocr.template.view.ImageComparisonView
import no.nav.tsm.ocr.template.view.ImagePanel
import no.nav.tsm.sykmelding.ocr.Ocr
import no.nav.tsm.sykmelding.ocr.ReferencesRectangle
import no.nav.tsm.sykmelding.ocr.SykmeldingOcr
import org.example.no.nav.tsm.ocr.template.template.Template
import org.example.no.nav.tsm.ocr.template.template.TemplateFiles
import org.junit.jupiter.api.io.TempDir
import java.awt.Color
import java.awt.Component
import java.awt.Container
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
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ReferenceOverlayTest {
    @TempDir
    lateinit var directory: File

    @Test
    fun `selection locates template crop and apply locates incoming crop without moving fields`() {
        val file = configuration()
        val view = ImageComparisonView(findReferences = { image, crop ->
            assertEquals(BufferedImage.TYPE_3BYTE_BGR, image.type)
            assertEquals(BufferedImage.TYPE_3BYTE_BGR, crop.type)
            image.setRGB(0, 0, Color.RED.rgb)
            val x = if (image.width == 200) 40 else 80
            listOf(
                ReferencesRectangle(x, 30, crop.width, crop.height),
                ReferencesRectangle(x, 30, image.width, image.height),
            )
        })
        val incoming = image(240)
        edt {
            view.loadConfiguration(file)
            assertFalse(applyButton(view).isEnabled)
            view.loadSecond(incoming)
            assertTrue(applyButton(view).isEnabled)
            referenceList(view).selectedIndex = 0
        }
        await(view) { text(view).contains("Template: x: 40 px") }
        edt {
            assertEquals(Color.BLUE.rgb, render(view.templatePanal).getRGB(41, 35))
            assertEquals(Color.WHITE.rgb, render(view.secondPanel, 240).getRGB(80, 35))
            applyButton(view).doClick()
            assertFalse(applyButton(view).isEnabled)
        }
        await { text(view).contains("Incoming: x: 80 px") }
        edt {
            assertTrue(applyButton(view).isEnabled)
            assertEquals(Color.BLUE.rgb, render(view.secondPanel, 240).getRGB(81, 35))
            assertEquals(Color.WHITE.rgb, view.templatePanal.image!!.getRGB(0, 0))
            assertEquals(Color.WHITE.rgb, incoming.getRGB(0, 0))
            assertSame(incoming, view.secondPanel.image)
            view.resetSecondImage()
            assertEquals(Color.WHITE.rgb, render(view.secondPanel, 240).getRGB(80, 35))
            assertEquals(Color.BLUE.rgb, render(view.templatePanal, 400, 200).getRGB(81, 70))
            view.loadReference(image())
            assertFalse(applyButton(view).isEnabled)
            assertEquals(0, referenceList(view).model.size)
            assertEquals(Color.WHITE.rgb, render(view.templatePanal).getRGB(40, 35))
        }
    }

    @Test
    fun `all reference images are applied and selection highlights the corresponding match`() {
        val file = configuration(count = 2)
        val view = ImageComparisonView(findReferences = { _, crop ->
            listOf(ReferencesRectangle(if (crop.width == 10) 40 else 80, 30, crop.width, crop.height))
        })
        edt {
            view.loadConfiguration(file)
            view.loadSecond(image())
            applyButton(view).doClick()
        }
        await { applyButton(view).isEnabled }
        edt {
            assertEquals(Color.BLUE.rgb, render(view.secondPanel).getRGB(40, 35))
            assertEquals(Color.BLUE.rgb, render(view.secondPanel).getRGB(80, 35))
            referenceList(view).selectedIndex = 1
            assertEquals(Color.BLUE.rgb, render(view.secondPanel).getRGB(81, 35))
            assertEquals(Color.WHITE.rgb, render(view.secondPanel).getRGB(42, 35))
        }
        await { text(view).contains("Template: x: 80 px") }
    }

    @Test
    fun `stale background matches do not reappear after a document change`() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val view = ImageComparisonView(findReferences = { _, crop ->
            started.countDown()
            release.await(5, TimeUnit.SECONDS)
            listOf(ReferencesRectangle(40, 30, crop.width, crop.height))
        })
        val file = configuration()
        edt {
            view.loadConfiguration(file)
            view.loadSecond(image())
            applyButton(view).doClick()
        }
        assertTrue(started.await(5, TimeUnit.SECONDS))
        edt {
            view.loadSecond(image())
            assertTrue(applyButton(view).isEnabled)
        }
        release.countDown()
        Thread.sleep(100)
        edt {
            assertEquals(Color.WHITE.rgb, render(view.secondPanel).getRGB(40, 35))
        }
    }

    @Test
    fun `invalid reference configuration preserves the previous state`() {
        val file = configuration()
        edt {
            val view = ImageComparisonView()
            view.loadConfiguration(file)
            val previous = view.templatePanal.image
            val invalid = directory.resolve("invalid.json")
            TemplateFiles.write(invalid, Template(
                "Invalid", listOf(directory.resolve("missing.png").absolutePath), "", emptyList(),
                directory.resolve("template.png").absolutePath,
            ))
            assertFails { view.loadConfiguration(invalid) }
            assertSame(previous, view.templatePanal.image)
            assertEquals(1, referenceList(view).model.size)
        }
    }

    @Test
    fun `matching errors restore apply control and report failure`() {
        val file = configuration()
        var status = ""
        val view = ImageComparisonView(
            findReferences = { _, _ -> error("Matching failed") },
            onStatus = { status = it },
        )
        edt {
            view.loadConfiguration(file)
            view.loadSecond(image())
            applyButton(view).doClick()
        }
        await { status.contains("Could not match reference images: Matching failed") }
        edt {
            assertTrue(applyButton(view).isEnabled)
            assertEquals(Color.WHITE.rgb, render(view.secondPanel).getRGB(40, 35))
        }
    }

    @Test
    fun `library locates a real reference crop in both documents`() {
        val template = image()
        val crop = BufferedImage(12, 10, BufferedImage.TYPE_INT_RGB).apply {
            for (y in 0 until height) for (x in 0 until width) {
                setRGB(x, y, Color((x * 47 + y * 31) % 256, (x * 13 + y * 59) % 256, (x * 71 + y * 7) % 256).rgb)
            }
        }
        fun stamp(image: BufferedImage, x: Int) {
            val graphics = image.createGraphics()
            try {
                graphics.drawImage(crop, x, 30, null)
            } finally {
                graphics.dispose()
            }
        }
        stamp(template, 40)
        val incoming = image()
        stamp(incoming, 80)
        ImageIO.write(template, "png", directory.resolve("template.png"))
        ImageIO.write(crop, "png", directory.resolve("crop.png"))
        val file = directory.resolve("real.json")
        TemplateFiles.write(file, Template(
            "Real", listOf(directory.resolve("crop.png").absolutePath), "", emptyList(),
            directory.resolve("template.png").absolutePath,
        ))
        val matcher = SykmeldingOcr(Ocr())
        val view = ImageComparisonView(findReferences = { image, reference -> matcher.findReferences(image, reference) })
        edt {
            view.loadConfiguration(file)
            view.loadSecond(incoming)
            referenceList(view).selectedIndex = 0
            applyButton(view).doClick()
        }
        await(view) {
            val details = text(view)
            details.contains("Template: x: 40 px") && details.contains("Incoming: x: 80 px")
        }
    }

    private fun configuration(count: Int = 1): File {
        ImageIO.write(image(), "png", directory.resolve("template.png"))
        val paths = (0 until count).map { index ->
            "crop-$index.png".also { ImageIO.write(image(10 + index, 10), "png", directory.resolve(it)) }
        }
        return directory.resolve("config.json").also {
            TemplateFiles.write(it, Template(
                "References", paths.map { path -> directory.resolve(path).absolutePath }, "", emptyList(),
                directory.resolve("template.png").absolutePath,
            ))
        }
    }

    private fun image(width: Int = 200, height: Int = 100) =
        BufferedImage(width, height, BufferedImage.TYPE_INT_RGB).apply {
            val graphics = createGraphics()
            try {
                graphics.color = Color.WHITE
                graphics.fillRect(0, 0, width, height)
            } finally {
                graphics.dispose()
            }
        }

    private fun render(panel: ImagePanel, width: Int = 200, height: Int = 100): BufferedImage {
        panel.setSize(width, height)
        return BufferedImage(width, height, BufferedImage.TYPE_INT_RGB).apply {
            val graphics = createGraphics()
            try {
                panel.paint(graphics)
            } finally {
                graphics.dispose()
            }
        }
    }

    private fun referenceList(view: ImageComparisonView) =
        descendants(view).filterIsInstance<JList<*>>().last()

    private fun applyButton(view: ImageComparisonView) =
        descendants(view).filterIsInstance<JButton>().single { it.text == "Apply reference images" }

    private fun text(view: ImageComparisonView) =
        descendants(view).filterIsInstance<JTextArea>().joinToString("\n") { it.text }

    private fun edt(action: () -> Unit) = SwingUtilities.invokeAndWait(action)

    private fun await(view: ImageComparisonView? = null, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (System.nanoTime() < deadline) {
            var ready = false
            edt { ready = condition() }
            if (ready) return
            Thread.sleep(20)
        }
        var details = ""
        edt {
            if (view != null) details = text(view) + "\n" +
                descendants(view).filterIsInstance<JList<*>>().joinToString { "size=${it.model.size}, selected=${it.selectedIndex}" }
        }
        error("Timed out waiting for reference matching: $details")
    }

    private fun descendants(container: Container): Sequence<Component> = sequence {
        for (component in container.components) {
            yield(component)
            if (component is Container) yieldAll(descendants(component))
        }
    }
}
