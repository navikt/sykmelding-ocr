package no.nav.tsm.ocr.template.view

import org.example.no.nav.tsm.ocr.template.template.Felter
import org.example.no.nav.tsm.ocr.template.template.Template
import org.example.no.nav.tsm.ocr.template.template.TemplateFiles
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.GridLayout
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import javax.swing.*

class ImageComparisonView(private val onStatus: (String) -> Unit = {}) : JPanel(BorderLayout()) {
    val templatePanal = ImagePanel()
    val secondPanel = ImagePanel()
    private val panels = listOf(templatePanal, secondPanel)
    private var configuration: Template? = null
    private var configurationApplied = false
    private val fields = DefaultListModel<Felter>()
    private val fieldList = JList(fields)
    private val summary = textArea("No configuration loaded.")
    private val details = textArea("Select a field to inspect its coordinates.")
    private val applyButton = JButton("Apply configuration").apply {
        isEnabled = false
        addActionListener { applyConfiguration() }
    }
    private val showFields = JCheckBox("Show field boxes", true).apply {
        isEnabled = false
        addActionListener { refreshFields() }
    }
    var originalSecondImage: BufferedImage? = null
        private set

    init {
        fieldList.selectionMode = ListSelectionModel.SINGLE_SELECTION
        fieldList.cellRenderer = object : DefaultListCellRenderer() {
            override fun getListCellRendererComponent(
                list: JList<*>?, value: Any?, index: Int, selected: Boolean, focus: Boolean,
            ): Component = super.getListCellRendererComponent(
                list, (value as? Felter)?.navn.orEmpty(), index, selected, focus,
            )
        }
        fieldList.addListSelectionListener {
            if (!it.valueIsAdjusting) {
                val field = fieldList.selectedValue
                details.text = if (field == null) "Select a field to inspect its coordinates." else
                    "${field.navn}\n\nx: ${field.x} px\ny: ${field.y} px\n" +
                        "Width: ${field.bredde} px\nHeight: ${field.hoyde} px\n\nID: ${field.id}"
                details.caretPosition = 0
                refreshFields()
            }
        }
        val images = JPanel(GridLayout(1, 2, 8, 0)).apply {
            minimumSize = Dimension(400, 300)
            add(labeledPanel("Template — reference", templatePanal))
            add(labeledPanel("Incoming document — not aligned", secondPanel))
        }
        add(JSplitPane(JSplitPane.HORIZONTAL_SPLIT, images, createSidebar()).apply {
            resizeWeight = 1.0
            isContinuousLayout = true
            border = BorderFactory.createEmptyBorder()
        }, BorderLayout.CENTER)
    }

    fun loadConfiguration(file: File): Template {
        val template = TemplateFiles.read(file)
        val image = requireNotNull(ImageIO.read(File(template.templateBilde))) {
            "Unsupported or unreadable template image: ${template.templateBilde}"
        }
        require(template.felter.map { it.id }.distinct().size == template.felter.size) {
            "Configuration contains duplicate field IDs."
        }
        template.felter.forEach { field ->
            require(field.id.isNotBlank() && field.navn.isNotBlank()) { "Each field needs an ID and a name." }
            require(field.fitsInside(image)) { "Field '${field.navn}' is outside the template image or has invalid dimensions." }
        }

        templatePanal.load(image)
        configuration = template
        configurationApplied = false
        fields.clear()
        template.felter.forEach { fields.addElement(it) }
        summary.text = "${file.name}\n${template.navn}\n\n${template.beskrivelse}\n" +
            "${image.width} × ${image.height} px\n${fields.size()} fields\n" +
            "${template.referansepunkter.size} reference images"
        summary.caretPosition = 0
        summary.toolTipText = file.absolutePath
        showFields.isSelected = true
        updateControls()
        refreshFields()
        onStatus(when {
            fields.isEmpty -> "Configuration loaded — it contains no fields."
            originalSecondImage == null -> "Configuration loaded — open an incoming document to apply it."
            else -> "Configuration loaded — click Apply configuration. Document not aligned."
        })
        return template
    }

