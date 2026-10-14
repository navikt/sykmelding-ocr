package no.nav.tsm.sykmelding.utils

import org.bytedeco.javacpp.indexer.IntIndexer
import org.bytedeco.javacv.Java2DFrameConverter
import org.bytedeco.javacv.OpenCVFrameConverter
import org.bytedeco.opencv.global.opencv_core.CV_8U
import org.bytedeco.opencv.global.opencv_imgproc
import org.bytedeco.opencv.opencv_core.Mat
import org.bytedeco.opencv.opencv_core.MatVector
import org.bytedeco.opencv.opencv_core.Rect
import java.awt.Point
import java.awt.image.BufferedImage
import kotlin.math.roundToInt

data class ContourBox(val bounds: Rect, val parentIndex: Int)

enum class RetreivalMode(val value: Int) {
    RETR_EXTERNAL(0),
    RETR_LIST(1),
    RETR_CCOMP(2),
    RETR_TREE(3),
    RETR_FLOODFILL(4)
}

enum class RetreivalMethod(val value: Int) {
    CHAIN_APPROX_NONE(1),
    CHAIN_APPROX_SIMPLE(2),
    CHAIN_APPROX_TC89_L1(3),
    CHAIN_APPROX_TC89_KCOS(4)
}

fun toBufferedImage(mat: Mat): BufferedImage {
    return Java2DFrameConverter().convert(OpenCVFrameConverter.ToMat().convert(mat))
}

fun toMat(bufferedImage: BufferedImage): Mat {
    return OpenCVFrameConverter.ToMat().convert(Java2DFrameConverter().convert(bufferedImage))
}

fun findCountours(
    image: Mat,
    mode: RetreivalMode = RetreivalMode.RETR_EXTERNAL,
    method: RetreivalMethod = RetreivalMethod.CHAIN_APPROX_SIMPLE,
): MatVector = findCountours(image, mode, method, Mat())

private fun findCountours(image: Mat, mode: RetreivalMode, method: RetreivalMethod, hierarchy: Mat): MatVector {
    val gray = grayscale(image)
    val binary = Mat()
    opencv_imgproc.threshold(gray, binary, 0.0, 255.0, opencv_imgproc.THRESH_BINARY_INV or opencv_imgproc.THRESH_OTSU)
    val contours = MatVector()
    val contourInput = if (mode == RetreivalMode.RETR_FLOODFILL) {
        Mat().also { opencv_imgproc.connectedComponents(binary, it) }
    } else {
        binary
    }
    opencv_imgproc.findContours(contourInput, contours, hierarchy, mode.value, method.value)
    return contours
}

internal fun grayscale(image: Mat): Mat {
    require(image.depth() == CV_8U) { "Contour image must have 8-bit pixels" }
    val gray = Mat()
    when (image.channels()) {
        1 -> image.copyTo(gray)
        3 -> opencv_imgproc.cvtColor(image, gray, opencv_imgproc.COLOR_BGR2GRAY)
        4 -> opencv_imgproc.cvtColor(image, gray, opencv_imgproc.COLOR_BGRA2GRAY)
        else -> throw IllegalArgumentException("Unsupported contour image channel count: ${image.channels()}")
    }
    return gray
}

/** Detect cells from rules rather than from all ink (which also contains text). */
fun findCellBoxes(
    image: Mat,
    settings: GridSettings = GridSettings(),
    edits: List<GridEdit> = emptyList(),
): List<ContourBox> = createGridMask(image, settings, edits).use { findGridCells(it, settings.minimumCellSize) }

fun findContourBoxes(
    image: Mat,
    mode: RetreivalMode = RetreivalMode.RETR_EXTERNAL,
    method: RetreivalMethod = RetreivalMethod.CHAIN_APPROX_SIMPLE,
): List<ContourBox> {
    val hierarchy = Mat()
    val boxes = getBoundingBox(findCountours(image, mode, method, hierarchy))
    if (mode != RetreivalMode.RETR_TREE || boxes.isEmpty()) {
        return boxes.map { ContourBox(it, -1) }
    }
    return hierarchy.createIndexer<IntIndexer>().use { indexer ->
        boxes.mapIndexed { index, box ->
            ContourBox(box, indexer.get(0L, index.toLong(), 3L))
        }
    }
}

fun getBoundingBox(mats: MatVector): List<Rect> {
    return (0 until mats.size()).map {
        opencv_imgproc.boundingRect(mats.get(it))
    }
}
enum class FeatureDetectorType {
    ORB, KAZE, AKAZE, MSER, SIFT, BRISK, FAST, AGAST, GFTT, SIMPLE_BLOB;

    fun create(): org.bytedeco.opencv.opencv_features2d.Feature2D = when (this) {
        ORB -> org.bytedeco.opencv.opencv_features2d.ORB.create()
        KAZE -> org.bytedeco.opencv.opencv_features2d.KAZE.create()
        AKAZE -> org.bytedeco.opencv.opencv_features2d.AKAZE.create()
        MSER -> org.bytedeco.opencv.opencv_features2d.MSER.create()
        SIFT -> org.bytedeco.opencv.opencv_features2d.SIFT.create()
        BRISK -> org.bytedeco.opencv.opencv_features2d.BRISK.create()
        FAST -> org.bytedeco.opencv.opencv_features2d.FastFeatureDetector.create()
        AGAST -> org.bytedeco.opencv.opencv_features2d.AgastFeatureDetector.create()
        GFTT -> org.bytedeco.opencv.opencv_features2d.GFTTDetector.create()
        SIMPLE_BLOB -> org.bytedeco.opencv.opencv_features2d.SimpleBlobDetector.create()
    }
}

fun findFeatures(image: Mat, type: FeatureDetectorType, mask: Mat? = null): List<Point> {
    return grayscale(image).use { gray ->
        org.bytedeco.opencv.opencv_core.KeyPointVector().use { keypoints ->
            type.create().use { detector ->
                if (mask == null) detector.detect(gray, keypoints) else detector.detect(gray, keypoints, mask)
            }
            (0 until keypoints.size()).map { i ->
                val pt = keypoints.get(i).pt()
                Point(pt.x().roundToInt(), pt.y().roundToInt())
            }
        }
    }
}
