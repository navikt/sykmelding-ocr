package no.nav.tsm.ocr.template.gcp

import no.nav.tsm.ocr.template.testing.AlignmentModel
import no.nav.tsm.ocr.template.testing.BatchSettings
import no.nav.tsm.ocr.template.testing.BatchStatus
import no.nav.tsm.ocr.template.testing.DEFAULT_MIN_LINE_FIT
import no.nav.tsm.ocr.template.testing.DEFAULT_PARALLEL
import no.nav.tsm.ocr.template.testing.FeatureDetectorKind
import no.nav.tsm.ocr.template.testing.FeatureSettings
import no.nav.tsm.ocr.template.testing.GuideImagePanel
import no.nav.tsm.ocr.template.testing.GuidePane
import no.nav.tsm.ocr.template.testing.LINE_TOLERANCE
import no.nav.tsm.ocr.template.testing.previewBoxes
import no.nav.tsm.ocr.template.testing.summarize
import org.example.no.nav.tsm.ocr.template.template.TemplateGroup
import org.example.no.nav.tsm.ocr.template.template.TemplateLibrary
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.GridLayout
import java.awt.Rectangle
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.awt.image.BufferedImage
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.time.Instant
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.prefs.Preferences
import javax.imageio.ImageIO
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComboBox
import javax.swing.JFileChooser
import javax.swing.JFrame
import javax.swing.JLabel
import javax.swing.JMenuItem
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JPopupMenu
import javax.swing.JScrollPane
import javax.swing.JSpinner
import javax.swing.JSplitPane
import javax.swing.JTabbedPane
import javax.swing.JTable
import javax.swing.JTextArea
import javax.swing.JTextField
import javax.swing.ListSelectionModel
import javax.swing.RowFilter
import javax.swing.RowSorter
import javax.swing.SortOrder
import javax.swing.SpinnerNumberModel
import javax.swing.SwingUtilities
import javax.swing.SwingWorker
import javax.swing.Timer
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import javax.swing.table.AbstractTableModel
import javax.swing.table.DefaultTableCellRenderer
import javax.swing.table.TableRowSorter
import kotlin.collections.emptyList

