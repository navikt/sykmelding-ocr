package no.nav.tsm.sykmelding.ocr

import org.bytedeco.tesseract.TessBaseAPI
import org.bytedeco.tesseract.global.tesseract
import org.bytedeco.tesseract.global.tesseract.OEM_LSTM_ONLY
import org.bytedeco.tesseract.global.tesseract.PSM_AUTO
import org.bytedeco.tesseract.global.tesseract.PSM_SINGLE_BLOCK
import org.bytedeco.tesseract.global.tesseract.PSM_SPARSE_TEXT
import org.bytedeco.tesseract.global.tesseract.TessDeleteText
import org.opencv.text.Text
import java.awt.Color
import java.awt.image.BufferedImage
import java.awt.image.DataBufferByte
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

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
        tesseract.SetPageSegMode(psmMode.value)
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
}

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
