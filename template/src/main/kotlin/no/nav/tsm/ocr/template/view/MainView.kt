package no.nav.tsm.ocr.template.view

import no.nav.tsm.ocr.template.gcp.BucketTemplateTestView
import no.nav.tsm.ocr.template.gcp.GcpBucketView
import org.example.no.nav.tsm.ocr.template.template.Template
import org.example.no.nav.tsm.ocr.template.template.TemplateFiles
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.io.File
import javax.swing.*
import javax.swing.filechooser.FileNameExtensionFilter

private data class PageEditor(val window: JFrame, val view: TemplateView, var file: File)

class MainView : JFrame() {
    private val pageEditors = mutableMapOf<File, MutableList<PageEditor>>()
    private val libraryView = TemplateLibraryView(onPageDeleted = { file ->
        pageEditors.remove(file.absoluteFile.normalize())?.toList()?.forEach { it.window.dispose() }
    }, onPageRenumbered = { previous, destination ->
        val editors = pageEditors.remove(previous.absoluteFile.normalize()).orEmpty()
        editors.forEach {
            it.view.relocateTemplateFile(destination)
            it.file = destination
            if (it.window.title == previous.parentFile.name) it.window.title = destination.parentFile.name
        }
        if (editors.isNotEmpty()) pageEditors.getOrPut(destination.absoluteFile.normalize()) { mutableListOf() }.addAll(editors)
    }) { file -> openTemplateFile(file) }
    private val newTemplateMenuItem = JMenuItem("Add template page")
    private val openSavedTemplateMenuItem = JMenuItem("Open template…")
    private var bucketTestWindow: JFrame? = null

    init {
        createUI()
    }


    fun createUI() {
        setSize(1500, 900)
        minimumSize = Dimension(1100, 750)
        jMenuBar = createMenuBar()
        libraryView.addToolbarComponent(JSeparator(SwingConstants.VERTICAL).apply { preferredSize = Dimension(8, 22) })
        libraryView.addToolbarComponent(JButton("Test bucket PDFs…").apply {
            toolTipText = "List the PDFs in the GCP bucket, view them, and test their pages against every page of every template."
            addActionListener { openBucketTest() }
        })
        add(libraryView, BorderLayout.CENTER)
    }

    /** One window at a time: it holds the template features detected for earlier tests. */
    private fun openBucketTest() {
        bucketTestWindow?.takeIf { it.isDisplayable }?.let { it.toFront(); it.requestFocus(); return }
        bucketTestWindow = BucketTemplateTestView.open(this) { pages, openInEditor ->
            libraryView.refresh(pages.firstOrNull())
            if (openInEditor) pages.forEach(::openTemplateFile)
        }
    }

    private fun openTemplateEditor(template: Template, file: File) {
        JFrame(template.navn).apply {
            val view = TemplateView(template, templateFile = file)
            val editor = PageEditor(this, view, file)
            pageEditors.getOrPut(file.absoluteFile.normalize()) { mutableListOf() }.add(editor)
            defaultCloseOperation = JFrame.DISPOSE_ON_CLOSE
            add(view, BorderLayout.CENTER)
            addWindowListener(object : WindowAdapter() {
                override fun windowClosed(e: WindowEvent) {
                    val key = editor.file.absoluteFile.normalize()
                    pageEditors[key]?.remove(editor)
                    if (pageEditors[key]?.isEmpty() == true) pageEditors.remove(key)
                    libraryView.refresh(editor.file)
                }
            })
            pack()
            setLocationRelativeTo(this@MainView)
            isVisible = true
        }
    }

    private fun chooseTemplate() {
        val chooser = JFileChooser("./templates").apply {
            dialogTitle = "Åpne mal"
            fileFilter = FileNameExtensionFilter("Mal (JSON)", "json")
        }
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return
        val file = chooser.selectedFile
        if (file.name == "mal.json" && file.parentFile.parentFile?.canonicalFile == File("templates").canonicalFile) {
            libraryView.refresh(file)
        } else {
            openTemplateFile(file)
        }
    }

    private fun openTemplateFile(file: File) {
        try {
            openTemplateEditor(TemplateFiles.read(file), file)
        } catch (e: Exception) {
            JOptionPane.showMessageDialog(this, e.message, "Kunne ikke åpne malen", JOptionPane.ERROR_MESSAGE)
        }
    }

    private fun createMenuBar(): JMenuBar {
        val menuBar = JMenuBar()

        val fileMenu = JMenu("File")
        val newItem = JMenuItem("New template")
        val exitItem = JMenuItem("Exit")

        newItem.addActionListener { libraryView.createTemplate() }
        newTemplateMenuItem.addActionListener { libraryView.createPage() }
        openSavedTemplateMenuItem.addActionListener { chooseTemplate() }
        exitItem.addActionListener { System.exit(0) }

        fileMenu.add(newItem)
        fileMenu.add(newTemplateMenuItem)
        fileMenu.add(JMenuItem("Import template page").apply {
            addActionListener { libraryView.choosePageImport() }
        })
        fileMenu.add(JMenuItem("Delete template page").apply {
            addActionListener { libraryView.deleteSelectedPage() }
        })
        fileMenu.add(JMenuItem("Change page number").apply {
            addActionListener { libraryView.changeSelectedPageNumber() }
        })
        fileMenu.add(openSavedTemplateMenuItem)
        fileMenu.addSeparator()
        fileMenu.add(JMenuItem("Browse GCP bucket…").apply {
            addActionListener { GcpBucketView.open(this@MainView) }
        })
        fileMenu.add(JMenuItem("Test bucket PDFs against templates…").apply {
            addActionListener { openBucketTest() }
        })
        fileMenu.addSeparator()
        fileMenu.add(exitItem)
        menuBar.add(fileMenu)
        return menuBar
    }
}
