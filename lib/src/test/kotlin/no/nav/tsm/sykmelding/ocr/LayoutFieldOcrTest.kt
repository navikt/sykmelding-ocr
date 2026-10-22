package no.nav.tsm.sykmelding.ocr

import no.nav.tsm.sykmelding.model.CheckboxState
import org.example.no.nav.tsm.ocr.template.template.FieldType
import org.example.no.nav.tsm.ocr.template.template.Felter
import org.example.no.nav.tsm.ocr.template.template.TemplateFiles
import org.example.no.nav.tsm.ocr.template.template.rectangle
import java.awt.Color
import java.awt.Font
import java.awt.Graphics2D
import java.awt.Rectangle
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LayoutFieldOcrTest {
    // The diagnosis row of 03.2019 page 1: Kodesystem, then the dropdown button's cell (no field), then Kode.
    private val system = Felter(1103, 2898, 385, 125, "hoveddiagnose.system", "system")
    private val code = Felter(1591, 2898, 471, 125, "hoveddiagnose.kode", "kode", FieldType.CODE)

    @Test
    fun `a line from the dropdown button into the code field is cut just inside the field's edge`() {
        // It comes in from 87 px left of the field, less than 2 line heights (100 px).
        val segment = fieldSegments(listOf(Rectangle(1504, 2940, 300, 50)), listOf(code)).single()
        // 4 px in from the field's edge, past the cell's frame.
        assertEquals(Rectangle(1595, 2940, 209, 50), segment.box)
    }

    @Test
    fun `a sliver of a line ending just inside a field is left out`() {
        // Ends 19 px into the field: less than half the line's height.
        assertTrue(fieldSegments(listOf(Rectangle(1300, 2940, 310, 50)), listOf(code)).isEmpty())
    }

    @Test
    fun `a checkbox gets no segments, so nothing in it is read`() {
        val checkbox = Felter(1100, 2900, 120, 120, "diagnose.yrkesskade", "yrkesskade", FieldType.CHECKBOX)
        assertTrue(fieldSegments(listOf(Rectangle(1120, 2930, 60, 50)), listOf(checkbox)).isEmpty())
        assertEquals(listOf(code), fieldSegments(listOf(Rectangle(1120, 2930, 60, 50), Rectangle(1600, 2940, 200, 50)), listOf(checkbox, code)).map { it.field })
        assertEquals(listOf(FieldType.CHECKBOX), FieldType.entries.filter { it.skipsOcr })
    }

    @Test
    fun `a narrow line wholly inside a field is kept`() {
        val segment = fieldSegments(listOf(Rectangle(1700, 2940, 12, 50)), listOf(code)).single()
        assertEquals(Rectangle(1700, 2940, 12, 50), segment.box)
    }

    @Test
    fun `a line mostly above the field, such as its label, is left out`() {
        assertTrue(fieldSegments(listOf(Rectangle(1600, 2850, 200, 40)), listOf(code)).isEmpty())
        assertTrue(fieldSegments(listOf(Rectangle(1600, 2860, 200, 50)), listOf(code)).isEmpty())
        assertEquals(Rectangle(1600, 2902, 200, 28), fieldSegments(listOf(Rectangle(1600, 2880, 200, 50)), listOf(code)).single().box)
    }

    @Test
    fun `segments are in each field from the top, and fields keep their order`() {
        val text = Felter(1103, 3308, 1900, 266, "beskrivelse", "beskrivelse")
        val lines = listOf(Rectangle(1200, 3480, 400, 50), Rectangle(1200, 3330, 400, 50), Rectangle(1700, 2950, 100, 40))
        assertEquals(listOf(code, text, text), fieldSegments(lines, listOf(code, text)).map { it.field })
        assertEquals(listOf(3330, 3480), fieldSegments(lines, listOf(text)).map { it.box.y })
    }

    @Test
    fun `each field type allows only its characters`() {
        assertEquals("ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789 .-", FieldType.CODE.allowedCharacters)
        assertEquals("0123456789 +%-", FieldType.NUMBER.allowedCharacters)
        assertEquals("0123456789 -", FieldType.IDENT.allowedCharacters)
        assertEquals("0123456789 .-/", FieldType.DATE.allowedCharacters)
        assertNull(FieldType.TEXT.allowedCharacters)
        assertNull(FieldType.CHECKBOX.allowedCharacters)
    }

    @Test
    fun `reads values with a hyphen or spaces in fields with allowed characters`() {
        // Without "-" and a space allowed, "L-84" was read as "L84" with 0 % confidence, and left out.
        val cells = DrawnCells(
            FieldType.CODE to "L-84",
            FieldType.IDENT to "010180-12345",
            FieldType.IDENT to "010180 12345",
            FieldType.NUMBER to "22 33 44 55",
            FieldType.NUMBER to "100 %",
            FieldType.DATE to "01.02.2026",
        )
        val read = Ocr().readFields(cells.image, cells.fields)
        cells.fields.zip(cells.values).forEach { (field, value) ->
            assertEquals(value, read.getValue(field).text, "${field.type} ${field.navn}")
        }
    }

    @Test
    fun `a field's value is the lines that pass the rules, and each line keeps its verdict`() {
        val segment = FieldSegment(code, Rectangle(1600, 2940, 200, 50))
        val page = pageRead(listOf(code, system), listOf(segment to listOf(TextRegion(1610, 2945, 150, 40, "M75.1", 69f), TextRegion(1780, 2945, 20, 40, "|", 12f))))
        assertEquals("M75.1", page.values.getValue(code).text)
        assertEquals("", page.values.getValue(system).text)
        assertEquals(listOf(null, LeftOut.UNSURE), page.segments.single().lines.map { it.leftOut })
    }

    @Test
    fun `reads diagnosis codes next to a dropdown button, a number and text, from a drawn form`() {
        val form = DrawnForm()
        val ocr = Ocr()
        val fields = listOf(form.mainCode, form.number, form.mainText, form.secondaryCode, form.system)
        val page = ocr.readPage(form.image, fields)
        // What this test is about: the layout puts the button and the code in one line; it is cut just inside the
        // code field, past the cell's frame. With the frame in it, the code was read with 0 % confidence.
        val codeSegments = page.segments.filter { it.field.type == FieldType.CODE }
        assertEquals(listOf(form.mainCode.x + 4, form.secondaryCode.x + 4), codeSegments.map { it.box.x }, "the code lines start at the button")

        val read = page.values
        assertEquals("M75.1", read.getValue(form.mainCode).text)
        assertEquals("D47.4", read.getValue(form.secondaryCode).text)
        assertEquals("9612084", read.getValue(form.number).text)
        assertEquals("Myelofibrose", read.getValue(form.mainText).text)
        assertEquals("ICPC-2", read.getValue(form.system).text)
        // Reading letters after code and number fields: the allowed characters were reset.
        assertEquals("Test\n", ocr.parse(ImageIO.read(javaClass.getResourceAsStream("/img.png"))))
        read.forEach { (field, text) ->
            text.lines.forEach { line ->
                assertTrue(field.rectangle.contains(Rectangle(line.x, line.y, line.width, line.height)), "$line in ${field.navn}")
            }
        }
    }

    @Test
    fun `reads a short HPR number in a wide cell of the template`() {
        val image = ImageIO.read(File("src/main/resources/templates/0.2019/page-3/template.png"))
        val fields = TemplateFiles.read(File("src/main/resources/templates/0.2019/page-3/page-3.json")).felter
        val hpr = fields.single { it.navn == "bekreftelse.hpr" }
        draw(image) {
            color = Color.BLACK
            font = Font(Font.MONOSPACED, Font.PLAIN, 38)
            drawString("9612084", hpr.x + 24, hpr.y + 80)
        }
        assertEquals("9612084", Ocr().readFields(image, fields).getValue(hpr).text)
    }

    @Test
    fun `reads a filled-in template page, and detects its ticked checkboxes instead of reading them as text`() {
        val image = ImageIO.read(File("src/main/resources/templates/03.2019/page-1/template.png"))
        val fields = TemplateFiles.read(File("src/main/resources/templates/03.2019/page-1/page-1.json")).felter
        fun field(name: String): Felter = fields.single { it.navn == name }
        val values = mapOf(
            "hoveddiagnose.system" to "ICPC-2", "hoveddiagnose.kode" to "L-84", "hoveddiagnose.text" to "Ryggsyndrom",
            "bidiagnose.system" to "ICD-10", "bidiagnose.kode" to "D47.4", "bidiagnose.text" to "Myelofibrose",
        )
        val checkboxes = fields.filter { it.type == FieldType.CHECKBOX }
        assertEquals(setOf("diagnose.svangerskapsrelatert", "diagnose.yrkesskade", "diagnose.skjermes"), checkboxes.map { it.navn }.toSet())
        draw(image) {
            color = Color.BLACK
            font = Font(Font.MONOSPACED, Font.PLAIN, 42)
            values.forEach { (name, value) -> drawString(value, field(name).x + 22, field(name).y + 80) }
            // Ticked: an X in each box, which OCR would read as a letter.
            checkboxes.forEach { drawString("X", it.x + 25, it.y + 50) }
        }
        val page = Ocr().readPage(image, fields)
        values.forEach { (name, value) -> assertEquals(value, page.values.getValue(field(name)).text, name) }
        assertTrue(page.segments.none { it.field in checkboxes })
        checkboxes.forEach { assertEquals(FieldText(emptyList()), page.values.getValue(it), it.navn) }
        assertEquals(checkboxes.associateWith { CheckboxState.CHECKED }, page.checkboxes.mapValues { it.value.state })
    }

    @Test
    fun `the empty checkboxes of every template page are unchecked`() {
        for (template in listOf("0.2019", "03.2019")) {
            for (page in 1..3) {
                val folder = File("src/main/resources/templates/$template/page-$page")
                val fields = TemplateFiles.read(folder.resolve("page-$page.json")).felter
                val checkboxes = readCheckboxes(ImageIO.read(folder.resolve("template.png")), fields)
                assertEquals(fields.count { it.type == FieldType.CHECKBOX }, checkboxes.size, "$template page $page")
                checkboxes.forEach { (field, reading) -> assertEquals(CheckboxState.UNCHECKED, reading.state, "$template page $page ${field.navn}: ${reading.coverageText}") }
            }
        }
    }

    @Test
    fun `the diagnosis code fields of both templates are code fields`() {
        for (template in listOf("0.2019", "03.2019")) {
            val fields = TemplateFiles.read(File("src/main/resources/templates/$template/page-1/page-1.json")).felter
            for (name in listOf("hoveddiagnose.kode", "bidiagnose.kode")) {
                assertEquals(FieldType.CODE, fields.single { it.navn == name }.type, "$template $name")
            }
        }
    }

    /**
     * Two diagnosis rows as on the form, Kodesystem | dropdown button | Kode | Diagnose, and a number row, with 3 px
     * cell frames. Each field is a cell's white inside grown by 1 px, as the template editor makes them.
     */
    internal class DrawnForm {
        val image = BufferedImage(2400, 600, BufferedImage.TYPE_INT_RGB)
        private val columns = listOf(100, 600, 700, 1180, 2200)
        private val rows = listOf(60, 185, 310, 435)
        val system = field(0, 0, "hoveddiagnose.system", FieldType.TEXT)
        val mainCode = field(2, 0, "hoveddiagnose.kode", FieldType.CODE)
        val mainText = field(3, 0, "hoveddiagnose.text", FieldType.TEXT)
        val secondaryCode = field(2, 1, "bidiagnose.kode", FieldType.CODE)
        val number = field(2, 2, "bekreftelse.hpr", FieldType.NUMBER)

        init {
            draw(image) {
                color = Color.WHITE
                fillRect(0, 0, image.width, image.height)
                color = Color.BLACK
                columns.forEach { fillRect(it, rows.first(), 3, rows.last() - rows.first() + 3) }
                rows.forEach { fillRect(columns.first(), it, columns.last() - columns.first() + 3, 3) }
                font = Font(Font.MONOSPACED, Font.PLAIN, 44)
                for (row in 0..1) {
                    val top = rows[row]
                    drawString("ICPC-2", 125, top + 80)
                    // The dropdown button: a box with a filled triangle, in the cell between Kodesystem and Kode.
                    drawRect(615, top + 30, 50, 64)
                    fillPolygon(intArrayOf(625, 655, 640), intArrayOf(top + 55, top + 55, top + 75), 3)
                    drawString(if (row == 0) "M75.1" else "D47.4", 725, top + 80)
                }
                drawString("Myelofibrose", 1205, rows[0] + 80)
                drawString("9612084", 725, rows[2] + 80)
            }
        }

        private fun field(column: Int, row: Int, name: String, type: FieldType): Felter {
            val x = columns[column] + 3
            val y = rows[row] + 3
            return Felter(x - 1, y - 1, columns[column + 1] - x + 2, rows[row + 1] - y + 2, name, name, type)
        }
    }
}

