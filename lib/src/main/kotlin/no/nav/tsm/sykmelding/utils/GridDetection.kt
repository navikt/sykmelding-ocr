package no.nav.tsm.sykmelding.utils

import org.bytedeco.javacpp.indexer.IntIndexer
import org.bytedeco.opencv.global.opencv_core
import org.bytedeco.opencv.global.opencv_imgproc
import org.bytedeco.opencv.opencv_core.Mat
import org.bytedeco.opencv.opencv_core.Point
import org.bytedeco.opencv.opencv_core.Rect
import org.bytedeco.opencv.opencv_core.Scalar
import org.bytedeco.opencv.opencv_core.Size

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
                opencv_imgproc.threshold(gray, ink, settings.inkThreshold.toDouble(), 255.0, opencv_imgproc.THRESH_BINARY_INV)
                val length = settings.minimumLineLength.takeIf { it > 0 }
                    ?: maxOf(15, minOf(image.cols(), image.rows()) / 40)
                Mat().use { horizontal ->
                    Mat().use { vertical ->
                        extractRules(ink, horizontal, length, settings.gapSize, true)
                        extractRules(ink, vertical, length, settings.gapSize, false)
                        opencv_core.bitwise_or(horizontal, vertical, grid)
                    }
                }
            }
        }
        edits.forEach { applyGridEdit(grid, it) }
        return grid
    } catch (error: Throwable) {
        grid.close()
        throw error
    }
}

private fun extractRules(ink: Mat, target: Mat, length: Int, gap: Int, horizontal: Boolean) {
    fun kernel(size: Int) = Size(if (horizontal) size else 1, if (horizontal) 1 else size).use {
        opencv_imgproc.getStructuringElement(opencv_imgproc.MORPH_RECT, it)
    }
    // Remove short strokes before bridging gaps, otherwise letters can join into false rules.
    kernel(length).use { opencv_imgproc.morphologyEx(ink, target, opencv_imgproc.MORPH_OPEN, it) }
    if (gap > 0) {
        kernel(2 * gap + 1).use {
            opencv_imgproc.morphologyEx(target, target, opencv_imgproc.MORPH_CLOSE, it)
            // Extend endpoints only along their rule, so near-miss T junctions can close.
            opencv_imgproc.dilate(target, target, it)
        }
    }
}

private fun applyGridEdit(grid: Mat, edit: GridEdit) {
    val x1 = edit.x1.coerceIn(0, grid.cols() - 1)
    val y1 = edit.y1.coerceIn(0, grid.rows() - 1)
    val x2 = edit.x2.coerceIn(0, grid.cols() - 1)
    val y2 = edit.y2.coerceIn(0, grid.rows() - 1)
    Point(x1, y1).use { start ->
        Point(if (edit.tool == GridEditTool.VERTICAL) x1 else x2,
            if (edit.tool == GridEditTool.HORIZONTAL) y1 else y2).use { end ->
            Scalar.all(if (edit.tool == GridEditTool.ERASE) 0.0 else 255.0).use { color ->
                when (edit.tool) {
                    GridEditTool.HORIZONTAL, GridEditTool.VERTICAL -> opencv_imgproc.line(grid, start, end, color, 2, opencv_imgproc.LINE_8, 0)
                    GridEditTool.CELL, GridEditTool.ERASE -> {
                        start.x(minOf(x1, x2)).y(minOf(y1, y2))
                        end.x(maxOf(x1, x2)).y(maxOf(y1, y2))
                        opencv_imgproc.rectangle(grid, start, end, color,
                            if (edit.tool == GridEditTool.ERASE) opencv_imgproc.FILLED else 2, opencv_imgproc.LINE_8, 0)
                    }
                }
            }
        }
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
        opencv_core.bitwise_not(grid, background)
        // Like findContours, ignore rules on the outermost pixel row/column: regions they close are open.
        Rect(0, 0, grid.cols(), grid.rows()).use { edge ->
            Scalar.all(255.0).use { opencv_imgproc.rectangle(background, edge, it, 1, opencv_imgproc.LINE_8, 0) }
        }
        // A 4-connected background matches 8-connected rules: a diagonal rule step still closes a cell.
        Mat().use { labels ->
            Mat().use { stats ->
                Mat().use { centroids ->
                    val count = opencv_imgproc.connectedComponentsWithStats(background, labels, stats, centroids, 4, opencv_core.CV_32S)
                    stats.createIndexer<IntIndexer>().use { values ->
                        (1 until count).mapNotNull { label ->
                            fun stat(column: Int) = values.get(label.toLong(), column.toLong())
                            val x = stat(opencv_imgproc.CC_STAT_LEFT)
                            val y = stat(opencv_imgproc.CC_STAT_TOP)
                            val width = stat(opencv_imgproc.CC_STAT_WIDTH)
                            val height = stat(opencv_imgproc.CC_STAT_HEIGHT)
                            val open = x == 0 || y == 0 || x + width == grid.cols() || y + height == grid.rows()
                            if (open || width + 2 < minimumCellSize || height + 2 < minimumCellSize) null
                            else ContourBox(Rect(x - 1, y - 1, width + 2, height + 2), -1)
                        }.sortedWith(compareBy({ it.bounds.y() }, { it.bounds.x() }))
                    }
                }
            }
        }
    }
}
