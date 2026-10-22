package no.nav.tsm.ocr.template.gcp

import no.nav.tsm.sykmelding.model.CheckboxState
import no.nav.tsm.sykmelding.ocr.CHECKBOX_RULE
import no.nav.tsm.sykmelding.ocr.CheckboxReading
import no.nav.tsm.sykmelding.ocr.FieldSegment
import no.nav.tsm.sykmelding.ocr.FieldText
import no.nav.tsm.sykmelding.ocr.skipsOcr
import org.example.no.nav.tsm.ocr.template.template.Felter
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.FlowLayout
import javax.swing.JCheckBox
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTable
import javax.swing.JTextArea
import javax.swing.table.AbstractTableModel
import javax.swing.table.TableCellRenderer

internal data class OcrFieldRow(
    val field: Felter,
    val value: FieldText?,
    val leftOut: List<String>,
    val checkbox: CheckboxReading? = null,
) {
    val isCheckbox: Boolean get() = this.field.type.skipsOcr

    val empty: Boolean get() = !isCheckbox && value != null && value.lines.isEmpty()

   val uncertain: Boolean get() = isCheckbox && checkbox?.state == CheckboxState.UNCERTAIN

    val dropped: Boolean get() = !isCheckbox && leftOut.any { it != NOTHING_FOUND }

    companion object {
        const val NOTHING_FOUND = "no text line in it"
    }
}

internal class OcrFieldsPanel : JPanel(BorderLayout()) {
    private var allRows = emptyList<OcrFieldRow>()
    private var rows = emptyList<OcrFieldRow>()
    private val timing = JLabel("No OCR results")
    private val emptyOnly = JCheckBox("Empty or uncertain only").apply {
        toolTipText = "Show only the fields OCR read nothing in, and the checkboxes that are neither checked nor unchecked"
        addActionListener { refresh() }
    }
    private val model = object : AbstractTableModel() {
        override fun getRowCount() = rows.size
        override fun getColumnCount() = COLUMNS.size
        override fun getColumnName(column: Int) = COLUMNS[column]
        override fun getValueAt(row: Int, column: Int): String = rows[row].let {
            when (column) {
                0 -> "${it.field.navn} (${it.field.type})"
                1 -> value(it)
                else -> it.leftOut.joinToString("\n")
            }
        }
    }
    private val table = JTable(model).apply {
        setDefaultRenderer(Any::class.java, object : JTextArea(), TableCellRenderer {
            override fun getTableCellRendererComponent(table: JTable, value: Any?, selected: Boolean, focus: Boolean, row: Int, column: Int): Component {
                val shown = this@OcrFieldsPanel.rows[row]
                text = value?.toString().orEmpty()
                lineWrap = column == COLUMNS.lastIndex
                wrapStyleWord = true
                font = table.font
                background = when {
                    selected -> table.selectionBackground
                    shown.uncertain -> UNCERTAIN
                    shown.dropped -> DROPPED
                    else -> table.background
                }
                foreground = if (selected) table.selectionForeground else table.foreground
                toolTipText = if (shown.isCheckbox && column == 1) "<html>${escape(text)}<br>${escape(CHECKBOX_RULE)}</html>" else text
                return this
            }
        })
        autoResizeMode = JTable.AUTO_RESIZE_OFF
        listOf(260, 320, 420).forEachIndexed { index, width -> columnModel.getColumn(index).preferredWidth = width }
    }

    init {
        add(JPanel(FlowLayout(FlowLayout.LEFT)).apply { add(emptyOnly); add(timing) }, BorderLayout.NORTH)
        add(JScrollPane(table), BorderLayout.CENTER)
    }

   fun show(
        fields: List<Felter>,
        values: OcrFields?,
        segments: List<FieldSegment>?,
        checkboxes: Map<Felter, CheckboxReading>?,
        result: PdfTestResult?,
    ) {
        allRows = fields.map {
            OcrFieldRow(it, values?.get(it), segments?.let { all -> leftOutOf(it, all) }.orEmpty(), checkboxes?.get(it))
        }
        timing.text = when {
            result == null -> "No OCR results"
            result.reads == null -> result.ocrError ?: "Fields not read"
            else -> "OCR for the whole file: ${seconds(result.ocrMillis)}"
        }
        timing.toolTipText = "<html>One layout analysis per page; then each text line in a field is read on its own.<br>" +
            "Orange: something read or found in the field was left out of its value.<br>" +
            "Checkboxes are not read by OCR but by the ink inside their frame, shown as % dark and % ink.<br>" +
            "${escape(CHECKBOX_RULE)}<br>" +
            "Pink: a checkbox with a faint or partial mark, neither checked nor unchecked; production returns it as UNCERTAIN.</html>"
        refresh()
    }

    private fun refresh() {
        rows = allRows.filter { !emptyOnly.isSelected || it.empty || it.uncertain }
        model.fireTableDataChanged()
        rows.indices.forEach { row ->
            val lines = (0 until model.columnCount).maxOf { model.getValueAt(row, it).lines().size }
            table.setRowHeight(row, maxOf(24, lines * table.getFontMetrics(table.font).height + 8))
        }
    }

    private fun value(row: OcrFieldRow): String = when {
        row.isCheckbox -> checkboxText(row.checkbox).replaceFirstChar(Char::uppercase)
        row.value == null -> "Not read"
        else -> row.value.lines.joinToString("\n") { withConfidence(listOf(it)) }.ifEmpty { "—" }
    }

    private fun escape(text: String) = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private companion object {
        val COLUMNS = listOf("Field", "Value", "Why left out")
        val DROPPED = Color(255, 225, 190)
        val UNCERTAIN = Color(255, 205, 230)
    }
}
