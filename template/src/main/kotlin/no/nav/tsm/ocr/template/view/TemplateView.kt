package no.nav.tsm.ocr.template.view

import no.nav.tsm.sykmelding.utils.RetreivalMethod
import no.nav.tsm.sykmelding.utils.RetreivalMode
import no.nav.tsm.sykmelding.utils.ContourBox
import no.nav.tsm.sykmelding.utils.GridEdit
import no.nav.tsm.sykmelding.utils.GridEditTool
import no.nav.tsm.sykmelding.utils.GridSettings
import no.nav.tsm.sykmelding.utils.GridSnapLine
import no.nav.tsm.sykmelding.utils.findGridSnapLines
import no.nav.tsm.sykmelding.utils.snapGridEdit
import no.nav.tsm.sykmelding.utils.createGridMask
import no.nav.tsm.sykmelding.utils.findGridCells
import no.nav.tsm.sykmelding.utils.toBufferedImage
import no.nav.tsm.sykmelding.utils.findContourBoxes
import no.nav.tsm.sykmelding.utils.FeatureDetectorType
import no.nav.tsm.sykmelding.utils.findFeatures
import no.nav.tsm.sykmelding.utils.toMat
import org.bytedeco.opencv.opencv_core.Rect
import org.example.no.nav.tsm.ocr.template.template.Felter
import org.example.no.nav.tsm.ocr.template.template.FieldCatalog
import org.example.no.nav.tsm.ocr.template.template.FieldDefinition
import org.example.no.nav.tsm.ocr.template.template.FieldType
import org.example.no.nav.tsm.ocr.template.template.ImageClearArea
import org.example.no.nav.tsm.ocr.template.template.Template
import org.example.no.nav.tsm.ocr.template.template.TemplateFiles
import org.example.no.nav.tsm.ocr.template.template.PdfTemplateImages
import org.example.no.nav.tsm.ocr.template.template.PageOrientation
import org.example.no.nav.tsm.ocr.template.template.PageRotation
import org.example.no.nav.tsm.ocr.template.template.OrientationGuess
import org.example.no.nav.tsm.ocr.template.template.clearTemplateAreas
import org.bytedeco.opencv.opencv_core.Mat
import java.awt.BasicStroke
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Cursor
import java.awt.Dialog
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.GridLayout
import java.awt.Image
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
import java.util.concurrent.ExecutionException
import javax.imageio.ImageIO
import javax.swing.*
import javax.swing.filechooser.FileNameExtensionFilter
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

private data class ContourEntry(val index: Int, val bounds: Rect, val kind: String) {
    override fun toString() =
        "$kind ${index + 1}: (${bounds.x()}, ${bounds.y()}) ${bounds.width()} × ${bounds.height()} px"
}

private data class CornerEntry(val index: Int, val point: Point) {
    override fun toString() = "Corner ${index + 1}: (${point.x}, ${point.y})"
}

