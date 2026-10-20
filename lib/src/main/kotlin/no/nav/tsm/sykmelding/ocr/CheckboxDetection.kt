package no.nav.tsm.sykmelding.ocr

import org.example.no.nav.tsm.ocr.template.template.Felter
import java.awt.image.BufferedImage
import kotlin.math.ceil

enum class CheckboxState { CHECKED, UNCHECKED, UNCERTAIN }

private const val BORDER_FRACTION = 0.20
private const val MIN_INTERIOR_SIZE = 8
private const val DARK_THRESHOLD = 128
private const val POSSIBLE_INK_THRESHOLD = 224
private const val CHECKED_COVERAGE = 0.10
private const val EMPTY_COVERAGE = 0.02

/** Expects an aligned page and a field tightly enclosing the checkbox, including its printed border. */
fun detectCheckbox(image: BufferedImage, field: Felter): CheckboxState {
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
    if (width < MIN_INTERIOR_SIZE || height < MIN_INTERIOR_SIZE) return CheckboxState.UNCERTAIN

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
    return when {
        darkPixels / area >= CHECKED_COVERAGE -> CheckboxState.CHECKED
        possibleInkPixels / area <= EMPTY_COVERAGE -> CheckboxState.UNCHECKED
        // Faint marks and intermediate coverage must not silently become an unchecked box.
        else -> CheckboxState.UNCERTAIN
    }
}

private fun brightnessOnWhite(argb: Int): Int {
    val alpha = argb ushr 24
    val red = (argb ushr 16) and 0xff
    val green = (argb ushr 8) and 0xff
    val blue = argb and 0xff
    val brightness = (299 * red + 587 * green + 114 * blue) / 1000
    return (brightness * alpha + 255 * (255 - alpha)) / 255
}
