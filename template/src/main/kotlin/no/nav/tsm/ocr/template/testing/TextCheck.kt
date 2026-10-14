package no.nav.tsm.ocr.template.testing

import no.nav.tsm.sykmelding.ocr.Ocr
import no.nav.tsm.sykmelding.ocr.PSM_MODE
import no.nav.tsm.sykmelding.utils.findTextGroups
import org.apache.pdfbox.Loader
import org.apache.pdfbox.rendering.ImageType
import org.apache.pdfbox.rendering.PDFRenderer
import org.bytedeco.opencv.global.opencv_core.CV_8UC1
import org.bytedeco.opencv.opencv_core.Mat
import org.example.no.nav.tsm.ocr.template.template.TextRequirement
import java.awt.Color
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.awt.image.DataBufferByte
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToInt

/*
 * The text check: before a page is aligned to a template page (feature detection, matching, line fit: seconds), check
 * that it contains the template page's required text (see [TextRequirement]). Text boxes are found with OpenCV
 * ([findTextGroups]) and read with Tesseract one at a time, the biggest text first, and only until the words are
 * found. A page with a form's heading on it is usually accepted after reading a handful of boxes; a page without it
 * costs reading at most [TextRequirement.maxBoxes] boxes.
 */

/** Pages are read at this resolution: enough for Tesseract on form text, about half the pixels of 400 DPI. */
internal const val TEXT_DPI = 300

/** Text boxes are found on a copy at this share of [TEXT_DPI]: a quarter of the pixels, and lines of text stay apart. */
private const val DETECT_SCALE = 0.5

/** One text box that was read: [bounds] in pixels of the page at [TEXT_DPI], [text] as Tesseract read it. */
internal data class TextBox(val bounds: Rectangle, val text: String)

/**
 * One [requirement] checked on one page. [found] maps each phrase found to the box it was read in. [read] lists the
 * boxes looked at, including those read earlier for other checks of the same page; [boxes] is the number of text
 * boxes on the page (in the requirement's region). [error] says why the page could not be checked.
 */
internal data class TextCheck(
    val requirement: TextRequirement,
    val found: Map<String, TextBox>,
    val read: List<TextBox>,
    val boxes: Int,
    val millis: Long,
    val error: String? = null,
) {
    val passed: Boolean get() = error == null && found.size >= requirement.needed()

    /** The page lacks the required text, so aligning it can be skipped. A check that failed rejects nothing. */
    val rejects: Boolean get() = error == null && !passed

    val missing: List<String> get() = requirement.phrases().filter { it !in found }

    val summary: String
        get() {
            val reading = " (read ${read.size} of $boxes text box${if (boxes == 1) "" else "es"} in %.1f s)".format(millis / 1000.0)
            return when {
                error != null -> "Could not check the required text, so the page was aligned anyway: $error"
                passed -> "Found " + found.entries.joinToString(", ") { (phrase, box) -> "«$phrase» in «${box.text.oneLine()}»" } + reading + "."
                else -> "Required text not found: ${requirement.describe()}; missing ${missing.joinToString(", ") { "«$it»" }}$reading. Not aligned."
            }
        }

    /** For tables: the words found, or why not. */
    val shortText: String
        get() = when {
            error != null -> "? text"
            passed -> "✓ " + found.keys.joinToString(", ")
            else -> "✗ " + missing.joinToString(", ")
        }
}

/**
 * One page prepared for text checks: a gray copy at [TEXT_DPI]. Its text boxes are found on first use, and each box
 * is read at most once, so checks for several templates share the reading. Checks run one at a time.
 */
internal class PageText(val image: BufferedImage, private val recognize: (BufferedImage) -> String = ::readTextBox) {
    /** Text boxes, most promising first: the tallest text (headings) first, then from the top. */
    val boxes: List<Rectangle> by lazy { findTextBoxes(image) }
    private val texts = HashMap<Int, String>()

