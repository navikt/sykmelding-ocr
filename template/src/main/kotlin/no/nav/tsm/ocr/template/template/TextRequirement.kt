package org.example.no.nav.tsm.ocr.template.template

import java.awt.Rectangle
import kotlin.math.roundToInt

/**
 * Text a page must contain before it is aligned to a template page: a cheap check that runs before feature matching,
 * so pages without the text are rejected in a fraction of the time. The page's text boxes are found with OpenCV and
 * read with Tesseract, the biggest text first, until [minFound] of [words] are found or [maxBoxes] boxes are read.
 * A word or phrase is found when the text read contains it with at most about one wrong letter in six (OCR errors),
 * ignoring case, spaces and punctuation. [region] limits the search to part of the page.
 *
 * Only functions here, no computed properties: Jackson would write those to the template JSON.
 */
data class TextRequirement(
    val words: List<String> = emptyList(),
    val minFound: Int = 1,
    val region: TextRegion? = null,
    val maxBoxes: Int = DEFAULT_MAX_TEXT_BOXES,
) {
    init {
        require(minFound >= 1) { "At least 1 word must be found." }
        require(maxBoxes in 1..MAX_TEXT_BOXES) { "Read between 1 and $MAX_TEXT_BOXES text boxes." }
    }

    /** The words and phrases to look for: trimmed, without blanks and duplicates. */
    fun phrases(): List<String> = words.map { it.trim() }.filter { it.isNotEmpty() }.distinct()

    /** Whether there is anything to check; a requirement without words lets every page through. */
    fun hasWords(): Boolean = phrases().isNotEmpty()

    /** How many of the [phrases] must be found: [minFound], but never more than there are. */
    fun needed(): Int = minFound.coerceAtMost(phrases().size)

    fun describe(): String {
        val phrases = phrases()
        if (phrases.isEmpty()) return "no required text"
        val list = phrases.joinToString(", ") { "«$it»" }
        val count = when {
            phrases.size == 1 -> list
            needed() == phrases.size -> "all of $list"
            needed() == 1 -> "one of $list"
            else -> "${needed()} of $list"
        }
        return count + (region?.let { " in ${it.describe()}" } ?: "")
    }
}

/** Part of a page as fractions (0–1) of its width and height; for example the top quarter is (0, 0, 1, 0.25). */
data class TextRegion(val left: Double = 0.0, val top: Double = 0.0, val right: Double = 1.0, val bottom: Double = 1.0) {
    init {
        require(left in 0.0..1.0 && right in 0.0..1.0 && top in 0.0..1.0 && bottom in 0.0..1.0) { "The area must be within the page (0–100 %)." }
        require(left < right && top < bottom) { "The area must have a size: left before right, top above bottom." }
    }

    /** The region in pixels of a [width] × [height] page. */
    fun pixels(width: Int, height: Int): Rectangle {
        val x = (left * width).roundToInt()
        val y = (top * height).roundToInt()
        return Rectangle(x, y, ((right * width).roundToInt() - x).coerceAtLeast(1), ((bottom * height).roundToInt() - y).coerceAtLeast(1))
    }

    fun describe(): String = "%.0f–%.0f %% across, %.0f–%.0f %% down the page".format(100 * left, 100 * right, 100 * top, 100 * bottom)

    companion object {
        /** [bounds] on a [width] × [height] page as a region, clipped to the page. */
        fun of(bounds: Rectangle, width: Int, height: Int): TextRegion {
            val clipped = bounds.intersection(Rectangle(0, 0, width, height))
            require(!clipped.isEmpty) { "The area is outside the page." }
            return TextRegion(clipped.x.toDouble() / width, clipped.y.toDouble() / height,
                (clipped.x + clipped.width).toDouble() / width, (clipped.y + clipped.height).toDouble() / height)
        }
    }
}

/**
 * Text boxes read per check unless a requirement says otherwise. A 400-DPI form page has about 40–70 boxes, all read
 * in about half a second; the limit bounds the time spent on scans with a lot of handwriting or noise.
 */
const val DEFAULT_MAX_TEXT_BOXES = 100

const val MAX_TEXT_BOXES = 2000
