package no.nav.tsm.ocr.template.gcp

import no.nav.tsm.ocr.template.view.WrapLayout
import no.nav.tsm.ocr.template.view.ZoomCanvas
import no.nav.tsm.ocr.template.view.ZoomControls
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Point
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.util.concurrent.ExecutionException
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTextArea
import javax.swing.SwingConstants
import javax.swing.SwingWorker
import javax.swing.Timer
import kotlin.math.ceil
import kotlin.math.roundToInt

internal class FilePreviewPanel : JPanel(BorderLayout(4, 4)) {
    private val title = JLabel(" ").apply { font = font.deriveFont(Font.BOLD) }
    private val canvas = PageCanvas()
    private val previous = JButton("◀ Page").apply { addActionListener { showPage(pageIndex - 1) } }
    private val next = JButton("Page ▶").apply { addActionListener { showPage(pageIndex + 1) } }
    private val pageLabel = JLabel()
    private val zoom = ZoomControls(canvas, "a PDF page at its size on paper, or one pixel of an image per screen pixel")
    private val pageControls = JPanel(WrapLayout(hgap = 4)).apply {
        add(previous); add(pageLabel); add(next); add(Box.createHorizontalStrut(10)); add(zoom)
    }
    private val text = JTextArea().apply { isEditable = false; font = Font(Font.MONOSPACED, Font.PLAIN, 12) }
    private val message = JLabel("", SwingConstants.CENTER)
    private val cards = CardLayout()
    private val content = JPanel(cards)

    private var pdf: ByteArray? = null
    private var pageCount = 0
    private var pageIndex = 0
    private val pages = mutableMapOf<Int, BufferedImage>()
    private var sharp: SharpPage? = null
    private var sharpening: SharpPage? = null
    private var generation = 0
    private val sharpenDelay = Timer(SHARPEN_DELAY_MS) { sharpen() }.apply { isRepeats = false }

    init {
        border = BorderFactory.createTitledBorder("Preview")
        add(JPanel(BorderLayout()).apply { add(title, BorderLayout.NORTH); add(pageControls, BorderLayout.CENTER) }, BorderLayout.NORTH)
        content.add(JScrollPane(canvas).apply { verticalScrollBar.unitIncrement = 24; horizontalScrollBar.unitIncrement = 24 }, IMAGE)
        content.add(JScrollPane(text), TEXT)
        content.add(message, MESSAGE)
        add(content, BorderLayout.CENTER)
        canvas.addZoomListener { if (pdf != null) sharpenDelay.restart() }
        showMessage("Select a file to preview it.")
    }

    // For tests.
    internal val currentPage: Int get() = pageIndex
    internal val currentPageCount: Int get() = pageCount
    internal val pageView: ZoomCanvas get() = canvas
    internal val zoomControls: ZoomControls get() = zoom
    /** The DPI the shown PDF page is rendered at; null when no PDF page is shown. */
    internal val renderedDpi: Float? get() = when {
        pdf == null || canvas.image == null -> null
        sharp?.page == pageIndex && canvas.image === sharp?.image -> sharp?.dpi
        else -> PreviewContent.PDF_DPI
    }

    fun showMessage(text: String, heading: String = " ") {
        reset(heading)
        message.text = "<html><div style='text-align:center'>" + escape(text).replace("\n", "<br>") + "</div></html>"
        cards.show(content, MESSAGE)
    }

    fun show(heading: String, bytes: ByteArray, preview: PreviewContent) {
        reset(heading)
        when (preview) {
            is PreviewContent.Pdf -> {
                pdf = bytes
                pageCount = preview.info.pages.size
                pages[0] = preview.firstPage
                cards.show(content, IMAGE)
                showPage(0)
            }
            is PreviewContent.Image -> {
                canvas.show(preview.image, Dimension(preview.image.width, preview.image.height))
                pageLabel.text = "${preview.image.width} × ${preview.image.height} px"
                pageControls.isVisible = true
                previous.isVisible = false
                next.isVisible = false
                cards.show(content, IMAGE)
                canvas.scrollTo(Point(0, 0))
            }
            is PreviewContent.Text -> {
                text.text = preview.text + if (preview.truncated) "\n\n… (truncated)" else ""
                text.caretPosition = 0
                cards.show(content, TEXT)
            }
            is PreviewContent.Unsupported -> {
                message.text = "<html><div style='text-align:center'>${escape(preview.reason)}</div></html>"
                cards.show(content, MESSAGE)
            }
        }
    }

    private fun reset(heading: String) {
        generation++
        sharpenDelay.stop()
        title.text = heading
        pdf = null
        pageCount = 0
        pageIndex = 0
        pages.clear()
        sharp = null
        sharpening = null
        canvas.show(null, null)
        canvas.placeholder = ""
        text.text = ""
        pageControls.isVisible = false
        previous.isVisible = true
        next.isVisible = true
    }

