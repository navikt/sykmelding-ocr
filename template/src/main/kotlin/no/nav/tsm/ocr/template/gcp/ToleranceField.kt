package no.nav.tsm.ocr.template.gcp

import java.util.Locale
import java.util.prefs.Preferences

/**
 * An editable combo box for the homography's tolerance: "Auto", one of the presets, or a number of template pixels
 * typed and confirmed with Enter. [auto] is what Auto comes to now (it follows the feature DPI). A valid tolerance
 * becomes [value] (null for Auto) and is stored under [key] in [settings]; an invalid one is explained to [complain],
 * and [value] is shown again.
 */
internal class ToleranceField(
    settings: Preferences?,
    key: String,
    complain: (String) -> Unit,
    private val auto: () -> Double,
) : ParsedComboField<Double?>(
    items = listOf(AUTO) + PRESETS.map(::number),
    initial = settings?.get(key, null)?.let { stored -> (parseTolerance(stored) as? ToleranceInput.Pixels)?.value },
    prototype = "Auto 00",
    parse = { typed ->
        when (val input = parseTolerance(typed)) {
            null -> null
            ToleranceInput.Auto -> Parsed(null)
            is ToleranceInput.Pixels -> Parsed(input.value)
        }
    },
    format = { tolerance -> tolerance?.let(::number) ?: AUTO },
    store = { tolerance -> if (tolerance == null) settings?.remove(key) else settings?.put(key, number(tolerance)) },
    rejected = { typed, kept ->
        "Tolerance: \"$typed\" is neither Auto nor a number of pixels from ${number(TestSettings.TOLERANCE_RANGE.start)} " +
            "to ${number(TestSettings.TOLERANCE_RANGE.endInclusive)}, with at most one decimal. " +
            "It stays at ${kept?.let(::pixels) ?: "Auto (${pixels(auto())})"}."
    },
    complain = complain,
) {
    init {
        describe()
    }

    /** Says again what Auto comes to, after the feature DPI changed. */
    fun describe() {
        toolTipText = "<html>How far, in template pixels (at 400 DPI, 1 px = 0.06 mm), a match may be from where the homography " +
            "puts it and still agree with it (an inlier).<br>At least 8 matches, and 40 % of them, must agree for a page to match.<br>" +
            "Too small: a scan, never exactly the form, leaves too few agreeing even on the right page. Too large: looser fits,<br>" +
            "so the aligned page that OCR reads may be a little off; the form lines must still line up.<br>" +
            "<b>Auto</b> (production): one pixel at the feature DPI, but at least 3 — now ${pixels(auto())}. " +
            "Choose one, or type a number of pixels (one decimal at most) and press Enter.</html>"
    }

    companion object {
        const val AUTO = "Auto"
        private val PRESETS = listOf(1.0, 2.0, 3.0, 4.0, 6.0, 8.0, 12.0)

        private fun number(value: Double): String = (if (value == Math.rint(value)) "%.0f" else "%.1f").format(Locale.ROOT, value)
    }
}
