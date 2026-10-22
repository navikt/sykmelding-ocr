package no.nav.tsm.ocr.template.gcp

import java.awt.BorderLayout
import java.awt.Component
import java.awt.Desktop
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.GridLayout
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.awt.event.ActionEvent
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.ExecutionException
import java.util.prefs.Preferences
import javax.swing.AbstractAction
import javax.swing.BorderFactory
import javax.swing.DefaultComboBoxModel
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComboBox
import javax.swing.JFrame
import javax.swing.JLabel
import javax.swing.JMenuItem
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JPopupMenu
import javax.swing.JScrollPane
import javax.swing.JSplitPane
import no.nav.tsm.ocr.template.view.splitPane
import javax.swing.JTable
import javax.swing.JTextArea
import javax.swing.JTextField
import javax.swing.KeyStroke
import javax.swing.ListSelectionModel
import javax.swing.RowFilter
import javax.swing.RowSorter
import javax.swing.SortOrder
import javax.swing.SwingWorker
import javax.swing.Timer
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import javax.swing.event.PopupMenuEvent
import javax.swing.event.PopupMenuListener
import javax.swing.table.AbstractTableModel
import javax.swing.table.DefaultTableCellRenderer
import javax.swing.table.TableRowSorter

class GcpBucketView(
    private val folders: SaveFolders = SaveFolders(),
    private val settings: Preferences? = Preferences.userNodeForPackage(GcpBucketView::class.java),
) : JPanel(BorderLayout(6, 6)) {
    private val downloadCache = DownloadCache.shared

    private val bucket = JComboBox((listOfNotNull(settings?.get(BUCKET, null)) + KNOWN_BUCKETS).distinct().toTypedArray()).apply {
        isEditable = true
        prototypeDisplayValue = "ocr-papirsykmelding-bucket-prod--"
        toolTipText = "Bucket name. Uses your Application Default Credentials (gcloud auth application-default login)."
    }
    private val prefix = JTextField(settings?.get(PREFIX, "").orEmpty(), 14).apply {
        toolTipText = "Only list objects whose name starts with this text, e.g. a journalpostId. Filtered by GCS, so it is fast."
        addActionListener { load() }
    }
    private val loadButton = JButton("Load").apply { addActionListener { load() } }
    private val stopButton = JButton("Stop").apply { isEnabled = false; addActionListener { listing?.cancel(true) } }
    private val filter = JTextField(18).apply { toolTipText = "Show only names containing this text" }
    private val pdfOnly = JCheckBox("PDF only", settings?.getBoolean(PDF_ONLY, true) ?: true)
    private val hideSaved = JCheckBox("Hide saved").apply { toolTipText = "Hide files already saved in any folder under ${folders.root.displayPath()}" }
    private val count = JLabel(" ")

    private val folder = JComboBox<String>().apply {
        isEditable = true
        prototypeDisplayValue = "a-fairly-long-folder-name"
        toolTipText = "Sub-folder of ${folders.root.displayPath()}. Choose one, or type a new name: it is created when you save."
    }
    private val newFolder = JButton("New folder…").apply { addActionListener { createFolder() } }
    private val save = JButton("Save").apply { addActionListener { saveSelected() } }
    private val openFolder = JButton("Open folder").apply { addActionListener { openFolder() } }

    private val model = BucketTableModel()
    private val sorter = TableRowSorter(model)
    private val table = JTable(model)
    private val details = JTextArea(12, 40).apply { isEditable = false; font = Font(Font.MONOSPACED, Font.PLAIN, 12) }
    private val preview = FilePreviewPanel()
    private val status = JLabel(" ")

    /** The bucket currently listed; downloads and saves always use this, not the (editable) combo box. */
    private var source: BucketSource? = null
    private var listing: SwingWorker<Int, List<BucketFile>>? = null
    private var listingToken = 0
    private var previewToken = 0
    private var saving = false
    private var closed = false
    private var shown: BucketFile? = null
    private var shownPdf: PdfInfo? = null
    private val downloadFolder = DownloadFolderControls(downloadCache) { status.text = it }
    private val previewDelay = Timer(PREVIEW_DELAY_MS) { previewSelected() }.apply { isRepeats = false }

    init {
        border = BorderFactory.createEmptyBorder(8, 8, 8, 8)
        preferredSize = Dimension(1600, 950)
        setupTable()
        reloadFolders(settings?.get(FOLDER, null))

        add(JPanel(GridLayout(0, 1, 0, 2)).apply {
            add(row(JLabel("Bucket:"), bucket, JLabel("Prefix:"), prefix, loadButton, stopButton, downloadFolder))
            add(row(JLabel("Filter:"), filter, pdfOnly, hideSaved, count))
        }, BorderLayout.NORTH)

        // The folder box stretches; the buttons always stay visible.
        val rootPath = folders.root.displayPath()
        val saveBar = JPanel(BorderLayout(6, 0)).apply {
            border = BorderFactory.createEmptyBorder(2, 4, 2, 4)
            add(JLabel("Save to ${if (rootPath.length > 24) "…" + rootPath.takeLast(22) else rootPath}/").apply {
                toolTipText = folders.root.absolutePath
            }, BorderLayout.WEST)
            add(folder, BorderLayout.CENTER)
            add(row(save, newFolder, openFolder), BorderLayout.EAST)
        }
        val left = splitPane(JSplitPane.VERTICAL_SPLIT, JScrollPane(table),
            JScrollPane(details).apply { border = BorderFactory.createTitledBorder("Details") }, location = 0.7, weight = 0.7)
        val right = JPanel(BorderLayout(4, 4)).apply {
            add(saveBar, BorderLayout.NORTH)
            add(preview, BorderLayout.CENTER)
        }
        // Both dividers can be dragged both ways: see splitPane.
        add(splitPane(JSplitPane.HORIZONTAL_SPLIT, left, right, location = 0.5, weight = 0.5), BorderLayout.CENTER)
        add(status, BorderLayout.SOUTH)

        filter.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = applyFilter()
            override fun removeUpdate(e: DocumentEvent) = applyFilter()
            override fun changedUpdate(e: DocumentEvent) = applyFilter()
        })
        pdfOnly.addActionListener { settings?.putBoolean(PDF_ONLY, pdfOnly.isSelected); applyFilter() }
        hideSaved.addActionListener { applyFilter() }
        folder.addPopupMenuListener(object : PopupMenuListener {
            override fun popupMenuWillBecomeVisible(e: PopupMenuEvent) = reloadFolders(folderText())
            override fun popupMenuWillBecomeInvisible(e: PopupMenuEvent) = Unit
            override fun popupMenuCanceled(e: PopupMenuEvent) = Unit
        })
        (bucket.editor.editorComponent as? JTextField)?.addActionListener { load() }

        val shortcut = runCatching { Toolkit.getDefaultToolkit().menuShortcutKeyMaskEx }.getOrDefault(InputEvent.CTRL_DOWN_MASK)
        getInputMap(WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke(KeyEvent.VK_S, shortcut), "save")
        actionMap.put("save", object : AbstractAction() {
            override fun actionPerformed(e: ActionEvent) { if (save.isEnabled) saveSelected() }
        })
        save.toolTipText = "Download the selected files into the folder (${KeyEvent.getModifiersExText(shortcut)}+S)"

        applyFilter()
        updateControls()
        status.text = "Press Load to list gs://${bucketName()}"
    }

    private fun setupTable() = with(table) {
        rowSorter = sorter
        sorter.sortKeys = listOf(RowSorter.SortKey(BucketTableModel.CREATED, SortOrder.DESCENDING))
        selectionModel.selectionMode = ListSelectionModel.MULTIPLE_INTERVAL_SELECTION
        fillsViewportHeight = true
        // Name absorbs resizing; size and date keep room for their full text.
        autoResizeMode = JTable.AUTO_RESIZE_SUBSEQUENT_COLUMNS
        setDefaultRenderer(Long::class.javaObjectType, object : DefaultTableCellRenderer() {
            init { horizontalAlignment = RIGHT }
            override fun setValue(value: Any?) { text = (value as? Long)?.let(::formatSize).orEmpty() }
        })
        setDefaultRenderer(Instant::class.java, object : DefaultTableCellRenderer() {
            override fun setValue(value: Any?) { text = formatTime(value as? Instant) }
        })
        listOf(330 to 120, 75 to 70, 150 to 140, 115 to 60, 110 to 60).forEachIndexed { index, (preferred, minimum) ->
            columnModel.getColumn(index).apply { preferredWidth = preferred; minWidth = minimum }
        }
        selectionModel.addListSelectionListener { if (!it.valueIsAdjusting) selectionChanged() }
        sorter.addRowSorterListener { updateCount() }
        componentPopupMenu = JPopupMenu().apply {
            add(JMenuItem("Copy name").apply { addActionListener { copy { it.name } } })
            add(JMenuItem("Copy gs:// path").apply { addActionListener { copy { "gs://${source?.bucket}/${it.name}" } } })
            add(JMenuItem("Copy journalpostId").apply { addActionListener { copy { it.reference?.journalpostId } } })
            addSeparator()
            add(JMenuItem("Save to folder").apply { addActionListener { saveSelected() } })
            add(JMenuItem("Show downloaded file").apply {
                toolTipText = "Show the previewed file in the download folder (it is downloaded when it is previewed)."
                addActionListener {
                    val file = shown
                    val cached = file?.let { current -> source?.let { downloadCache.cachedFile(it.bucket, current) } }
                    if (cached == null) status.text = "Preview a file first." else downloadFolder.reveal(cached)
                }
            })
        }
    }

    /** Lists the bucket from scratch. Rows appear page by page; the list is sorted by date added, newest first. */
    fun load() {
        if (closed) return
        val name = bucketName()
        if (name.isEmpty()) { status.text = "Enter a bucket name."; return }
        listing?.cancel(true)
        val prefixText = prefix.text.trim()
        settings?.put(BUCKET, name)
        settings?.put(PREFIX, prefixText)
        val current = GcsBucketSource(name)
        source = current
        val token = ++listingToken
        previewToken++
        model.clear()
        shown = null
        shownPdf = null
        details.text = ""
        preview.showMessage("Select a file to preview it.")
        refreshSaved()
        val location = "gs://$name/$prefixText"
        status.text = "Listing $location …"
        val worker = object : SwingWorker<Int, List<BucketFile>>() {
            override fun doInBackground(): Int {
                var listed = 0
                for (page in current.pages(prefixText)) {
                    if (isCancelled) break
                    publish(page)
                    listed += page.size
                }
                return listed
            }

            // Chunks may still arrive after done(); keep them unless a newer listing has started.
            override fun process(chunks: List<List<BucketFile>>) {
                if (token != listingToken) return
                model.add(chunks.flatten())
                if (listing === this) status.text = "Listing $location … ${model.rowCount} files so far"
            }

            override fun done() {
                if (listing !== this) return
                listing = null
                updateControls()
                status.text = when {
                    isCancelled -> "Stopped listing $location. The list is incomplete."
                    else -> try {
                        val listed = get()
                        "Listed $listed files from $location" + if (listed == 0) " — the bucket or prefix is empty." else "."
                    } catch (error: Exception) {
                        val cause = if (error is ExecutionException) error.cause ?: error else error
                        "Could not list $location: ${describeGcpError(cause).replace("\n", " — ")}"
                    }
                }
            }
        }
        listing = worker
        updateControls()
        worker.execute()
    }

    private fun selectionChanged() {
        updateControls()
        // Debounced, so holding an arrow key down does not start a download for every row passed.
        previewDelay.restart()
    }

    private fun previewSelected() {
        if (closed) return
        val file = leadFile() ?: return
        if (file != shown) showFile(file)
    }

    /** The row last clicked, so shift/cmd-selecting several files previews the one clicked. */
    private fun leadFile(): BucketFile? {
        val lead = table.selectionModel.leadSelectionIndex
        val row = if (lead >= 0 && table.isRowSelected(lead)) lead else table.selectedRow
        return if (row < 0) null else model.files[table.convertRowIndexToModel(row)]
    }

    private fun showFile(file: BucketFile) {
        val current = source ?: return
        shown = file
        shownPdf = null
        showDetails()
        val cached = downloadCache.cachedFile(current.bucket, file) != null
        val token = ++previewToken
        preview.showMessage(if (cached) "Opening ${file.fileName} from the download folder …"
            else "Downloading ${file.fileName} (${formatSize(file.size)}) …", file.fileName)
        object : SwingWorker<Pair<ByteArray, PreviewContent>, Unit>() {
            override fun doInBackground(): Pair<ByteArray, PreviewContent> {
                // The download is kept even if the user has moved on: it makes saving or opening it again instant.
                val bytes = downloadCache.using(current, file) { it.readBytes() }
                val content = try {
                    PreviewContent.of(file, bytes)
                } catch (error: Exception) {
                    PreviewContent.Unsupported("Could not read ${file.fileName}: ${error.message}")
                }
                return bytes to content
            }

            override fun done() {
                val result = runCatching { get() }
                if (closed) return
                if (!cached) downloadFolder.refresh()
                if (token != previewToken) return
                result.onSuccess { (bytes, content) ->
                    preview.show(file.fileName, bytes, content)
                    shownPdf = (content as? PreviewContent.Pdf)?.info
                    showDetails()
                }.onFailure { error ->
                    val cause = if (error is ExecutionException) error.cause ?: error else error
                    preview.showMessage("Could not download ${file.fileName}:\n${describeGcpError(cause)}", file.fileName)
                }
                updateControls()
            }
        }.execute()
    }

    private fun saveSelected() {
        val current = source ?: return
        if (saving) return
        val files = table.selectedRows.map { model.files[table.convertRowIndexToModel(it)] }
        if (files.isEmpty()) { status.text = "Select one or more files to save."; return }
        val folderName = try {
            SaveFolders.validName(folderText())
        } catch (error: IllegalArgumentException) {
            JOptionPane.showMessageDialog(this, error.message, "Choose a folder", JOptionPane.WARNING_MESSAGE)
            return
        }
        val target = folders.folder(folderName).displayPath()
        val existing = files.filter { folders.target(folderName, it.name).exists() }
        var skipped = emptyList<BucketFile>()
        if (existing.isNotEmpty()) {
            val names = existing.take(8).joinToString("\n") { "  ${it.fileName}" } + if (existing.size > 8) "\n  …" else ""
            val choice = JOptionPane.showOptionDialog(this,
                "${existing.size} of ${files.size} file(s) already exist in $target:\n$names\n\nOverwrite them?",
                "Already saved", JOptionPane.YES_NO_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE, null,
                arrayOf("Overwrite", "Skip existing", "Cancel"), "Skip existing")
            when (choice) {
                0 -> Unit
                1 -> skipped = existing
                else -> return
            }
        }
        val toSave = files - skipped.toSet()
        if (toSave.isEmpty()) { status.text = "Nothing saved: all ${files.size} file(s) already exist in $target."; return }
        val advanceFrom = table.selectedRows.minOrNull() ?: -1
        settings?.put(FOLDER, folderName)
        saving = true
        updateControls()
        status.text = "Saving ${toSave.size} file(s) to $target …"
        object : SwingWorker<List<File>, String>() {
            override fun doInBackground(): List<File> = toSave.mapIndexed { index, file ->
                publish("Saving ${index + 1}/${toSave.size} to $target: ${file.fileName} …")
                folders.save(folderName, file.name, downloadCache.using(current, file) { it.readBytes() })
            }

            override fun process(chunks: List<String>) { if (saving) status.text = chunks.last() }

            override fun done() {
                saving = false
                if (!closed) downloadFolder.refresh()
                try {
                    val saved = get()
                    status.text = "Saved ${saved.size} file(s) to $target" +
                        (if (skipped.isNotEmpty()) ", skipped ${skipped.size} already there" else "") +
                        (saved.singleOrNull()?.let { ": ${it.name}" } ?: ".")
                } catch (error: Exception) {
                    val cause = if (error is ExecutionException) error.cause ?: error else error
                    val text = describeGcpError(cause)
                    status.text = "Save failed: ${text.replace("\n", " — ")}"
                    if (!closed) JOptionPane.showMessageDialog(this@GcpBucketView, text, "Could not save", JOptionPane.ERROR_MESSAGE)
                }
                if (closed) return
                reloadFolders(folderName)
                refreshSaved(advanceFrom)
                updateControls()
            }
        }.execute()
    }

    private fun createFolder() {
        val name = JOptionPane.showInputDialog(this, "New folder in ${folders.root.displayPath()}:", "New folder",
            JOptionPane.PLAIN_MESSAGE) ?: return
        try {
            val created = folders.create(SaveFolders.validName(name))
            reloadFolders(created.name)
            status.text = "Created ${created.displayPath()}"
        } catch (error: Exception) {
            JOptionPane.showMessageDialog(this, error.message, "Could not create folder", JOptionPane.ERROR_MESSAGE)
        }
    }

    private fun openFolder() {
        val chosen = runCatching { folders.folder(folderText()) }.getOrNull()?.takeIf { it.isDirectory }
        val directory = chosen ?: folders.root.apply { mkdirs() }
        try {
            Desktop.getDesktop().open(directory)
        } catch (error: Exception) {
            status.text = "Could not open ${directory.displayPath()}: ${error.message}"
        }
    }

    private fun copy(value: (BucketFile) -> String?) {
        val text = table.selectedRows.map { value(model.files[table.convertRowIndexToModel(it)]) }.filterNotNull().joinToString("\n")
        if (text.isEmpty()) return
        runCatching { Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null) }
            .onSuccess { status.text = "Copied ${text.lines().size} value(s)." }
    }

    /** Re-reads which files are already saved locally, refilters, and selects the next file if the saved one was hidden. */
    private fun refreshSaved(advanceFrom: Int = -1) {
        model.saved = runCatching { folders.savedIndex() }.getOrDefault(emptyMap())
        if (model.rowCount > 0) model.fireTableRowsUpdated(0, model.rowCount - 1)
        applyFilter()
        if (advanceFrom >= 0 && table.selectedRowCount == 0 && table.rowCount > 0) {
            val row = advanceFrom.coerceAtMost(table.rowCount - 1)
            table.setRowSelectionInterval(row, row)
            table.scrollRectToVisible(table.getCellRect(row, 0, true))
        }
        if (shown != null) showDetails()
    }

    private fun applyFilter() {
        val text = filter.text.trim().lowercase()
        val onlyPdf = pdfOnly.isSelected
        val unsaved = hideSaved.isSelected
        sorter.rowFilter = object : RowFilter<BucketTableModel, Int>() {
            override fun include(entry: Entry<out BucketTableModel, out Int>): Boolean {
                val file = model.files[entry.identifier]
                return (!onlyPdf || file.isPdf) && (text.isEmpty() || text in file.name.lowercase()) &&
                    (!unsaved || model.savedIn(file).isEmpty())
            }
        }
        updateCount()
    }

    private fun updateCount() {
        count.text = "Showing ${table.rowCount} of ${model.rowCount} files (${formatSize(model.totalBytes)} in total)"
    }

    private fun updateControls() {
        stopButton.isEnabled = listing != null
        val selected = table.selectedRowCount
        save.isEnabled = !saving && source != null && selected > 0
        save.text = if (selected > 1) "Save $selected files" else "Save"
    }

    private fun showDetails() {
        val file = shown ?: return
        details.text = describe(file, shownPdf)
        details.caretPosition = 0
    }

    private fun describe(file: BucketFile, pdf: PdfInfo?): String = buildString {
        fun line(key: String, value: Any?) {
            val text = value?.toString().orEmpty()
            if (text.isNotEmpty()) append(key.padEnd(17)).append(text).append('\n')
        }
        line("Name", file.name)
        line("Location", "gs://${source?.bucket}/${file.name}")
        line("Size", "%,d bytes (%s)".format(file.size, formatSize(file.size)))
        line("Date added", formatTime(file.created))
        if (file.updated != file.created) line("Updated", formatTime(file.updated))
        line("Content type", file.contentType)
        line("Storage class", file.storageClass)
        line("Generation", file.generation)
        line("MD5", file.md5)
        line("CRC32C", file.crc32c)
        file.metadata.forEach { (key, value) -> line("Metadata", "$key = $value") }
        file.reference?.let { reference ->
            line("JournalpostId", reference.journalpostId)
            line("DokumentInfoId", reference.dokumentInfoId)
            line("File UUID", reference.fileUuid)
            val related = model.files.filter { it !== file && it.reference?.journalpostId == reference.journalpostId }
            related.take(10).forEach { line("Same journalpost", "${it.fileName} (${formatSize(it.size)})") }
            if (related.size > 10) line("", "… ${related.size - 10} more")
        }
        line("Saved in", model.savedIn(file).joinToString(", ") { folders.folder(it).displayPath() }.ifEmpty { "–" })
        source?.let { downloadCache.cachedFile(it.bucket, file) }?.let { line("Downloaded to", it.path) }
        if (pdf != null) {
            append('\n')
            line("PDF pages", pdf.pages.size)
            pdf.pages.forEachIndexed { index, page -> line("  Page ${index + 1}", page.description) }
            line("PDF version", pdf.version)
            if (pdf.encrypted) line("Encrypted", "yes")
            line("Title", pdf.title)
            line("Author", pdf.author)
            line("Creator", pdf.creator)
            line("Producer", pdf.producer)
            line("PDF created", pdf.created?.let(::formatTime))
            line("PDF modified", pdf.modified?.let(::formatTime))
        }
    }

    private fun reloadFolders(keep: String?) {
        val names = folders.folders()
        val current = List(folder.model.size) { folder.model.getElementAt(it) }
        if (names != current) folder.model = DefaultComboBoxModel(names.toTypedArray())
        folder.selectedItem = keep?.takeIf(String::isNotBlank) ?: names.firstOrNull()
    }

    private fun folderText(): String = (folder.editor.item ?: folder.selectedItem)?.toString().orEmpty().trim()

    private fun bucketName(): String =
        (bucket.editor.item ?: bucket.selectedItem)?.toString().orEmpty().trim().removePrefix("gs://").trimEnd('/')

    /** Stops background work; results that arrive later are ignored. */
    fun close() {
        closed = true
        previewDelay.stop()
        listing?.cancel(true)
        listing = null
        listingToken++
        previewToken++
    }

    companion object {
        private const val BUCKET = "bucket"
        private const val PREFIX = "prefix"
        private const val FOLDER = "folder"
        private const val PDF_ONLY = "pdfOnly"
        private const val PREVIEW_DELAY_MS = 150

        /** Opens the browser in its own window and starts listing the last used bucket. */
        fun open(owner: Component? = null, exitOnClose: Boolean = false): JFrame {
            val view = GcpBucketView()
            return JFrame("GCP bucket files").apply {
                defaultCloseOperation = if (exitOnClose) JFrame.EXIT_ON_CLOSE else JFrame.DISPOSE_ON_CLOSE
                add(view)
                addWindowListener(object : WindowAdapter() {
                    override fun windowClosed(e: WindowEvent) = view.close()
                })
                pack()
                setLocationRelativeTo(owner)
                isVisible = true
                view.load()
            }
        }
    }
}

