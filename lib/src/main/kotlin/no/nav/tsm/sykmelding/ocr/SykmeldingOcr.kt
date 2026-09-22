package no.nav.tsm.sykmelding.ocr

import net.sourceforge.tess4j.ITessAPI
import net.sourceforge.tess4j.Tesseract
import java.awt.Rectangle
import java.awt.image.BufferedImage
import java.io.File

class Ocr {

    public fun parse(img: BufferedImage, field: Field): String? {
        val subImage = img.getSubimage(field.x, field.y, field.width, field.height)
        val tesseract = Tesseract().apply {
            setDatapath("")
            setLanguage("nor")
            setOcrEngineMode(1) // LSTM only (required for "best" tessdata)
        }
        return tesseract.doOCR(subImage)
    }

    public fun getTextsRegions(img: BufferedImage): List<Rectangle?>? {
        val tesseract = Tesseract().apply {
            setDatapath("")
            setLanguage("nor")
            setOcrEngineMode(1) // LSTM only (required for "best" tessdata)
            setVariable("debug_file", "NUL")
        }
        return tesseract.getSegmentedRegions(img, ITessAPI.TessPageIteratorLevel.RIL_WORD)
    }

    public fun parse(img: BufferedImage): String? {
        val tesseract = Tesseract().apply {
            setDatapath("")
            setLanguage("nor")
            setOcrEngineMode(1) // LSTM only (required for "best" tessdata)
            setVariable("debug_file", "NUL")
        }
        tesseract.setPageSegMode(11)
        return tesseract.doOCR(img)
    }
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
                if (result != null) {
                    return result
                }
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
