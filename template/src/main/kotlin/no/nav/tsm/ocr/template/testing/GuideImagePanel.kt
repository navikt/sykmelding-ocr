package no.nav.tsm.ocr.template.testing

import no.nav.tsm.ocr.template.view.ZoomCanvas
import no.nav.tsm.sykmelding.model.CheckboxState
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Insets
import java.awt.Point
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.event.MouseEvent
import java.awt.geom.Rectangle2D
import java.awt.image.BufferedImage
import javax.swing.ToolTipManager
import kotlin.math.roundToInt

/**
 * A picture with [boxes] drawn on it: [flagged] ones need a look; a box in [checkboxes] is a checkbox, drawn by its
 * state ([boxStyle]); [regions] are drawn over them all.
 */
internal data class GuidePane(
    val title: String,
    val image: BufferedImage,
    val boxes: List<Rectangle> = emptyList(),
    val flagged: Set<Int> = emptySet(),
    val regions: List<Rectangle> = emptyList(),
    val checkboxes: Map<Int, CheckboxState> = emptyMap(),
)

/** How a box is drawn: its outline's [color] and [width], and a translucent [fill] inside, if any. */
internal data class BoxStyle(val color: Color, val width: Float, val fill: Color? = null)

/**
 * How box [number] of [pane] is drawn. Green: a field; magenta, thicker: one that needs a look. A checkbox by its
 * state: checked green and filled, unchecked blue, uncertain magenta like other fields that need a look.
 */
internal fun boxStyle(pane: GuidePane, number: Int): BoxStyle = when (pane.checkboxes[number]) {
    CheckboxState.CHECKED -> BoxStyle(GuideColors.FIELD, 2.5f, GuideColors.CHECKED_FILL)
    CheckboxState.UNCHECKED -> BoxStyle(GuideColors.UNCHECKED, 2f)
    CheckboxState.UNCERTAIN -> BoxStyle(GuideColors.FLAGGED, 2.5f)
    null -> if (number in pane.flagged) BoxStyle(GuideColors.FLAGGED, 2.5f) else BoxStyle(GuideColors.FIELD, 1.5f)
}

/** The colours of a [GuideImagePanel]; the views that show one name them in its title. */
internal object GuideColors {
    val FIELD = Color(0, 170, 60)
    val FLAGGED = Color(200, 0, 160)
    val UNCHECKED = Color(0, 110, 230)
    val CHECKED_FILL = Color(0, 170, 60, 70)
    val FOUND = Color(255, 130, 0)
}

internal data class GuideBox(val pane: Int, val box: Int)

internal class GuideImagePanel : ZoomCanvas() {
    var panes: List<GuidePane> = emptyList()
        set(value) { field = value; contentChanged() }

    var describeBox: (GuideBox) -> String? = { null }
    var describeRegion: (GuideBox) -> String? = { null }

    init {
        background = Color(235, 237, 240)
        ToolTipManager.sharedInstance().registerComponent(this)
    }
    override val contentSize: Dimension?
        get() = if (panes.isEmpty()) null else Dimension(panes.sumOf { it.image.width }, panes.maxOf { it.image.height })

    override val around: Insets get() = Insets(TITLE, GAP, GAP, GAP * maxOf(1, panes.size))

    override fun getToolTipText(event: MouseEvent): String? {
        val parts = listOfNotNull(regionAt(event.point)?.let(describeRegion), boxAt(event.point)?.let(describeBox))
        return if (parts.isEmpty()) null else parts.joinToString("<hr>", "<html>", "</html>")
    }

   internal fun boxAt(point: Point): GuideBox? = smallestAt(point, drawn { it.boxes })

    internal fun regionAt(point: Point): GuideBox? = smallestAt(point, drawn { it.regions })

    internal fun drawnBox(pane: Int, box: Int): Rectangle2D? = drawn { it.boxes }.firstOrNull { it.first == GuideBox(pane, box) }?.second

    private fun smallestAt(point: Point, shapes: List<Pair<GuideBox, Rectangle2D>>): GuideBox? =
        shapes.filter { (_, region) -> region.contains(point) }.minByOrNull { (_, region) -> region.width * region.height }?.first

    private fun drawn(of: (GuidePane) -> List<Rectangle>): List<Pair<GuideBox, Rectangle2D>> = panes.zip(layoutPanes()).flatMapIndexed { index, (pane, area) ->
        val sx = area.width / pane.image.width
        val sy = area.height / pane.image.height
        of(pane).mapIndexed { box, region ->
            GuideBox(index, box) to Rectangle2D.Double(area.x + region.x * sx, area.y + region.y * sy, region.width * sx, region.height * sy)
        }
    }

   private fun layoutPanes(): List<Rectangle2D.Double> {
        if (panes.isEmpty()) return emptyList()
        val bounds = contentBounds()
        val s = scale
        var x = bounds.x
        return panes.map { pane ->
            val w = pane.image.width * s
            Rectangle2D.Double(x, bounds.y, w, pane.image.height * s).also { x += w + GAP }
        }
    }

    override fun getPreferredScrollableViewportSize() = Dimension(900, 650)

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

    private fun paintPane(g: Graphics2D, pane: GuidePane, box: Rectangle2D.Double) {
        val sx = box.width / pane.image.width
        val sy = box.height / pane.image.height
        g.color = Color.DARK_GRAY
        g.drawString(pane.title, box.x.roundToInt(), (box.y - 6).roundToInt())
        g.drawImage(pane.image, box.x.roundToInt(), box.y.roundToInt(), box.width.roundToInt().coerceAtLeast(1), box.height.roundToInt().coerceAtLeast(1), null)
        val clip = g.clip
        g.clip(box)
        pane.boxes.forEachIndexed { number, region ->
            val style = boxStyle(pane, number)
            val drawn = Rectangle2D.Double(box.x + region.x * sx, box.y + region.y * sy, region.width * sx, region.height * sy)
            style.fill?.let {
                g.color = it
                g.fill(drawn)
            }
            g.color = style.color
            g.stroke = BasicStroke(style.width)
            g.draw(drawn)
        }
        g.color = GuideColors.FOUND
        g.stroke = BasicStroke(1.2f)
        pane.regions.forEach { region ->
            g.draw(Rectangle2D.Double(box.x + region.x * sx, box.y + region.y * sy, region.width * sx, region.height * sy))
        }
        g.clip = clip
    }

    private companion object {
        const val GAP = 12
        const val TITLE = 22
    }
}
