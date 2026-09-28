package no.nav.tsm.ocr.template.view

import java.awt.BorderLayout
import java.awt.GridLayout
import java.awt.image.BufferedImage
import javax.swing.BorderFactory
import javax.swing.JPanel

class ImageComparisonView : JPanel(GridLayout(1, 2, 8, 0)) {
    val templatePanal = ImagePanel()
    val secondPanel = ImagePanel()
    private val panels = listOf(templatePanal, secondPanel)
    var originalSecondImage: BufferedImage? = null
        private set

    init {
        add(labeledPanel("Template — reference", templatePanal))
        add(labeledPanel("Second image — align to reference", secondPanel))
    }

    fun loadReference(image: BufferedImage) {
        templatePanal.load(image)
        resetSecondImage()
    }

    fun loadSecond(image: BufferedImage) {
        originalSecondImage = image
        secondPanel.load(image)
    }

    fun resetSecondImage() {
        originalSecondImage?.let { secondPanel.load(it) }
    }

    fun showOrbs(show: Boolean = true) = panels.forEach { it.showOrbs(show) }
    fun showLines(show: Boolean = true) = panels.forEach { it.showLines(show) }
    fun showGrids(mode: GridMode?) = panels.forEach { it.showGrids(mode) }

    private fun labeledPanel(label: String, panel: ImagePanel) = JPanel(BorderLayout()).apply {
        border = BorderFactory.createTitledBorder(label)
        add(panel, BorderLayout.CENTER)
    }
}
