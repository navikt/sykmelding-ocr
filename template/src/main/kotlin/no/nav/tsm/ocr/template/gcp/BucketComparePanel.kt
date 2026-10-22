package no.nav.tsm.ocr.template.gcp

import no.nav.tsm.ocr.template.view.ImageComparisonView
import org.apache.pdfbox.Loader
import org.apache.pdfbox.rendering.ImageType
import org.apache.pdfbox.rendering.PDFRenderer
import org.example.no.nav.tsm.ocr.template.template.PdfTemplateImages
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.File
import java.util.concurrent.ExecutionException
import javax.imageio.ImageIO
import javax.swing.BorderFactory
import javax.swing.DefaultComboBoxModel
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JSpinner
import javax.swing.SpinnerNumberModel
import javax.swing.SwingWorker

/**
 * The "Compare" tab of the bucket test: one page of the shown document in an [ImageComparisonView] with a template page
 * of the library, so its steps can be done by hand: apply reference images, apply the configuration, read fields (OCR),
 * read the page number, detect sykmelding. Loading renders the page at 400 DPI and applies the reference images.
 */
internal class BucketComparePanel(
    private val templatePages: () -> List<LibraryPage>,
) : JPanel(BorderLayout(4, 4)) {
    private val status = JLabel(" ")
    private val comparison = ImageComparisonView(onStatus = { status.text = it })
    private val pageModel = SpinnerNumberModel(1, 1, 1, 1)
    private val page = JSpinner(pageModel).apply { toolTipText = "Page of the document" }
    private val pageCount = JLabel(" ")
    private val templates = JComboBox<LibraryPage>().apply {
        toolTipText = "Template page to compare the document page with; all templates of the library, not only the chosen ones."
        prototypeDisplayValue = LibraryPage("a long template name", File(""), 1, 1, File("page-10/page-10.json"),
            org.example.no.nav.tsm.ocr.template.template.Template("", "", emptyList(), ""))
    }
    private val load = JButton("Load").apply {
        toolTipText = "Render the document page at 400 DPI, open it with the template page and apply the reference images."
        addActionListener { load() }
    }

    private var documentName: String? = null
    private var bytes: (() -> ByteArray)? = null
    private var token = 0

    /** What was last loaded, or is being loaded: document, page and template page file; null when nothing is. */
    var requested: Triple<String, Int, File>? = null
        private set

    /** Whether a document is set that can be loaded. */
    val canLoad: Boolean get() = documentName != null && bytes != null

    init {
        add(JPanel(FlowLayout(FlowLayout.LEFT, 6, 2)).apply {
            add(JLabel("Page:")); add(page); add(pageCount)
            add(JLabel("Template page:")); add(templates); add(load)
        }, BorderLayout.NORTH)
        add(comparison, BorderLayout.CENTER)
        add(status.apply { border = BorderFactory.createEmptyBorder(2, 4, 2, 4) }, BorderLayout.SOUTH)
        setDocument(null, 0, null)
    }

    /** The document to compare; [bytes] is called off the EDT and may download it. Null [name] when no file is shown. */
    fun setDocument(name: String?, pages: Int, bytes: (() -> ByteArray)?) {
        if (name == documentName && pages == pageModel.maximum) { this.bytes = bytes; return }
        documentName = name
        this.bytes = bytes
        token++
        requested = null
        pageModel.maximum = maxOf(1, pages)
        pageModel.value = 1
        page.isEnabled = pages > 1
        pageCount.text = if (pages > 0) "of $pages" else " "
        load.isEnabled = name != null && bytes != null
        status.text = if (name == null) "Select a file in the list." else "Choose a page and a template page, then Load."
    }

    /** Fills in [documentPage] and [template] (the first template page when null), and loads them unless they are loaded already. */
    fun open(documentPage: Int, template: File?) {
        refreshTemplates()
        pageModel.value = documentPage.coerceIn(1, pageModel.maximum as Int)
        selectTemplate(template)
        val name = documentName ?: return
        val chosen = (templates.selectedItem as? LibraryPage)?.file ?: return
        if (requested != Triple(name, (page.value as Number).toInt(), chosen)) load()
    }

    /** Reads the library's template pages again, keeping the selection. */
    fun refreshTemplates() {
        val selected = (templates.selectedItem as? LibraryPage)?.file
        templates.model = DefaultComboBoxModel(templatePages().toTypedArray())
        selectTemplate(selected)
    }

    private fun selectTemplate(file: File?) {
        val model = templates.model
        val index = (0 until model.size).firstOrNull { model.getElementAt(it).file.absoluteFile == file?.absoluteFile }
        if (index != null) templates.selectedIndex = index else if (templates.selectedIndex < 0 && model.size > 0) templates.selectedIndex = 0
    }

    fun load() {
        val name = documentName ?: return
        val source = bytes ?: return
        val template = templates.selectedItem as? LibraryPage ?: run { status.text = "The library has no template page to compare with."; return }
        val documentPage = (page.value as Number).toInt()
        val current = ++token
        requested = Triple(name, documentPage, template.file)
        load.isEnabled = false
        status.text = "Rendering page $documentPage of $name at ${PdfTemplateImages.DPI} DPI …"
        object : SwingWorker<BufferedImage, Unit>() {
            override fun doInBackground(): BufferedImage = render(source(), documentPage)

            override fun done() {
                if (current != token) return
                load.isEnabled = true
                val image = try {
                    get()
                } catch (error: Exception) {
                    val cause = if (error is ExecutionException) error.cause ?: error else error
                    status.text = "Could not render page $documentPage: ${cause.message}"
                    requested = null
                    return
                }
                try {
                    comparison.loadConfiguration(template.file)
                } catch (error: Exception) {
                    status.text = "Could not open ${template.label}: ${error.message}"
                    requested = null
                    return
                }
                comparison.loadSecond(image)
                if (!comparison.applyReferenceImages()) {
                    status.text = "Page $documentPage opened with ${template.label}, which has no reference images to apply."
                }
            }
        }.execute()
    }

    private fun render(bytes: ByteArray, documentPage: Int): BufferedImage = if (PreviewContent.isPdf(bytes)) {
        Loader.loadPDF(bytes).use { document ->
            require(documentPage in 1..document.numberOfPages) { "The document has ${document.numberOfPages} pages." }
            PDFRenderer(document).renderImageWithDPI(documentPage - 1, PdfTemplateImages.DPI.toFloat(), ImageType.RGB)
        }
    } else {
        requireNotNull(ImageIO.read(ByteArrayInputStream(bytes))) { "Not a PDF or a readable image." }
    }
}
