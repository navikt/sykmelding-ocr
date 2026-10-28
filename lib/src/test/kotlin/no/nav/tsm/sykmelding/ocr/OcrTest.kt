package no.nav.tsm.sykmelding.ocr

import no.nav.tsm.sykmelding.model.CheckboxState
import org.apache.pdfbox.Loader
import org.apache.pdfbox.rendering.PDFRenderer
import no.nav.tsm.sykmelding.template.Felter
import no.nav.tsm.sykmelding.template.FieldType
import org.junit.jupiter.api.Assertions.assertEquals
import tools.jackson.module.kotlin.jacksonObjectMapper
import tools.jackson.module.kotlin.readValue
import java.awt.BasicStroke
import java.awt.Color
import java.awt.image.BufferedImage
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertTrue

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

    private data class Crop(val name: String, val x: Int, val y: Int, val width: Int, val height: Int)

    @Test
    fun `reading a page detects its checkboxes and still reads its text fields`() {
        val cells = DrawnCells(FieldType.TEXT to "Test")
        val empty = Felter(760, 20, 75, 67, "empty box", "empty", FieldType.CHECKBOX)
        val checked = empty.copy(y = 130, navn = "marked box", id = "checked")
        val graphics = cells.image.createGraphics()
        try {
            graphics.color = Color.BLACK
            graphics.stroke = BasicStroke(3f)
            for (box in listOf(empty, checked)) {
                graphics.drawRect(box.x + 1, box.y + 1, box.bredde - 3, box.hoyde - 3)
            }
            // A cross in the marked box.
            graphics.drawLine(checked.x + 20, checked.y + 15, checked.x + 55, checked.y + 52)
            graphics.drawLine(checked.x + 55, checked.y + 15, checked.x + 20, checked.y + 52)
        } finally {
            graphics.dispose()
        }

        val page = Ocr().readPage(cells.image, cells.fields + listOf(empty, checked))

        assertEquals("Test", page.values.getValue(cells.fields.single()).text)
        assertEquals(mapOf(empty to CheckboxState.UNCHECKED, checked to CheckboxState.CHECKED), page.checkboxes.mapValues { it.value.state })
        assertEquals(0.0, page.checkboxes.getValue(empty).darkCoverage)
        assertTrue(page.checkboxes.getValue(checked).darkCoverage!! >= CHECKED_COVERAGE, page.checkboxes.getValue(checked).coverageText)
    }

    @Test
    fun `reading a page reports an uncertain checkbox mark instead of failing or calling it unchecked`() {
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

        assertEquals(mapOf(field to CheckboxReading(CheckboxState.UNCERTAIN, 50 / 900.0, 50 / 900.0)), Ocr().readPage(image, listOf(field)).checkboxes)
    }

    @Test
    fun `OCR read field`() {
        val ocr = Ocr()
        val pdf = javaClass.getResourceAsStream("/test.pdf")!!.readBytes()
        val crops: List<Crop> = objectMapper.readValue(javaClass.getResourceAsStream("/fields.json")!!.readBytes())
        val page = renderPages(pdf).first()
        crops.forEach {
            val result = ocr.parse(page.getSubimage(it.x, it.y, it.width, it.height), PsmMode.PSM_SINGLE_LINE)
            assertEquals("R74", result?.trim(), it.name)
        }
    }

}

private fun renderPages(pdf: ByteArray): List<BufferedImage> =
    Loader.loadPDF(pdf).use { document ->
        val renderer = PDFRenderer(document)
        (0 until 1).map { renderer.renderImageWithDPI(it, 400.0f) }
    }
