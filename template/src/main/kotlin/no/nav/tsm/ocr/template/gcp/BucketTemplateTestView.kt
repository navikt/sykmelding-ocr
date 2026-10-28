package no.nav.tsm.ocr.template.gcp

import no.nav.tsm.ocr.template.testing.DEFAULT_PARALLEL
import no.nav.tsm.ocr.template.testing.GuideImagePanel
import no.nav.tsm.ocr.template.testing.GuidePane
import no.nav.tsm.ocr.template.testing.previewBoxes
import no.nav.tsm.ocr.template.view.WrapLayout
import no.nav.tsm.ocr.template.view.ZoomControls
import no.nav.tsm.ocr.template.view.splitPane
import no.nav.tsm.sykmelding.model.CheckboxState
import no.nav.tsm.sykmelding.ocr.CheckboxReading
import no.nav.tsm.sykmelding.ocr.FieldSegment
import no.nav.tsm.sykmelding.ocr.SykmeldingOcrMapping
import no.nav.tsm.sykmelding.ocr.skipsOcr
import no.nav.tsm.sykmelding.template.HomographyMethod
import no.nav.tsm.sykmelding.template.KeypointDetector
import no.nav.tsm.sykmelding.template.MatchFilter
import no.nav.tsm.sykmelding.template.MatchingMethods
import no.nav.tsm.sykmelding.template.TemplateMatcher
import org.example.no.nav.tsm.ocr.template.template.Felter
import org.example.no.nav.tsm.ocr.template.template.PdfTemplateImages
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
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.time.Instant
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.prefs.Preferences
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComboBox
import javax.swing.JDialog
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
import javax.swing.Scrollable
import javax.swing.Timer
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import javax.swing.table.AbstractTableModel
import javax.swing.table.DefaultTableCellRenderer
import javax.swing.table.TableRowSorter
import tools.jackson.module.kotlin.jacksonObjectMapper

