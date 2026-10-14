package org.example.no.nav.tsm.ocr.template.template

import no.nav.tsm.sykmelding.utils.estimateSkew
import org.bytedeco.javacpp.BytePointer
import org.bytedeco.javacpp.FloatPointer
import org.bytedeco.javacpp.IntPointer
import org.bytedeco.javacpp.PointerPointer
import org.bytedeco.opencv.global.opencv_core.CV_8UC1
import org.bytedeco.opencv.opencv_core.Mat
import org.bytedeco.tesseract.TessBaseAPI
import org.bytedeco.tesseract.global.tesseract.PSM_OSD_ONLY
import java.awt.Color
import java.awt.RenderingHints
import java.awt.geom.AffineTransform
import java.awt.image.BufferedImage
import java.awt.image.DataBufferByte
import java.nio.file.Files
import kotlin.math.abs

/**
 * How to turn a page upright: [quarterTurns] clockwise quarter turns (exact), then [degrees] more clockwise (negative =
 * counter-clockwise) to straighten a scan that is slightly tilted. The canvas follows the quarter turns; the small
 * rotation keeps it and fills the corners with white.
 */
data class PageRotation(val quarterTurns: Int = 0, val degrees: Double = 0.0) {
    init {
        require(degrees in -45.0..45.0) { "Straighten by at most 45°; turn the page for more." }
    }

    private val turns: Int get() = Math.floorMod(quarterTurns, 4)

    fun isNone(): Boolean = turns == 0 && abs(degrees) < MIN_DEGREES

    fun turnedRight(): PageRotation = copy(quarterTurns = Math.floorMod(turns + 1, 4))

    fun turnedLeft(): PageRotation = copy(quarterTurns = Math.floorMod(turns + 3, 4))

    fun describe(): String = listOfNotNull(
        when (turns) { 1 -> "turned 90° right"; 2 -> "turned 180°"; 3 -> "turned 90° left"; else -> null },
        if (abs(degrees) < MIN_DEGREES) null else "straightened %.1f° %s".format(abs(degrees), if (degrees > 0) "right" else "left"),
    ).joinToString(", ").ifEmpty { "not rotated" }

    /** A rotated copy of [image]; [image] itself when there is nothing to do. */
    fun apply(image: BufferedImage): BufferedImage {
        if (isNone()) return image
        val turned = if (turns == 0) image else quarterTurn(image, turns)
        return if (abs(degrees) < MIN_DEGREES) turned else rotate(turned, degrees)
    }

    private companion object {
        /** Smaller rotations are not worth blurring the page for: 0.05° moves the far edge of an A4 page by about 4 px at 400 DPI. */
        const val MIN_DEGREES = 0.05

        /** Pixel-exact: nearest-neighbour sampling of a 90° multiple only moves pixels. */
        fun quarterTurn(image: BufferedImage, turns: Int): BufferedImage {
            val width = if (turns % 2 == 0) image.width else image.height
            val height = if (turns % 2 == 0) image.height else image.width
            val transform = AffineTransform().apply {
                when (turns) {
                    1 -> { translate(image.height.toDouble(), 0.0); quadrantRotate(1) }
                    2 -> { translate(image.width.toDouble(), image.height.toDouble()); quadrantRotate(2) }
                    else -> { translate(0.0, image.width.toDouble()); quadrantRotate(3) }
                }
            }
            return BufferedImage(width, height, outputType(image)).apply {
                createGraphics().let { g -> try { g.drawImage(image, transform, null) } finally { g.dispose() } }
            }
        }

        /** Rotates [image] about its centre by [degrees] clockwise, on a white canvas of the same size. */
        fun rotate(image: BufferedImage, degrees: Double): BufferedImage = BufferedImage(image.width, image.height, outputType(image)).apply {
            createGraphics().let { g ->
                try {
                    g.color = Color.WHITE
                    g.fillRect(0, 0, width, height)
                    g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
                    g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
                    g.rotate(Math.toRadians(degrees), image.width / 2.0, image.height / 2.0)
                    g.drawImage(image, 0, 0, null)
                } finally { g.dispose() }
            }
        }

        fun outputType(image: BufferedImage) = if (image.type == BufferedImage.TYPE_BYTE_GRAY) BufferedImage.TYPE_BYTE_GRAY else BufferedImage.TYPE_INT_RGB
    }
}

