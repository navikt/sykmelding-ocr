package no.nav.tsm.ocr.template

import no.nav.tsm.sykmelding.ocr.Ocr
import org.junit.jupiter.api.Assertions.*
import javax.imageio.ImageIO
import kotlin.test.Test

class OcrTest {

    @Test
    fun `OCR returns test text`() {
        val ocr = Ocr()
        val img = ImageIO.read(javaClass.getResourceAsStream("/test.png"))
        val result = ocr.parse(img)
        assertEquals("Test\n", result)
    }
}