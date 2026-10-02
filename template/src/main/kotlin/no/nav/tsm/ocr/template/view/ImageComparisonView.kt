package no.nav.tsm.ocr.template.view

import org.example.no.nav.tsm.ocr.template.template.Felter
import org.example.no.nav.tsm.ocr.template.template.Template
import org.example.no.nav.tsm.ocr.template.template.TemplateFiles
import no.nav.tsm.sykmelding.ocr.Ocr
import no.nav.tsm.sykmelding.ocr.ReferencesRectangle
import no.nav.tsm.sykmelding.ocr.SykmeldingOcr
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.GridLayout
import java.awt.Color
import java.awt.GraphicsEnvironment
import java.awt.image.BufferedImage
import java.io.File
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutionException
import javax.imageio.ImageIO
import javax.swing.*

class ImageComparisonView(
    private val findReferences: (BufferedImage, BufferedImage) -> List<ReferencesRectangle> = { image, reference ->
        SykmeldingOcr(Ocr()).findReferences(image, reference)
    },
    private val onStatus: (String) -> Unit = {},
) : JPanel(BorderLayout()) {
    val templatePanal = ImagePanel()
    val secondPanel = ImagePanel()
    private val panels = listOf(templatePanal, secondPanel)
    private var configuration: Template? = null
    private var configurationApplied = false
    private val fields = DefaultListModel<Felter>()
    private val fieldList = JList(fields)
    private val summary = textArea("No configuration loaded.")
    private val details = textArea("Select a field to inspect its coordinates.")
    private val references = DefaultListModel<File>()
    private val referenceList = JList(references)
    private val referenceDetails = textArea("Select a reference image to locate it on the template.")
    private var referenceImages = emptyList<BufferedImage>()
    private var templateMatches = emptyList<ReferencesRectangle?>()
    private var incomingMatches = emptyList<ReferencesRectangle?>()
    private var templateWorker: SwingWorker<List<ReferencesRectangle?>, Unit>? = null
    private var incomingWorker: SwingWorker<List<ReferencesRectangle?>, Unit>? = null
    private val applyReferencesButton = JButton("Apply reference images").apply {
        isEnabled = false
        addActionListener { applyReferences() }
    }
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
        referenceList.selectionMode = ListSelectionModel.SINGLE_SELECTION
        referenceList.cellRenderer = object : DefaultListCellRenderer() {
            override fun getListCellRendererComponent(
                list: JList<*>?, value: Any?, index: Int, selected: Boolean, focus: Boolean,
            ): Component = super.getListCellRendererComponent(
                list, (value as? File)?.name.orEmpty(), index, selected, focus,
            )
        }
        referenceList.addListSelectionListener {
            if (!it.valueIsAdjusting) {
                refreshReferences()
                locateSelectedReference()
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
        val loadedReferences = template.referansepunkter.map { path ->
            requireNotNull(ImageIO.read(File(path))) { "Unsupported or unreadable reference image: $path" }.also {
                require(it.width <= image.width && it.height <= image.height) {
                    "Reference image '${File(path).name}' is larger than the template image."
                }
            }
        }

        clearReferenceMatches()
        templatePanal.load(image)
        configuration = template
        configurationApplied = false
        fields.clear()
        template.felter.forEach { fields.addElement(it) }
        references.clear()
        referenceImages = loadedReferences
        templateMatches = List(loadedReferences.size) { null }
        template.referansepunkter.forEach { references.addElement(File(it)) }
        summary.text = "${file.name}\n${template.navn}\n\n${template.beskrivelse}\n" +
            "${image.width} × ${image.height} px\n${fields.size()} fields\n" +
            "${template.referansepunkter.size} reference images"
        summary.caretPosition = 0
        summary.toolTipText = file.absolutePath
        showFields.isSelected = true
        updateControls()
        refreshFields()
        onStatus(when {
            fields.isEmpty && references.isEmpty -> "Configuration loaded — it contains no fields or reference images."
            originalSecondImage == null -> "Configuration loaded — open an incoming document to apply it."
            fields.isEmpty -> "Configuration loaded — select a reference image or click Apply reference images. Document not aligned."
            else -> "Configuration loaded — click Apply configuration. Document not aligned."
        })
        return template
    }

    fun loadReference(image: BufferedImage) {
        clearReferenceMatches()
        templatePanal.load(image)
        configuration = null
        configurationApplied = false
        fields.clear()
        references.clear()
        referenceImages = emptyList()
        summary.text = "No configuration loaded."
        summary.toolTipText = null
        resetSecondImage()
        onStatus("Reference image loaded — load a configuration to display its fields.")
    }

    fun loadSecond(image: BufferedImage) {
        clearIncomingMatches()
        originalSecondImage = image
        secondPanel.load(image)
        configurationApplied = false
        updateControls()
        refreshFields()
        onStatus(when {
            applyButton.isEnabled -> "Incoming document loaded — click Apply configuration. Document not aligned."
            applyReferencesButton.isEnabled -> "Incoming document loaded — click Apply reference images. Document not aligned."
            else -> "Incoming document loaded — load a configuration with fields or reference images to apply it."
        })
    }

    fun resetSecondImage() {
        clearIncomingMatches()
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
        applyReferencesButton.isEnabled = !references.isEmpty && originalSecondImage != null && incomingWorker == null
    }

    private fun locateSelectedReference() {
        templateWorker?.cancel(true)
        templateWorker = null
        val index = referenceList.selectedIndex
        if (index < 0 || templateMatches.getOrNull(index) != null) return
        val image = templatePanal.image ?: return
        val reference = referenceImages[index]
        referenceDetails.text = "${references[index].name}\n\nFinding template match…"
        val worker = matchingWorker(
            image, listOf(reference),
            isCurrent = { templateWorker === it },
            completed = { matches ->
                templateWorker = null
                templateMatches = templateMatches.toMutableList().also { it[index] = matches.single() }
                refreshReferences()
            },
            failed = {
                templateWorker = null
                referenceDetails.text = "${references[index].name}\n\nTemplate matching failed."
            },
        )
        templateWorker = worker
        worker.execute()
    }

    private fun applyReferences() {
        val image = originalSecondImage ?: return
        val worker = matchingWorker(
            image, referenceImages,
            isCurrent = { incomingWorker === it },
            completed = { matches ->
                incomingWorker = null
                incomingMatches = matches
                refreshReferences()
                updateControls()
                onStatus("Reference images applied — ${matches.count { it != null }} match(es). Document not aligned.")
            },
            failed = {
                incomingWorker = null
                updateControls()
            },
        )
        incomingWorker = worker
        updateControls()
        onStatus("Finding reference images on the incoming document…")
        worker.execute()
    }

    private fun matchingWorker(
        image: BufferedImage,
        searches: List<BufferedImage>,
        isCurrent: (SwingWorker<List<ReferencesRectangle?>, Unit>) -> Boolean,
        completed: (List<ReferencesRectangle?>) -> Unit,
        failed: () -> Unit,
    ) = object : SwingWorker<List<ReferencesRectangle?>, Unit>() {
        override fun doInBackground(): List<ReferencesRectangle?> = searches.map { search ->
            require(search.width <= image.width && search.height <= image.height) {
                "A reference image (${search.width} × ${search.height} px) is larger than the document."
            }
            // The library's first rectangle describes the matched crop; the second describes the full image.
            findReferences(bgrCopy(image), bgrCopy(search)).firstOrNull()?.also {
                require(it.x >= 0 && it.y >= 0 && it.width == search.width && it.height == search.height &&
                    it.x.toLong() + it.width <= image.width && it.y.toLong() + it.height <= image.height) {
                    "Reference matching returned an invalid rectangle."
                }
            }
        }

        override fun done() {
            if (!isCurrent(this) || isCancelled) return
            try {
                completed(get())
            } catch (_: CancellationException) {
                failed()
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                failed()
                reportMatchingFailure(e)
            } catch (e: ExecutionException) {
                failed()
                reportMatchingFailure(e.cause ?: e)
            }
        }
    }

    private fun reportMatchingFailure(error: Throwable) {
        onStatus("Could not match reference images: ${error.message}")
        if (!GraphicsEnvironment.isHeadless()) {
            JOptionPane.showMessageDialog(this, error.message, "Could not match reference images", JOptionPane.ERROR_MESSAGE)
        }
    }

    private fun bgrCopy(image: BufferedImage) =
        BufferedImage(image.width, image.height, BufferedImage.TYPE_3BYTE_BGR).apply {
            val graphics = createGraphics()
            try {
                graphics.color = Color.WHITE
                graphics.fillRect(0, 0, width, height)
                graphics.drawImage(image, 0, 0, null)
            } finally {
                graphics.dispose()
            }
        }

    private fun clearIncomingMatches() {
        incomingWorker?.cancel(true)
        incomingWorker = null
        incomingMatches = emptyList()
        refreshReferences()
    }

    private fun clearReferenceMatches() {
        templateWorker?.cancel(true)
        templateWorker = null
        templateMatches = emptyList()
        clearIncomingMatches()
    }

    private fun refreshReferences() {
        val index = referenceList.selectedIndex
        templatePanal.showReferences(templateMatches, index)
        secondPanel.showReferences(incomingMatches, index)
        referenceDetails.text = if (index < 0) "Select a reference image to locate it on the template." else {
            fun describe(match: ReferencesRectangle?) = match?.let {
                "x: ${it.x} px, y: ${it.y} px\nWidth: ${it.width} px, Height: ${it.height} px"
            } ?: "No match displayed."
            "${references[index].name}\n\nTemplate: ${describe(templateMatches.getOrNull(index))}\n\n" +
                "Incoming: ${describe(incomingMatches.getOrNull(index))}"
        }
        referenceDetails.caretPosition = 0
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
        add(JPanel(GridLayout(2, 1, 0, 12)).apply {
            add(JPanel(BorderLayout(0, 8)).apply {
                add(JLabel("Fields").apply { labelFor = fieldList }, BorderLayout.NORTH)
                add(JScrollPane(fieldList), BorderLayout.CENTER)
                add(JScrollPane(details).apply { preferredSize = Dimension(300, 120) }, BorderLayout.SOUTH)
            })
            add(JPanel(BorderLayout(0, 8)).apply {
                add(JLabel("Reference images").apply { labelFor = referenceList }, BorderLayout.NORTH)
                add(JScrollPane(referenceList), BorderLayout.CENTER)
                add(JPanel(BorderLayout(0, 8)).apply {
                    add(JScrollPane(referenceDetails).apply { preferredSize = Dimension(300, 120) }, BorderLayout.CENTER)
                    add(applyReferencesButton, BorderLayout.SOUTH)
                }, BorderLayout.SOUTH)
            })
        }, BorderLayout.CENTER)
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
