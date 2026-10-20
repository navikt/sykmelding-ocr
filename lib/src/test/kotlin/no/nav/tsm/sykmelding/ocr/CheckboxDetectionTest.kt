package no.nav.tsm.sykmelding.ocr

import org.example.no.nav.tsm.ocr.template.template.Felter
import org.example.no.nav.tsm.ocr.template.template.FieldType
import java.awt.BasicStroke
import java.awt.Color
import java.awt.image.BufferedImage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class CheckboxDetectionTest {
    private val field = Felter(10, 10, 50, 50, "checkbox", "checkbox-id", FieldType.CHECKBOX)
    private val imageTypes = listOf(
        BufferedImage.TYPE_INT_RGB,
        BufferedImage.TYPE_INT_ARGB,
        BufferedImage.TYPE_3BYTE_BGR,
        BufferedImage.TYPE_4BYTE_ABGR,
        BufferedImage.TYPE_BYTE_GRAY,
    )

    @Test
    fun `empty borders are ignored across checkbox sizes and pixel layouts`() {
        for ((width, height) in listOf(20 to 20, 49 to 47, 75 to 67, 100 to 100)) {
            val box = field.copy(bredde = width, hoyde = height)
            for (type in imageTypes) {
                assertEquals(CheckboxState.UNCHECKED, detectCheckbox(emptyBox(box, type), box))
            }
        }
    }

    @Test
    fun `crosses ticks and filled boxes are checked across sizes and pixel layouts`() {
        for ((width, height) in listOf(20 to 20, 49 to 47, 75 to 67, 100 to 100)) {
            val box = field.copy(bredde = width, hoyde = height)
            for (type in imageTypes) {
                for (mark in listOf("cross", "tick", "filled")) {
                    val image = emptyBox(box, type)
                    val graphics = image.createGraphics()
                    try {
                        graphics.translate(box.x, box.y)
                        graphics.color = Color.BLACK
                        graphics.stroke = BasicStroke(maxOf(2f, minOf(width, height) / 12f))
                        when (mark) {
                            "cross" -> {
                                graphics.drawLine(width / 4, height / 4, width * 3 / 4, height * 3 / 4)
                                graphics.drawLine(width * 3 / 4, height / 4, width / 4, height * 3 / 4)
                            }
                            "tick" -> {
                                graphics.drawLine(width / 4, height / 2, width * 2 / 5, height * 3 / 4)
                                graphics.drawLine(width * 2 / 5, height * 3 / 4, width * 3 / 4, height / 4)
                            }
                            "filled" -> graphics.fillRect(width / 4, height / 4, width / 2, height / 2)
                        }
                    } finally {
                        graphics.dispose()
                    }
                    assertEquals(
                        CheckboxState.CHECKED, detectCheckbox(image, box),
                        "$mark in ${width}x$height with image type $type",
                    )
                }
            }
        }
    }

    @Test
    fun `coverage thresholds distinguish noise uncertain marks and clear marks`() {
        // A 50x50 field has a 30x30 interior after excluding its border.
        for ((pixels, expected) in listOf(
            0 to CheckboxState.UNCHECKED,
            18 to CheckboxState.UNCHECKED,
            19 to CheckboxState.UNCERTAIN,
            89 to CheckboxState.UNCERTAIN,
            90 to CheckboxState.CHECKED,
            900 to CheckboxState.CHECKED,
        )) {
            assertEquals(expected, detectCheckbox(interiorInk(pixels), field), "$pixels / 900 pixels")
        }
    }

    @Test
    fun `faint marks are uncertain rather than unchecked`() {
        assertEquals(
            CheckboxState.UNCERTAIN,
            detectCheckbox(interiorInk(150, Color(180, 180, 180)), field),
        )
    }

    @Test
    fun `blue ink is detected and transparent pixels are treated as white`() {
        assertEquals(
            CheckboxState.CHECKED,
            detectCheckbox(interiorInk(150, Color.BLUE), field),
        )
        val transparent = BufferedImage(70, 70, BufferedImage.TYPE_INT_ARGB)
        assertEquals(CheckboxState.UNCHECKED, detectCheckbox(transparent, field))
        transparent.setRGB(25, 25, Color(0, 0, 0, 10).rgb)
        assertEquals(CheckboxState.UNCHECKED, detectCheckbox(transparent, field))
    }

    @Test
    fun `pixels outside the checkbox and inside its margin do not count`() {
        val image = BufferedImage(70, 70, BufferedImage.TYPE_INT_RGB)
        val graphics = image.createGraphics()
        try {
            graphics.color = Color.WHITE
            graphics.fillRect(20, 20, 30, 30)
        } finally {
            graphics.dispose()
        }
        assertEquals(CheckboxState.UNCHECKED, detectCheckbox(image, field))
    }

    @Test
    fun `too few interior pixels is uncertain`() {
        assertEquals(
            CheckboxState.UNCERTAIN,
            detectCheckbox(emptyBox(), field.copy(bredde = 10, hoyde = 10)),
        )
    }

    @Test
    fun `invalid and out of bounds boxes fail explicitly`() {
        val image = emptyBox()
        for (invalid in listOf(
            field.copy(bredde = 0),
            field.copy(hoyde = -1),
            field.copy(x = -1),
            field.copy(y = -1),
            field.copy(x = 21),
            field.copy(y = 21),
            field.copy(x = Int.MAX_VALUE),
            field.copy(bredde = Int.MAX_VALUE),
        )) {
            assertFailsWith<IllegalArgumentException> { detectCheckbox(image, invalid) }
        }
    }

    private fun emptyBox(
        box: Felter = field,
        type: Int = BufferedImage.TYPE_INT_RGB,
    ): BufferedImage = BufferedImage(box.bredde + 20, box.hoyde + 20, type).apply {
        val graphics = createGraphics()
        try {
            graphics.color = Color.WHITE
            graphics.fillRect(0, 0, width, height)
            graphics.color = Color.BLACK
            graphics.stroke = BasicStroke(3f)
            graphics.drawRect(box.x + 1, box.y + 1, box.bredde - 3, box.hoyde - 3)
        } finally {
            graphics.dispose()
        }
    }

    private fun interiorInk(pixels: Int, color: Color = Color.BLACK): BufferedImage = emptyBox().apply {
        repeat(pixels) { index -> setRGB(20 + index % 30, 20 + index / 30, color.rgb) }
    }
}
