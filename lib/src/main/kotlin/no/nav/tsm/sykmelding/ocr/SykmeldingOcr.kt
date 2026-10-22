package no.nav.tsm.sykmelding.ocr

import org.bytedeco.javacpp.DoublePointer
import org.bytedeco.javacv.Java2DFrameUtils
import org.bytedeco.opencv.global.opencv_core.*
import org.bytedeco.opencv.global.opencv_imgproc.*
import org.bytedeco.opencv.opencv_core.Mat
import org.bytedeco.opencv.opencv_core.Point
import org.bytedeco.tesseract.PageIterator
import org.bytedeco.tesseract.TessBaseAPI
import org.bytedeco.tesseract.global.tesseract.*
import java.awt.Color
import java.awt.Rectangle
import java.awt.image.BufferedImage
import java.awt.image.DataBufferByte
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.roundToInt

enum class PSM_MODE(val value: Int) {
    PSM_OSD_ONLY(0),
    PSM_AUTO_OSD(1),
    PSM_AUTO_ONLY(2),
    PSM_AUTO(3),
    PSM_SINGLE_COLUMN(4),
    PSM_SINGLE_BLOCK_VERT_TEXT(5),
    PSM_SINGLE_BLOCK(6),
    PSM_SINGLE_LINE(7),
    PSM_SINGLE_WORD(8),
    PSM_CIRCLE_WORD(9),
    PSM_SINGLE_CHAR(10),
    PSM_SPARSE_TEXT(11),
    PSM_SPARSE_TEXT_OSD(12)
}

data class TextRegion(
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
    val text: String,
    val confidence: Float,
    val baseline: Int = y + height,
    val angle: Float = 0f,
) {
    fun isLevel(): Boolean = abs(angle) <= MAX_TEXT_ANGLE
}


fun textLines(foundLines: List<TextRegion>): List<List<TextRegion>> {
    val lines = mutableListOf<MutableList<TextRegion>>()
    for (foundLine in foundLines.sortedBy { it.baseline }) {
        val line = lines.lastOrNull()?.takeIf { line ->
            val height = minOf(foundLine.height, line.map { it.height }.sorted()[line.size / 2])
            foundLine.baseline - line.map { it.baseline }.average() <= height / 2.0
        }
        if (line == null) lines += mutableListOf(foundLine) else line += foundLine
    }
    return lines.map { line -> line.sortedBy { it.x } }
}

enum class LeftOut(val description: String) {
    ROTATED("rotated more than ${MAX_TEXT_ANGLE.roundToInt()}°, like a stamp"),
    UNSURE("read with less than ${MIN_TEXT_CONFIDENCE.roundToInt()}% confidence"),
    RIGHT_HALF("starts in the field's right half, like a stamp"),
    FAR_FROM_EDGE("no line in the field starts within ${MAX_FIRST_LINE_START.roundToInt()} line heights of its left edge, like a stamp alone in it"),
    INDENTED("starts more than ${LINE_INDENT.roundToInt()} line heights right of the field's leftmost line, like a stamp"),
}

fun fieldLineVerdicts(lines: List<TextRegion>, field: Rectangle): List<LeftOut?> {
    val verdicts = lines.map { line ->
        when {
            !line.isLevel() -> LeftOut.ROTATED
            line.confidence < MIN_TEXT_CONFIDENCE -> LeftOut.UNSURE
            line.x >= field.x + MAX_LINE_START * field.width -> LeftOut.RIGHT_HALF
            else -> null
        }
    }.toMutableList()
    val candidates = lines.indices.filter { verdicts[it] == null }
    val first = candidates.map { lines[it] }.filter { it.x - field.x <= MAX_FIRST_LINE_START * it.height }.minOfOrNull { it.x }
    for (index in candidates) {
        val line = lines[index]
        verdicts[index] = when {
            first == null -> LeftOut.FAR_FROM_EDGE
            line.x - first > LINE_INDENT * line.height -> LeftOut.INDENTED
            else -> null
        }
    }
    return verdicts
}

