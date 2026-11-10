package no.nav.tsm.ocr.template.gcp

import no.nav.tsm.ocr.template.pipeline.TextTemplatePanel
import no.nav.tsm.sykmelding.ocr.Ocr
import no.nav.tsm.sykmelding.ocr.PsmMode
import no.nav.tsm.sykmelding.ocr.TextPart
import no.nav.tsm.sykmelding.ocr.TextRegion
import no.nav.tsm.sykmelding.pipeline.CustomTextTemplateParser
import no.nav.tsm.sykmelding.pipeline.LineField
import no.nav.tsm.sykmelding.pipeline.TextTemplates
import no.nav.tsm.sykmelding.utils.PageOrientation
import no.nav.tsm.sykmelding.utils.PageRotation
import org.apache.pdfbox.Loader
import java.awt.BasicStroke
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.GridLayout
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.image.BufferedImage
import java.util.concurrent.ExecutionException
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComponent
import javax.swing.JFrame
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JSpinner
import javax.swing.JSplitPane
import javax.swing.JTabbedPane
import javax.swing.JTable
import javax.swing.JTextArea
import javax.swing.JTextField
import javax.swing.ListSelectionModel
import javax.swing.SpinnerNumberModel
import javax.swing.SwingUtilities
import javax.swing.SwingWorker
import javax.swing.WindowConstants
import javax.swing.table.AbstractTableModel
import javax.swing.table.TableCellRenderer


class OcrLabView(private val pdf: ByteArray, templates: TextTemplates = TextTemplates()) : JPanel(BorderLayout(6, 6)) {


    private var pageCount = 0
    private var pageIndex = 0
    private var image: BufferedImage? = null
    private var imageDpi = 0
    private var parts: List<TextPart>? = null
    private var regions: List<TextRegion> = emptyList()
    private var parsed: Map<LineField, List<List<TextPart>>> = emptyMap()
    private var parseToken = 0
    private var running = false
    private var rendering = false
    private val ocr by lazy { Ocr() }

    private val pageLabel = JLabel()
    private val previous = JButton("◀ Page").apply { addActionListener { showPage(pageIndex - 1) } }
    private val next = JButton("Page ▶").apply { addActionListener { showPage(pageIndex + 1) } }
    private var rotation = PageRotation()
    private val rotationLabel = JLabel("not rotated")
    private val autoRotate = JCheckBox("Auto rotate + straighten", true).apply {
        toolTipText = "Turn each page upright and straighten it, as when creating a template (Tesseract OSD + skew)."
        addActionListener { rotation = PageRotation(); showPage(pageIndex) }
    }
    private val rotateLeft = JButton("⟲").apply { toolTipText = "Turn 90° left"; addActionListener { rotate(rotation.turnedLeft()) } }
    private val rotateRight = JButton("⟳").apply { toolTipText = "Turn 90° right"; addActionListener { rotate(rotation.turnedRight()) } }
    private val dpi = JSpinner(SpinnerNumberModel(300, 72, 600, 25))
    private val runButton = JButton("Run OCR").apply { addActionListener { runOcr() } }
    private val whitelist = JTextField(40).apply {
        toolTipText = "Allowed characters for Run OCR, for example 0123456789.-/. Leave empty to allow all characters."
    }
    private val showRegions = JCheckBox("Show regions", true).apply { addActionListener { canvas.repaint() } }
    private val zoom = JSpinner(SpinnerNumberModel(50, 10, 200, 10)).apply { addChangeListener { canvas.revalidate(); canvas.repaint() } }
    private val showValuesOverlay = JCheckBox("Highlight found fields", true).apply {
        addActionListener { canvas.repaint() }
    }
    private val status = JLabel(" ")

