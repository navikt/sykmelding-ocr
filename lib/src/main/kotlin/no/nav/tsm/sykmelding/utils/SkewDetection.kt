package no.nav.tsm.sykmelding.utils

import org.bytedeco.javacpp.PointerScope
import org.bytedeco.opencv.global.opencv_core
import org.bytedeco.opencv.global.opencv_imgproc
import org.bytedeco.opencv.global.opencv_ximgproc
import org.bytedeco.opencv.opencv_core.Mat
import org.bytedeco.opencv.opencv_core.Point
import org.bytedeco.opencv.opencv_core.Size
import kotlin.math.roundToInt

/**
 * Clockwise tilt of a page's text rows and form lines, in degrees (within ±[maxDegrees]). OpenCV's RadonTransform
 * sums the ink along lines at each angle; rows give the sharpest profile (largest sum of squares) when the angle
 * follows them. 90° is horizontal.
 */
fun estimateSkew(image: Mat, maxDegrees: Double = 10.0): Double = PointerScope().use {
    val scale = minOf(1.0, 1200.0 / maxOf(image.cols(), image.rows()))
    val ink = Mat()
    Size((image.cols() * scale).roundToInt().coerceAtLeast(1), (image.rows() * scale).roundToInt().coerceAtLeast(1)).let { size ->
        grayscale(image).use { gray -> opencv_imgproc.resize(gray, ink, size, 0.0, 0.0, opencv_imgproc.INTER_AREA) }
    }
    opencv_imgproc.threshold(ink, ink, 0.0, 255.0, opencv_imgproc.THRESH_BINARY_INV or opencv_imgproc.THRESH_OTSU)
    if (opencv_core.countNonZero(ink) < 100) return@use 0.0
    fun sharpest(from: Double, to: Double, step: Double): Double {
        val projections = Mat()
        // The end angle is exclusive.
        opencv_ximgproc.RadonTransform(ink, projections, step, from, to + step / 2, false, false)
        val values = Mat().also { projections.convertTo(it, opencv_core.CV_64F) }
        val squares = Mat().also { opencv_core.multiply(values, values, it) }
        val sharpness = Mat().also { opencv_core.reduce(squares, it, 0, opencv_core.REDUCE_SUM, opencv_core.CV_64F) }
        val best = Point()
        opencv_core.minMaxLoc(sharpness, null as DoubleArray?, null, null, best, null)
        return from + best.x() * step
    }
    val coarse = sharpest(90 - maxDegrees, 90 + maxDegrees, 0.25)
    90 - sharpest(coarse - 0.25, coarse + 0.25, 0.025)
}
