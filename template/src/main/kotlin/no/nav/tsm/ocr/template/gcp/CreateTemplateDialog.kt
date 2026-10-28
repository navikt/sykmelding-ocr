package no.nav.tsm.ocr.template.gcp

import org.apache.pdfbox.Loader
import org.apache.pdfbox.rendering.ImageType
import org.apache.pdfbox.rendering.PDFRenderer
import no.nav.tsm.ocr.template.template.OrientationGuess
import no.nav.tsm.ocr.template.template.PageOrientation
import no.nav.tsm.ocr.template.template.PageRotation
import no.nav.tsm.sykmelding.template.TemplateGroup
import no.nav.tsm.sykmelding.template.TemplateLibrary
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Cursor
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.GridLayout
import java.awt.Insets
import java.awt.Window
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.image.BufferedImage
import javax.swing.BorderFactory
import javax.swing.ButtonGroup
import javax.swing.ImageIcon
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JDialog
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JRadioButton
import javax.swing.JScrollPane
import javax.swing.JSpinner
import javax.swing.JTextField
import javax.swing.KeyStroke
import javax.swing.SpinnerNumberModel
import javax.swing.SwingConstants
import javax.swing.SwingUtilities
import javax.swing.SwingWorker
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import kotlin.math.ceil

/** What "Create template from" shows for the file in the preview. Page numbers are one-based. */
internal class TemplateDialogInput(
    val fileName: String,
    val pdf: ByteArray,
    val pageCount: Int,
    /** Per page, the template page it matched in the last test, as in the file list; empty when untested. */
    val pageNotes: Map<Int, String> = emptyMap(),
    /** The pages that matched no template page in the last test; null when the file is untested. */
    val unmatchedPages: Set<Int>? = null,
    /** The library's templates, which pages can also be added to. */
    val groups: List<TemplateGroup> = emptyList(),
    /** Whether "open the new pages in the template editor" can be offered. */
    val canOpenEditor: Boolean = false,
)

/**
 * Asks which pages of a PDF become a template, and whether they start a new template or are added to an existing
 * one. Every page is shown as a thumbnail with a checkbox; all are included at first. Each page can be turned by hand
 * or fixed automatically with [PageOrientation.detect] (upside down, sideways, slightly tilted). [request] is null if cancelled.
 */