    fun loadReference(image: BufferedImage) {
        templatePanal.load(image)
        configuration = null
        configurationApplied = false
        fields.clear()
        summary.text = "No configuration loaded."
        summary.toolTipText = null
        resetSecondImage()
        onStatus("Reference image loaded — load a configuration to display its fields.")
    }

    fun loadSecond(image: BufferedImage) {
        originalSecondImage = image
        secondPanel.load(image)
        configurationApplied = false
        updateControls()
        refreshFields()
        onStatus(if (applyButton.isEnabled)
            "Incoming document loaded — click Apply configuration. Document not aligned."
        else "Incoming document loaded — load a configuration with fields to apply it.")
    }

    fun resetSecondImage() {
        originalSecondImage?.let { secondPanel.load(it) }
        configurationApplied = false
        updateControls()
        refreshFields()
    }

    private fun applyConfiguration() {
        val template = configuration ?: return
        val incoming = originalSecondImage ?: return
        configurationApplied = true
        showFields.isSelected = true
        refreshFields()
        val outside = template.felter.count { !it.fitsInside(incoming) }
        val reference = requireNotNull(templatePanal.image)
        val warning = when {
            outside > 0 -> " $outside field(s) extend beyond the incoming image."
            incoming.width != reference.width || incoming.height != reference.height ->
                " Image dimensions differ; coordinates have not been rescaled."
            else -> ""
        }
        onStatus("Configuration applied — document not aligned.$warning")
    }

    private fun updateControls() {
        showFields.isEnabled = configuration != null && !fields.isEmpty
        applyButton.isEnabled = showFields.isEnabled && originalSecondImage != null
    }

    private fun refreshFields() {
        val visible = if (showFields.isSelected) configuration?.felter.orEmpty() else emptyList()
        templatePanal.showFields(visible, fieldList.selectedIndex)
        secondPanel.showFields(if (configurationApplied) visible else emptyList(), fieldList.selectedIndex)
    }

    private fun Felter.fitsInside(image: BufferedImage): Boolean =
        x >= 0 && y >= 0 && bredde > 0 && hoyde > 0 &&
            x.toLong() + bredde <= image.width && y.toLong() + hoyde <= image.height

    private fun createSidebar() = JPanel(BorderLayout(0, 12)).apply {
        preferredSize = Dimension(340, 700)
        minimumSize = Dimension(260, 300)
        border = BorderFactory.createEmptyBorder(12, 12, 12, 12)
        add(JPanel(BorderLayout(0, 8)).apply {
            add(JLabel("Configuration").apply { labelFor = summary }, BorderLayout.NORTH)
            add(JScrollPane(summary).apply { preferredSize = Dimension(300, 150) }, BorderLayout.CENTER)
            add(JPanel(GridLayout(0, 1, 0, 4)).apply {
                add(applyButton)
                add(showFields)
            }, BorderLayout.SOUTH)
        }, BorderLayout.NORTH)
        add(JPanel(BorderLayout(0, 8)).apply {
            add(JLabel("Fields").apply { labelFor = fieldList }, BorderLayout.NORTH)
            add(JScrollPane(fieldList), BorderLayout.CENTER)
        }, BorderLayout.CENTER)
        add(JPanel(BorderLayout(0, 8)).apply {
            add(JLabel("Selected field").apply { labelFor = details }, BorderLayout.NORTH)
            add(JScrollPane(details).apply { preferredSize = Dimension(300, 210) }, BorderLayout.CENTER)
        }, BorderLayout.SOUTH)
    }

    fun showOrbs(show: Boolean = true) = panels.forEach { it.showOrbs(show) }
    fun showLines(show: Boolean = true) = panels.forEach { it.showLines(show) }
    fun showGrids(mode: GridMode?) = panels.forEach { it.showGrids(mode) }

    private fun labeledPanel(label: String, panel: ImagePanel) = JPanel(BorderLayout()).apply {
        border = BorderFactory.createTitledBorder(label)
        add(panel, BorderLayout.CENTER)
    }

    private fun textArea(initialText: String) = JTextArea(initialText).apply {
        isEditable = false
        lineWrap = true
        wrapStyleWord = true
        font = UIManager.getFont("Label.font")
        border = BorderFactory.createEmptyBorder(6, 6, 6, 6)
    }
}
