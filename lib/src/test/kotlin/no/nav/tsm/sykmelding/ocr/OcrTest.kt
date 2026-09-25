package no.nav.tsm.sykmelding.ocr

import org.apache.pdfbox.Loader
import org.apache.pdfbox.rendering.PDFRenderer
import org.junit.jupiter.api.Assertions.assertEquals
import tools.jackson.module.kotlin.jacksonObjectMapper
import tools.jackson.module.kotlin.readValue
import java.awt.Color
import java.awt.image.BufferedImage
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertNotNull

class OcrTest {
    val objectMapper = jacksonObjectMapper()
    @Test
    fun `OCR returns test text`() {
        val ocr = Ocr()
        val img = ImageIO.read(javaClass.getResourceAsStream("/img.png"))
        val result = ocr.parse(img)
        assertEquals("Test\n", result)
    }

    @Test
    fun `OCR returns test with field text`() {
        val ocr = Ocr()
        val img = ImageIO.read(javaClass.getResourceAsStream("/img_1.png"))
        val result = ocr.parse(img, Field(0, 0, img.width, img.height, "test"))
        assertNotNull(result)
        println(result)
    }

    @Test
    fun `OCR returns test-svensk with field text`() {
        val ocr = Ocr()
        val img = ImageIO.read(javaClass.getResourceAsStream("/svenskmann2.png"))
        val result = ocr.parse(img, Field(0, 0, img.width, img.height, "test"))
        assertNotNull(result)
    }

    @Test
    fun `OCR returns test-svensk with field text 2`() {
        val ocr = Ocr()
        val img1 = ImageIO.read(javaClass.getResourceAsStream("/svenskmann1.png"))
        val result = ocr.parse(img1, Field(0, 0, img1.width, img1.height, "test"))
        val img2 = ImageIO.read(javaClass.getResourceAsStream("/svenskmann2.png"))
        val result2 = ocr.parse(img2, Field(0, 0, img2.width, img2.height, "test"))
        val img3 = ImageIO.read(javaClass.getResourceAsStream("/svenskmann3.png"))
        val result3 = ocr.parse(img3, Field(0, 0, img3.width, img3.height, "test"))
        println(result + "\n\n\n Side 2 \n" + result2 + "\n\n\n Side 3 \n" + result3)
        assertNotNull(result)
        assertNotNull(result2)
        assertNotNull(result3)
    }

    @Test
    fun `OCR reads different pixel layouts`() {
        val ocr = Ocr()
        val source = ImageIO.read(javaClass.getResourceAsStream("/img.png"))
        val types = listOf(
            BufferedImage.TYPE_4BYTE_ABGR,
            BufferedImage.TYPE_INT_ARGB,
            BufferedImage.TYPE_3BYTE_BGR,
            BufferedImage.TYPE_INT_RGB,
            BufferedImage.TYPE_BYTE_GRAY
        )
        types.forEach { type ->
            val img = BufferedImage(source.width, source.height, type)
            val graphics = img.createGraphics()
            try {
                graphics.color = Color.WHITE
                graphics.fillRect(0, 0, img.width, img.height)
                graphics.drawImage(source, 0, 0, null)
            } finally {
                graphics.dispose()
            }
            assertEquals("Test\n", ocr.parse(img), "BufferedImage type $type")
        }
    }

    @Test
    fun `OCR reads only the requested field`() {
        val source = ImageIO.read(javaClass.getResourceAsStream("/img.png"))
        val img = BufferedImage(source.width * 2, source.height * 2, BufferedImage.TYPE_INT_ARGB)
        val graphics = img.createGraphics()
        try {
            graphics.color = Color.WHITE
            graphics.fillRect(0, 0, img.width, img.height)
            graphics.drawImage(source, 0, 0, null)
            graphics.drawImage(source, source.width, source.height, null)
        } finally {
            graphics.dispose()
        }
        val field = Field(source.width, source.height, source.width, source.height)

        assertEquals("Test\n", Ocr().parse(img, field))
    }

    @Test
    fun `OCR read field`() {
        val ocr = Ocr()
        val pdf = javaClass.getResourceAsStream("/test.pdf").readBytes()
        val fields: List<Field> =  objectMapper.readValue(javaClass.getResourceAsStream("/fields.json").readBytes())
        val pages = renderPages(pdf)
        fields.forEach {
            val result = ocr.parse(pages.first(), Field(it.x, it.y, it.width, it.height, it.name), PSM_MODE.PSM_SINGLE_LINE)
            assertEquals("R74", result?.trim())
        }
    }
}

private fun renderPages(pdf: ByteArray): List<BufferedImage> =
    Loader.loadPDF(pdf).use { document ->
        val renderer = PDFRenderer(document)
        (0 until 1).map { renderer.renderImageWithDPI(it, 400.0f) }
    }