    private val model = RegionTableModel()
    private val table = JTable(model).apply {
        setSelectionMode(ListSelectionModel.SINGLE_SELECTION)
        autoCreateRowSorter = false
        columnModel.getColumn(0).preferredWidth = 40
        columnModel.getColumn(1).preferredWidth = 420
        columnModel.getColumn(1).cellRenderer = MultiLineCell(-1)
        (2..6).forEach { columnModel.getColumn(it).preferredWidth = 55 }
        selectionModel.addListSelectionListener { if (!it.valueIsAdjusting) { canvas.repaint(); scrollToSelected(); showDetails() } }
    }
    private val fullText = JTextArea().apply { isEditable = false; font = Font(Font.MONOSPACED, Font.PLAIN, 12) }
    private val values = ValueTableModel()
    private val valuesTable = JTable(values).apply {
        setDefaultRenderer(Any::class.java, MultiLineCell(ValueTableModel.COLUMNS.lastIndex))
        autoResizeMode = JTable.AUTO_RESIZE_OFF
        listOf(50, 280, 60, 220, 300).forEachIndexed { index, width -> columnModel.getColumn(index).preferredWidth = width }
    }
    private val foundText = JTextArea().apply { isEditable = false; font = Font(Font.MONOSPACED, Font.PLAIN, 12) }
    private val details = JTextArea().apply { isEditable = false; font = Font(Font.MONOSPACED, Font.PLAIN, 12) }
    private val canvas = PageCanvas()
    private val results = JTabbedPane()
    private val templatePanel = TextTemplatePanel(templates, { status.text = it }) { readValues() }

    init {
        border = BorderFactory.createEmptyBorder(8, 8, 8, 8)
        preferredSize = Dimension(1600, 950)
        val controls = JPanel(FlowLayout(FlowLayout.LEFT)).apply {
            add(previous); add(pageLabel); add(next)
            add(autoRotate); add(rotateLeft); add(rotationLabel); add(rotateRight)
            add(JLabel("DPI:")); add(dpi)
            add(runButton); add(JLabel("Sparse text · Custom parser"))
        }
        add(JPanel(GridLayout(0, 1)).apply {
            add(controls)
            add(JPanel(FlowLayout(FlowLayout.LEFT)).apply {
                add(JLabel("Character whitelist:")); add(whitelist)
                add(showRegions); add(showValuesOverlay)
                add(JLabel("Zoom %:")); add(zoom)
                add(JButton("Copy TSV").apply { addActionListener { copyTsv() } })
            })
        }, BorderLayout.NORTH)
        results.apply {
            addTab("Regions", JScrollPane(table))
            addTab("Full text", JScrollPane(fullText))
            addTab("Template values", JSplitPane(JSplitPane.VERTICAL_SPLIT, JScrollPane(valuesTable), JScrollPane(foundText))
                .apply { resizeWeight = 0.6 })
        }
        val selected = JScrollPane(details).apply { border = BorderFactory.createTitledBorder("Selected region") }
        val lower = JSplitPane(JSplitPane.HORIZONTAL_SPLIT, selected, templatePanel).apply { resizeWeight = 0.5 }
        val right = JSplitPane(JSplitPane.VERTICAL_SPLIT, results, lower).apply { resizeWeight = 0.55 }
        add(JSplitPane(JSplitPane.HORIZONTAL_SPLIT, JScrollPane(canvas).apply { verticalScrollBar.unitIncrement = 24 }, right)
            .apply { resizeWeight = 0.5 }, BorderLayout.CENTER)
        add(status, BorderLayout.SOUTH)
        load()
    }

    private fun load() {
        rendering = true
        updateControls()
        status.text = "Reading the PDF …"
        object : SwingWorker<Int, Unit>() {
            override fun doInBackground(): Int = Loader.loadPDF(pdf).use { document ->
                document.numberOfPages.also { require(it > 0) { "it has no pages" } }
            }

            override fun done() {
                rendering = false
                try {
                    pageCount = get()
                } catch (error: Exception) {
                    val cause = if (error is ExecutionException) error.cause ?: error else error
                    status.text = "Could not read the PDF: ${cause.message ?: cause}"
                }
                updateControls()
                if (pageCount > 0) showPage(0)
            }
        }.execute()
    }

