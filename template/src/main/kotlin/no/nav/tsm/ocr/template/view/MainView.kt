package no.nav.tsm.ocr.template.view

import java.awt.BorderLayout
import java.awt.event.ActionEvent
import java.awt.event.ActionListener
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import javax.swing.*

class MainView : JFrame() {
    val comparisonView = ImageComparisonView()
    var currentFile: String? = null
    private val status = JLabel("Open a reference image and a second image.")
    private val openItem = JMenuItem("Open template image")
    private val openSecondItem = JMenuItem("Open test image")

    init {
        val config = File("config.txt")

        if(config.exists() && config.readText().isNotEmpty()) {
            val file = File(config.readText())
            if (file.isFile) {
                runCatching { readImage(file) }.onSuccess { comparisonView.loadReference(it) }
            } }
        createUI()
    }


    fun createUI() {
        setSize(1200, 800)
        jMenuBar = createMenuBar()
        add(comparisonView, BorderLayout.CENTER)
        add(status, BorderLayout.SOUTH)
    }

    private fun openImage(selectedFile: File) {
        comparisonView.loadReference(readImage(selectedFile))
        currentFile = selectedFile.path
        File("config.txt").writeText(selectedFile.path)
        status.text = "First image: ${selectedFile.name}"
    }

    private fun readImage(file: File): BufferedImage =
        requireNotNull(ImageIO.read(file)) { "Unsupported or unreadable image: ${file.name}" }

    private fun chooseImage(second: Boolean) {
        val chooser = JFileChooser(".")
        chooser.dialogTitle = if (second) "Open second image" else "Open first image (reference)"
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return
        try {
            if (second) {
                comparisonView.loadSecond(readImage(chooser.selectedFile))
                status.text = "Second image: ${chooser.selectedFile.name}"
            } else {
                openImage(chooser.selectedFile)
            }
        } catch (e: Exception) {
            JOptionPane.showMessageDialog(this, e.message, "Could not open image", JOptionPane.ERROR_MESSAGE)
        }
    }

    private fun createMenuBar(): JMenuBar {
        val menuBar = JMenuBar()

        val fileMenu = JMenu("File")
        val viewMenu = JMenu("View")
        val viewORB = JCheckBoxMenuItem("show orbs").apply {
            addActionListener {
                comparisonView.showOrbs(isSelected)
            }
        }
        val viewLines = JCheckBoxMenuItem("Lines").apply {
            addActionListener {
                comparisonView.showLines(isSelected)
            }
        }
        val newItem = JMenuItem("New")
        val exitItem = JMenuItem("Exit")

        openItem.addActionListener { chooseImage(false) }
        openSecondItem.addActionListener { chooseImage(true) }



        exitItem.addActionListener(ActionListener { e: ActionEvent? -> System.exit(0) })

        fileMenu.add(newItem)
        fileMenu.add(openItem)
        fileMenu.add(openSecondItem)
        fileMenu.addSeparator()
        fileMenu.add(exitItem)
        viewMenu.add(viewORB)
        viewMenu.add(viewLines)
        viewMenu.add(JMenu("Grids").apply {
            val modes = ButtonGroup()
            add(JRadioButtonMenuItem("Off", true).apply {
                modes.add(this)
                addActionListener { comparisonView.showGrids(null) }
            })
            for (mode in GridMode.entries) {
                add(JRadioButtonMenuItem(mode.label).apply {
                    modes.add(this)
                    toolTipText = mode.description
                    addActionListener { comparisonView.showGrids(mode) }
                })
            }
        })
        menuBar.add(fileMenu)
        menuBar.add(viewMenu)
        return menuBar
    }
}