/** One framed cell per row, each a field of its type with its value written in it, as the template editor makes them. */
internal class DrawnCells(vararg cells: Pair<FieldType, String>) {
    val values = cells.map { it.second }
    val image = BufferedImage(1000, 60 + 125 * cells.size + 60, BufferedImage.TYPE_INT_RGB)
    val fields = cells.mapIndexed { row, (type, _) -> Felter(102, 62 + 125 * row, 597, 124, "${type.name.lowercase()}.$row", "cell$row", type) }

    init {
        val graphics = image.createGraphics()
        try {
            graphics.color = Color.WHITE
            graphics.fillRect(0, 0, image.width, image.height)
            graphics.color = Color.BLACK
            graphics.fillRect(100, 60, 3, 125 * cells.size + 3)
            graphics.fillRect(700, 60, 3, 125 * cells.size + 3)
            for (row in 0..cells.size) graphics.fillRect(100, 60 + 125 * row, 603, 3)
            graphics.font = Font(Font.MONOSPACED, Font.PLAIN, 44)
            values.forEachIndexed { row, value -> graphics.drawString(value, 130, 60 + 125 * row + 80) }
        } finally {
            graphics.dispose()
        }
    }
}

private fun draw(image: BufferedImage, block: Graphics2D.() -> Unit) {
    val graphics = image.createGraphics()
    try {
        graphics.block()
    } finally {
        graphics.dispose()
    }
}
