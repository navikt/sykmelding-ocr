package no.nav.tsm.ocr.template

import no.nav.tsm.sykmelding.utils.RetreivalMethod
import no.nav.tsm.sykmelding.utils.RetreivalMode
import no.nav.tsm.sykmelding.utils.GridEdit
import no.nav.tsm.sykmelding.utils.GridEditTool
import no.nav.tsm.sykmelding.utils.GridSettings
import org.example.no.nav.tsm.ocr.template.template.Felter
import org.example.no.nav.tsm.ocr.template.template.Template
import org.example.no.nav.tsm.ocr.template.template.TemplateFiles
import org.example.no.nav.tsm.ocr.template.template.TemplateLibrary
import org.example.no.nav.tsm.ocr.template.template.PageRotation
import no.nav.tsm.ocr.template.view.TemplateImageCanvas
import no.nav.tsm.ocr.template.view.TemplateView
import org.example.no.nav.tsm.ocr.template.template.FieldCatalog
import java.awt.Component
import java.awt.Color
import java.awt.Container
import java.awt.Point
import java.awt.Rectangle
import java.awt.event.MouseEvent
import java.awt.image.BufferedImage
import java.io.File
import java.nio.file.Files
import javax.imageio.ImageIO
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JCheckBox
import javax.swing.JTree
import javax.swing.SwingUtilities
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.TreePath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TemplateViewTest {
    @Test
    fun `page rotation corrects the image before saving without changing its source`() {
        val directory = Files.createTempDirectory("rotate-editor-test").toFile()
        try {
            val source = BufferedImage(200, 300, BufferedImage.TYPE_INT_RGB).apply {
                createGraphics().let { g ->
                    g.color = Color.WHITE; g.fillRect(0, 0, width, height)
                    g.color = Color.BLUE; g.fillRect(70, 130, 40, 40)
                    g.dispose()
                }
            }
            val image = directory.resolve("source.png")
            ImageIO.write(source, "png", image)
            val json = directory.resolve("page.json")
            val rotation = PageRotation(1, -2.0)
            val expected = rotation.apply(source)
            SwingUtilities.invokeAndWait {
                val view = TemplateView(Template("Rotation", "", emptyList(), image.absolutePath), json)
                assertNotNull(descendants(view).filterIsInstance<JButton>().single { it.text == "Rotate + straighten" })
                assertTrue(view.applyPageRotation(rotation))
                val canvas = descendants(view).filterIsInstance<TemplateImageCanvas>().single()
                assertEquals(300 to 200, canvas.image!!.let { it.width to it.height })
                descendants(view).filterIsInstance<JButton>().single { it.text == "Lagre mal" }.doClick()
                val saved = ImageIO.read(File(TemplateFiles.read(json).templateBilde))
                assertTrue(expected.getRGB(0, 0, 300, 200, null, 0, 300).contentEquals(
                    saved.getRGB(0, 0, 300, 200, null, 0, 300)))
                assertTrue(view.applyPageRotation(PageRotation()))
                assertTrue(canvas.image!!.getRGB(0, 0, 300, 200, null, 0, 300).contentEquals(
                    expected.getRGB(0, 0, 300, 200, null, 0, 300)))
            }
            assertEquals(200 to 300, ImageIO.read(image).let { it.width to it.height })
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `rotation does not invalidate assigned fields or references`() {
        val directory = Files.createTempDirectory("rotation-regions-test").toFile()
        try {
            val image = directory.resolve("template.png")
            ImageIO.write(BufferedImage(80, 60, BufferedImage.TYPE_INT_RGB), "png", image)
            val templates = listOf(
                Template("Fields", "", listOf(Felter(10, 10, 20, 20, "Field", "field")), image.absolutePath),
                Template("References", "", emptyList(), image.absolutePath, referansepunkter = listOf("reference.png")),
            )
            SwingUtilities.invokeAndWait {
                templates.forEach { template ->
                    val view = TemplateView(template)
                    val canvas = descendants(view).filterIsInstance<TemplateImageCanvas>().single()
                    val before = canvas.image
                    assertFalse(view.applyPageRotation(PageRotation(1)))
                    assertTrue(before === canvas.image)
                    assertTrue(descendants(view).filterIsInstance<JLabel>().any { it.text.startsWith("Rotate before assigning") })
                }
            }
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `editor saves reference crops and retains references after reopening`() {
        val directory = Files.createTempDirectory("reference-editor-test").toFile()
        try {
            val image = directory.resolve("template.png")
            ImageIO.write(BufferedImage(80, 60, BufferedImage.TYPE_INT_RGB), "png", image)
            val json = directory.resolve("page.json")
            val crop = directory.resolve("reference.png")
            SwingUtilities.invokeAndWait {
                val view = TemplateView(Template("References", "", emptyList(), image.absolutePath), json)
                view.addReference(Rectangle(10, 15, 30, 25), crop)
                descendants(view).filterIsInstance<JButton>().single { it.text == "Lagre mal" }.doClick()
                assertEquals(listOf(crop.absolutePath), TemplateFiles.read(json).referansepunkter)
                val reopened = TemplateView(TemplateFiles.read(json), json)
                descendants(reopened).filterIsInstance<JButton>().single { it.text == "Lagre mal" }.doClick()
                assertEquals(listOf(crop.absolutePath), TemplateFiles.read(json).referansepunkter)
                descendants(reopened).filterIsInstance<JList<*>>().single {
                    it.model.size == 1 && it.model.getElementAt(0) == crop.absolutePath
                }.selectedIndex = 0
                descendants(reopened).filterIsInstance<JButton>().single { it.text == "Remove reference point" }.doClick()
                descendants(reopened).filterIsInstance<JButton>().single { it.text == "Lagre mal" }.doClick()
                assertTrue(TemplateFiles.read(json).referansepunkter.isEmpty())
            }
            assertEquals(30, ImageIO.read(crop).width)
            assertEquals(25, ImageIO.read(crop).height)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `open editor retains unsaved image edits and saves to renumbered page`() {
        val directory = Files.createTempDirectory("renumber-editor-test").toFile()
        try {
            val library = TemplateLibrary(directory)
            val group = library.create("03.2019")
            val page = library.addPage(group)
            val image = page.parentFile.resolve("template.png")
            val source = BufferedImage(40, 40, BufferedImage.TYPE_INT_RGB)
            ImageIO.write(source, "png", image)
            TemplateFiles.write(page, TemplateFiles.read(page).copy(templateBilde = image.absolutePath))
            SwingUtilities.invokeAndWait {
                val view = TemplateView(TemplateFiles.read(page), page)
                val components = descendants(view).toList()
                val canvas = components.filterIsInstance<TemplateImageCanvas>().single()
                canvas.image = BufferedImage(40, 40, BufferedImage.TYPE_INT_RGB).apply { setRGB(10, 10, Color.BLUE.rgb) }
                val moved = library.renumberPage(group, page, 5)
                view.relocateTemplateFile(moved)
                components.filterIsInstance<JButton>().single { it.text == "Lagre mal" }.doClick()
                val saved = TemplateFiles.read(moved)
                assertEquals("page-5", saved.navn)
                assertEquals(moved.parentFile.resolve("template.png").absolutePath, saved.templateBilde)
                assertEquals(Color.BLUE.rgb, ImageIO.read(File(saved.templateBilde)).getRGB(10, 10))
                assertFalse(page.parentFile.exists())
            }
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `saving overwrites template png without deleting existing image files`() {
        val directory = Files.createTempDirectory("template-image-save-test").toFile()
        try {
            val sourceFile = directory.resolve("source.png")
            val source = BufferedImage(80, 60, BufferedImage.TYPE_INT_RGB).apply {
                setRGB(20, 20, Color.RED.rgb)
            }
            ImageIO.write(source, "png", sourceFile)
            val folder = directory.resolve("template").apply { mkdir() }
            val json = folder.resolve("template.json")
            val png = folder.resolve("template.png")
            val legacy = folder.resolve("template-edited-12345.png")
            val reference = folder.resolve("reference.png")
            val unrelated = folder.resolve("template-edited-not-generated.png")
            listOf(legacy, reference, unrelated).forEach { ImageIO.write(source, "png", it) }
            SwingUtilities.invokeAndWait {
                val view = TemplateView(Template("Save", "", emptyList(), sourceFile.absolutePath), json)
                val components = descendants(view).toList()
                val save = components.filterIsInstance<JButton>().single { it.text == "Lagre mal" }
                val canvas = components.filterIsInstance<TemplateImageCanvas>().single()

                save.doClick()
                assertEquals(png.absolutePath, TemplateFiles.read(json).templateBilde)
                assertEquals(Color.RED.rgb, ImageIO.read(png).getRGB(20, 20))
                assertTrue(legacy.exists())
                assertTrue(reference.exists())
                assertTrue(unrelated.exists())

                canvas.image = BufferedImage(80, 60, BufferedImage.TYPE_INT_RGB).apply {
                    setRGB(20, 20, Color.BLUE.rgb)
                }
                save.doClick()
                save.doClick()
                assertEquals(png.absolutePath, TemplateFiles.read(json).templateBilde)
                assertEquals(Color.BLUE.rgb, ImageIO.read(png).getRGB(20, 20))
                assertEquals(Color.RED.rgb, ImageIO.read(sourceFile).getRGB(20, 20))
                assertEquals(setOf("template.json", "template.png", legacy.name, "reference.png", unrelated.name),
                    folder.listFiles()!!.map { it.name }.toSet())
            }
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `clear fields keeps assignments and borders supports undo and persists checkbox type`() {
        val directory = Files.createTempDirectory("clear-fields-test").toFile()
        try {
            val imageFile = directory.resolve("original.png")
            val source = BufferedImage(400, 200, BufferedImage.TYPE_INT_RGB).apply {
                createGraphics().let { g ->
                    g.color = Color.WHITE
                    g.fillRect(0, 0, width, height)
                    g.color = Color.BLACK
                    g.drawRect(20, 20, 160, 160)
                    g.drawRect(220, 20, 160, 160)
                    g.fillRect(60, 60, 10, 30)
                    g.fillRect(260, 60, 10, 30)
                    g.dispose()
                }
            }
            ImageIO.write(source, "png", imageFile)
            val file = directory.resolve("template.json")
            val first = Felter(20, 20, 161, 161, "First", "first")
            val second = Felter(220, 20, 161, 161, "Second", "second")
            SwingUtilities.invokeAndWait {
                val view = TemplateView(Template("Clear", "", listOf(first, second), imageFile.absolutePath),
                    file, FieldCatalog(directory.resolve("fields.json"))
                )
                val components = descendants(view).toList()
                val canvas = components.filterIsInstance<TemplateImageCanvas>().single()
                fun click(text: String) = components.filterIsInstance<JButton>().single { it.text == text }.doClick()
                val list = components.filterIsInstance<JList<*>>().single {
                    it.model.size > 0 && it.model.getElementAt(0) is Felter
                }
                list.selectedIndex = 0
                click("Mark as checkbox")
                val checkbox = first.copy(type = org.example.no.nav.tsm.ocr.template.template.FieldType.CHECKBOX)
                assertEquals(listOf(checkbox, second), canvas.fields)
                click("Remove selected area")
                assertEquals(Color.WHITE.rgb, canvas.image!!.getRGB(65, 65))
                assertEquals(Color.BLACK.rgb, canvas.image!!.getRGB(265, 65))
                assertEquals(Color.BLACK.rgb, canvas.image!!.getRGB(20, 80))
                assertEquals(listOf(checkbox, second), canvas.fields)
                click("Undo removal")
                assertEquals(Color.BLACK.rgb, canvas.image!!.getRGB(65, 65))
                click("Clear fields")
                assertEquals(Color.WHITE.rgb, canvas.image!!.getRGB(65, 65))
                assertEquals(Color.WHITE.rgb, canvas.image!!.getRGB(265, 65))
                assertEquals(Color.BLACK.rgb, canvas.image!!.getRGB(220, 80))
                click("Undo removal")
                assertEquals(Color.BLACK.rgb, canvas.image!!.getRGB(65, 65))
                assertEquals(Color.BLACK.rgb, canvas.image!!.getRGB(265, 65))
                click("Clear fields")
                click("Lagre mal")
                assertEquals(listOf(checkbox, second), TemplateFiles.read(file).felter)
                assertEquals(Color.BLACK.rgb, ImageIO.read(imageFile).getRGB(65, 65))
            }
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `line tools snap endpoints to crossing rules at half scale`() {
        SwingUtilities.invokeAndWait {
            val view = TemplateView(null)
            val components = descendants(view).toList()
            val canvas = components.filterIsInstance<TemplateImageCanvas>().single().apply {
                image = BufferedImage(400, 200, BufferedImage.TYPE_INT_RGB).apply {
                    createGraphics().let { g ->
                        g.color = Color.WHITE
                        g.fillRect(0, 0, width, height)
                        g.color = Color.BLACK
                        g.drawRect(40, 40, 300, 120)
                        g.dispose()
                    }
                }
                setSize(232, 232)
            }
            val tools = components.filterIsInstance<JComboBox<*>>().single { it.getItemAt(0) is GridEditTool }
            val draw = components.filterIsInstance<JButton>().single { it.text == "Draw" }
            tools.selectedItem = GridEditTool.HORIZONTAL
            draw.doClick()
            drag(canvas, Point(40, 116), Point(182, 116))
            assertEquals(GridEdit(GridEditTool.HORIZONTAL, 40, 100, 340, 100), canvas.gridEdits.last())
            assertEquals(2, canvas.countours.size)
            tools.selectedItem = GridEditTool.VERTICAL
            draw.doClick()
            drag(canvas, Point(116, 90), Point(116, 142))
            assertEquals(GridEdit(GridEditTool.VERTICAL, 200, 40, 200, 160), canvas.gridEdits.last())
            assertEquals(4, canvas.countours.size)
        }
    }

    @Test
    fun `cells can be fields and cleared to a saved copy with undo`() {
        val directory = Files.createTempDirectory("template-regions-test").toFile()
        try {
            val imageFile = directory.resolve("original.png")
            val source = BufferedImage(400, 200, BufferedImage.TYPE_INT_RGB).apply {
                createGraphics().let { g ->
                    g.color = Color.WHITE
                    g.fillRect(0, 0, width, height)
                    g.color = Color.BLACK
                    g.drawRect(20, 20, 160, 160)
                    g.fillRect(60, 60, 10, 80)
                    g.fillRect(80, 60, 10, 80)
                    g.dispose()
                }
            }
            ImageIO.write(source, "png", imageFile)
            val file = directory.resolve("template.json")
            val template = Template("Regions", "", emptyList(), imageFile.absolutePath)
            SwingUtilities.invokeAndWait {
                val view = TemplateView(template, file)
                val components = descendants(view).toList()
                fun click(label: String) = components.filterIsInstance<JButton>().single { it.text == label }.doClick()
                val canvas = components.filterIsInstance<TemplateImageCanvas>().single().apply { setSize(432, 232) }
                click("Cells")
                // Clicking a cell selects it, even away from the tree.
                drag(canvas, Point(116, 116), Point(116, 116))
                view.createFieldFromCell { "Field" }
                assertEquals(1, canvas.fields.size)
                click("Remove selected area")
                assertEquals(Color.WHITE.rgb, canvas.image!!.getRGB(65, 65))
                click("Undo removal")
                assertEquals(Color.BLACK.rgb, canvas.image!!.getRGB(65, 65))
                // Arbitrary removal uses original pixels at half scale and does not snap.
                canvas.setSize(232, 232)
                click("Remove area…")
                drag(canvas, Point(41, 91), Point(66, 141))
                assertEquals(Color.WHITE.rgb, canvas.image!!.getRGB(65, 65))
                click("Lagre mal")
                val saved = TemplateFiles.read(file)
                assertEquals(directory.resolve("template.png").absolutePath, saved.templateBilde)
                assertEquals(Color.WHITE.rgb, ImageIO.read(java.io.File(saved.templateBilde)).getRGB(65, 65))
                assertEquals(Color.BLACK.rgb, ImageIO.read(imageFile).getRGB(65, 65))
                val reopened = TemplateView(saved)
                val reopenedCanvas = descendants(reopened).filterIsInstance<TemplateImageCanvas>().single()
                assertEquals(saved.felter, reopenedCanvas.fields)
                assertEquals(Color.WHITE.rgb, reopenedCanvas.image!!.getRGB(65, 65))
            }
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `manual cells snap at different zoom levels and can be drawn freely`() {
        SwingUtilities.invokeAndWait {
            val view = TemplateView(null)
            val components = descendants(view).toList()
            val canvas = components.filterIsInstance<TemplateImageCanvas>().single()
            canvas.image = BufferedImage(400, 200, BufferedImage.TYPE_INT_RGB).apply {
                createGraphics().let { g ->
                    g.color = Color.WHITE
                    g.fillRect(0, 0, width, height)
                    g.color = Color.BLACK
                    g.drawRect(40, 40, 300, 120)
                    g.dispose()
                }
            }
            val tools = components.filterIsInstance<JComboBox<*>>().single { it.getItemAt(0) is GridEditTool }
            tools.selectedItem = GridEditTool.CELL
            val draw = components.filterIsInstance<JButton>().single { it.text == "Draw" }
            draw.doClick()
            // At half size, 4 screen pixels from the rules (8 original pixels), backwards.
            canvas.setSize(232, 232)
            drag(canvas, Point(182, 142), Point(40, 90))
            assertEquals(GridEdit(GridEditTool.CELL, 340, 160, 40, 40), canvas.gridEdits.last())
            assertEquals(1, canvas.countours.size)
            // At full size, the same screen-space offset also snaps.
            canvas.setSize(432, 232)
            drag(canvas, Point(60, 60), Point(352, 172))
            assertEquals(GridEdit(GridEditTool.CELL, 40, 40, 340, 160), canvas.gridEdits.last())
            assertEquals(1, canvas.countours.size)
            components.filterIsInstance<JCheckBox>().single { it.text == "Snap cells and lines to grid" }.doClick()
            drag(canvas, Point(60, 60), Point(352, 172))
            assertEquals(GridEdit(GridEditTool.CELL, 44, 44, 336, 156), canvas.gridEdits.last())
            // Replacing the image clears cached snap targets.
            canvas.image = BufferedImage(400, 200, BufferedImage.TYPE_INT_RGB).apply {
                createGraphics().let { g ->
                    g.color = Color.WHITE
                    g.fillRect(0, 0, width, height)
                    g.dispose()
                }
            }
            components.filterIsInstance<JCheckBox>().single { it.text == "Snap cells and lines to grid" }.doClick()
            draw.doClick()
            drag(canvas, Point(60, 60), Point(352, 172))
            assertEquals(GridEdit(GridEditTool.CELL, 44, 44, 336, 156), canvas.gridEdits.single())
        }
    }

    @Test
    fun `text groups button requires an image and displays selectable boxes`() {
        SwingUtilities.invokeAndWait {
            val view = TemplateView(null)
            val components = descendants(view).toList()
            val button = components.filterIsInstance<JButton>().single { it.text == "Detect text groups" }
            button.doClick()
            assertTrue(components.filterIsInstance<JLabel>().any { it.text == "Importer et malbilde først." })
            val canvas = components.filterIsInstance<TemplateImageCanvas>().single()
            canvas.image = BufferedImage(400, 200, BufferedImage.TYPE_INT_RGB).apply {
                val graphics = createGraphics()
                try {
                    graphics.color = Color.WHITE
                    graphics.fillRect(0, 0, width, height)
                    graphics.color = Color.BLACK
                    graphics.font = java.awt.Font(java.awt.Font.SANS_SERIF, java.awt.Font.PLAIN, 20)
                    graphics.drawString("Sample text", 30, 50)
                } finally {
                    graphics.dispose()
                }
            }
            canvas.corners = listOf(Point(10, 10))
            canvas.selectedCorner = 0
            canvas.gridEditTool = GridEditTool.CELL
            button.doClick()

            val tree = components.filterIsInstance<JTree>().single()
            val root = tree.model.root as DefaultMutableTreeNode
            assertEquals(1, canvas.countours.size)
            assertEquals("Text groups (1)", root.userObject)
            assertEquals(1, root.childCount)
            assertEquals(0, canvas.selectedContour)
            assertTrue(canvas.corners.isEmpty())
            assertNull(canvas.selectedCorner)
            assertNull(canvas.gridEditTool)
            components.filterIsInstance<JCheckBox>().single { it.text == "Show all" }.doClick()
            assertFalse(canvas.showAllContours)
            tree.selectionPath = TreePath(root.path)
            assertNull(canvas.selectedContour)
            val minimumWidth = components.filterIsInstance<JLabel>().single { it.text == "Min. group width:" }
                .labelFor as javax.swing.JSpinner
            // Typed values must be committed when applying settings.
            (minimumWidth.editor as javax.swing.JSpinner.DefaultEditor).textField.text = "1000"
            components.filterIsInstance<JButton>().single { it.text == "Apply / detect" }.doClick()
            assertTrue(canvas.countours.isEmpty())
            assertEquals("Text groups (0)", (tree.model.root as DefaultMutableTreeNode).userObject)
            val removeLines = components.filterIsInstance<JCheckBox>().single { it.text == "Remove form lines" }
            val lineLength = components.filterIsInstance<JLabel>().single { it.text == "Min. line length:" }
                .labelFor as javax.swing.JSpinner
            removeLines.doClick()
            assertFalse(lineLength.isEnabled)
            components.filterIsInstance<JButton>().single { it.text == "Reset defaults" }.doClick()
            assertEquals(0, minimumWidth.value)
            assertTrue(removeLines.isSelected)
            assertTrue(lineLength.isEnabled)
            assertEquals(1, canvas.countours.size)
        }
    }

    @Test
    fun `drawing grid lines recalculates cells and supports undo reset and escape`() {
        SwingUtilities.invokeAndWait {
            val originalField = Felter(0, 0, 20, 20, "Original", "original")
            val view = TemplateView(Template("Test", "", listOf(originalField), ""))
            val components = descendants(view).toList()
            val canvas = components.filterIsInstance<TemplateImageCanvas>().single().apply {
                image = BufferedImage(400, 200, BufferedImage.TYPE_INT_RGB).apply {
                    createGraphics().let { g ->
                        g.color = Color.WHITE
                        g.fillRect(0, 0, width, height)
                        g.dispose()
                    }
                }
                setSize(232, 232)
            }
            fun click(label: String) = components.filterIsInstance<JButton>().single { it.text == label }.doClick()
            val tools = components.filterIsInstance<JComboBox<*>>().single { it.getItemAt(0) is GridEditTool }
            tools.selectedItem = GridEditTool.CELL
            click("Draw")
            // Half-size image centered at (16, 66). Draw the border backwards.
            drag(canvas, Point(206, 156), Point(26, 76))
            assertEquals(1, canvas.countours.size)
            assertEquals(GridEdit(GridEditTool.CELL, 380, 180, 20, 20), canvas.gridEdits.single())
            tools.selectedItem = GridEditTool.VERTICAL
            click("Draw")
            drag(canvas, Point(116, 76), Point(116, 156))
            assertEquals(2, canvas.countours.size)
            val root = components.filterIsInstance<JTree>().single().model.root as DefaultMutableTreeNode
            assertEquals("Cells (2)", root.userObject)
            assertEquals(listOf(originalField), canvas.fields)
            click("Undo grid edit")
            assertEquals(1, canvas.countours.size)
            click("Draw")
            view.actionMap.get("cancelSelection").actionPerformed(null)
            assertNull(canvas.gridEditTool)
            click("Reset grid")
            assertEquals(0, canvas.countours.size)
            assertTrue(canvas.gridEdits.isEmpty())
        }
    }

    @Test
    fun `grid repairs survive saving reopening and are cleared for a replacement image`() {
        val directory = Files.createTempDirectory("template-grid-test").toFile()
        try {
            val image = directory.resolve("image.png")
            val blank = BufferedImage(200, 120, BufferedImage.TYPE_INT_RGB).apply {
                createGraphics().let { g ->
                    g.color = Color.WHITE
                    g.fillRect(0, 0, width, height)
                    g.dispose()
                }
            }
            ImageIO.write(blank, "png", image)
            val file = directory.resolve("template.json")
            val original = Template("Grid", "", emptyList(), image.absolutePath,
                GridSettings(minimumLineLength = 25, gapSize = 2),
                listOf(GridEdit(GridEditTool.CELL, 10, 10, 190, 110)))
            TemplateFiles.write(file, original)
            SwingUtilities.invokeAndWait {
                val view = TemplateView(TemplateFiles.read(file), templateFile = file)
                val components = descendants(view).toList()
                val canvas = components.filterIsInstance<TemplateImageCanvas>().single()
                assertEquals(1, canvas.countours.size)
                assertEquals(original.gridEdits, canvas.gridEdits)
                components.filterIsInstance<JButton>().single { it.text == "Lagre mal" }.doClick()
                assertEquals(original.copy(templateBilde = directory.resolve("template.png").absolutePath), TemplateFiles.read(file))
                canvas.image = blank
                assertTrue(canvas.gridEdits.isEmpty())
                assertEquals(0, canvas.showCells().size)
            }
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `Harris button stays visible when the editor is narrow`() {
        SwingUtilities.invokeAndWait {
            val view = TemplateView(null).apply { setSize(1000, 540) }
            layoutRecursively(view)
            val canvas = descendants(view).filterIsInstance<TemplateImageCanvas>().single()
            val preview = canvas.parent
            val button = descendants(view).filterIsInstance<JButton>().single { it.text == "Harris corners" }
            val position = SwingUtilities.convertRectangle(button.parent, button.bounds, preview)
            assertTrue(button.width > 0 && button.height > 0)
            assertTrue(position.x >= 0 && position.x + position.width <= preview.width)
            assertTrue(position.y >= 0 && position.y + position.height <= preview.height)
            for (label in listOf("Draw", "Undo grid edit", "Reset grid", "Cell → field")) {
                val control = descendants(view).filterIsInstance<JButton>().single { it.text == label }
                val panel = control.parent.parent
                val location = SwingUtilities.convertRectangle(control.parent, control.bounds, panel)
                assertTrue(location.x >= 0 && location.x + location.width <= panel.width, "$label must fit horizontally: $location in ${panel.size}")
                assertTrue(location.y >= 0 && location.y + location.height <= panel.height, "$label must fit vertically")
            }
        }
    }

    @Test
    fun `Harris button displays selectable corners and clears previous boxes`() {
        SwingUtilities.invokeAndWait {
            val view = TemplateView(null)
            val components = descendants(view).toList()
            val canvas = components.filterIsInstance<TemplateImageCanvas>().single()
            canvas.image = BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB).apply {
                val graphics = createGraphics()
                try {
                    graphics.color = Color.WHITE
                    graphics.fillRect(0, 0, width, height)
                    graphics.color = Color.BLACK
                    graphics.drawRect(12, 12, 39, 39)
                } finally {
                    graphics.dispose()
                }
            }
            components.filterIsInstance<JButton>().single { it.text == "Contours" }.doClick()
            assertTrue(canvas.countours.isNotEmpty())
            components.filterIsInstance<JButton>().single { it.text == "Harris corners" }.doClick()

            val tree = components.filterIsInstance<JTree>().single()
            val root = tree.model.root as DefaultMutableTreeNode
            assertTrue(canvas.countours.isEmpty())
            assertTrue(canvas.corners.size >= 4)
            assertEquals("Corners (${canvas.corners.size})", root.userObject)
            assertEquals(canvas.corners.size, root.childCount)
            assertNotNull(canvas.selectedCorner)

            components.filterIsInstance<JCheckBox>().single { it.text == "Show all" }.doClick()
            assertFalse(canvas.showAllContours)
            tree.selectionPath = TreePath(root.path)
            assertNull(canvas.selectedCorner)
        }
    }

    @Test
    fun `contour controls select retrieval mode and approximation method`() {
        SwingUtilities.invokeAndWait {
            val view = TemplateView(null)
            val components = descendants(view).toList()
            val canvas = components.filterIsInstance<TemplateImageCanvas>().single()
            canvas.image = BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB).apply {
                val graphics = createGraphics()
                try {
                    graphics.color = Color.WHITE
                    graphics.fillRect(0, 0, width, height)
                    graphics.color = Color.BLACK
                    graphics.fillRect(4, 4, 24, 24)
                    graphics.color = Color.WHITE
                    graphics.fillRect(10, 10, 12, 12)
                } finally {
                    graphics.dispose()
                }
            }
            val combos = components.filterIsInstance<JComboBox<*>>()
            val mode = combos.single { it.getItemAt(0) is RetreivalMode }
            val method = combos.single { it.getItemAt(0) is RetreivalMethod }
            val button = components.filterIsInstance<JButton>().single { it.text == "Contours" }

            assertEquals(RetreivalMode.entries.toList(), (0 until mode.itemCount).map(mode::getItemAt))
            assertEquals(RetreivalMethod.entries.toList(), (0 until method.itemCount).map(method::getItemAt))
            assertEquals(RetreivalMethod.CHAIN_APPROX_SIMPLE, method.selectedItem)
            button.doClick()
            assertEquals(1, canvas.countours.size)

            mode.selectedItem = RetreivalMode.RETR_LIST
            method.selectedItem = RetreivalMethod.CHAIN_APPROX_NONE
            button.doClick()
            assertEquals(2, canvas.countours.size)

            mode.selectedItem = RetreivalMode.RETR_FLOODFILL
            button.doClick()
            assertTrue(canvas.countours.isNotEmpty())

            mode.selectedItem = RetreivalMode.RETR_TREE
            button.doClick()
            val tree = components.filterIsInstance<JTree>().single()
            val root = tree.model.root as DefaultMutableTreeNode
            assertEquals(2, canvas.countours.size)
            assertEquals(1, root.childCount)
            val outer = root.getChildAt(0) as DefaultMutableTreeNode
            assertEquals(1, outer.childCount)
            val inner = outer.getChildAt(0) as DefaultMutableTreeNode
            assertTrue(outer.toString().startsWith("Contour "))
            assertTrue(inner.toString().startsWith("Contour "))

            val showAll = components.filterIsInstance<JCheckBox>().single { it.text == "Show all" }
            assertTrue(showAll.isSelected)
            showAll.doClick()
            assertFalse(canvas.showAllContours)
            tree.selectionPath = TreePath(inner.path)
            assertNotNull(canvas.selectedContour)
            tree.selectionPath = TreePath(root.path)
            assertNull(canvas.selectedContour)
        }
    }

    @Test
    fun `cells button detects faint enclosed table cells`() {
        SwingUtilities.invokeAndWait {
            val view = TemplateView(null)
            val components = descendants(view).toList()
            val canvas = components.filterIsInstance<TemplateImageCanvas>().single()
            canvas.image = ImageIO.read(javaClass.getResource("/3pager-v1/3pager-p1-ubc.png"))
            components.filterIsInstance<JButton>().single { it.text == "Cells" }.doClick()

            val tree = components.filterIsInstance<JTree>().single()
            val root = tree.model.root as DefaultMutableTreeNode
            assertEquals("Cells (${canvas.countours.size})", root.userObject)
            assertTrue(canvas.countours.any { box ->
                box.x() < 160 && box.x() + box.width() > 160 &&
                    box.y() < 35 && box.y() + box.height() > 35 &&
                    box.width() < 400 && box.height() < 80
            }, "Expected the top-left table cell")
        }
    }

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
    fun `callback saves retain field identity and overwrite the current template image`() {
        val directory = Files.createTempDirectory("template-view-test").toFile()
        val file = directory.resolve("source.png")
        try {
            ImageIO.write(BufferedImage(400, 200, BufferedImage.TYPE_INT_RGB), "png", file)
            SwingUtilities.invokeAndWait {
                val original = Template(
                    "Testmal", "Beskrivelse",
                    listOf(Felter(0, 0, 20, 20, "Fornavn", "name")), file.absolutePath,
                )
                var saved: Template? = null
                val view = TemplateView(original) { saved = it }
                val components = descendants(view).toList()
                val canvas = components.filterIsInstance<TemplateImageCanvas>().single().apply { setSize(232, 232) }
                val list = components.filterIsInstance<JList<*>>().single { it.model.size > 0 && it.model.getElementAt(0) is Felter }
                list.selectedIndex = 0
                drag(canvas, Point(41, 76), Point(91, 106))
                val save = components.filterIsInstance<JButton>().single { it.text == "Lagre mal" }
                save.doClick()
                canvas.image!!.setRGB(10, 10, Color.BLUE.rgb)
                save.doClick()

                val result = assertNotNull(saved)
                assertEquals(original.copy(felter = listOf(Felter(50, 20, 100, 60, "Fornavn", "name")),
                    templateBilde = directory.resolve("template.png").absolutePath), result)
                assertEquals(Color.BLUE.rgb, ImageIO.read(File(result.templateBilde)).getRGB(10, 10))
                assertEquals(setOf("source.png", "template.png"), directory.listFiles()!!.map { it.name }.toSet())
            }
        } finally {
            directory.deleteRecursively()
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
                "Mal", "Beskrivelse",
                listOf(Felter(50, 20, 100, 60, "Fornavn", "name"), Felter(200, 20, 100, 60, "Etternavn", "surname")),
                image.absolutePath,
            )
            TemplateFiles.write(file, original)
            SwingUtilities.invokeAndWait {
                val view = TemplateView(TemplateFiles.read(file), templateFile = file)
                val components = descendants(view).toList()
                val canvas = components.filterIsInstance<TemplateImageCanvas>().single().apply { setSize(232, 232) }
                val list = components.filterIsInstance<JList<*>>().single { it.model.size > 0 && it.model.getElementAt(0) is Felter }
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
                assertEquals(original.copy(felter = canvas.fields,
                    templateBilde = directory.resolve("template.png").absolutePath), TemplateFiles.read(file))

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
            val view = TemplateView(Template("Mal", "", listOf(field), ""))
            val components = descendants(view).toList()
            val list = components.filterIsInstance<JList<*>>().single { it.model.size > 0 && it.model.getElementAt(0) is Felter }
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

    private fun descendants(container: Container): Sequence<Component> = sequence {
        for (component in container.components) {
            yield(component)
            if (component is Container) yieldAll(descendants(component))
        }
    }

    private fun layoutRecursively(container: Container) {
        container.doLayout()
        container.components.filterIsInstance<Container>().forEach(::layoutRecursively)
    }

    private fun drag(canvas: TemplateImageCanvas, start: Point, end: Point) {
        canvas.dispatchEvent(MouseEvent(canvas, MouseEvent.MOUSE_PRESSED, 0, 0, start.x, start.y, 1, false, MouseEvent.BUTTON1))
        canvas.dispatchEvent(MouseEvent(canvas, MouseEvent.MOUSE_DRAGGED, 0, MouseEvent.BUTTON1_DOWN_MASK, end.x, end.y, 0, false))
        canvas.dispatchEvent(MouseEvent(canvas, MouseEvent.MOUSE_RELEASED, 0, 0, end.x, end.y, 1, false, MouseEvent.BUTTON1))
    }
}
