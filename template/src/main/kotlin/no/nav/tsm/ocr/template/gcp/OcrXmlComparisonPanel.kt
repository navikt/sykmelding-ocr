package no.nav.tsm.ocr.template.gcp

import no.nav.tsm.ocr.template.view.splitPane
import java.awt.BorderLayout
import java.awt.Font
import javax.swing.BorderFactory
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JSplitPane
import javax.swing.JTextArea

/** A snapshot with matching fields on the same lines and synchronized vertical scrolling. */
internal class OcrXmlComparisonPanel(comparison: OcrXmlComparison) : JPanel(BorderLayout(4, 4)) {
    init {
        val ocr = StringBuilder()
        val xml = StringBuilder()
        comparison.fields.forEach { field ->
            val left = (field.ocr ?: "(missing)").lines()
            val right = (field.xml ?: "(missing)").lines()
            for ((text, lines) in listOf(ocr to left, xml to right)) {
                text.appendLine("${field.status}  ${field.path}")
                repeat(maxOf(left.size, right.size)) { index -> text.appendLine(lines.getOrElse(index) { "" }) }
                field.note?.let { text.appendLine("Note: $it") }
                text.appendLine()
            }
        }
        fun pane(title: String, text: String): JScrollPane = JScrollPane(JTextArea(text).apply {
            isEditable = false
            font = Font(Font.MONOSPACED, Font.PLAIN, 12)
            caretPosition = 0
        }).apply { border = BorderFactory.createTitledBorder(title) }

        val left = pane("Final OCR output - OcrParserSykmelding", ocr.toString())
        val right = pane("Iron Mountain XML - original values", xml.toString())
        right.verticalScrollBar.model = left.verticalScrollBar.model
        add(JLabel("<html>Fields are aligned by XML path. Left: final mapped OCR object, not raw OCR text. Right: XML as written.<br>" +
            "Agreement is not ground truth; check differences and OCR-only values against the PDF.</html>"), BorderLayout.NORTH)
        add(splitPane(JSplitPane.HORIZONTAL_SPLIT, left, right, location = 0.5, weight = 0.5), BorderLayout.CENTER)
        if (comparison.problems.isNotEmpty()) {
            add(pane("XML problems", comparison.problems.joinToString("\n")).apply {
                preferredSize = java.awt.Dimension(0, 100)
            }, BorderLayout.SOUTH)
        }
    }
}
