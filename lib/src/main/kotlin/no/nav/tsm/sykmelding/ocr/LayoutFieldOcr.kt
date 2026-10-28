package no.nav.tsm.sykmelding.ocr

import no.nav.tsm.sykmelding.utils.toBufferedImage
import no.nav.tsm.sykmelding.utils.toMat
import no.nav.tsm.sykmelding.utils.use
import org.bytedeco.tesseract.PageIterator
import org.bytedeco.tesseract.global.tesseract.PTIsTextType
import org.bytedeco.tesseract.global.tesseract.RIL_TEXTLINE
import org.bytedeco.tesseract.global.tesseract.TessPageIteratorDelete
import no.nav.tsm.sykmelding.template.FieldType
import no.nav.tsm.sykmelding.template.Felter
import no.nav.tsm.sykmelding.template.PdfTemplateImages
import no.nav.tsm.sykmelding.template.rectangle
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc.INTER_AREA
import org.opencv.imgproc.Imgproc.INTER_CUBIC
import org.opencv.imgproc.Imgproc.resize
import java.awt.Color
import java.awt.Rectangle
import java.awt.image.BufferedImage
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt


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

/**
 * Reads [fields] on [image], a page aligned to their template: in template pixels ([PdfTemplateImages.DPI]).
 *
 * Tesseract reads the page at [dpi]: at another resolution than the template's, the page is resampled to it first,
 * and every line box and line read is given back in template pixels, so they still line up with [fields].
 * Checkboxes are always judged on [image] itself.
 */
fun Ocr.readPage(image: BufferedImage, fields: List<Felter>, dpi: Int = PdfTemplateImages.DPI): PageRead {
    val page = OcrPage(grayscale(image), dpi)
    val segments = fieldSegments(layoutLines(page), fields)
    return pageRead(fields, segments.map { it to readSegment(page, it) }, readCheckboxes(image, fields))
}

fun readCheckboxes(image: BufferedImage, fields: List<Felter>): Map<Felter, CheckboxReading> =
    fields.filter { it.type == FieldType.CHECKBOX }.associateWith { detectCheckbox(image, it) }

/**
 * A page as Tesseract is given it: [template] (gray, in template pixels) resampled to [dpi] as [image], with
 * conversions of boxes between the two.
 */
private class OcrPage(template: BufferedImage, val dpi: Int) {
    init {
        require(dpi > 0) { "The OCR resolution must be positive: $dpi" }
    }

    private val same = dpi == PdfTemplateImages.DPI
    val image: BufferedImage = if (same) template else resampled(template, dpi.toDouble() / PdfTemplateImages.DPI)
    /** White around each line cut out, as in template pixels but at [dpi]. */
    val padding: Int = if (same) PADDING else maxOf(1, (PADDING * dpi.toDouble() / PdfTemplateImages.DPI).roundToInt())
    /** What Tesseract is told: [dpi], within the resolutions it believes (it warns and uses the nearest otherwise). */
    val resolution: Int = dpi.coerceIn(TESSERACT_RESOLUTIONS)

    /** [box] in template pixels as the smallest box of [image]'s pixels covering it, within [image]. */
    fun toOcr(box: Rectangle): Rectangle =
        if (same) box else scaledOut(box, PdfTemplateImages.DPI, dpi).intersection(Rectangle(0, 0, image.width, image.height))

    /** [box] in [image]'s pixels as the smallest box of template pixels covering it. */
    fun toTemplate(box: Rectangle): Rectangle = if (same) box else scaledOut(box, dpi, PdfTemplateImages.DPI)

    /** [region], read in [image]'s pixels, in template pixels. */
    fun toTemplate(region: TextRegion): TextRegion {
        if (same) return region
        val box = toTemplate(Rectangle(region.x, region.y, region.width, region.height))
        val baseline = (region.baseline * PdfTemplateImages.DPI.toDouble() / dpi).roundToInt()
        return region.copy(x = box.x, y = box.y, width = box.width, height = box.height, baseline = baseline)
    }
}

/** Tesseract's credible resolutions (kMinCredibleResolution..kMaxCredibleResolution). */
val TESSERACT_RESOLUTIONS = 70..2400

/** [box] at [from] DPI as the smallest box at [to] DPI that covers it. */
private fun scaledOut(box: Rectangle, from: Int, to: Int): Rectangle {
    val scale = to.toDouble() / from
    val left = floor(box.x * scale).toInt()
    val top = floor(box.y * scale).toInt()
    val right = ceil((box.x + box.width) * scale).toInt()
    val bottom = ceil((box.y + box.height) * scale).toInt()
    return Rectangle(left, top, right - left, bottom - top)
}

/** The gray [page] [scale] times its size: averaged when shrunk, so thin lines and print survive; bicubic when enlarged. */
private fun resampled(page: BufferedImage, scale: Double): BufferedImage = toMat(page).use { source ->
    Mat().use { target ->
        val size = Size(maxOf(1, (page.width * scale).roundToInt()).toDouble(), maxOf(1, (page.height * scale).roundToInt()).toDouble())
        resize(source, target, size, 0.0, 0.0, if (scale < 1) INTER_AREA else INTER_CUBIC)
        toBufferedImage(target)
    }
}

private fun Ocr.layoutLines(page: OcrPage): List<Rectangle> = withEngine { engine ->
    engine.setPage(page.image, PsmMode.PSM_SPARSE_TEXT, page.resolution)
    textLineBoxes(engine.AnalyseLayout()).map(page::toTemplate)
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

private fun Ocr.readSegment(page: OcrPage, segment: FieldSegment): List<TextRegion> {
    val box = page.toOcr(segment.box)
    if (box.isEmpty) return emptyList()
    val lines = detectText(crop(page.image, box, page.padding), PsmMode.PSM_SINGLE_LINE, page.resolution, segment.field.type.allowedCharacters)
    return lines.map { page.toTemplate(it.translate(box.x - page.padding, box.y - page.padding)) }
}

private fun TextRegion.translate(dx: Int, dy: Int) = copy(x = x + dx, y = y + dy, baseline = baseline + dy)

private fun crop(page: BufferedImage, box: Rectangle, padding: Int): BufferedImage {
    val image = BufferedImage(box.width + 2 * padding, box.height + 2 * padding, BufferedImage.TYPE_BYTE_GRAY)
    val graphics = image.createGraphics()
    try {
        graphics.color = Color.WHITE
        graphics.fillRect(0, 0, image.width, image.height)
        graphics.drawImage(page.getSubimage(box.x, box.y, box.width, box.height), padding, padding, null)
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