private class BucketTableModel : AbstractTableModel() {
    val files = ArrayList<BucketFile>()
    var saved: Map<String, List<String>> = emptyMap()
    var totalBytes = 0L
        private set

    fun add(page: List<BucketFile>) {
        if (page.isEmpty()) return
        val first = files.size
        files += page
        totalBytes += page.sumOf { it.size }
        fireTableRowsInserted(first, files.size - 1)
    }

    fun clear() {
        files.clear()
        totalBytes = 0
        fireTableDataChanged()
    }

    fun savedIn(file: BucketFile): List<String> = saved[SaveFolders.localName(file.name)].orEmpty()

    override fun getRowCount() = files.size
    override fun getColumnCount() = COLUMNS.size
    override fun getColumnName(column: Int) = COLUMNS[column]
    override fun getColumnClass(column: Int): Class<*> = when (column) {
        SIZE -> Long::class.javaObjectType
        CREATED -> Instant::class.java
        else -> String::class.java
    }

    override fun getValueAt(row: Int, column: Int): Any? {
        val file = files[row]
        return when (column) {
            NAME -> file.name
            SIZE -> file.size
            CREATED -> file.created
            TYPE -> file.contentType
            else -> savedIn(file).joinToString(", ")
        }
    }

    companion object {
        const val NAME = 0
        const val SIZE = 1
        const val CREATED = 2
        const val TYPE = 3
        val COLUMNS = listOf("Name", "Size", "Date added", "Content type", "Saved in")
    }
}

private fun row(vararg components: Component) =
    JPanel(FlowLayout(FlowLayout.LEFT, 6, 2)).apply { components.forEach { add(it) } }

private val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault())

internal fun formatTime(time: Instant?): String = time?.let(TIME_FORMAT::format).orEmpty()

internal fun formatSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
    bytes < 1024L * 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024))
    else -> "%.2f GB".format(bytes / (1024.0 * 1024 * 1024))
}

private val HOME = System.getProperty("user.home")

private fun File.displayPath(): String = absolutePath.let { if (it.startsWith(HOME)) "~" + it.removePrefix(HOME) else it }
