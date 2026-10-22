package no.nav.tsm.sykmelding.ocr

import no.nav.tsm.sykmelding.model.CheckboxState
import org.example.no.nav.tsm.ocr.template.template.Felter
import java.awt.image.BufferedImage
import java.util.Locale
import kotlin.math.ceil

data class CheckboxReading(
    val state: CheckboxState,
    val darkCoverage: Double?,
    val inkCoverage: Double?,
) {
    val coverageText: String
        get() = if (darkCoverage == null || inkCoverage == null) "too small to measure"
            else "${percent(darkCoverage)} dark, ${percent(inkCoverage)} ink"
}


val CHECKBOX_RULE: String =
    "Checked at ${percent(CHECKED_COVERAGE, 0)} dark or more (darker than mid-grey); unchecked at ${percent(EMPTY_COVERAGE, 0)} " +
        "ink or less (anything but near-white); uncertain in between. Measured inside the box, its frame and a " +
        "${percent(BORDER_FRACTION, 0)} margin on each side left out."

private const val BORDER_FRACTION = 0.20
private const val MIN_INTERIOR_SIZE = 8
private const val DARK_THRESHOLD = 128
private const val POSSIBLE_INK_THRESHOLD = 224
const val CHECKED_COVERAGE = 0.10
private const val EMPTY_COVERAGE = 0.02

/** Expects an aligned page and a field tightly enclosing the checkbox, including its printed border. */
fun detectCheckbox(image: BufferedImage, field: Felter): CheckboxReading {
    require(field.bredde > 0 && field.hoyde > 0) {
        "Checkbox '${field.navn}' (${field.id}) has invalid dimensions."
    }
    require(
        field.x >= 0 && field.y >= 0 &&
            field.x <= image.width - field.bredde && field.y <= image.height - field.hoyde
    ) { "Checkbox '${field.navn}' (${field.id}) extends outside the incoming document." }

    // Ignore the frame and a small alignment margin on each side.
    val insetX = ceil(field.bredde * BORDER_FRACTION).toInt()
    val insetY = ceil(field.hoyde * BORDER_FRACTION).toInt()
    val width = field.bredde - 2 * insetX
    val height = field.hoyde - 2 * insetY
    if (width < MIN_INTERIOR_SIZE || height < MIN_INTERIOR_SIZE) return CheckboxReading(CheckboxState.UNCERTAIN, null, null)

    var darkPixels = 0
    var possibleInkPixels = 0
    for (y in field.y + insetY until field.y + field.hoyde - insetY) {
        for (x in field.x + insetX until field.x + field.bredde - insetX) {
            val brightness = brightnessOnWhite(image.getRGB(x, y))
            if (brightness < DARK_THRESHOLD) darkPixels++
            if (brightness < POSSIBLE_INK_THRESHOLD) possibleInkPixels++
        }
    }
    val area = width.toDouble() * height
    val dark = darkPixels / area
    val ink = possibleInkPixels / area
    val state = when {
        dark >= CHECKED_COVERAGE -> CheckboxState.CHECKED
        ink <= EMPTY_COVERAGE -> CheckboxState.UNCHECKED
        else -> CheckboxState.UNCERTAIN
    }
    return CheckboxReading(state, dark, ink)
}

private fun percent(share: Double, decimals: Int = 1): String = "%.${decimals}f %%".format(Locale.ROOT, share * 100)

private fun brightnessOnWhite(argb: Int): Int {
    val alpha = argb ushr 24
    val red = (argb ushr 16) and 0xff
    val green = (argb ushr 8) and 0xff
    val blue = argb and 0xff
    val brightness = (299 * red + 587 * green + 114 * blue) / 1000
    return (brightness * alpha + 255 * (255 - alpha)) / 255
}