class BucketTemplateTestView(
    private val library: TemplateLibrary = TemplateLibrary(),
    private val settings: Preferences? = Preferences.userNodeForPackage(GcpBucketView::class.java),
    private val onTemplateCreated: ((pages: List<File>, openInEditor: Boolean) -> Unit)? = null,
    private val testFolder: File? = findTestResources(),
) : JPanel(BorderLayout(6, 6)) {
    private val downloadCache = DownloadCache.shared

    // Listing, as in GcpBucketView (which shares the last used bucket and prefix).
    private val bucket = JComboBox((listOfNotNull(settings?.get(BUCKET, null)) + downloadCache.bucketNames()).distinct().toTypedArray()).apply {
        isEditable = true
        prototypeDisplayValue = "x".repeat(32)
        toolTipText = "Bucket name: type it; the last one used and those downloaded from are remembered locally. Uses your Application Default Credentials (gcloud auth application-default login)."
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
    private val showTestPdfs = JButton("Show test PDFs").apply {
        toolTipText = testFolder?.let { "<html>List the PDFs (and XMLs) in ${escape(it.path)} instead of the bucket.<br>" +
            "They are previewed and tested as usual; Prefix filters them by their path in the folder.</html>" }
            ?: "No src/test/resources folder found from ${File("").absolutePath}."
        isEnabled = testFolder != null
        addActionListener { listTestFiles() }
    }
    private val stopListing = JButton("Stop listing").apply { isEnabled = false; addActionListener { listing?.cancel(true) } }
    private val filter = JTextField(18).apply { toolTipText = "Show only names containing this text" }
    private val pdfOnly = JCheckBox("PDF only", settings?.getBoolean(PDF_ONLY, true) ?: true)
    private val xmlToo = JCheckBox("XML too", settings?.getBoolean(XML_TOO, true) ?: true).apply {
        toolTipText = "<html>With PDF only: show the XML files too. syfosmpapirmottak stores one next to some PDFs, with what " +
            "the scanning read from the form.<br>A PDF that has one is marked in the XML column; the test result shows the XML " +
            "below the OCR fields, and the XML tab shows it too.</html>"
    }
    private val withXmlOnly = JCheckBox("With XML only").apply {
        toolTipText = "<html>Show only the PDFs that have an XML (and, with XML too, those XMLs):<br>" +
            "the files whose OCR can be compared with the XML. Run tests them only.</html>"
    }
    private val resultFilter = JComboBox(ShowFilter.entries.toTypedArray()).apply { toolTipText = "Show files by their test result" }
    private val count = JLabel(" ")

    // Running tests: matching is lib's TemplateMatcher, as in production. The resolutions and the matching methods can
    // be changed, to see how matching and OCR fare with others than production's.
    private val parallel = JSpinner(SpinnerNumberModel(DEFAULT_PARALLEL, 1, Runtime.getRuntime().availableProcessors(), 1)).apply {
        toolTipText = "At most this many files are matched at the same time. The keypoint detector already uses several cores " +
            "per page, and each file being matched needs several hundred MB of memory."
    }
    private val renderDpi = DpiField("Render DPI", listOf(150, 200, 300, 400, 600), TestSettings.PRODUCTION.renderDpi,
        { TestSettings.RENDER_RANGE }, settings, RENDER_DPI, ::tell).apply {
        toolTipText = "<html>The PDF's pages are rendered at this resolution to be matched. Production: ${TestSettings.PRODUCTION.renderDpi} DPI, " +
            "the templates' own.<br>Choose one, or type a whole number from ${TestSettings.RENDER_RANGE.first} to " +
            "${TestSettings.RENDER_RANGE.last} and press Enter. A matched page is aligned to the template's size whatever this is.<br>" +
            "Higher takes more memory and time: a page at 600 DPI has 2.25 times the pixels of one at 400.</html>"
    }
    private val featureDpi = DpiField("Feature DPI", listOf(50, 75, 100, 150, 200), TestSettings.PRODUCTION.featureDpi,
        { TestSettings.featureRange(renderDpi.value) }, settings, FEATURE_DPI, ::tell,
        hint = { if (renderDpi.value < PdfTemplateImages.DPI) " (the render DPI)" else " (the templates' resolution)" }).apply {
        toolTipText = "<html>Keypoints are found on the rendered pages and on the templates scaled down to this resolution. " +
            "Production: ${TestSettings.PRODUCTION.featureDpi} DPI.<br>Choose one, or type a whole number from 10 up to the render DPI " +
            "(at most ${PdfTemplateImages.DPI}, the templates') and press Enter.<br>Lower is faster but finds fewer features; " +
            "with too few, nothing matches. Higher takes much more time and memory.<br>" +
            "The templates' features are detected again at the next test when this or the render DPI changes.</html>"
    }
    private val detector = ChoiceField(KeypointDetector.entries, MatchingMethods.PRODUCTION.detector, settings, DETECTOR,
        KeypointDetector::label, KeypointDetector::description,
        "The keypoint detector and descriptor, used on the pages and the templates alike. " +
            "The templates' features are detected again at the next test when it changes.")
    private val matchFilter = ChoiceField(MatchFilter.entries, MatchingMethods.PRODUCTION.filter, settings, MATCH_FILTER,
        MatchFilter::label, MatchFilter::description,
        "How the matches between a template page's descriptors and a page's are narrowed down to the good ones. " +
            "At least 8 must be left.")
    private val homography = ChoiceField(HomographyMethod.entries, MatchingMethods.PRODUCTION.homography, settings, HOMOGRAPHY,
        HomographyMethod::label, HomographyMethod::description,
        "How the homography that aligns a page to a template page is fitted to the matches. At least 8 of them, and 40 % " +
            "of them, must agree with it (inliers: within the tolerance next to it); then the form lines must line up.")
    private val tolerance = ToleranceField(settings, TOLERANCE, ::tell) { TemplateMatcher.reprojectionTolerance(featureDpi.value) }
    private val ocrDpi = DpiField("OCR DPI", listOf(150, 200, 250, 300, 400), TestSettings.PRODUCTION.ocrDpi,
        { TestSettings.OCR_RANGE }, settings, OCR_DPI, ::tell).apply {
        toolTipText = "<html>The matched page, aligned to the template at ${PdfTemplateImages.DPI} DPI, is resampled to this resolution " +
            "for Tesseract,<br>and the lines it reads are mapped back onto the template. Production: ${TestSettings.PRODUCTION.ocrDpi} DPI. " +
            "Checkboxes are always judged at ${PdfTemplateImages.DPI}.<br>Choose one, or type a whole number from " +
            "${TestSettings.OCR_RANGE.first} to ${TestSettings.OCR_RANGE.last} and press Enter. Below ${TestSettings.MIN_TESSERACT_DPI} DPI " +
            "Tesseract is told ${TestSettings.MIN_TESSERACT_DPI},<br>the lowest it accepts; the page is still read at the resolution chosen.</html>"
    }
    private val productionDpi = JButton("As production").apply {
        toolTipText = "Render at ${TestSettings.PRODUCTION.renderDpi} DPI, find ${MatchingMethods.PRODUCTION.detector.label} features at " +
            "${TestSettings.PRODUCTION.featureDpi} DPI, filter the matches with ${MatchingMethods.PRODUCTION.filter.label}, fit the " +
            "homography with ${MatchingMethods.PRODUCTION.homography.label} within the Auto tolerance " +
            "(${pixels(TestSettings.PRODUCTION.effectiveTolerance)}), and read the fields at ${TestSettings.PRODUCTION.ocrDpi} DPI, " +
            "as production does."
        addActionListener { productionSettings() }
    }
    private val testSelected = JButton("Test selected").apply {
        toolTipText = "<html>Match the selected files with lib's TemplateMatcher, the matcher production runs:<br>" +
            "a file matches a template when every page of the template is found among the file's pages<br>" +
            "(keypoints and matches as chosen below — production: SIFT, ratio test, MAGSAC++ homography — then the form lines must line up).<br>" +
            "The fields are read as production reads them: the layout analysis finds the text lines on the page,<br>" +
            "and each line is cut to its field and read on its own.</html>"
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
    private val fieldTable = OcrFieldsPanel()
    private val images = GuideImagePanel().apply {
        describeBox = { describeField(it.box) }
        describeRegion = { describeSegmentAt(it.box) }
    }
    private val imageZoom = ZoomControls(images, "one preview pixel per screen pixel; previews are half the size of the 400-DPI page")
    private val showSegments = JCheckBox("Show line segments", true).apply {
        toolTipText = "Draw the line segments OCR read in orange: each text line the layout found, cut to its field. Hover one to see what was read in it."
        isEnabled = false
        addActionListener { refreshOutcome() }
    }
    private val outcomeText = JTextArea(6, 40).apply {
        isEditable = false; lineWrap = true; wrapStyleWord = true; font = Font(Font.MONOSPACED, Font.PLAIN, 12)
    }
    private val ocrFields = JScrollPane(outcomeText)
    private val ocrFieldsBorder = ocrFields.border
    /** In the test result, below the OCR fields, when the shown PDF has an XML; see [setXmlBesideOcr]. */
    private val xmlBesideOcr = XmlPanel("XML — what the scanning read, to compare with the OCR", ::openXmlComparisonWindow)
    /** The OCR fields above the XML; [ocrFields] is moved into it by [setXmlBesideOcr]. */
    private val fieldsSplit = splitPane(JSplitPane.VERTICAL_SPLIT, ocrFields, xmlBesideOcr, location = 0.5, weight = 0.5)
    /** The OCR fields, alone or with [xmlBesideOcr] in [fieldsSplit]. */
    private val fields = JPanel(BorderLayout()).apply { add(ocrFields) }
    private var xmlShownBesideOcr = false
    private val tabs = JTabbedPane()
    private val xml = XmlPanel()
    private var comparisonXml: XmlContent? = null
    private var mappedXmlComparison: OcrXmlComparison? = null
    private var comparisonWindow: JDialog? = null
    private var finalOcrText = "Test the selected PDF to see the final OCR output."
    /** The comparison as text, for copying and printing, and the message shown when there is none. */
    private val comparisonText = JTextArea().apply {
        isEditable = false
        font = Font(Font.MONOSPACED, Font.PLAIN, 12)
    }
    /** The comparison as a table, or [comparisonText] when there is none. */
    private val comparisonView = JPanel(BorderLayout())
    private val compareXml = JButton("Compare with XML").apply {
        isEnabled = false
        toolTipText = "Compare the whole document's mapped production OCR result with the Iron Mountain XML."
        addActionListener { tabs.selectedIndex = XML_COMPARISON_TAB }
    }
    private val printComparison = JButton("Print comparison…").apply {
        isEnabled = false
        addActionListener {
            try {
                comparisonText.print()
            } catch (error: java.awt.print.PrinterException) {
                status.text = "Could not print the comparison: ${error.message}"
            }
        }
    }
    private val copyComparison = JButton("Copy comparison").apply {
        isEnabled = false
        addActionListener { Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(comparisonText.text), null) }
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

    /** The bucket currently listed; previews and new runs use this, not the (editable) combo box. */
    private var source: BucketSource? = null
    /** Where the XML of a PDF is looked for when the list may not have it (see [listHasAll]): the bucket itself, also for downloads. */
    private var lookup: BucketSource? = null
    /** The prefix of the last listing, once it has listed every file under it; null while listing, or when it stopped or failed. */
    private var listedPrefix: String? = null
    private var listing: SwingWorker<Int, List<BucketFile>>? = null
    private var listingToken = 0
    private var previewToken = 0
    private var xmlToken = 0
    @Volatile private var closed = false
    private var shown: BucketFile? = null
    private var shownPdf: PdfInfo? = null
    private var resultFile: BucketFile? = null
    private var shownResult: PdfTestResult? = null
    /** The template page whose aligned page and fields "Test result" shows. */
    private var shownOutcome: TemplateOutcome? = null
    private val downloadFolder = DownloadFolderControls(downloadCache) { status.text = it }
    private val previewDelay = Timer(PREVIEW_DELAY_MS) { previewSelected() }.apply { isRepeats = false }

    private var job: SwingWorker<List<String>, Unit>? = null
    @Volatile private var stopRequested = false
    /** The matcher from the last run, reused while the library is unchanged; see [matcherFor]. Only the run thread
     * changes it while a run is going; otherwise only the EDT. */
    @Volatile private var prepared: PreparedMatcher? = null
    private var runKeys: List<String> = emptyList()
    private var runStarted = 0L
    private var runEnded = 0L
    private var runStopped = false
    private val clock = Timer(1000) { updateSummary() }

    init {
        border = BorderFactory.createEmptyBorder(8, 8, 8, 8)
        preferredSize = Dimension(1800, 980)
        setupTable()
        setupOutcomeTable()

        add(JPanel(GridLayout(0, 1, 0, 2)).apply {
            add(flowRow(JLabel("Bucket:"), bucket, JLabel("Prefix:"), prefix, loadButton, showDownloads, showTestPdfs, stopListing, Box.createHorizontalStrut(18),
                downloadFolder))
            add(flowRow(JLabel("Filter:"), filter, pdfOnly, xmlToo, withXmlOnly, JLabel("Show:"), resultFilter, count))
            add(flowRow(testSelected, Box.createHorizontalStrut(18), JLabel("Test"), runCount, JLabel("of the shown files"), order,
                untestedOnly, run, stop, Box.createHorizontalStrut(18), JLabel("Parallel files:"), parallel))
            add(flowRow(JLabel("Matching: render the PDF at"), renderDpi, JLabel("DPI, find"), detector, JLabel("keypoints at"), featureDpi,
                JLabel("DPI, filter the matches with"), matchFilter, JLabel("and fit the homography with"), homography,
                JLabel("within"), tolerance, JLabel("px")))
            add(flowRow(JLabel("OCR: read the fields at"), ocrDpi, JLabel("DPI"), Box.createHorizontalStrut(18), productionDpi))
            add(flowRow(templatesLabel, chooseTemplates, reloadTemplates, Box.createHorizontalStrut(18), summary))
        }, BorderLayout.NORTH)

        // Three columns: the files; the shown file's details and test result; and its PDF or aligned page alone. Every
        // divider can be dragged both ways: see splitPane.
        val left = JScrollPane(table)
        val result = JPanel(BorderLayout(4, 4)).apply {
            add(JPanel(BorderLayout()).apply {
                add(resultTitle, BorderLayout.NORTH)
                add(JPanel(WrapLayout()).apply { add(compareXml) }, BorderLayout.CENTER)
            }, BorderLayout.NORTH)
            add(splitPane(JSplitPane.VERTICAL_SPLIT, JScrollPane(outcomeTable), fields, location = 0.22, weight = 0.0), BorderLayout.CENTER)
        }
        val middle = object : JPanel(BorderLayout(4, 4)), Scrollable {
            override fun getPreferredScrollableViewportSize() = Dimension(500, 700)
            override fun getScrollableTracksViewportWidth() = true
            override fun getScrollableTracksViewportHeight() = (parent?.height ?: 0) >= preferredSize.height
            override fun getScrollableUnitIncrement(visibleRect: Rectangle, orientation: Int, direction: Int) = 24
            override fun getScrollableBlockIncrement(visibleRect: Rectangle, orientation: Int, direction: Int) =
                (if (orientation == javax.swing.SwingConstants.VERTICAL) visibleRect.height else visibleRect.width).coerceAtLeast(24)
        }.apply {
            preferredSize = Dimension(500, 1100)
            add(JPanel(BorderLayout(6, 0)).apply {
                border = BorderFactory.createEmptyBorder(0, 4, 0, 0)
                add(shownName, BorderLayout.NORTH)
                add(JPanel(WrapLayout()).apply { add(download); add(createTemplate) }, BorderLayout.CENTER)
            }, BorderLayout.NORTH)
            val info = JScrollPane(details).apply { border = BorderFactory.createTitledBorder("Details") }
            add(splitPane(JSplitPane.VERTICAL_SPLIT, info, result, location = 0.26, weight = 0.0), BorderLayout.CENTER)
        }
        val page = JPanel(BorderLayout()).apply {
            add(JPanel(WrapLayout()).apply {
                add(showSegments); add(Box.createHorizontalStrut(12)); add(imageZoom)
            }, BorderLayout.NORTH)
            add(splitPane(JSplitPane.VERTICAL_SPLIT, JScrollPane(images), fieldTable, location = 0.65, weight = 0.65), BorderLayout.CENTER)
        }
        tabs.addTab("PDF", preview)
        tabs.addTab("Test result", page)
        tabs.addTab("XML", xml)
        tabs.addTab("OCR vs XML", JPanel(BorderLayout(4, 4)).apply {
            add(JPanel(FlowLayout(FlowLayout.LEFT)).apply { add(copyComparison); add(printComparison) }, BorderLayout.NORTH)
            add(comparisonView, BorderLayout.CENTER)
        })
        showComparison(null)
        tabs.setToolTipTextAt(XML_COMPARISON_TAB, "Field-by-field comparison of the mapped OCR result and Iron Mountain XML for the whole document.")
        tabs.setToolTipTextAt(RESULT_TAB, "The page of the file aligned to the template page chosen in the middle, " +
            "with its fields as production reads them, and the line segments read in them.")
        tabs.setToolTipTextAt(XML_TAB, "The XML stored with the PDF, with what the scanning read from the form, " +
            "also shown below the OCR fields.")
        val middleScroll = JScrollPane(middle).apply {
            horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
            verticalScrollBar.unitIncrement = 24
        }
        val right = splitPane(JSplitPane.HORIZONTAL_SPLIT, middleScroll, tabs, location = 0.46, weight = 0.4)
        add(splitPane(JSplitPane.HORIZONTAL_SPLIT, left, right, location = 0.36, weight = 0.35), BorderLayout.CENTER)
        add(status, BorderLayout.SOUTH)

        filter.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = applyFilter()
            override fun removeUpdate(e: DocumentEvent) = applyFilter()
            override fun changedUpdate(e: DocumentEvent) = applyFilter()
        })
        pdfOnly.addActionListener {
            settings?.putBoolean(PDF_ONLY, pdfOnly.isSelected)
            xmlToo.isEnabled = pdfOnly.isSelected
            applyFilter()
        }
        xmlToo.isEnabled = pdfOnly.isSelected
        xmlToo.addActionListener { settings?.putBoolean(XML_TOO, xmlToo.isSelected); applyFilter() }
        withXmlOnly.addActionListener { applyFilter() }
        resultFilter.addActionListener { applyFilter() }
        (bucket.editor.editorComponent as? JTextField)?.addActionListener { load() }
        featureDpi.onChange = { tolerance.describe() }
        renderDpi.onChange = { render ->
            if (featureDpi.value > render) {
                tell("Feature DPI lowered from ${featureDpi.value} to $render: no more than the PDF is rendered at.")
                featureDpi.select(render)
            }
        }

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
        columnModel.getColumn(FileColumns.TEMPLATE).cellRenderer = fullTextRenderer
        columnModel.getColumn(FileColumns.XML).cellRenderer = object : DefaultTableCellRenderer() {
            init { horizontalAlignment = CENTER }
            override fun getTableCellRendererComponent(table: JTable, value: Any?, selected: Boolean, focus: Boolean, row: Int, column: Int): Component {
                val model = table.convertRowIndexToModel(row)
                return super.getTableCellRendererComponent(table, value, selected, focus, row, column).also {
                    toolTipText = files.xmlDescription(model)
                    if (!selected) foreground = if (files.rows[model].isPdf) MATCH_COLOR else Color.GRAY
                }
            }
        }
        columnModel.getColumn(FileColumns.RESULT).cellRenderer = object : DefaultTableCellRenderer() {
            override fun getTableCellRendererComponent(table: JTable, value: Any?, selected: Boolean, focus: Boolean, row: Int, column: Int): Component {
                val result = files.resultAt(table.convertRowIndexToModel(row))
                return super.getTableCellRendererComponent(table, result?.statusText.orEmpty(), selected, focus, row, column).also {
                    if (!selected) foreground = statusColor(result?.status)
                }
            }
        }
        listOf(300 to 120, 70 to 60, 140 to 130, 45 to 40, 40 to 35, 110 to 90, 260 to 100, 60 to 50).forEachIndexed { index, (preferred, minimum) ->
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
            add(JMenuItem("Copy path").apply { addActionListener { copy { source?.location(it.name) } } })
            add(JMenuItem("Copy results (tab-separated)").apply {
                addActionListener {
                    copy { file ->
                        results[files.key(file)]?.takeIf { it.status.finished }?.let { result ->
                            listOf(file.name, result.pages?.toString().orEmpty(), result.status.name, result.templateText).joinToString("\t")
                        }
                    }
                }
            })
        }
    }

    private fun setupOutcomeTable() = with(outcomeTable) {
        selectionModel.selectionMode = ListSelectionModel.SINGLE_SELECTION
        fillsViewportHeight = true
        listOf(200, 200).forEachIndexed { index, width -> columnModel.getColumn(index).preferredWidth = width }
        selectionModel.addListSelectionListener {
            if (!it.valueIsAdjusting) showOutcome(selectedOutcome())
        }
    }

    /** Lists the bucket from scratch, newest first. Test results are kept, so reloading shows them again. */
    fun load() = list(ListMode.BUCKET)

    /** Lists the files downloaded from the bucket instead (see [DownloadedFilesSource]); test results are shared with [load]'s. */
    private fun listDownloads() = list(ListMode.DOWNLOADS)
    private fun listTestFiles() = list(ListMode.TEST_FILES)

    private fun list(mode: ListMode) {
        if (closed) return
        val folder = testFolder
        val name = bucketName()
        if (mode == ListMode.TEST_FILES && folder == null) { status.text = "No test resources folder found."; return }
        if (mode != ListMode.TEST_FILES && name.isEmpty()) { status.text = "Enter a bucket name."; return }
        listing?.cancel(true)
        val prefixText = prefix.text.trim()
        if (mode != ListMode.TEST_FILES) {
            settings?.put(BUCKET, name)
            settings?.put(PREFIX, prefixText)
        }
        val current = when (mode) {
            ListMode.BUCKET -> GcsBucketSource(name)
            ListMode.DOWNLOADS -> DownloadedFilesSource(GcsBucketSource(name), downloadCache)
            ListMode.TEST_FILES -> LocalFolderSource(folder!!, include = { it.isPdf || it.isXml })
        }
        source = current
        lookup = (current as? DownloadedFilesSource)?.online ?: current
        listedPrefix = null
        val token = ++listingToken
        previewToken++
        files.clear(current.bucket)
        shown = null
        shownPdf = null
        details.text = ""
        preview.showMessage("Select a file to preview it.")
        showResult()
        resetXml()
        val location = if (folder != null && mode == ListMode.TEST_FILES) folder.resolve(prefixText).path else "gs://$name/$prefixText"
        val target = when (mode) {
            ListMode.BUCKET -> location
            ListMode.DOWNLOADS -> "the files downloaded from $location"
            ListMode.TEST_FILES -> "the test files in $location"
        }
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
                val paired = files.add(chunks.flatten())
                if (paired.isNotEmpty()) {
                    // Rows listed earlier that now have their PDF or XML.
                    if (withXmlOnly.isSelected) applyFilter()
                    if (shown?.documentPrefix in paired) { showDetails(); xmlChanged() }
                }
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
                        // Downloads are some of the bucket's files only.
                        if (mode != ListMode.DOWNLOADS) listedPrefix = prefixText
                        when {
                            mode == ListMode.BUCKET ->
                                "Listed $found files from $location" + if (found == 0) " — the bucket or prefix is empty." else "."
                            found == 0 -> "No ${if (mode == ListMode.DOWNLOADS) "files downloaded from" else "test files in"} $location."
                            mode == ListMode.DOWNLOADS ->
                                "Listed $found file${plural(found)} downloaded from $location. Press Load to list the bucket again."
                            else -> "Listed $found test file${plural(found)} in $location. Press Load to list the bucket again."
                        }
                    } catch (error: Exception) {
                        val cause = if (error is ExecutionException) error.cause ?: error else error
                        "Could not list $target: ${describeGcpError(cause).replace("\n", " — ")}" +
                            if (mode == ListMode.BUCKET) " — Show downloads lists the files already downloaded from it." else ""
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
        showDetails()
        showResult()
        updateControls()
        xmlChanged()
        val token = ++previewToken
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
                runCatching { get() }.onSuccess { (bytes, content) ->
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

    private fun testSelection() {
        val selected = table.selectedRows.map { files.rows[table.convertRowIndexToModel(it)] }
        if (selected.isEmpty()) { status.text = "Select one or more files to test."; return }
        if (startRun(selected) && selected.size == 1) tabs.selectedIndex = RESULT_TAB
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
        val testable = shownFiles.filter { it.isPdf }
        val candidates = testable.filter { !untestedOnly.isSelected || results[files.key(it)]?.status?.finished != true }
        val chosen = (if (order.selectedItem == RunOrder.RANDOM) candidates.shuffled() else candidates).take(wanted)
        if (chosen.isEmpty()) {
            status.text = when {
                shownFiles.isEmpty() -> "No files are shown. Press Load, or change the filter."
                testable.isEmpty() -> "None of the shown files is a PDF."
                else -> "All shown files are tested already. Untick \"Untested only\" to test them again."
            }
            return
        }
        startRun(chosen)
    }

    /**
     * Matches [chosen] with lib's TemplateMatcher, up to [parallel] files at a time, at the resolutions chosen. The
     * matcher (which detects the features of every template page when created) is reused by later runs while the
     * library and the matching resolutions are unchanged.
     */
    private fun startRun(chosen: List<BucketFile>): Boolean {
        if (job != null || closed || chosen.isEmpty()) return false
        val current = source ?: return false
        val threads = try {
            parallel.commitEdit()
            (parallel.value as Number).toInt()
        } catch (error: Exception) {
            status.text = "Invalid number of parallel files: ${error.message}"
            return false
        }
        val testSettings = testSettings() ?: return false
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
                val fileWorkers = Executors.newFixedThreadPool(threads) { task -> Thread(task, "bucket-template-file").apply { isDaemon = true } }
                try {
                    val (matcher, problems) = matcherFor(::say, testSettings)
                    for ((index, file) in chosen.withIndex()) {
                        val key = keys[index]
                        fileWorkers.submit {
                            if (stopRequested || closed) return@submit
                            say("Testing file ${index + 1} of ${chosen.size}: ${file.fileName} …")
                            post(key, PdfTestResult(PdfStatus.RUNNING, "Downloading …"))
                            // The file is kept in use while it is tested, so "Clear downloads" leaves it alone.
                            val result = try {
                                downloadCache.using(current, file) { cached ->
                                    if (!file.isPdf) PdfTestResult(PdfStatus.ERROR, "Not a PDF: the matcher takes PDFs only.") else {
                                        post(key, PdfTestResult(PdfStatus.RUNNING, "Matching …"))
                                        testAgainstTemplates(cached, matcher, testSettings.ocrDpi) { post(key, PdfTestResult(PdfStatus.RUNNING, "Reading fields (OCR) …")) }
                                    }
                                }
                            } catch (error: Exception) {
                                PdfTestResult(PdfStatus.ERROR, "Could not download the file: " + describeGcpError(error).replace("\n", " — "))
                            }
                            post(key, result)
                        }
                    }
                    fileWorkers.shutdown()
                    fileWorkers.awaitTermination(1, TimeUnit.DAYS)
                    return problems
                } finally {
                    fileWorkers.shutdownNow()
                    fileWorkers.awaitTermination(1, TimeUnit.DAYS)
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

    /**
     * On the run thread: lib's [TemplateMatcher] for the chosen templates as they are now, with [testSettings]'
     * resolutions and matching methods, kept from an earlier run when none of them changed, with the library's
     * problems (pages it cannot test). Creating the matcher detects the features of every template page; it throws
     * when a template cannot be read, which fails the run.
     */
    private fun matcherFor(say: (String) -> Unit, testSettings: TestSettings): Pair<PreparedMatcher, List<String>> {
        val pages = loadTemplates()
        SwingUtilities.invokeLater { if (!closed) showTemplates(pages) }
        prepared?.let { if (it.fingerprint == pages.fingerprint && testSettings.matchesAs(it.matcher)) return it to pages.problems }
        closePrepared()
        say("Preparing the matcher: detecting the ${testSettings.methods.detector.label} features of ${pages.pages.size} " +
            "template page${plural(pages.pages.size)} at ${testSettings.featureDpi} DPI once …")
        val chosen = chosenTemplates
        val groups = templateGroups().filter { chosen == null || it.file.parentFile.name in chosen }
        val matcher = TemplateMatcher(library, templates = groups, renderDpi = testSettings.renderDpi,
            featureDpi = testSettings.featureDpi, methods = testSettings.methods, tolerance = testSettings.tolerance)
        return PreparedMatcher(pages.fingerprint, matcher).also { prepared = it } to pages.problems
    }

    /** The resolutions typed or chosen, and the matching methods chosen; null, after saying why, when a resolution is not allowed. */
    private fun testSettings(): TestSettings? {
        val render = renderDpi.commit()?.value ?: return null
        val features = featureDpi.commit()?.value ?: return null
        val ocr = ocrDpi.commit()?.value ?: return null
        if (tolerance.commit() == null) return null
        return TestSettings(render, features, ocr, MatchingMethods(detector.value, matchFilter.value, homography.value), tolerance.value)
    }

    private fun productionSettings() {
        if (job != null) return
        val production = TestSettings.PRODUCTION
        // The render DPI first: the feature DPI may be no higher.
        renderDpi.select(production.renderDpi)
        featureDpi.select(production.featureDpi)
        ocrDpi.select(production.ocrDpi)
        detector.select(production.methods.detector)
        matchFilter.select(production.methods.filter)
        homography.select(production.methods.homography)
        tolerance.select(production.tolerance)
        tell("Production's settings: the PDF rendered at ${production.renderDpi} DPI, ${production.methods.detector.label} keypoints " +
            "found at ${production.featureDpi} DPI, the matches filtered with ${production.methods.filter.label}, the homography " +
            "fitted with ${production.methods.homography.label} within ${pixels(production.effectiveTolerance)} (auto), and the fields " +
            "read at ${production.ocrDpi} DPI.")
    }

    private fun tell(text: String) {
        status.text = text
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
            line("Location", source?.location(file.name))
            line("Size", "%,d bytes (%s)".format(file.size, formatSize(file.size)))
            line("Date added", formatTime(file.created))
            line("Content type", file.contentType)
            file.reference?.let { line("JournalpostId", it.journalpostId); line("DokumentInfoId", it.dokumentInfoId) }
            if (file.isPdf) line("XML", files.xmlOf(file)?.fileName)
            else if (file.isXml) line("PDF", files.pdfOf(file)?.fileName)
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

    /** The test result in the middle: the template pages found in the shown file, the first selected. */
    private fun showResult() {
        val file = shown
        val result = file?.let { results[files.key(it)] }
        if (file === resultFile && result === shownResult) return
        resultFile = file
        shownResult = result
        resultTitle.text = when {
            file == null -> " "
            result == null -> "${file.fileName} — not tested"
            result.outcomes.isNotEmpty() -> "${file.fileName} — ${result.statusText}: ${result.templateText}"
            else -> "${file.fileName} — ${result.statusText}"
        }
        outcomes.reset(result?.outcomes.orEmpty())
        if (outcomes.rowCount > 0) outcomeTable.setRowSelectionInterval(0, 0) else showOutcome(null)
        refreshXmlComparison()
    }

    private fun refreshXmlComparison() {
        mappedXmlComparison = null
        val result = shownResult
        val content = comparisonXml
        val document = content?.document
        val mapped = try {
            if (result?.status == PdfStatus.MATCHED && result.reads != null) {
                SykmeldingOcrMapping.map(result.outcomes.first().template.group, result.reads)
            } else null
        } catch (error: Exception) {
            finalOcrText = "Could not map the final OCR output: ${error.message ?: error}"
            xmlBesideOcr.showFinalOcr(finalOcrText)
            comparisonText.text = finalOcrText
            comparisonText.caretPosition = 0
            showComparison(null)
            compareXml.isEnabled = false
            printComparison.isEnabled = false
            copyComparison.isEnabled = false
            xmlBesideOcr.setComparisonEnabled(content != null)
            return
        }
        finalOcrText = when {
            shown?.isPdf != true -> "Select a PDF to see its final OCR output."
            result?.status != PdfStatus.MATCHED -> "Test the selected PDF first. A matched document is required for final OCR output."
            result.reads == null -> result.ocrError ?: "The document's OCR fields have not been read."
            mapped == null -> "No production mapping is available for template ${result.outcomes.firstOrNull()?.template?.group}."
            else -> jacksonObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(mapped)
        }
        xmlBesideOcr.showFinalOcr(finalOcrText)
        var ready = false
        comparisonText.text = when {
            shown?.isPdf != true -> "Select a PDF to compare its mapped OCR result with the Iron Mountain XML."
            result?.status != PdfStatus.MATCHED -> "Test the selected PDF first. A matched document is required for comparison."
            result.reads == null -> result.ocrError ?: "The document's OCR fields have not been read."
            content == null -> "No XML is loaded for this PDF. See the XML tab for availability or loading errors."
            document == null -> content.fields
            else -> try {
                if (mapped == null) finalOcrText
                else {
                    val comparison = compareMappedOcrWithXml(mapped, document)
                    val report = "${shown?.fileName}\n\n" + comparison.describe()
                    mappedXmlComparison = comparison
                    ready = true
                    report
                }
            } catch (error: Exception) {
                "Could not compare the mapped OCR result with the XML: ${error.message ?: error}"
            }
        }
        comparisonText.caretPosition = 0
        showComparison(mappedXmlComparison)
        compareXml.isEnabled = ready
        printComparison.isEnabled = ready
        copyComparison.isEnabled = ready
        xmlBesideOcr.setComparisonEnabled(shown?.isPdf == true && content != null)
    }

    private fun showComparison(comparison: OcrXmlComparison?) {
        comparisonView.removeAll()
        comparisonView.add(if (comparison != null) OcrXmlComparisonPanel(comparison) else JScrollPane(comparisonText), BorderLayout.CENTER)
        comparisonView.revalidate()
        comparisonView.repaint()
    }

    private fun openXmlComparisonWindow() {
        val comparison = mappedXmlComparison
        comparisonWindow?.dispose()
        comparisonWindow = JDialog(
            SwingUtilities.getWindowAncestor(this),
            "${shown?.fileName} - Mapped OCR vs Iron Mountain XML",
            java.awt.Dialog.ModalityType.MODELESS,
        ).apply {
            defaultCloseOperation = JDialog.DISPOSE_ON_CLOSE
            contentPane = if (comparison != null) OcrXmlComparisonPanel(comparison) else JPanel(BorderLayout()).apply {
                fun pane(title: String, text: String) = JScrollPane(JTextArea(text).apply {
                    isEditable = false
                    font = Font(Font.MONOSPACED, Font.PLAIN, 12)
                    caretPosition = 0
                }).apply { border = BorderFactory.createTitledBorder(title) }
                add(splitPane(JSplitPane.HORIZONTAL_SPLIT,
                    pane("Final OCR output - unavailable", finalOcrText),
                    pane("Iron Mountain XML", comparisonXml?.fields ?: "No XML is loaded."),
                    location = 0.5, weight = 0.5))
            }
            val screen = graphicsConfiguration.bounds
            val insets = Toolkit.getDefaultToolkit().getScreenInsets(graphicsConfiguration)
            setSize(minOf(1200, screen.width - insets.left - insets.right),
                minOf(750, screen.height - insets.top - insets.bottom))
            setLocationRelativeTo(this@BucketTemplateTestView)
            isVisible = true
        }
    }

    private fun selectedOutcome(): TemplateOutcome? = outcomeTable.selectedRow.takeIf { it >= 0 }?.let { outcomes.rows[it] }

    private fun showOutcome(outcome: TemplateOutcome?) {
        shownOutcome = outcome
        fieldTable.show(shownFields(), shownOcr(), shownSegments(), shownCheckboxes(), shownResult)
        if (outcome == null) {
            images.panes = emptyList()
            showSegments.isEnabled = false
            val result = shownResult
            outcomeText.text = when {
                shown == null -> "Select a file in the list."
                result == null -> "Not tested yet. Click \"Test selected\" to match the file with the production matcher."
                else -> result.summary()
            }
            outcomeText.caretPosition = 0
            return
        }
        val image = outcome.previewImage()
        val template = outcome.template
        val fields = previewBoxes(template.template.felter.map { Rectangle(it.x, it.y, it.bredde, it.hoyde) }, outcome.previewScale)
        val segments = shownSegments().orEmpty()
        showSegments.isEnabled = segments.isNotEmpty()
        val regions = if (!showSegments.isSelected) emptyList() else previewBoxes(segments.map { it.box }, outcome.previewScale)
        val checkboxes = checkboxStates()
        images.panes = if (image == null) emptyList() else listOf(GuidePane(
            "${template.label}: green = fields, magenta = OCR read nothing" +
                (if (checkboxes.isEmpty()) "" else "; checkboxes: filled green = checked, blue = unchecked, magenta = uncertain") +
                (if (regions.isEmpty()) "." else "; orange = line segments read."),
            image, fields, unreadFields(), regions, checkboxes))
        outcomeText.text = buildString {
            appendLine("✓ ${template.label} — page ${template.position} of ${template.templateSize}")
            appendLine("The matcher found this template page in the file and aligned the PDF page to it (gray, at the template image's size). " +
                "That aligned page is what the fields are read from, so the green boxes show where OCR looks.")
            if (image == null) appendLine("No picture: the template image could not be read.")
            shownResult?.takeIf { it.millis > 0 }?.let { appendLine("Time for the whole file: %.1f s".format(it.millis / 1000.0)) }
            val read = shownPage(shownResult?.reads)
            appendLine()
            if (read == null) appendLine(shownResult?.ocrError ?: "Fields: not read.")
            else {
                appendLine("Fields (${read.values.size}) — [how sure Tesseract is of each line]:")
                append(fieldLines(read))
                append(leftOutSegments(segments))
            }
        }
        outcomeText.caretPosition = 0
    }

    /** Shows the shown template page again, e.g. with or without its line segments. */
    private fun refreshOutcome() = showOutcome(shownOutcome)

    /** The fields of the template page "Test result" shows, in the order their boxes are drawn. */
    private fun shownFields(): List<Felter> = shownOutcome?.template?.template?.felter.orEmpty()

    /** What lib's OCR read in the fields of the shown template page; null when not read. */
    private fun shownOcr(): OcrFields? = shownPage(shownResult?.ocr)

    /** The shown template page's entry of [pages], one per page of the result. */
    private fun <T> shownPage(pages: List<T>?): T? = shownOutcome?.let { pages?.getOrNull(outcomes.rows.indexOf(it)) }

    /** The line segments read on the shown template page, in the order they are drawn. */
    private fun shownSegments(): List<FieldSegment>? = shownPage(shownResult?.segments)

    /** The state of each checkbox on the shown template page; null when the page was not read. */
    private fun shownCheckboxes(): Map<Felter, CheckboxReading>? = shownPage(shownResult?.checkboxes)

    /** The state of each shown checkbox that was read, by its index among the shown fields. */
    private fun checkboxStates(): Map<Int, CheckboxState> {
        val checkboxes = shownCheckboxes() ?: return emptyMap()
        return shownFields().withIndex().mapNotNull { (index, field) -> checkboxes[field]?.let { index to it.state } }.toMap()
    }

    /**
     * The indices of the shown fields that need a look, drawn magenta: text fields the OCR read nothing in, and
     * checkboxes ([skipsOcr]) neither checked nor unchecked. None when the fields were not read.
     */
    private fun unreadFields(): Set<Int> {
        val ocr = shownOcr() ?: return emptySet()
        val checkboxes = shownCheckboxes().orEmpty()
        return shownFields().indices.filterTo(LinkedHashSet()) {
            val field = shownFields()[it]
            if (field.type.skipsOcr) checkboxes[field]?.state.let { state -> state == null || state == CheckboxState.UNCERTAIN }
            else ocr[field]?.lines.isNullOrEmpty()
        }
    }

    /** The tooltip of field [index] on the page: what the OCR read in it, each line with its confidence. */
    private fun describeField(index: Int): String? {
        val field = shownFields().getOrNull(index) ?: return null
        val ocr = shownOcr()
        return buildString {
            append("<b>${escape(field.navn)}</b> (${field.type})<br>")
            when {
                field.type.skipsOcr -> append(escape(checkboxText(shownCheckboxes()?.get(field)).replaceFirstChar(Char::uppercase)) + "<br>" + escape(CHECKBOX_READ))
                ocr == null -> append("Not read")
                else -> append("Read: " + (ocr[field]?.lines?.ifEmpty { null }?.let { "“${escape(withConfidence(it))}”" } ?: "nothing"))
            }
        }
    }

    /** The tooltip of line segment [index] on the page. */
    private fun describeSegmentAt(index: Int): String? = shownSegments()?.getOrNull(index)?.let(::describeSegment)

    /** A line segment's tooltip: its field, and what was read in it and whether each line was used. */
    private fun describeSegment(segment: FieldSegment): String = buildString {
        append("Line segment in <b>${escape(segment.field.navn)}</b> (${segment.field.type})<br>")
        if (segment.lines.isEmpty()) append("Read nothing")
        append(segment.lines.joinToString("<br>") { line ->
            "Read: “${escape(withConfidence(listOf(line.region)))}” — ${escape(line.leftOut?.let { "left out: ${it.description}" } ?: "used")}"
        })
    }

    private fun fetch(source: BucketSource, file: BucketFile): ByteArray = downloadCache.using(source, file) { it.readBytes() }

    /** Forgets the XML shown; nothing is shown. */
    private fun resetXml() {
        xmlToken++
        showXmlMessage("Select a PDF to see its XML.", " ", besideOcr = false)
        tabs.setTitleAt(XML_TAB, "XML")
    }

    /** After the shown file, or what the list knows of its XML, changed: shows its XML again, below the OCR fields and in the XML tab. */
    private fun xmlChanged() {
        val file = shown
        tabs.setTitleAt(XML_TAB, if (file != null && (file.isXml || files.xmlOf(file) != null)) "XML ✓" else "XML")
        showXml()
    }

    /** Whether the list has every file whose name starts with [prefix], so a PDF whose XML it lacks has none. */
    private fun listHasAll(prefix: String): Boolean = listedPrefix?.let(prefix::startsWith) == true

    /**
     * The XML of the shown PDF, or the shown XML itself, read off the EDT, in the XML tab and below the OCR fields.
     * An XML the list lacks, while it may be incomplete, is looked for in the bucket.
     */
    private fun showXml() {
        val file = shown
        val current = source
        if (file == null || current == null) { resetXml(); return }
        val token = ++xmlToken
        val listed = if (file.isXml && !file.isPdf) file else files.xmlOf(file)
        val prefix = file.documentPrefix
        val where = lookup?.takeIf { listed == null && file.isPdf && prefix != null && !listHasAll(prefix) }
        when {
            listed != null -> showXmlMessage("Reading ${listed.fileName} …", listed.fileName, besideOcr = true)
            // Next to the OCR only once found: most PDFs have none.
            where != null -> showXmlMessage("Looking for the XML of ${file.fileName} …", file.fileName, besideOcr = false)
            else -> { showXmlMessage(noXml(file, current), file.fileName, besideOcr = false); return }
        }
        object : SwingWorker<Pair<BucketFile, XmlContent>?, Unit>() {
            override fun doInBackground(): Pair<BucketFile, XmlContent>? {
                val found = listed ?: xmlFor(file, where!!.pages(prefix!!).flatten().asIterable()) ?: return null
                return found to XmlContent.of(fetch(current, found))
            }

            override fun done() {
                if (closed || token != xmlToken) return
                runCatching { get() }.onSuccess { result ->
                    if (result == null) showXmlMessage(noXml(file, current), file.fileName, besideOcr = false) else {
                        val (found, content) = result
                        xml.show(found.fileName, current.location(found.name), content)
                        xmlBesideOcr.show(found.fileName, current.location(found.name), content)
                        comparisonXml = content
                        refreshXmlComparison()
                        setXmlBesideOcr(true)
                        tabs.setTitleAt(XML_TAB, "XML ✓")
                    }
                }.onFailure { error ->
                    val cause = if (error is ExecutionException) error.cause ?: error else error
                    showXmlMessage("Could not ${if (listed == null) "look for" else "read"} the XML of ${file.fileName}:\n" +
                        describeGcpError(cause), file.fileName, besideOcr = true)
                }
                downloadFolder.refresh()
            }
        }.execute()
    }

    /** [text] in the XML tab, and below the OCR fields when [besideOcr]; otherwise the OCR fields are alone. */
    private fun showXmlMessage(text: String, heading: String, besideOcr: Boolean) {
        comparisonXml = null
        refreshXmlComparison()
        xml.showMessage(text, heading)
        xmlBesideOcr.showMessage(text, heading)
        setXmlBesideOcr(besideOcr)
    }

    /** The test result with the XML below the OCR fields, or the OCR fields alone, as they were before XMLs were shown. */
    private fun setXmlBesideOcr(visible: Boolean) {
        if (visible == xmlShownBesideOcr) return
        xmlShownBesideOcr = visible
        fields.removeAll()
        if (visible) {
            ocrFields.border = BorderFactory.createTitledBorder("OCR — what lib's OCR read")
            // As tall as the OCR fields with their title, so the two get half each.
            xmlBesideOcr.preferredSize = ocrFields.preferredSize
            fieldsSplit.topComponent = ocrFields
            fields.add(fieldsSplit)
        } else {
            ocrFields.border = ocrFieldsBorder
            fields.add(ocrFields)
        }
        fields.revalidate()
        fields.repaint()
    }

    /** Why [file], shown from [current], has no XML. */
    private fun noXml(file: BucketFile, current: BucketSource): String {
        val reference = file.reference
        return when {
            !file.isPdf -> "Only PDFs have an XML."
            reference == null -> "${file.fileName} is not named <journalpostId>_<dokumentInfoId>_<file UUID>.pdf, " +
                "so its XML cannot be found."
            else -> "No XML for journalpost ${reference.journalpostId}, dokumentInfo ${reference.dokumentInfoId}:\n" +
                "nothing named ${file.documentPrefix}….xml in ${current.location("").removeSuffix("/")}."
        }
    }

    /** Shows the previewed file in the download folder. */
    private fun revealShown() {
        val file = shown ?: run { status.text = "Select a file first."; return }
        val cached = source?.let { downloadCache.cachedFile(it.bucket, file) }
        if (cached == null) status.text = "${file.fileName} is not downloaded yet." else downloadFolder.reveal(cached)
    }

    /** Saves the previewed file, exactly as stored in the bucket, where [askDownloadTarget] says. */
    private fun downloadShown() {
        val file = shown ?: return
        val current = source ?: return
        if (downloading) return
        val target = askDownloadTarget(file)?.absoluteFile ?: return
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
        // Which PDF page is which template page is not known; a matched file is assumed to have the template's pages in order.
        val tested = results[files.key(file)]?.takeIf { it.status == PdfStatus.MATCHED || it.status == PdfStatus.NO_MATCH }
        val matchedPages = tested?.outcomes?.associate { it.template.position to "✓ ${it.template.label}" }.orEmpty()
        val input = TemplateDialogInput(
            fileName = file.fileName,
            pdf = bytes,
            pageCount = pageCount,
            pageNotes = if (tested == null) emptyMap() else (1..pageCount).associateWith { matchedPages[it] ?: "✗ no template" },
            unmatchedPages = tested?.let { (1..pageCount).filter { it !in matchedPages }.toSet() },
            groups = templateGroups(),
            canOpenEditor = onTemplateCreated != null,
        )
        val request = CreateTemplateDialog.ask(this, input, library)
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

    private fun copy(value: (BucketFile) -> String?) {
        val text = table.selectedRows.toList().mapNotNull { value(files.rows[table.convertRowIndexToModel(it)]) }.joinToString("\n")
        if (text.isEmpty()) return
        runCatching { Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null) }
            .onSuccess { status.text = "Copied ${text.lines().size} line(s)." }
    }

    private fun applyFilter() {
        val text = filter.text.trim().lowercase()
        val onlyPdf = pdfOnly.isSelected
        val alsoXml = xmlToo.isSelected
        val paired = withXmlOnly.isSelected
        val showing = resultFilter.selectedItem as ShowFilter
        sorter.rowFilter = object : RowFilter<FileTableModel, Int>() {
            override fun include(entry: Entry<out FileTableModel, out Int>): Boolean {
                val row = entry.identifier
                val file = files.rows[row]
                return (!onlyPdf || file.isPdf || (alsoXml && file.isXml)) && (!paired || files.paired(row)) &&
                    (text.isEmpty() || text in file.name.lowercase()) && showing.accepts(files.resultAt(row))
            }
        }
        updateCount()
    }

    private fun updateCount() {
        val tested = files.rows.count { results[files.key(it)]?.status?.finished == true }
        val what = when (source) {
            is DownloadedFilesSource -> "downloaded files"
            is LocalFolderSource -> "test files"
            else -> "files"
        }
        count.text = "Showing ${table.rowCount} of ${files.rowCount} $what (${formatSize(files.totalBytes)}), $tested tested, " +
            "${files.pdfsWithXml} PDF${plural(files.pdfsWithXml)} with XML"
    }

    private fun updateControls() {
        val running = job != null
        stopListing.isEnabled = listing != null
        val selected = table.selectedRowCount
        testSelected.isEnabled = !running && source != null && selected > 0
        testSelected.text = if (selected > 1) "Test $selected selected" else "Test selected"
        run.isEnabled = !running && source != null
        stop.isEnabled = running && !stopRequested
        listOf(parallel, runCount, order, untestedOnly, reloadTemplates, chooseTemplates, renderDpi, featureDpi, ocrDpi, detector,
            matchFilter, homography, tolerance, productionDpi)
            .forEach { it.isEnabled = !running }
        val file = shown
        shownName.text = file?.fileName ?: " "
        shownName.toolTipText = file?.let { source?.location(it.name) }
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
            "${counts[PdfStatus.MATCHED] ?: 0} matched, ${counts[PdfStatus.NO_MATCH] ?: 0} no match, ${counts[PdfStatus.ERROR] ?: 0} errors"
        val perTemplate = runResults.mapNotNull { result -> result.outcomes.firstOrNull()?.template?.group }.groupingBy { it }.eachCount()
        summary.toolTipText = if (perTemplate.isEmpty()) null else "<html>Files per template in this run:<br>" +
            perTemplate.entries.sortedByDescending { it.value }.joinToString("<br>") { "${escape(it.key)}: ${it.value}" } + "</html>"
    }

    private fun bucketName(): String =
        (bucket.editor.item ?: bucket.selectedItem)?.toString().orEmpty().trim().removePrefix("gs://").trimEnd('/')

    /** Stops listing and tests; tests in progress finish in the background and their results are dropped. */
    fun close() {
        comparisonWindow?.dispose()
        closed = true
        stopRequested = true
        previewDelay.stop()
        clock.stop()
        listing?.cancel(true)
        listing = null
        listingToken++
        previewToken++
        xmlToken++
        // A running test still uses the template features; the run frees them when it ends.
        if (job == null) closePrepared()
    }

    /** Columns of the file list; the model is an inner class, which cannot hold them. */
    private object FileColumns {
        const val NAME = 0
        const val SIZE = 1
        const val CREATED = 2
        const val PAGES = 3
        const val XML = 4
        const val RESULT = 5
        const val TEMPLATE = 6
        const val TIME = 7
        val NAMES = listOf("Name", "Size", "Date added", "Pages", "XML", "Result", "Template", "Time")
    }

    /** The rows of one scanned document's PDFs and XMLs, which have the same [BucketFile.documentPrefix]. */
    private class Document {
        val pdfs = ArrayList<Int>(1)
        val xmls = ArrayList<Int>(1)
        val paired: Boolean get() = pdfs.isNotEmpty() && xmls.isNotEmpty()
    }

    private inner class FileTableModel : AbstractTableModel() {
        val rows = ArrayList<BucketFile>()
        private val index = HashMap<String, Int>()
        /** Each row's [BucketFile.documentPrefix], worked out once. */
        private val prefixes = ArrayList<String?>()
        private val documents = HashMap<String, Document>()
        var bucket = ""
            private set
        var totalBytes = 0L
            private set

        /** How many listed PDFs have an XML. */
        val pdfsWithXml: Int get() = documents.values.sumOf { if (it.xmls.isEmpty()) 0 else it.pdfs.size }

        /** Adds a page of the listing. Returns the documents with rows listed earlier that now have both a PDF and an XML. */
        fun add(page: List<BucketFile>): Set<String> {
            if (page.isEmpty()) return emptySet()
            val first = rows.size
            val paired = HashSet<String>()
            page.forEach { file ->
                val row = rows.size
                index[key(file)] = row
                rows += file
                val prefix = file.documentPrefix
                prefixes += prefix
                val document = prefix?.let { documents.getOrPut(it, ::Document) } ?: return@forEach
                val wasPaired = document.paired
                when {
                    file.isPdf -> document.pdfs += row
                    file.isXml -> document.xmls += row
                }
                if (!wasPaired && document.paired && (document.pdfs + document.xmls).any { it < first }) paired += prefix
            }
            totalBytes += page.sumOf { it.size }
            fireTableRowsInserted(first, rows.size - 1)
            paired.flatMap { documents.getValue(it).let { document -> document.pdfs + document.xmls } }.filter { it < first }
                .forEach { fireTableRowsUpdated(it, it) }
            return paired
        }

        fun clear(bucketName: String) {
            rows.clear()
            index.clear()
            prefixes.clear()
            documents.clear()
            totalBytes = 0
            bucket = bucketName
            fireTableDataChanged()
        }

        fun key(file: BucketFile) = resultKey(bucket, file)

        fun resultAt(row: Int): PdfTestResult? = results[key(rows[row])]

        /** Whether the PDF or XML in [row] has its XML or PDF in the list. */
        fun paired(row: Int): Boolean = (rows[row].isPdf || rows[row].isXml) && prefixes[row]?.let { documents[it] }?.paired == true

        /** The listed XML of [file], a PDF, the newest if there are several. */
        fun xmlOf(file: BucketFile): BucketFile? =
            if (!file.isPdf) null else documentOf(file)?.let { document -> xmlFor(file, document.xmls.map(rows::get)) }

        /** The listed PDF of [file], an XML, the newest if there are several. */
        fun pdfOf(file: BucketFile): BucketFile? =
            documentOf(file)?.pdfs?.map(rows::get)?.maxWithOrNull(compareBy(nullsFirst()) { it.created })

        /** For the XML column's tooltip. */
        fun xmlDescription(row: Int): String? {
            val file = rows[row]
            return when {
                file.isPdf -> xmlOf(file)?.let { "Has an XML: ${it.fileName}. The XML tab shows it." }
                file.isXml -> pdfOf(file)?.let { "The XML of ${it.fileName}." } ?: "An XML; the list has no PDF of this document."
                else -> null
            }
        }

        private fun documentOf(file: BucketFile): Document? =
            (index[key(file)]?.let(prefixes::get) ?: file.documentPrefix)?.let(documents::get)

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
                FileColumns.XML -> when {
                    file.isPdf -> if (paired(row)) "✓" else ""
                    file.isXml -> "XML"
                    else -> ""
                }
                FileColumns.RESULT -> result?.status
                FileColumns.TEMPLATE -> result?.templateText.orEmpty()
                else -> result?.takeIf { it.status.finished }?.millis?.takeIf { it > 0 }
            }
        }
    }

    companion object {
        // Shared with GcpBucketView.
        private const val BUCKET = "bucket"
        private const val PREFIX = "prefix"
        private const val PDF_ONLY = "pdfOnly"
        private const val XML_TOO = "templateTestXmlToo"
        private const val RUN_COUNT = "templateTestRunCount"
        private const val DOWNLOAD_FOLDER = "downloadFolder"
        private const val CHOSEN_TEMPLATES = "templateTestTemplates"
        private const val RENDER_DPI = "templateTestRenderDpi"
        private const val FEATURE_DPI = "templateTestFeatureDpi"
        private const val OCR_DPI = "templateTestOcrDpi"
        private const val DETECTOR = "templateTestDetector"
        private const val MATCH_FILTER = "templateTestMatchFilter"
        private const val HOMOGRAPHY = "templateTestHomography"
        private const val TOLERANCE = "templateTestTolerance"
        private const val FOLDER = "templateFolder"
        private const val MAX_RUN = 100_000
        private const val PREVIEW_DELAY_MS = 150
        private const val RESULT_TAB = 1
        private const val XML_TAB = 2
        private const val XML_COMPARISON_TAB = 3

        /**
         * Opens the view in its own window; the bucket is listed when Load is pressed. [onTemplateCreated] gets the page files
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
            }
        }
    }
}

private enum class ListMode { BUCKET, DOWNLOADS, TEST_FILES }

private enum class ShowFilter(private val label: String, val accepts: (PdfTestResult?) -> Boolean) {
    ALL("All files", { true }),
    UNTESTED("Untested", { it?.status?.finished != true }),
    TESTED("Tested", { it?.status?.finished == true }),
    MATCHED("✓ Matched", { it?.status == PdfStatus.MATCHED }),
    NO_MATCH("✗ No match", { it?.status == PdfStatus.NO_MATCH }),
    ERROR("Errors", { it?.status == PdfStatus.ERROR });

    override fun toString() = label
}

private enum class RunOrder(private val label: String) {
    LIST("from the top"), RANDOM("at random");

    override fun toString() = label
}

/** The template pages the matcher found in one file, in template order. */
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
    override fun getColumnClass(column: Int): Class<*> = String::class.java

    override fun getValueAt(row: Int, column: Int): Any? {
        val template = rows[row].template
        return when (column) {
            0 -> template.group
            else -> "${template.position} of ${template.templateSize} (${template.name})"
        }
    }

    companion object {
        val COLUMNS = listOf("Template", "Template page")
    }
}

private val MATCH_COLOR = Color(20, 130, 50)
private val NO_MATCH_COLOR = Color(195, 30, 30)
private val ERROR_COLOR = Color(150, 60, 170)

private fun statusColor(status: PdfStatus?): Color = when (status) {
    PdfStatus.MATCHED -> MATCH_COLOR
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
