package no.nav.tsm.sykmelding.utils

import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.geom.AffineTransform
import java.awt.image.BufferedImage
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

class SkewDetectionTest {
    private val unevenRows = listOf(90, 90, 120, 90, 90, 160, 90, 200, 90, 90, 130, 90, 240, 90)

    @Test
    fun `skew is measured clockwise in degrees`() {
        for (degrees in listOf(-6.5, 0.0, 3.2)) {
            val page = place(form(filled = true), AffineTransform.getRotateInstance(Math.toRadians(degrees), 850.0, 1200.0), 1700, 2400)
            assertEquals(degrees, toMat(page).use { estimateSkew(it) }, 0.15)
        }
    }

    private fun place(page: BufferedImage, placement: AffineTransform, width: Int, height: Int) =
        BufferedImage(width, height, BufferedImage.TYPE_INT_RGB).apply {
            createGraphics().apply {
                try {
                    color = Color.WHITE
                    fillRect(0, 0, width, height)
                    setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
                    drawImage(page, placement, null)
                } finally { dispose() }
            }
        }

    /** A form with a header box and a two-column table; labels move by [labelOffset], as between form editions. */
    private fun form(rows: List<Int> = unevenRows, filled: Boolean = false, labelOffset: Int = 0) =
        BufferedImage(1700, 2400, BufferedImage.TYPE_INT_RGB).apply {
            createGraphics().apply {
                try {
                    color = Color.WHITE
                    fillRect(0, 0, width, height)
                    color = Color.BLACK
                    fun box(x: Int, y: Int, w: Int, h: Int) { fillRect(x, y, w, 3); fillRect(x, y + h - 3, w, 3); fillRect(x, y, 3, h); fillRect(x + w - 3, y, 3, h) }
                    box(100, 150, 1500, 120)
                    fillRect(600, 150, 3, 120)
                    fillRect(1100, 150, 3, 120)
                    var y = 330
                    font = Font(Font.SANS_SERIF, Font.PLAIN, 26)
                    rows.forEachIndexed { index, height ->
                        box(100, y, 1500, height + 3)
                        fillRect(250, y, 3, height)
                        if (index % 3 != 1) fillRect(800, y, 3, height)
                        drawString("${index + 1}.${index % 4}", 130 + labelOffset, y + 45)
                        drawString("Etikett nummer $index", 280 + labelOffset, y + 45 + labelOffset / 3)
                        y += height
                    }
                    if (filled) {
                        val random = Random(rows.size)
                        drawText(this, random)
                        stroke = BasicStroke(4f)
                        repeat(6) { drawLine(random.nextInt(900, 1500), random.nextInt(400, 2100), random.nextInt(900, 1500), random.nextInt(400, 2100)) }
                        rotate(Math.toRadians(12.0), 1300.0, 700.0)
                        drawRect(1150, 620, 300, 160)
                    }
                } finally { dispose() }
            }
        }

    private fun drawText(graphics: Graphics2D, random: Random) {
        graphics.font = Font(Font.SANS_SERIF, Font.PLAIN, 30)
        repeat(40) { graphics.drawString("Utfylt ${random.nextInt(100000)}", random.nextInt(830, 1300), random.nextInt(380, 2150)) }
    }
}
