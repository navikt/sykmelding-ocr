package no.nav.tsm.ocr.template.view

import org.example.no.nav.tsm.ocr.template.template.DEFAULT_MAX_TEXT_BOXES
import org.example.no.nav.tsm.ocr.template.template.MAX_TEXT_BOXES
import org.example.no.nav.tsm.ocr.template.template.TextRegion
import org.example.no.nav.tsm.ocr.template.template.TextRequirement
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import javax.swing.JCheckBox
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JSpinner
import javax.swing.JTextArea
import javax.swing.SpinnerNumberModel
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import kotlin.math.roundToInt

/**
 * Edits a [TextRequirement]: the words or phrases (one per line), how many must be found, how many text boxes to read
 * at most, and optionally the part of the page to look in, in percent of its width and height. [onChange] is called
 * on every edit.
 */
internal class TextRequirementPanel(initial: TextRequirement? = null) : JPanel(GridBagLayout()) {
    private val words = JTextArea(4, 22).apply {
        lineWrap = false
    }
    private val minFound = JSpinner(SpinnerNumberModel(1, 1, 99, 1)).apply {
        toolTipText = "How many of the words must be found. More than there are means all of them."
    }
    private val maxBoxes = JSpinner(SpinnerNumberModel(DEFAULT_MAX_TEXT_BOXES, 1, MAX_TEXT_BOXES, 10)).apply {
        toolTipText = "<html>Text boxes are read tallest first until the words are found. A form page has about 40–70;<br>" +
            "reading them all takes about half a second. The limit bounds the time on noisy scans.</html>"
    }
    private val limitArea = JCheckBox("Only look in part of the page:").apply {
        toolTipText = "Faster, and avoids words that are elsewhere on other pages too. Draw the area on the template image, or type it."
    }
    private val left = percent(0)
    private val top = percent(0)
    private val right = percent(100)
    private val bottom = percent(100)

    var onChange: () -> Unit = {}

    init {
        val at = GridBagConstraints().apply { anchor = GridBagConstraints.WEST; insets = Insets(2, 2, 2, 2); gridx = 0; fill = GridBagConstraints.HORIZONTAL; weightx = 1.0 }
        add(JLabel("Words or phrases, one per line:"), at.apply { gridy = 0 })
        add(JScrollPane(words).apply { minimumSize = Dimension(120, 72) },
            at.apply { gridy = 1; fill = GridBagConstraints.BOTH; weighty = 1.0 })
        add(row(JLabel("Found at least"), minFound, JLabel("of them")), at.apply { gridy = 2; fill = GridBagConstraints.HORIZONTAL; weighty = 0.0 })
        add(row(JLabel("Read at most"), maxBoxes, JLabel("text boxes")), at.apply { gridy = 3 })
        add(limitArea, at.apply { gridy = 4 })
        add(row(JLabel("Left %"), left, JLabel("Right %"), right), at.apply { gridy = 5 })
        add(row(JLabel("Top %"), top, JLabel("Bottom %"), bottom), at.apply { gridy = 6 })
        set(initial)
        words.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = onChange()
            override fun removeUpdate(e: DocumentEvent) = onChange()
            override fun changedUpdate(e: DocumentEvent) = onChange()
        })
        listOf(minFound, maxBoxes, left, top, right, bottom).forEach { spinner -> spinner.addChangeListener { onChange() } }
        limitArea.addActionListener { updateArea(); onChange() }
    }

    /** Shows [requirement]; null clears the panel. */
    fun set(requirement: TextRequirement?) {
        words.text = requirement?.words.orEmpty().joinToString("\n")
        minFound.value = requirement?.minFound ?: 1
        maxBoxes.value = requirement?.maxBoxes ?: DEFAULT_MAX_TEXT_BOXES
        setRegion(requirement?.region)
    }

    /** Looks only in [region] (or everywhere, with null). */
    fun setRegion(region: TextRegion?) {
        limitArea.isSelected = region != null
        val shown = region ?: TextRegion()
        left.value = (100 * shown.left).roundToInt()
        top.value = (100 * shown.top).roundToInt()
        right.value = (100 * shown.right).roundToInt()
        bottom.value = (100 * shown.bottom).roundToInt()
        updateArea()
        onChange()
    }

    /** The requirement as edited; null when there are no words. Throws [IllegalArgumentException] with a message when invalid. */
    fun requirement(): TextRequirement? {
        listOf(minFound, maxBoxes, left, top, right, bottom).forEach { it.commitEdit() }
        val phrases = words.text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (phrases.isEmpty()) return null
        return TextRequirement(phrases, (minFound.value as Number).toInt(), region(), (maxBoxes.value as Number).toInt())
    }

    /** The area as edited, or null when it is not limited or not valid; for showing it while it is being edited. */
    fun regionOrNull(): TextRegion? = runCatching { region() }.getOrNull()

    private fun region(): TextRegion? = if (!limitArea.isSelected) null else TextRegion(
        (left.value as Number).toDouble() / 100, (top.value as Number).toDouble() / 100,
        (right.value as Number).toDouble() / 100, (bottom.value as Number).toDouble() / 100)

    private fun updateArea() = listOf(left, top, right, bottom).forEach { it.isEnabled = limitArea.isSelected }

    private fun percent(value: Int) = JSpinner(SpinnerNumberModel(value, 0, 100, 5))

    private fun row(vararg components: java.awt.Component) = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply {
        components.forEach { add(it) }
    }.let { JPanel(BorderLayout()).apply { add(it, BorderLayout.WEST) } }
}
