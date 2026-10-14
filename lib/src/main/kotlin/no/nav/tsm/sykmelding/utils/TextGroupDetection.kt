package no.nav.tsm.sykmelding.utils

import org.bytedeco.opencv.global.opencv_core
import org.bytedeco.opencv.global.opencv_imgproc
import org.bytedeco.opencv.global.opencv_text
import org.bytedeco.opencv.opencv_core.Mat
import org.bytedeco.opencv.opencv_core.MatVector
import org.bytedeco.opencv.opencv_core.Size
import kotlin.math.roundToInt

/** Pixel settings use 0 for automatic values based on image/glyph size. */
data class TextGroupSettings(
    val horizontalGap: Int = 0,
    val verticalGap: Int = 0,
    val minimumWidth: Int = 0,
    val minimumHeight: Int = 0,
    val removeLines: Boolean = true,
    val minimumLineLength: Int = 0,
) {
    init {
        require(horizontalGap in 0..1000 && verticalGap in 0..1000)
        require(minimumWidth in 0..10000 && minimumHeight in 0..10000 && minimumLineLength in 0..10000)
    }
}

/**
 * Finds candidate text groups (nearby words on a line), without recognizing their contents.
 * Suppresses long form rules, then joins strokes using a kernel scaled to the typical glyph height.
 * Bounds use original image pixels. The source image is not modified.
 */
fun findTextGroups(image: Mat, settings: TextGroupSettings = TextGroupSettings()): List<ContourBox> {

    require(!image.empty()) { "Text detection requires a non-empty image" }
    return grayscale(image).use { gray ->
        Mat().use { ink ->

            opencv_imgproc.threshold(gray, ink, 0.0, 255.0,
                opencv_imgproc.THRESH_BINARY_INV or opencv_imgproc.THRESH_OTSU)
            if (settings.removeLines) {
                val ruleLength = settings.minimumLineLength.takeIf { it > 0 }
                    ?: maxOf(40, minOf(image.cols(), image.rows()) / 15)
                createGridMask(gray, GridSettings(minimumLineLength = ruleLength, gapSize = 0)).use { rules ->
                    opencv_core.subtract(ink, rules, ink)
                }
            }
            val glyphHeights = MatVector().use { contours ->
                Mat().use { hierarchy ->
                    opencv_imgproc.findContours(ink, contours, hierarchy,
                        opencv_imgproc.RETR_EXTERNAL, opencv_imgproc.CHAIN_APPROX_SIMPLE)
                }
                (0 until contours.size()).mapNotNull { index ->
                    opencv_imgproc.boundingRect(contours.get(index)).use { box ->
                        box.height().takeIf {
                            it in 3..maxOf(10, image.rows() / 5) && box.width() in 1..(it * 4)
                        }
                    }
                }.sorted()
            }
            if (glyphHeights.isEmpty()) return emptyList()
            val glyphHeight = glyphHeights[glyphHeights.size / 2]
            // Closing bridges gaps up to kernel size - 1; odd kernels keep the bounds centered.
            val horizontalKernel = if (settings.horizontalGap > 0) (settings.horizontalGap + 1) or 1
                else maxOf(3, (glyphHeight * 1.5).roundToInt()) or 1
            val verticalKernel = if (settings.verticalGap > 0) (settings.verticalGap + 1) or 1
                else maxOf(1, glyphHeight / 4) or 1
            val minimumWidth = settings.minimumWidth.takeIf { it > 0 } ?: glyphHeight
            val minimumHeight = settings.minimumHeight.takeIf { it > 0 } ?: maxOf(3, glyphHeight / 2)
            Size(horizontalKernel, verticalKernel).use { size ->
                opencv_imgproc.getStructuringElement(opencv_imgproc.MORPH_RECT, size).use { kernel ->
                    opencv_imgproc.morphologyEx(ink, ink, opencv_imgproc.MORPH_CLOSE, kernel)
                }
            }
            MatVector().use { contours ->
                Mat().use { hierarchy ->
                    opencv_imgproc.findContours(ink, contours, hierarchy,
                        opencv_imgproc.RETR_EXTERNAL, opencv_imgproc.CHAIN_APPROX_SIMPLE)
                }
                (0 until contours.size()).mapNotNull { index ->
                    val box = opencv_imgproc.boundingRect(contours.get(index))
                    if (box.height() < minimumHeight || box.width() < minimumWidth ||
                        opencv_imgproc.contourArea(contours.get(index)) < glyphHeight.toDouble() * glyphHeight / 4
                    ) {
                        box.close()
                        null
                    } else ContourBox(box, -1)
                }.sortedWith(compareBy({ it.bounds.y() }, { it.bounds.x() }))
            }
        }
    }
}
