package no.nav.tsm.sykmelding.utils

import org.example.no.nav.tsm.ocr.template.template.Felter
import java.awt.Color
import java.awt.RenderingHints
import java.awt.geom.AffineTransform
import java.awt.image.BufferedImage

fun cropAlignedField(image: BufferedImage, field: Felter, transform: FieldTransform): BufferedImage {
    require(field.bredde > 0 && field.hoyde > 0) { "The selected field has invalid dimensions." }
    require(transform.region(field).corners.all {
        it.x.isFinite() && it.y.isFinite() && it.x >= 0 && it.y >= 0 && it.x <= image.width && it.y <= image.height
    }) { "The selected field extends outside the incoming document." }
    val incomingToField = AffineTransform.getTranslateInstance(-field.x.toDouble(), -field.y.toDouble()).apply {
        concatenate(AffineTransform(transform.a, transform.b, transform.c, transform.d, transform.tx, transform.ty).createInverse())
    }
    return BufferedImage(field.bredde, field.hoyde, BufferedImage.TYPE_3BYTE_BGR).apply {
        val graphics = createGraphics()
        try {
            graphics.color = Color.WHITE
            graphics.fillRect(0, 0, width, height)
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
            graphics.drawImage(image, incomingToField, null)
        } finally {
            graphics.dispose()
        }
    }
}
