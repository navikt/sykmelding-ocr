package no.nav.tsm.ocr.template

import no.nav.tsm.sykmelding.utils.FieldTransform
import no.nav.tsm.sykmelding.utils.cropAlignedField
import org.example.no.nav.tsm.ocr.template.template.Felter
import java.awt.Color
import java.awt.geom.AffineTransform
import java.awt.image.BufferedImage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails

class FieldOcrTest {
    private val field = Felter(20, 30, 80, 40, "Value", "value")

    @Test
    fun `crop rectifies translation rotation shear and unequal scale without surrounding pixels`() {
        for (transform in listOf(
            FieldTransform(1.0, 0.0, 110.0, 80.0),
            FieldTransform(1.1, 0.15, 110.0, 80.0, c = -0.1, d = 0.9),
        )) {
            val incoming = BufferedImage(400, 300, BufferedImage.TYPE_INT_RGB)
            val graphics = incoming.createGraphics()
            try {
                graphics.color = Color.RED
                graphics.fillRect(0, 0, incoming.width, incoming.height)
                graphics.transform = AffineTransform(transform.a, transform.b, transform.c, transform.d, transform.tx, transform.ty)
                graphics.color = Color.GREEN
                graphics.fillRect(field.x, field.y, field.bredde, field.hoyde)
                graphics.color = Color.BLUE
                graphics.fillRect(field.x + 30, field.y + 10, 20, 20)
            } finally {
                graphics.dispose()
            }
            val crop = cropAlignedField(incoming, field, transform)
            assertEquals(80, crop.width)
            assertEquals(40, crop.height)
            for ((x, y) in listOf(4 to 4, 75 to 4, 4 to 35, 75 to 35)) {
                assertEquals(Color.GREEN.rgb, crop.getRGB(x, y))
            }
            assertEquals(Color.BLUE.rgb, crop.getRGB(40, 20))
            crop.setRGB(0, 0, Color.WHITE.rgb)
            assertEquals(Color.RED.rgb, incoming.getRGB(field.x, field.y))
        }
    }

    @Test
    fun `partial or invalid fields fail instead of returning misleading padded OCR crops`() {
        val incoming = BufferedImage(200, 100, BufferedImage.TYPE_INT_RGB)
        for (transform in listOf(
            FieldTransform(1.0, 0.0, -30.0, 0.0),
            FieldTransform(1.0, 0.0, 150.0, 0.0),
            FieldTransform(1.0, 0.0, 0.0, 50.0),
            FieldTransform(0.0, 0.0, 30.0, 40.0),
            FieldTransform(Double.NaN, 0.0, 0.0, 0.0),
        )) assertFails { cropAlignedField(incoming, field, transform) }
        assertFails { cropAlignedField(incoming, field.copy(bredde = 0), FieldTransform(1.0, 0.0, 0.0, 0.0)) }
    }
}
