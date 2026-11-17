package no.nav.tsm.ocr.template.testdata

import no.nav.tsm.sykmelding.ocr.TextPart
import no.nav.tsm.sykmelding.ocr.TextRegion
import java.awt.Color
import java.awt.Font
import java.awt.Graphics2D
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.font.FontRenderContext
import java.awt.font.TextLayout
import java.awt.image.BufferedImage
import kotlin.math.roundToInt

data class DrawnText(
    val text: String,
    val x: Int,
    val baseline: Int,
    val family: String,
    val size: Int,
    val bold: Boolean,
    val color: Color = Color.BLACK,
) {
    private fun layout() = TextLayout(text, Font(family, if (bold) Font.BOLD else Font.PLAIN, size), FRC)

    fun draw(graphics: Graphics2D) {
        graphics.color = color
        layout().draw(graphics, x.toFloat(), baseline.toFloat())
    }

    fun part(): TextPart {
        val layout = layout()
        fun region(from: Int, to: Int, text: String): TextRegion {
            val box = layout.getBlackBoxBounds(from, to).bounds
            return TextRegion(x + box.x, baseline + box.y, box.width, box.height, text, 100f, baseline, 0f)
        }
        return TextPart(region(0, text.length, text), WORD.findAll(text).map { region(it.range.first, it.range.last + 1, it.value) }.toList())
    }
}

data class PagePart(val part: TextPart, val drawn: DrawnText? = null)

class TestDataPage(private val scan: BufferedImage, private val scanned: List<TextPart>) {
    val background = BufferedImage(scan.width, scan.height, BufferedImage.TYPE_INT_RGB)

    var parts: List<PagePart> = scanned.map { PagePart(it) }
        private set

    init {
        val graphics = background.createGraphics()
        try {
            graphics.color = Color.WHITE
            graphics.fillRect(0, 0, background.width, background.height)
            graphics.drawImage(scan, 0, 0, null)
        } finally {
            graphics.dispose()
        }
    }

