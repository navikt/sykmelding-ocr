package no.nav.tsm.ocr.template.view

import org.example.no.nav.tsm.ocr.template.template.TemplateGroup
import org.example.no.nav.tsm.ocr.template.template.TemplateLibrary
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.io.File
import javax.swing.*
import javax.swing.filechooser.FileNameExtensionFilter
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath
import javax.swing.tree.TreeSelectionModel

private data class GroupEntry(val group: TemplateGroup) {
    override fun toString() = "${group.manifest.navn} (${group.manifest.pages.size} pages)"
}

private data class PageEntry(val file: File) {
    override fun toString() = "${file.parentFile.name} / ${file.name}"
}

class TemplateLibraryView(
    private val onPageDeleted: (File) -> Unit = {},
    private val onPageRenumbered: (File, File) -> Unit = { _, _ -> },
    private val onOpenPage: (File) -> Unit,
) : JPanel(BorderLayout()) {
    private val library = TemplateLibrary()
    val tree = JTree(DefaultMutableTreeNode("Templates"))
    private val status = JLabel()
    private val addPage = JButton("Add page").apply {
        isEnabled = false
        addActionListener { createPage() }
    }
    private val openPage = JButton("Open page").apply {
        isEnabled = false
        addActionListener { openSelectedPage() }
    }
    private val importPage = JButton("Import page").apply {
        isEnabled = false
        toolTipText = "Copy an existing page JSON and its images into a new page folder."
        addActionListener { choosePageImport() }
    }
    private val deletePage = JButton("Delete page").apply {
        isEnabled = false
        addActionListener { deleteSelectedPage() }
    }
    private val renumberPage = JButton("Change page number").apply {
        isEnabled = false
        addActionListener { changeSelectedPageNumber() }
    }

    private val toolbar = JPanel(FlowLayout(FlowLayout.LEFT))

    init {
        add(toolbar.apply {
            add(JButton("New template").apply { addActionListener { createTemplate() } })
            add(addPage)
            add(importPage)
            add(openPage)
            add(renumberPage)
            add(deletePage)
            add(JButton("Refresh").apply { addActionListener { refresh() } })
        }, BorderLayout.NORTH)
        tree.selectionModel.selectionMode = TreeSelectionModel.SINGLE_TREE_SELECTION
        tree.addTreeSelectionListener {
            addPage.isEnabled = selectedGroup() != null
            importPage.isEnabled = selectedGroup() != null
            openPage.isEnabled = selectedNode()?.userObject is PageEntry
            deletePage.isEnabled = selectedNode()?.userObject is PageEntry
            renumberPage.isEnabled = selectedNode()?.userObject is PageEntry
        }
        tree.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2 && tree.getPathForLocation(e.x, e.y) == tree.selectionPath) openSelectedPage()
            }
        })
        tree.inputMap.put(KeyStroke.getKeyStroke("ENTER"), "openPage")
        tree.actionMap.put("openPage", object : AbstractAction() {
            override fun actionPerformed(e: java.awt.event.ActionEvent) = openSelectedPage()
        })
        add(JScrollPane(tree), BorderLayout.CENTER)
        add(status, BorderLayout.SOUTH)
        refresh()
    }

    /** Adds [component] after the library's own buttons, for actions that use the whole library. */
    fun addToolbarComponent(component: JComponent) {
        toolbar.add(component)
        toolbar.revalidate()
    }

    private fun selectedNode() = tree.lastSelectedPathComponent as? DefaultMutableTreeNode

    private fun selectedGroup(): TemplateGroup? {
        val node = selectedNode() ?: return null
        return (node.userObject as? GroupEntry)?.group
            ?: ((node.parent as? DefaultMutableTreeNode)?.userObject as? GroupEntry)?.group
    }

    fun refresh(selectFile: File? = null) {
        runCatching {
            val groups = library.list()
            val root = DefaultMutableTreeNode("Templates")
            var selection: TreePath? = null
            groups.forEach { group ->
                val node = DefaultMutableTreeNode(GroupEntry(group))
                root.add(node)
                if (group.file == selectFile?.absoluteFile) selection = TreePath(node.path)
                group.pageFiles.forEach { file ->
                    val page = DefaultMutableTreeNode(PageEntry(file))
                    node.add(page)
                    if (file == selectFile?.absoluteFile) selection = TreePath(page.path)
                }
            }
            tree.model = DefaultTreeModel(root)
            var row = 0
            while (row < tree.rowCount) tree.expandRow(row++)
            tree.selectionPath = selection
            status.text = if (groups.isEmpty()) "No templates yet. Create a template such as 03.2019."
                else "${groups.size} templates, ${groups.sumOf { it.manifest.pages.size }} pages - ${library.directory.absolutePath}"
        }.onFailure { showError("Could not load templates", it) }
    }

    fun createTemplate() {
        val name = JOptionPane.showInputDialog(this, "Template folder name (for example 03.2019):") ?: return
        runCatching { library.create(name) }
            .onSuccess { refresh(it.file) }
            .onFailure { showError("Could not create template", it) }
    }

    fun createPage() {
        val group = selectedGroup()
        if (group == null) {
            status.text = "Select a template before adding a page."
            return
        }
        runCatching {
            val number = choosePageNumber(library.nextPageNumber(group)) ?: return
            library.addPage(group, number)
        }
            .onSuccess { refresh(it); onOpenPage(it) }
            .onFailure { showError("Could not add page", it) }
    }

    fun choosePageImport() {
        val group = selectedGroup()
        if (group == null) {
            status.text = "Select a template before importing a page."
            return
        }
        val chooser = JFileChooser(".").apply {
            dialogTitle = "Import page template (original files will be kept)"
            fileFilter = FileNameExtensionFilter("Page template (JSON)", "json")
        }
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return
        runCatching {
            val number = choosePageNumber(library.nextPageNumber(group)) ?: return
            importPage(chooser.selectedFile, number)
        }.onFailure { showError("Could not import page", it) }
    }

    fun importPage(file: File, number: Int? = null) {
        val group = selectedGroup()
        if (group == null) {
            status.text = "Select a template before importing a page."
            return
        }
        runCatching { library.importPage(group, file, number) }
            .onSuccess { refresh(it); onOpenPage(it) }
            .onFailure { showError("Could not import page", it) }
    }

    fun selectedPageFile(): File? = (selectedNode()?.userObject as? PageEntry)?.file

    private fun openSelectedPage() {
        val page = selectedNode()?.userObject as? PageEntry ?: return
        onOpenPage(page.file)
    }

    private fun choosePageNumber(initial: Int): Int? {
        val number = JSpinner(SpinnerNumberModel(initial, 1, Int.MAX_VALUE, 1))
        val panel = JPanel(FlowLayout(FlowLayout.LEFT)).apply {
            add(JLabel("Page number:"))
            add(number)
        }
        if (JOptionPane.showConfirmDialog(
                this, panel, "Page number", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE,
            ) != JOptionPane.OK_OPTION) return null
        number.commitEdit()
        return number.value as Int
    }

    fun changeSelectedPageNumber() {
        val page = selectedNode()?.userObject as? PageEntry
        val group = selectedGroup()
        if (page == null || group == null) {
            status.text = "Select a page to change its number."
            return
        }
        runCatching {
            val initial = page.file.parentFile.name.removePrefix("page-").toInt()
            val number = choosePageNumber(initial) ?: return
            library.renumberPage(group, page.file, number)
        }.onSuccess {
            if (it != page.file) onPageRenumbered(page.file, it)
            refresh(it)
        }.onFailure { showError("Could not change page number", it) }
    }

    fun deleteSelectedPage() {
        val page = selectedNode()?.userObject as? PageEntry
        val group = selectedGroup()
        if (page == null || group == null) {
            status.text = "Select a page layout to delete."
            return
        }
        if (JOptionPane.showConfirmDialog(
                this,
                "Delete ${page.file.parentFile.name}, including its layout and images? Open editors for this page will be closed.",
                "Delete page",
                JOptionPane.YES_NO_OPTION,
                JOptionPane.WARNING_MESSAGE,
            ) != JOptionPane.YES_OPTION) return
        runCatching { library.deletePage(group, page.file) }
            .onSuccess { onPageDeleted(page.file); refresh(group.file) }
            .onFailure {
                if (!page.file.exists()) onPageDeleted(page.file)
                refresh(group.file)
                showError("Could not delete page", it)
            }
    }

    private fun showError(title: String, error: Throwable) {
        status.text = "$title: ${error.message}"
        JOptionPane.showMessageDialog(this, error.message, title, JOptionPane.ERROR_MESSAGE)
    }
}
