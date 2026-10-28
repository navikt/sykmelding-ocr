package no.nav.tsm.ocr.template.gcp

import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import javax.swing.BorderFactory
import javax.swing.JCheckBox
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTable
import javax.swing.JTextArea
import javax.swing.table.AbstractTableModel
import javax.swing.table.TableCellRenderer

/**
 * One row per field, OCR next to XML, coloured by result: the fields to check first, then the correct ones, then
 * those that cannot be compared. A summary above counts each result.
 */
internal class OcrXmlComparisonPanel(comparison: OcrXmlComparison) : JPanel(BorderLayout(4, 4)) {
    private val allRows = comparison.fields.sortedBy { ORDER.indexOf(it.status) }
    private var rows = allRows
    private val toCheckOnly = JCheckBox("Only fields to check").apply {
        toolTipText = "Hide the correct fields and those that cannot be compared"
        addActionListener { refresh() }
    }
    private val model = object : AbstractTableModel() {
        override fun getRowCount() = rows.size
        override fun getColumnCount() = COLUMNS.size
        override fun getColumnName(column: Int) = COLUMNS[column]
        override fun getValueAt(row: Int, column: Int): String = rows[row].let {
            when (column) {
                0 -> label(it.status)
                1 -> it.path
                2 -> it.ocr ?: MISSING
                3 -> it.xml ?: MISSING
                else -> it.note.orEmpty()
            }
        }
    }
    private val table = JTable(model).apply {
        setDefaultRenderer(Any::class.java, object : JTextArea(), TableCellRenderer {
            override fun getTableCellRendererComponent(table: JTable, value: Any?, selected: Boolean, focus: Boolean, row: Int, column: Int): Component {
                val status = this@OcrXmlComparisonPanel.rows[row].status
                text = value?.toString().orEmpty()
                lineWrap = column == COLUMNS.lastIndex
                wrapStyleWord = true
                font = if (column == 0) table.font.deriveFont(Font.BOLD) else table.font
                background = if (selected) table.selectionBackground else color(status) ?: table.background
                foreground = when {
                    selected -> table.selectionForeground
                    color(status) != null -> Color.BLACK
                    status == ComparisonStatus.NOT_COMPARABLE || text == MISSING -> Color.GRAY
                    else -> table.foreground
                }
                toolTipText = text.takeIf { it.isNotEmpty() }
                return this
            }
        })
        autoResizeMode = JTable.AUTO_RESIZE_SUBSEQUENT_COLUMNS
        listOf(170, 300, 260, 260, 260).forEachIndexed { index, width -> columnModel.getColumn(index).preferredWidth = width }
    }

    init {
        add(JPanel(FlowLayout(FlowLayout.LEFT, 6, 2)).apply {
            ORDER.forEach { status ->
                val count = comparison.fields.count { it.status == status }
                if (count > 0) add(JLabel(" ${label(status)}: $count ").apply {
                    isOpaque = true
                    background = color(status) ?: background
                    foreground = if (color(status) != null) Color.BLACK else foreground
                    font = font.deriveFont(Font.BOLD)
                    border = BorderFactory.createLineBorder(Color.LIGHT_GRAY)
                })
            }
            add(toCheckOnly)
        }, BorderLayout.NORTH)
        add(JScrollPane(table).apply {
            toolTipText = "OCR: final OcrParserSykmelding. XML: as production reads it. Agreement is not ground truth; check against the PDF."
        }, BorderLayout.CENTER)
        if (comparison.problems.isNotEmpty()) {
            add(JScrollPane(JTextArea(comparison.problems.joinToString("\n")).apply {
                isEditable = false
                font = Font(Font.MONOSPACED, Font.PLAIN, 12)
            }).apply {
                border = BorderFactory.createTitledBorder("XML problems")
                preferredSize = Dimension(0, 100)
            }, BorderLayout.SOUTH)
        }
        refresh()
    }

    private fun refresh() {
        rows = allRows.filter { !toCheckOnly.isSelected || it.status !in NOT_TO_CHECK }
        model.fireTableDataChanged()
        rows.indices.forEach { row ->
            val lines = (0 until model.columnCount).maxOf { model.getValueAt(row, it).lines().size }
            table.setRowHeight(row, maxOf(24, lines * table.getFontMetrics(table.font).height + 8))
        }
    }

    internal companion object {
        val COLUMNS = listOf("Result", "Field", "OCR", "XML", "Note")
        const val MISSING = "—"

        /** The fields to check first. */
        val ORDER = listOf(
            ComparisonStatus.DIFFERENT, ComparisonStatus.MISSING_OCR, ComparisonStatus.XML_PROBLEM, ComparisonStatus.OCR_ONLY,
            ComparisonStatus.UNCERTAIN, ComparisonStatus.NORMALIZED_MATCH, ComparisonStatus.MATCH, ComparisonStatus.NOT_COMPARABLE,
        )
        private val NOT_TO_CHECK = setOf(ComparisonStatus.MATCH, ComparisonStatus.NORMALIZED_MATCH, ComparisonStatus.NOT_COMPARABLE)
        private val CORRECT = Color(205, 238, 205)
        private val WRONG = Color(255, 200, 200)
        private val UNSURE = Color(255, 238, 180)
        private val XML_WRONG = Color(255, 220, 180)

        fun label(status: ComparisonStatus): String = when (status) {
            ComparisonStatus.MATCH -> "✓ Correct"
            ComparisonStatus.NORMALIZED_MATCH -> "✓ Correct, other format"
            ComparisonStatus.DIFFERENT -> "✗ Different"
            ComparisonStatus.MISSING_OCR -> "✗ Missing in OCR"
            ComparisonStatus.OCR_ONLY -> "? Only in OCR"
            ComparisonStatus.UNCERTAIN -> "? Uncertain checkbox"
            ComparisonStatus.XML_PROBLEM -> "! XML problem"
            ComparisonStatus.NOT_COMPARABLE -> "Not compared"
        }

        private fun color(status: ComparisonStatus): Color? = when (status) {
            ComparisonStatus.MATCH, ComparisonStatus.NORMALIZED_MATCH -> CORRECT
            ComparisonStatus.DIFFERENT, ComparisonStatus.MISSING_OCR -> WRONG
            ComparisonStatus.OCR_ONLY, ComparisonStatus.UNCERTAIN -> UNSURE
            ComparisonStatus.XML_PROBLEM -> XML_WRONG
            ComparisonStatus.NOT_COMPARABLE -> null
        }
    }
}
