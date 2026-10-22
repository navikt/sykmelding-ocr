package no.nav.tsm.ocr.template

import no.nav.tsm.ocr.template.view.ImageComparisonView
import no.nav.tsm.ocr.template.view.ImagePanel
import no.nav.tsm.sykmelding.utils.FieldTransform
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
import javax.swing.JCheckBox
import javax.swing.JList
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class AlignedFieldsTest {
    @TempDir
    lateinit var directory: File
    private val field = Felter(40, 30, 60, 40, "Name", "name")

    @Test
    fun `reported reference rectangles apply configuration with unequal horizontal and vertical scales`() {
        val templateMatches = listOf(
            ReferencesRectangle(62, 4186, 1179, 80),
            ReferencesRectangle(424, 173, 547, 243),
            ReferencesRectangle(57, 0, 146, 142),
            ReferencesRectangle(53, 619, 1863, 115),
            ReferencesRectangle(2146, 491, 1060, 261),
        )
        val incomingMatches = listOf(
            ReferencesRectangle(99, 4230, 1179, 80),
            ReferencesRectangle(460, 317, 547, 243),
            ReferencesRectangle(101, 150, 146, 142),
            ReferencesRectangle(86, 754, 1863, 115),
            ReferencesRectangle(2158, 629, 1060, 261),
        )
        val templateFile = directory.resolve("portrait.png")
        ImageIO.write(image(3307, 4677), "png", templateFile)
        val references = templateMatches.mapIndexed { index, reference ->
            directory.resolve("reference-$index.png").also {
                ImageIO.write(image(reference.width, reference.height), "png", it)
            }.absolutePath
        }
        val file = directory.resolve("portrait.json")
        TemplateFiles.write(file, Template("Portrait", "", listOf(field), templateFile.absolutePath, referansepunkter = references))
        var status = ""
        val view = ImageComparisonView(findReferences = { page, crop ->
            val matches = if (page.getRGB(0, 0) == Color.BLACK.rgb) incomingMatches else templateMatches
            listOf(matches.single { it.width == crop.width })
        }, onStatus = { status = it })
        val incoming = image(3307, 4677).apply { setRGB(0, 0, Color.BLACK.rgb) }
        edt {
            view.loadConfiguration(file)
            view.loadSecond(incoming)
            button(view, "Convert both to A4 at 400 DPI").doClick()
        }
        await { button(view, "Convert both to A4 at 400 DPI").isEnabled }
        edt { button(view, "Match incoming size to template").doClick() }
        await { button(view, "Match incoming size to template").isEnabled }
        edt { button(view, "Apply reference images").doClick() }
        await { status.startsWith("Reference alignment ready") }
        edt {
            assertTrue(status.contains("5/5 consistent pairs"))
            assertTrue(status.contains("Affine alignment"))
            button(view, "Apply configuration").doClick()
            assertTrue(status.startsWith("Configuration applied"))
            assertSame(incoming, view.secondPanel.image)
            assertEquals(Color.RED.rgb, render(view.templatePanal).getRGB(40, 50))
            assertEquals(Color.WHITE.rgb, render(view.secondPanel).getRGB(40, 50))
            assertTrue(render(view.secondPanel).getRGB(85, 200) != Color.WHITE.rgb)
        }
    }

    @Test
    fun `template searches are reused across applies documents and incoming normalization but not configuration changes`() {
        val templateCalls = AtomicInteger()
        val incomingCalls = AtomicInteger()
        var status = ""
        val file = configuration()
        val view = ImageComparisonView(findReferences = { page, crop ->
            if (page.getRGB(0, 0) == Color.BLACK.rgb) incomingCalls.incrementAndGet() else templateCalls.incrementAndGet()
            match(page, crop)
        }, onStatus = { status = it })
        edt {
            view.loadConfiguration(file)
            view.loadSecond(image().apply { setRGB(0, 0, Color.BLACK.rgb) })
            descendants(view).filterIsInstance<JList<*>>().last().selectedIndex = 0
        }
        await { button(view, "Apply configuration").isEnabled && templateCalls.get() == 1 }
        edt { button(view, "Apply reference images").doClick() }
        await { status.startsWith("Reference alignment ready") }
        assertEquals(2, templateCalls.get(), "The selected reference must not be searched again")
        assertEquals(2, incomingCalls.get())
        edt { button(view, "Apply reference images").doClick() }
        await { status.startsWith("Reference alignment ready") }
        assertEquals(2, templateCalls.get())
        assertEquals(4, incomingCalls.get(), "Incoming matches should still be refreshed")
        edt {
            view.loadSecond(image(2338, 1653).apply {
                createGraphics().let { graphics ->
                    graphics.color = Color.BLACK
                    graphics.fillRect(0, 0, 10, 10)
                    graphics.dispose()
                }
            })
            button(view, "Apply reference images").doClick()
        }
        await { status.startsWith("Reference alignment ready") }
        assertEquals(2, templateCalls.get(), "Only the incoming image changed size")
        assertEquals(6, incomingCalls.get())
        edt {
            view.loadConfiguration(file)
            button(view, "Apply reference images").doClick()
        }
        await { status.startsWith("Reference alignment ready") }
        assertEquals(4, templateCalls.get(), "Loading a configuration invalidates template matches")
        assertEquals(8, incomingCalls.get())
    }

    @Test
    fun `both pages match all references before adjusted fields can be applied selected and toggled`() {
        val calls = AtomicInteger()
        var status = ""
        val file = configuration()
        val view = ImageComparisonView(findReferences = { page, crop ->
            assertFalse(SwingUtilities.isEventDispatchThread())
            calls.incrementAndGet()
            match(page, crop)
        }, onStatus = { status = it })
        val incoming = image().apply { setRGB(0, 0, Color.BLACK.rgb) }
        edt {
            view.loadConfiguration(file)
            view.loadSecond(incoming)
            button(view, "Apply configuration").doClick()
            assertTrue(status.startsWith("Cannot apply configuration"))
            button(view, "Apply reference images").doClick()
            assertFalse(button(view, "Apply configuration").isEnabled)
        }
        await { status.startsWith("Reference alignment ready") }
        edt {
            assertEquals(4, calls.get(), "Each reference must be matched on each page without selecting it")
            assertTrue(status.contains("Only two pairs"))
            assertSame(incoming, view.secondPanel.image, "No image warping")
            assertEquals(Color.WHITE.rgb, render(view.secondPanel).getRGB(60, 60))
            button(view, "Apply configuration").doClick()
            assertEquals(Color.RED.rgb, render(view.templatePanal).getRGB(40, 50))
            assertEquals(Color.RED.rgb, render(view.secondPanel).getRGB(60, 60))
            assertEquals(Color.WHITE.rgb, render(view.secondPanel).getRGB(40, 50))
            val fields = descendants(view).filterIsInstance<JList<*>>().first()
            fields.selectedIndex = 0
            assertEquals(field, fields.selectedValue)
            assertEquals(Color.RED.rgb, render(view.secondPanel).getRGB(61, 60))
            assertTrue(render(view.secondPanel).getRGB(80, 60) != Color.WHITE.rgb)
            assertEquals(Color.RED.rgb, render(view.secondPanel, 2).getRGB(120, 120))
            val toggle = descendants(view).filterIsInstance<JCheckBox>().single()
            toggle.doClick()
            assertEquals(Color.WHITE.rgb, render(view.secondPanel).getRGB(60, 60))
            assertEquals(Color.WHITE.rgb, render(view.templatePanal).getRGB(40, 50))
            toggle.doClick()
            assertEquals(Color.RED.rgb, render(view.secondPanel).getRGB(60, 60))
            assertEquals(Color.WHITE.rgb, incoming.getRGB(60, 60))
        }
    }

    @Test
    fun `paired matching rotates and scales field polygons without changing template boxes`() {
        val file = configuration()
        var status = ""
        val view = ImageComparisonView(findReferences = { page, crop ->
            val x = if (crop.width == 10) 500 else 2000
            val y = if (crop.width == 10) 500 else 1500
            val incoming = page.getRGB(0, 0) == Color.BLACK.rgb
            listOf(ReferencesRectangle(
                (if (incoming) (1.1 * x - 0.1 * y + 20).toInt() else x) - crop.width / 2,
                (if (incoming) (0.1 * x + 1.1 * y + 10).toInt() else y) - crop.height / 2,
                crop.width, crop.height,
            ))
        }, onStatus = { status = it })
        edt {
            view.loadConfiguration(file)
            view.loadSecond(image().apply { setRGB(0, 0, Color.BLACK.rgb) })
            button(view, "Apply reference images").doClick()
        }
        await { status.startsWith("Reference alignment ready") }
        edt {
            button(view, "Apply configuration").doClick()
            assertEquals(Color.RED.rgb, render(view.templatePanal).getRGB(40, 50))
            assertEquals(Color.RED.rgb, render(view.secondPanel).getRGB(59, 69))
            assertEquals(Color.WHITE.rgb, render(view.secondPanel).getRGB(57, 69))
            assertEquals(Color.RED.rgb, render(view.secondPanel, 2).getRGB(118, 138))
        }
    }

    @Test
    fun `missing or degenerate rematches remove applied fields and report an explicit failure`() {
        val file = configuration()
        var mode = "valid"
        var status = ""
        val view = ImageComparisonView(findReferences = { page, crop ->
            when (mode) {
                "missing" -> if (crop.width == 12) emptyList() else match(page, crop)
                "duplicate" -> listOf(ReferencesRectangle(500, 500, crop.width, crop.height))
                else -> match(page, crop)
            }
        }, onStatus = { status = it })
        edt {
            view.loadConfiguration(file)
            view.loadSecond(image().apply { setRGB(0, 0, Color.BLACK.rgb) })
        }
        for (failure in listOf("missing", "duplicate")) {
            edt {
                mode = "valid"
                button(view, "Apply reference images").doClick()
            }
            await { status.startsWith("Reference alignment ready") }
            edt {
                button(view, "Apply configuration").doClick()
                assertEquals(Color.RED.rgb, render(view.secondPanel).getRGB(60, 60))
                mode = failure
                button(view, "Apply reference images").doClick()
                assertEquals(Color.WHITE.rgb, render(view.secondPanel).getRGB(60, 60))
            }
            await { status.startsWith("Cannot align fields") }
            edt {
                button(view, "Apply configuration").doClick()
                assertTrue(status.startsWith("Cannot apply configuration"))
                assertEquals(Color.WHITE.rgb, render(view.secondPanel).getRGB(60, 60))
            }
        }
    }

    @Test
    fun `changed document configuration resize and reset invalidate an applied alignment`() {
        val file = configuration()
        var status = ""
        val view = ImageComparisonView(findReferences = ::match, onStatus = { status = it })
        edt {
            view.loadConfiguration(file)
            view.loadSecond(image().apply { setRGB(0, 0, Color.BLACK.rgb) })
        }
        val changes: List<() -> Unit> = listOf(
            { view.loadSecond(image().apply { setRGB(0, 0, Color.BLACK.rgb) }) },
            { view.loadConfiguration(file) },
            { view.resetSecondImage() },
            { button(view, "Convert both to A4 at 400 DPI").doClick() },
            { button(view, "Match incoming size to template").doClick() },
        )
        for (change in changes) {
            edt { button(view, "Apply reference images").doClick() }
            await { status.startsWith("Reference alignment ready") }
            edt {
                button(view, "Apply configuration").doClick()
                assertEquals(Color.RED.rgb, render(view.secondPanel).getRGB(60, 60))
                change()
                assertEquals(Color.WHITE.rgb, render(view.secondPanel).getRGB(60, 60))
            }
            await { button(view, "Convert both to A4 at 400 DPI").isEnabled }
            edt {
                button(view, "Apply configuration").doClick()
                assertEquals(Color.WHITE.rgb, render(view.secondPanel).getRGB(60, 60))
            }
        }
    }

    @Test
    fun `late paired matching results cannot restore alignment after replacing configuration`() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val calls = AtomicInteger()
        val file = configuration()
        val view = ImageComparisonView(findReferences = { page, crop ->
            if (calls.incrementAndGet() == 3) {
                started.countDown()
                var waiting = true
                while (waiting) {
                    try {
                        check(release.await(10, TimeUnit.SECONDS))
                        waiting = false
                    } catch (_: InterruptedException) {
                        // Simulate a native matcher which does not honor interruption.
                    }
                }
                finished.countDown()
            }
            match(page, crop)
        })
        edt {
            view.loadConfiguration(file)
            view.loadSecond(image().apply { setRGB(0, 0, Color.BLACK.rgb) })
            button(view, "Apply reference images").doClick()
        }
        assertTrue(started.await(10, TimeUnit.SECONDS))
        edt { view.loadConfiguration(file) }
        release.countDown()
        assertTrue(finished.await(10, TimeUnit.SECONDS))
        Thread.sleep(100)
        edt {
            button(view, "Apply configuration").doClick()
            assertEquals(Color.WHITE.rgb, render(view.secondPanel).getRGB(60, 60))
        }
    }

    @Test
    fun `rotated field overlay follows polygon edges rather than bounding rectangle at any display scale`() {
        edt {
            val panel = ImagePanel()
            panel.load(image(200, 100))
            val transform = FieldTransform(0.8, 0.6, 40.0, 0.0)
            panel.showFieldRegions(listOf(transform.region(field)), 0)
            for (scale in listOf(1, 2)) {
                val rendered = render(panel, scale)
                assertEquals(Color.RED.rgb, rendered.getRGB(42 * scale, 64 * scale), "Rotated left edge")
                assertEquals(Color.WHITE.rgb, rendered.getRGB(30 * scale, 60 * scale), "No bounding-box left edge")
                assertTrue(rendered.getRGB(80 * scale, 80 * scale) != Color.WHITE.rgb, "Selected polygon fill")
            }
        }
    }

    private fun match(page: BufferedImage, crop: BufferedImage): List<ReferencesRectangle> {
        val shifted = page.getRGB(0, 0) == Color.BLACK.rgb
        return listOf(ReferencesRectangle(
            (if (crop.width == 10) 500 else 2000) + if (shifted) 20 else 0,
            (if (crop.width == 10) 500 else 1600) + if (shifted) 10 else 0,
            crop.width, crop.height,
        ))
    }

    private fun configuration(): File {
        ImageIO.write(image(), "png", directory.resolve("template.png"))
        val references = listOf(10, 12).map { width ->
            directory.resolve("crop-$width.png").also { ImageIO.write(image(width, 10), "png", it) }.absolutePath
        }
        return directory.resolve("config.json").also {
            TemplateFiles.write(it, Template("Test", "", listOf(field), directory.resolve("template.png").absolutePath,
                referansepunkter = references))
        }
    }

    private fun image(width: Int = 4677, height: Int = 3307) =
        BufferedImage(width, height, BufferedImage.TYPE_INT_RGB).apply {
            createGraphics().let { graphics ->
                graphics.color = Color.WHITE
                graphics.fillRect(0, 0, width, height)
                graphics.dispose()
            }
        }

    private fun render(panel: ImagePanel, scale: Int = 1): BufferedImage {
        val source = requireNotNull(panel.image)
        panel.setSize(source.width * scale, source.height * scale)
        return BufferedImage(200 * scale, 300 * scale, BufferedImage.TYPE_INT_RGB).apply {
            createGraphics().let { graphics -> panel.paint(graphics); graphics.dispose() }
        }
    }

    private fun button(view: ImageComparisonView, text: String) =
        descendants(view).filterIsInstance<JButton>().single { it.text == text }

    private fun descendants(container: Container): Sequence<Component> = sequence {
        for (component in container.components) {
            yield(component)
            if (component is Container) yieldAll(descendants(component))
        }
    }

    private fun edt(action: () -> Unit) = SwingUtilities.invokeAndWait(action)

    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
        while (System.nanoTime() < deadline) {
            var ready = false
            edt { ready = condition() }
            if (ready) return
            Thread.sleep(20)
        }
        error("Timed out waiting for alignment")
    }
}
