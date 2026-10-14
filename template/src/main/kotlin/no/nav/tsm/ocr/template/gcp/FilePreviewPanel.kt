package no.nav.tsm.ocr.template.gcp

import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.util.concurrent.ExecutionException
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTextArea
import javax.swing.JViewport
import javax.swing.Scrollable
import javax.swing.SwingConstants
import javax.swing.SwingWorker
import kotlin.math.roundToInt

/** Right-hand preview: PDF pages (rendered on demand, off the EDT), images, or text. */
internal class FilePreviewPanel : JPanel(BorderLayout(4, 4)) {
    private enum class Zoom(private val label: String) {
        FIT_PAGE("Fit page"), FIT_WIDTH("Fit width");

        override fun toString() = label
    }

    private val title = JLabel(" ").apply { font = font.deriveFont(Font.BOLD) }
    private val canvas = PageCanvas()
    private val previous = JButton("◀ Page").apply { addActionListener { showPage(pageIndex - 1) } }
    private val next = JButton("Page ▶").apply { addActionListener { showPage(pageIndex + 1) } }
    private val pageLabel = JLabel()
    private val zoom = JComboBox(Zoom.entries.toTypedArray()).apply {
        addActionListener { canvas.fitWidth = selectedItem == Zoom.FIT_WIDTH }
    }
    private val pageControls = JPanel(FlowLayout(FlowLayout.RIGHT, 4, 0)).apply {
        add(previous); add(pageLabel); add(next); add(zoom)
    }
    private val text = JTextArea().apply { isEditable = false; font = Font(Font.MONOSPACED, Font.PLAIN, 12) }
    private val message = JLabel("", SwingConstants.CENTER)
    private val cards = CardLayout()
    private val content = JPanel(cards)

    private var pdf: ByteArray? = null
    private var pageCount = 0
    private var pageIndex = 0
    private val pages = mutableMapOf<Int, BufferedImage>()
    /** Bumped for every new document, so page renders for an earlier one are dropped. */
    private var generation = 0

    init {
        border = BorderFactory.createTitledBorder("Preview")
        add(JPanel(BorderLayout()).apply { add(title, BorderLayout.CENTER); add(pageControls, BorderLayout.EAST) }, BorderLayout.NORTH)
        content.add(JScrollPane(canvas).apply { verticalScrollBar.unitIncrement = 24 }, IMAGE)
        content.add(JScrollPane(text), TEXT)
        content.add(message, MESSAGE)
        add(content, BorderLayout.CENTER)
        minimumSize = Dimension(300, 300)
        showMessage("Select a file to preview it.")
    }

    // For tests.
    internal val currentPage: Int get() = pageIndex
    internal val currentPageCount: Int get() = pageCount
    internal val currentImage: BufferedImage? get() = canvas.image
    internal val messageText: String get() = message.text

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
                canvas.image = preview.image
                pageLabel.text = "${preview.image.width} × ${preview.image.height} px"
                pageControls.isVisible = true
                previous.isVisible = false
                next.isVisible = false
                cards.show(content, IMAGE)
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
        title.text = heading
        pdf = null
        pageCount = 0
        pageIndex = 0
        pages.clear()
        canvas.image = null
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
        pages[index]?.let { canvas.image = it; return }
        canvas.image = null
        canvas.placeholder = "Rendering page ${index + 1} …"
        val token = generation
        object : SwingWorker<BufferedImage, Unit>() {
            override fun doInBackground(): BufferedImage = PreviewContent.renderPdfPage(bytes, index)
            override fun done() {
                if (token != generation) return
                try {
                    val image = get()
                    pages[index] = image
                    if (pageIndex == index) canvas.image = image
                } catch (error: Exception) {
                    val cause = if (error is ExecutionException) error.cause ?: error else error
                    if (pageIndex == index) canvas.placeholder = "Could not render page ${index + 1}: ${cause.message}"
                }
            }
        }.execute()
    }

    private companion object {
        const val IMAGE = "image"
        const val TEXT = "text"
        const val MESSAGE = "message"

        fun escape(text: String) = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    }
}

/** Draws one page scaled to fit the viewport, or to its width with vertical scrolling. */
private class PageCanvas : JPanel(), Scrollable {
    var image: BufferedImage? = null
        set(value) { field = value; revalidate(); repaint() }
    var fitWidth = false
        set(value) { field = value; revalidate(); repaint() }
    var placeholder = ""
        set(value) { field = value; repaint() }

    init { background = Color(235, 237, 240) }

    private val viewportSize: Dimension? get() = (parent as? JViewport)?.extentSize

    override fun getPreferredSize(): Dimension {
        val page = image ?: return Dimension(1, 1)
        val available = viewportSize ?: return Dimension(page.width, page.height)
        if (!fitWidth) return available
        val width = (available.width - 2 * MARGIN).coerceAtLeast(1)
        return Dimension(available.width, (page.height * width.toDouble() / page.width).roundToInt() + 2 * MARGIN)
    }

    override fun getPreferredScrollableViewportSize(): Dimension = preferredSize
    override fun getScrollableUnitIncrement(visible: Rectangle, orientation: Int, direction: Int) = 24
    override fun getScrollableBlockIncrement(visible: Rectangle, orientation: Int, direction: Int) =
        (if (orientation == SwingConstants.VERTICAL) visible.height else visible.width) - 24
    override fun getScrollableTracksViewportWidth() = true
    override fun getScrollableTracksViewportHeight() = !fitWidth

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
            val availableWidth = (width - 2 * MARGIN).coerceAtLeast(1).toDouble()
            val availableHeight = (height - 2 * MARGIN).coerceAtLeast(1).toDouble()
            val scale = if (fitWidth) availableWidth / page.width else minOf(availableWidth / page.width, availableHeight / page.height)
            val w = (page.width * scale).roundToInt()
            val h = (page.height * scale).roundToInt()
            val x = (width - w) / 2
            val y = if (fitWidth) MARGIN else (height - h) / 2
            g.color = Color(0, 0, 0, 40)
            g.fillRect(x + 3, y + 3, w, h)
            g.drawImage(page, x, y, w, h, null)
            g.color = Color.GRAY
            g.drawRect(x, y, w - 1, h - 1)
        } finally {
            g.dispose()
        }
    }

    private companion object {
        const val MARGIN = 10
    }
}
