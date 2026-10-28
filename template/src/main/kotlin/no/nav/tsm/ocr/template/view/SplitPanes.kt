package no.nav.tsm.ocr.template.view

import java.awt.Component
import java.awt.Container
import java.awt.Dimension
import java.awt.FlowLayout
import javax.swing.BorderFactory
import javax.swing.JScrollPane
import javax.swing.JSplitPane
import javax.swing.SwingUtilities

/**
 * The smallest a side of a [splitPane] gets when its divider is dragged; below its content's own minimum it is cut
 * off or scrolls. Without this, a side's minimum is what its toolbars and labels need, often hundreds of pixels, and
 * the divider stops there: it then moves one way only, or not at all when the two sides' minimums fill the pane.
 */
internal const val MIN_SPLIT_SIDE = 80

/** The divider's width, easy to grab. */
internal const val SPLIT_DIVIDER_SIZE = 10

/**
 * A split pane whose divider can be dragged both ways, to within [MIN_SPLIT_SIDE] of either edge. [first] is the left
 * (top) side, [second] the right (bottom). The divider starts at [location], a share of the pane's width (height),
 * when the pane is first laid out, and stays where it is dragged afterwards; when the pane is resized, [weight] of
 * the change goes to [first]. The sides are redrawn while the divider is dragged.
 */
internal fun splitPane(orientation: Int, first: Component, second: Component, location: Double? = null, weight: Double = 0.5): JSplitPane =
    InitialSplitPane(orientation, location).apply {
        leftComponent = allowSmall(first)
        rightComponent = allowSmall(second)
        resizeWeight = weight
        isContinuousLayout = true
        dividerSize = SPLIT_DIVIDER_SIZE
        border = BorderFactory.createEmptyBorder()
    }

/** Lets [component] be made as small as [MIN_SPLIT_SIDE] in a split pane, whatever its content needs; returns it. */
internal fun <T : Component> allowSmall(component: T): T = component.apply { minimumSize = Dimension(MIN_SPLIT_SIDE, MIN_SPLIT_SIDE) }

/** Sets its divider to the share it starts at once it has a size: before that, a share of nothing is nothing. */
private class InitialSplitPane(orientation: Int, private var initial: Double?) : JSplitPane(orientation) {
    override fun doLayout() {
        val share = initial
        if (share != null && width > 0 && height > 0) {
            initial = null
            setDividerLocation(share)
        }
        super.doLayout()
    }
}

/**
 * A [FlowLayout] that wraps onto more rows when its container is too narrow, and asks for the height of all of them.
 * A plain FlowLayout asks for one row and cuts off what does not fit in it, so narrowing a pane hides its buttons.
 */
internal class WrapLayout(align: Int = LEFT, hgap: Int = 6, vgap: Int = 2) : FlowLayout(align, hgap, vgap) {
    override fun preferredLayoutSize(target: Container): Dimension = layoutSize(target, preferred = true)

    // Narrower than the rows need, so a container that shrinks can make them wrap.
    override fun minimumLayoutSize(target: Container): Dimension = layoutSize(target, preferred = false).apply { width -= hgap + 1 }

    private fun layoutSize(target: Container, preferred: Boolean): Dimension = synchronized(target.treeLock) {
        // The width to wrap at: the container's own, or before it has one, the nearest ancestor's that has.
        var sized: Container = target
        while (sized.width == 0 && sized.parent != null) sized = sized.parent
        val available = if (sized.width == 0) Int.MAX_VALUE else sized.width
        val insets = target.insets
        val horizontal = insets.left + insets.right + hgap * 2
        val maxWidth = available - horizontal
        val size = Dimension(0, 0)
        var rowWidth = 0
        var rowHeight = 0
        for (component in target.components) {
            if (!component.isVisible) continue
            val wanted = if (preferred) component.preferredSize else component.minimumSize
            if (rowWidth > 0 && rowWidth + hgap + wanted.width > maxWidth) {
                addRow(size, rowWidth, rowHeight)
                rowWidth = 0
                rowHeight = 0
            }
            if (rowWidth > 0) rowWidth += hgap
            rowWidth += wanted.width
            rowHeight = maxOf(rowHeight, wanted.height)
        }
        addRow(size, rowWidth, rowHeight)
        size.width += horizontal
        size.height += insets.top + insets.bottom + vgap * 2
        // In a scroll pane, leave room for its vertical scroll bar rather than wrapping under it.
        val scrollPane = SwingUtilities.getAncestorOfClass(JScrollPane::class.java, target)
        if (scrollPane != null && target.isValid) size.width -= hgap + 1
        size
    }

    private fun addRow(size: Dimension, rowWidth: Int, rowHeight: Int) {
        size.width = maxOf(size.width, rowWidth)
        if (size.height > 0) size.height += vgap
        size.height += rowHeight
    }
}
