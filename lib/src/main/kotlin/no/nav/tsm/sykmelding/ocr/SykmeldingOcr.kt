package no.nav.tsm.sykmelding.ocr

import org.bytedeco.javacpp.DoublePointer
import org.bytedeco.javacpp.PointerScope
import org.bytedeco.javacv.Java2DFrameUtils
import org.bytedeco.opencv.global.opencv_core.minMaxLoc
import org.bytedeco.opencv.global.opencv_imgproc.COLOR_BGR2GRAY
import org.bytedeco.opencv.global.opencv_imgproc.COLOR_BGRA2GRAY
import org.bytedeco.opencv.global.opencv_imgproc.INTER_AREA
import org.bytedeco.opencv.global.opencv_imgproc.INTER_LINEAR
import org.bytedeco.opencv.global.opencv_imgproc.TM_CCOEFF_NORMED
import org.bytedeco.opencv.global.opencv_imgproc.cvtColor
import org.bytedeco.opencv.global.opencv_imgproc.matchTemplate
import org.bytedeco.opencv.global.opencv_imgproc.resize
import org.bytedeco.opencv.opencv_core.Mat
import org.bytedeco.opencv.opencv_core.Point
import org.bytedeco.opencv.opencv_core.Rect
import org.bytedeco.opencv.opencv_core.Size
import org.bytedeco.tesseract.TessBaseAPI
import org.bytedeco.tesseract.global.tesseract.OEM_LSTM_ONLY
import org.bytedeco.tesseract.global.tesseract.PSM_AUTO
import org.bytedeco.tesseract.global.tesseract.TessDeleteText
import java.awt.Color
import java.awt.image.BufferedImage
import java.awt.image.DataBufferByte
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import javax.swing.JLabel
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



class Ocr {

    internal object TessData {
        private val languages = listOf("nor")
        private val auxFiles = listOf("osd")

        val dir: Path by lazy {
            val d = Files.createTempDirectory("tessdata")
            (languages + auxFiles).forEach { it ->
                val name = "$it.traineddata"
                val stream = TessData::class.java.getResourceAsStream("/tessdata/$name")
                    ?: error("Missing /tessdata/$name on classpath")
                stream.use { Files.copy(it, d.resolve(name), StandardCopyOption.REPLACE_EXISTING) }
            }
            d
        }
    }

    companion object {
        val tesseract = TessBaseAPI()

        init {
            val path = TessData.dir.toString()
            check(tesseract.Init(path, "nor", OEM_LSTM_ONLY) == 0) {
                "Could not initialize Norwegian OCR"
            }
            tesseract.SetPageSegMode(PSM_AUTO)

            Runtime.getRuntime().addShutdownHook(Thread {
                tesseract.End()
            })
        }
        /* val tesseract = Tesseract().apply {
             setDatapath("")
             setLanguage("nor")
             setOcrEngineMode(1) // LSTM only (required for "best" tessdata)
             setVariable("debug_file", "NUL")
         }*/
    }

