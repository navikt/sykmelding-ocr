package no.nav.tsm.sykmelding.utils

import java.awt.Color
import java.awt.Font
import java.awt.Rectangle
import java.awt.image.BufferedImage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TextGroupDetectionTest {
    @Test
    fun `settings control word and line grouping and minimum dimensions`() {
        val image = BufferedImage(400, 200, BufferedImage.TYPE_INT_RGB)
        val graphics = image.createGraphics()
        try {
            graphics.color = Color.WHITE
            graphics.fillRect(0, 0, image.width, image.height)
            graphics.color = Color.BLACK
            for (y in listOf(40, 75)) {
                for (x in listOf(40, 54, 110, 124)) graphics.fillRect(x, y, 8, 12)
            }
        } finally {
            graphics.dispose()
        }
        toMat(image).use { source ->
            fun count(settings: TextGroupSettings): Int {
                val groups = findTextGroups(source, settings)
                return groups.size.also { groups.forEach { it.bounds.close() } }
            }
            assertEquals(4, count(TextGroupSettings()))
            assertEquals(2, count(TextGroupSettings(horizontalGap = 60)))
            assertEquals(2, count(TextGroupSettings(verticalGap = 30)))
            assertEquals(1, count(TextGroupSettings(horizontalGap = 60, verticalGap = 30)))
            assertEquals(0, count(TextGroupSettings(minimumWidth = 40)))
            assertEquals(0, count(TextGroupSettings(minimumHeight = 20)))
            assertEquals(0, count(TextGroupSettings(minimumLineLength = 5)))
            assertEquals(4, count(TextGroupSettings(removeLines = false, minimumLineLength = 5)))
        }
    }

    @Test
    fun `groups words while separating lines and columns and ignoring form rules`() {
        for (scale in listOf(1, 2)) {
            val image = BufferedImage(600 * scale, 240 * scale, BufferedImage.TYPE_INT_RGB)
            val graphics = image.createGraphics()
            val expected = mutableListOf<Rectangle>()
            try {
                graphics.color = Color.WHITE
                graphics.fillRect(0, 0, image.width, image.height)
                graphics.scale(scale.toDouble(), scale.toDouble())
                graphics.color = Color.BLACK
                graphics.drawRect(10, 10, 580, 220)
                graphics.drawLine(300, 10, 300, 230)
                graphics.drawLine(10, 120, 590, 120)
                graphics.font = Font(Font.SANS_SERIF, Font.PLAIN, 20)
                for ((x, baseline) in listOf(30 to 50, 30 to 95, 350 to 50)) {
                    graphics.drawString("Sample text", x, baseline)
                    expected += Rectangle(x * scale, (baseline - 20) * scale,
                        graphics.fontMetrics.stringWidth("Sample text") * scale, 24 * scale)
                }
                graphics.fillRect(550, 190, 1, 1) // Isolated speck should not become a text group.
            } finally {
                graphics.dispose()
            }
            toMat(image).use { source ->
                val groups = findTextGroups(source)
                try {
                    assertEquals(3, groups.size, "Expected separate lines and columns at scale $scale")
                    expected.forEach { region ->
                        assertTrue(groups.any { group ->
                            val box = group.bounds
                            region.contains(Rectangle(box.x(), box.y(), box.width(), box.height())) &&
                                box.width() > region.width * 0.8
                        }, "Missing text group in $region")
                    }
                } finally {
                    groups.forEach { it.bounds.close() }
                }
            }
        }
    }

    @Test
    fun `blank and rule-only images have no text groups`() {
        for (type in listOf(BufferedImage.TYPE_BYTE_GRAY, BufferedImage.TYPE_INT_RGB, BufferedImage.TYPE_INT_ARGB)) {
            val image = BufferedImage(400, 200, type)
            val graphics = image.createGraphics()
            try {
                graphics.color = Color.WHITE
                graphics.fillRect(0, 0, image.width, image.height)
                toMat(image).use { assertTrue(findTextGroups(it).isEmpty()) }
                graphics.color = Color.BLACK
                graphics.drawRect(10, 10, 380, 180)
                graphics.drawLine(200, 10, 200, 190)
                toMat(image).use { assertTrue(findTextGroups(it).isEmpty()) }
            } finally {
                graphics.dispose()
            }
        }
    }
}