    @Synchronized
    fun check(requirement: TextRequirement): TextCheck {
        val started = System.nanoTime()
        val patterns = requirement.phrases().map(::SearchPhrase)
        val needed = requirement.needed()
        val area = requirement.region?.pixels(image.width, image.height)
        val candidates = boxes.indices.filter { area == null || boxes[it].let { box -> area.contains(box.centerX, box.centerY) } }
        val allowed = candidates.take(requirement.maxBoxes)
        val found = LinkedHashMap<String, TextBox>()
        val read = mutableListOf<TextBox>()
        fun consider(index: Int, text: String) {
            val box = TextBox(boxes[index], text).also(read::add)
            val searched = SearchText(text)
            patterns.forEach { pattern ->
                if (pattern.phrase !in found && pattern.foundIn(searched)) found[pattern.phrase] = box
            }
        }
        // Boxes read for another template's check cost nothing, so they are looked at first.
        for (index in allowed) {
            if (found.size >= needed) break
            texts[index]?.let { consider(index, it) }
        }
        for (index in allowed) {
            if (found.size >= needed) break
            if (index in texts) continue
            val text = recognize(crop(boxes[index]))
            texts[index] = text
            consider(index, text)
        }
        return TextCheck(requirement, found, read, candidates.size, (System.nanoTime() - started) / 1_000_000)
    }

    /** The box with a margin: Tesseract reads text that touches the image edge badly. */
    private fun crop(box: Rectangle): BufferedImage {
        val margin = maxOf(4, box.height / 4)
        val area = Rectangle(box.x - margin, box.y - margin, box.width + 2 * margin, box.height + 2 * margin)
            .intersection(Rectangle(0, 0, image.width, image.height))
        return image.getSubimage(area.x, area.y, area.width, area.height)
    }

    companion object {
        /** [page] of [file] (a PDF, or an image whose DPI metadata says how to scale it; 400 DPI when missing). */
        fun load(file: File, page: Int): PageText = PageText(render(file, page))

        /** A page image at [dpi], such as a 400-DPI template image. */
        fun of(image: BufferedImage, dpi: Double = 400.0): PageText = PageText(grayCopy(image, TEXT_DPI / dpi))

        fun render(file: File, page: Int): BufferedImage = if (file.extension.equals("pdf", true)) {
            Loader.loadPDF(file).use { document ->
                require(page in 1..document.numberOfPages) { "Invalid PDF page: $page" }
                PDFRenderer(document).renderImageWithDPI(page - 1, TEXT_DPI.toFloat(), ImageType.GRAY)
            }
        } else {
            val input = TestInput.load(file, page)
            grayCopy(input.raw, TEXT_DPI / (input.dpi ?: 400.0))
        }
    }
}

/**
 * The text checks of one file's pages: each page is rendered and its boxes read at most once, however many template
 * pages it is checked against. Safe to use from several threads. [release] a page that is decided to free its image.
 */
internal class DocumentText(private val file: File, private val load: (File, Int) -> PageText = PageText::load) {
    private val pages = ConcurrentHashMap<Int, Lazy<PageText>>()

    /** Never throws: a page that cannot be read gives a check with an [TextCheck.error], which rejects nothing. */
    fun check(page: Int, requirement: TextRequirement): TextCheck {
        val started = System.nanoTime()
        fun elapsed() = (System.nanoTime() - started) / 1_000_000
        return try {
            pages.computeIfAbsent(page) { lazy { load(file, page) } }.value.check(requirement).copy(millis = elapsed())
        } catch (error: Exception) {
            TextCheck(requirement, emptyMap(), emptyList(), 0, elapsed(), error.message ?: error.toString())
        }
    }

    fun release(page: Int) {
        pages.remove(page)
    }
}

/** Tesseract on one text box, which may hold a line or two. Runs one box at a time across threads (one engine). */
internal fun readTextBox(box: BufferedImage): String = Ocr().parse(box, psmMode = PSM_MODE.PSM_SINGLE_BLOCK).orEmpty()