    private fun showPage(index: Int, detect: Boolean = autoRotate.isSelected) {
        if (index !in 0 until pageCount || running || rendering) return
        pageIndex = index
        rendering = true
        image = null
        setResult(null)
        updateControls()
        val renderDpi = dpi.value as Int
        val known = rotation
        status.text = "Rendering page ${index + 1} at $renderDpi DPI …"
        object : SwingWorker<Pair<BufferedImage, PageRotation>, Unit>() {
            override fun doInBackground(): Pair<BufferedImage, PageRotation> {
                val page = PreviewContent.renderPdfPage(pdf, index, renderDpi.toFloat())
                val found = when {
                    !detect -> known
                    renderDpi == PageOrientation.DPI -> PageOrientation.detect(page, PageOrientation.DPI).rotation
                    else -> PageOrientation.detect(PreviewContent.renderPdfPage(pdf, index, PageOrientation.DPI.toFloat()), PageOrientation.DPI).rotation
                }
                return found.apply(page) to found
            }
            override fun done() {
                rendering = false
                try {
                    val (page, found) = get()
                    image = page
                    imageDpi = renderDpi
                    rotation = found
                    rotationLabel.text = found.describe()
                    status.text = "Page ${index + 1}: ${image!!.width}×${image!!.height} px. Press Run OCR."
                } catch (error: Exception) {
                    val cause = if (error is ExecutionException) error.cause ?: error else error
                    status.text = "Could not render page ${index + 1}: ${cause.message ?: cause}"
                }
                updateControls()
                canvas.revalidate(); canvas.repaint()
            }
        }.execute()
    }

    private fun runOcr() {
        if (running || rendering) return
        running = true
        updateControls()
        val renderDpi = dpi.value as Int
        val allowedCharacters = whitelist.text.takeIf { it.isNotEmpty() }
        val index = pageIndex
        val turn = rotation
        val shown = image?.takeIf { imageDpi == renderDpi }
        status.text = "OCR on page ${index + 1} (sparse text, $renderDpi DPI) …"
        object : SwingWorker<Pair<BufferedImage, List<TextPart>>, Unit>() {
            var millis = 0L
            override fun doInBackground(): Pair<BufferedImage, List<TextPart>> {
                val page = shown ?: turn.apply(PreviewContent.renderPdfPage(pdf, index, renderDpi.toFloat()))
                val start = System.nanoTime()
                val parts = ocr.detectParts(page, renderDpi, allowedCharacters, PsmMode.PSM_SPARSE_TEXT)
                millis = (System.nanoTime() - start) / 1_000_000
                return page to parts
            }

            override fun done() {
                running = false
                updateControls()
                try {
                    val (page, read) = get()
                    image = page
                    imageDpi = renderDpi
                    setResult(read)
                    status.text = "${read.sumOf { it.words.size }} words in ${read.size} parts. OCR completed in $millis ms."
                } catch (error: Exception) {
                    val cause = if (error is ExecutionException) error.cause ?: error else error
                    status.text = "OCR failed: ${cause.message ?: cause}"
                }
                canvas.revalidate(); canvas.repaint()
            }
        }.execute()
    }

    private fun setResult(read: List<TextPart>?) {
        parts = read
        regions = read?.map { it.line }.orEmpty()
        readValues()
    }

    private fun readValues() {
        val read = parts
        val template = templatePanel.template
        val token = ++parseToken
        parsed = emptyMap()
        showValues(emptyList())
        if (read == null) {
            foundText.text = "Run OCR to find the fields."
            foundText.caretPosition = 0
            showRegionsList()
            return
        }
        foundText.text = "Running CustomTextTemplateParser …"
        showRegionsList()
        object : SwingWorker<Map<LineField, List<List<TextPart>>>, Unit>() {
            override fun doInBackground(): Map<LineField, List<List<TextPart>>> = CustomTextTemplateParser.parse(read, template)

            override fun done() {
                if (token != parseToken) return
                try {
                    parsed = get()
                    showValues(template?.fields.orEmpty().map { ValueRow(it, parsed[it]?.let(::valueText)) })
                    foundText.text = if (template == null) "Custom parser completed. Select a text template to match fields."
                        else "Custom parser — text template ${template.name}: ${parsed.size}/${template.fields.size} fields found."
                } catch (error: Exception) {
                    val cause = if (error is ExecutionException) error.cause ?: error else error
                    foundText.text = "Could not read the fields: ${cause.message ?: cause}"
                }
                foundText.caretPosition = 0
                showRegionsList()
            }
        }.execute()
    }

    private fun showValues(rows: List<ValueRow>) {
        values.rows = rows
        values.fireTableDataChanged()
        rows.indices.forEach { row ->
            val lines = (0 until values.columnCount).maxOf { values.getValueAt(row, it).lines().size }
            valuesTable.setRowHeight(row, maxOf(24, lines * valuesTable.getFontMetrics(valuesTable.font).height + 8))
        }
    }

