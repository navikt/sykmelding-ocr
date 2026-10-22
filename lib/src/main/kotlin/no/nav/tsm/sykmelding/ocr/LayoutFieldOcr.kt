package no.nav.tsm.sykmelding.ocr

import org.bytedeco.tesseract.PageIterator
import org.bytedeco.tesseract.global.tesseract.PTIsTextType
import org.bytedeco.tesseract.global.tesseract.RIL_TEXTLINE
import org.bytedeco.tesseract.global.tesseract.TessPageIteratorDelete
import org.example.no.nav.tsm.ocr.template.template.FieldType
import org.example.no.nav.tsm.ocr.template.template.Felter
import org.example.no.nav.tsm.ocr.template.template.PdfTemplateImages
import org.example.no.nav.tsm.ocr.template.template.rectangle
import java.awt.Color
import java.awt.Rectangle
import java.awt.image.BufferedImage

data class FieldSegment(val field: Felter, val box: Rectangle, val lines: List<ReadLine> = emptyList())

data class ReadLine(val region: TextRegion, val leftOut: LeftOut?)

/**
 * One aligned page read: the text [values] of every field (empty for checkboxes, which text OCR skips), the layout's
 * line [segments] they were read from, and each checkbox field's state and ink coverage ([checkboxes], see
 * [detectCheckbox]).
 */
data class PageRead(
    val segments: List<FieldSegment>,
    val values: Map<Felter, FieldText>,
    val checkboxes: Map<Felter, CheckboxReading> = emptyMap(),
)

fun Ocr.readFields(image: BufferedImage, fields: List<Felter>): Map<Felter, FieldText> = readPage(image, fields).values

fun Ocr.readPage(image: BufferedImage, fields: List<Felter>): PageRead {
    val page = grayscale(image)
    val segments = fieldSegments(layoutLines(page), fields)
    return pageRead(fields, segments.map { it to readSegment(page, it) }, readCheckboxes(image, fields))
}

fun readCheckboxes(image: BufferedImage, fields: List<Felter>): Map<Felter, CheckboxReading> =
    fields.filter { it.type == FieldType.CHECKBOX }.associateWith { detectCheckbox(image, it) }

private fun Ocr.layoutLines(page: BufferedImage): List<Rectangle> = withEngine { engine ->
    engine.setPage(page, PSM_MODE.PSM_SPARSE_TEXT, PdfTemplateImages.DPI)
    textLineBoxes(engine.AnalyseLayout())
}

private fun textLineBoxes(iterator: PageIterator?): List<Rectangle> {
    if (iterator == null || iterator.isNull) return emptyList()
    val left = IntArray(1)
    val top = IntArray(1)
    val right = IntArray(1)
    val bottom = IntArray(1)
    try {
        return buildList {
            do {
                if (iterator.Empty(RIL_TEXTLINE) || !PTIsTextType(iterator.BlockType())) continue
                if (iterator.BoundingBox(RIL_TEXTLINE, left, top, right, bottom)) {
                    add(Rectangle(left[0], top[0], right[0] - left[0], bottom[0] - top[0]))
                }
            } while (iterator.Next(RIL_TEXTLINE))
        }
    } finally {
        TessPageIteratorDelete(iterator)
    }
}

val FieldType.skipsOcr: Boolean get() = this == FieldType.CHECKBOX

internal fun fieldSegments(lines: List<Rectangle>, fields: List<Felter>): List<FieldSegment> =
    fields.filter { !it.type.skipsOcr }.flatMap { field ->
        lines.mapNotNull { segment(field, it) }.sortedWith(compareBy({ it.box.y }, { it.box.x }))
    }

private const val FRAME_WIDTH = 4

private fun segment(field: Felter, line: Rectangle): FieldSegment? {
    val box = line.intersection(field.rectangle.shrunkBy(FRAME_WIDTH))
    return when {
        box.isEmpty -> null
        isMostlyOutside(line, box) -> null
        isSliver(line, box) -> null
        else -> FieldSegment(field, box)
    }
}

private fun Rectangle.shrunkBy(pixels: Int) = Rectangle(x + pixels, y + pixels, width - 2 * pixels, height - 2 * pixels)

private fun isMostlyOutside(line: Rectangle, box: Rectangle) = 2 * box.height < line.height

// The line runs on past the field's side and leaves only a letter or so in it: the edge of the next field's text.
private fun isSliver(line: Rectangle, box: Rectangle) = box.width < line.width && 2 * box.width < line.height
private const val DIGITS = "0123456789"

val FieldType.allowedCharacters: String?
    get() = when (this) {
        FieldType.CODE -> "ABCDEFGHIJKLMNOPQRSTUVWXYZ$DIGITS .-"
        FieldType.NUMBER -> "$DIGITS +%-"
        FieldType.IDENT -> "$DIGITS -"
        FieldType.DATE -> "$DIGITS .-/"
        FieldType.TEXT, FieldType.CHECKBOX -> null
    }

private const val PADDING = 16

private fun Ocr.readSegment(page: BufferedImage, segment: FieldSegment): List<TextRegion> {
    val lines = detectText(crop(page, segment.box), PSM_MODE.PSM_SINGLE_LINE, PdfTemplateImages.DPI, segment.field.type.allowedCharacters)
    return lines.map { it.translate(segment.box.x - PADDING, segment.box.y - PADDING) }
}

private fun TextRegion.translate(dx: Int, dy: Int) = copy(x = x + dx, y = y + dy, baseline = baseline + dy)

private fun crop(page: BufferedImage, box: Rectangle): BufferedImage {
    val image = BufferedImage(box.width + 2 * PADDING, box.height + 2 * PADDING, BufferedImage.TYPE_BYTE_GRAY)
    val graphics = image.createGraphics()
    try {
        graphics.color = Color.WHITE
        graphics.fillRect(0, 0, image.width, image.height)
        graphics.drawImage(page.getSubimage(box.x, box.y, box.width, box.height), PADDING, PADDING, null)
    } finally {
        graphics.dispose()
    }
    return image
}

fun pageRead(
    fields: List<Felter>,
    read: List<Pair<FieldSegment, List<TextRegion>>>,
    checkboxes: Map<Felter, CheckboxReading> = emptyMap(),
): PageRead {
    val segments = read.groupBy { (segment, _) -> segment.field }.flatMap { (field, own) -> withVerdicts(field, own) }
    val values = fields.associateWith { field ->
        val used = segments.filter { it.field == field }.flatMap { it.lines }.filter { it.leftOut == null }
        FieldText(textLines(used.map { it.region }).flatten())
    }
    return PageRead(segments, values, checkboxes)
}

private fun withVerdicts(field: Felter, read: List<Pair<FieldSegment, List<TextRegion>>>): List<FieldSegment> {
    val verdicts = fieldLineVerdicts(read.flatMap { (_, lines) -> lines }, field.rectangle).iterator()
    return read.map { (segment, lines) -> segment.copy(lines = lines.map { ReadLine(it, verdicts.next()) }) }
}
