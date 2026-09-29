package no.nav.tsm.ocr.template.view

import org.example.no.nav.tsm.ocr.template.view.TemplateView
import org.example.no.nav.tsm.ocr.template.template.Template
import org.example.no.nav.tsm.ocr.template.template.TemplateFiles
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.event.ActionEvent
import java.awt.event.ActionListener
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import javax.swing.*
import javax.swing.filechooser.FileNameExtensionFilter

class MainView : JFrame() {
    private val status = JLabel("Open a reference image and a second image.")
    val comparisonView = ImageComparisonView { status.text = it }
    var currentFile: String? = null
    private val newTemplateMenuItem = JMenuItem("New template item")
    private val openSavedTemplateMenuItem = JMenuItem("Open template…")
    private val openTemplateMenuItem = JMenuItem("Open template image")
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
        setSize(1500, 900)
        minimumSize = Dimension(1100, 750)
        jMenuBar = createMenuBar()
        add(comparisonView, BorderLayout.CENTER)
        add(status, BorderLayout.SOUTH)
    }

    private fun openImage(selectedFile: File) {
        comparisonView.loadReference(readImage(selectedFile))
        currentFile = selectedFile.path
        File("config.txt").writeText(selectedFile.path)
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
            } else {
                openImage(chooser.selectedFile)
            }
        } catch (e: Exception) {
            JOptionPane.showMessageDialog(this, e.message, "Could not open image", JOptionPane.ERROR_MESSAGE)
        }
    }

    private fun openTemplateEditor(template: Template? = null, file: File? = null) {
        JFrame(template?.navn ?: "Ny mal").apply {
            defaultCloseOperation = JFrame.DISPOSE_ON_CLOSE
            add(TemplateView(template, templateFile = file), BorderLayout.CENTER)
            pack()
            setLocationRelativeTo(this@MainView)
            isVisible = true
        }
    }

    private fun chooseTemplate() {
        val chooser = JFileChooser(".").apply {
            dialogTitle = "Åpne mal"
            fileFilter = FileNameExtensionFilter("Mal (JSON)", "json")
        }
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return
        try {
            val file = chooser.selectedFile
            openTemplateEditor(TemplateFiles.read(file), file)
        } catch (e: Exception) {
            JOptionPane.showMessageDialog(this, e.message, "Kunne ikke åpne malen", JOptionPane.ERROR_MESSAGE)
        }
    }

    private fun chooseConfiguration() {
        val chooser = JFileChooser(".").apply {
            dialogTitle = "Load configuration"
            fileFilter = FileNameExtensionFilter("Template configuration (JSON)", "json")
        }
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return
        try {
            currentFile = comparisonView.loadConfiguration(chooser.selectedFile).templateBilde
        } catch (e: Exception) {
            JOptionPane.showMessageDialog(this, e.message, "Could not load configuration", JOptionPane.ERROR_MESSAGE)
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

        newItem.addActionListener {  }
        newTemplateMenuItem.addActionListener { openTemplateEditor() }
        openSavedTemplateMenuItem.addActionListener { chooseTemplate() }
        openTemplateMenuItem.addActionListener { chooseImage(false) }
        openSecondItem.addActionListener { chooseImage(true) }



        exitItem.addActionListener(ActionListener { e: ActionEvent? -> System.exit(0) })

        fileMenu.add(newItem)
        fileMenu.add(newTemplateMenuItem)
        fileMenu.add(openSavedTemplateMenuItem)
        fileMenu.add(JMenuItem("Load configuration…").apply {
            addActionListener { chooseConfiguration() }
        })
        fileMenu.add(openTemplateMenuItem)
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