    private fun showRegionsList() {
        val selected = selectedRegion()
        model.rows = regions
        model.fireTableDataChanged()
        regions.forEachIndexed { index, region ->
            table.setRowHeight(index, maxOf(24, region.text.lines().size * table.getFontMetrics(table.font).height + 6))
        }
        regions.indexOfFirst { it === selected }.takeIf { it >= 0 }?.let { table.setRowSelectionInterval(it, it) }
        fullText.text = regions.joinToString("\n") { it.text }
        fullText.caretPosition = 0
        showDetails()
        canvas.repaint()
    }

    private fun showDetails() {
        val index = selectedIndex()
        val part = index?.let { parts?.getOrNull(it) }
        if (index == null || part == null) {
            details.text = "Click a region to see its OCR text and the fields it is part of."
            return
        }
        details.text = buildString {
            append("Region ${index + 1}: ${part.line.text}\n")
            parsed.forEach { (field, rows) ->
                if (rows.any { part in it }) append("\n${field.number} ${field.name}: ${valueText(rows)}\n")
            }
            append("\nWords:\n")
            part.words.forEach { word ->
                append("%-28s conf %5.1f  x %5d  y %5d  w %4d  h %3d\n".format(word.text, word.confidence, word.x, word.y, word.width, word.height))
            }
        }
        details.caretPosition = 0
    }

    private fun rotate(turned: PageRotation) {
        if (running || rendering) return
        rotation = turned
        showPage(pageIndex, detect = false)
    }

    private fun updateControls() {
        val idle = !running && !rendering && pageCount > 0
        rotateLeft.isEnabled = idle
        rotateRight.isEnabled = idle
        autoRotate.isEnabled = idle
        dpi.isEnabled = idle
        pageLabel.text = if (pageCount > 0) "${pageIndex + 1} / $pageCount" else "– / –"
        previous.isEnabled = idle && pageIndex > 0
        next.isEnabled = idle && pageIndex < pageCount - 1
        runButton.isEnabled = idle && image != null
        whitelist.isEnabled = idle
    }

    private fun selectedIndex(): Int? = table.selectedRow.takeIf { it in model.rows.indices }

    private fun selectedRegion(): TextRegion? = selectedIndex()?.let { model.rows[it] }

    private fun scale(): Double = (zoom.value as Int) / 100.0

    private fun regionAt(e: MouseEvent): TextRegion? {
        val s = scale()
        val x = (e.x / s).toInt()
        val y = (e.y / s).toInt()
        return model.rows.filter { Rectangle(it.x, it.y, it.width, it.height).contains(x, y) }.minByOrNull { it.width * it.height }
    }

    private fun scrollToSelected() {
        val region = selectedRegion() ?: return
        val s = scale()
        canvas.scrollRectToVisible(Rectangle((region.x * s).toInt() - 40, (region.y * s).toInt() - 40,
            (region.width * s).toInt() + 80, (region.height * s).toInt() + 80))
    }