    private fun showPage(index: Int) {
        val bytes = pdf ?: return
        if (index !in 0 until pageCount) return
        pageIndex = index
        pageLabel.text = "${index + 1} / $pageCount"
        pageControls.isVisible = true
        previous.isEnabled = index > 0
        next.isEnabled = index < pageCount - 1
        pages[index]?.let { showRendered(it); return }
        canvas.show(null, null)
        canvas.placeholder = "Rendering page ${index + 1} …"
        val token = generation
        object : SwingWorker<BufferedImage, Unit>() {
            override fun doInBackground(): BufferedImage = PreviewContent.renderPdfPage(bytes, index)
            override fun done() {
                if (token != generation) return
                try {
                    val image = get()
                    pages[index] = image
                    if (pageIndex == index) showRendered(image)
                } catch (error: Exception) {
                    val cause = if (error is ExecutionException) error.cause ?: error else error
                    if (pageIndex == index) canvas.placeholder = "Could not render page ${index + 1}: ${cause.message}"
                }
            }
        }.execute()
    }

    /** Shows the page rendered at [PreviewContent.PDF_DPI], from its top left, then a sharper one if the zoom needs it. */
    private fun showRendered(image: BufferedImage) {
        // At 100 % a page is as large as on paper: one PDF point per screen pixel.
        val points = Dimension(
            (image.width * POINTS_PER_INCH / PreviewContent.PDF_DPI).roundToInt(),
            (image.height * POINTS_PER_INCH / PreviewContent.PDF_DPI).roundToInt(),
        )
        canvas.show(sharp?.takeIf { it.page == pageIndex }?.image ?: image, points)
        canvas.scrollTo(Point(0, 0))
        sharpenDelay.restart()
    }

    /**
     * Renders the shown PDF page again when the zoom shows it larger than it is rendered: at the DPI the screen needs,
     * in steps of 50, at most [MAX_PREVIEW_DPI].
     */
    internal fun sharpen() {
        val bytes = pdf ?: return
        val index = pageIndex
        if (pages[index] == null) return
        val deviceScale = canvas.graphicsConfiguration?.defaultTransform?.scaleX ?: 1.0
        val needed = (ceil(POINTS_PER_INCH * canvas.scale * deviceScale / DPI_STEP) * DPI_STEP).toFloat().coerceAtMost(MAX_PREVIEW_DPI)
        val shown = sharp?.takeIf { it.page == index }?.dpi ?: PreviewContent.PDF_DPI
        if (needed <= shown * 1.1f) return
        if (sharpening?.let { it.page == index && it.dpi >= needed } == true) return
        val request = SharpPage(index, needed, null)
        sharpening = request
        val token = generation
        object : SwingWorker<BufferedImage, Unit>() {
            override fun doInBackground(): BufferedImage = PreviewContent.renderPdfPage(bytes, index, needed)
            override fun done() {
                if (sharpening === request) sharpening = null
                if (token != generation || pageIndex != index) return
                val image = try { get() } catch (_: Exception) { return }
                // A sharper one may have come first.
                if ((sharp?.takeIf { it.page == index }?.dpi ?: 0f) >= needed) return
                sharp = SharpPage(index, needed, image)
                canvas.sharper(image)
            }
        }.execute()
    }

    private class SharpPage(val page: Int, val dpi: Float, val image: BufferedImage?)

    private companion object {
        const val IMAGE = "image"
        const val TEXT = "text"
        const val MESSAGE = "message"
        const val POINTS_PER_INCH = 72.0
        const val DPI_STEP = 50.0
        /** A zoomed-in page is rendered at most this sharp: as sharp as the template pages, sharper than most scans. */
        const val MAX_PREVIEW_DPI = 400f
        const val SHARPEN_DELAY_MS = 250

        fun escape(text: String) = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    }
}

/** One page, or an image, at the preview's zoom. Its size at 100 % stays the same when it is rendered sharper. */
private class PageCanvas : ZoomCanvas() {
    var image: BufferedImage? = null
        private set
    private var pageSize: Dimension? = null
    var placeholder = ""
        set(value) { field = value; repaint() }

    init { background = Color(235, 237, 240) }

    override val contentSize: Dimension? get() = pageSize.takeIf { image != null }

    /** Shows [image] as large as [size] at 100 %; null for nothing. */
    fun show(image: BufferedImage?, size: Dimension?) {
        this.image = image
        pageSize = size
        contentChanged()
    }

    /** The same page rendered sharper: drawn in the same place. */
    fun sharper(image: BufferedImage) {
        this.image = image
        repaint()
    }

    override fun paintComponent(graphics: Graphics) {
        super.paintComponent(graphics)
        val g = graphics.create() as Graphics2D
        try {
            val page = image
            if (page == null) {
                g.color = Color.DARK_GRAY
                val metrics = g.fontMetrics
                g.drawString(placeholder, (width - metrics.stringWidth(placeholder)) / 2, height / 2)
                return
            }
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
            val bounds = contentBounds()
            val x = bounds.x.roundToInt()
            val y = bounds.y.roundToInt()
            val w = bounds.width.roundToInt().coerceAtLeast(1)
            val h = bounds.height.roundToInt().coerceAtLeast(1)
            g.color = Color(0, 0, 0, 40)
            g.fillRect(x + 3, y + 3, w, h)
            g.drawImage(page, x, y, w, h, null)
            g.color = Color.GRAY
            g.drawRect(x, y, w - 1, h - 1)
        } finally {
            g.dispose()
        }
    }
}
