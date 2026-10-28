package org.example.no.nav.tsm.ocr.template.template

import no.nav.tsm.sykmelding.utils.GridEdit
import no.nav.tsm.sykmelding.utils.GridSettings
import no.nav.tsm.sykmelding.utils.createGridMask
import no.nav.tsm.sykmelding.utils.toMat
import org.bytedeco.javacpp.indexer.UByteIndexer
import org.bytedeco.opencv.global.opencv_core.CV_8UC1
import org.bytedeco.opencv.global.opencv_imgproc
import org.bytedeco.opencv.opencv_core.Mat
import org.bytedeco.opencv.opencv_core.Rect
import org.bytedeco.opencv.opencv_core.Scalar
import java.awt.Color
import java.awt.Rectangle
import java.awt.image.BufferedImage

/** An image-editing region that can be cleared while preserving its printed border. */
internal data class ImageClearArea(val bounds: Rectangle, val preserveBorders: Boolean = false)

private fun createRegionMask(
    source: Mat,
    areas: List<ImageClearArea>,
    gridSettings: GridSettings,
    gridEdits: List<GridEdit>,
): Mat {
    val result = Scalar.all(255.0).use { Mat(source.rows(), source.cols(), CV_8UC1, it) }
    try {
        val bordered = areas.filter { it.preserveBorders }
        if (bordered.isNotEmpty()) {
            createGridMask(source, gridSettings.copy(gapSize = 0), gridEdits).use { rules ->
                rules.createIndexer<UByteIndexer>().use { ink ->
                    result.createIndexer<UByteIndexer>().use { pixels ->
                        bordered.forEach { area ->
                            val box = area.bounds.intersection(Rectangle(0, 0, source.cols(), source.rows()))
                            if (!box.isEmpty) {
                                fun thickness(x: Int, y: Int, dx: Int, dy: Int): Int {
                                    var px = x
                                    var py = y
                                    var length = 0
                                    while (box.contains(px, py) && ink.get(py.toLong(), px.toLong()) != 0) {
                                        length++
                                        px += dx
                                        py += dy
                                    }
                                    return length
                                }
                                fun edge(values: List<Int>, dimension: Int): Int {
                                    val median = values.sorted()[values.size / 2]
                                    return if (median >= dimension / 2) 1 else maxOf(1, median)
                                }
                                val xs = box.x until box.x + box.width
                                val ys = box.y until box.y + box.height
                                val top = edge(xs.map { thickness(it, box.y, 0, 1) }, box.height)
                                val bottom = edge(xs.map { thickness(it, box.y + box.height - 1, 0, -1) }, box.height)
                                val left = edge(ys.map { thickness(box.x, it, 1, 0) }, box.width)
                                val right = edge(ys.map { thickness(box.x + box.width - 1, it, -1, 0) }, box.width)
                                for (y in box.y + top until box.y + box.height - bottom) {
                                    for (x in box.x + left until box.x + box.width - right) {
                                        pixels.put(y.toLong(), x.toLong(), 0)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        Scalar.all(0.0).use { black ->
            areas.filterNot { it.preserveBorders }.forEach { area ->
                val box = area.bounds.intersection(Rectangle(0, 0, source.cols(), source.rows()))
                if (!box.isEmpty) Rect(box.x, box.y, box.width, box.height).use {
                    opencv_imgproc.rectangle(result, it, black, opencv_imgproc.FILLED, opencv_imgproc.LINE_8, 0)
                }
            }
        }
        return result
    } catch (error: Throwable) {
        result.close()
        throw error
    }
}

/** Clear on a detached copy, keeping the imported image intact. */
internal fun clearTemplateAreas(
    source: BufferedImage,
    areas: List<ImageClearArea>,
    gridSettings: GridSettings = GridSettings(),
    gridEdits: List<GridEdit> = emptyList(),
): BufferedImage =
    BufferedImage(source.width, source.height, BufferedImage.TYPE_INT_RGB).apply {
        createGraphics().let { graphics ->
            try {
                graphics.color = Color.WHITE
                graphics.fillRect(0, 0, width, height)
                graphics.drawImage(source, 0, 0, null)
            } finally {
                graphics.dispose()
            }
        }
        if (areas.isNotEmpty()) toMat(source).use { mat ->
            createRegionMask(mat, areas, gridSettings, gridEdits).use { mask ->
                mask.createIndexer<UByteIndexer>().use { pixels ->
                    areas.forEach { area ->
                        val box = area.bounds.intersection(Rectangle(0, 0, width, height))
                        for (y in box.y until box.y + box.height) for (x in box.x until box.x + box.width) {
                            if (pixels.get(y.toLong(), x.toLong()) == 0) setRGB(x, y, Color.WHITE.rgb)
                        }
                    }
                }
            }
        }
    }
