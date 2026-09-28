package org.example.no.nav.tsm.ocr.template.view

import org.example.no.nav.tsm.ocr.template.template.Felter
import org.example.no.nav.tsm.ocr.template.template.Template
import org.example.no.nav.tsm.ocr.template.template.TemplateFiles
import java.awt.BasicStroke
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Cursor
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import java.awt.Point
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.event.ActionEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.image.BufferedImage
import java.io.File
import java.util.UUID
import javax.imageio.ImageIO
import javax.swing.*
import javax.swing.filechooser.FileNameExtensionFilter
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

class TemplateView(
    template: Template?,
    private var templateFile: File? = null,
    private val onSave: ((Template) -> Unit)? = null,
) : JPanel(BorderLayout()) {
    private val navn = JTextField(template?.navn.orEmpty(), 18)
    private val beskrivelse = JTextField(template?.beskrivelse.orEmpty(), 18)
    private val fields = DefaultListModel<Felter>().apply { template?.felter?.forEach { addElement(it) } }
    private val references = DefaultListModel<String>().apply {
        template?.referansepunkter?.forEach { addElement(it) }
    }
    private val fieldList = JList(fields)
    private val referenceList = JList(references)
    private val status = JLabel("Importer et malbilde for å komme i gang.")
    private val imageTitle = JLabel("Malbilde")
    private var imagePath = template?.templateBilde.orEmpty()
    private var newFieldName: String? = null
    private var selectingReference = false
    private val referenceRegions = mutableMapOf<String, Rectangle>()
    private val canvas = TemplateImageCanvas(
        onFieldSelected = { index ->
            cancelSelection()
            fieldList.selectedIndex = index
            fieldList.ensureIndexIsVisible(index)
        },
        onRegionSelected = ::regionSelected,
    )

    init {
        preferredSize = Dimension(1200, 850)
        minimumSize = Dimension(760, 540)
        border = BorderFactory.createEmptyBorder(12, 12, 12, 12)
        fieldList.selectionMode = ListSelectionModel.SINGLE_SELECTION
        referenceList.selectionMode = ListSelectionModel.SINGLE_SELECTION
        fieldList.cellRenderer = object : DefaultListCellRenderer() {
            override fun getListCellRendererComponent(
                list: JList<*>?, value: Any?, index: Int, selected: Boolean, focus: Boolean,
            ): Component = super.getListCellRendererComponent(
                list, (value as? Felter)?.navn.orEmpty(), index, selected, focus,
            )
        }
        referenceList.cellRenderer = object : DefaultListCellRenderer() {
            override fun getListCellRendererComponent(
                list: JList<*>?, value: Any?, index: Int, selected: Boolean, focus: Boolean,
            ): Component = super.getListCellRendererComponent(
                list, File(value.toString()).nameWithoutExtension, index, selected, focus,
            ).also { toolTipText = value?.toString() }
        }
        fieldList.addListSelectionListener {
            if (!it.valueIsAdjusting) {
                cancelSelection()
                canvas.selectedField = fieldList.selectedIndex
                fieldList.selectedValue?.let { field ->
                    status.text = "${field.navn}: x=${field.x}, y=${field.y}, ${field.bredde} × ${field.hoyde} px. Dra for å endre området."
                }
                canvas.repaint()
            }
        }
        referenceList.addListSelectionListener {
            if (!it.valueIsAdjusting) {
                canvas.selectedReference = referenceList.selectedValue
                canvas.repaint()
            }
        }

        val preview = JPanel(BorderLayout(0, 12)).apply {
            border = BorderFactory.createEmptyBorder(8, 16, 8, 0)
            add(imageTitle, BorderLayout.NORTH)
            add(canvas, BorderLayout.CENTER)
            add(JLabel("Rød: felt   •   Grønn: referansepunkt   •   Klikk for å velge, dra for å markere"), BorderLayout.SOUTH)
        }
        add(JSplitPane(JSplitPane.HORIZONTAL_SPLIT, createSidebar(), preview).apply {
            resizeWeight = 0.0
            dividerLocation = 350
            isContinuousLayout = true
            border = BorderFactory.createEmptyBorder()
        }, BorderLayout.CENTER)
        add(status, BorderLayout.SOUTH)
        getInputMap(WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(KeyStroke.getKeyStroke("ESCAPE"), "cancelSelection")
        actionMap.put("cancelSelection", object : AbstractAction() {
            override fun actionPerformed(event: ActionEvent?) {
                cancelSelection()
                status.text = "Markering avbrutt."
            }
        })
        refreshRegions()
        if (imagePath.isNotBlank()) {
            runCatching { loadImage(File(imagePath)) }
                .onFailure { status.text = "Kunne ikke åpne malbildet: ${it.message}" }
        }
    }

    private fun createSidebar() = JPanel(GridBagLayout()).apply {
        minimumSize = Dimension(300, 400)
        border = BorderFactory.createEmptyBorder(8, 0, 8, 12)
        val constraints = GridBagConstraints().apply {
            gridx = 0
            weightx = 1.0
            fill = GridBagConstraints.BOTH
            insets = Insets(0, 0, 16, 0)
        }
        add(JPanel(BorderLayout(8, 0)).apply {
            add(button("Lagre mal") { saveTemplate() }, BorderLayout.WEST)
            add(button("Importer malbilde") { importImage() }, BorderLayout.CENTER)
        }, constraints.apply { gridy = 0 })
        add(JPanel(GridBagLayout()).apply {
            val row = GridBagConstraints().apply { insets = Insets(4, 0, 4, 8); anchor = GridBagConstraints.WEST }
            add(JLabel("Navn:"), row.apply { gridx = 0; gridy = 0 })
            add(navn, row.apply { gridx = 1; weightx = 1.0; fill = GridBagConstraints.HORIZONTAL })
            add(JLabel("Beskrivelse:"), row.apply { gridx = 0; gridy = 1; weightx = 0.0; fill = GridBagConstraints.NONE })
            add(beskrivelse, row.apply { gridx = 1; weightx = 1.0; fill = GridBagConstraints.HORIZONTAL })
        }, constraints.apply { gridy = 1 })
        add(listSection("Felter", fieldList, button("Nytt felt") {
            if (!requireImage()) return@button
            val name = JOptionPane.showInputDialog(this@TemplateView, "Feltnavn:", "Nytt felt", JOptionPane.PLAIN_MESSAGE)
                ?.trim()?.takeIf { it.isNotEmpty() } ?: return@button
            cancelSelection()
            newFieldName = name
            canvas.creatingRegion = true
            status.text = "Dra en ramme rundt $name i bildet. Esc avbryter."
        }, button("Fjern") {
            val index = fieldList.selectedIndex
            if (index >= 0) {
                fields.remove(index)
                refreshRegions()
            }
        }), constraints.apply { gridy = 2; weighty = 0.65 })
        add(listSection("Bilder (referansepunkter)", referenceList, button("Nytt referansepunkt") {
            if (!requireImage()) return@button
            cancelSelection()
            selectingReference = true
            canvas.creatingRegion = true
            canvas.referenceMode = true
            status.text = "Dra en ramme rundt referansepunktet, og lagre utsnittet som PNG. Esc avbryter."
        }, button("Fjern") {
            val index = referenceList.selectedIndex
            if (index >= 0) {
                referenceRegions.remove(references.remove(index))
                refreshRegions()
            }
        }), constraints.apply { gridy = 3; weighty = 0.35 })
    }

    private fun listSection(title: String, list: JList<*>, vararg buttons: JButton) =
        JPanel(BorderLayout(0, 8)).apply {
            border = BorderFactory.createTitledBorder(title)
            add(JScrollPane(list), BorderLayout.CENTER)
            add(JPanel(FlowLayout(FlowLayout.LEADING, 4, 0)).apply {
                buttons.forEach { add(it) }
            }, BorderLayout.SOUTH)
        }

    private fun button(title: String, action: () -> Unit) = JButton(title).apply {
        addActionListener { action() }
    }

    private fun requireImage(): Boolean {
        if (canvas.image != null) return true
        status.text = "Importer et malbilde først."
        return false
    }

    private fun importImage() {
        val chooser = JFileChooser(imagePath.takeIf { it.isNotBlank() }?.let { File(it).parentFile }).apply {
            dialogTitle = "Importer malbilde"
            fileFilter = FileNameExtensionFilter("Bilder", *ImageIO.getReaderFileSuffixes())
        }
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return
        runCatching { loadImage(chooser.selectedFile) }.onFailure { showError("Kunne ikke åpne bildet", it) }
    }

    private fun loadImage(file: File) {
        val image = requireNotNull(ImageIO.read(file)) { "Ugyldig eller ustøttet bildefil." }
        cancelSelection()
        canvas.image = image
        imagePath = file.absolutePath
        // Reference paths contain no coordinates; only crops made on this image have overlays.
        referenceRegions.clear()
        refreshRegions()
        imageTitle.text = "${file.name} — ${image.width} × ${image.height} px"
        status.text = "Velg et felt, eller opprett et nytt felt og marker området i bildet."
        canvas.repaint()
    }

    private fun regionSelected(bounds: Rectangle) {
        if (selectingReference) {
            saveReference(bounds)
            return
        }
        val index = fieldList.selectedIndex
        val previous = if (newFieldName == null && index >= 0) fields[index] else null
        val name = newFieldName ?: previous?.navn ?: run {
            status.text = "Velg et felt i listen eller trykk Nytt felt først."
            return
        }
        val field = Felter(bounds.x, bounds.y, bounds.width, bounds.height, name, previous?.id ?: UUID.randomUUID().toString())
        cancelSelection()
        if (previous == null) {
            fields.addElement(field)
            fieldList.selectedIndex = fields.size() - 1
        } else {
            fields.set(index, field)
        }
        refreshRegions()
        status.text = "$name: x=${bounds.x}, y=${bounds.y}, ${bounds.width} × ${bounds.height} px"
    }

    private fun saveReference(bounds: Rectangle) {
        val source = canvas.image ?: return
        val chooser = JFileChooser(File(imagePath).parentFile).apply {
            dialogTitle = "Lagre referansepunkt"
            selectedFile = File("referansepunkt-${references.size() + 1}.png")
            fileFilter = FileNameExtensionFilter("PNG-bilde", "png")
        }
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) {
            cancelSelection()
            return
        }
        val selected = chooser.selectedFile
        val file = if (selected.extension.equals("png", true)) selected else File(selected.path + ".png")
        if (file.exists() && JOptionPane.showConfirmDialog(
                this, "Erstatt ${file.name}?", "Filen finnes", JOptionPane.YES_NO_OPTION,
            ) != JOptionPane.YES_OPTION) return
        runCatching {
            check(ImageIO.write(source.getSubimage(bounds.x, bounds.y, bounds.width, bounds.height), "png", file)) {
                "PNG-formatet kunne ikke lagres."
            }
        }.onSuccess {
            if (!references.contains(file.absolutePath)) references.addElement(file.absolutePath)
            referenceRegions[file.absolutePath] = Rectangle(bounds)
            referenceList.setSelectedValue(file.absolutePath, true)
            cancelSelection()
            refreshRegions()
            status.text = "Referansepunkt lagret: ${file.name}"
        }.onFailure { showError("Kunne ikke lagre referansepunkt", it) }
    }

    private fun cancelSelection() {
        newFieldName = null
        selectingReference = false
        canvas.creatingRegion = false
        canvas.referenceMode = false
        canvas.cancelDrag()
    }

    private fun refreshRegions() {
        canvas.fields = (0 until fields.size()).map { fields[it] }
        canvas.references = referenceRegions.toMap()
        canvas.selectedField = fieldList.selectedIndex
        canvas.repaint()
    }

    private fun saveTemplate() {
        if (navn.text.isBlank()) {
            status.text = "Gi malen et navn før du lagrer."
            navn.requestFocusInWindow()
            return
        }
        if (!requireImage()) return
        val updated = Template(
            navn = navn.text.trim(),
            beskrivelse = beskrivelse.text.trim(),
            felter = (0 until fields.size()).map { fields[it] },
            referansepunkter = (0 until references.size()).map { references[it] },
            templateBilde = imagePath,
        )
        val saveCallback = onSave
        val destination = if (saveCallback == null) templateFile ?: chooseTemplateDestination() ?: return else null
        runCatching {
            if (saveCallback != null) {
                saveCallback(updated)
            } else {
                TemplateFiles.write(requireNotNull(destination), updated)
                templateFile = destination
            }
        }
            .onSuccess { status.text = "Mal lagret: ${updated.navn}" }
            .onFailure { showError("Kunne ikke lagre malen", it) }
    }

    private fun chooseTemplateDestination(): File? {
        val chooser = JFileChooser(File(imagePath).parentFile).apply {
            dialogTitle = "Lagre mal"
            selectedFile = File("mal.json")
            fileFilter = FileNameExtensionFilter("Mal (JSON)", "json")
        }
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return null
        val selected = chooser.selectedFile
        val file = if (selected.extension.equals("json", true)) selected else File(selected.path + ".json")
        if (file.exists() && JOptionPane.showConfirmDialog(
                this, "Erstatt ${file.name}?", "Filen finnes", JOptionPane.YES_NO_OPTION,
            ) != JOptionPane.YES_OPTION) return null
        return file
    }

    private fun showError(title: String, error: Throwable) {
        JOptionPane.showMessageDialog(this, error.message, title, JOptionPane.ERROR_MESSAGE)
    }
}