const val MAX_LINE_START = 0.5

const val MIN_TEXT_CONFIDENCE = 30f
const val MAX_TEXT_ANGLE = 5f
const val MAX_FIRST_LINE_START = 6.0
const val LINE_INDENT = 3.0

data class FieldText(val lines: List<TextRegion>) {
    val text: String get() = lines.joinToString("\n") { it.text }
}

class Ocr() {

    internal object TessData {
        private val languages = listOf("nor")
        private val auxFiles = listOf("osd")

        private val dir: Path by lazy { Files.createTempDirectory("tessdata") }

       @Synchronized
        fun dirWith(language: String): Path {
            (listOf(language) + auxFiles).forEach { name ->
                val target = dir.resolve("$name.traineddata")
                if (Files.exists(target)) return@forEach
                val stream = TessData::class.java.getResourceAsStream("/tessdata/$name.traineddata")
                    ?: error("Missing /tessdata/$name.traineddata on classpath")
                val part = dir.resolve("$name.traineddata.part")
                stream.use { Files.copy(it, part, StandardCopyOption.REPLACE_EXISTING) }
                Files.move(part, target, StandardCopyOption.REPLACE_EXISTING)
            }
            return dir
        }
    }

    private val tesseract: TessBaseAPI = engine()

    companion object {
        private val engines = ConcurrentHashMap<String, TessBaseAPI>()

        init {
            Runtime.getRuntime().addShutdownHook(Thread {
                engines.values.forEach { it.End() }
            })
        }

      private fun engine(): TessBaseAPI = engines.computeIfAbsent("nor") {
            val path = TessData.dirWith("nor").toString()
            val api = TessBaseAPI()
            if (api.Init(path, "nor", OEM_LSTM_ONLY) != 0) {
                api.close()
                error("Could not initialize the OCR model nor")
            }
            api.SetPageSegMode(PSM_AUTO)
            api
        }
    }

    fun parse(img: BufferedImage, psmMode: PSM_MODE = PSM_MODE.PSM_AUTO): String? {
        val grayscale = grayscale(img)
        val pixels = (grayscale.raster.dataBuffer as DataBufferByte).data

        return synchronized(tesseract) {
            tesseract.SetPageSegMode(psmMode.value)
            tesseract.SetImage(pixels, grayscale.width, grayscale.height, 1, grayscale.width)
            val textPointer = tesseract.GetUTF8Text()
            check(textPointer != null && !textPointer.isNull) { "OCR recognition failed" }
            try {
                textPointer.getString(Charsets.UTF_8)
            } finally {
                TessDeleteText(textPointer)
                tesseract.Clear()
            }
        }
    }

    internal fun detectText(image: BufferedImage, psmMode: PSM_MODE, dpi: Int?, allowedCharacters: String?): List<TextRegion> {
        val page = grayscale(image)
        return withEngine { engine ->
            engine.setPage(page, psmMode, dpi)
            try {
                allowedCharacters?.let {
                    check(engine.SetVariable(WHITELIST, it)) { "Tesseract does not know $WHITELIST" }
                }
                check(engine.Recognize(null) == 0) { "OCR recognition failed" }
                engine.readLines()
            } finally {
                // Back to every character, for the next caller of the shared engine.
                if (allowedCharacters != null) engine.SetVariable(WHITELIST, "")
            }
        }
    }

    internal fun <T> withEngine(block: (TessBaseAPI) -> T): T = synchronized(tesseract) {
        try {
            block(tesseract)
        } finally {
            tesseract.Clear()
        }
    }
}

internal fun TessBaseAPI.setPage(page: BufferedImage, psmMode: PSM_MODE, dpi: Int?) {
    val pixels = (page.raster.dataBuffer as DataBufferByte).data
    SetPageSegMode(psmMode.value)
    SetImage(pixels, page.width, page.height, 1, page.width)
    dpi?.let { SetSourceResolution(it) }
}


