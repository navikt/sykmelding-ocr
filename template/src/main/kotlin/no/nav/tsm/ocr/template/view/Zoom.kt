package no.nav.tsm.ocr.template.view

import java.awt.Cursor
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Insets
import java.awt.Point
import java.awt.Rectangle
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.MouseWheelEvent
import java.awt.geom.Point2D
import java.awt.geom.Rectangle2D
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JViewport
import javax.swing.Scrollable
import javax.swing.SwingConstants
import javax.swing.SwingUtilities
import kotlin.math.pow
import kotlin.math.roundToInt

/** How a [ZoomCanvas] shows its content: fitted to what is visible of it, or at a fixed [Scale] (1.0 = 100 %). */
sealed interface Zoom {
    /** All of the content, as large as fits. */
    data object FitPage : Zoom

    /** As wide as fits; scroll for the rest. */
    data object FitWidth : Zoom

    data class Scale(val scale: Double) : Zoom
}

/** The fixed zooms the zoom buttons step through. */
internal val ZOOM_LEVELS = listOf(0.1, 0.25, 0.33, 0.5, 0.67, 0.75, 1.0, 1.25, 1.5, 2.0, 3.0, 4.0, 6.0, 8.0)
internal const val MIN_ZOOM = 0.05
internal const val MAX_ZOOM = 8.0

/** One wheel notch zooms by this much. */
private const val WHEEL_STEP = 1.1

/**
 * A panel in a scroll pane that shows its content at a [zoom]. Ctrl (or ⌘) + the scroll wheel zooms where the mouse
 * is; the wheel alone scrolls; dragging moves the content. [contentSize] is the content's size at 100 %, and [around]
 * the room left around it that is not scaled, such as margins and titles; [contentBounds] is where the content is
 * drawn, centred when it is smaller than the view.
 */
internal abstract class ZoomCanvas : JPanel(), Scrollable {
    /** The content's size at 100 %, in screen pixels; null when there is nothing to show. */
    protected abstract val contentSize: Dimension?

    /** Room around the content that is not scaled. */
    protected open val around: Insets get() = Insets(MARGIN, MARGIN, MARGIN, MARGIN)

    private var current: Zoom = Zoom.FitPage
    private val zoomListeners = mutableListOf<() -> Unit>()
    private var reported: Pair<Zoom, Double>? = null

    /** How the content is shown; setting it keeps the middle of the view where it is. */
    var zoom: Zoom
        get() = current
        set(value) = zoomTo(value)

    /** The scale the content is shown at now: the fixed one, or what fits. */
    val scale: Double get() = scaleIn(viewSize())

    /** Whether there is anything to show. */
    val hasContent: Boolean get() = contentSize != null

    /** Called when [zoom] or [scale] changes, e.g. when a fitted view is resized. */
    fun addZoomListener(listener: () -> Unit) {
        zoomListeners += listener
    }

    private val viewport: JViewport? get() = parent as? JViewport

    init {
        val mouse = Mouse()
        addMouseWheelListener(mouse)
        addMouseListener(mouse)
        addMouseMotionListener(mouse)
    }

    fun zoomIn(anchor: Point? = null) = zoomTo(Zoom.Scale(ZOOM_LEVELS.firstOrNull { it > scale * 1.01 } ?: MAX_ZOOM), anchor)

    fun zoomOut(anchor: Point? = null) = zoomTo(Zoom.Scale(ZOOM_LEVELS.lastOrNull { it < scale * 0.99 } ?: MIN_ZOOM), anchor)

    /**
     * Shows the content at [zoom], keeping the point of it at [anchor] (in this panel's pixels; the middle of the view
     * when null) where it is on the screen.
     */
    fun zoomTo(zoom: Zoom, anchor: Point? = null) {
        val target = if (zoom is Zoom.Scale) Zoom.Scale(zoom.scale.coerceIn(MIN_ZOOM, MAX_ZOOM)) else zoom
        val viewport = viewport
        if (contentSize == null || viewport == null) {
            current = target
            contentChanged()
            return
        }
        val visible = viewport.viewRect
        val at = anchor ?: Point(visible.x + visible.width / 2, visible.y + visible.height / 2)
        val before = contentBounds()
        val oldScale = scale
        val point = Point2D.Double((at.x - before.x) / oldScale, (at.y - before.y) / oldScale)
        val onScreen = Point(at.x - visible.x, at.y - visible.y)
        current = target
        invalidate()
        layOut(viewport)
        val after = contentBounds()
        val newScale = scale
        scrollTo(Point((after.x + point.x * newScale - onScreen.x).roundToInt(), (after.y + point.y * newScale - onScreen.y).roundToInt()))
        repaint()
        fireZoom()
    }

