package no.nav.tsm.ocr.template.view

import org.example.no.nav.tsm.ocr.template.template.Felter
import org.example.no.nav.tsm.ocr.template.template.Template
import org.example.no.nav.tsm.ocr.template.template.TemplateFiles
import no.nav.tsm.sykmelding.ocr.Ocr
import no.nav.tsm.sykmelding.ocr.PSM_MODE
import no.nav.tsm.sykmelding.ocr.ReferencesRectangle
import no.nav.tsm.sykmelding.ocr.SykmeldingOcr
import org.bytedeco.javacpp.PointerScope
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.GridLayout
import java.awt.Color
import java.awt.GraphicsEnvironment
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutionException
import javax.imageio.ImageIO
import javax.swing.*
import kotlin.math.roundToInt

class ImageComparisonView(
    private val findReferences: (BufferedImage, BufferedImage) -> List<ReferencesRectangle> = { image, reference ->
        val matcher = SykmeldingOcr(Ocr())
        PointerScope().use { matcher.findReferences(image, reference) }
    },
    private val recognizeText: (BufferedImage, PSM_MODE) -> String? = { image, mode ->
        Ocr().parse(image, psmMode = mode)
    },
    private val onStatus: (String) -> Unit = {},
) : JPanel(BorderLayout()) {
    val templatePanal = ImagePanel()
    val secondPanel = ImagePanel()
    private val panels = listOf(templatePanal, secondPanel)
    private var configuration: Template? = null
    private var configurationApplied = false
    private var alignment: ReferenceAlignment? = null
    private var alignmentError = "Apply reference images first."
    private var originalTemplateImage: BufferedImage? = null
    private var originalReferenceImages = emptyList<BufferedImage>()
    private var resizeWorker: SwingWorker<ResizedImages, Unit>? = null
    private data class ResizedImages(
        val incoming: BufferedImage,
        val template: BufferedImage,
        val references: List<BufferedImage>,
    )
    private val fields = DefaultListModel<Felter>()
    private val fieldList = JList(fields)
    private val summary = textArea("No configuration loaded.")
    private val details = textArea("Select a field to inspect its coordinates.")
    private val fieldOcrDetails = textArea("Apply configuration and select a field to read its value.")
    private var fieldOcrReadStarted = false
    private val readFieldButton = JButton("Read field").apply {
        toolTipText = "Read the selected reference-adjusted incoming field using block OCR."
        isEnabled = false
        addActionListener { readField() }
    }
    private val references = DefaultListModel<File>()
    private val referenceList = JList(references)
    private val referenceDetails = textArea("Select a reference image to locate it on the template.")
    private val pageNumberDetails = textArea("Select a matched reference, then read its page number.")
    private val sykmeldingDetails = textArea("Select the matched top-left letter reference, then detect sykmelding.")
    private enum class OcrTarget { REFERENCE, FIELD }
    private var ocrWorker: SwingWorker<String, Unit>? = null
    private var ocrTarget: OcrTarget? = null
    private var referenceOcrReadStarted = false
    private val readPageNumberButton = JButton("Read page number").apply {
        toolTipText = "Read 'side x av y' from the selected incoming reference using block OCR."
        isEnabled = false
        addActionListener { readPageNumber() }
    }
    private val detectSykmeldingButton = JButton("Detect sykmelding").apply {
        toolTipText = "Check the selected incoming top-left reference for A; retry blank block OCR with character OCR."
        isEnabled = false
        addActionListener { detectSykmelding() }
    }
    private val imageSizes = JLabel("Open both images to adjust their size.")
    private val dpiButton = JButton("Convert both to A4 at 400 DPI").apply {
        toolTipText = "Resample full A4 pages, using the template orientation. Ignores embedded DPI."
        isEnabled = false
        addActionListener { resizeImages(convertToA4 = true) }
    }
    private val sizeButton = JButton("Match incoming size to template").apply {
        toolTipText = "Match exact pixel width and height; may stretch the incoming document."
        isEnabled = false
        addActionListener { resizeImages(convertToA4 = false) }
    }
    private val resetImagesButton = JButton("Reset image sizes").apply {
        isEnabled = false
        addActionListener {
            cancelResize()
            clearReferenceMatches()
            referenceList.clearSelection()
            originalTemplateImage?.let { templatePanal.load(it) }
            referenceImages = originalReferenceImages
            templateMatches = List(referenceImages.size) { null }
            updateFieldList()
            resetSecondImage()
            onStatus("Original image sizes restored — apply reference images again.")
        }
    }
    private var referenceImages = emptyList<BufferedImage>()
    private var templateMatches = emptyList<ReferencesRectangle?>()
    private var incomingMatches = emptyList<ReferencesRectangle?>()
    private var templateWorker: SwingWorker<List<ReferencesRectangle?>, Unit>? = null
    private data class PairedMatches(
        val template: List<ReferencesRectangle?>,
        val incoming: List<ReferencesRectangle?>,
    )
    private var incomingWorker: SwingWorker<PairedMatches, Unit>? = null
    private val applyReferencesButton = JButton("Apply reference images").apply {
        toolTipText = "Normalize both full A4 pages to 400 DPI, then find the reference images."
        isEnabled = false
        addActionListener { applyReferences() }
    }
    private val applyButton = JButton("Apply configuration").apply {
        toolTipText = "Uses affine alignment for three or more non-collinear reference pairs; otherwise uniform scale and rotation."
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
                clearFieldOcr()
                updateFieldDetails()
                refreshFields()
                updateControls()
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
                clearReferenceOcr()
                refreshReferences()
                locateSelectedReference()
                updateControls()
            }
        }
        val images = JPanel(GridLayout(1, 2, 8, 0)).apply {
            minimumSize = Dimension(400, 300)
            add(labeledPanel("Template — reference", templatePanal))
            add(labeledPanel("Incoming document — reference-adjusted fields, unchanged image", secondPanel))
        }
        val sidebar = JScrollPane(createSidebar()).apply {
            preferredSize = Dimension(360, 700)
            minimumSize = Dimension(280, 300)
            horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
            border = BorderFactory.createEmptyBorder()
        }
        add(JSplitPane(JSplitPane.HORIZONTAL_SPLIT, images, sidebar).apply {
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

        cancelResize()
        clearReferenceMatches()
        templatePanal.load(image)
        originalTemplateImage = image
        originalSecondImage?.let { secondPanel.load(it) }
        configuration = template
        configurationApplied = false
        fields.clear()
        template.felter.forEach { fields.addElement(it) }
        references.clear()
        referenceImages = loadedReferences
        originalReferenceImages = loadedReferences
        templateMatches = List(loadedReferences.size) { null }
        template.referansepunkter.forEach { references.addElement(File(it)) }
        summary.text = "${file.name}\n${template.navn}\n\n${template.beskrivelse}\n" +
            "Original template: ${image.width} × ${image.height} px\n${fields.size()} fields\n" +
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
            else -> "Configuration loaded — apply reference images before applying configuration."
        })
        return template
    }

    fun loadReference(image: BufferedImage) {
        cancelResize()
        clearReferenceMatches()
        templatePanal.load(image)
        originalTemplateImage = image
        configuration = null
        configurationApplied = false
        fields.clear()
        references.clear()
        referenceImages = emptyList()
        originalReferenceImages = emptyList()
        summary.text = "No configuration loaded."
        summary.toolTipText = null
        resetSecondImage()
        onStatus("Reference image loaded — load a configuration to display its fields.")
    }

    fun loadSecond(image: BufferedImage) {
        cancelResize()
        clearIncomingMatches()
        originalSecondImage = image
        secondPanel.load(image)
        configurationApplied = false
        updateControls()
        refreshFields()
        onStatus(when {
            applyButton.isEnabled -> "Incoming document loaded — apply reference images before applying configuration."
            applyReferencesButton.isEnabled -> "Incoming document loaded — click Apply reference images. Document not aligned."
            else -> "Incoming document loaded — load a configuration with fields or reference images to apply it."
        })
    }

    fun resetSecondImage() {
        cancelResize()
        clearIncomingMatches()
        originalSecondImage?.let { secondPanel.load(it) }
        configurationApplied = false
        updateControls()
        refreshFields()
    }

    private fun applyConfiguration() {
        if (configuration == null) return
        val incoming = secondPanel.image ?: return
        val currentAlignment = alignment
        if (currentAlignment == null || incomingWorker != null || resizeWorker != null) {
            clearFieldOcr()
            configurationApplied = false
            refreshFields()
            updateControls()
            onStatus("Cannot apply configuration: $alignmentError No incoming field boxes displayed.")
            return
        }
        configurationApplied = true
        showFields.isSelected = true
        refreshFields()
        updateControls()
        val outside = displayedFields().count { field ->
            currentAlignment.transform.region(field).corners.any {
                it.x < 0 || it.y < 0 || it.x > incoming.width || it.y > incoming.height
            }
        }
        val warning = if (outside > 0) " $outside field(s) extend beyond the incoming image." else ""
        onStatus("Configuration applied — reference-adjusted field polygons; image unchanged.$warning")
    }

    private fun updateControls() {
        showFields.isEnabled = configuration != null && !fields.isEmpty
        val ready = templatePanal.image != null && secondPanel.image != null && resizeWorker == null
        applyButton.isEnabled = showFields.isEnabled && ready && incomingWorker == null && templateWorker == null
        applyReferencesButton.isEnabled = !references.isEmpty && ready && incomingWorker == null
        val canReadReference = ready && incomingWorker == null && ocrWorker == null &&
            incomingMatches.getOrNull(referenceList.selectedIndex) != null
        readPageNumberButton.isEnabled = canReadReference
        detectSykmeldingButton.isEnabled = canReadReference
        readFieldButton.isEnabled = ready && incomingWorker == null && templateWorker == null && ocrWorker == null &&
            configurationApplied && alignment != null && fieldList.selectedValue != null
        dpiButton.isEnabled = ready
        sizeButton.isEnabled = ready
        resetImagesButton.isEnabled = originalTemplateImage != null && originalSecondImage != null &&
            (resizeWorker != null || templatePanal.image !== originalTemplateImage || secondPanel.image !== originalSecondImage)
        referenceList.isEnabled = resizeWorker == null
        imageSizes.text = if (ready) {
            val template = requireNotNull(templatePanal.image)
            val incoming = requireNotNull(secondPanel.image)
            "<html>Template: ${template.width} × ${template.height} px<br>" +
                "Incoming: ${incoming.width} × ${incoming.height} px</html>"
        } else if (resizeWorker != null) "Resizing images…" else "Open both images to adjust their size."
    }

    private fun readField() {
        val image = secondPanel.image ?: return
        val field = fieldList.selectedValue ?: return
        val transform = alignment?.transform ?: return
        if (!configurationApplied) return
        startOcr(
            OcrTarget.FIELD, { cropAlignedField(image, field, transform) },
            PSM_MODE.PSM_SINGLE_BLOCK, fieldOcrDetails, "Reading field '${field.navn}'", "Could not read field",
        ) { text ->
            if (text.isBlank()) "No text found in field '${field.navn}' (${field.id})."
            else "Field read: ${field.navn} (${field.id})"
        }
    }

    private fun readPageNumber() = readSelectedReference(
        PSM_MODE.PSM_SINGLE_BLOCK, pageNumberDetails, "Reading page number", "Could not read page number",
    ) { text ->
        val page = parsePageNumber(text)
        "Detected page: ${page.page} of ${page.total}"
    }

    private fun detectSykmelding() = readSelectedReference(
        PSM_MODE.PSM_SINGLE_BLOCK, sykmeldingDetails, "Detecting sykmelding", "Could not detect sykmelding",
        fallbackMode = PSM_MODE.PSM_SINGLE_CHAR,
    ) { text ->
        if (isSykmeldingMarker(text)) "Sykmelding detected: A."
        else "Not a sykmelding: detected ${text.trim()} (expected A)."
    }

    private fun readSelectedReference(
        mode: PSM_MODE,
        resultDetails: JTextArea,
        progress: String,
        failure: String,
        fallbackMode: PSM_MODE? = null,
        interpret: (String) -> String,
    ) {
        val image = secondPanel.image ?: return
        val match = incomingMatches.getOrNull(referenceList.selectedIndex) ?: return
        startOcr(
            OcrTarget.REFERENCE, { bgrCopy(image.getSubimage(match.x, match.y, match.width, match.height)) },
            mode, resultDetails, progress, failure, fallbackMode, interpret,
        )
    }

    private fun startOcr(
        target: OcrTarget,
        cropImage: () -> BufferedImage,
        mode: PSM_MODE,
        resultDetails: JTextArea,
        progress: String,
        failure: String,
        fallbackMode: PSM_MODE? = null,
        interpret: (String) -> String,
    ) {
        val worker = object : SwingWorker<String, Unit>() {
            override fun doInBackground(): String {
                val crop = cropImage()
                val text = checkNotNull(recognizeText(crop, mode)) { "OCR returned no result." }
                return if (text.isBlank() && fallbackMode != null)
                    checkNotNull(recognizeText(crop, fallbackMode)) { "OCR returned no result." }
                else text
            }

            override fun done() {
                if (ocrWorker !== this || isCancelled) return
                ocrWorker = null
                ocrTarget = null
                try {
                    val text = get()
                    val result = try {
                        interpret(text)
                    } catch (e: IllegalArgumentException) {
                        resultDetails.text = "${e.message}\n\nRecognized text:\n$text"
                        onStatus("$failure: ${e.message}")
                        return
                    }
                    resultDetails.text = "$result\n\nRecognized text:\n$text"
                    onStatus(result)
                } catch (e: ExecutionException) {
                    resultDetails.text = "$failure: ${(e.cause ?: e).message}"
                    onStatus(resultDetails.text)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    resultDetails.text = "$failure: ${e.message}"
                    onStatus(resultDetails.text)
                } finally {
                    resultDetails.caretPosition = 0
                    updateControls()
                }
            }
        }
        ocrWorker = worker
        ocrTarget = target
        if (target == OcrTarget.REFERENCE) referenceOcrReadStarted = true else fieldOcrReadStarted = true
        resultDetails.text = "$progress…"
        onStatus("$progress from the incoming document…")
        updateControls()
        worker.execute()
    }

    private fun clearReferenceOcr() {
        cancelOcr(OcrTarget.REFERENCE)
        pageNumberDetails.text = "Select a matched reference, then read its page number."
        pageNumberDetails.caretPosition = 0
        sykmeldingDetails.text = "Select the matched top-left letter reference, then detect sykmelding."
        sykmeldingDetails.caretPosition = 0
        if (referenceOcrReadStarted) onStatus("OCR results cleared — read the selected incoming reference again.")
        referenceOcrReadStarted = false
    }

    private fun clearFieldOcr() {
        cancelOcr(OcrTarget.FIELD)
        fieldOcrDetails.text = "Apply configuration and select a field to read its value."
        fieldOcrDetails.caretPosition = 0
        if (fieldOcrReadStarted) onStatus("Field OCR result cleared — read the selected incoming field again.")
        fieldOcrReadStarted = false
    }

    private fun cancelOcr(target: OcrTarget) {
        if (ocrTarget != target) return
        ocrWorker?.cancel(true)
        ocrWorker = null
        ocrTarget = null
    }

    private fun locateSelectedReference() {
        if (resizeWorker != null || incomingWorker != null) return
        templateWorker?.cancel(true)
        templateWorker = null
        updateControls()
        val index = referenceList.selectedIndex
        if (index < 0 || templateMatches.getOrNull(index) != null) return
        val image = templatePanal.image ?: return
        val reference = referenceImages[index]
        clearAlignment()
        referenceDetails.text = "${references[index].name}\n\nFinding template match…"
        val worker = matchingWorker(
            image, listOf(reference),
            isCurrent = { templateWorker === it },
            completed = { matches ->
                templateWorker = null
                templateMatches = MutableList(referenceImages.size) { templateMatches.getOrNull(it) }
                    .also { it[index] = matches.single() }
                refreshReferences()
                updateControls()
            },
            failed = {
                templateWorker = null
                referenceDetails.text = "${references[index].name}\n\nTemplate matching failed."
                updateControls()
            },
        )
        templateWorker = worker
        updateControls()
        worker.execute()
    }

    private fun applyReferences() {
        val image = secondPanel.image ?: return
        val template = templatePanal.image ?: return
        val size = a4Size()
        if (image.width != size.width || image.height != size.height ||
            template.width != size.width || template.height != size.height) {
            val selectedReference = referenceList.selectedIndex
            resizeImages(convertToA4 = true) {
                applyReferences()
                referenceList.selectedIndex = selectedReference
            }
            return
        }
        templateWorker?.cancel(true)
        templateWorker = null
        clearIncomingMatches()
        val cachedTemplateMatches = templateMatches.toList()
        val searches = referenceImages.toList()
        val worker = object : SwingWorker<PairedMatches, Unit>() {
            override fun doInBackground() = PairedMatches(
                matchReferences(template, searches, cachedTemplateMatches) { isCancelled },
                matchReferences(image, searches) { isCancelled },
            )

            override fun done() {
                if (incomingWorker !== this || isCancelled) return
                incomingWorker = null
                try {
                    val matches = get()
                    templateMatches = matches.template
                    incomingMatches = matches.incoming
                    val pairs = templateMatches.zip(incomingMatches).mapNotNull { (from, to) ->
                        if (from == null || to == null) null else ReferencePair(
                            ImagePoint(from.x + from.width / 2.0, from.y + from.height / 2.0),
                            ImagePoint(to.x + to.width / 2.0, to.y + to.height / 2.0),
                        )
                    }
                    try {
                        alignment = estimateReferenceAlignment(pairs, template.width, template.height)
                        val result = requireNotNull(alignment)
                        val warning = if (result.pairs == 2)
                            " Only two pairs: match correctness cannot be independently checked."
                        else if (result.affine && result.inliers == 3)
                            " Only three supporting pairs: affine alignment cannot be independently checked."
                        else ""
                        onStatus("Reference alignment ready — ${result.inliers}/${result.pairs} consistent pairs." +
                            " ${if (result.affine) "Affine" else "Uniform-scale"} alignment.$warning Apply configuration to display adjusted fields.")
                    } catch (e: IllegalArgumentException) {
                        alignmentError = e.message ?: "Reference alignment failed."
                        onStatus("Cannot align fields: $alignmentError No incoming field boxes displayed.")
                    }
                    refreshReferences()
                } catch (e: ExecutionException) {
                    updateControls()
                    reportMatchingFailure(e.cause ?: e)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    updateControls()
                    reportMatchingFailure(e)
                } finally {
                    updateControls()
                }
            }
        }
        incomingWorker = worker
        updateControls()
        val missing = searches.indices.count { cachedTemplateMatches.getOrNull(it) == null }
        onStatus("Finding reference images — $missing template search(es), ${searches.size} incoming search(es).")
        worker.execute()
    }

    private fun matchingWorker(
        image: BufferedImage,
        searches: List<BufferedImage>,
        isCurrent: (SwingWorker<List<ReferencesRectangle?>, Unit>) -> Boolean,
        completed: (List<ReferencesRectangle?>) -> Unit,
        failed: () -> Unit,
    ) = object : SwingWorker<List<ReferencesRectangle?>, Unit>() {
        override fun doInBackground() = matchReferences(image, searches) { isCancelled }

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

    private fun matchReferences(
        image: BufferedImage,
        searches: List<BufferedImage>,
        cached: List<ReferencesRectangle?> = emptyList(),
        cancelled: () -> Boolean,
    ): List<ReferencesRectangle?> = searches.mapIndexed { index, search ->
        if (cancelled()) throw CancellationException()
        cached.getOrNull(index)?.let { return@mapIndexed it }
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

    private fun reportMatchingFailure(error: Throwable) {
        alignmentError = "Reference matching failed: ${error.message}"
        onStatus("Could not match reference images: ${error.message}")
        if (!GraphicsEnvironment.isHeadless()) {
            JOptionPane.showMessageDialog(this, error.message, "Could not match reference images", JOptionPane.ERROR_MESSAGE)
        }
    }

    private fun bgrCopy(image: BufferedImage, width: Int = image.width, height: Int = image.height) =
        BufferedImage(width, height, BufferedImage.TYPE_3BYTE_BGR).apply {
            val graphics = createGraphics()
            try {
                graphics.color = Color.WHITE
                graphics.fillRect(0, 0, width, height)
                graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
                graphics.drawImage(image, 0, 0, width, height, null)
            } finally {
                graphics.dispose()
            }
        }

    private fun a4Size(): Dimension {
        val template = requireNotNull(originalTemplateImage)
        return if (template.width > template.height) Dimension(4677, 3307) else Dimension(3307, 4677)
    }

    private fun resizeImages(convertToA4: Boolean, onResized: (() -> Unit)? = null) {
        val template = originalTemplateImage ?: return
        val incoming = originalSecondImage ?: return
        val displayed = templatePanal.image ?: return
        val size = if (convertToA4) a4Size() else Dimension(displayed.width, displayed.height)
        val width = size.width
        val height = size.height
        val crops = originalReferenceImages
        val displayedCrops = referenceImages
        val templateChanges = convertToA4 && (displayed.width != width || displayed.height != height)
        if (templateChanges) clearReferenceMatches() else clearIncomingMatches()
        referenceList.clearSelection()
        if (templateChanges) templateMatches = List(crops.size) { null }
        configurationApplied = false
        refreshFields()
        val worker = object : SwingWorker<ResizedImages, Unit>() {
            override fun doInBackground(): ResizedImages {
                fun resize(image: BufferedImage, w: Int, h: Int) =
                    if (image.width == w && image.height == h) image else bgrCopy(image, w, h)
                return ResizedImages(
                    incoming = resize(incoming, width, height),
                    template = if (templateChanges) resize(template, width, height) else displayed,
                    references = if (templateChanges) crops.map {
                        resize(it, (it.width.toDouble() * width / template.width).roundToInt().coerceAtLeast(1),
                            (it.height.toDouble() * height / template.height).roundToInt().coerceAtLeast(1))
                    } else displayedCrops,
                )
            }

            override fun done() {
                if (resizeWorker !== this || isCancelled) return
                resizeWorker = null
                try {
                    val resized = get()
                    if (templateChanges) {
                        templatePanal.load(resized.template)
                        referenceImages = resized.references
                        updateFieldList()
                    }
                    secondPanel.load(resized.incoming)
                    refreshFields()
                    refreshReferences()
                    onStatus(if (convertToA4)
                        "Both images resampled as A4 at 400 DPI — $width × $height px. Apply reference images again."
                    else "Incoming image resized to $width × $height px — no rotation or position alignment. Apply reference images again.")
                    onResized?.invoke()
                } catch (e: ExecutionException) {
                    reportResizeFailure(e.cause ?: e)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    reportResizeFailure(e)
                } finally {
                    updateControls()
                }
            }
        }
        resizeWorker = worker
        updateControls()
        onStatus("Resizing images…")
        worker.execute()
    }

    private fun reportResizeFailure(error: Throwable) {
        onStatus("Could not resize images: ${error.message}")
        if (!GraphicsEnvironment.isHeadless()) {
            JOptionPane.showMessageDialog(this, error.message, "Could not resize images", JOptionPane.ERROR_MESSAGE)
        }
    }

    private fun cancelResize() {
        resizeWorker?.cancel(true)
        resizeWorker = null
    }

    private fun displayedFields(): List<Felter> {
        val original = originalTemplateImage ?: return emptyList()
        val current = templatePanal.image ?: return emptyList()
        return configuration?.felter.orEmpty().map { field ->
            val x = (field.x.toDouble() * current.width / original.width).roundToInt().coerceAtMost(current.width - 1)
            val y = (field.y.toDouble() * current.height / original.height).roundToInt().coerceAtMost(current.height - 1)
            field.copy(
                x = x, y = y,
                bredde = ((field.x.toLong() + field.bredde).toDouble() * current.width / original.width)
                    .roundToInt().minus(x).coerceAtLeast(1),
                hoyde = ((field.y.toLong() + field.hoyde).toDouble() * current.height / original.height)
                    .roundToInt().minus(y).coerceAtLeast(1),
            )
        }
    }

    private fun updateFieldList() {
        val selected = fieldList.selectedIndex
        fields.clear()
        displayedFields().forEach { fields.addElement(it) }
        fieldList.selectedIndex = selected
        updateFieldDetails()
    }

    private fun updateFieldDetails() {
        val field = fieldList.selectedValue
        details.text = if (field == null) "Select a field to inspect its coordinates." else
            "${field.navn}\n\nx: ${field.x} px\ny: ${field.y} px\n" +
                "Width: ${field.bredde} px\nHeight: ${field.hoyde} px\n\nID: ${field.id}"
        details.caretPosition = 0
    }

    private fun clearIncomingMatches() {
        clearReferenceOcr()
        incomingWorker?.cancel(true)
        incomingWorker = null
        incomingMatches = emptyList()
        clearAlignment()
        refreshReferences()
    }

    private fun clearAlignment() {
        clearFieldOcr()
        alignment = null
        alignmentError = "Apply reference images first; at least two separated corresponding pairs are required."
        configurationApplied = false
        refreshFields()
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
        val visible = if (showFields.isSelected) displayedFields() else emptyList()
        templatePanal.showFields(visible, fieldList.selectedIndex)
        val transform = alignment?.transform
        secondPanel.showFieldRegions(
            if (configurationApplied && transform != null) visible.map(transform::region) else emptyList(),
            fieldList.selectedIndex,
        )
    }

    private fun Felter.fitsInside(image: BufferedImage): Boolean =
        x >= 0 && y >= 0 && bredde > 0 && hoyde > 0 &&
            x.toLong() + bredde <= image.width && y.toLong() + hoyde <= image.height

    private fun createSidebar() = JPanel(BorderLayout(0, 12)).apply {
        preferredSize = Dimension(340, 1410)
        minimumSize = Dimension(260, 300)
        border = BorderFactory.createEmptyBorder(12, 12, 12, 12)
        add(JPanel(BorderLayout(0, 8)).apply {
            add(JLabel("Configuration").apply { labelFor = summary }, BorderLayout.NORTH)
            add(JScrollPane(summary).apply { preferredSize = Dimension(300, 150) }, BorderLayout.CENTER)
            add(JPanel(GridLayout(0, 1, 0, 4)).apply {
                add(applyButton)
                add(showFields)
                add(dpiButton)
                add(sizeButton)
                add(resetImagesButton)
                add(imageSizes)
            }, BorderLayout.SOUTH)
        }, BorderLayout.NORTH)
        add(JPanel(BorderLayout(0, 12)).apply {
            add(JPanel(BorderLayout(0, 8)).apply {
                preferredSize = Dimension(300, 400)
                add(JLabel("Fields").apply { labelFor = fieldList }, BorderLayout.NORTH)
                add(JScrollPane(fieldList), BorderLayout.CENTER)
                add(JPanel(BorderLayout(0, 8)).apply {
                    add(JScrollPane(details).apply { preferredSize = Dimension(300, 120) }, BorderLayout.NORTH)
                    add(readFieldButton, BorderLayout.CENTER)
                    add(JScrollPane(fieldOcrDetails).apply { preferredSize = Dimension(300, 130) }, BorderLayout.SOUTH)
                }, BorderLayout.SOUTH)
            }, BorderLayout.NORTH)
            add(JPanel(BorderLayout(0, 8)).apply {
                add(JLabel("Reference images").apply { labelFor = referenceList }, BorderLayout.NORTH)
                add(JScrollPane(referenceList), BorderLayout.CENTER)
                add(JPanel(BorderLayout(0, 8)).apply {
                    add(JScrollPane(referenceDetails).apply { preferredSize = Dimension(300, 120) }, BorderLayout.CENTER)
                    add(JPanel(BorderLayout(0, 8)).apply {
                        add(JPanel(GridLayout(0, 1, 0, 4)).apply {
                            add(applyReferencesButton)
                            add(readPageNumberButton)
                            add(detectSykmeldingButton)
                        }, BorderLayout.NORTH)
                        add(JScrollPane(pageNumberDetails).apply {
                            preferredSize = Dimension(300, 130)
                        }, BorderLayout.CENTER)
                        add(JScrollPane(sykmeldingDetails).apply {
                            preferredSize = Dimension(300, 110)
                        }, BorderLayout.SOUTH)
                    }, BorderLayout.SOUTH)
                }, BorderLayout.SOUTH)
            }, BorderLayout.CENTER)
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
