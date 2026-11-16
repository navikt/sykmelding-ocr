package no.nav.tsm.ocr.template.testdata

import no.nav.tsm.sykmelding.ocr.CustomTextTemplateParser
import no.nav.tsm.sykmelding.ocr.TextPart
import no.nav.tsm.sykmelding.template.TextTemplate
import java.awt.BasicStroke
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.GraphicsEnvironment
import java.awt.GridLayout
import java.awt.Point
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.event.ActionEvent
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.image.BufferedImage
import java.io.File
import java.util.concurrent.ExecutionException
import javax.swing.AbstractAction
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JFrame
import javax.swing.JLabel
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JSpinner
import javax.swing.JSplitPane
import javax.swing.JTable
import javax.swing.JTextField
import javax.swing.KeyStroke
import javax.swing.ListSelectionModel
import javax.swing.SpinnerNumberModel
import javax.swing.SwingUtilities
import javax.swing.SwingWorker
import javax.swing.WindowConstants
import javax.swing.table.AbstractTableModel
import kotlin.math.roundToInt

class TestDataEditorView(
    scan: BufferedImage,
    private val dpi: Int,
    parts: List<TextPart>,
    suggestedName: String,
    private val directory: File = OcrTestData.DIRECTORY,
    template: TextTemplate? = null,
) : JPanel(BorderLayout(6, 6)) {

    private val page = TestDataPage(scan, parts)

    private var area: Rectangle? = null

    private var loading = false

    private val nameField = JTextField(suggestedName, 24)
    private val save = JButton("Save").apply { addActionListener { save() } }
    private val delete = JButton("Delete").apply {
        toolTipText = "Delete the dragged area, or else the selected part (also the Delete and Backspace keys)."
        addActionListener { deleteSelected() }
    }
    private val restore = JButton("Restore scan").apply {
        toolTipText = "Put the scan back in the dragged area, or else where the selected part is."
        addActionListener { restoreSelected() }
    }
    private val zoom = JSpinner(SpinnerNumberModel(50, 10, 200, 10)).apply { addChangeListener { canvas.revalidate(); canvas.repaint() } }
    private val status = JLabel("Click a part to select it, drag to select an area. Edit a text in the list.")

    private val family = JComboBox(GraphicsEnvironment.getLocalGraphicsEnvironment().availableFontFamilyNames).apply {
        addActionListener { if (!loading) applyStyle() }
    }
    private val fontSize = JSpinner(SpinnerNumberModel(12, MIN_FONT_SIZE, 400, 1)).apply { addChangeListener { if (!loading) applyStyle() } }
    private val bold = JCheckBox().apply { addActionListener { if (!loading) applyStyle() } }
    private val penX = JSpinner(SpinnerNumberModel(0, -100_000, 100_000, 1)).apply { addChangeListener { if (!loading) applyStyle() } }
    private val baselineY = JSpinner(SpinnerNumberModel(0, -100_000, 100_000, 1)).apply { addChangeListener { if (!loading) applyStyle() } }

    private val model = PartTableModel()
    private val table = JTable(model).apply {
        setSelectionMode(ListSelectionModel.SINGLE_SELECTION)
        columnModel.getColumn(0).preferredWidth = 40
        columnModel.getColumn(1).preferredWidth = 420
        (2..6).forEach { columnModel.getColumn(it).preferredWidth = 55 }
        selectionModel.addListSelectionListener {
            if (!it.valueIsAdjusting && !loading) {
                if (selectedRow >= 0) area = null
                loadStyle()
                scrollToSelected()
                canvas.repaint()
            }
        }
    }
    private val canvas = PageCanvas()

    init {
        border = BorderFactory.createEmptyBorder(8, 8, 8, 8)
        preferredSize = Dimension(1600, 950)
        add(JPanel(FlowLayout(FlowLayout.LEFT)).apply {
            add(JLabel("Name:")); add(nameField)
            add(save); add(delete); add(restore)
            add(JLabel("Zoom %:")); add(zoom)
        }, BorderLayout.NORTH)
        val style = JPanel(GridLayout(0, 2, 4, 4)).apply {
            border = BorderFactory.createTitledBorder("Selected part")
            add(JLabel("Font")); add(family)
            add(JLabel("Size (px)")); add(fontSize)
            add(JLabel("Bold")); add(bold)
            add(JLabel("X")); add(penX)
            add(JLabel("Y (baseline)")); add(baselineY)
        }
        val right = JPanel(BorderLayout(4, 4)).apply {
            add(JScrollPane(table), BorderLayout.CENTER)
            add(style, BorderLayout.SOUTH)
        }
        add(JSplitPane(JSplitPane.HORIZONTAL_SPLIT, JScrollPane(canvas).apply { verticalScrollBar.unitIncrement = 24 }, right)
            .apply { resizeWeight = 0.6 }, BorderLayout.CENTER)
        add(status, BorderLayout.SOUTH)

        val deleteAction = object : AbstractAction() {
            override fun isEnabled() = !table.isEditing
            override fun actionPerformed(e: ActionEvent) = deleteSelected()
        }
        listOf(canvas, table).forEach { component ->
            component.getInputMap(JComponent.WHEN_FOCUSED).apply {
                put(KeyStroke.getKeyStroke(KeyEvent.VK_DELETE, 0), DELETE_SELECTED)
                put(KeyStroke.getKeyStroke(KeyEvent.VK_BACK_SPACE, 0), DELETE_SELECTED)
            }
            component.actionMap.put(DELETE_SELECTED, deleteAction)
        }
        if (template != null) {
            status.text = fillIn(CustomTextTemplateParser.read(parts, template), template)
            model.fireTableDataChanged()
        }
        loadStyle()
    }

    private fun fillIn(read: CustomTextTemplateParser.Page, template: TextTemplate): String {
        if (read.rows.isEmpty()) return "Text template ${template.name} finds no fields on the page: it is as it was scanned."
        val used = page.useDefaults(read, OcrTestData.defaultValues(directory))
        return listOfNotNull(
            "Trimmed to the form, ${used.typed} default values typed in.",
            used.withoutDefault.takeIf { it.isNotEmpty() }?.let { "Deleted, as they have no default value: ${it.joinToString()}." },
            used.ignoredRows.takeIf { it > 0 }?.let { "$it rows match no field and are as scanned." },
            "Green parts are as scanned; Restore scan puts the scan back.",
        ).joinToString(" ")
    }

    private fun selected(): Int? = table.selectedRow.takeIf { it in page.parts.indices }

    private fun scale(): Double = (zoom.value as Int) / 100.0

    private fun refresh(select: Int?) {
        loading = true
        try {
            model.fireTableDataChanged()
            if (select != null && select in page.parts.indices) table.setRowSelectionInterval(select, select) else table.clearSelection()
            loadStyle()
        } finally {
            loading = false
        }
        canvas.repaint()
    }

    private fun loadStyle() {
        val style = selected()?.let(page::style)
        val outer = loading
        loading = true
        try {
            listOf(family, fontSize, bold, penX, baselineY).forEach { it.isEnabled = style != null }
            if (style != null) {
                family.selectedItem = style.family
                fontSize.value = style.size
                bold.isSelected = style.bold
                penX.value = style.x
                baselineY.value = style.baseline
            }
        } finally {
            loading = outer
        }
    }

    private fun applyStyle() {
        val index = selected() ?: return
        val current = page.style(index)
        val next = current.copy(family = family.selectedItem as String, size = fontSize.value as Int, bold = bold.isSelected,
            x = penX.value as Int, baseline = baselineY.value as Int)
        if (next == current) return
        page.redraw(index, next)
        refresh(index)
    }

    private fun scrollToSelected() {
        val line = selected()?.let { page.parts[it].part.line } ?: return
        val s = scale()
        canvas.scrollRectToVisible(Rectangle((line.x * s).toInt() - 40, (line.y * s).toInt() - 40,
            (line.width * s).toInt() + 80, (line.height * s).toInt() + 80))
    }

    private fun deleteSelected() {
        val before = page.parts.size
        val chosen = area
        val index = selected()
        when {
            chosen != null -> {
                page.deleteArea(chosen)
                area = null
            }
            index != null -> page.delete(index)
            else -> {
                status.text = "Select a part or drag an area to delete."
                return
            }
        }
        refresh(null)
        status.text = "Deleted; ${before - page.parts.size} parts removed, ${page.parts.size} left."
    }

    private fun restoreSelected() {
        val where = area ?: selected()?.let { box(page.parts[it].part.line) }
        if (where == null) {
            status.text = "Select a part or drag an area to put the scan back in."
            return
        }
        page.restore(where)
        area = null
        refresh(null)
        status.text = "The scan is back; ${page.parts.size} parts."
    }

    private fun save() {
        if (table.isEditing) table.cellEditor.stopCellEditing()
        val target = try {
            OcrTestData.validName(nameField.text)
        } catch (error: IllegalArgumentException) {
            status.text = error.message
            return
        }
        if (File(directory, "$target.pdf").exists() && JOptionPane.showConfirmDialog(this, "Test data $target already exists in $directory. Replace it?",
                "Save test data", JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION) return
        val data = OcrTestData(page.image(), dpi)
        save.isEnabled = false
        status.text = "Saving $target …"
        object : SwingWorker<Unit, Unit>() {
            override fun doInBackground() = data.write(directory, target)

            override fun done() {
                save.isEnabled = true
                status.text = try {
                    get()
                    "Saved ${File(directory, "$target.pdf")}."
                } catch (error: Exception) {
                    val cause = if (error is ExecutionException) error.cause ?: error else error
                    "Could not save $target: ${cause.message ?: cause}"
                }
            }
        }.execute()
    }

    private inner class PageCanvas : JComponent() {
        private var press: Point? = null

        private var moving: DrawnText? = null
        private var dragged = false

        init {
            isFocusable = true
            val mouse = object : MouseAdapter() {
                override fun mousePressed(e: MouseEvent) {
                    if (!SwingUtilities.isLeftMouseButton(e)) return
                    requestFocusInWindow()
                    if (table.isEditing) table.cellEditor.stopCellEditing()
                    press = e.point
                    dragged = false
                    moving = partAt(e.point)?.takeIf { it == selected() }?.let(page::style)
                }

                override fun mouseDragged(e: MouseEvent) {
                    val from = press ?: return
                    if (!dragged && from.distance(e.point) < DRAG_START) return
                    dragged = true
                    val s = scale()
                    val start = moving
                    if (start != null) {
                        val index = selected() ?: return
                        page.redraw(index, start.copy(x = start.x + ((e.x - from.x) / s).roundToInt(),
                            baseline = start.baseline + ((e.y - from.y) / s).roundToInt()))
                        refresh(index)
                    } else {
                        table.clearSelection()
                        area = Rectangle(imagePoint(from, s)).apply { add(imagePoint(e.point, s)) }
                            .intersection(Rectangle(0, 0, page.background.width, page.background.height)).takeUnless { it.isEmpty }
                        repaint()
                    }
                }

                override fun mouseReleased(e: MouseEvent) {
                    if (!SwingUtilities.isLeftMouseButton(e) || press == null) return
                    if (!dragged) {
                        area = null
                        val hit = partAt(e.point)
                        if (hit != null) {
                            table.setRowSelectionInterval(hit, hit)
                            table.scrollRectToVisible(table.getCellRect(hit, 0, true))
                        } else {
                            table.clearSelection()
                        }
                        repaint()
                    }
                    press = null
                    moving = null
                }
            }
            addMouseListener(mouse)
            addMouseMotionListener(mouse)
        }

        private fun imagePoint(point: Point, s: Double) = Point((point.x / s).toInt(), (point.y / s).toInt())

        private fun partAt(point: Point): Int? {
            val at = imagePoint(point, scale())
            return page.parts.indices.filter { box(page.parts[it].part.line).contains(at) }
                .minByOrNull { page.parts[it].part.line.run { width * height } }
        }

        override fun getPreferredSize(): Dimension =
            Dimension((page.background.width * scale()).toInt(), (page.background.height * scale()).toInt())

        override fun paintComponent(g: Graphics) {
            super.paintComponent(g)
            val s = scale()
            val image = g.create() as Graphics2D
            try {
                image.scale(s, s)
                image.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
                image.drawImage(page.background, 0, 0, null)
                page.drawTexts(image)
            } finally {
                image.dispose()
            }
            val g2 = g.create() as Graphics2D
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                val chosen = selected()
                page.parts.forEachIndexed { index, part ->
                    val rect = scaled(box(part.part.line), s)
                    val colour = if (part.drawn != null) TYPED else SCAN
                    g2.color = Color(colour.red, colour.green, colour.blue, if (index == chosen) 90 else 35)
                    g2.fill(rect)
                    g2.color = colour
                    g2.stroke = BasicStroke(if (index == chosen) 3f else 1f)
                    g2.draw(rect)
                }
                area?.let {
                    val rect = scaled(it, s)
                    g2.color = Color(AREA.red, AREA.green, AREA.blue, 40)
                    g2.fill(rect)
                    g2.color = AREA
                    g2.stroke = BasicStroke(2f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f, floatArrayOf(6f, 4f), 0f)
                    g2.draw(rect)
                }
            } finally {
                g2.dispose()
            }
        }

        private fun scaled(r: Rectangle, s: Double) =
            Rectangle((r.x * s).toInt(), (r.y * s).toInt(), (r.width * s).toInt().coerceAtLeast(1), (r.height * s).toInt().coerceAtLeast(1))
    }

    private inner class PartTableModel : AbstractTableModel() {
        override fun getRowCount() = page.parts.size
        override fun getColumnCount() = COLUMNS.size
        override fun getColumnName(column: Int) = COLUMNS[column]
        override fun isCellEditable(row: Int, column: Int) = column == 1
        override fun getValueAt(row: Int, column: Int): Any {
            val part = page.parts[row]
            val line = part.part.line
            return when (column) {
                0 -> row + 1
                1 -> line.text
                2 -> if (part.drawn != null) "typed" else "scan"
                3 -> line.x
                4 -> line.y
                5 -> line.width
                else -> line.height
            }
        }

        override fun setValueAt(value: Any?, row: Int, column: Int) {
            if (column != 1) return
            val before = page.parts.size
            page.setText(row, value.toString())
            refresh(row.takeIf { page.parts.size == before })
        }
    }

    companion object {
        fun open(title: String, scan: BufferedImage, dpi: Int, parts: List<TextPart>, suggestedName: String, template: TextTemplate? = null) {
            SwingUtilities.invokeLater {
                JFrame("Test data — $title").apply {
                    defaultCloseOperation = WindowConstants.DISPOSE_ON_CLOSE
                    contentPane = TestDataEditorView(scan, dpi, parts, suggestedName, template = template)
                    pack()
                    setLocationRelativeTo(null)
                    isVisible = true
                }
            }
        }

        private val COLUMNS = listOf("#", "Text", "Source", "X", "Y", "W", "H")
        private val SCAN = Color(0, 160, 0)
        private val TYPED = Color(0, 90, 220)
        private val AREA = Color(220, 0, 0)
        private const val DELETE_SELECTED = "deleteSelected"
        private const val DRAG_START = 4
    }
}