    public fun parse(img: BufferedImage, field: Field? = null, psmMode: PSM_MODE = PSM_MODE.PSM_AUTO): String? {
        val subImage = field?.let {
            img.getSubimage(it.x, it.y, it.width, it.height)
        } ?: img
        // Normalize channel order and composite transparency onto white before OCR.
        val grayscale = BufferedImage(subImage.width, subImage.height, BufferedImage.TYPE_BYTE_GRAY)
        val graphics = grayscale.createGraphics()
        try {
            graphics.color = Color.WHITE
            graphics.fillRect(0, 0, grayscale.width, grayscale.height)
            graphics.drawImage(subImage, 0, 0, null)
        } finally {
            graphics.dispose()
        }
        val pixels = (grayscale.raster.dataBuffer as DataBufferByte).data

        return synchronized(tesseract) {
            tesseract.SetPageSegMode(psmMode.value)
            tesseract.SetImage(pixels, grayscale.width, grayscale.height, 1, grayscale.width)
//            tesseract.SetVariable("tessedit_char_whitelist", "0123456789-")
//            tesseract.SetVariable("tessedit_char_blacklist", "|»")
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

    /* public fun getTextsRegions(img: BufferedImage): List<Rectangle?>? {
         return tesseract.getSegmentedRegions(img, ITessAPI.TessPageIteratorLevel.RIL_WORD)
     }

     public fun parse(img: BufferedImage): String? {

         tesseract.setPageSegMode(11)
         return tesseract.doOCR(img)
     }

     fun parseFnr(img: BufferedImage, field: Field): String? {
         val subImage = img.getSubimage(field.x, field.y, field.width, field.height)
         tesseract.setVariable("tessedit_char_whitelist", "0123456789-")
         tesseract.setVariable("", "true")
         tesseract.setPageSegMode(PSM_SINGLE_LINE)
         return tesseract.doOCR(subImage)
     }*/
}



// TODO(this is just ideas)
class SykmeldingOcr(val ocr: Ocr) {
    public fun getText(img: BufferedImage): String {
        return img.toString()
    }

    public fun parseSykmelding(pages: List<BufferedImage>): List<Pair<String, String?>>? {
        //loading configs
        val configs = listOf(
            SykmeldingTemplates(
                1, listOf(
                    Field(10, 10, 100, 30, "1.2-pasientFnr")
                )
            )
        )

        configs.forEach {
            if (pages.size > it.pages) {
                //continue
            } else {
                // try to parse pages for given sykmeldingTemplate
                val result = parseTemplate(pages, it)
                return result
            }
        }
        return null
    }

    private fun parseTemplate(
        pages: List<BufferedImage>,
        sykmeldingTemplate: SykmeldingTemplates
    ): List<kotlin.Pair<String, String?>> {
        val parseResults = sykmeldingTemplate.fields.map {
            it.name to ocr.parse(pages.first(), it)
        }
        return parseResults
    }

    /**
     * Finds [searchImage] (a reference crop) on [referenceImage] (a page) with OpenCV's TM_CCOEFF_NORMED template
     * matching. The crop is tried at scales from `minScale` to `maxScale` (default 0.8–1.2), because a form printed or
     * scanned at another size is another size at the same DPI: first on a downscaled copy of the page, then at full
     * resolution around the best place. The first rectangle is where the (scaled) crop lies on the page; its
     * [ReferencesRectangle.confidence] is the raw correlation (1 = identical, about 0.8 or more for the same print).
     * Empty when the best correlation is below `minScore` (default [DEFAULT_MIN_SCORE]): the crop is not on the page.
     * The second rectangle has the page's size, as before.
     */
    public fun findReferences(
        referenceImage: BufferedImage,
        searchImage: BufferedImage,
        configs: Map<String, Any?> = emptyMap()
    ): List<ReferencesRectangle> = PointerScope().use {
        val minScale = (configs["minScale"] as? Number)?.toDouble() ?: DEFAULT_MIN_SCALE
        val maxScale = (configs["maxScale"] as? Number)?.toDouble() ?: DEFAULT_MAX_SCALE
        val minScore = (configs["minScore"] as? Number)?.toDouble() ?: DEFAULT_MIN_SCORE
        require(minScale > 0 && minScale <= maxScale) { "Invalid scale range $minScale–$maxScale" }
        val page = gray(Java2DFrameUtils.toMat(referenceImage))
        val crop = gray(Java2DFrameUtils.toMat(searchImage))
        if (page.empty() || crop.empty()) return emptyList()

        // Coarse: the page at about a quarter of its size, but never so small that the crop's short side drops below 16 px.
        val coarse = minOf(1.0, maxOf(COARSE_FACTOR, 16.0 / (minScale * minOf(crop.cols(), crop.rows()))))
        val smallPage = scaled(page, coarse)
        var best: Match? = null
        var scale = minScale
        while (scale <= maxScale + 1e-9) {
            match(smallPage, crop, scale * coarse, null)?.let { found ->
                if (best == null || found.score > best.score) best = found.copy(scale = scale)
            }
            scale += COARSE_STEP
        }
        val rough = best ?: return emptyList()

        // Fine: full resolution, near the coarse scale, in a window around the coarse place.
        val roughX = (rough.x / coarse).roundToInt()
        val roughY = (rough.y / coarse).roundToInt()
        val margin = (2 / coarse).roundToInt() + (maxOf(crop.cols(), crop.rows()) * COARSE_STEP).roundToInt() + 4
        var fine: Match? = null
        var fineScale = maxOf(minScale, rough.scale - COARSE_STEP)
        while (fineScale <= minOf(maxScale, rough.scale + COARSE_STEP) + 1e-9) {
            val width = (crop.cols() * fineScale).roundToInt()
            val height = (crop.rows() * fineScale).roundToInt()
            val window = Rect(roughX - margin, roughY - margin, width + 2 * margin, height + 2 * margin)
                .intersect(page.cols(), page.rows())
            if (window != null) match(page, crop, fineScale, window)?.let { found ->
                if (fine == null || found.score > fine.score) fine = found.copy(scale = fineScale)
            }
            fineScale += FINE_STEP
        }
        val result = fine ?: rough.let { it.copy(x = roughX, y = roughY, width = (crop.cols() * it.scale).roundToInt(),
            height = (crop.rows() * it.scale).roundToInt()) }
        if (result.score < minScore) return emptyList()
        listOf(
            ReferencesRectangle(result.x, result.y, result.width, result.height, result.score.toFloat(), result.scale),
            ReferencesRectangle(result.x, result.y, page.cols(), page.rows(), result.score.toFloat(), result.scale),
        )
    }

    private data class Match(val x: Int, val y: Int, val width: Int, val height: Int, val score: Double, val scale: Double = 1.0)

    /** [crop] resized by [factor], matched on [page] (within [window], in page pixels); null when it does not fit. */
    private fun match(page: Mat, crop: Mat, factor: Double, window: Rect?): Match? {
        val width = (crop.cols() * factor).roundToInt()
        val height = (crop.rows() * factor).roundToInt()
        if (width < 4 || height < 4) return null
        val area = window ?: Rect(0, 0, page.cols(), page.rows())
        if (width > area.width() || height > area.height()) return null
        val resized = Mat()
        resize(crop, resized, Size(width, height), 0.0, 0.0, if (factor < 1) INTER_AREA else INTER_LINEAR)
        val result = Mat()
        matchTemplate(Mat(page, area), resized, result, TM_CCOEFF_NORMED)
        val maxVal = DoublePointer(1)
        val maxLoc = Point()
        minMaxLoc(result, null, maxVal, null, maxLoc, null)
        val score = maxVal.get()
        // A blank crop or page area has no variance; OpenCV then reports NaN or infinity.
        if (!score.isFinite()) return null
        return Match(area.x() + maxLoc.x(), area.y() + maxLoc.y(), width, height, score)
    }

    private fun Rect.intersect(width: Int, height: Int): Rect? {
        val left = maxOf(0, x())
        val top = maxOf(0, y())
        val right = minOf(width, x() + width())
        val bottom = minOf(height, y() + height())
        return if (right > left && bottom > top) Rect(left, top, right - left, bottom - top) else null
    }

    private fun gray(image: Mat): Mat = when (image.channels()) {
        1 -> image
        3 -> Mat().also { cvtColor(image, it, COLOR_BGR2GRAY) }
        4 -> Mat().also { cvtColor(image, it, COLOR_BGRA2GRAY) }
        else -> error("Unsupported image with ${image.channels()} channels")
    }

    private fun scaled(image: Mat, factor: Double): Mat = if (factor == 1.0) image else Mat().also {
        resize(image, it, Size((image.cols() * factor).roundToInt().coerceAtLeast(1), (image.rows() * factor).roundToInt().coerceAtLeast(1)),
            0.0, 0.0, INTER_AREA)
    }

    companion object {
        /** Below this correlation the crop is taken as not on the page; wrong lines of text score about 0.3–0.5. */
        const val DEFAULT_MIN_SCORE = 0.6
        const val DEFAULT_MIN_SCALE = 0.8
        const val DEFAULT_MAX_SCALE = 1.2
        private const val COARSE_FACTOR = 0.25
        private const val COARSE_STEP = 0.02
        private const val FINE_STEP = 0.005
    }
}

/** [confidence] is the raw TM_CCOEFF_NORMED correlation; [scale] the size of the crop as found, relative to the crop. */
data class ReferencesRectangle(val x: Int, val y: Int, val width: Int, val height: Int, val confidence: Float? = null, val scale: Double = 1.0)

data class Field(
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
    val name: String = "1.1"
)

class SykmeldingTemplates(
    val pages: Int = 1,
    val fields: List<Field>
)

data class SykmeldingParseResult(
    val text: String,
)
