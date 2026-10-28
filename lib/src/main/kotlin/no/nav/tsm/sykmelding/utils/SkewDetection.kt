package no.nav.tsm.sykmelding.utils

import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.core.Rect
import org.opencv.core.Size
import org.opencv.geometry.Geometry
import org.opencv.imgproc.Imgproc
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * Clockwise tilt of a page's text rows and form lines, in degrees (within ±[maxDegrees]). A Radon transform, as
 * OpenCV contrib's RadonTransform does it, sums the ink along lines at each angle; rows give the sharpest profile
 * (largest sum of squares) when the angle follows them. 90° is horizontal.
 */
fun estimateSkew(image: Mat, maxDegrees: Double = 10.0): Double {
    val scale = minOf(1.0, 1200.0 / maxOf(image.cols(), image.rows()))
    val size = Size((image.cols() * scale).roundToInt().coerceAtLeast(1).toDouble(), (image.rows() * scale).roundToInt().coerceAtLeast(1).toDouble())
    return Mat().use { ink ->
        grayscale(image).use { gray -> Imgproc.resize(gray, ink, size, 0.0, 0.0, Imgproc.INTER_AREA) }
        Imgproc.threshold(ink, ink, 0.0, 255.0, Imgproc.THRESH_BINARY_INV or Imgproc.THRESH_OTSU)
        if (Core.countNonZero(ink) < 100) return 0.0
        // turned and rows are reused for every angle: OpenCV writes into them in place once they have their size.
        radonCanvas(ink).use { canvas ->
            Mat().use { turned ->
                Mat().use { rows ->
                    fun sharpest(from: Double, to: Double, step: Double): Double = (0..((to - from) / step).roundToInt())
                        .map { from + it * step }.maxBy { profileSharpness(canvas, it, turned, rows) }
                    val coarse = sharpest(90 - maxDegrees, 90 + maxDegrees, 0.25)
                    90 - sharpest(coarse - 0.25, coarse + 0.25, 0.025)
                }
            }
        }
    }
}

/** [ink] as RadonTransform turns it: transposed, centred on a black square as wide as its diagonal, so no ink turns out. */
private fun radonCanvas(ink: Mat): Mat {
    val side = ceil(hypot(ink.cols().toDouble(), ink.rows().toDouble())).toInt()
    val canvas = Mat.zeros(side, side, CvType.CV_8UC1)
    Mat().use { transposed ->
        Core.transpose(ink, transposed)
        canvas.submat(Rect((side - transposed.cols()) / 2, (side - transposed.rows()) / 2, transposed.cols(), transposed.rows()))
            .use { transposed.copyTo(it) }
    }
    return canvas
}

/**
 * How sharp the profile of [canvas]'s row sums is when it is turned [degrees] counter-clockwise about its centre: the
 * root of their sum of squares. RadonTransform's column for that angle, squared and summed. [turned] and [rows] are
 * scratch space.
 */
private fun profileSharpness(canvas: Mat, degrees: Double, turned: Mat, rows: Mat): Double {
    val centre = Point((canvas.cols() / 2).toDouble(), (canvas.rows() / 2).toDouble())
    Geometry.getRotationMatrix2D(centre, degrees, 1.0).use { rotation -> Imgproc.warpAffine(canvas, turned, rotation, canvas.size()) }
    Core.reduce(turned, rows, 1, Core.REDUCE_SUM, CvType.CV_64F)
    return Core.norm(rows)
}
