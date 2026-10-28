package no.nav.tsm.sykmelding.utils

import no.nav.tsm.OpenCV
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfKeyPoint
import org.opencv.core.MatOfPoint
import org.opencv.core.Rect
import org.opencv.geometry.Geometry
import org.opencv.imgproc.Imgproc
import java.awt.Color
import java.awt.Point
import java.awt.image.BufferedImage
import java.awt.image.DataBufferByte
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

/**
 * Runs [block] on this Mat and then frees its pixels, as [AutoCloseable.use] would: OpenCV's Mat is not closeable, and
 * the garbage collector, which frees it in the end, does not see the native memory it holds.
 */
inline fun <M : Mat, R> M.use(block: (M) -> R): R = try {
    block(this)
} finally {
    release()
}

/** This 8-bit Mat's values, row by row and every channel of a pixel together. */
fun Mat.bytes(): ByteArray = ByteArray((total() * channels()).toInt()).also { get(0, 0, it) }

/** This 32-bit integer Mat's values, row by row and every channel of a pixel together. */
fun Mat.ints(): IntArray = IntArray((total() * channels()).toInt()).also { get(0, 0, it) }

/** An 8-bit gray or BGR [mat] as an image of its own. */
fun toBufferedImage(mat: Mat): BufferedImage {
    require(mat.depth() == CvType.CV_8U) { "Image must have 8-bit pixels" }
    val type = when (mat.channels()) {
        1 -> BufferedImage.TYPE_BYTE_GRAY
        3 -> BufferedImage.TYPE_3BYTE_BGR
        else -> throw IllegalArgumentException("Unsupported image channel count: ${mat.channels()}")
    }
    return BufferedImage(mat.cols(), mat.rows(), type).apply { mat.get(0, 0, (raster.dataBuffer as DataBufferByte).data) }
}

/**
 * Loads OpenCV's native library, which must be loaded before any Mat is made. The lib's entry points call it: [toMat]
 * and TemplateMatcher.
 */
internal fun loadOpenCv() = check(OpenCV.load()) { "Could not load OpenCV's native library" }

/** [bufferedImage] as an 8-bit Mat: gray if it is gray, else BGR with transparency put on white. Loads OpenCV first. */
fun toMat(bufferedImage: BufferedImage): Mat {
    loadOpenCv()
    val gray = bufferedImage.type == BufferedImage.TYPE_BYTE_GRAY
    val pixels = packed(bufferedImage, if (gray) BufferedImage.TYPE_BYTE_GRAY else BufferedImage.TYPE_3BYTE_BGR)
    return Mat(bufferedImage.height, bufferedImage.width, if (gray) CvType.CV_8UC1 else CvType.CV_8UC3).apply {
        put(0, 0, (pixels.raster.dataBuffer as DataBufferByte).data)
    }
}

/** [image] itself when it is [type] without row padding, as OpenCV copies it; else a copy drawn as [type] on white. */
private fun packed(image: BufferedImage, type: Int): BufferedImage {
    val channels = if (type == BufferedImage.TYPE_BYTE_GRAY) 1 else 3
    if (image.type == type && image.raster.parent == null &&
        (image.raster.dataBuffer as DataBufferByte).data.size == image.width * image.height * channels) return image
    return BufferedImage(image.width, image.height, type).apply {
        createGraphics().let { graphics ->
            try {
                if (image.colorModel.hasAlpha()) {
                    graphics.color = Color.WHITE
                    graphics.fillRect(0, 0, width, height)
                }
                graphics.drawImage(image, 0, 0, null)
            } finally {
                graphics.dispose()
            }
        }
    }
}

private fun findCountours(image: Mat, mode: RetreivalMode, method: RetreivalMethod, hierarchy: Mat): List<MatOfPoint> {
    val gray = grayscale(image)
    val binary = Mat()
    Imgproc.threshold(gray, binary, 0.0, 255.0, Imgproc.THRESH_BINARY_INV or Imgproc.THRESH_OTSU)
    val contours = mutableListOf<MatOfPoint>()
    val contourInput = if (mode == RetreivalMode.RETR_FLOODFILL) {
        Mat().also { Imgproc.connectedComponents(binary, it) }
    } else {
        binary
    }
    Imgproc.findContours(contourInput, contours, hierarchy, mode.value, method.value)
    return contours
}
fun grayscale(image: BufferedImage): Mat {
    return toMat(image).use { grayscale(it) }
}
fun grayscale(image: Mat): Mat {
    require(image.depth() == CvType.CV_8U) { "Contour image must have 8-bit pixels" }
    val gray = Mat()
    when (image.channels()) {
        1 -> image.copyTo(gray)
        3 -> Imgproc.cvtColor(image, gray, Imgproc.COLOR_BGR2GRAY)
        4 -> Imgproc.cvtColor(image, gray, Imgproc.COLOR_BGRA2GRAY)
        else -> throw IllegalArgumentException("Unsupported contour image channel count: ${image.channels()}")
    }
    return gray
}

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
    // Next, previous, first child and parent of each contour.
    val links = hierarchy.ints()
    return boxes.mapIndexed { index, box -> ContourBox(box, links[4 * index + 3]) }
}

fun getBoundingBox(mats: List<MatOfPoint>): List<Rect> {
    return mats.map { Geometry.boundingRect(it) }
}
/** ORB finds at most this many keypoints on a page. */
const val ORB_FEATURES = 10_000

enum class FeatureDetectorType {
    ORB, MSER, SIFT, FAST, GFTT, SIMPLE_BLOB;

    /** A new detector, with OpenCV's settings except ORB's number of keypoints: its 500 are few for a form page. */
    fun create(): org.opencv.features.Feature2D = when (this) {
        ORB -> org.opencv.features.ORB.create().apply { setMaxFeatures(ORB_FEATURES) }
        MSER -> org.opencv.features.MSER.create()
        SIFT -> org.opencv.features.SIFT.create()
        FAST -> org.opencv.features.FastFeatureDetector.create()
        GFTT -> org.opencv.features.GFTTDetector.create()
        SIMPLE_BLOB -> org.opencv.features.SimpleBlobDetector.create()
    }
}

fun findFeatures(image: Mat, type: FeatureDetectorType, mask: Mat? = null): List<Point> {
    return grayscale(image).use { gray ->
        MatOfKeyPoint().use { keypoints ->
            val detector = type.create()
            if (mask == null) detector.detect(gray, keypoints) else detector.detect(gray, keypoints, mask)
            keypoints.toArray().map { keypoint ->
                Point(keypoint.pt.x.roundToInt(), keypoint.pt.y.roundToInt())
            }
        }
    }
}