    /** Scrolls so that [position] of this panel is at the view's top left, as far as it can. */
    fun scrollTo(position: Point) {
        val viewport = viewport ?: return
        val extent = viewport.extentSize
        viewport.viewPosition = Point(
            position.x.coerceIn(0, maxOf(0, width - extent.width)),
            position.y.coerceIn(0, maxOf(0, height - extent.height)),
        )
    }

    /** Call when the content or its size changes. */
    protected fun contentChanged() {
        revalidate()
        repaint()
        fireZoom(always = true)
    }

    /** Where the content is drawn, at [scale], in this panel's pixels; empty when there is none. */
    protected fun contentBounds(): Rectangle2D.Double {
        val content = contentSize ?: return Rectangle2D.Double()
        val s = scale
        val a = around
        val w = content.width * s
        val h = content.height * s
        return Rectangle2D.Double(a.left + maxOf(0.0, (width - a.left - a.right - w) / 2), a.top + maxOf(0.0, (height - a.top - a.bottom - h) / 2), w, h)
    }

    /** The point of the content at [point] of this panel, in content pixels at 100 %. */
    fun contentPoint(point: Point): Point2D.Double {
        val bounds = contentBounds()
        val s = scale
        return Point2D.Double((point.x - bounds.x) / s, (point.y - bounds.y) / s)
    }

    /** The size the content is fitted to: what is visible of this panel, or this panel without a scroll pane. */
    private fun viewSize(): Dimension = viewport?.extentSize ?: size

    private fun scaleIn(view: Dimension): Double {
        val content = contentSize ?: return 1.0
        if (content.width <= 0 || content.height <= 0) return 1.0
        val a = around
        val width = (view.width - a.left - a.right).toDouble() / content.width
        val height = (view.height - a.top - a.bottom).toDouble() / content.height
        return when (val zoom = current) {
            Zoom.FitPage -> minOf(width, height)
            Zoom.FitWidth -> width
            is Zoom.Scale -> zoom.scale
        }.coerceIn(MIN_ZOOM, MAX_ZOOM)
    }

    override fun getPreferredSize(): Dimension {
        if (isPreferredSizeSet) return super.getPreferredSize()
        val content = contentSize ?: return Dimension(1, 1)
        val s = scale
        val a = around
        return Dimension((content.width * s).roundToInt() + a.left + a.right, (content.height * s).roundToInt() + a.top + a.bottom)
    }

    override fun getPreferredScrollableViewportSize(): Dimension = Dimension(600, 700)
    override fun getScrollableUnitIncrement(visible: Rectangle, orientation: Int, direction: Int) = 32
    override fun getScrollableBlockIncrement(visible: Rectangle, orientation: Int, direction: Int) =
        ((if (orientation == SwingConstants.VERTICAL) visible.height else visible.width) - 32).coerceAtLeast(32)

    // A fitted view never scrolls that way; at a fixed zoom it fills the view when the content is smaller.
    override fun getScrollableTracksViewportWidth() = current !is Zoom.Scale || (viewport?.let { it.width > preferredSize.width } == true)
    override fun getScrollableTracksViewportHeight() = current == Zoom.FitPage || (viewport?.let { it.height > preferredSize.height } == true)

    override fun setBounds(x: Int, y: Int, width: Int, height: Int) {
        super.setBounds(x, y, width, height)
        fireZoom()
    }

    private fun fireZoom(always: Boolean = false) {
        val now = current to scale
        if (now == reported && !always) return
        reported = now
        zoomListeners.forEach { it() }
    }

    /** Lays out the scroll pane at once, so the view's new size is known; validate() does nothing until it is on screen. */
    private fun layOut(viewport: JViewport) {
        val scrollPane = viewport.parent as? JScrollPane
        if (scrollPane != null && scrollPane.isDisplayable) {
            scrollPane.validate()
        } else {
            scrollPane?.doLayout()
            viewport.doLayout()
        }
    }

