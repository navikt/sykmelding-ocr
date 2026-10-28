package no.nav.tsm.sykmelding.utils

import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.Size
import org.opencv.geometry.Geometry
import org.opencv.imgproc.Imgproc
import kotlin.math.abs

/** A rule's centerline and its local extent, in original image pixels. */
data class GridSnapLine(val horizontal: Boolean, val coordinate: Int, val start: Int, val end: Int)

/** Lines align with parallel rules and connect their endpoints to perpendicular rules. */
fun snapGridEdit(edit: GridEdit, lines: List<GridSnapLine>, toleranceX: Int, toleranceY: Int): GridEdit {
    require(toleranceX >= 0 && toleranceY >= 0)
    if (edit.tool == GridEditTool.CELL) return snapCellToGrid(edit, lines, toleranceX, toleranceY)
    if (edit.tool == GridEditTool.ERASE) return edit
    val horizontal = edit.tool == GridEditTool.HORIZONTAL
    val coordinate = if (horizontal) edit.y1 else edit.x1
    val start = if (horizontal) edit.x1 else edit.y1
    val end = if (horizontal) edit.x2 else edit.y2
    if (start == end) return edit
    val alongTolerance = if (horizontal) toleranceX else toleranceY
    val acrossTolerance = if (horizontal) toleranceY else toleranceX
    val aligned = lines.asSequence()
        .filter { it.horizontal == horizontal && abs(it.coordinate - coordinate) <= acrossTolerance }
        // Also allow continuing a rule whose endpoint is just short of the new line.
        .filter { it.end + alongTolerance >= minOf(start, end) && it.start - alongTolerance <= maxOf(start, end) }
        .minWithOrNull(compareBy<GridSnapLine> { abs(it.coordinate - coordinate) }.thenBy { it.coordinate })
        ?.coordinate ?: coordinate
    fun endpoint(value: Int): Int = lines.asSequence()
        .filter { it.horizontal != horizontal && abs(it.coordinate - value) <= alongTolerance }
        .filter { aligned in it.start..it.end }
        .minWithOrNull(compareBy<GridSnapLine> { abs(it.coordinate - value) }.thenBy { it.coordinate })
        ?.coordinate ?: value
    val snappedStart = endpoint(start)
    val snappedEnd = endpoint(end)
    val from = if (snappedStart == snappedEnd) start else snappedStart
    val to = if (snappedStart == snappedEnd) end else snappedEnd
    return if (horizontal) edit.copy(x1 = from, y1 = aligned, x2 = to, y2 = aligned)
        else edit.copy(x1 = aligned, y1 = from, x2 = aligned, y2 = to)
}

/** Extract from the final mask so added and erased rules are reflected in snapping. */
fun findGridSnapLines(grid: Mat, minimumLength: Int): List<GridSnapLine> {
    require(minimumLength > 0)
    val length = maxOf(3, minimumLength) or 1
    return listOf(true, false).flatMap { horizontal ->
        Mat().use { rules ->
            val size = Size(if (horizontal) length.toDouble() else 1.0, if (horizontal) 1.0 else length.toDouble())
            Imgproc.getStructuringElement(Imgproc.MORPH_RECT, size).use { kernel ->
                Imgproc.morphologyEx(grid, rules, Imgproc.MORPH_OPEN, kernel)
            }
            val contours = mutableListOf<MatOfPoint>()
            Mat().use { hierarchy ->
                Imgproc.findContours(rules, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)
            }
            contours.mapNotNull { contour ->
                val box = contour.use { Geometry.boundingRect(it) }
                val extent = if (horizontal) box.width else box.height
                val thickness = if (horizontal) box.height else box.width
                if (extent < length || thickness >= length) null
                else {
                    val start = if (horizontal) box.x else box.y
                    val coordinate = if (horizontal) box.y else box.x
                    GridSnapLine(horizontal, coordinate + (thickness - 1) / 2, start, start + extent - 1)
                }
            }
        }
    }
}

/** Snap each cell edge only to nearby, overlapping rules; keep drag direction and reject collapse. */
fun snapCellToGrid(edit: GridEdit, lines: List<GridSnapLine>, toleranceX: Int, toleranceY: Int): GridEdit {
    require(toleranceX >= 0 && toleranceY >= 0)
    if (edit.tool != GridEditTool.CELL || edit.x1 == edit.x2 || edit.y1 == edit.y2) return edit
    fun snap(coordinate: Int, horizontal: Boolean, start: Int, end: Int, tolerance: Int): Int = lines
        .asSequence()
        .filter { it.horizontal == horizontal && abs(it.coordinate - coordinate) <= tolerance }
        .filter { it.end >= minOf(start, end) && it.start <= maxOf(start, end) }
        .minWithOrNull(compareBy<GridSnapLine> { abs(it.coordinate - coordinate) }
            .thenByDescending { minOf(it.end, maxOf(start, end)) - maxOf(it.start, minOf(start, end)) }
            .thenBy { it.coordinate })?.coordinate ?: coordinate

    var x1 = snap(edit.x1, false, edit.y1, edit.y2, toleranceX)
    var x2 = snap(edit.x2, false, edit.y1, edit.y2, toleranceX)
    var y1 = snap(edit.y1, true, edit.x1, edit.x2, toleranceY)
    var y2 = snap(edit.y2, true, edit.x1, edit.x2, toleranceY)
    if (x1 == x2) { x1 = edit.x1; x2 = edit.x2 }
    if (y1 == y2) { y1 = edit.y1; y2 = edit.y2 }
    return edit.copy(x1 = x1, y1 = y1, x2 = x2, y2 = y2)
}
