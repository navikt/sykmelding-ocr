package no.nav.tsm.ocr.template.pipeline

import no.nav.tsm.sykmelding.pipeline.TextTemplates
import java.awt.BorderLayout
import java.awt.Component
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import javax.swing.BorderFactory
import javax.swing.JFrame
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.WindowConstants

class TextTemplateEditor private constructor(val window: JFrame, private val panel: TextTemplatePanel) {
    fun select(name: String) = panel.select(name)

    companion object {
        fun open(owner: Component, name: String?, closed: () -> Unit): TextTemplateEditor {
            val status = JLabel(" ")
            val panel = TextTemplatePanel(TextTemplates(), { status.text = it }, name) {}
            val window = JFrame("Text templates").apply {
                defaultCloseOperation = WindowConstants.DISPOSE_ON_CLOSE
                contentPane = JPanel(BorderLayout(4, 4)).apply {
                    border = BorderFactory.createEmptyBorder(8, 8, 8, 8)
                    add(JLabel("<html>A text template's fields are found on a page by their numbers and labels. " +
                        "Add them here by hand.</html>"), BorderLayout.NORTH)
                    add(panel, BorderLayout.CENTER)
                    add(status, BorderLayout.SOUTH)
                }
                addWindowListener(object : WindowAdapter() {
                    override fun windowClosed(e: WindowEvent) = closed()
                })
                setSize(950, 700)
                setLocationRelativeTo(owner)
                isVisible = true
            }
            return TextTemplateEditor(window, panel)
        }
    }
}