/** All regions use original image pixels, independently of the editor's display size. */
internal class TemplateImageCanvas(
    private val onFieldSelected: (Int) -> Unit,
    private val onRegionSelected: (Rectangle) -> Unit,
) : JPanel() {
    var image: BufferedImage? = null
    var fields: List<Felter> = emptyList()
    var references: Map<String, Rectangle> = emptyMap()
    var selectedField = -1
    var selectedReference: String? = null
    var creatingRegion = false
        set(value) {
            field = value
            cursor = Cursor.getPredefinedCursor(if (value) Cursor.CROSSHAIR_CURSOR else Cursor.DEFAULT_CURSOR)
        }
    var referenceMode = false
    private var dragStart: Point? = null
    private var selection: Rectangle? = null

    init {
        background = Color(245, 246, 248)
        preferredSize = Dimension(800, 700)
        minimumSize = Dimension(200, 200)
        border = BorderFactory.createLineBorder(Color(190, 195, 200))
        val mouse = object : MouseAdapter() {
            override fun mousePressed(event: MouseEvent) {
                if (!SwingUtilities.isLeftMouseButton(event) || !imageBounds().contains(event.point)) return
                dragStart = event.point
            }

            override fun mouseDragged(event: MouseEvent) {
                selection = selectedRegion(event.point)
                repaint()
            }

            override fun mouseReleased(event: MouseEvent) {
                if (!SwingUtilities.isLeftMouseButton(event)) return
                val start = dragStart ?: return
                val region = selectedRegion(event.point)
                val click = start.distance(event.point) < 4
                cancelDrag()
                if (click && !creatingRegion) {
                    val bounds = imageBounds()
                    val source = image ?: return
                    val point = Point(
                        ((event.x - bounds.x).toDouble() * source.width / bounds.width).toInt(),
                        ((event.y - bounds.y).toDouble() * source.height / bounds.height).toInt(),
                    )
                    // Prefer the smallest containing field when rectangles overlap.
                    fields.indices.filter { fields[it].bounds().contains(point) }
                        .minByOrNull { fields[it].bredde.toLong() * fields[it].hoyde }
                        ?.let(onFieldSelected)
                } else if (!click && region != null) {
                    onRegionSelected(region)
                }
            }
        }
        addMouseListener(mouse)
        addMouseMotionListener(mouse)
    }

    fun cancelDrag() {
        dragStart = null
        selection = null
        repaint()
    }

    private fun imageBounds(): Rectangle {
        val source = image ?: return Rectangle()
        val scale = minOf((width - 32).coerceAtLeast(0).toDouble() / source.width,
            (height - 32).coerceAtLeast(0).toDouble() / source.height)
        val w = (source.width * scale).roundToInt()
        val h = (source.height * scale).roundToInt()
        return Rectangle((width - w) / 2, (height - h) / 2, w, h)
    }

    private fun selectedRegion(end: Point): Rectangle? {
        val start = dragStart ?: return null
        val source = image ?: return null
        val bounds = imageBounds()
        if (bounds.isEmpty) return null
        val left = minOf(start.x, end.x).coerceIn(bounds.x, bounds.x + bounds.width)
        val top = minOf(start.y, end.y).coerceIn(bounds.y, bounds.y + bounds.height)
        val right = maxOf(start.x, end.x).coerceIn(bounds.x, bounds.x + bounds.width)
        val bottom = maxOf(start.y, end.y).coerceIn(bounds.y, bounds.y + bounds.height)
        if (left == right || top == bottom) return null
        val x = floor((left - bounds.x).toDouble() * source.width / bounds.width).toInt()
        val y = floor((top - bounds.y).toDouble() * source.height / bounds.height).toInt()
        val maxX = ceil((right - bounds.x).toDouble() * source.width / bounds.width).toInt()
        val maxY = ceil((bottom - bounds.y).toDouble() * source.height / bounds.height).toInt()
        return Rectangle(x, y, maxX - x, maxY - y)
    }

    override fun paintComponent(graphics: Graphics) {
        super.paintComponent(graphics)
        val g = graphics.create() as Graphics2D
        try {
            val source = image
            if (source == null) {
                val text = "Importer et malbilde for å markere felter og referansepunkter"
                g.color = Color.DARK_GRAY
                g.drawString(text, maxOf(16, (width - g.fontMetrics.stringWidth(text)) / 2), height / 2)
                return
            }
            val bounds = imageBounds()
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.drawImage(source, bounds.x, bounds.y, bounds.width, bounds.height, null)
            g.clipRect(bounds.x, bounds.y, bounds.width, bounds.height)
            fun drawRegion(region: Rectangle, color: Color, selected: Boolean) {
                val x = bounds.x + (region.x.toDouble() * bounds.width / source.width).roundToInt()
                val y = bounds.y + (region.y.toDouble() * bounds.height / source.height).roundToInt()
                val w = (region.width.toDouble() * bounds.width / source.width).roundToInt()
                val h = (region.height.toDouble() * bounds.height / source.height).roundToInt()
                if (selected) {
                    g.color = Color(color.red, color.green, color.blue, 35)
                    g.fillRoundRect(x, y, w, h, 10, 10)
                }
                g.color = color
                g.stroke = BasicStroke(if (selected) 3f else 1.5f)
                g.drawRoundRect(x, y, w, h, 10, 10)
            }
            fields.forEachIndexed { index, field ->
                drawRegion(field.bounds(), Color(220, 45, 45), index == selectedField)
            }
            references.forEach { (path, region) ->
                drawRegion(region, Color(30, 155, 65), path == selectedReference)
            }
            selection?.let { drawRegion(it, if (referenceMode) Color(30, 155, 65) else Color(220, 45, 45), true) }
        } finally {
            g.dispose()
        }
    }

    private fun Felter.bounds() = Rectangle(x, y, bredde, hoyde)
}
