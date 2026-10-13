package no.nav.tsm.ocr.template

import no.nav.tsm.ocr.template.view.ImageComparisonView
import no.nav.tsm.ocr.template.view.ImagePanel
import no.nav.tsm.sykmelding.ocr.ReferencesRectangle
import org.example.no.nav.tsm.ocr.template.template.Felter
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
import java.util.concurrent.atomic.AtomicInteger
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
    fun `selection locates template crop and apply normalizes before matching without modifying originals`() {
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
        lateinit var originalTemplate: BufferedImage
        edt {
            view.loadConfiguration(file)
            originalTemplate = requireNotNull(view.templatePanal.image)
            assertFalse(applyButton(view).isEnabled)
            view.loadSecond(incoming)
            referenceList(view).selectedIndex = 0
        }
        await(view) { text(view).contains("Template: x: 40 px") }
        edt {
            assertEquals(Color.BLUE.rgb, render(view.templatePanal).getRGB(41, 35))
            assertEquals(Color.WHITE.rgb, render(view.secondPanel).getRGB(80, 35))
            applyButton(view).doClick()
            assertFalse(applyButton(view).isEnabled)
        }
        await(view) {
            text(view).contains("Incoming: x: 80 px") && text(view).contains("Template: x: 80 px")
        }
        edt {
            assertEquals(0, referenceList(view).selectedIndex)
            for (panel in listOf(view.templatePanal, view.secondPanel)) {
                assertEquals(4677, panel.image!!.width)
                assertEquals(3307, panel.image!!.height)
                assertEquals(Color.BLUE.rgb, render(panel).getRGB(81, 35))
            }
            assertEquals(Color.WHITE.rgb, originalTemplate.getRGB(0, 0))
            assertEquals(Color.WHITE.rgb, incoming.getRGB(0, 0))
            assertSame(incoming, view.originalSecondImage)
            view.resetSecondImage()
            assertSame(incoming, view.secondPanel.image)
            assertEquals(Color.WHITE.rgb, render(view.secondPanel).getRGB(80, 35))
            assertEquals(Color.BLUE.rgb, render(view.templatePanal).getRGB(81, 35))
            view.loadReference(image())
            assertFalse(applyButton(view).isEnabled)
            assertEquals(0, referenceList(view).model.size)
        }
    }

    @Test
    fun `all normalized references are applied and selection highlights the corresponding match`() {
        val file = configuration(count = 2)
        val view = ImageComparisonView(findReferences = { _, crop ->
            listOf(ReferencesRectangle(if (crop.width == 234) 40 else 800, 30, crop.width, crop.height))
        })
        edt {
            view.loadConfiguration(file)
            view.loadSecond(image())
            applyButton(view).doClick()
        }
        await { applyButton(view).isEnabled }
        edt {
            assertEquals(Color.BLUE.rgb, render(view.secondPanel).getRGB(40, 35))
            assertEquals(Color.BLUE.rgb, render(view.secondPanel).getRGB(800, 35))
            referenceList(view).selectedIndex = 1
            assertEquals(Color.BLUE.rgb, render(view.secondPanel).getRGB(801, 35))
            assertEquals(Color.WHITE.rgb, render(view.secondPanel).getRGB(42, 35))
        }
        await(view) { text(view).contains("Template: x: 800 px") }
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
        assertTrue(started.await(10, TimeUnit.SECONDS))
        edt { view.loadSecond(image()) }
        release.countDown()
        Thread.sleep(100)
        edt {
            assertTrue(applyButton(view).isEnabled)
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
    fun `library locates a real reference crop in already normalized documents`() {
        val template = image(4677, 3307)
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
        val incoming = image(4677, 3307)
        stamp(incoming, 80)
        val file = configuration(template = template)
        ImageIO.write(crop, "png", directory.resolve("crop-0.png"))
        var status = ""
        val view = ImageComparisonView { status = it }
        edt {
            view.loadConfiguration(file)
            view.loadSecond(incoming)
            referenceList(view).selectedIndex = 0
        }
        await(view) {
            check(!status.startsWith("Could not")) { status }
            text(view).contains("Template: x: 40 px")
        }
        edt { applyButton(view).doClick() }
        await(view) {
            check(!status.startsWith("Could not")) { status }
            text(view).contains("Template: x: 40 px") && text(view).contains("Incoming: x: 80 px")
        }
        edt { assertSame(incoming, view.secondPanel.image) }
    }

    @Test
    fun `manual size matching works independently and apply then normalizes from originals`() {
        val file = configuration()
        var matchedSize = 0 to 0
        var cropSize = 0 to 0
        val view = ImageComparisonView(findReferences = { image, crop ->
            matchedSize = image.width to image.height
            cropSize = crop.width to crop.height
            listOf(ReferencesRectangle(40, 30, crop.width, crop.height))
        })
        val incoming = image(120, 90)
        lateinit var originalTemplate: BufferedImage
        edt {
            assertFalse(button(view, "Match incoming size to template").isEnabled)
            view.loadConfiguration(file)
            assertFalse(button(view, "Match incoming size to template").isEnabled)
            originalTemplate = requireNotNull(view.templatePanal.image)
            view.loadSecond(incoming)
            button(view, "Match incoming size to template").doClick()
        }
        await { button(view, "Match incoming size to template").isEnabled }
        edt {
            assertSame(originalTemplate, view.templatePanal.image)
            assertEquals(200, view.secondPanel.image!!.width)
            assertEquals(100, view.secondPanel.image!!.height)
            applyButton(view).doClick()
        }
        await { applyButton(view).isEnabled }
        edt {
            assertEquals(4677 to 3307, matchedSize)
            assertEquals(234 to 331, cropSize)
            button(view, "Reset image sizes").doClick()
            assertSame(incoming, view.secondPanel.image)
            assertSame(originalTemplate, view.templatePanal.image)
            assertFalse(button(view, "Reset image sizes").isEnabled)
        }
    }

    @Test
    fun `automatic normalization scales fields and crops once and leaves configuration unchanged`() {
        val file = configuration(count = 2)
        val original = TemplateFiles.read(file).copy(felter = listOf(Felter(40, 30, 60, 40, "Name", "name")))
        TemplateFiles.write(file, original)
        val saved = file.readText()
        var matchedSize = 0 to 0
        var cropSize = 0 to 0
        var status = ""
        val view = ImageComparisonView(findReferences = { image, crop ->
            matchedSize = image.width to image.height
            if (crop.width == 234) cropSize = crop.width to crop.height
            listOf(ReferencesRectangle(if (crop.width == 234) 0 else 1000, 0, crop.width, crop.height))
        }, onStatus = { status = it })
        val incoming = image(100, 80)
        edt {
            view.loadConfiguration(file)
            view.loadSecond(incoming)
            applyButton(view).doClick()
        }
        await { applyButton(view).isEnabled }
        lateinit var normalizedTemplate: BufferedImage
        lateinit var normalizedIncoming: BufferedImage
        edt {
            normalizedTemplate = requireNotNull(view.templatePanal.image)
            normalizedIncoming = requireNotNull(view.secondPanel.image)
            assertEquals(4677 to 3307, matchedSize)
            assertEquals(234 to 331, cropSize)
            assertSame(incoming, view.originalSecondImage)
            val list = descendants(view).filterIsInstance<JList<*>>().first()
            assertEquals(Felter(935, 992, 1404, 1323, "Name", "name"), list.model.getElementAt(0))
            list.selectedIndex = 0
            assertTrue(text(view).contains("x: 935 px"))
            button(view, "Apply configuration").doClick()
            assertFalse(status.contains("dimensions differ"))
            assertTrue(status.contains("Configuration applied"))
            assertEquals(Color.RED.rgb, render(view.secondPanel).getRGB(935, 1100))
            applyButton(view).doClick()
            assertEquals(Color.WHITE.rgb, render(view.secondPanel).getRGB(935, 1100))
        }
        await { applyButton(view).isEnabled }
        edt {
            assertSame(normalizedTemplate, view.templatePanal.image)
            assertSame(normalizedIncoming, view.secondPanel.image)
            assertEquals(234 to 331, cropSize)
            button(view, "Reset image sizes").doClick()
            assertEquals(original.felter.first(), descendants(view).filterIsInstance<JList<*>>().first().model.getElementAt(0))
            assertSame(incoming, view.secondPanel.image)
            applyButton(view).doClick()
        }
        await { applyButton(view).isEnabled }
        edt {
            assertEquals(4677 to 3307, matchedSize)
            assertEquals(234 to 331, cropSize)
            view.loadSecond(image(80, 70))
            applyButton(view).doClick()
        }
        await { applyButton(view).isEnabled }
        edt {
            assertEquals(4677, view.secondPanel.image!!.width)
            assertEquals(3307, view.secondPanel.image!!.height)
            assertEquals(234 to 331, cropSize)
        }
        assertEquals(saved, file.readText())
    }

    @Test
    fun `portrait normalization matches scaled crops and canceled normalization never starts matching`() {
        val calls = AtomicInteger()
        val file = configuration(template = image(100, 200))
        val view = ImageComparisonView(findReferences = { image, crop ->
            calls.incrementAndGet()
            assertEquals(3307, image.width)
            assertEquals(4677, image.height)
            assertEquals(331, crop.width)
            assertEquals(234, crop.height)
            listOf(ReferencesRectangle(0, 0, crop.width, crop.height))
        })
        val incoming = image(80, 120)
        edt {
            view.loadConfiguration(file)
            view.loadSecond(incoming)
            applyButton(view).doClick()
        }
        await { applyButton(view).isEnabled }
        edt {
            assertEquals(2, calls.get())
            assertEquals(3307, view.templatePanal.image!!.width)
            assertEquals(4677, view.templatePanal.image!!.height)
            button(view, "Reset image sizes").doClick()
            applyButton(view).doClick()
            view.loadSecond(incoming)
        }
        Thread.sleep(500)
        edt {
            assertEquals(2, calls.get())
            assertSame(incoming, view.secondPanel.image)
            assertEquals(100, view.templatePanal.image!!.width)
            assertTrue(applyButton(view).isEnabled)
        }
    }

    private fun configuration(count: Int = 1, template: BufferedImage = image()): File {
        ImageIO.write(template, "png", directory.resolve("template.png"))
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

    private fun render(panel: ImagePanel): BufferedImage {
        val source = requireNotNull(panel.image)
        panel.setSize(source.width, source.height)
        return BufferedImage(source.width, source.height, BufferedImage.TYPE_INT_RGB).apply {
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

    private fun applyButton(view: ImageComparisonView) = button(view, "Apply reference images")

    private fun button(view: ImageComparisonView, text: String) =
        descendants(view).filterIsInstance<JButton>().single { it.text == text }

    private fun text(view: ImageComparisonView) =
        descendants(view).filterIsInstance<JTextArea>().joinToString("\n") { it.text }

    private fun edt(action: () -> Unit) = SwingUtilities.invokeAndWait(action)

    private fun await(view: ImageComparisonView? = null, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
        while (System.nanoTime() < deadline) {
            var ready = false
            edt { ready = condition() }
            if (ready) return
            Thread.sleep(20)
        }
        var details = ""
        edt { if (view != null) details = text(view) }
        error("Timed out waiting for reference matching: $details")
    }

    private fun descendants(container: Container): Sequence<Component> = sequence {
        for (component in container.components) {
            yield(component)
            if (component is Container) yieldAll(descendants(component))
        }
    }
}