class TemplateView(
    template: Template?,
    private var templateFile: File? = null,
    private val fieldCatalog: FieldCatalog = FieldCatalog(),
    private val onSave: ((Template) -> Unit)? = null,
) : JPanel(BorderLayout()) {
    private val navn = JTextField(template?.navn.orEmpty(), 18)
    private val beskrivelse = JTextField(template?.beskrivelse.orEmpty(), 18)
    private val fields = DefaultListModel<Felter>().apply { template?.felter?.forEach { addElement(it) } }
    private val fieldList = JList(fields)
    private val references = DefaultListModel<String>().apply { template?.referansepunkter?.forEach { addElement(it) } }
    private val referenceList = JList(references)
    private val referenceRegions = mutableMapOf<String, Rectangle>()
    private val catalogFields = DefaultListModel<FieldDefinition>()
    private val catalogList = JList(catalogFields)
    private val catalogName = JTextField(16)
    private val catalogType = JComboBox(FieldType.entries.toTypedArray())
    private val catalogStatus = JLabel("Shared field definitions")
    private val clickToAssign = JCheckBox("Click cell to assign field").apply {
        addActionListener {
            val enabled = isSelected
            cancelSelection()
            isSelected = enabled
            status.text = "Velg et katalogfelt, og klikk cellen. Esc avslutter."
        }
    }
    private val editFieldButton = button("Rediger") { editSelectedField() }.apply { isEnabled = false }

    private var areaAction: String? = null
    private var originalImage: BufferedImage? = null
    private val removedAreas = mutableListOf<List<ImageClearArea>>()
    private val status = JLabel("Importer et malbilde for å komme i gang.")
    private val imageTitle = JLabel("Malbilde")

    private val viewFieldCheckbox: JCheckBox = JCheckBox("view fields").apply { isEnabled = true }
    private var imagePath = template?.templateBilde.orEmpty()
    private var newFieldName: String? = null
    private val contourMode = JComboBox(RetreivalMode.entries.toTypedArray())
    private val contourMethod = JComboBox(RetreivalMethod.entries.toTypedArray()).apply {
        selectedItem = RetreivalMethod.CHAIN_APPROX_SIMPLE
    }
    private val gridTool = JComboBox(GridEditTool.entries.toTypedArray())
    private val minimumLineLength = JSpinner(SpinnerNumberModel(template?.gridSettings?.minimumLineLength ?: 0, 0, 10000, 1))
    private val gapSize = JSpinner(SpinnerNumberModel(template?.gridSettings?.gapSize ?: 3, 0, 100, 1))
    private val gridMaskCheckbox = JCheckBox("Show grid mask").apply {
        addActionListener {
            canvas.showGridMask = isSelected
            if (isSelected) detectCells()
        }
    }
    private val showAllContours = JCheckBox("Show all", true).apply {
        addActionListener { canvas.showAllContours = isSelected }
    }
    private val contourTree = JTree(DefaultMutableTreeNode("Contours (0)")).apply {
        addTreeSelectionListener {
            val node = lastSelectedPathComponent as? DefaultMutableTreeNode
            canvas.selectedContour = (node?.userObject as? ContourEntry)?.index
            canvas.selectedCorner = (node?.userObject as? CornerEntry)?.index
        }
    }

    val showContoursButton: JButton = JButton("Contours").apply {
        addActionListener {
            if (!requireImage()) return@addActionListener
            cancelSelection()
            val mode = contourMode.getItemAt(contourMode.selectedIndex)
            runCatching {
                canvas.showContours(mode, contourMethod.getItemAt(contourMethod.selectedIndex))
            }.onSuccess { updateContourTree(it, mode) }
                .onFailure { showError("Kunne ikke finne konturer", it) }
        }
    }
    private val showCellsButton = JButton("Cells").apply {
        addActionListener { cancelSelection(); detectCells() }
    }

    private val featureDetector = JComboBox(FeatureDetectorType.entries.toTypedArray())
    private val showFeaturesButton = JButton("Detect features").apply {
        addActionListener {
            if (!requireImage()) return@addActionListener
            cancelSelection()
            val type = featureDetector.getItemAt(featureDetector.selectedIndex)
            runCatching { canvas.showFeatures(type) }
                .onSuccess {
                    updateCornerTree(it, "$type features")
                    status.text = "$type fant ${it.size} features."
                }
                .onFailure { showError("Kunne ikke finne features", it) }
        }
    }
    private val canvas: TemplateImageCanvas = TemplateImageCanvas(
        onFieldSelected = { index ->
            cancelSelection()
            contourTree.clearSelection()
            fieldList.selectedIndex = index
            fieldList.ensureIndexIsVisible(index)
        },
        onRegionSelected = ::regionSelected,
        onContourSelected = { index ->
            val root = contourTree.model.root as DefaultMutableTreeNode
            val node = root.depthFirstEnumeration().asSequence().filterIsInstance<DefaultMutableTreeNode>()
                .firstOrNull { (it.userObject as? ContourEntry)?.index == index }
            if (node != null) contourTree.selectionPath = TreePath(node.path)
            if (clickToAssign.isSelected) assignCatalogField(keepAssigning = true)
        },
        onGridEdited = { edit ->
            runCatching { canvasEdit(edit) }.onFailure { showError("Kunne ikke endre rutenettet", it) }
        },
    )

    init {
        preferredSize = Dimension(1450, 850)
        minimumSize = Dimension(1000, 540)
        border = BorderFactory.createEmptyBorder(12, 12, 12, 12)
        fieldList.selectionMode = ListSelectionModel.SINGLE_SELECTION
        referenceList.selectionMode = ListSelectionModel.SINGLE_SELECTION
        referenceList.cellRenderer = object : DefaultListCellRenderer() {
            override fun getListCellRendererComponent(list: JList<*>?, value: Any?, index: Int, selected: Boolean, focus: Boolean): Component =
                super.getListCellRendererComponent(list, File(value.toString()).name, index, selected, focus)
                    .also { toolTipText = value?.toString() }
        }
        referenceList.addListSelectionListener {
            canvas.selectedReference = referenceList.selectedValue
            canvas.repaint()
        }
        fieldList.cellRenderer = object : DefaultListCellRenderer() {
            override fun getListCellRendererComponent(
                list: JList<*>?, value: Any?, index: Int, selected: Boolean, focus: Boolean,
            ): Component = super.getListCellRendererComponent(
                list, (value as? Felter)?.let { if (it.type == FieldType.CHECKBOX) "☐ ${it.navn}" else it.navn }.orEmpty(), index, selected, focus,
            )
        }
        fieldList.addListSelectionListener {
            if (!it.valueIsAdjusting) {
                cancelSelection()
                editFieldButton.isEnabled = fieldList.selectedIndex >= 0
                canvas.selectedField = fieldList.selectedIndex
                fieldList.selectedValue?.let { field ->
                    status.text = "${field.navn}: x=${field.x}, y=${field.y}, ${field.bredde} × ${field.hoyde} px. Dra for å endre området."
                }
                canvas.repaint()
            }
        }
        viewFieldCheckbox.addActionListener {
            canvas.viewFieldCheckbox(viewFieldCheckbox.isSelected)
        }
        val preview = JPanel(BorderLayout(0, 12)).apply {
            border = BorderFactory.createEmptyBorder(8, 16, 8, 0)
            add(imageTitle, BorderLayout.NORTH)
            add(canvas, BorderLayout.CENTER)
            add(JPanel().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                add(JPanel(FlowLayout(FlowLayout.LEFT, 8, 0)).apply {
                    add(JLabel("Mode:"))
                    add(contourMode)
                })
                add(JPanel(FlowLayout(FlowLayout.LEFT, 8, 0)).apply {
                    add(JLabel("Method:"))
                    add(contourMethod)
                })
                add(JPanel(FlowLayout(FlowLayout.LEFT, 8, 0)).apply {
                    add(showContoursButton)
                    add(showCellsButton)
                })
                add(JPanel(FlowLayout(FlowLayout.LEFT, 8, 0)).apply {
                    add(JLabel("Detector:"))
                    add(featureDetector)
                    add(showFeaturesButton)
                })
            }, BorderLayout.SOUTH)
        }
        val contourPanel = JPanel(BorderLayout(0, 8)).apply {
            border = BorderFactory.createTitledBorder("Contours / cells / corners / text groups")
            preferredSize = Dimension(280, 400)
            minimumSize = Dimension(280, 200)
            add(showAllContours, BorderLayout.NORTH)
            add(JScrollPane(contourTree), BorderLayout.CENTER)
            add(JTabbedPane().apply {
                addTab("Grid", createGridControls())
                addTab("Regions", createRegionControls())
            }, BorderLayout.SOUTH)
        }
        val previewAndContours = JSplitPane(JSplitPane.HORIZONTAL_SPLIT, preview, contourPanel).apply {
            resizeWeight = 1.0
            isContinuousLayout = true
            border = BorderFactory.createEmptyBorder()
        }
        add(JSplitPane(JSplitPane.HORIZONTAL_SPLIT, createSidebar(), previewAndContours).apply {
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
        canvas.gridSettings = template?.gridSettings ?: GridSettings()
        canvas.gridEdits = template?.gridEdits.orEmpty()
        if (canvas.image != null && canvas.gridEdits.isNotEmpty()) detectCells()
    }

    private fun createRegionControls() = JPanel(GridLayout(0, 1, 0, 4)).apply {
        border = BorderFactory.createEmptyBorder(4, 4, 4, 4)
        add(JLabel("Select a box in the image or tree"))
        add(button("Mark as field") {
            if (catalogList.selectedValue != null) assignCatalogField() else createFieldFromCell()
        }.apply {
            toolTipText = "Use the selected detector to show feature points across the full image."
        })
        add(button("Mark as checkbox") { markCheckbox() })
        add(button("Remove selected area") {
            selectedBox()?.let { bounds ->
                val isField = (0 until fields.size()).any {
                    val field = fields[it]
                    Rectangle(field.x, field.y, field.bredde, field.hoyde) == bounds
                }
                removeArea(bounds, preserveBorders = isField)
            }
        })
        add(button("Clear fields") { clearFields() }.apply {
            toolTipText = "Clear contents of all assigned fields, keeping their borders and assignments. Undo removal restores the contents."
        })
        add(button("Remove area…") { startAreaAction("remove") })
        add(button("Undo removal") {
            if (removedAreas.isNotEmpty()) {
                removedAreas.removeLast()
                updateClearedImage()
            }
        })
    }

    private fun startAreaAction(action: String) {
        if (!requireImage()) return
        cancelSelection()
        areaAction = action
        canvas.creatingRegion = true
        status.text = when (action) {
            "text" -> "Dra området der den påkrevde teksten skal finnes. Esc avbryter."
            else -> "Dra et område som skal fjernes fra malbildet. Esc avbryter."
        }
    }

    private fun selectedBox(): Rectangle? {
        val node = contourTree.lastSelectedPathComponent as? DefaultMutableTreeNode
        val entry = node?.userObject as? ContourEntry
        if (entry == null) {
            fieldList.selectedValue?.let { return Rectangle(it.x, it.y, it.bredde, it.hoyde) }
            status.text = "Velg en celle eller tekstgruppe først."
            return null
        }
        return entry.bounds.let { Rectangle(it.x(), it.y(), it.width(), it.height()) }
    }

    private fun removeArea(bounds: Rectangle, preserveBorders: Boolean = false) {
        val source = canvas.image ?: return
        val clipped = bounds.intersection(Rectangle(0, 0, source.width, source.height))
        if (clipped.isEmpty) return
        if (originalImage == null) originalImage = source
        removedAreas += listOf(ImageClearArea(clipped, preserveBorders))
        cancelSelection()
        updateClearedImage()
    }

    private fun clearFields() {
        val source = canvas.image ?: return
        if (fields.isEmpty) {
            status.text = "Ingen felt å tømme."
            return
        }
        if (originalImage == null) originalImage = source
        removedAreas += (0 until fields.size()).map { index ->
            val field = fields[index]
            ImageClearArea(Rectangle(field.x, field.y, field.bredde, field.hoyde), preserveBorders = true)
        }
        cancelSelection()
        updateClearedImage()
    }

    private fun markCheckbox() {
        val bounds = selectedBox() ?: return
        fun selectedFieldIndex() = (0 until fields.size()).firstOrNull {
            val field = fields[it]
            Rectangle(field.x, field.y, field.bredde, field.hoyde) == bounds
        }
        if (selectedFieldIndex() == null) {
            if (catalogList.selectedValue != null) assignCatalogField() else createFieldFromCell()
        }
        val index = selectedFieldIndex() ?: return
        val field = fields[index]
        runCatching {
            // Shared definitions keep the same type wherever they are assigned.
            if (fieldCatalog.read().any { it.id == field.id }) {
                fieldCatalog.setType(field.id, FieldType.CHECKBOX)
                refreshCatalog(field.id)
            }
            fields.set(index, field.copy(type = FieldType.CHECKBOX))
            cancelSelection()
            refreshRegions()
            status.text = "${field.navn} er markert som Checkbox."
        }.onFailure { showError("Kunne ikke endre felttype", it) }
    }

    private fun updateClearedImage() {
        val source = originalImage ?: return
        canvas.replaceImageContent(clearTemplateAreas(source, removedAreas.flatten(), canvas.gridSettings, canvas.gridEdits))
        detectCells()
        status.text = "${removedAreas.sumOf { it.size }} områder tømt. Lagre mal for å lagre det redigerte bildet."
    }

    private fun createGridControls() = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        border = BorderFactory.createTitledBorder("Edit grid (image pixels)")
        fun row(vararg components: Component) = JPanel(BorderLayout(4, 0)).apply {
            border = BorderFactory.createEmptyBorder(2, 4, 2, 4)
            add(components[0], BorderLayout.CENTER)
            if (components.size > 1) add(components[1], BorderLayout.EAST)
        }
        minimumLineLength.toolTipText = "0 = automatic. Increase above text stroke length to reject text."
        gapSize.toolTipText = "Extend detected rule endpoints by this many pixels. Keep small to preserve open cells."
        add(row(JLabel("Min. line:"), minimumLineLength))
        add(row(JLabel("Bridge gap:"), gapSize))
        add(row(gridMaskCheckbox))
        add(row(JCheckBox("Snap cells and lines to grid", true).apply {
            toolTipText = "Snap cells and parallel lines; connect line endpoints to crossing rules within 8 screen pixels."
            addActionListener { canvas.snapCellsToGrid = isSelected }
        }))
        add(row(gridTool, button("Draw") {
            cancelSelection()
            if (!detectCells()) return@button
            canvas.gridEditTool = gridTool.selectedItem as GridEditTool
            status.text = "Dra i bildet: ${gridTool.selectedItem}. Celler beregnes på nytt. Esc avslutter."
        }))
        add(row(button("Undo grid edit") {
            cancelSelection()
            if (canvas.gridEdits.isNotEmpty()) {
                canvas.gridEdits = canvas.gridEdits.dropLast(1)
                detectCells()
            }
        }))
        add(row(button("Reset grid") {
            cancelSelection()
            canvas.gridEdits = emptyList()
            detectCells()
        }))
        add(row(button("Cell → field") { createFieldFromCell() }))
    }

    private fun detectCells(): Boolean {
        if (!requireImage()) return false
        return runCatching {
            minimumLineLength.commitEdit()
            gapSize.commitEdit()
            canvas.gridSettings = canvas.gridSettings.copy(
                minimumLineLength = (minimumLineLength.value as Number).toInt(),
                gapSize = (gapSize.value as Number).toInt(),
            )
            displayCells(canvas.showCells())
        }.onFailure { showError("Kunne ikke finne celler", it) }.isSuccess
    }

    private fun canvasEdit(edit: GridEdit) {
        val previous = canvas.gridEdits
        canvas.gridEdits = previous + edit
        try {
            displayCells(canvas.showCells())
        } catch (error: Throwable) {
            canvas.gridEdits = previous
            throw error
        }
    }

    private fun displayCells(cells: List<ContourBox>) {
        updateContourTree(cells, RetreivalMode.RETR_LIST, "Cell")
        status.text = "${cells.size} celler. ${canvas.gridEdits.size} rutenettendringer. Esc avslutter tegning."
    }

    internal fun createFieldFromCell(requestName: () -> String? = {
        JOptionPane.showInputDialog(this, "Feltnavn:", "Nytt felt", JOptionPane.PLAIN_MESSAGE)
    }) {
        val bounds = selectedBox() ?: return
        val name = requestName()
            ?.trim()?.takeIf { it.isNotEmpty() } ?: return
        cancelSelection()
        val existing = (0 until fields.size()).firstOrNull {
            val field = fields[it]
            Rectangle(field.x, field.y, field.bredde, field.hoyde) == bounds
        }
        if (existing != null) {
            fields.set(existing, fields[existing].copy(navn = name))
            refreshRegions()
            return
        }
        newFieldName = name
        regionSelected(bounds)
    }

    private fun createCatalogControls() = JPanel(BorderLayout(0, 6)).apply {
        border = BorderFactory.createEmptyBorder(6, 6, 6, 6)
        catalogList.selectionMode = ListSelectionModel.SINGLE_SELECTION
        catalogList.cellRenderer = object : DefaultListCellRenderer() {
            override fun getListCellRendererComponent(
                list: JList<*>?, value: Any?, index: Int, selected: Boolean, focus: Boolean,
            ): Component {
                val definition = value as? FieldDefinition
                val assigned = definition != null && (0 until fields.size()).any { fields[it].id == definition.id }
                return super.getListCellRendererComponent(list,
                    (if (definition?.type == FieldType.CHECKBOX) "☐ " else "") +
                        definition?.navn.orEmpty() + if (assigned) " (assigned)" else "", index, selected, focus)
                    .also { toolTipText = definition?.id }
            }
        }
        catalogList.addListSelectionListener {
            if (!it.valueIsAdjusting) {
                catalogName.text = catalogList.selectedValue?.navn.orEmpty()
                catalogType.selectedItem = catalogList.selectedValue?.type ?: FieldType.TEXT
            }
        }
        add(catalogStatus.apply { toolTipText = fieldCatalog.file.absolutePath }, BorderLayout.NORTH)
        add(JScrollPane(catalogList), BorderLayout.CENTER)
        add(JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(JLabel("Field name:").apply { labelFor = catalogName })
            add(catalogName)
            add(JLabel("Field type:").apply { labelFor = catalogType })
            add(catalogType)
            add(JPanel(GridLayout(0, 2, 4, 4)).apply {
                add(button("Add global field") {
                    runCatching { fieldCatalog.add(catalogName.text, catalogType.selectedItem as FieldType) }
                        .onSuccess { refreshCatalog(it.id) }
                        .onFailure { showError("Kunne ikke legge til felt", it) }
                })
                add(button("Rename global field") {
                    val selected = catalogList.selectedValue ?: return@button
                    runCatching { fieldCatalog.rename(selected.id, catalogName.text) }
                        .onSuccess { refreshCatalog(selected.id) }
                        .onFailure { showError("Kunne ikke endre felt", it) }
                })
                add(button("Delete global field") {
                    val selected = catalogList.selectedValue ?: return@button
                    runCatching { fieldCatalog.remove(selected.id) }
                        .onSuccess { refreshCatalog(); status.text = "Felt fjernet fra katalogen. Eksisterende tildelinger beholdes." }
                        .onFailure { showError("Kunne ikke fjerne felt", it) }
                })
                add(button("Refresh catalog") { refreshCatalog(catalogList.selectedValue?.id) })
                add(button("Set field type") {
                    val selected = catalogList.selectedValue ?: return@button
                    runCatching { fieldCatalog.setType(selected.id, catalogType.selectedItem as FieldType) }
                        .onSuccess { refreshCatalog(selected.id) }
                        .onFailure { showError("Kunne ikke endre felttype", it) }
                })
            })
            add(button("Assign to selected cell") { assignCatalogField() })
            add(clickToAssign)
        }, BorderLayout.SOUTH)
        refreshCatalog()
    }

    private fun refreshCatalog(selectedId: String? = null) {
        runCatching { fieldCatalog.read() }.onSuccess { definitions ->
            catalogFields.clear()
            definitions.forEach { catalogFields.addElement(it) }
            catalogList.selectedIndex = definitions.indexOfFirst { it.id == selectedId }
            val byId = definitions.associateBy { it.id }
            for (index in 0 until fields.size()) {
                val field = fields[index]
                byId[field.id]?.let { fields.set(index, field.copy(navn = it.navn, type = it.type)) }
            }
            refreshRegions()
            catalogStatus.text = "${definitions.size} global fields — saved automatically"
        }.onFailure {
            catalogStatus.text = "Could not load catalog: ${it.message}"
        }
    }

    private fun assignCatalogField(keepAssigning: Boolean = false) {
        val definition = catalogList.selectedValue ?: run {
            status.text = "Velg et felt i Global fields først."
            return
        }
        val bounds = selectedBox() ?: return
        cancelSelection()
        // A definition has one location per template; assigning again moves it.
        for (index in fields.size() - 1 downTo 0) {
            val field = fields[index]
            if (field.id == definition.id || Rectangle(field.x, field.y, field.bredde, field.hoyde) == bounds) {
                fields.remove(index)
            }
        }
        fields.addElement(Felter(bounds.x, bounds.y, bounds.width, bounds.height, definition.navn, definition.id, definition.type))
        fieldList.selectedIndex = fields.size() - 1
        refreshRegions()
        clickToAssign.isSelected = keepAssigning
        status.text = "${definition.navn} tildelt cellen. Feltinnhold inngår i funksjonspunktdeteksjonen."
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
            add(button("Importer bilde / PDF") { importImage() }, BorderLayout.CENTER)
            add(button("Rotate + straighten") { rotatePage() }.apply {
                toolTipText = "Automatically turn the page upright and correct small scan angles. Preview before applying; do this before marking regions."
            }, BorderLayout.SOUTH)
        }, constraints.apply { gridy = 0 })
        add(JPanel(GridBagLayout()).apply {
            val row = GridBagConstraints().apply { insets = Insets(4, 0, 4, 8); anchor = GridBagConstraints.WEST }
            add(JLabel("Navn:"), row.apply { gridx = 0; gridy = 0 })
            add(navn, row.apply { gridx = 1; weightx = 1.0; fill = GridBagConstraints.HORIZONTAL })
            add(JLabel("Beskrivelse:"), row.apply { gridx = 0; gridy = 1; weightx = 0.0; fill = GridBagConstraints.NONE })
            add(beskrivelse, row.apply { gridx = 1; weightx = 1.0; fill = GridBagConstraints.HORIZONTAL })
            add(viewFieldCheckbox, row.apply { gridx = 0; gridy = 2 })
        }, constraints.apply { gridy = 1 })
        val fieldSection = listSection("Felter", fieldList, button("Nytt felt") {
            if (!requireImage()) return@button
            val name = JOptionPane.showInputDialog(this@TemplateView, "Feltnavn:", "Nytt felt", JOptionPane.PLAIN_MESSAGE)
                ?.trim()?.takeIf { it.isNotEmpty() } ?: return@button
            cancelSelection()
            newFieldName = name
            canvas.creatingRegion = true
            status.text = "Dra en ramme rundt $name i bildet. Esc avbryter."
        }, editFieldButton, button("Fjern") {
            val index = fieldList.selectedIndex
            if (index >= 0) {
                fields.remove(index)
                refreshRegions()
            }
        })
        add(JTabbedPane().apply {
            addTab("Fields", fieldSection)
            addTab("Global fields", createCatalogControls())
            addTab("Reference points", listSection("Reference crops", referenceList,
                button("New reference point") {
                    if (!requireImage()) return@button
                    cancelSelection()
                    areaAction = "reference"
                    canvas.creatingRegion = true
                    status.text = "Drag a distinctive reference crop. Use several crops spread across the page for perspective alignment."
                },
                button("Remove reference point") {
                    val index = referenceList.selectedIndex
                    if (index >= 0) {
                        referenceRegions.remove(references.remove(index))
                        refreshRegions()
                    }
                }))
        }, constraints.apply { gridy = 2; weighty = 0.65 })
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

    internal fun editSelectedField(
        requestName: (String) -> String? = { currentName ->
            JOptionPane.showInputDialog(
                this, "Feltnavn:", "Rediger felt", JOptionPane.PLAIN_MESSAGE, null, null, currentName,
            )?.toString()
        },
    ) {
        val index = fieldList.selectedIndex
        if (index < 0) return
        val field = fields[index]
        val name = requestName(field.navn)?.trim() ?: return
        if (name.isEmpty()) {
            status.text = "Feltnavnet kan ikke være tomt."
            return
        }
        cancelSelection()
        fields.set(index, field.copy(navn = name))
        refreshRegions()
        status.text = "Feltnavn endret til $name."
    }

    private fun importImage() {
        val chooser = JFileChooser(imagePath.takeIf { it.isNotBlank() }?.let { File(it).parentFile }).apply {
            dialogTitle = "Importer malbilde eller PDF"
            fileFilter = FileNameExtensionFilter("Bilder og PDF", *ImageIO.getReaderFileSuffixes(), "pdf")
        }
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return
        runCatching {
            val file = chooser.selectedFile
            if (file.extension.equals("pdf", ignoreCase = true)) importPdf(file) else loadImage(file)
        }.onFailure { showError("Kunne ikke importere malbildet", it) }
    }

    private fun canRotatePage(): Boolean {
        if (!requireImage()) return false
        if (!fields.isEmpty || !references.isEmpty || canvas.gridEdits.isNotEmpty() ||
            canvas.textArea != null || removedAreas.isNotEmpty()) {
            status.text = "Rotate before assigning fields, reference points, text regions, grid edits or removing areas."
            return false
        }
        return true
    }

    private fun rotatePage() {
        if (!canRotatePage()) return
        cancelSelection()
        val source = requireNotNull(canvas.image)
        val progress = JDialog(SwingUtilities.getWindowAncestor(this), "Rotate + straighten",
            Dialog.ModalityType.APPLICATION_MODAL).apply {
            defaultCloseOperation = JDialog.DO_NOTHING_ON_CLOSE
            add(JPanel(BorderLayout(8, 8)).apply {
                border = BorderFactory.createEmptyBorder(20, 20, 20, 20)
                add(JLabel("Finding the page orientation and scan angle..."), BorderLayout.NORTH)
                add(JProgressBar().apply { isIndeterminate = true }, BorderLayout.CENTER)
            })
            pack()
            setLocationRelativeTo(this@TemplateView)
        }
        val worker = object : SwingWorker<Pair<OrientationGuess, BufferedImage>, Unit>() {
            override fun doInBackground() = PageOrientation.detect(source).let { it to it.rotation.apply(source) }
            override fun done() { progress.dispose() }
        }
        worker.execute()
        progress.isVisible = true
        val (guess, rotated) = try {
            worker.get()
        } catch (error: ExecutionException) {
            showError("Could not rotate the page", error.cause ?: error)
            return
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            status.text = "Page rotation interrupted."
            return
        }
        if (guess.rotation.isNone()) {
            status.text = guess.description
            return
        }
        val scale = minOf(1.0, 500.0 / rotated.width, 600.0 / rotated.height)
        val preview = JLabel(ImageIcon(rotated.getScaledInstance(
            (rotated.width * scale).roundToInt().coerceAtLeast(1),
            (rotated.height * scale).roundToInt().coerceAtLeast(1), Image.SCALE_SMOOTH)))
        val confirmation = JPanel(BorderLayout(8, 8)).apply {
            add(JLabel(guess.description), BorderLayout.NORTH)
            add(preview, BorderLayout.CENTER)
            add(JLabel("Apply this rotation? Save the template to keep the corrected image."), BorderLayout.SOUTH)
        }
        if (JOptionPane.showConfirmDialog(this, confirmation, "Rotate + straighten",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) return
        applyPageRotation(guess.rotation, rotated)
    }

    internal fun applyPageRotation(rotation: PageRotation, rotated: BufferedImage? = null): Boolean {
        if (!canRotatePage()) return false
        if (!rotation.isNone()) {
            loadImage(File(imagePath), rotated ?: rotation.apply(requireNotNull(canvas.image)))
            status.text = "${rotation.describe()}. Save the template to keep the corrected image."
        }
        return true
    }

    private fun importPdf(file: File) {
        val pages = PdfTemplateImages.pageCount(file)
        val pageNumber = JSpinner(SpinnerNumberModel(1, 1, pages, 1))
        val selection = JPanel(FlowLayout(FlowLayout.LEFT)).apply {
            add(JLabel("Side (1–$pages):"))
            add(pageNumber)
            add(JLabel("400 DPI"))
        }
        if (JOptionPane.showConfirmDialog(this, selection, "Velg PDF-side – ${file.name}",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) return
        pageNumber.commitEdit()
        val page = (pageNumber.value as Number).toInt()
        val chooser = JFileChooser(file.absoluteFile.parentFile).apply {
            dialogTitle = "Lagre malbilde fra PDF (400 DPI)"
            selectedFile = File(file.absoluteFile.parentFile, "template.png")
            fileFilter = FileNameExtensionFilter("PNG-bilde", "png")
        }
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return
        val selected = chooser.selectedFile
        val destination = File(selected.absoluteFile.parentFile, "template.png")
        require(destination.canonicalFile != file.canonicalFile) { "Malbildet kan ikke erstatte PDF-filen." }
        if (destination.exists() && JOptionPane.showConfirmDialog(this, "Erstatt ${destination.name}?",
                "Filen finnes", JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) return

        val progress = JDialog(SwingUtilities.getWindowAncestor(this), "Importerer PDF",
            Dialog.ModalityType.APPLICATION_MODAL).apply {
            defaultCloseOperation = JDialog.DO_NOTHING_ON_CLOSE
            add(JPanel(BorderLayout(8, 8)).apply {
                border = BorderFactory.createEmptyBorder(20, 20, 20, 20)
                add(JLabel("Lager bilde av side $page ved 400 DPI …"), BorderLayout.NORTH)
                add(JProgressBar().apply { isIndeterminate = true }, BorderLayout.CENTER)
            })
            pack()
            setLocationRelativeTo(this@TemplateView)
        }
        val worker = object : SwingWorker<BufferedImage, Unit>() {
            override fun doInBackground(): BufferedImage {
                val image = PdfTemplateImages.render(file, page)
                PdfTemplateImages.writePng(image, destination)
                return image
            }

            override fun done() { progress.dispose() }
        }
        worker.execute()
        progress.isVisible = true
        val image = try {
            worker.get()
        } catch (error: ExecutionException) {
            throw error.cause ?: error
        }
        loadImage(destination, image)
        status.text = "Importert side $page av $pages fra ${file.name} ved 400 DPI."
    }

    private fun loadImage(file: File, image: BufferedImage = requireNotNull(ImageIO.read(file)) { "Ugyldig eller ustøttet bildefil." }) {
        cancelSelection()
        canvas.image = image
        originalImage = image
        removedAreas.clear()
        referenceRegions.clear()
        canvas.countours = emptyList()
        canvas.selectedContour = null
        canvas.corners = emptyList()
        canvas.selectedCorner = null
        updateContourTree(emptyList(), contourMode.getItemAt(contourMode.selectedIndex))
        imagePath = file.absolutePath
        refreshRegions()
        imageTitle.text = "${file.name} — ${image.width} × ${image.height} px"
        status.text = "Velg et felt, eller opprett et nytt felt og marker området i bildet."
        canvas.textBoxes = emptyList()
        canvas.repaint()
    }

    private fun updateContourTree(
        contours: List<ContourBox>, mode: RetreivalMode, kind: String = "Contour",
    ) {
        val root = DefaultMutableTreeNode("${kind}s (${contours.size})")
        val nodes = contours.mapIndexed { index, contour ->
            DefaultMutableTreeNode(ContourEntry(index, contour.bounds, kind))
        }
        nodes.forEachIndexed { index, node ->
            val parent = contours[index].parentIndex
            if (mode == RetreivalMode.RETR_TREE && parent in nodes.indices && parent != index) {
                nodes[parent].add(node)
            } else {
                root.add(node)
            }
        }
        contourTree.model = DefaultTreeModel(root)
        contourTree.expandRow(0)
        if (nodes.isNotEmpty()) {
            contourTree.selectionPath = TreePath(nodes[0].path)
        }
    }

    private fun updateCornerTree(corners: List<Point>) = updateCornerTree(corners, "Corners")

    private fun updateCornerTree(corners: List<Point>, title: String) {
        val root = DefaultMutableTreeNode("$title (${corners.size})")
        val nodes = corners.mapIndexed { index, point -> DefaultMutableTreeNode(CornerEntry(index, point)) }
        nodes.forEach { root.add(it) }
        contourTree.model = DefaultTreeModel(root)
        contourTree.expandRow(0)
        if (nodes.isNotEmpty()) {
            contourTree.selectionPath = TreePath(nodes[0].path)
        }
    }

    private fun regionSelected(bounds: Rectangle) {
        when (areaAction) {
            "reference" -> { saveReference(bounds); return }
            "remove" -> { removeArea(bounds); return }
        }
        val index = fieldList.selectedIndex
        val previous = if (newFieldName == null && index >= 0) fields[index] else null
        val name = newFieldName ?: previous?.navn ?: run {
            status.text = "Velg et felt i listen eller trykk Nytt felt først."
            return
        }
        val field = Felter(bounds.x, bounds.y, bounds.width, bounds.height, name, previous?.id ?: UUID.randomUUID().toString(),
            previous?.type ?: FieldType.TEXT)
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
        val chooser = JFileChooser(templateFile?.absoluteFile?.parentFile ?: File(imagePath).absoluteFile.parentFile).apply {
            dialogTitle = "Save reference point"
            selectedFile = File(currentDirectory, "reference-${UUID.randomUUID()}.png")
            fileFilter = FileNameExtensionFilter("PNG image", "png")
        }
        cancelSelection()
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return
        val selected = chooser.selectedFile
        val file = if (selected.extension.equals("png", true)) selected else File(selected.path + ".png")
        if (file.exists() && JOptionPane.showConfirmDialog(this, "Replace ${file.name}?", "File exists",
                JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) return
        runCatching { addReference(bounds, file) }
            .onFailure { showError("Could not save reference point", it) }
    }

    internal fun addReference(bounds: Rectangle, file: File) {
        val image = requireNotNull(canvas.image) { "Import a template image first." }
        require(bounds.width > 0 && bounds.height > 0 && Rectangle(0, 0, image.width, image.height).contains(bounds)) {
            "Reference point must be inside the template image."
        }
        PdfTemplateImages.writePng(image.getSubimage(bounds.x, bounds.y, bounds.width, bounds.height), file)
        if (!references.contains(file.absolutePath)) references.addElement(file.absolutePath)
        referenceRegions[file.absolutePath] = Rectangle(bounds)
        referenceList.setSelectedValue(file.absolutePath, true)
        refreshRegions()
        status.text = "Reference point saved: ${file.name}. Save the page template to keep its reference."
    }

    private fun cancelSelection() {
        clickToAssign.isSelected = false
        areaAction = null
        newFieldName = null
        canvas.creatingRegion = false
        canvas.gridEditTool = null
        canvas.cancelDrag()
    }

    private fun refreshRegions() {
        canvas.fields = (0 until fields.size()).map { fields[it] }
        canvas.references = referenceRegions.toMap()
        canvas.selectedField = fieldList.selectedIndex
        catalogList.repaint()
        canvas.repaint()
    }

    fun relocateTemplateFile(file: File) {
        val previous = requireNotNull(templateFile) { "This editor has no saved template file." }
        val from = previous.absoluteFile.parentFile
        val to = file.absoluteFile.parentFile
        imagePath = TemplateFiles.relocatePath(imagePath, from, to)
        for (index in 0 until references.size()) {
            val path = references[index]
            val relocated = TemplateFiles.relocatePath(path, from, to)
            references.set(index, relocated)
            referenceRegions.remove(path)?.let { referenceRegions[relocated] = it }
        }
        if (navn.text == from.name) navn.text = to.name
        templateFile = file
        refreshRegions()
        status.text = "Page number changed: ${file.parentFile.name}"
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
            templateBilde = imagePath,
            gridSettings = canvas.gridSettings,
            gridEdits = canvas.gridEdits,
            referansepunkter = (0 until references.size()).map { references[it] },
        )
        val saveCallback = onSave
        val destination = if (saveCallback == null) templateFile ?: chooseTemplateDestination() ?: return else null
        runCatching {
            val directory = destination?.absoluteFile?.parentFile ?: File(imagePath).absoluteFile.parentFile
            val file = File(directory, "template.png")
            PdfTemplateImages.writePng(requireNotNull(canvas.image), file)
            val saved = updated.copy(templateBilde = file.absolutePath)
            if (saveCallback != null) {
                saveCallback(saved)
            } else {
                TemplateFiles.write(requireNotNull(destination), saved)
                templateFile = destination
            }
            imagePath = saved.templateBilde
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
    private val onGridEdited: (GridEdit) -> Unit = {},
    private val onContourSelected: (Int) -> Unit = {},
) : JPanel(BorderLayout()) {
    var image: BufferedImage? = null
        set(value) {
            field = value
            gridEdits = emptyList()
            gridPreview = null
            gridSnapLines = emptyList()
            displayingCells = false
            gridEditTool = null
            cancelDrag()
        }
    var gridSettings = GridSettings()
    var gridEdits: List<GridEdit> = emptyList()
    private var gridPreview: BufferedImage? = null
    private var gridSnapLines: List<GridSnapLine> = emptyList()
    var snapCellsToGrid = true
    private var displayingCells = false
    var showGridMask = false
        set(value) {
            field = value
            repaint()
        }
    var gridEditTool: GridEditTool? = null
        set(value) {
            field = value
            cancelDrag()
            cursor = Cursor.getPredefinedCursor(if (value != null || creatingRegion) Cursor.CROSSHAIR_CURSOR else Cursor.DEFAULT_CURSOR)
        }
    var fields: List<Felter> = emptyList()
    var references: Map<String, Rectangle> = emptyMap()
    var selectedReference: String? = null
    /** Where the required text is looked for, in image pixels; null = the whole page. */
    var textArea: Rectangle? = null
        set(value) { field = value; repaint() }
    /** Text boxes read in the last required-text test, in image pixels; those with a word found are highlighted. */
    var textBoxes: List<Pair<Rectangle, Boolean>> = emptyList()
        set(value) { field = value; repaint() }

    fun replaceImageContent(value: BufferedImage) {
        val edits = gridEdits
        image = value
        gridEdits = edits
    }
    var selectedField = -1
    var countours: List<Rect> = emptyList()
    var selectedContour: Int? = null
        set(value) {
            field = value
            repaint()
        }
    var corners: List<Point> = emptyList()
    var selectedCorner: Int? = null
        set(value) {
            field = value
            repaint()
        }
    var showAllContours = true
        set(value) {
            field = value
            repaint()
        }

    fun showContours(
        mode: RetreivalMode = RetreivalMode.RETR_EXTERNAL,
        method: RetreivalMethod = RetreivalMethod.CHAIN_APPROX_SIMPLE,
    ): List<ContourBox> {
        val bufferedImage = requireNotNull(image) { "Importer et malbilde først." }
        displayingCells = false
        val contours = findContourBoxes(toMat(bufferedImage), mode, method)
        countours = contours.map { it.bounds }
        selectedContour = null
        corners = emptyList()
        selectedCorner = null
        repaint()
        return contours
    }

    fun showCells(): List<ContourBox> {
        val bufferedImage = requireNotNull(image) { "Importer et malbilde først." }
        val cells = toMat(bufferedImage).use { source ->
            createGridMask(source, gridSettings, gridEdits).use { mask ->
                val preview = toBufferedImage(mask)
                // Detach the preview from the converter's native-backed image storage.
                gridPreview = BufferedImage(preview.colorModel, preview.copyData(null), preview.isAlphaPremultiplied, null)
                gridSnapLines = findGridSnapLines(mask, gridSettings.minimumCellSize)
                findGridCells(mask, gridSettings.minimumCellSize)
            }
        }
        displayingCells = true
        countours = cells.map { it.bounds }
        selectedContour = null
        corners = emptyList()
        selectedCorner = null
        repaint()
        return cells
    }

    fun showFeatures(type: FeatureDetectorType): List<Point> = showPoints { source -> findFeatures(source, type) }

    private fun showPoints(detect: (Mat) -> List<Point>): List<Point> {
        val bufferedImage = requireNotNull(image) { "Importer et malbilde først." }
        val detected = toMat(bufferedImage).use(detect)
        displayingCells = false
        countours = emptyList()
        selectedContour = null
        corners = detected
        selectedCorner = null
        repaint()
        return detected
    }

    var creatingRegion = false
        set(value) {
            field = value
            cursor = Cursor.getPredefinedCursor(if (value) Cursor.CROSSHAIR_CURSOR else Cursor.DEFAULT_CURSOR)
        }
    private var dragStart: Point? = null
    private var selection: Rectangle? = null
    private var gridSelection: GridEdit? = null
    private var viewFildBoxes: Boolean = false

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
                if (gridEditTool != null) gridSelection = selectedGridEdit(event.point)
                else selection = selectedRegion(event.point)
                repaint()
            }

            override fun mouseReleased(event: MouseEvent) {
                if (!SwingUtilities.isLeftMouseButton(event)) return
                val start = dragStart ?: return
                if (gridEditTool != null) {
                    val edit = selectedGridEdit(event.point)
                    cancelDrag()
                    if (edit != null) onGridEdited(edit)
                    return
                }
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
                    val contour = countours.indices.filter { index ->
                        val box = countours[index]
                        Rectangle(box.x(), box.y(), box.width(), box.height()).contains(point)
                    }.minByOrNull { countours[it].width().toLong() * countours[it].height() }
                    if (contour != null) {
                        onContourSelected(contour)
                        return
                    }
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
        gridSelection = null
        repaint()
    }

    fun viewFieldCheckbox(viewFileds: Boolean) {
        this.viewFildBoxes = viewFileds
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

    private fun selectedGridEdit(end: Point): GridEdit? {
        val tool = gridEditTool ?: return null
        val start = dragStart ?: return null
        val source = image ?: return null
        val bounds = imageBounds()
        if (bounds.isEmpty) return null
        fun x(point: Point) = ((point.x - bounds.x).toDouble() * source.width / bounds.width)
            .roundToInt().coerceIn(0, source.width - 1)
        fun y(point: Point) = ((point.y - bounds.y).toDouble() * source.height / bounds.height)
            .roundToInt().coerceIn(0, source.height - 1)
        val x1 = x(start)
        val y1 = y(start)
        val x2 = if (tool == GridEditTool.VERTICAL) x1 else x(end)
        val y2 = if (tool == GridEditTool.HORIZONTAL) y1 else y(end)
        if (x1 == x2 && y1 == y2) return null
        if ((tool == GridEditTool.CELL || tool == GridEditTool.ERASE) && (x1 == x2 || y1 == y2)) return null
        val edit = GridEdit(tool, x1, y1, x2, y2)
        return if (snapCellsToGrid && tool != GridEditTool.ERASE) {
            snapGridEdit(edit, gridSnapLines,
                ceil(8.0 * source.width / bounds.width).toInt(),
                ceil(8.0 * source.height / bounds.height).toInt())
        } else edit
    }

    override fun paintComponent(graphics: Graphics) {
        super.paintComponent(graphics)
        val g = graphics.create() as Graphics2D
        try {
            val source = image
            if (source == null) {
                val text = "Importer et malbilde for å markere felter"
                g.color = Color.DARK_GRAY
                g.drawString(text, maxOf(16, (width - g.fontMetrics.stringWidth(text)) / 2), height / 2)
                return
            }
            val bounds = imageBounds()
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.drawImage(if (showGridMask && displayingCells) gridPreview ?: source else source,
                bounds.x, bounds.y, bounds.width, bounds.height, null)
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
            if(viewFildBoxes) {
                fields.forEachIndexed { index, field ->
                    drawRegion(field.bounds(), Color(220, 45, 45), index == selectedField)
                }
                references.forEach { (path, region) -> drawRegion(region, Color(30, 155, 65), path == selectedReference) }
            }
            countours.forEachIndexed { index, countour ->
                if (showAllContours || index == selectedContour) {
                    drawRegion(
                        Rectangle(countour.x(), countour.y(), countour.width(), countour.height()),
                        when {
                            fields.any { it.bounds() == Rectangle(countour.x(), countour.y(), countour.width(), countour.height()) } -> Color(30, 155, 65)
                            else -> Color(220, 45, 45)
                        }, index == selectedContour,
                    )
                }
            }
            corners.forEachIndexed { index, corner ->
                if (showAllContours || index == selectedCorner) {
                    val x = bounds.x + (corner.x.toDouble() * bounds.width / source.width).roundToInt()
                    val y = bounds.y + (corner.y.toDouble() * bounds.height / source.height).roundToInt()
                    g.color = Color(0, 145, 220)
                    g.stroke = BasicStroke(if (index == selectedCorner) 2.5f else 1.5f)
                    g.drawOval(x - 5, y - 5, 10, 10)
                    g.drawLine(x - 7, y, x + 7, y)
                    g.drawLine(x, y - 7, x, y + 7)
                }
            }
            textBoxes.forEach { (box, found) -> drawRegion(box, if (found) Color(230, 120, 0) else Color(230, 120, 0, 90), found) }
            textArea?.let { area ->
                val dashed = BasicStroke(2f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f, floatArrayOf(8f, 6f), 0f)
                g.color = Color(230, 120, 0)
                g.stroke = dashed
                g.drawRect(bounds.x + (area.x.toDouble() * bounds.width / source.width).roundToInt(),
                    bounds.y + (area.y.toDouble() * bounds.height / source.height).roundToInt(),
                    (area.width.toDouble() * bounds.width / source.width).roundToInt(),
                    (area.height.toDouble() * bounds.height / source.height).roundToInt())
            }
            if (displayingCells) {
                fun drawEdit(edit: GridEdit, preview: Boolean) {
                    val x1 = bounds.x + (edit.x1.toDouble() * bounds.width / source.width).roundToInt()
                    val y1 = bounds.y + (edit.y1.toDouble() * bounds.height / source.height).roundToInt()
                    val x2 = bounds.x + (edit.x2.toDouble() * bounds.width / source.width).roundToInt()
                    val y2 = bounds.y + (edit.y2.toDouble() * bounds.height / source.height).roundToInt()
                    g.color = if (edit.tool == GridEditTool.ERASE) Color(220, 130, 0) else Color(0, 145, 220)
                    g.stroke = BasicStroke(if (preview) 3f else 1.5f)
                    when (edit.tool) {
                        GridEditTool.HORIZONTAL -> g.drawLine(x1, y1, x2, y1)
                        GridEditTool.VERTICAL -> g.drawLine(x1, y1, x1, y2)
                        GridEditTool.CELL, GridEditTool.ERASE -> g.drawRect(minOf(x1, x2), minOf(y1, y2),
                            abs(x2 - x1),
                            abs(y2 - y1)
                        )
                    }
                }
                // The mask shows the final result; overlays on the image show the edit history.
                if (!showGridMask) gridEdits.forEach { drawEdit(it, false) }
                gridSelection?.let { drawEdit(it, true) }
            }
            selection?.let { drawRegion(it, Color(220, 45, 45), true) }
        } finally {
            g.dispose()
        }
    }

    private fun Felter.bounds() = Rectangle(x, y, bredde, hoyde)
}
