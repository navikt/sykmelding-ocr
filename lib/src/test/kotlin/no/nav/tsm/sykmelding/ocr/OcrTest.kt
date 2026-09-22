package no.nav.tsm.sykmelding.ocr

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.apache.pdfbox.Loader
import org.apache.pdfbox.rendering.PDFRenderer
import org.junit.jupiter.api.Assertions.assertEquals
import java.awt.image.BufferedImage
import javax.imageio.ImageIO
import kotlin.test.Test

class OcrTest {
    val objectMapper = jacksonObjectMapper().apply {
        configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
        configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false)
    }

    @Test
    fun `OCR returns "test" text`() {
        val ocr = Ocr()
        val img = ImageIO.read(javaClass.getResourceAsStream("/img.png"))
        val result = ocr.parse(img)
        assertEquals("Test\n", result)
    }

    @Test
    fun `OCR returns "test" with field text`() {
        val ocr = Ocr()
        val img = ImageIO.read(javaClass.getResourceAsStream("/img.png"))
        val result = ocr.parse(img, Field(20, 20, img.width - 20, img.height - 20, "test"))
        assertEquals("Test\n", result)
    }

    @Test
    fun `OCR read field`() {
        val ocr = Ocr()
        val pdf = javaClass.getResourceAsStream("/test.pdf").readBytes()
        val fields: List<Field> =  objectMapper.readValue(javaClass.getResourceAsStream("/fields.json").readBytes())
        val pages = renderPages(pdf)
        fields.forEach {
            val result = ocr.parse(pages.first(), Field(it.x, it.y, it.width, it.height, it.name))
            assertEquals("R74", result?.trim())
        }
    }
}
private fun renderPages(pdf: ByteArray): List<BufferedImage> =
    Loader.loadPDF(pdf).use { document ->
        val renderer = PDFRenderer(document)
        (0 until 1).map { renderer.renderImageWithDPI(it, 400.0f) }
    }


