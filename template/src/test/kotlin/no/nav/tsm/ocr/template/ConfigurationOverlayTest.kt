package no.nav.tsm.ocr.template

import no.nav.tsm.ocr.template.view.ImageComparisonView
import no.nav.tsm.ocr.template.view.ImagePanel
import org.example.no.nav.tsm.ocr.template.template.Felter
import org.example.no.nav.tsm.ocr.template.template.Template
import org.example.no.nav.tsm.ocr.template.template.TemplateFiles
import org.junit.jupiter.api.io.TempDir
import java.awt.Color
import java.awt.Component
import java.awt.Container
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JList
import javax.swing.JTextArea
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ConfigurationOverlayTest {
    @TempDir
    lateinit var directory: File

    private val field = Felter(40, 30, 60, 40, "First name", "name")

    @Test
    fun `configuration loads reference and sidebar while incoming overlays require apply`() {
        val file = configuration()
        SwingUtilities.invokeAndWait {
            var status = ""
            val view = ImageComparisonView { status = it }
            val incoming = image()
            view.loadSecond(incoming)
            val controls = descendants(view).toList()
            val apply = controls.filterIsInstance<JButton>().single()
            assertFalse(apply.isEnabled)

            view.loadConfiguration(file)
            assertTrue(apply.isEnabled)
            assertSame(incoming, view.originalSecondImage)
            assertSame(incoming, view.secondPanel.image)
            assertTrue(controls.filterIsInstance<JTextArea>().any { it.text.contains("Test template") })
            assertEquals(Color.RED.rgb, render(view.templatePanal).getRGB(40, 50))
            assertEquals(Color.WHITE.rgb, render(view.secondPanel).getRGB(40, 50))

            apply.doClick()
            assertEquals(Color.RED.rgb, render(view.secondPanel).getRGB(40, 50))
            assertTrue(status.contains("document not aligned"))
            assertEquals(Color.WHITE.rgb, incoming.getRGB(40, 50))

            val list = controls.filterIsInstance<JList<*>>().single()
            list.selectedIndex = 0
            assertTrue(controls.filterIsInstance<JTextArea>().any {
                it.text.contains("x: 40 px") && it.text.contains("Width: 60 px") && it.text.contains("ID: name")
            })
            for (panel in listOf(view.templatePanal, view.secondPanel)) {
                val rendered = render(panel)
                assertEquals(Color.RED.rgb, rendered.getRGB(41, 50), "Selected outline should be thicker")
                assertTrue(rendered.getRGB(60, 50) != Color.WHITE.rgb, "Selected region should be tinted")
            }

            val toggle = controls.filterIsInstance<JCheckBox>().single()
            toggle.doClick()
            assertEquals(Color.WHITE.rgb, render(view.templatePanal).getRGB(40, 50))
            assertEquals(Color.WHITE.rgb, render(view.secondPanel).getRGB(40, 50))
            toggle.doClick()
            assertEquals(Color.RED.rgb, render(view.secondPanel).getRGB(40, 50))
        }
    }

    @Test
    fun `changing documents configurations or reference clears stale applied overlays`() {
        val file = configuration()
        SwingUtilities.invokeAndWait {
            val view = ImageComparisonView()
            val apply = descendants(view).filterIsInstance<JButton>().single()
            view.loadConfiguration(file)
            assertFalse(apply.isEnabled)
            view.loadSecond(image())
            apply.doClick()
            view.loadSecond(image())
            assertEquals(Color.WHITE.rgb, render(view.secondPanel).getRGB(40, 50))
            assertTrue(apply.isEnabled)

            apply.doClick()
            view.loadConfiguration(file)
            assertEquals(Color.WHITE.rgb, render(view.secondPanel).getRGB(40, 50))
            apply.doClick()
            view.loadReference(image())
            assertFalse(apply.isEnabled)
            assertEquals(0, descendants(view).filterIsInstance<JList<*>>().single().model.size)
            assertEquals(Color.WHITE.rgb, render(view.templatePanal).getRGB(40, 50))
            assertEquals(Color.WHITE.rgb, render(view.secondPanel).getRGB(40, 50))
        }
    }

    @Test
    fun `overlays scale with display size but not with document dimensions`() {
        val file = configuration()
        SwingUtilities.invokeAndWait {
            var status = ""
            val view = ImageComparisonView { status = it }
            view.loadConfiguration(file)
            view.loadSecond(image(400, 200))
            descendants(view).filterIsInstance<JButton>().single().doClick()
            assertTrue(status.contains("dimensions differ"))
            assertTrue(status.contains("not been rescaled"))
            assertEquals(Color.RED.rgb, render(view.secondPanel, 400, 200).getRGB(40, 50))
            assertEquals(Color.RED.rgb, render(view.secondPanel, 800, 400).getRGB(80, 100))
            assertEquals(Color.RED.rgb, render(view.secondPanel, 400, 400).getRGB(40, 50))
            assertEquals(Color.WHITE.rgb, render(view.secondPanel, 400, 200).getRGB(160, 50))

            view.loadSecond(image(80, 50))
            descendants(view).filterIsInstance<JButton>().single().doClick()
            assertTrue(status.contains("1 field(s) extend beyond"))
        }
    }

    @Test
    fun `invalid configuration leaves previous configuration and overlays intact`() {
        val file = configuration()
        SwingUtilities.invokeAndWait {
            val view = ImageComparisonView()
            view.loadConfiguration(file)
            view.loadSecond(image())
            descendants(view).filterIsInstance<JButton>().single().doClick()
            for (invalidFields in listOf(
                listOf(field.copy(x = -1)),
                listOf(field.copy(bredde = 0)),
                listOf(field.copy(x = Int.MAX_VALUE)),
                listOf(field.copy(id = "")),
                listOf(field.copy(navn = "")),
                listOf(field, field),
            )) {
                val invalid = configuration(invalidFields)
                assertFails { view.loadConfiguration(invalid) }
                assertEquals(Color.RED.rgb, render(view.secondPanel).getRGB(40, 50))
                assertEquals(field, descendants(view).filterIsInstance<JList<*>>().single().model.getElementAt(0))
            }
            val invalid = directory.resolve("broken.json")
            invalid.writeText("not json")
            assertFails { view.loadConfiguration(invalid) }
            val missingImage = configuration()
            directory.resolve("reference.png").delete()
            assertFails { view.loadConfiguration(missingImage) }
            assertEquals(Color.RED.rgb, render(view.secondPanel).getRGB(40, 50))
        }
    }

    @Test
    fun `empty configuration cannot be applied`() {
        val file = configuration(emptyList())
        SwingUtilities.invokeAndWait {
            var status = ""
            val view = ImageComparisonView { status = it }
            view.loadSecond(image())
            view.loadConfiguration(file)
            assertFalse(descendants(view).filterIsInstance<JButton>().single().isEnabled)
            assertFalse(descendants(view).filterIsInstance<JCheckBox>().single().isEnabled)
            assertTrue(status.contains("no fields"))
        }
    }

    private fun configuration(fields: List<Felter> = listOf(field)): File {
        val reference = directory.resolve("reference.png")
        ImageIO.write(image(), "png", reference)
        return directory.resolve("mal.json").also {
            TemplateFiles.write(it, Template("Test template", emptyList(), "Description", fields, reference.absolutePath))
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

    private fun descendants(container: Container): Sequence<Component> = sequence {
        for (component in container.components) {
            yield(component)
            if (component is Container) yieldAll(descendants(component))
        }
    }
}
