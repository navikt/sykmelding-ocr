package no.nav.tsm.sykmelding.utils

import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.core.Rect
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

data class GridSettings(
    val minimumLineLength: Int = 0,
    val gapSize: Int = 3,
    val minimumCellSize: Int = 12,
    val inkThreshold: Int = 225,
) {
    init {
        require(minimumLineLength >= 0 && gapSize in 0..100 && minimumCellSize > 0 && inkThreshold in 0..255)
    }
}

enum class GridEditTool(private val label: String) {
    HORIZONTAL("Horizontal line"), VERTICAL("Vertical line"), CELL("Cell border"), ERASE("Erase region");

    override fun toString() = label
}

/** Endpoints are in original image pixels. Edits are replayed in order on the extracted rules. */
data class GridEdit(val tool: GridEditTool, val x1: Int, val y1: Int, val x2: Int, val y2: Int)

/** Returns an owned, white-on-black mask. The original image is never modified. */
fun createGridMask(image: Mat, settings: GridSettings = GridSettings(), edits: List<GridEdit> = emptyList()): Mat {
    val grid = Mat()
    try {
        grayscale(image).use { gray ->
            Mat().use { ink ->
                Imgproc.threshold(gray, ink, settings.inkThreshold.toDouble(), 255.0, Imgproc.THRESH_BINARY_INV)
                val length = settings.minimumLineLength.takeIf { it > 0 }
                    ?: maxOf(15, minOf(image.cols(), image.rows()) / 40)
                Mat().use { horizontal ->
                    Mat().use { vertical ->
                        extractRules(ink, horizontal, length, settings.gapSize, true)
                        extractRules(ink, vertical, length, settings.gapSize, false)
                        Core.bitwise_or(horizontal, vertical, grid)
                    }
                }
            }
        }
        edits.forEach { applyGridEdit(grid, it) }
        return grid
    } catch (error: Throwable) {
        grid.release()
        throw error
    }
}

private fun extractRules(ink: Mat, target: Mat, length: Int, gap: Int, horizontal: Boolean) {
    fun kernel(size: Int) = Imgproc.getStructuringElement(Imgproc.MORPH_RECT,
        Size(if (horizontal) size.toDouble() else 1.0, if (horizontal) 1.0 else size.toDouble()))
    // Remove short strokes before bridging gaps, otherwise letters can join into false rules.
    kernel(length).use { Imgproc.morphologyEx(ink, target, Imgproc.MORPH_OPEN, it) }
    if (gap > 0) {
        kernel(2 * gap + 1).use {
            Imgproc.morphologyEx(target, target, Imgproc.MORPH_CLOSE, it)
            // Extend endpoints only along their rule, so near-miss T junctions can close.
            Imgproc.dilate(target, target, it)
        }
    }
}

private fun applyGridEdit(grid: Mat, edit: GridEdit) {
    val x1 = edit.x1.coerceIn(0, grid.cols() - 1)
    val y1 = edit.y1.coerceIn(0, grid.rows() - 1)
    val x2 = edit.x2.coerceIn(0, grid.cols() - 1)
    val y2 = edit.y2.coerceIn(0, grid.rows() - 1)
    val color = Scalar.all(if (edit.tool == GridEditTool.ERASE) 0.0 else 255.0)
    when (edit.tool) {
        GridEditTool.HORIZONTAL, GridEditTool.VERTICAL -> {
            val end = Point((if (edit.tool == GridEditTool.VERTICAL) x1 else x2).toDouble(),
                (if (edit.tool == GridEditTool.HORIZONTAL) y1 else y2).toDouble())
            Imgproc.line(grid, Point(x1.toDouble(), y1.toDouble()), end, color, 2, Imgproc.LINE_8, 0)
        }
        GridEditTool.CELL, GridEditTool.ERASE -> Imgproc.rectangle(grid,
            Point(minOf(x1, x2).toDouble(), minOf(y1, y2).toDouble()),
            Point(maxOf(x1, x2).toDouble(), maxOf(y1, y2).toDouble()), color,
            if (edit.tool == GridEditTool.ERASE) Imgproc.FILLED else 2, Imgproc.LINE_8, 0)
    }
}

/**
 * Cells are the background regions enclosed by rules (OpenCV's connectedComponentsWithStats on the inverted mask).
 * Regions that reach the image edge are open, not cells. Bounds include the surrounding rule pixels, as the cell's
 * inner border would.
 */
fun findGridCells(grid: Mat, minimumCellSize: Int = 12): List<ContourBox> {
    require(minimumCellSize > 0)
    return Mat().use { background ->
        Core.bitwise_not(grid, background)
        // Like findContours, ignore rules on the outermost pixel row/column: regions they close are open.
        Imgproc.rectangle(background, Rect(0, 0, grid.cols(), grid.rows()), Scalar.all(255.0), 1, Imgproc.LINE_8, 0)
        // A 4-connected background matches 8-connected rules: a diagonal rule step still closes a cell.
        Mat().use { labels ->
            Mat().use { stats ->
                Mat().use { centroids ->
                    val count = Imgproc.connectedComponentsWithStats(background, labels, stats, centroids, 4, CvType.CV_32S)
                    val values = stats.ints()
                    (1 until count).mapNotNull { label ->
                        fun stat(column: Int) = values[label * stats.cols() + column]
                        val x = stat(Imgproc.CC_STAT_LEFT)
                        val y = stat(Imgproc.CC_STAT_TOP)
                        val width = stat(Imgproc.CC_STAT_WIDTH)
                        val height = stat(Imgproc.CC_STAT_HEIGHT)
                        val open = x == 0 || y == 0 || x + width == grid.cols() || y + height == grid.rows()
                        if (open || width + 2 < minimumCellSize || height + 2 < minimumCellSize) null
                        else ContourBox(Rect(x - 1, y - 1, width + 2, height + 2), -1)
                    }.sortedWith(compareBy({ it.bounds.y }, { it.bounds.x }))
                }
            }
        }
    }
}