    fun drawTexts(graphics: Graphics2D) {
        graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        graphics.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON)
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        parts.forEach { it.drawn?.draw(graphics) }
    }

    fun image(): BufferedImage = BufferedImage(background.width, background.height, BufferedImage.TYPE_INT_RGB).also { image ->
        val graphics = image.createGraphics()
        try {
            graphics.drawImage(background, 0, 0, null)
            drawTexts(graphics)
        } finally {
            graphics.dispose()
        }
    }

    fun style(index: Int): DrawnText = parts[index].let { it.drawn ?: fitted(it.part.line, normalized(it.part.line.text)) }

    fun setText(index: Int, text: String) {
        val entered = normalized(text)
        val current = parts[index]
        when {
            entered.isEmpty() -> delete(index)
            entered == current.part.line.text -> Unit
            else -> redraw(index, current.drawn?.copy(text = entered) ?: fitted(current.part.line, entered))
        }
    }

    private fun fitted(line: TextRegion, text: String): DrawnText {
        fun ink(size: Int, of: String = text) = DrawnText(of, 0, 0, Font.SANS_SERIF, size, false).part().line
        val scanned = ink(PROBE_SIZE, normalized(line.text)).height
        var size = (if (scanned > 0) (PROBE_SIZE.toDouble() * line.height / scanned).roundToInt() else line.height).coerceAtLeast(MIN_FONT_SIZE)
        var ink = ink(size)
        if (ink.height > line.height) {
            size = (size.toDouble() * line.height / ink.height).toInt().coerceAtLeast(MIN_FONT_SIZE)
            ink = ink(size)
        }
        while (ink.height > line.height && size > MIN_FONT_SIZE) ink = ink(--size)
        val lowest = line.y - ink.y
        val highest = maxOf(lowest, line.y + line.height - ink.y - ink.height)
        return DrawnText(text, line.x - ink.x, line.baseline.coerceIn(lowest, highest), Font.SANS_SERIF, size, false, inkColor(box(line)))
    }

    fun type(text: String, x: Int, baseline: Int, digitHeight: Int) {
        val entered = normalized(text)
        require(entered.isNotEmpty()) { "A typed text cannot be blank" }
        val digit = DrawnText("0", 0, 0, Font.SANS_SERIF, PROBE_SIZE, false).part().line.height
        val size = (PROBE_SIZE.toDouble() * digitHeight / digit).roundToInt().coerceAtLeast(MIN_FONT_SIZE)
        val drawn = DrawnText(entered, x, baseline, Font.SANS_SERIF, size, false)
        parts = parts + PagePart(drawn.part(), drawn)
    }

    fun restore(area: Rectangle) {
        val back = scanned.filter { box(it.line).intersects(area) }
        val whole = back.fold(Rectangle(area)) { all, part -> all.union(box(part.line)) }
        parts = parts.filterNot { box(it.part.line).intersects(if (it.drawn != null) whole else area) } + back.map { PagePart(it) }
        val pixels = Rectangle(whole).apply { grow(ERASE_MARGIN, ERASE_MARGIN) }.intersection(Rectangle(0, 0, scan.width, scan.height))
        if (pixels.isEmpty) return
        val graphics = background.createGraphics()
        try {
            graphics.drawImage(scan.getSubimage(pixels.x, pixels.y, pixels.width, pixels.height), pixels.x, pixels.y, null)
        } finally {
            graphics.dispose()
        }
    }

    private fun inkColor(area: Rectangle): Color {
        val box = area.intersection(Rectangle(0, 0, background.width, background.height))
        if (box.isEmpty) return Color.BLACK
        val pixels = background.getRGB(box.x, box.y, box.width, box.height, null, 0, box.width)
        val darkest = pixels.minOf(::luminance)
        val lightest = pixels.maxOf(::luminance)
        if (lightest - darkest < MIN_INK_CONTRAST) return Color.BLACK
        val limit = darkest + (lightest - darkest) / 4
        var red = 0L
        var green = 0L
        var blue = 0L
        var count = 0
        for (pixel in pixels) {
            if (luminance(pixel) > limit) continue
            red += pixel shr 16 and 0xFF
            green += pixel shr 8 and 0xFF
            blue += pixel and 0xFF
            count++
        }
        return Color((red / count).toInt(), (green / count).toInt(), (blue / count).toInt())
    }

    fun redraw(index: Int, drawn: DrawnText) {
        require(drawn.text.isNotBlank()) { "A typed text cannot be blank" }
        val current = parts[index]
        val typed = PagePart(drawn.part(), drawn)
        if (current.drawn == null) erase(box(current.part.line))
        parts = parts.toMutableList().also { it[index] = typed }
    }

    fun delete(index: Int) {
        val current = parts[index]
        if (current.drawn == null) erase(box(current.part.line))
        parts = parts.filterIndexed { at, _ -> at != index }
    }

    fun deleteArea(area: Rectangle) {
        parts = parts.mapNotNull { current ->
            val line = current.part.line
            if (!box(line).intersects(area)) return@mapNotNull current
            if (current.drawn != null) return@mapNotNull null
            val (hit, kept) = current.part.words.partition { box(it).intersects(area) }
            when {
                hit.isEmpty() -> current
                kept.isEmpty() -> {
                    erase(box(line))
                    null
                }
                else -> {
                    hit.forEach { erase(box(it)) }
                    val bounds = kept.map(::box).reduce(Rectangle::union)
                    PagePart(TextPart(line.copy(x = bounds.x, y = bounds.y, width = bounds.width, height = bounds.height,
                        text = kept.joinToString(" ") { it.text }), kept))
                }
            }
        }
        fill(area)
    }

    private fun fill(r: Rectangle) {
        val graphics = background.createGraphics()
        try {
            graphics.color = Color.WHITE
            graphics.fill(r)
        } finally {
            graphics.dispose()
        }
    }

    private fun erase(r: Rectangle) = fill(Rectangle(r).apply { grow(ERASE_MARGIN, ERASE_MARGIN) })

    private fun normalized(text: String) = text.trim().replace(WHITESPACE, " ")
}

internal fun box(r: TextRegion) = Rectangle(r.x, r.y, r.width, r.height)

private fun luminance(rgb: Int) = (77 * (rgb shr 16 and 0xFF) + 150 * (rgb shr 8 and 0xFF) + 29 * (rgb and 0xFF)) shr 8

private val FRC = FontRenderContext(null, true, true)
private val WORD = Regex("\\S+")
private val WHITESPACE = Regex("\\s+")
private const val ERASE_MARGIN = 2
private const val MIN_INK_CONTRAST = 32
private const val PROBE_SIZE = 100
const val MIN_FONT_SIZE = 4
