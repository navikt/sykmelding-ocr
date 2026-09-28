package org.example.no.nav.tsm.ocr.template.view

import org.example.no.nav.tsm.ocr.template.template.Template
import java.awt.GridLayout
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JTextField

class TemplateView(private val template: Template?) : JPanel() {
    private val navn: JTextField = JTextField(template?.navn ?: "")
    private val label: JLabel = JLabel("navn")
    private val beskrivelse: JTextField = JTextField(template?.beskrivelse ?: "")
    private val lagreKnapp: JButton = JButton("Lagre mal")

    init {
        lagreKnapp.addActionListener {
            val updatedTemplate = Template(
                navn = navn.text,
                referansepunkter = template?.referansepunkter ?: emptyList(),
                beskrivelse = beskrivelse.text,
                felter = template?.felter ?: emptyList(),
                templateBilde = template?.templateBilde ?: ""
            )
            println("Template saved: $updatedTemplate")
        }

        layout = GridLayout(1, 2) // Set layout to a single column

        add(navn)
        add(label)
        add(beskrivelse)
        add(lagreKnapp)

    }

    //lagre knapp
    // import template bilde knapp



}