class BucketTemplateTestView(
    private val sourceFor: (String) -> BucketSource = { GcsBucketSource(it) },
    private val library: TemplateLibrary = TemplateLibrary(),
    private val settings: Preferences? = Preferences.userNodeForPackage(GcpBucketView::class.java),
    private val downloadCache: DownloadCache = DownloadCache.shared,
    private val onTemplateCreated: ((pages: List<File>, openInEditor: Boolean) -> Unit)? = null,
) : JPanel(BorderLayout(6, 6)) {

    // Listing, as in GcpBucketView (which shares the last used bucket and prefix).
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
    private val showDownloads = JButton("Show downloads").apply {
        toolTipText = "<html>List the files already downloaded from this bucket instead of the bucket itself, e.g. when listing " +
            "fails<br>(expired credentials, no network). They are previewed and tested as usual. Their date added is unknown, " +
            "and files<br>whose names had to be changed to store them (names with / or unusual characters) are not listed.</html>"
        addActionListener { listDownloads() }
    }
    private val stopListing = JButton("Stop listing").apply { isEnabled = false; addActionListener { listing?.cancel(true) } }
    private val filter = JTextField(18).apply { toolTipText = "Show only names containing this text" }
    private val pdfOnly = JCheckBox("PDF only", settings?.getBoolean(PDF_ONLY, true) ?: true)
    private val resultFilter = JComboBox(ShowFilter.entries.toTypedArray()).apply { toolTipText = "Show files by their test result" }
    private val count = JLabel(" ")

    // Test settings, as in "Test against many".
    private val detector = JComboBox(FeatureDetectorKind.entries.filter { it.ownDescriptor != null }.toTypedArray()).apply {
        selectedItem = FeatureDetectorKind.AKAZE
        toolTipText = "AKAZE separates matching and other forms most clearly; ORB is several times faster."
    }
    private val maxPoints = JSpinner(SpinnerNumberModel(20_000, 0, 1_000_000, 1000)).apply {
        toolTipText = "Keep the strongest points per page; 0 = all."
    }
    private val alignment = JComboBox(AlignmentModel.entries.toTypedArray()).apply { selectedItem = AlignmentModel.HOMOGRAPHY }
    private val minLineFit = JSpinner(SpinnerNumberModel((100 * DEFAULT_MIN_LINE_FIT).toInt(), 0, 100, 5)).apply {
        toolTipText = "A matching page must also line up with the form: at least this share of the template's ruled lines " +
            "must lie on the aligned page (within $LINE_TOLERANCE px). 0 = off."
    }
    private val parallel = JSpinner(SpinnerNumberModel(DEFAULT_PARALLEL, 1, Runtime.getRuntime().availableProcessors(), 1)).apply {
        toolTipText = "At most this many page × template tests run at the same time across all files. Files are tested " +
            "in parallel; AKAZE already uses several cores per test, and each running test needs several hundred MB of memory."
    }
    private val checkText = JCheckBox("Check required text first", true).apply {
        toolTipText = "<html>Template pages can require text, e.g. «sykmelding» (set it in the template editor).<br>" +
            "A page is read with OpenCV + Tesseract first, and only aligned (AKAZE) to template pages whose text it has.<br>" +
            "Untick to align every page to every template page.</html>"
    }

    // Running tests.
    private val testSelected = JButton("Test selected").apply {
        toolTipText = "<html>Match the pages of the selected files to template pages:<br>" +
            "a page is tried against each template's first page, in library order, until one matches (else the next page is tried);<br>" +
            "the pages after it are then tested against that template's page 2, 3, … only.</html>"
        addActionListener { testSelection() }
    }
    private val runCount = JSpinner(SpinnerNumberModel((settings?.getInt(RUN_COUNT, 10) ?: 10).coerceIn(1, MAX_RUN), 1, MAX_RUN, 1)).apply {
        toolTipText = "How many of the shown files to test"
    }
    private val order = JComboBox(RunOrder.entries.toTypedArray()).apply { toolTipText = "Which of the shown files to test" }
    private val untestedOnly = JCheckBox("Untested only", true).apply { toolTipText = "Skip files that already have a result" }
    private val run = JButton("Run").apply {
        toolTipText = "Test the files concurrently, up to the Parallel tests limit. Uses the list's current sort and filter."
        addActionListener { runNext() }
    }
    private val stop = JButton("Stop").apply { isEnabled = false; addActionListener { requestStop() } }
    private val templatesLabel = JLabel(" ")
    /** Folder names of the templates to test against; null = all, including templates added later. Only changed on the EDT while no run is going. */
    @Volatile private var chosenTemplates: Set<String>? = settings?.get(CHOSEN_TEMPLATES, null)?.split('\n')?.filter { it.isNotEmpty() }?.toSet()
    private val chooseTemplates = JButton("Choose templates…").apply {
        toolTipText = "Test against one, several or all templates."
        addActionListener { chooseTemplates() }
    }
    private val reloadTemplates = JButton("Reload templates").apply {
        toolTipText = "Read the template library again. Edited templates are also picked up automatically when a test starts."
        addActionListener { reloadTemplates() }
    }
    private val summary = JLabel(" ")

    /** Test results by [resultKey]; they survive reloading the list. Only touched on the EDT. */
    private val results = HashMap<String, PdfTestResult>()
    private val files = FileTableModel()
    private val sorter = TableRowSorter(files)
    private val table = JTable(files)
    private val details = JTextArea(10, 40).apply { isEditable = false; font = Font(Font.MONOSPACED, Font.PLAIN, 12) }
    private val preview = FilePreviewPanel()

    private val resultTitle = JLabel(" ").apply { font = font.deriveFont(Font.BOLD) }
    private val outcomes = OutcomeTableModel()
    private val outcomeTable = JTable(outcomes)
    private val images = GuideImagePanel()
    private val actualSize = JCheckBox("Actual size (1:1)").apply {
        toolTipText = "Previews are half the size of the 400-DPI page."
        addActionListener { images.actualSize = isSelected }
    }
    private val outcomeText = JTextArea(6, 40).apply {
        isEditable = false; lineWrap = true; wrapStyleWord = true; font = Font(Font.MONOSPACED, Font.PLAIN, 12)
    }
    private val tabs = JTabbedPane()
    private val compare = BucketComparePanel({ LibraryPages.load(library).pages })
    private val openInCompare = JButton("Open in Compare").apply {
        toolTipText = "Open the selected PDF page and template page in the Compare tab, to apply reference images and read fields."
        isEnabled = false
        addActionListener { selectedOutcome()?.let(::compareOutcome) }
    }
    private val status = JLabel(" ")

    // The file in the preview.
    private val shownName = JLabel(" ").apply { font = font.deriveFont(Font.BOLD) }
    private val download = JButton("Download…").apply {
        isEnabled = false
        toolTipText = "Save the file in the preview to a folder of your choice."
        addActionListener { downloadShown() }
    }
    private val createTemplate = JButton("Create template from…").apply {
        isEnabled = false
        toolTipText = "<html>Choose pages of the PDF in the preview and make them a new template,<br>" +
            "or add them to an existing one. Each page becomes a template image at 400 DPI, without fields yet.</html>"
        addActionListener { createTemplateFromShown() }
    }
    private val downloadItem = JMenuItem("Download…").apply { addActionListener { downloadShown() } }
    private val createTemplateItem = JMenuItem("Create template from…").apply { addActionListener { createTemplateFromShown() } }
    private var downloading = false
    private var creatingTemplate = false

    /** Where "Download…" saves [BucketFile]; null to cancel. Replaced by tests. */
    internal var chooseDownloadTarget: (BucketFile) -> File? = ::askDownloadTarget

    /** What "Create template from…" makes of the PDF; null to cancel. Replaced by tests. */
    internal var askTemplateRequest: (TemplateDialogInput) -> TemplateFromPdfRequest? = { CreateTemplateDialog.ask(this, it, library) }

    /** The bucket currently listed; previews and new runs use this, not the (editable) combo box. */
    private var source: BucketSource? = null
    private var listing: SwingWorker<Int, List<BucketFile>>? = null
    private var listingToken = 0
    private var previewToken = 0
    private var previewing = false
    @Volatile private var closed = false
    private var shown: BucketFile? = null
    /** The file and PDF-tab page the Compare tab last followed. */
    private var comparedPreview: Pair<String, Int>? = null
    private var shownPdf: PdfInfo? = null
    private var resultFile: BucketFile? = null
    private var shownResult: PdfTestResult? = null
    private val downloadFolder = DownloadFolderControls(downloadCache) { status.text = it }
    private val previewDelay = Timer(PREVIEW_DELAY_MS) { previewSelected() }.apply { isRepeats = false }

    private var job: SwingWorker<List<String>, Unit>? = null
    @Volatile private var stopRequested = false
    /** Template features from the last run, reused while the library and settings are unchanged. Only the run thread
     * changes it while a run is going; otherwise only the EDT. */
    @Volatile private var prepared: PreparedTemplates? = null
    private var runKeys: List<String> = emptyList()
    private var runStarted = 0L
    private var runEnded = 0L
    private var runStopped = false
    private val clock = Timer(1000) { updateSummary() }

    /** Owner-only (see [Files.createTempDirectory]) folder for copies the pipeline needs under another name; removed when the JVM exits. */
    private val scratchFolder = lazy { Files.createTempDirectory("bucket-template-test-").also { it.toFile().deleteOnExit() } }
    private val scratch: Path by scratchFolder

    init {
        border = BorderFactory.createEmptyBorder(8, 8, 8, 8)
        preferredSize = Dimension(1650, 980)
        setupTable()
        setupOutcomeTable()

        add(JPanel(GridLayout(0, 1, 0, 2)).apply {
            add(flowRow(JLabel("Bucket:"), bucket, JLabel("Prefix:"), prefix, loadButton, showDownloads, stopListing, Box.createHorizontalStrut(18),
                downloadFolder))
            add(flowRow(JLabel("Filter:"), filter, pdfOnly, JLabel("Show:"), resultFilter, count))
            add(flowRow(JLabel("Detector:"), detector, JLabel("max points:"), maxPoints, JLabel("Alignment:"), alignment,
                JLabel("Min. form lines on page (%):"), minLineFit, JLabel("Parallel tests:"), parallel))
            add(flowRow(checkText))
            add(flowRow(testSelected, Box.createHorizontalStrut(18), JLabel("Test"), runCount, JLabel("of the shown files"), order,
                untestedOnly, run, stop))
            add(flowRow(templatesLabel, chooseTemplates, reloadTemplates, Box.createHorizontalStrut(18), summary))
        }, BorderLayout.NORTH)

        val left = JSplitPane(JSplitPane.VERTICAL_SPLIT, JScrollPane(table),
            JScrollPane(details).apply { border = BorderFactory.createTitledBorder("Details") }).apply { resizeWeight = 0.7 }
        val result = JPanel(BorderLayout(4, 4)).apply {
            add(JPanel(BorderLayout()).apply {
                add(resultTitle, BorderLayout.CENTER)
                add(JPanel(FlowLayout(FlowLayout.RIGHT, 6, 0)).apply { add(openInCompare); add(actualSize) }, BorderLayout.EAST)
            }, BorderLayout.NORTH)
            val image = JSplitPane(JSplitPane.VERTICAL_SPLIT, JScrollPane(images), JScrollPane(outcomeText)).apply { resizeWeight = 0.85 }
            val outcomeList = JScrollPane(outcomeTable).apply { minimumSize = Dimension(200, 90) }
            add(JSplitPane(JSplitPane.VERTICAL_SPLIT, outcomeList, image).apply { dividerLocation = 170 }, BorderLayout.CENTER)
        }
        tabs.addTab("PDF", preview)
        tabs.addTab("Test result", result)
        tabs.addTab("Compare", compare)
        tabs.setToolTipTextAt(COMPARE_TAB, "A page of the file with a template page, as in the image comparison view: " +
            "apply reference images, apply the configuration and read fields.")
        tabs.addChangeListener { if (tabs.selectedIndex == COMPARE_TAB) compareShownPage() }
        val right = JPanel(BorderLayout(4, 4)).apply {
            add(JPanel(BorderLayout(6, 0)).apply {
                border = BorderFactory.createEmptyBorder(0, 4, 0, 0)
                add(shownName, BorderLayout.CENTER)
                add(JPanel(FlowLayout(FlowLayout.RIGHT, 6, 0)).apply { add(download); add(createTemplate) }, BorderLayout.EAST)
            }, BorderLayout.NORTH)
            add(tabs, BorderLayout.CENTER)
        }
        add(JSplitPane(JSplitPane.HORIZONTAL_SPLIT, left, right).apply { resizeWeight = 0.5; dividerLocation = 800 }, BorderLayout.CENTER)
        add(status, BorderLayout.SOUTH)

        filter.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = applyFilter()
            override fun removeUpdate(e: DocumentEvent) = applyFilter()
            override fun changedUpdate(e: DocumentEvent) = applyFilter()
        })
        pdfOnly.addActionListener { settings?.putBoolean(PDF_ONLY, pdfOnly.isSelected); applyFilter() }
        resultFilter.addActionListener { applyFilter() }
        (bucket.editor.editorComponent as? JTextField)?.addActionListener { load() }

        showTemplates(loadTemplates())
        applyFilter()
        updateControls()
        showResult()
        status.text = "Press Load to list gs://${bucketName()}"
    }

    private fun setupTable() = with(table) {
        rowSorter = sorter
        sorter.sortKeys = listOf(RowSorter.SortKey(FileColumns.CREATED, SortOrder.DESCENDING))
        selectionModel.selectionMode = ListSelectionModel.MULTIPLE_INTERVAL_SELECTION
        fillsViewportHeight = true
        autoResizeMode = JTable.AUTO_RESIZE_SUBSEQUENT_COLUMNS
        setDefaultRenderer(Instant::class.java, object : DefaultTableCellRenderer() {
            override fun setValue(value: Any?) { text = formatTime(value as? Instant) }
        })
        setDefaultRenderer(Int::class.javaObjectType, object : DefaultTableCellRenderer() {
            init { horizontalAlignment = RIGHT }
        })
        columnModel.getColumn(FileColumns.SIZE).cellRenderer = object : DefaultTableCellRenderer() {
            init { horizontalAlignment = RIGHT }
            override fun setValue(value: Any?) { text = (value as? Long)?.let(::formatSize).orEmpty() }
        }
        columnModel.getColumn(FileColumns.TIME).cellRenderer = secondsRenderer()
        // Long names and template lists are cut off; the tooltip shows them in full.
        val fullTextRenderer = object : DefaultTableCellRenderer() {
            override fun setValue(value: Any?) {
                text = value?.toString().orEmpty()
                toolTipText = text.takeIf { it.isNotEmpty() }
            }
        }
        columnModel.getColumn(FileColumns.NAME).cellRenderer = fullTextRenderer
        columnModel.getColumn(FileColumns.BEST).cellRenderer = fullTextRenderer
        columnModel.getColumn(FileColumns.RESULT).cellRenderer = object : DefaultTableCellRenderer() {
            override fun getTableCellRendererComponent(table: JTable, value: Any?, selected: Boolean, focus: Boolean, row: Int, column: Int): Component {
                val result = files.resultAt(table.convertRowIndexToModel(row))
                return super.getTableCellRendererComponent(table, result?.statusText.orEmpty(), selected, focus, row, column).also {
                    if (!selected) foreground = statusColor(result?.status)
                }
            }
        }
        listOf(300 to 120, 70 to 60, 140 to 130, 45 to 40, 110 to 90, 260 to 100, 60 to 50).forEachIndexed { index, (preferred, minimum) ->
            columnModel.getColumn(index).apply { preferredWidth = preferred; minWidth = minimum }
        }
        selectionModel.addListSelectionListener { if (!it.valueIsAdjusting) selectionChanged() }
        sorter.addRowSorterListener { updateCount() }
        componentPopupMenu = JPopupMenu().apply {
            add(JMenuItem("Test selected").apply { addActionListener { testSelection() } })
            addSeparator()
            add(downloadItem)
            add(createTemplateItem)
            add(JMenuItem("Show downloaded file").apply {
                toolTipText = "Show the previewed file in the download folder (it is downloaded when it is previewed)."
                addActionListener { revealShown() }
            })
            addSeparator()
            add(JMenuItem("Copy name").apply { addActionListener { copy { it.name } } })
            add(JMenuItem("Copy gs:// path").apply { addActionListener { copy { "gs://${source?.bucket}/${it.name}" } } })
            add(JMenuItem("Copy results (tab-separated)").apply {
                addActionListener {
                    copy { file ->
                        results[files.key(file)]?.takeIf { it.status.finished }?.let { result ->
                            listOf(file.name, result.pages?.toString().orEmpty(), result.status.name, result.templatesPerPage).joinToString("\t")
                        }
                    }
                }
            })
        }
    }

    private fun setupOutcomeTable() = with(outcomeTable) {
        selectionModel.selectionMode = ListSelectionModel.SINGLE_SELECTION
        fillsViewportHeight = true
        setDefaultRenderer(BatchStatus::class.java, object : DefaultTableCellRenderer() {
            override fun getTableCellRendererComponent(table: JTable, value: Any?, selected: Boolean, focus: Boolean, row: Int, column: Int): Component =
                super.getTableCellRendererComponent(table, value, selected, focus, row, column).also {
                    if (!selected) foreground = when (value) {
                        BatchStatus.MATCH -> MATCH_COLOR
                        BatchStatus.NO_MATCH -> NO_MATCH_COLOR
                        BatchStatus.ERROR -> ERROR_COLOR
                        else -> Color.GRAY
                    }
                }
        })
        setDefaultRenderer(Double::class.javaObjectType, object : DefaultTableCellRenderer() {
            init { horizontalAlignment = RIGHT }
            override fun setValue(value: Any?) { text = (value as? Double)?.let { "%.0f %%".format(100 * it) }.orEmpty() }
        })
        setDefaultRenderer(Long::class.javaObjectType, secondsRenderer())
        columnModel.getColumn(OutcomeTableModel.TEXT).cellRenderer = object : DefaultTableCellRenderer() {
            override fun getTableCellRendererComponent(table: JTable, value: Any?, selected: Boolean, focus: Boolean, row: Int, column: Int): Component =
                super.getTableCellRendererComponent(table, value, selected, focus, row, column).also {
                    if (!selected) foreground = when (value?.toString()?.firstOrNull()) {
                        '✓' -> MATCH_COLOR
                        '✗' -> NO_MATCH_COLOR
                        else -> Color.GRAY
                    }
                }
        }
        listOf(60, 70, 200, 90, 60, 60, 60).forEachIndexed { index, width -> columnModel.getColumn(index).preferredWidth = width }
        selectionModel.addListSelectionListener {
            if (!it.valueIsAdjusting) {
                showOutcome(selectedOutcome())
                openInCompare.isEnabled = selectedOutcome() != null
            }
        }
        addMouseListener(object : java.awt.event.MouseAdapter() {
            override fun mouseClicked(e: java.awt.event.MouseEvent) {
                if (e.clickCount == 2) selectedOutcome()?.let(::compareOutcome)
            }
        })
        toolTipText = "Double-click a row to open it in the Compare tab."
    }

    /** Lists the bucket from scratch, newest first. Test results are kept, so reloading shows them again. */
    fun load() = list(downloaded = false)

    /** Lists the files downloaded from the bucket instead (see [DownloadedFilesSource]); test results are shared with [load]'s. */
    private fun listDownloads() = list(downloaded = true)

    private fun list(downloaded: Boolean) {
        if (closed) return
        val name = bucketName()
        if (name.isEmpty()) { status.text = "Enter a bucket name."; return }
        listing?.cancel(true)
        val prefixText = prefix.text.trim()
        settings?.put(BUCKET, name)
        settings?.put(PREFIX, prefixText)
        val current = sourceFor(name).let { if (downloaded) DownloadedFilesSource(it, downloadCache) else it }
        source = current
        val token = ++listingToken
        previewToken++
        previewing = false
        files.clear(current.bucket)
        shown = null
        shownPdf = null
        compare.setDocument(null, 0, null)
        details.text = ""
        preview.showMessage("Select a file to preview it.")
        showResult()
        val location = "gs://$name/$prefixText"
        val target = if (downloaded) "the files downloaded from $location" else location
        status.text = "Listing $target …"
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
                files.add(chunks.flatten())
                if (listing === this) status.text = "Listing $target … ${files.rowCount} files so far"
            }

            override fun done() {
                if (listing !== this) return
                listing = null
                updateControls()
                status.text = when {
                    isCancelled -> "Stopped listing $target. The list is incomplete."
                    else -> try {
                        val found = get()
                        when {
                            !downloaded -> "Listed $found files from $location" + if (found == 0) " — the bucket or prefix is empty." else "."
                            found == 0 -> "No files downloaded from $location."
                            else -> "Listed $found file${plural(found)} downloaded from $location. Press Load to list the bucket again."
                        }
                    } catch (error: Exception) {
                        val cause = if (error is ExecutionException) error.cause ?: error else error
                        "Could not list $target: ${describeGcpError(cause).replace("\n", " — ")}" +
                            if (downloaded) "" else " — Show downloads lists the files already downloaded from it."
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

    /** The row last clicked, so shift/cmd-selecting several files shows the one clicked. */
    private fun leadFile(): BucketFile? {
        val lead = table.selectionModel.leadSelectionIndex
        val row = if (lead >= 0 && table.isRowSelected(lead)) lead else table.selectedRow
        return if (row < 0) null else files.rows[table.convertRowIndexToModel(row)]
    }

    private fun showFile(file: BucketFile) {
        val current = source ?: return
        shown = file
        shownPdf = null
        compare.setDocument(file.name, 0, null)
        showDetails()
        showResult()
        updateControls()
        val token = ++previewToken
        previewing = true
        val cached = downloadCache.cachedFile(current.bucket, file) != null
        preview.showMessage(if (cached) "Opening ${file.fileName} from the download folder …"
            else "Downloading ${file.fileName} (${formatSize(file.size)}) …", file.fileName)
        object : SwingWorker<Pair<ByteArray, PreviewContent>, Unit>() {
            override fun doInBackground(): Pair<ByteArray, PreviewContent> {
                val bytes = fetch(current, file)
                val content = try {
                    PreviewContent.of(file, bytes)
                } catch (error: Exception) {
                    PreviewContent.Unsupported("Could not read ${file.fileName}: ${error.message}")
                }
                return bytes to content
            }

            override fun done() {
                if (closed) return
                if (!cached) downloadFolder.refresh()
                if (token != previewToken) return
                previewing = false
                runCatching { get() }.onSuccess { (bytes, content) ->
                    preview.show(file.fileName, bytes, content)
                    shownPdf = (content as? PreviewContent.Pdf)?.info
                    val pages = shownPdf?.pages?.size ?: if (content is PreviewContent.Image) 1 else 0
                    if (pages > 0) compare.setDocument(file.name, pages) { fetch(current, file) }
                    showDetails()
                    if (tabs.selectedIndex == COMPARE_TAB) compareShownPage()
                }.onFailure { error ->
                    val cause = if (error is ExecutionException) error.cause ?: error else error
                    preview.showMessage("Could not download ${file.fileName}:\n${describeGcpError(cause)}", file.fileName)
                }
                updateControls()
            }
        }.execute()
    }

    private fun testSelection() {
        val selected = table.selectedRows.map { files.rows[table.convertRowIndexToModel(it)] }
        if (selected.isEmpty()) { status.text = "Select one or more files to test."; return }
        if (startRun(selected, keepAllPreviews = selected.size == 1) && selected.size == 1) tabs.selectedIndex = RESULT_TAB
    }

    /** Tests the next [runCount] shown files that can be tested, in list order or at random. */
    internal fun runNext() {
        val wanted = try {
            runCount.commitEdit()
            (runCount.value as Number).toInt()
        } catch (error: Exception) {
            status.text = "Invalid number of files: ${error.message}"
            return
        }
        settings?.putInt(RUN_COUNT, wanted)
        val shownFiles = (0 until table.rowCount).map { files.rows[table.convertRowIndexToModel(it)] }
        val testable = shownFiles.filter { it.isPdf || it.extension in IMAGE_SUFFIXES }
        val candidates = testable.filter { !untestedOnly.isSelected || results[files.key(it)]?.status?.finished != true }
        val chosen = (if (order.selectedItem == RunOrder.RANDOM) candidates.shuffled() else candidates).take(wanted)
        if (chosen.isEmpty()) {
            status.text = when {
                shownFiles.isEmpty() -> "No files are shown. Press Load, or change the filter."
                testable.isEmpty() -> "None of the shown files is a PDF or an image."
                else -> "All shown files are tested already. Untick \"Untested only\" to test them again."
            }
            return
        }
        startRun(chosen, keepAllPreviews = chosen.size == 1)
    }

    /**
     * Tests [chosen] concurrently; the shared pool limits page × template tests across all files to [parallel]. The template
     * features are detected once and reused by later runs while the library and the settings are unchanged. With
     * [keepAllPreviews], every outcome keeps its preview; otherwise only the template page each page matched (or came closest to) does.
     */
    private fun startRun(chosen: List<BucketFile>, keepAllPreviews: Boolean): Boolean {
        if (job != null || closed || chosen.isEmpty()) return false
        val current = source ?: return false
        val batch = readSettings() ?: return false
        val threads = (parallel.value as Number).toInt()
        val keys = chosen.map { resultKey(current.bucket, it) }
        val previous = keys.associateWith { results[it] }
        keys.forEach { setResult(it, PdfTestResult(PdfStatus.QUEUED)) }
        stopRequested = false
        runKeys = keys
        runStarted = System.nanoTime()
        runEnded = 0L
        runStopped = false
        fun post(key: String, result: PdfTestResult?) = SwingUtilities.invokeLater { if (!closed) setResult(key, result) }
        fun say(text: String) = SwingUtilities.invokeLater { if (!closed) status.text = text }

        val worker = object : SwingWorker<List<String>, Unit>() {
            override fun doInBackground(): List<String> {
                val pool = Executors.newFixedThreadPool(threads) { task -> Thread(task, "bucket-template-test").apply { isDaemon = true } }
                val fileWorkers = Executors.newFixedThreadPool(threads) { task -> Thread(task, "bucket-template-file").apply { isDaemon = true } }
                try {
                    val templates = templatesFor(batch.features, pool, ::say)
                    for ((index, file) in chosen.withIndex()) {
                        val key = keys[index]
                        fileWorkers.submit {
                            if (stopRequested || closed) return@submit
                            say("Testing file ${index + 1} of ${chosen.size}: ${file.fileName} …")
                            post(key, PdfTestResult(PdfStatus.RUNNING, "Downloading …"))
                            // The file is kept in use while it is tested, so "Clear downloads" leaves it alone.
                            val result = try {
                                downloadCache.using(current, file) { cached ->
                                    withTestableFile(file, cached) { local ->
                                        testAgainstTemplates(local, templates, batch, pool, { stopRequested || closed }) { done, total ->
                                            post(key, PdfTestResult(PdfStatus.RUNNING, done = done, total = total))
                                        }
                                    }
                                }
                            } catch (error: Exception) {
                                PdfTestResult(PdfStatus.ERROR, "Could not download the file: " + describeGcpError(error).replace("\n", " — "),
                                    settings = batch)
                            }
                            // A file stopped part-way keeps its earlier result.
                            post(key, when {
                                result == null -> previous[key]
                                keepAllPreviews || result.status == PdfStatus.ERROR -> result
                                else -> result.compact()
                            })
                        }
                    }
                    fileWorkers.shutdown()
                    fileWorkers.awaitTermination(1, TimeUnit.DAYS)
                    return templates.problems
                } finally {
                    fileWorkers.shutdownNow()
                    fileWorkers.awaitTermination(1, TimeUnit.DAYS)
                    pool.shutdown()
                    pool.awaitTermination(1, TimeUnit.DAYS)
                }
            }

            override fun done() {
                job = null
                runEnded = System.nanoTime()
                runStopped = stopRequested
                clock.stop()
                // Files the run did not reach keep their earlier result.
                keys.forEach { key -> if (results[key]?.status?.finished != true) setResult(key, previous[key]) }
                if (closed) { closePrepared(); return }
                status.text = try {
                    val problems = get()
                    (if (runStopped) "Stopped." else "Finished.") +
                        if (problems.isEmpty()) "" else " Not tested against: ${problems.joinToString("; ")}"
                } catch (error: Exception) {
                    val cause = if (error is ExecutionException) error.cause ?: error else error
                    "The run failed: ${cause.message}"
                }
                applyFilter()
                updateControls()
                updateSummary()
                downloadFolder.refresh()
                showDetails()
            }
        }
        job = worker
        clock.start()
        updateControls()
        updateSummary()
        worker.execute()
        return true
    }

    /** On the run thread: the library as it is now, with features from an earlier run when nothing changed. */
    private fun templatesFor(features: FeatureSettings, pool: ExecutorService, say: (String) -> Unit): PreparedTemplates {
        val pages = loadTemplates()
        SwingUtilities.invokeLater { if (!closed) showTemplates(pages) }
        prepared?.let { if (it.matches(pages, features)) return it }
        closePrepared()
        say("Preparing ${pages.pages.size} template page${plural(pages.pages.size)}: reading the images and detecting their features once …")
        return prepareTemplates(pages, features, pool) { done, total -> say("Preparing templates: $done of $total …") }.also { prepared = it }
    }

    private fun closePrepared() {
        prepared?.close()
        prepared = null
    }

    private fun requestStop() {
        stopRequested = true
        stop.isEnabled = false
        status.text = "Stopping after the tests in progress …"
    }

    private fun reloadTemplates() {
        if (job != null) return
        closePrepared()
        val pages = loadTemplates()
        showTemplates(pages)
        status.text = "Read ${pages.description} from ${library.directory.absolutePath}. Their features are detected at the next test."
    }

    /** The chosen templates as they are now. */
    private fun loadTemplates(): LibraryPages = LibraryPages.load(library, chosenTemplates)

    /** Asks which templates to test against; at least one must be ticked. */
    private fun chooseTemplates() {
        if (job != null) return
        val groups = templateGroups()
        if (groups.isEmpty()) { status.text = "No templates in ${library.directory.absolutePath}."; return }
        val boxes = groups.map { group ->
            val folder = group.file.parentFile.name
            JCheckBox("${group.manifest.navn} (${group.pageFiles.size} page${plural(group.pageFiles.size)})",
                chosenTemplates?.contains(folder) ?: true).apply { putClientProperty(FOLDER, folder) }
        }
        val panel = JPanel(BorderLayout(0, 6)).apply {
            add(JLabel("Test the PDFs against these templates:"), BorderLayout.NORTH)
            add(JScrollPane(JPanel(GridLayout(0, 1, 0, 2)).apply { boxes.forEach(::add) }).apply {
                preferredSize = Dimension(360, minOf(400, 28 * boxes.size + 10))
            }, BorderLayout.CENTER)
            add(flowRow(
                JButton("All").apply { addActionListener { boxes.forEach { it.isSelected = true } } },
                JButton("None").apply { addActionListener { boxes.forEach { it.isSelected = false } } },
            ), BorderLayout.SOUTH)
        }
        while (true) {
            if (JOptionPane.showConfirmDialog(this, panel, "Choose templates", JOptionPane.OK_CANCEL_OPTION,
                    JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) return
            if (boxes.any { it.isSelected }) break
            JOptionPane.showMessageDialog(this, "Choose at least one template.", "Choose templates", JOptionPane.WARNING_MESSAGE)
        }
        setChosenTemplates(if (boxes.all { it.isSelected }) null else boxes.filter { it.isSelected }.map { it.getClientProperty(FOLDER) as String }.toSet())
    }

    /** Null = all templates. Results already shown keep the templates they were tested against. */
    internal fun setChosenTemplates(folders: Set<String>?) {
        chosenTemplates = folders
        if (folders == null) settings?.remove(CHOSEN_TEMPLATES) else settings?.put(CHOSEN_TEMPLATES, folders.sorted().joinToString("\n"))
        val pages = loadTemplates()
        showTemplates(pages)
        status.text = "Testing against ${pages.description}."
    }

    private fun showTemplates(pages: LibraryPages) {
        val chosen = chosenTemplates
        templatesLabel.text = "Templates" + (if (chosen == null) " (all)" else " (${pages.groups} chosen)") + ": ${pages.description}"
        templatesLabel.foreground = if (pages.pages.isEmpty()) ERROR_COLOR else null
        templatesLabel.toolTipText = "<html>" + (pages.pages.map { escape(it.label) } + pages.problems.map { "<i>Not tested: ${escape(it)}</i>" })
            .joinToString("<br>") + "</html>"
    }

    internal fun readSettings(): BatchSettings? = try {
        maxPoints.commitEdit(); minLineFit.commitEdit(); parallel.commitEdit()
        BatchSettings(
            detector = detector.selectedItem as FeatureDetectorKind,
            maxPoints = (maxPoints.value as Number).toInt(),
            model = alignment.selectedItem as AlignmentModel,
            minLineFit = (minLineFit.value as Number).toDouble() / 100,
        )
    } catch (error: Exception) {
        status.text = "Invalid test settings: ${error.message}"
        null
    }

    /** On the EDT: stores (or with null, forgets) a file's result and refreshes what shows it. */
    private fun setResult(key: String, result: PdfTestResult?) {
        if (result == null) results.remove(key) else results[key] = result
        files.updated(key)
        if (result == null || result.status.finished) updateCount()
        if (shown?.let(files::key) == key) {
            showDetails()
            showResult()
        }
    }

    private fun showDetails() {
        val file = shown
        if (file == null) { details.text = ""; return }
        details.text = buildString {
            fun line(key: String, value: Any?) {
                val text = value?.toString().orEmpty()
                if (text.isNotEmpty()) append(key.padEnd(15)).append(text).append('\n')
            }
            line("Name", file.name)
            line("Location", "gs://${source?.bucket}/${file.name}")
            line("Size", "%,d bytes (%s)".format(file.size, formatSize(file.size)))
            line("Date added", formatTime(file.created))
            line("Content type", file.contentType)
            file.reference?.let { line("JournalpostId", it.journalpostId); line("DokumentInfoId", it.dokumentInfoId) }
            source?.let { downloadCache.cachedFile(it.bucket, file) }?.let { line("Downloaded to", it.path) }
            shownPdf?.let { pdf ->
                line("PDF pages", pdf.pages.size)
                pdf.pages.forEachIndexed { index, page -> line("  Page ${index + 1}", page.description) }
                line("Producer", pdf.producer)
            }
            append('\n')
            append(results[files.key(file)]?.summary() ?: "Not tested yet.")
        }
        details.caretPosition = 0
    }

    /** The "Test result" tab: every page × template outcome of the shown file, the best of page 1 selected. */
    private fun showResult() {
        val file = shown
        val result = file?.let { results[files.key(it)] }
        if (file === resultFile && result === shownResult) return
        resultFile = file
        shownResult = result
        resultTitle.text = when {
            file == null -> " "
            result == null -> "${file.fileName} — not tested"
            result.status.finished && result.pageResults.isNotEmpty() -> "${file.fileName} — ${result.statusText}: ${result.templatesPerPage}"
            else -> "${file.fileName} — ${result.statusText}"
        }
        outcomes.reset(result?.pageResults?.flatMap { it.outcomes }.orEmpty())
        if (outcomes.rowCount > 0) outcomeTable.setRowSelectionInterval(0, 0) else showOutcome(null)
    }

    private fun selectedOutcome(): TemplateOutcome? = outcomeTable.selectedRow.takeIf { it >= 0 }?.let { outcomes.rows[it] }

    /** The Compare tab with the page shown in the PDF tab, and the template page it matched (or came closest to). */
    private fun compareShownPage() {
        val file = shown ?: return
        if (!compare.canLoad) return
        val page = previewPage()
        // Only when another page is shown than last time, so a page opened from the test result is kept.
        if (comparedPreview == file.name to page) return
        comparedPreview = file.name to page
        compare.open(page, templateFor(file, page))
    }

    private fun compareOutcome(outcome: TemplateOutcome) {
        shown?.let { comparedPreview = it.name to previewPage() }
        compare.open(outcome.page, outcome.template.file)
        tabs.selectedIndex = COMPARE_TAB
    }

    private fun previewPage(): Int = if (preview.currentPageCount > 0) preview.currentPage + 1 else 1

    /** The template page [page] of [file] matched in the last test, or else came closest to; null when untested. */
    private fun templateFor(file: BucketFile, page: Int): File? =
        results[files.key(file)]?.pageResults?.firstOrNull { it.page == page }?.outcomes?.firstOrNull()?.template?.file

    private fun showOutcome(outcome: TemplateOutcome?) {
        if (outcome == null) {
            images.panes = emptyList()
            val result = shownResult
            outcomeText.text = when {
                shown == null -> "Select a file in the list."
                result == null -> "Not tested yet. Click \"Test selected\" to test every page against every template page."
                else -> result.summary()
            }
            outcomeText.caretPosition = 0
            return
        }
        val result = outcome.result
        val image = result.previewImage()
        val template = outcome.template
        val fields = previewBoxes(template.template.felter.map { Rectangle(it.x, it.y, it.bredde, it.hoyde) }, result.previewScale)
        val lines = result.lineFit?.let { " Form lines on page: %.0f %%.".format(100 * it) }.orEmpty()
        images.panes = when {
            image == null -> emptyList()
            result.status == BatchStatus.MATCH -> listOf(GuidePane(
                "PDF page ${outcome.page} aligned to ${template.label} (black), template on top (red); green = fields.$lines", image, fields))
            result.aligned -> listOf(GuidePane(
                "PDF page ${outcome.page} aligned to ${template.label}, but the form lines do not line up — not a match.$lines", image, fields))
            else -> listOf(GuidePane("PDF page ${outcome.page} at 400 DPI — did not match ${template.label}", image))
        }
        val minimum = shownResult?.settings?.minLineFit ?: DEFAULT_MIN_LINE_FIT
        outcomeText.text = buildString {
            appendLine("${result.status}  PDF page ${outcome.page} → ${template.label}")
            appendLine(when (outcome.role) {
                PageRole.START -> "Tested as a first page: each template's first page is tried, in library order, until one matches."
                PageRole.NEXT -> "Tested as the next page of ${template.group}, which an earlier page started; only this template page was tried."
            })
            if (result.millis > 0) appendLine("Time: %.1f s".format(result.millis / 1000.0))
            result.agreement?.let {
                appendLine("Agreeing matches: ${result.inliers} / ${result.matches} (%.0f %%; at least 40 %% needed)".format(100 * it))
            }
            result.lineFit?.let {
                appendLine("Form lines on page: %.0f %% of the template's lines within %d px (at least %.0f %% needed)"
                    .format(100 * it, LINE_TOLERANCE, 100 * minimum))
            }
            result.transform?.let { appendLine("Page → template: ${summarize(it)}") }
            when {
                image == null && result.status != BatchStatus.ERROR -> appendLine("No picture: after a run of several files, only the template " +
                    "page each page matched (or came closest to) keeps one. Test this file alone (\"Test selected\") to see them all.")
            }
            if (result.message.isNotEmpty()) { appendLine(); appendLine(result.message) }
            shownResult?.settings?.let { appendLine(); appendLine("Settings: ${describeSettings(it)}") }
        }
        outcomeText.caretPosition = 0
    }

    /** The file's bytes from the download folder, downloaded first if needed. Not on the EDT. */
    private fun fetch(source: BucketSource, file: BucketFile): ByteArray = downloadCache.using(source, file) { it.readBytes() }

    /** Shows the previewed file in the download folder. */
    private fun revealShown() {
        val file = shown ?: run { status.text = "Select a file first."; return }
        val cached = source?.let { downloadCache.cachedFile(it.bucket, file) }
        if (cached == null) status.text = "${file.fileName} is not downloaded yet." else downloadFolder.reveal(cached)
    }

    /**
     * Runs [use] with [cached] itself when its name tells the pipeline what it is (".pdf", or an image's extension),
     * and else with a copy under the right name that is deleted afterwards.
     */
    private fun <T> withTestableFile(file: BucketFile, cached: File, use: (File) -> T): T {
        val head = cached.inputStream().use { it.readNBytes(1024) }
        val suffix = suffixFor(file, head)
        return if (suffix.isEmpty() || cached.name.endsWith(suffix, ignoreCase = true)) use(cached)
            else withScratchFile(file, cached.readBytes(), use)
    }

    /** Saves the previewed file, exactly as stored in the bucket, where [chooseDownloadTarget] says. */
    private fun downloadShown() {
        val file = shown ?: return
        val current = source ?: return
        if (downloading) return
        val target = chooseDownloadTarget(file)?.absoluteFile ?: return
        downloading = true
        updateControls()
        status.text = "Saving ${file.fileName} to ${target.parent} …"
        object : SwingWorker<Unit, Unit>() {
            override fun doInBackground() = writeFile(target, fetch(current, file))

            override fun done() {
                downloading = false
                if (closed) return
                updateControls()
                downloadFolder.refresh()
                status.text = try {
                    get()
                    "Saved ${file.fileName} to ${target.path}"
                } catch (error: Exception) {
                    val cause = if (error is ExecutionException) error.cause ?: error else error
                    "Could not save ${file.fileName}: ${describeGcpError(cause).replace("\n", " — ")}"
                }
            }
        }.execute()
    }

    /** A save dialog in the folder last saved to (at first ~/Downloads) that suggests the file's own name. */
    private fun askDownloadTarget(file: BucketFile): File? {
        val home = File(System.getProperty("user.home"))
        val folder = settings?.get(DOWNLOAD_FOLDER, null)?.let(::File)?.takeIf { it.isDirectory }
            ?: home.resolve("Downloads").takeIf { it.isDirectory } ?: home
        val name = file.fileName.let { if (file.isPdf && !it.endsWith(".pdf", ignoreCase = true)) "$it.pdf" else it }
        val chooser = JFileChooser(folder).apply {
            dialogTitle = "Download ${file.fileName}"
            selectedFile = File(folder, name)
        }
        while (true) {
            if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return null
            val target = chooser.selectedFile.absoluteFile
            if (target.isDirectory) { chooser.currentDirectory = target; chooser.selectedFile = File(target, name); continue }
            if (target.exists() && JOptionPane.showConfirmDialog(this, "${target.name} already exists in ${target.parent}. Replace it?",
                    "Replace file", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE) != JOptionPane.YES_OPTION) continue
            settings?.put(DOWNLOAD_FOLDER, target.parent)
            return target
        }
    }

    /** Via a temporary file in the same folder, so a failed save never leaves part of a file. */
    private fun writeFile(target: File, bytes: ByteArray) {
        val destination = target.toPath()
        val temporary = Files.createTempFile(destination.parent, ".download-", ".part")
        try {
            Files.write(temporary, bytes)
            Files.move(temporary, destination, REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    /** Gets the previewed PDF (from the download folder), then asks which of its pages to make a template of. */
    private fun createTemplateFromShown() {
        val file = shown ?: return
        val pageCount = shownPdf?.pages?.size?.takeIf { it > 0 } ?: return
        val current = source ?: return
        if (creatingTemplate) return
        creatingTemplate = true
        updateControls()
        status.text = "Reading ${file.fileName} …"
        object : SwingWorker<ByteArray, Unit>() {
            override fun doInBackground(): ByteArray = fetch(current, file)

            override fun done() {
                creatingTemplate = false
                if (closed) return
                updateControls()
                val bytes = try {
                    get()
                } catch (error: Exception) {
                    val cause = if (error is ExecutionException) error.cause ?: error else error
                    status.text = "Could not download ${file.fileName}: ${describeGcpError(cause).replace("\n", " — ")}"
                    return
                }
                status.text = " "
                askAndCreateTemplate(file, bytes, pageCount)
            }
        }.execute()
    }

    private fun askAndCreateTemplate(file: BucketFile, bytes: ByteArray, pageCount: Int) {
        val tested = results[files.key(file)]?.takeIf { it.status.finished && it.pageResults.isNotEmpty() }
        val input = TemplateDialogInput(
            fileName = file.fileName,
            pdf = bytes,
            pageCount = pageCount,
            pageNotes = tested?.pageResults?.associate { page ->
                page.page to (page.match?.let { "✓ ${it.template.label}" } ?: if (page.skipped != null) "not tested" else "✗ no template")
            }.orEmpty(),
            unmatchedPages = tested?.pageResults?.filter { it.match == null }?.map { it.page }?.toSet(),
            groups = templateGroups(),
            canOpenEditor = onTemplateCreated != null,
        )
        val request = askTemplateRequest(input)
        if (request == null || closed) { status.text = "No template created."; return }
        val label = when (val target = request.target) {
            is TemplateTarget.New -> target.name
            is TemplateTarget.Existing -> target.group.manifest.navn
        }
        creatingTemplate = true
        updateControls()
        status.text = "Creating template $label …"
        object : SwingWorker<CreatedTemplate, String>() {
            override fun doInBackground() = createTemplateFromPdf(library, bytes, request.target, request.pages,
                request.rotations) { done, total ->
                if (done < total) publish("Creating template $label: PDF page ${request.pages[done]} at 400 DPI (${done + 1} of $total) …")
            }

            override fun process(chunks: List<String>) { if (creatingTemplate && !closed) status.text = chunks.last() }

            override fun done() {
                creatingTemplate = false
                if (closed) return
                updateControls()
                showTemplates(loadTemplates())
                try {
                    val created = get()
                    status.text = "Created ${created.pageFiles.joinToString(", ") { it.parentFile.name }} of ${created.group.manifest.navn} " +
                        "from PDF page${plural(request.pages.size)} ${request.pages.joinToString(", ")} in ${created.group.file.parent}. " +
                        request.rotations.size.let { if (it == 0) "" else "$it page${plural(it)} turned or straightened. " } +
                        "Add its fields in the template editor."
                    onTemplateCreated?.invoke(created.pageFiles, request.openInEditor)
                } catch (error: Exception) {
                    val cause = if (error is ExecutionException) error.cause ?: error else error
                    status.text = "Could not create template $label: ${cause.message}"
                    JOptionPane.showMessageDialog(this@BucketTemplateTestView, cause.message ?: cause.toString(), "Could not create template",
                        JOptionPane.ERROR_MESSAGE)
                }
            }
        }.execute()
    }

    /** The library's templates that can be read; a broken one is left out rather than hiding the others. */
    private fun templateGroups(): List<TemplateGroup> =
        library.directory.listFiles()?.filter { it.isDirectory && it.resolve("mal.json").isFile }?.sortedBy { it.name }
            ?.mapNotNull { runCatching { library.read(it.resolve("mal.json")) }.getOrNull() }.orEmpty()

    /** Writes [bytes] to the scratch folder only while [use] runs. */
    private fun <T> withScratchFile(file: BucketFile, bytes: ByteArray, use: (File) -> T): T {
        val temporary = Files.createTempFile(scratch, "test-", suffixFor(file, bytes))
        try {
            Files.write(temporary, bytes)
            return use(temporary.toFile())
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    /** The pipeline tells PDFs from images by the file extension. */
    private fun suffixFor(file: BucketFile, bytes: ByteArray): String = when {
        PreviewContent.isPdf(bytes) -> ".pdf"
        file.extension.isNotEmpty() -> ".${file.extension}"
        else -> ""
    }

    private fun copy(value: (BucketFile) -> String?) {
        val text = table.selectedRows.toList().mapNotNull { value(files.rows[table.convertRowIndexToModel(it)]) }.joinToString("\n")
        if (text.isEmpty()) return
        runCatching { Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null) }
            .onSuccess { status.text = "Copied ${text.lines().size} line(s)." }
    }

    private fun applyFilter() {
        val text = filter.text.trim().lowercase()
        val onlyPdf = pdfOnly.isSelected
        val showing = resultFilter.selectedItem as ShowFilter
        sorter.rowFilter = object : RowFilter<FileTableModel, Int>() {
            override fun include(entry: Entry<out FileTableModel, out Int>): Boolean {
                val file = files.rows[entry.identifier]
                return (!onlyPdf || file.isPdf) && (text.isEmpty() || text in file.name.lowercase()) && showing.accepts(files.resultAt(entry.identifier))
            }
        }
        updateCount()
    }

    private fun updateCount() {
        val tested = files.rows.count { results[files.key(it)]?.status?.finished == true }
        val what = if (source is DownloadedFilesSource) "downloaded files" else "files"
        count.text = "Showing ${table.rowCount} of ${files.rowCount} $what (${formatSize(files.totalBytes)}), $tested tested"
    }

    private fun updateControls() {
        val running = job != null
        stopListing.isEnabled = listing != null
        val selected = table.selectedRowCount
        testSelected.isEnabled = !running && source != null && selected > 0
        testSelected.text = if (selected > 1) "Test $selected selected" else "Test selected"
        run.isEnabled = !running && source != null
        stop.isEnabled = running && !stopRequested
        listOf(detector, maxPoints, alignment, minLineFit, parallel, checkText, runCount, order, untestedOnly,
            reloadTemplates, chooseTemplates)
            .forEach { it.isEnabled = !running }
        val file = shown
        shownName.text = file?.fileName ?: " "
        shownName.toolTipText = file?.let { "gs://${source?.bucket}/${it.name}" }
        val canDownload = !downloading && source != null && file != null
        download.isEnabled = canDownload
        downloadItem.isEnabled = canDownload
        val canCreate = !creatingTemplate && source != null && shownPdf?.pages?.isNotEmpty() == true
        createTemplate.isEnabled = canCreate
        createTemplateItem.isEnabled = canCreate
    }

    private fun updateSummary() {
        if (runKeys.isEmpty()) { summary.text = " "; return }
        val runResults = runKeys.mapNotNull { results[it] }
        val counts = runResults.groupingBy { it.status }.eachCount()
        val finished = runResults.count { it.status.finished }
        val seconds = ((if (runEnded > 0) runEnded else System.nanoTime()) - runStarted) / 1_000_000_000
        val state = when {
            job != null -> "Running ($seconds s):"
            runStopped -> "Stopped after $seconds s:"
            else -> "Finished in $seconds s:"
        }
        summary.text = "$state $finished of ${runKeys.size} file${plural(runKeys.size)} tested — " +
            "${counts[PdfStatus.ALL_PAGES] ?: 0} all pages matched, ${counts[PdfStatus.SOME_PAGES] ?: 0} some pages, " +
            "${counts[PdfStatus.NO_MATCH] ?: 0} no match, ${counts[PdfStatus.ERROR] ?: 0} errors"
        val perTemplate = runResults.flatMap { result -> result.pageResults.mapNotNull { it.match?.template?.label } }.groupingBy { it }.eachCount()
        summary.toolTipText = if (perTemplate.isEmpty()) null else "<html>Pages per best template in this run:<br>" +
            perTemplate.entries.sortedByDescending { it.value }.joinToString("<br>") { "${escape(it.key)}: ${it.value}" } + "</html>"
    }

    private fun bucketName(): String =
        (bucket.editor.item ?: bucket.selectedItem)?.toString().orEmpty().trim().removePrefix("gs://").trimEnd('/')

    /** Stops listing and tests; tests in progress finish in the background and their results are dropped. */
    fun close() {
        closed = true
        stopRequested = true
        previewDelay.stop()
        clock.stop()
        listing?.cancel(true)
        listing = null
        listingToken++
        previewToken++
        // A running test still uses the template features; the run frees them when it ends.
        if (job == null) closePrepared()
    }

    /** Columns of the file list; the model is an inner class, which cannot hold them. */
    private object FileColumns {
        const val NAME = 0
        const val SIZE = 1
        const val CREATED = 2
        const val PAGES = 3
        const val RESULT = 4
        const val BEST = 5
        const val TIME = 6
        val NAMES = listOf("Name", "Size", "Date added", "Pages", "Result", "Template per page", "Time")
    }

    private inner class FileTableModel : AbstractTableModel() {
        val rows = ArrayList<BucketFile>()
        private val index = HashMap<String, Int>()
        var bucket = ""
            private set
        var totalBytes = 0L
            private set

        fun add(page: List<BucketFile>) {
            if (page.isEmpty()) return
            val first = rows.size
            page.forEach { index[key(it)] = rows.size; rows += it }
            totalBytes += page.sumOf { it.size }
            fireTableRowsInserted(first, rows.size - 1)
        }

        fun clear(bucketName: String) {
            rows.clear()
            index.clear()
            totalBytes = 0
            bucket = bucketName
            fireTableDataChanged()
        }

        fun key(file: BucketFile) = resultKey(bucket, file)

        fun resultAt(row: Int): PdfTestResult? = results[key(rows[row])]

        fun updated(key: String) {
            index[key]?.let { fireTableRowsUpdated(it, it) }
        }

        override fun getRowCount() = rows.size
        override fun getColumnCount() = FileColumns.NAMES.size
        override fun getColumnName(column: Int) = FileColumns.NAMES[column]
        override fun getColumnClass(column: Int): Class<*> = when (column) {
            FileColumns.SIZE, FileColumns.TIME -> Long::class.javaObjectType
            FileColumns.CREATED -> Instant::class.java
            FileColumns.PAGES -> Int::class.javaObjectType
            FileColumns.RESULT -> PdfStatus::class.java
            else -> String::class.java
        }

        override fun getValueAt(row: Int, column: Int): Any? {
            val file = rows[row]
            val result = results[key(file)]
            return when (column) {
                FileColumns.NAME -> file.name
                FileColumns.SIZE -> file.size
                FileColumns.CREATED -> file.created
                FileColumns.PAGES -> result?.pages
                FileColumns.RESULT -> result?.status
                FileColumns.BEST -> result?.templatesPerPage.orEmpty()
                else -> result?.takeIf { it.status.finished }?.millis?.takeIf { it > 0 }
            }
        }
    }

    companion object {
        // Shared with GcpBucketView.
        private const val BUCKET = "bucket"
        private const val PREFIX = "prefix"
        private const val PDF_ONLY = "pdfOnly"
        private const val RUN_COUNT = "templateTestRunCount"
        private const val DOWNLOAD_FOLDER = "downloadFolder"
        private const val CHOSEN_TEMPLATES = "templateTestTemplates"
        private const val FOLDER = "templateFolder"
        private const val MAX_RUN = 100_000
        private const val PREVIEW_DELAY_MS = 150
        private const val RESULT_TAB = 1
        private const val COMPARE_TAB = 2
        private val IMAGE_SUFFIXES = ImageIO.getReaderFileSuffixes().map { it.lowercase() }.toSet()

        /**
         * Opens the view in its own window and lists the last used bucket. [onTemplateCreated] gets the page files
         * "Create template from…" added, and whether to open them in the editor.
         */
        fun open(owner: Component? = null, onTemplateCreated: ((pages: List<File>, openInEditor: Boolean) -> Unit)? = null): JFrame {
            val view = BucketTemplateTestView(onTemplateCreated = onTemplateCreated)
            return JFrame("Test bucket PDFs against all templates").apply {
                defaultCloseOperation = JFrame.DISPOSE_ON_CLOSE
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

private enum class ShowFilter(private val label: String, val accepts: (PdfTestResult?) -> Boolean) {
    ALL("All files", { true }),
    UNTESTED("Untested", { it?.status?.finished != true }),
    TESTED("Tested", { it?.status?.finished == true }),
    ALL_PAGES("✓ All pages matched", { it?.status == PdfStatus.ALL_PAGES }),
    SOME_PAGES("◐ Some pages matched", { it?.status == PdfStatus.SOME_PAGES }),
    NO_MATCH("✗ No match", { it?.status == PdfStatus.NO_MATCH }),
    ERROR("Errors", { it?.status == PdfStatus.ERROR });

    override fun toString() = label
}

private enum class RunOrder(private val label: String) {
    LIST("from the top"), RANDOM("at random");

    override fun toString() = label
}

/** Every page × template outcome of one file: by page, each page's matches best first, then the rest closest first. */
private class OutcomeTableModel : AbstractTableModel() {
    var rows: List<TemplateOutcome> = emptyList()
        private set

    fun reset(values: List<TemplateOutcome>) {
        rows = values
        fireTableDataChanged()
    }

    override fun getRowCount() = rows.size
    override fun getColumnCount() = COLUMNS.size
    override fun getColumnName(column: Int) = COLUMNS[column]
    override fun getColumnClass(column: Int): Class<*> = when (column) {
        0 -> Int::class.javaObjectType
        3 -> BatchStatus::class.java
        4, 5 -> Double::class.javaObjectType
        6 -> Long::class.javaObjectType
        else -> String::class.java
    }

    override fun getValueAt(row: Int, column: Int): Any? {
        val outcome = rows[row]
        return when (column) {
            0 -> outcome.page
            1 -> outcome.role.toString()
            2 -> outcome.template.label
            3 -> outcome.result.status
            4 -> outcome.result.agreement
            5 -> outcome.result.lineFit
            else -> outcome.result.millis
        }
    }

    companion object {
        const val TEXT = 4
        val COLUMNS = listOf("PDF page", "Tested as", "Template", "Result", "Agree", "Lines", "Time")
    }
}

private val MATCH_COLOR = Color(20, 130, 50)
private val PARTIAL_COLOR = Color(170, 120, 0)
private val NO_MATCH_COLOR = Color(195, 30, 30)
private val ERROR_COLOR = Color(150, 60, 170)

private fun statusColor(status: PdfStatus?): Color = when (status) {
    PdfStatus.ALL_PAGES -> MATCH_COLOR
    PdfStatus.SOME_PAGES -> PARTIAL_COLOR
    PdfStatus.NO_MATCH -> NO_MATCH_COLOR
    PdfStatus.ERROR -> ERROR_COLOR
    else -> Color.GRAY
}

private fun secondsRenderer() = object : DefaultTableCellRenderer() {
    init { horizontalAlignment = RIGHT }
    override fun setValue(value: Any?) { text = (value as? Long)?.takeIf { it > 0 }?.let { "%.1f s".format(it / 1000.0) }.orEmpty() }
}

/** Results are kept per object version: a re-uploaded file is untested. */
private fun resultKey(bucket: String, file: BucketFile) = "$bucket/${file.name}#${file.generation}"

private fun flowRow(vararg components: Component) =
    JPanel(FlowLayout(FlowLayout.LEFT, 6, 2)).apply { components.forEach { add(it) } }

private fun escape(text: String) = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