    /** Zooms with Ctrl/⌘ + the wheel, passes the wheel alone on to the scroll pane, and moves the content when dragged. */
    private inner class Mouse : MouseAdapter() {
        private var dragFrom: Point? = null
        private var dragStart: Point? = null

        override fun mouseWheelMoved(event: MouseWheelEvent) {
            if (event.isControlDown || event.isMetaDown) {
                if (contentSize != null) zoomTo(Zoom.Scale(scale * WHEEL_STEP.pow(-event.preciseWheelRotation)), event.point)
                event.consume()
                return
            }
            // A wheel listener here keeps the event from the scroll pane, so hand it on.
            val scrollPane = SwingUtilities.getAncestorOfClass(JScrollPane::class.java, this@ZoomCanvas) ?: return
            scrollPane.dispatchEvent(SwingUtilities.convertMouseEvent(this@ZoomCanvas, event, scrollPane))
        }

        override fun mousePressed(event: MouseEvent) {
            val viewport = viewport ?: return
            if (!SwingUtilities.isLeftMouseButton(event)) return
            dragFrom = event.locationOnScreen
            dragStart = viewport.viewPosition
        }

        override fun mouseDragged(event: MouseEvent) {
            val from = dragFrom ?: return
            val start = dragStart ?: return
            cursor = Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR)
            // Screen coordinates: the panel moves under the mouse while it is dragged.
            scrollTo(Point(start.x - (event.xOnScreen - from.x), start.y - (event.yOnScreen - from.y)))
        }

        override fun mouseReleased(event: MouseEvent) {
            dragFrom = null
            dragStart = null
            cursor = Cursor.getDefaultCursor()
        }
    }

    protected companion object {
        const val MARGIN = 10
    }
}

private const val FIT_PAGE = "Fit page"
private const val FIT_WIDTH = "Fit width"

/** "Fit page", "Fit width", or a percentage such as "150 %" or "150"; null when [text] is none of them. */
internal fun parseZoom(text: String?): Zoom? {
    val value = text?.trim().orEmpty()
    if (value.equals(FIT_PAGE, ignoreCase = true)) return Zoom.FitPage
    if (value.equals(FIT_WIDTH, ignoreCase = true)) return Zoom.FitWidth
    val number = value.removeSuffix("%").trim().replace(',', '.').toDoubleOrNull() ?: return null
    return if (number > 0) Zoom.Scale(number / 100) else null
}

internal fun zoomText(zoom: Zoom): String = when (zoom) {
    Zoom.FitPage -> FIT_PAGE
    Zoom.FitWidth -> FIT_WIDTH
    is Zoom.Scale -> percent(zoom.scale)
}

private fun percent(scale: Double) = "${(scale * 100).roundToInt()}%"

/**
 * Zoom out, the zoom (fit page, fit width or a percentage, which can be typed), zoom in, and the percentage a fitted
 * view is at, for [canvas]. [hundredPercent] says what 100 % is.
 */
internal class ZoomControls(private val canvas: ZoomCanvas, hundredPercent: String) : JPanel(FlowLayout(FlowLayout.LEFT, 3, 0)) {
    private val zoomOut = JButton("−").apply {
        toolTipText = "Zoom out (or Ctrl/⌘ + scroll wheel, where the mouse is)"
        margin = Insets(2, 8, 2, 8)
        addActionListener { canvas.zoomOut() }
    }
    private val choice = JComboBox(arrayOf(FIT_PAGE, FIT_WIDTH, "25%", "50%", "75%", "100%", "150%", "200%", "300%", "400%")).apply {
        isEditable = true
        prototypeDisplayValue = "Fit width  "
        toolTipText = "<html>Fit the page or its width, or a zoom: choose one, or type a percentage and press Enter.<br>" +
            "100% is $hundredPercent. Drag the picture to move it.</html>"
        addActionListener { if (!updating) choose(selectedItem) }
    }
    private val zoomIn = JButton("+").apply {
        toolTipText = "Zoom in (or Ctrl/⌘ + scroll wheel, where the mouse is)"
        margin = Insets(2, 8, 2, 8)
        addActionListener { canvas.zoomIn() }
    }
    private val fitted = JLabel()
    private var updating = false

    init {
        isOpaque = false
        add(zoomOut)
        add(choice)
        add(zoomIn)
        add(fitted)
        canvas.addZoomListener(::refresh)
        refresh()
    }

    // For tests.
    internal val shownZoom: String get() = choice.selectedItem?.toString().orEmpty()
    internal val shownScale: String get() = fitted.text
    internal fun type(text: String) {
        choice.selectedItem = text
    }

    private fun choose(item: Any?) {
        val zoom = parseZoom(item?.toString())
        if (zoom == null) refresh() else canvas.zoom = zoom
    }

    private fun refresh() {
        updating = true
        try {
            choice.selectedItem = zoomText(canvas.zoom)
        } finally {
            updating = false
        }
        // The percentage a fitted view is at; nothing to tell when the view is empty.
        fitted.text = if (canvas.zoom is Zoom.Scale || !canvas.hasContent) "" else percent(canvas.scale)
    }
}