internal class CreateTemplateDialog(
    owner: Window?,
    private val input: TemplateDialogInput,
    private val library: TemplateLibrary,
) : JDialog(owner, "Create template from ${input.fileName}", ModalityType.APPLICATION_MODAL) {

    var request: TemplateFromPdfRequest? = null
        private set

    private val newTemplate = JRadioButton("New template:", true)
    private val existingTemplate = JRadioButton("Add to template:").apply {
        isEnabled = input.groups.isNotEmpty()
        toolTipText = if (input.groups.isEmpty()) "The library has no templates yet." else null
    }
    private val templateName = JTextField(18).apply {
        toolTipText = "Folder name in ${library.directory.absolutePath}, for example 03.2019"
    }
    private val group = JComboBox(input.groups.map(::GroupItem).toTypedArray()).apply { isEnabled = input.groups.isNotEmpty() }
    private val firstNumber = JSpinner(SpinnerNumberModel(input.groups.firstOrNull()?.let(::nextNumber) ?: 1, 1, 9999, 1)).apply {
        isEnabled = input.groups.isNotEmpty()
        toolTipText = "The template page number the first chosen PDF page gets; the next ones follow."
    }
    private val summary = JLabel(" ")
    private val problem = JLabel(" ").apply { foreground = PROBLEM_COLOR }
    private val create = JButton("Create")
    private val openInEditor = JCheckBox("Open the new pages in the template editor", true).apply { isVisible = input.canOpenEditor }
    private val cells = (1..input.pageCount).map { PageCell(it, input.pageNotes[it]) }
    private val thumbnails = ThumbnailRenderer()
    private val fixAll = JButton("Rotate + straighten included pages").apply {
        toolTipText = "<html>Turn every included page upright and straighten it: Tesseract finds which way up the text is,<br>" +
            "and the tilt of a scan is measured from its text rows and form lines. About a third of a second per page.</html>"
        addActionListener { fixRotation(includedPages.ifEmpty { cells.map { it.page } }) }
    }
    private var orientationWorker: OrientationWorker? = null

    private val includedPages: List<Int> get() = cells.filter { it.include.isSelected }.map { it.page }
    private val rotations: Map<Int, PageRotation> get() = cells.filterNot { it.rotation.isNone() }.associate { it.page to it.rotation }

    init {
        ButtonGroup().apply { add(newTemplate); add(existingTemplate) }
        val content = JPanel(BorderLayout(8, 8)).apply { border = BorderFactory.createEmptyBorder(10, 12, 10, 12) }
        content.add(targetPanel(), BorderLayout.NORTH)
        content.add(pagesPanel(), BorderLayout.CENTER)
        content.add(bottomPanel(), BorderLayout.SOUTH)
        contentPane = content

        templateName.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = nameEdited()
            override fun removeUpdate(e: DocumentEvent) = nameEdited()
            override fun changedUpdate(e: DocumentEvent) = nameEdited()
        })
        group.addActionListener {
            existingTemplate.isSelected = true
            selectedGroup()?.let { firstNumber.value = nextNumber(it) }
            refreshSummary()
        }
        firstNumber.addChangeListener { existingTemplate.isSelected = true; refreshSummary() }
        newTemplate.addActionListener { refreshSummary() }
        existingTemplate.addActionListener { refreshSummary() }
        create.addActionListener { submit() }
        rootPane.defaultButton = create
        rootPane.registerKeyboardAction({ dispose() }, KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), JComponent.WHEN_IN_FOCUSED_WINDOW)

        refreshSummary()
        pack()
        // Many pages scroll; the dialog itself stays on the screen.
        graphicsConfiguration?.bounds?.let { screen ->
            setSize(minOf(width, screen.width * 9 / 10), minOf(height, screen.height * 9 / 10))
        }
        setLocationRelativeTo(owner)
        thumbnails.execute()
    }

    private fun targetPanel() = JPanel(GridBagLayout()).apply {
        border = BorderFactory.createTitledBorder("Template")
        val at = GridBagConstraints().apply { anchor = GridBagConstraints.WEST; insets = Insets(2, 4, 2, 4) }
        add(newTemplate, at.apply { gridx = 0; gridy = 0 })
        add(templateName, at.apply { gridx = 1; gridwidth = 3; fill = GridBagConstraints.HORIZONTAL })
        add(existingTemplate, at.apply { gridx = 0; gridy = 1; gridwidth = 1; fill = GridBagConstraints.NONE })
        add(group, at.apply { gridx = 1 })
        add(JLabel("first new page number:"), at.apply { gridx = 2 })
        add(firstNumber, at.apply { gridx = 3 })
        add(JPanel(), at.apply { gridx = 4; weightx = 1.0 })
    }

    private fun pagesPanel(): JPanel {
        val columns = input.pageCount.coerceIn(1, MAX_COLUMNS)
        val rows = ceil(input.pageCount / columns.toDouble()).toInt()
        val grid = JPanel(GridLayout(0, columns, 8, 8)).apply { cells.forEach(::add) }
        val scroll = JScrollPane(JPanel(BorderLayout()).apply { add(grid, BorderLayout.NORTH) }).apply {
            border = null
            verticalScrollBar.unitIncrement = 24
            val cell = cells.firstOrNull()?.preferredSize ?: Dimension(THUMB_WIDTH, THUMB_HEIGHT)
            preferredSize = Dimension(columns * (cell.width + 8) + 24, minOf(rows, MAX_VISIBLE_ROWS) * (cell.height + 8) + 8)
        }
        val buttons = JPanel(FlowLayout(FlowLayout.LEFT, 6, 0)).apply {
            add(JButton("All").apply { addActionListener { choose { true } } })
            add(JButton("None").apply { addActionListener { choose { false } } })
            add(JButton("Pages without a template").apply {
                val unmatched = input.unmatchedPages
                isEnabled = unmatched != null
                toolTipText = if (unmatched == null) "Test the file first to see which pages match a template." else
                    "Only the pages that matched no template page in the last test."
                addActionListener { choose { it in unmatched.orEmpty() } }
            })
            add(fixAll)
            add(JLabel("Click a page to include or leave it out. Template pages are numbered in PDF page order."))
        }
        return JPanel(BorderLayout(4, 4)).apply {
            border = BorderFactory.createTitledBorder("Pages to include (${input.pageCount})")
            add(buttons, BorderLayout.NORTH)
            add(scroll, BorderLayout.CENTER)
        }
    }

    private fun bottomPanel() = JPanel(BorderLayout(4, 4)).apply {
        add(JPanel(GridLayout(0, 1, 0, 2)).apply {
            add(summary)
            add(JLabel("The template images are the pages as scanned, with everything filled in on them. " +
                "Remove personal data with \"Remove area…\" in the editor.").apply {
                font = font.deriveFont(Font.ITALIC)
                foreground = Color.DARK_GRAY
            })
            add(openInEditor)
            add(problem)
        }, BorderLayout.CENTER)
        add(JPanel(FlowLayout(FlowLayout.RIGHT, 6, 0)).apply {
            add(create)
            add(JButton("Cancel").apply { addActionListener { dispose() } })
        }, BorderLayout.SOUTH)
    }

    private fun nameEdited() {
        if (!newTemplate.isSelected && templateName.text.isNotEmpty()) newTemplate.isSelected = true
        refreshSummary()
    }

    private fun choose(include: (Int) -> Boolean) = cells.forEach { it.include.isSelected = include(it.page) }

    private fun selectedGroup(): TemplateGroup? = (group.selectedItem as? GroupItem)?.group

    private fun nextNumber(group: TemplateGroup): Int = runCatching { library.nextPageNumber(group) }.getOrDefault(1)

    /** Shows which PDF page becomes which template page. */
    private fun refreshSummary() {
        cells.forEach { it.showSelection() }
        problem.text = " "
        val chosen = includedPages
        create.isEnabled = chosen.isNotEmpty() && orientationWorker == null
        val existing = existingTemplate.isSelected
        val first = if (existing) (firstNumber.value as Number).toInt() else 1
        val target = if (existing) selectedGroup()?.manifest?.navn.orEmpty() else templateName.text.trim().ifEmpty { "the new template" }
        val turned = cells.count { it.include.isSelected && !it.rotation.isNone() }
            .let { if (it == 0) "" else "; $it page${plural(it)} turned or straightened" }
        summary.text = when {
            chosen.isEmpty() -> "Choose the pages to include."
            chosen.size <= 4 -> "<html>" + chosen.mapIndexed { index, page -> "PDF page $page → page-${first + index}" }.joinToString(", ") +
                " of <b>${html(target)}</b>${html(turned)}</html>"
            else -> "<html>PDF pages ${chosen.joinToString(", ")} → page-$first … page-${first + chosen.size - 1} of <b>${html(target)}</b>" +
                "${html(turned)}</html>"
        }
    }

    /** Checks what can be checked before anything is written; the dialog stays open on a problem. */
    private fun submit() {
        try {
            val chosen = includedPages
            require(chosen.isNotEmpty()) { "Choose at least one page." }
            val target = if (existingTemplate.isSelected) {
                val selected = requireNotNull(selectedGroup()) { "Choose a template to add the pages to." }
                firstNumber.commitEdit()
                val first = (firstNumber.value as Number).toInt()
                val current = library.read(selected.file)
                val taken = chosen.indices.map { first + it }.filterNot { library.isPageNumberFree(current, it) }
                require(taken.isEmpty()) { "${current.manifest.navn} already has page ${taken.joinToString(", ")}. Choose another first page number." }
                TemplateTarget.Existing(current, first)
            } else {
                val folder = library.validName(templateName.text)
                require(!library.exists(folder)) { "A template named $folder already exists. Choose another name, or add the pages to it." }
                TemplateTarget.New(folder)
            }
            request = TemplateFromPdfRequest(target, chosen, input.canOpenEditor && openInEditor.isSelected,
                rotations.filterKeys { it in chosen })
            dispose()
        } catch (error: Exception) {
            problem.text = error.message ?: error.toString()
        }
    }

    /** Finds and applies the rotation of [pages] one after the other, off the EDT; one run at a time. */
    private fun fixRotation(pages: List<Int>) {
        if (orientationWorker != null || pages.isEmpty()) return
        val worker = OrientationWorker(pages)
        orientationWorker = worker
        pages.forEach { cells[it - 1].detecting = true }
        updateFixButtons()
        refreshSummary()
        worker.execute()
    }

    private fun updateFixButtons() {
        val idle = orientationWorker == null
        fixAll.isEnabled = idle
        cells.forEach { it.fixEnabled = idle }
        create.isEnabled = idle && includedPages.isNotEmpty()
    }

    override fun dispose() {
        thumbnails.cancel(false)
        orientationWorker?.cancel(false)
        super.dispose()
    }

    /**
     * One PDF page: its thumbnail (shown turned by [rotation]), whether it is included, buttons to turn or fix it,
     * and what it matched in the last test.
     */
    private inner class PageCell(val page: Int, note: String?) : JPanel(BorderLayout(0, 2)) {
        val include = JCheckBox("Page $page", true).apply {
            isOpaque = false
            addItemListener { refreshSummary() }
        }
        private val image = JLabel("Rendering …", SwingConstants.CENTER).apply {
            preferredSize = Dimension(THUMB_WIDTH, THUMB_HEIGHT)
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) { include.isSelected = !include.isSelected }
            })
        }
        private val left = smallButton("↺ 90°", "Turn the page 90° to the left") { rotation = rotation.turnedLeft() }
        private val right = smallButton("↻ 90°", "Turn the page 90° to the right") { rotation = rotation.turnedRight() }
        private val fix = smallButton("Straighten", "Automatically turn upright and correct small scan angles before creating the template") { fixRotation(listOf(page)) }
        private val rotationLabel = JLabel(" ").apply { font = font.deriveFont(11f) }
        /** The thumbnail as rendered, before [rotation]. */
        private var original: BufferedImage? = null

        var rotation = PageRotation()
            set(value) {
                field = value
                showRotation()
                refreshSummary()
            }

        var detecting = false
            set(value) {
                field = value
                showRotation()
            }

        var fixEnabled = true
            set(value) {
                field = value
                fix.isEnabled = value
                left.isEnabled = value
                right.isEnabled = value
            }

        init {
            add(include, BorderLayout.NORTH)
            add(image, BorderLayout.CENTER)
            add(JPanel(GridLayout(0, 1, 0, 1)).apply {
                isOpaque = false
                add(JPanel(FlowLayout(FlowLayout.LEFT, 3, 0)).apply { isOpaque = false; add(left); add(right); add(fix) })
                add(rotationLabel)
                add(JLabel(note ?: " ").apply {
                    font = font.deriveFont(11f)
                    foreground = Color.DARK_GRAY
                    toolTipText = note?.let { "In the last test: $it" }
                })
            }, BorderLayout.SOUTH)
            showSelection()
        }

        fun showSelection() {
            include.text = "Page $page"
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(if (include.isSelected) SELECTED_COLOR else UNSELECTED_COLOR, 2),
                BorderFactory.createEmptyBorder(4, 4, 4, 4))
        }

        val hasThumbnail: Boolean get() = original != null

        fun showThumbnail(thumbnail: BufferedImage) {
            original = thumbnail
            showRotation()
        }

        fun showProblem(text: String) {
            image.text = "<html><div style='text-align:center'>${html(text)}</div></html>"
        }

        /** What a detection found; shown until the page is turned by hand. */
        fun showGuess(guess: OrientationGuess) {
            rotation = guess.rotation
            rotationLabel.text = if (guess.orientation == null) "? ${guess.rotation.describe()}" else
                guess.rotation.takeUnless { it.isNone() }?.describe() ?: "✓ upright and straight"
            rotationLabel.toolTipText = guess.description +
                (guess.confidence?.let { " (orientation confidence %.1f, tilt %.2f°)".format(it, guess.skew) } ?: "")
        }

        private fun showRotation() {
            rotationLabel.text = when {
                detecting -> "Finding the rotation …"
                rotation.isNone() -> " "
                else -> rotation.describe()
            }
            rotationLabel.foreground = if (rotation.isNone()) Color.DARK_GRAY else ROTATED_COLOR
            rotationLabel.toolTipText = null
            original?.let { thumbnail ->
                image.text = null
                image.icon = ImageIcon(fitted(rotation.apply(thumbnail)))
            }
        }

        private fun smallButton(text: String, tip: String, action: () -> Unit) = JButton(text).apply {
            toolTipText = tip
            margin = Insets(1, 6, 1, 6)
            font = font.deriveFont(11f)
            addActionListener { action() }
        }
    }

    /** [pages] rendered for text (400 DPI, gray) one at a time, and their rotation found with [PageOrientation.detect]. */
    private inner class OrientationWorker(private val pages: List<Int>) : SwingWorker<Unit, Pair<Int, Result<OrientationGuess>>>() {
        override fun doInBackground() = Loader.loadPDF(input.pdf).use { document ->
            val renderer = PDFRenderer(document)
            for (page in pages) {
                if (isCancelled) return@use
                publish(page to runCatching { PageOrientation.detect(renderer.renderImageWithDPI(page - 1, 400.toFloat(), ImageType.GRAY)) })
            }
        }

        override fun process(chunks: List<Pair<Int, Result<OrientationGuess>>>) = chunks.forEach { (page, result) ->
            val cell = cells[page - 1]
            cell.detecting = false
            result.onSuccess(cell::showGuess).onFailure { cell.showProblem("Could not find the rotation: ${it.message}") }
        }

        override fun done() {
            if (orientationWorker === this) orientationWorker = null
            // Pages not reached (cancelled or failed) stop showing that they are being looked at.
            pages.map { cells[it - 1] }.filter { it.detecting }.forEach { it.detecting = false }
            runCatching { get() }.onFailure { error ->
                if (!isCancelled) problem.text = "Could not find the rotation: ${(error.cause ?: error).message}"
            }
            updateFixButtons()
        }
    }

    /** [image] scaled down to fit a thumbnail cell, keeping its proportions; a page turned sideways gets smaller. */
    private fun fitted(image: BufferedImage): BufferedImage {
        val scale = minOf(1.0, THUMB_WIDTH.toDouble() / image.width, THUMB_HEIGHT.toDouble() / image.height)
        if (scale >= 1.0) return image
        val width = (image.width * scale).toInt().coerceAtLeast(1)
        val height = (image.height * scale).toInt().coerceAtLeast(1)
        return BufferedImage(width, height, BufferedImage.TYPE_INT_RGB).apply {
            createGraphics().let { g ->
                try {
                    g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR)
                    g.drawImage(image, 0, 0, width, height, null)
                } finally { g.dispose() }
            }
        }
    }

    /**
     * Renders every page off the EDT, one after the other, with its long side as tall as a cell: a sideways page then
     * stays sharp when it is turned upright ([fitted] scales it down for showing it sideways).
     */
    private inner class ThumbnailRenderer : SwingWorker<Unit, Pair<Int, BufferedImage>>() {
        override fun doInBackground() = Loader.loadPDF(input.pdf).use { document ->
            val renderer = PDFRenderer(document)
            for (index in 0 until minOf(document.numberOfPages, cells.size)) {
                if (isCancelled) return@use
                val box = document.getPage(index).cropBox
                val dpi = THUMB_HEIGHT / maxOf(box.width, box.height) * 72f
                publish(index to renderer.renderImageWithDPI(index, dpi, ImageType.RGB))
            }
        }

        override fun process(chunks: List<Pair<Int, BufferedImage>>) = chunks.forEach { (index, image) -> cells[index].showThumbnail(image) }

        override fun done() {
            if (isCancelled) return
            runCatching { get() }.onFailure { error ->
                val cause = error.cause ?: error
                cells.filterNot { it.hasThumbnail }.forEach { it.showProblem("No preview: ${cause.message}") }
            }
        }
    }

    private class GroupItem(val group: TemplateGroup) {
        override fun toString() = "${group.manifest.navn} (${group.manifest.pages.size} page${plural(group.manifest.pages.size)})"
    }

    companion object {
        private const val THUMB_WIDTH = 180
        private const val THUMB_HEIGHT = 255
        private const val MAX_COLUMNS = 4
        private const val MAX_VISIBLE_ROWS = 2
        private val SELECTED_COLOR = Color(40, 110, 210)
        private val ROTATED_COLOR = Color(230, 120, 0)
        private val UNSELECTED_COLOR = Color(200, 200, 200)
        private val PROBLEM_COLOR = Color(195, 30, 30)

        /** Shows the dialog and waits for it to close; null if cancelled. */
        fun ask(owner: Component?, input: TemplateDialogInput, library: TemplateLibrary): TemplateFromPdfRequest? {
            val dialog = CreateTemplateDialog(owner?.let { it as? Window ?: SwingUtilities.getWindowAncestor(it) }, input, library)
            dialog.isVisible = true
            return dialog.request
        }
    }
}

private fun html(text: String) = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