/** Text boxes of a gray page, tallest first; boxes taller than [maxHeight] (pictures, scribbles) are left out. */
internal fun findTextBoxes(page: BufferedImage, maxHeight: Int = page.height / 10): List<Rectangle> {
    val small = grayCopy(page, DETECT_SCALE)
    val groups = grayMat(small).use { findTextGroups(it) }
    val scale = page.width.toDouble() / small.width
    return groups.map { group ->
        group.bounds.use { Rectangle((it.x() * scale).roundToInt(), (it.y() * scale).roundToInt(),
            (it.width() * scale).roundToInt(), (it.height() * scale).roundToInt()) }
    }.filter { it.height <= maxHeight }
        .sortedWith(compareByDescending<Rectangle> { it.height }.thenBy { it.y }.thenBy { it.x })
}

/** Lower-case letters and digits of [text], and where each word starts: what phrases are looked for in. */
internal class SearchText(text: String) {
    val letters: String
    /** Whether a word starts at each index of [letters]. */
    val wordStarts: BooleanArray

    init {
        val letters = StringBuilder()
        val starts = mutableListOf<Boolean>()
        var inWord = false
        for (char in text) {
            if (char.isLetterOrDigit()) {
                letters.append(char.lowercaseChar())
                starts += !inWord
                inWord = true
            } else inWord = false
        }
        this.letters = letters.toString()
        wordStarts = starts.toBooleanArray()
    }
}

/**
 * A phrase to look for. OCR gets spaces and punctuation wrong, so they are ignored ([key]); a match must start where
 * a word starts, so «sykmelding» is not found in «friskmelding». [maxEdits] allows about one wrong, missing or extra
 * letter per six letters of each word: none in «til» or «NAV», one in «sykmelding».
 */
internal class SearchPhrase(val phrase: String) {
    private val words = SearchText(phrase).let { text -> text.letters.indices.filter { text.wordStarts[it] }.plus(text.letters.length).zipWithNext { a, b -> b - a } }
    val key: String = SearchText(phrase).letters
    val maxEdits: Int = words.sumOf { it / 6 }

    fun foundIn(text: SearchText): Boolean = containsApproximately(text, key, maxEdits)
}

/**
 * Whether [pattern] occurs in [text], starting where a word starts, with at most [maxEdits] letters wrong, missing or
 * extra. Sellers' algorithm (edit distance to the best-matching substring), with matches allowed to start at word starts only.
 */
internal fun containsApproximately(text: SearchText, pattern: String, maxEdits: Int): Boolean {
    if (pattern.isEmpty()) return true
    val letters = text.letters
    val blocked = pattern.length + maxEdits + 1
    // previous[i]: fewest edits that turn pattern[0, i) into text ending before the current letter.
    var previous = IntArray(pattern.length + 1) { if (letters.isNotEmpty()) it else blocked }
    var current = IntArray(pattern.length + 1)
    for (j in letters.indices) {
        val char = letters[j]
        current[0] = if (j + 1 < letters.length && text.wordStarts[j + 1]) 0 else blocked
        for (i in 1..pattern.length) {
            val replace = previous[i - 1] + if (pattern[i - 1] == char) 0 else 1
            current[i] = minOf(replace, previous[i] + 1, current[i - 1] + 1)
        }
        if (current[pattern.length] <= maxEdits) return true
        previous = current.also { current = previous }
    }
    return false
}

private fun String.oneLine() = lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.joinToString(" ").take(80)

/** [image] scaled by [scale] in 8-bit gray, transparency on white. */
private fun grayCopy(image: BufferedImage, scale: Double): BufferedImage {
    val width = (image.width * scale).roundToInt().coerceAtLeast(1)
    val height = (image.height * scale).roundToInt().coerceAtLeast(1)
    return BufferedImage(width, height, BufferedImage.TYPE_BYTE_GRAY).apply {
        createGraphics().let { g ->
            try {
                g.color = Color.WHITE
                g.fillRect(0, 0, width, height)
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
                g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
                g.drawImage(image, 0, 0, width, height, null)
            } finally { g.dispose() }
        }
    }
}

/** A TYPE_BYTE_GRAY image (as from [grayCopy]) as an 8-bit Mat. The caller owns the result. */
private fun grayMat(gray: BufferedImage): Mat {
    val bytes = (gray.raster.dataBuffer as DataBufferByte).data
    return Mat(gray.height, gray.width, CV_8UC1).apply { data().put(bytes, 0, gray.width * gray.height) }
}