    private fun copyTsv() {
        val text = (listOf("#\ttext\tconf\tx\ty\tw\th") + model.rows.mapIndexed { i, r ->
            "${i + 1}\t${r.text}\t${"%.1f".format(r.confidence)}\t${r.x}\t${r.y}\t${r.width}\t${r.height}"
        }).joinToString("\n")
        Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null)
        status.text = "Copied ${model.rows.size} regions."
    }

    private inner class PageCanvas : JComponent() {
        init {
            addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    val region = regionAt(e) ?: return
                    val hit = model.rows.indexOfFirst { it === region }
                    table.setRowSelectionInterval(hit, hit)
                    table.scrollRectToVisible(table.getCellRect(hit, 0, true))
                }
            })
            addMouseMotionListener(object : MouseAdapter() {
                override fun mouseMoved(e: MouseEvent) {
                    toolTipText = regionAt(e)?.let { "<html>${escape(it.text)}<br>conf ${"%.1f".format(it.confidence)}</html>" }
                }
            })
        }

        override fun getPreferredSize(): Dimension {
            val img = image ?: return Dimension(400, 400)
            return Dimension((img.width * scale()).toInt(), (img.height * scale()).toInt())
        }

        override fun paintComponent(g: Graphics) {
            super.paintComponent(g)
            val img = image ?: return
            val g2 = g.create() as Graphics2D
            try {
                g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                val s = scale()
                g2.drawImage(img, 0, 0, (img.width * s).toInt(), (img.height * s).toInt(), null)
                if (showRegions.isSelected) {
                    val selected = selectedRegion()
                    g2.font = g2.font.deriveFont(11f)
                    for (r in model.rows) {
                        val rect = scaled(r, s)
                        val colour = confidenceColour(r.confidence)
                        g2.color = Color(colour.red, colour.green, colour.blue, if (r == selected) 90 else 35)
                        g2.fill(rect)
                        g2.color = colour
                        g2.stroke = BasicStroke(if (r == selected) 3f else 1f)
                        g2.draw(rect)
                    }
                }
                if (showValuesOverlay.isSelected) {
                    g2.color = Color(0, 150, 0)
                    g2.stroke = BasicStroke(2f)
                    parsed.values.forEach { rows -> rows.forEach { row -> row.forEach { g2.draw(scaled(it.line, s)) } } }
                }
            } finally {
                g2.dispose()
            }
        }

        private fun scaled(r: TextRegion, s: Double) =
            Rectangle((r.x * s).toInt(), (r.y * s).toInt(), (r.width * s).toInt().coerceAtLeast(1), (r.height * s).toInt().coerceAtLeast(1))

    }

    private inner class RegionTableModel : AbstractTableModel() {
        var rows: List<TextRegion> = emptyList()
        private val columns = listOf("#", "Text", "Conf", "X", "Y", "W", "H")
        override fun getRowCount() = rows.size
        override fun getColumnCount() = columns.size
        override fun getColumnName(column: Int) = columns[column]
        override fun getValueAt(row: Int, column: Int): Any {
            val r = rows[row]
            return when (column) {
                0 -> row + 1
                1 -> r.text
                2 -> "%.1f".format(r.confidence)
                3 -> r.x
                4 -> r.y
                5 -> r.width
                else -> r.height
            }
        }
    }

    private class ValueRow(val field: LineField, val value: String?)

    private class ValueTableModel : AbstractTableModel() {
        var rows: List<ValueRow> = emptyList()
        override fun getRowCount() = rows.size
        override fun getColumnCount() = COLUMNS.size
        override fun getColumnName(column: Int) = COLUMNS[column]
        override fun getValueAt(row: Int, column: Int): String = rows[row].let {
            when (column) {
                0 -> it.field.number
                1 -> it.field.name
                2 -> it.field.layout.name.lowercase()
                3 -> it.field.field?.navn ?: "(none)"
                else -> it.value ?: "(not found)"
            }
        }

        companion object {
            val COLUMNS = listOf("No.", "Field", "Value is", "Global field", "Value")
        }
    }

    private class MultiLineCell(private val wrapped: Int) : JTextArea(), TableCellRenderer {
        override fun getTableCellRendererComponent(table: JTable, value: Any?, selected: Boolean, focus: Boolean, row: Int, column: Int): Component {
            text = value?.toString().orEmpty()
            lineWrap = column == wrapped
            wrapStyleWord = true
            font = table.font
            background = if (selected) table.selectionBackground else table.background
            foreground = if (selected) table.selectionForeground else table.foreground
            toolTipText = text.takeIf { it.isNotEmpty() }
            return this
        }
    }

    companion object {
        fun open(fileName: String, pdf: ByteArray) {
            SwingUtilities.invokeLater {
                JFrame("OCR lab — $fileName").apply {
                    defaultCloseOperation = WindowConstants.DISPOSE_ON_CLOSE
                    contentPane = OcrLabView(pdf)
                    pack()
                    setLocationRelativeTo(null)
                    isVisible = true
                }
            }
        }

        private fun confidenceColour(confidence: Float): Color = when {
            confidence >= 85 -> Color(0, 160, 0)
            confidence >= 60 -> Color(230, 150, 0)
            else -> Color(220, 0, 0)
        }

        private fun valueText(rows: List<List<TextPart>>): String =
            rows.joinToString("\n") { row -> row.joinToString(" ") { it.line.text } }
    }
}