/**
 * What [PageOrientation.detect] found: the [rotation] that turns the page upright and straight. [orientation] is how
 * far the page is turned clockwise as Tesseract read it (0, 90, 180 or 270), with [confidence]; null when there was
 * too little text to tell. [skew] is how far the upright page is tilted clockwise, in degrees.
 */
class OrientationGuess(val rotation: PageRotation, val orientation: Int?, val confidence: Float?, val skew: Double) {
    val description: String
        get() = when {
            orientation == null -> "Could not tell which way up the page is (too little text); " +
                if (rotation.isNone()) "it is straight." else "${rotation.describe()}."
            rotation.isNone() -> "The page is upright and straight."
            else -> rotation.describe().replaceFirstChar { it.uppercase() } + "."
        }
}

/**
 * Finds which way up a page is: Tesseract's orientation and script detection (OSD, osd.traineddata) for quarter
 * turns, then [estimateSkew] on the upright page for the small tilt of a scan. About a third of a second for a page at
 * 300 DPI. The OSD engine is created on first use and shared; detections run one at a time.
 */
object PageOrientation {
    /** Tesseract's orientation confidence below which the page is left as it is; upright form pages score about 12. */
    const val MIN_CONFIDENCE = 3f

    private val engine: TessBaseAPI by lazy {
        val directory = Files.createTempDirectory("tessdata-osd")
        val model = directory.resolve("osd.traineddata")
        val stream = PageOrientation::class.java.getResourceAsStream("/tessdata/osd.traineddata")
            ?: error("Missing /tessdata/osd.traineddata on the classpath")
        stream.use { Files.copy(it, model) }
        listOf(model, directory).forEach { it.toFile().deleteOnExit() }
        TessBaseAPI().apply {
            check(Init(directory.toString(), "osd") == 0) { "Could not start Tesseract's orientation detection" }
            SetPageSegMode(PSM_OSD_ONLY)
            Runtime.getRuntime().addShutdownHook(Thread { synchronized(this) { End() } })
        }
    }

    /** [page] should be at about 300 DPI: OSD reads letter shapes, and the tilt needs the text rows. */
    fun detect(page: BufferedImage): OrientationGuess {
        val gray = gray(page)
        val (orientation, confidence) = orientation(gray)
        val turns = if (orientation != null && confidence != null && confidence >= MIN_CONFIDENCE) (4 - orientation / 90) % 4 else 0
        val upright = PageRotation(turns).apply(gray).let(::gray)
        val skew = mat(upright).use { estimateSkew(it) }
        return OrientationGuess(PageRotation(turns, -skew), orientation, confidence, skew)
    }

    private fun orientation(gray: BufferedImage): Pair<Int?, Float?> {
        val bytes = (gray.raster.dataBuffer as DataBufferByte).data
        val api = engine
        return synchronized(api) {
            IntPointer(1L).use { degrees ->
                FloatPointer(1L).use { confidence ->
                    PointerPointer<BytePointer>(1L).use { script ->
                        FloatPointer(1L).use { scriptConfidence ->
                            try {
                                api.SetImage(bytes, gray.width, gray.height, 1, gray.width)
                                if (api.DetectOrientationScript(degrees, confidence, script, scriptConfidence)) degrees.get() to confidence.get()
                                else null to null
                            } finally {
                                api.Clear()
                            }
                        }
                    }
                }
            }
        }
    }

    /** An 8-bit gray copy without row padding, as Tesseract and OpenCV read it. */
    private fun gray(image: BufferedImage): BufferedImage {
        if (image.type == BufferedImage.TYPE_BYTE_GRAY && image.raster.parent == null &&
            (image.raster.dataBuffer as DataBufferByte).data.size == image.width * image.height) return image
        return BufferedImage(image.width, image.height, BufferedImage.TYPE_BYTE_GRAY).apply {
            createGraphics().let { g ->
                try { g.color = Color.WHITE; g.fillRect(0, 0, width, height); g.drawImage(image, 0, 0, null) } finally { g.dispose() }
            }
        }
    }

    private fun mat(gray: BufferedImage): Mat {
        val bytes = (gray.raster.dataBuffer as DataBufferByte).data
        return Mat(gray.height, gray.width, CV_8UC1).apply { data().put(bytes, 0, bytes.size) }
    }
}