private fun TessBaseAPI.readLines(): List<TextRegion> {
    val iterator = GetIterator()
    if (iterator == null || iterator.isNull) return emptyList()
    try {
        val left = IntArray(1)
        val top = IntArray(1)
        val right = IntArray(1)
        val bottom = IntArray(1)
        return buildList {
            do {
                if (iterator.Empty(RIL_TEXTLINE) || !iterator.BoundingBox(RIL_TEXTLINE, left, top, right, bottom)) continue
                val textPointer = iterator.GetUTF8Text(RIL_TEXTLINE)
                val text = if (textPointer == null || textPointer.isNull) "" else try {
                    textPointer.getString(Charsets.UTF_8)
                } finally {
                    TessDeleteText(textPointer)
                }
                if (text.isBlank()) continue
                val (baseline, angle) = lineBaseline(iterator, left[0], right[0], bottom[0])
                add(TextRegion(left[0], top[0], right[0] - left[0], bottom[0] - top[0], text.trim(), iterator.Confidence(RIL_TEXTLINE), baseline, angle))
            } while (iterator.Next(RIL_TEXTLINE))
        }
    } finally {
        TessResultIteratorDelete(iterator)
    }
}

internal fun lineBaseline(iterator: PageIterator, left: Int, right: Int, bottom: Int): Pair<Int, Float> {
    val x1 = IntArray(1)
    val y1 = IntArray(1)
    val x2 = IntArray(1)
    val y2 = IntArray(1)
    if (!iterator.Baseline(RIL_TEXTLINE, x1, y1, x2, y2) || (x1[0] == x2[0] && y1[0] == y2[0])) return bottom to 0f
    // Image y goes down, so a line rising to the right has y2 < y1.
    val angle = Math.toDegrees(atan2((y1[0] - y2[0]).toDouble(), (x2[0] - x1[0]).toDouble())).toFloat()
    val middle = (left + right) / 2.0
    val baseline = if (x1[0] == x2[0]) (y1[0] + y2[0]) / 2
        else (y1[0] + (y2[0] - y1[0]) * (middle - x1[0]) / (x2[0] - x1[0])).roundToInt()
    return baseline to angle
}

/** Tesseract's setting for the only characters it may read; empty for all. */
private const val WHITELIST = "tessedit_char_whitelist"

/** [image] as 8-bit grey on white, the form Tesseract is given: transparency is put on white, colour made grey. */
internal fun grayscale(image: BufferedImage): BufferedImage {
    val grayscale = BufferedImage(image.width, image.height, BufferedImage.TYPE_BYTE_GRAY)
    val graphics = grayscale.createGraphics()
    try {
        graphics.color = Color.WHITE
        graphics.fillRect(0, 0, grayscale.width, grayscale.height)
        graphics.drawImage(image, 0, 0, null)
    } finally {
        graphics.dispose()
    }
    return grayscale
}

class SykmeldingOcr {

    fun findReferences(referenceImage: BufferedImage, searchImage: BufferedImage): List<ReferencesRectangle> {
        val referenceImgMat = Java2DFrameUtils.toMat(referenceImage)
        val searchImgMat = Java2DFrameUtils.toMat(searchImage)

        if (referenceImgMat.empty() || searchImgMat.empty()) {
            return emptyList()
        }

        val result = Mat()
        val resultCols = referenceImgMat.cols() - searchImgMat.cols() + 1
        val resultRows = referenceImgMat.rows() - searchImgMat.rows() + 1
        result.create(resultRows, resultCols, CV_32FC1)

        matchTemplate(referenceImgMat, searchImgMat, result, TM_CCOEFF_NORMED)

        val minVal = DoublePointer(1)
        val maxVal = DoublePointer(1)
        val minLoc = Point()
        val maxLoc = Point()

        minMaxLoc(result, minVal, maxVal, minLoc, maxLoc, null)

        return listOf(ReferencesRectangle(
            x = maxLoc.x(),
            y = maxLoc.y(),
            width = searchImgMat.cols(),
            height = searchImgMat.rows(),
        ))
    }
}

data class ReferencesRectangle(val x: Int, val y: Int, val width: Int, val height: Int)
