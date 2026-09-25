package org.example.no.nav.tsm.ocr.template

import java.awt.BasicStroke
import java.awt.Color
import java.awt.Cursor
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Point
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.image.BufferedImage
import javax.swing.JPanel
import javax.swing.SwingUtilities
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

internal class ImagePanel(
    initialImage: BufferedImage,
    var overlay: BufferedImage? = null
) : JPanel() {
    var image: BufferedImage = initialImage
        set(value) {
            cancelSelection()
            field = value
            repaint()
        }

    private var onSelected: ((BufferedImage) -> Unit)? = null
    private var onInvalidSelection: (() -> Unit)? = null
    private var selectionStart: Point? = null
    private var selection: Rectangle? = null
    val isSelecting: Boolean get() = onSelected != null

    init {
        val mouse = object : MouseAdapter() {
            override fun mousePressed(event: MouseEvent) {
                if (!isSelecting || !SwingUtilities.isLeftMouseButton(event)) return
                val bounds = imageBounds()
                if (!bounds.contains(event.point)) {
                    onInvalidSelection?.invoke()
                    return
                }
                selectionStart = event.point
                selection = null
                repaint()
            }

            override fun mouseDragged(event: MouseEvent) {
                updateSelection(event.point)
            }

            override fun mouseReleased(event: MouseEvent) {
                if (selectionStart == null || !SwingUtilities.isLeftMouseButton(event)) return
                updateSelection(event.point)
                selectionStart = null
                val selected = selection
                if (selected == null || selected.isEmpty) {
                    onInvalidSelection?.invoke()
                    return
                }
                val crop = image.getSubimage(selected.x, selected.y, selected.width, selected.height)
                val callback = onSelected
                cancelSelection()
                callback?.invoke(crop)
            }
        }
        addMouseListener(mouse)
        addMouseMotionListener(mouse)
    }

    fun selectArea(onSelected: (BufferedImage) -> Unit, onInvalidSelection: () -> Unit) {
        cancelSelection()
        this.onSelected = onSelected
        this.onInvalidSelection = onInvalidSelection
        cursor = Cursor.getPredefinedCursor(Cursor.CROSSHAIR_CURSOR)
    }

    fun cancelSelection() {
        onSelected = null
        onInvalidSelection = null
        selectionStart = null
        selection = null
        cursor = Cursor.getDefaultCursor()
        repaint()
    }

    private fun imageBounds(): Rectangle {
        val scale = minOf(width.toDouble() / image.width, height.toDouble() / image.height)
        val imageWidth = (image.width * scale).roundToInt()
        val imageHeight = (image.height * scale).roundToInt()
        return Rectangle((width - imageWidth) / 2, (height - imageHeight) / 2, imageWidth, imageHeight)
    }

    private fun updateSelection(end: Point) {
        val start = selectionStart ?: return
        val bounds = imageBounds()
        if (bounds.isEmpty) return
        val left = minOf(start.x, end.x).coerceIn(bounds.x, bounds.x + bounds.width)
        val top = minOf(start.y, end.y).coerceIn(bounds.y, bounds.y + bounds.height)
        val right = maxOf(start.x, end.x).coerceIn(bounds.x, bounds.x + bounds.width)
        val bottom = maxOf(start.y, end.y).coerceIn(bounds.y, bounds.y + bounds.height)
        // Use the actual painted bounds, including centering and rounded display dimensions.
        val x = floor((left - bounds.x).toDouble() * image.width / bounds.width).toInt()
        val y = floor((top - bounds.y).toDouble() * image.height / bounds.height).toInt()
        val maxX = ceil((right - bounds.x).toDouble() * image.width / bounds.width).toInt()
        val maxY = ceil((bottom - bounds.y).toDouble() * image.height / bounds.height).toInt()
        selection = if (left == right || top == bottom) null else Rectangle(x, y, maxX - x, maxY - y)
        repaint()
    }

    override fun paintComponent(graphics: Graphics) {
        super.paintComponent(graphics)
        val bounds = imageBounds()
        val g = graphics.create() as Graphics2D
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            g.drawImage(image, bounds.x, bounds.y, bounds.width, bounds.height, null)
            overlay?.let {
                g.drawImage(it, bounds.x, bounds.y, bounds.width, bounds.height, null)
            }
            selection?.let {
                val x = bounds.x + (it.x.toDouble() * bounds.width / image.width).roundToInt()
                val y = bounds.y + (it.y.toDouble() * bounds.height / image.height).roundToInt()
                val width = (it.width.toDouble() * bounds.width / image.width).roundToInt()
                val height = (it.height.toDouble() * bounds.height / image.height).roundToInt()
                g.color = Color(0, 100, 255, 40)
                g.fillRect(x, y, width, height)
                g.color = Color.BLUE
                g.stroke = BasicStroke(2f)
                g.drawRect(x, y, width, height)
            }
        } finally {
            g.dispose()
        }
    }
}
