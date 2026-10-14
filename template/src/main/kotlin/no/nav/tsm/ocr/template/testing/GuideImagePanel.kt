package no.nav.tsm.ocr.template.testing

import java.awt.BasicStroke
import java.awt.Color
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.geom.Rectangle2D
import java.awt.image.BufferedImage
import javax.swing.JViewport
import javax.swing.JPanel
import javax.swing.Scrollable
import javax.swing.SwingConstants
import kotlin.math.roundToInt

internal data class GuidePane(
    val title: String,
    val image: BufferedImage,
    val boxes: List<Rectangle> = emptyList(),
)

/** Shows bucket-test previews fitted to the view or at actual size (scrollable). */
internal class GuideImagePanel : JPanel(), Scrollable {
    var panes: List<GuidePane> = emptyList()
        set(value) { field = value; revalidate(); repaint() }
    var actualSize = false
        set(value) { field = value; revalidate(); repaint() }

    init {
        background = Color(235, 237, 240)
    }

    private fun layoutPanes(): List<Rectangle> {
        if (panes.isEmpty()) return emptyList()
        if (actualSize) {
            var x = GAP
            return panes.map { pane -> Rectangle(x, TITLE, pane.image.width, pane.image.height).also { x += it.width + GAP } }
        }
        val slot = ((width - GAP * (panes.size + 1)) / panes.size).coerceAtLeast(1)
        val available = (height - TITLE - GAP).coerceAtLeast(1)
        return panes.mapIndexed { i, pane ->
            val scale = minOf(slot.toDouble() / pane.image.width, available.toDouble() / pane.image.height)
            val w = (pane.image.width * scale).roundToInt().coerceAtLeast(1)
            val h = (pane.image.height * scale).roundToInt().coerceAtLeast(1)
            Rectangle(GAP + i * (slot + GAP) + (slot - w) / 2, TITLE + (available - h) / 2, w, h)
        }
    }

    override fun getPreferredSize(): Dimension = when {
        actualSize && panes.isNotEmpty() -> Dimension(panes.sumOf { it.image.width } + GAP * (panes.size + 1),
            panes.maxOf { it.image.height } + TITLE + GAP)
        isPreferredSizeSet -> super.getPreferredSize()
        else -> Dimension(320, 240)
    }

    override fun getPreferredScrollableViewportSize() = Dimension(900, 650)
    override fun getScrollableUnitIncrement(visible: Rectangle, orientation: Int, direction: Int) = 32
    override fun getScrollableBlockIncrement(visible: Rectangle, orientation: Int, direction: Int) =
        if (orientation == SwingConstants.VERTICAL) visible.height else visible.width
    override fun getScrollableTracksViewportWidth() = !actualSize || (parent as? JViewport)?.let { it.width > preferredSize.width } == true
    override fun getScrollableTracksViewportHeight() = !actualSize || (parent as? JViewport)?.let { it.height > preferredSize.height } == true

    override fun paintComponent(graphics: Graphics) {
        super.paintComponent(graphics)
        val g = graphics.create() as Graphics2D
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            if (panes.isEmpty()) {
                val message = "Nothing to show for this result yet."
                g.color = Color.GRAY
                g.drawString(message, maxOf(8, (width - g.fontMetrics.stringWidth(message)) / 2), height / 2)
                return
            }
            panes.zip(layoutPanes()).forEach { (pane, box) -> paintPane(g, pane, box) }
        } finally { g.dispose() }
    }

    private fun paintPane(g: Graphics2D, pane: GuidePane, box: Rectangle) {
        val sx = box.width.toDouble() / pane.image.width
        val sy = box.height.toDouble() / pane.image.height
        g.color = Color.DARK_GRAY
        g.drawString(pane.title, box.x, box.y - 6)
        g.drawImage(pane.image, box.x, box.y, box.width, box.height, null)
        val clip = g.clip
        g.clipRect(box.x, box.y, box.width, box.height)
        pane.boxes.forEach { region ->
            val shape = Rectangle2D.Double(box.x + region.x * sx, box.y + region.y * sy, region.width * sx, region.height * sy)
            g.color = Color(0, 170, 60)
            g.stroke = BasicStroke(1.5f)
            g.draw(shape)
        }
        g.clip = clip
    }

    private companion object {
        const val GAP = 12
        const val TITLE = 22
    }
}
