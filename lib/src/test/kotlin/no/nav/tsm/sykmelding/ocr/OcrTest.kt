package no.nav.tsm.sykmelding.ocr

import org.apache.pdfbox.Loader
import org.apache.pdfbox.rendering.PDFRenderer
import org.example.no.nav.tsm.ocr.template.template.Felter
import org.example.no.nav.tsm.ocr.template.template.FieldType
import org.example.no.nav.tsm.ocr.template.template.Template
import org.junit.jupiter.api.Assertions.assertEquals
import tools.jackson.module.kotlin.jacksonObjectMapper
import tools.jackson.module.kotlin.readValue
import java.awt.Color
import java.awt.image.BufferedImage
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFailsWith

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
    fun `page parsing routes checkbox fields to mark detection and retains text OCR`() {
        val source = ImageIO.read(javaClass.getResourceAsStream("/img.png"))
        val image = BufferedImage(source.width + 90, maxOf(source.height, 150), BufferedImage.TYPE_INT_RGB)
        val empty = Felter(source.width + 20, 10, 50, 50, "empty box", "empty", FieldType.CHECKBOX)
        val checked = empty.copy(y = 80, navn = "marked box", id = "checked")
        val textFields = FieldType.entries.filter { it != FieldType.CHECKBOX }.map { type ->
            Felter(0, 0, source.width, source.height, type.name, type.name, type)
        }
        val graphics = image.createGraphics()
        try {
            graphics.color = Color.WHITE
            graphics.fillRect(0, 0, image.width, image.height)
            graphics.drawImage(source, 0, 0, null)
            graphics.color = Color.BLACK
            for (box in listOf(empty, checked)) {
                graphics.drawRect(box.x, box.y, box.bredde - 1, box.hoyde - 1)
            }
            graphics.fillRect(checked.x + 15, checked.y + 15, 20, 20)
        } finally {
            graphics.dispose()
        }
        val template = Template("test", "", textFields + listOf(empty, checked), "")

        val result = Ocr().parsePageWithFields(template, image)

        assertEquals(template.felter.size, result.size)
        textFields.forEach { assertEquals("Test", result[it], "Text OCR for ${it.type}") }
        assertEquals("false", result[empty])
        assertEquals("true", result[checked])
    }

    @Test
    fun `page parsing rejects uncertain checkbox marks instead of returning false`() {
        val image = BufferedImage(50, 50, BufferedImage.TYPE_INT_RGB)
        val graphics = image.createGraphics()
        try {
            graphics.color = Color.WHITE
            graphics.fillRect(0, 0, 50, 50)
            graphics.color = Color.BLACK
            graphics.fillRect(20, 20, 5, 10)
        } finally {
            graphics.dispose()
        }
        val field = Felter(0, 0, 50, 50, "aktivitet.forholdArbeidsplassen", "checkbox-id", FieldType.CHECKBOX)
        val template = Template("test", "", listOf(field), "")

        val failure = assertFailsWith<IllegalStateException> {
            Ocr().parsePageWithFields(template, image)
        }
        assertContains(failure.message.orEmpty(), field.navn)
        assertContains(failure.message.orEmpty(), field.id)
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
