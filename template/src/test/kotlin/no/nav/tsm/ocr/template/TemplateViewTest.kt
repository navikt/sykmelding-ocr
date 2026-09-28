package no.nav.tsm.ocr.template

import org.example.no.nav.tsm.ocr.template.template.Felter
import org.example.no.nav.tsm.ocr.template.template.Template
import org.example.no.nav.tsm.ocr.template.template.TemplateFiles
import org.example.no.nav.tsm.ocr.template.view.TemplateImageCanvas
import org.example.no.nav.tsm.ocr.template.view.TemplateView
import java.awt.Component
import java.awt.Container
import java.awt.Point
import java.awt.Rectangle
import java.awt.event.MouseEvent
import java.awt.image.BufferedImage
import java.nio.file.Files
import javax.imageio.ImageIO
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TemplateViewTest {
    @Test
    fun `regions use original pixels when scaled centered and dragged backwards`() {
        SwingUtilities.invokeAndWait {
            var selected: Rectangle? = null
            val canvas = TemplateImageCanvas({}, { selected = it }).apply {
                image = BufferedImage(400, 200, BufferedImage.TYPE_INT_RGB)
                setSize(232, 232)
                creatingRegion = true
            }
            // Image occupies (16, 66) to (216, 166), at half its original size.
            drag(canvas, Point(91, 106), Point(41, 76))
            assertEquals(Rectangle(50, 20, 100, 60), selected)

            canvas.setSize(432, 432)
            // Resizing changes the display transform, not the stored image coordinates.
            drag(canvas, Point(66, 136), Point(166, 196))
            assertEquals(Rectangle(50, 20, 100, 60), selected)
        }
    }

    @Test
    fun `drag clips to image edges and rejects letterbox starts and clicks`() {
        SwingUtilities.invokeAndWait {
            var selected: Rectangle? = null
            val canvas = TemplateImageCanvas({}, { selected = it }).apply {
                image = BufferedImage(400, 200, BufferedImage.TYPE_INT_RGB)
                setSize(232, 232)
                creatingRegion = true
            }
            drag(canvas, Point(20, 20), Point(100, 100))
            assertNull(selected)
            drag(canvas, Point(50, 100), Point(50, 100))
            assertNull(selected)
            drag(canvas, Point(116, 116), Point(400, 400))
            assertEquals(Rectangle(200, 100, 200, 100), selected)
        }
    }

    @Test
    fun `click selects smallest containing field and creation does not select fields`() {
        SwingUtilities.invokeAndWait {
            var selected: Int? = null
            val canvas = TemplateImageCanvas({ selected = it }, {}).apply {
                image = BufferedImage(400, 200, BufferedImage.TYPE_INT_RGB)
                setSize(232, 232)
                fields = listOf(
                    Felter(0, 0, 400, 200, "Ytre", "outer"),
                    Felter(50, 20, 100, 60, "Fornavn", "name"),
                )
            }
            drag(canvas, Point(60, 90), Point(60, 90))
            assertEquals(1, selected)
            selected = null
            canvas.creatingRegion = true
            drag(canvas, Point(60, 90), Point(60, 90))
            assertNull(selected)
        }
    }

    @Test
    fun `save includes edited field coordinates and retains field identity and references`() {
        val file = Files.createTempFile("template-view-test", ".png").toFile()
        try {
            ImageIO.write(BufferedImage(400, 200, BufferedImage.TYPE_INT_RGB), "png", file)
            SwingUtilities.invokeAndWait {
                val original = Template(
                    "Testmal", listOf("navlogo.png"), "Beskrivelse",
                    listOf(Felter(0, 0, 20, 20, "Fornavn", "name")), file.absolutePath,
                )
                var saved: Template? = null
                val view = TemplateView(original) { saved = it }
                val components = descendants(view).toList()
                val canvas = components.filterIsInstance<TemplateImageCanvas>().single().apply { setSize(232, 232) }
                val list = components.filterIsInstance<JList<*>>().single { it.model.getElementAt(0) is Felter }
                list.selectedIndex = 0
                drag(canvas, Point(41, 76), Point(91, 106))
                components.filterIsInstance<JButton>().single { it.text == "Lagre mal" }.doClick()

                val result = assertNotNull(saved)
                assertEquals(original.copy(felter = listOf(Felter(50, 20, 100, 60, "Fornavn", "name"))), result)
            }
        } finally {
            file.delete()
        }
    }

    @Test
    fun `renaming updates list and canvas and persists only the selected field name`() {
        val directory = Files.createTempDirectory("template-rename-test").toFile()
        try {
            val image = directory.resolve("image.png")
            ImageIO.write(BufferedImage(400, 200, BufferedImage.TYPE_INT_RGB), "png", image)
            val file = directory.resolve("template.json")
            val original = Template(
                "Mal", listOf(directory.resolve("navlogo.png").absolutePath), "Beskrivelse",
                listOf(Felter(50, 20, 100, 60, "Fornavn", "name"), Felter(200, 20, 100, 60, "Etternavn", "surname")),
                image.absolutePath,
            )
            TemplateFiles.write(file, original)
            SwingUtilities.invokeAndWait {
                val view = TemplateView(TemplateFiles.read(file), templateFile = file)
                val components = descendants(view).toList()
                val canvas = components.filterIsInstance<TemplateImageCanvas>().single().apply { setSize(232, 232) }
                val list = components.filterIsInstance<JList<*>>().single { it.model.getElementAt(0) is Felter }
                val edit = components.filterIsInstance<JButton>().single { it.text == "Rediger" }
                assertFalse(edit.isEnabled)

                drag(canvas, Point(60, 90), Point(60, 90))
                assertTrue(edit.isEnabled)
                view.editSelectedField { currentName ->
                    assertEquals("Fornavn", currentName)
                    "  Fullt navn  "
                }

                val expected = original.felter[0].copy(navn = "Fullt navn")
                assertEquals(expected, list.selectedValue)
                assertEquals(listOf(expected, original.felter[1]), canvas.fields)
                assertEquals(0, canvas.selectedField)
                components.filterIsInstance<JButton>().single { it.text == "Lagre mal" }.doClick()
                assertEquals(original.copy(felter = canvas.fields), TemplateFiles.read(file))

                list.clearSelection()
                assertFalse(edit.isEnabled)
                list.selectedIndex = 1
                assertTrue(edit.isEnabled)
                view.editSelectedField { "Familienavn" }
                assertEquals(expected, canvas.fields[0])
                assertEquals(original.felter[1].copy(navn = "Familienavn"), list.selectedValue)
            }
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `rename ignores missing selection and cancellation and reports blank names`() {
        SwingUtilities.invokeAndWait {
            val field = Felter(50, 20, 100, 60, "Fornavn", "name")
            val view = TemplateView(Template("Mal", emptyList(), "", listOf(field), ""))
            val components = descendants(view).toList()
            val list = components.filterIsInstance<JList<*>>().single { it.model.size > 0 }
            val canvas = components.filterIsInstance<TemplateImageCanvas>().single()
            view.editSelectedField { error("No dialog should open without a selected field") }
            list.selectedIndex = 0
            view.editSelectedField { null }
            assertEquals(field, list.selectedValue)
            assertEquals(listOf(field), canvas.fields)

            view.editSelectedField { " \t " }
            assertEquals(field, list.selectedValue)
            assertEquals(listOf(field), canvas.fields)
            assertTrue(components.filterIsInstance<JLabel>().any { it.text == "Feltnavnet kan ikke være tomt." })

            components.filterIsInstance<JButton>().first { it.text == "Fjern" }.doClick()
            assertFalse(components.filterIsInstance<JButton>().single { it.text == "Rediger" }.isEnabled)
        }
    }

    @Test
    fun `opened template loads its image and saves edits to the same file`() {
        val directory = Files.createTempDirectory("template-reopen-test").toFile()
        try {
            val image = directory.resolve("image.png")
            ImageIO.write(BufferedImage(400, 200, BufferedImage.TYPE_INT_RGB), "png", image)
            val file = directory.resolve("template.json")
            val original = Template("Mal", emptyList(), "", listOf(Felter(0, 0, 20, 20, "Fornavn", "name")), image.absolutePath)
            TemplateFiles.write(file, original)
            SwingUtilities.invokeAndWait {
                val view = TemplateView(TemplateFiles.read(file), templateFile = file)
                val components = descendants(view).toList()
                val canvas = components.filterIsInstance<TemplateImageCanvas>().single().apply { setSize(232, 232) }
                assertNotNull(canvas.image)
                components.filterIsInstance<JList<*>>().single { it.model.size > 0 }.selectedIndex = 0
                drag(canvas, Point(41, 76), Point(91, 106))
                components.filterIsInstance<JButton>().single { it.text == "Lagre mal" }.doClick()
            }
            assertEquals(original.copy(felter = listOf(Felter(50, 20, 100, 60, "Fornavn", "name"))), TemplateFiles.read(file))
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun descendants(container: Container): Sequence<Component> = sequence {
        for (component in container.components) {
            yield(component)
            if (component is Container) yieldAll(descendants(component))
        }
    }

    private fun drag(canvas: TemplateImageCanvas, start: Point, end: Point) {
        canvas.dispatchEvent(MouseEvent(canvas, MouseEvent.MOUSE_PRESSED, 0, 0, start.x, start.y, 1, false, MouseEvent.BUTTON1))
        canvas.dispatchEvent(MouseEvent(canvas, MouseEvent.MOUSE_DRAGGED, 0, MouseEvent.BUTTON1_DOWN_MASK, end.x, end.y, 0, false))
        canvas.dispatchEvent(MouseEvent(canvas, MouseEvent.MOUSE_RELEASED, 0, 0, end.x, end.y, 1, false, MouseEvent.BUTTON1))
    }
}
