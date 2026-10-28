package no.nav.tsm.ocr.template.gcp

import java.util.prefs.Preferences

/**
 * An editable combo box for a resolution: choose one of [presets], or type a whole number of DPI and press Enter.
 * [range] gives the resolutions allowed now, which may depend on another field; [hint] explains its limits. A valid
 * resolution becomes [value], is stored under [key] in [settings] and is passed to [onChange]. An invalid one is
 * explained to [complain], and [value] is shown again.
 */
internal class DpiField(
    what: String,
    presets: List<Int>,
    default: Int,
    range: () -> IntRange,
    settings: Preferences?,
    key: String,
    complain: (String) -> Unit,
    hint: () -> String = { "" },
) : ParsedComboField<Int>(
    items = presets.map(Int::toString),
    initial = settings?.getInt(key, default)?.takeIf { it in range() } ?: default.coerceIn(range()),
    prototype = "00000",
    parse = { typed -> parseDpi(typed, range())?.let(::Parsed) },
    format = Int::toString,
    store = { dpi -> settings?.putInt(key, dpi) },
    rejected = { typed, kept ->
        val allowed = range()
        "$what: \"$typed\" is not a whole number of DPI from ${allowed.first} to ${allowed.last}${hint()}." +
            if (kept in allowed) " It stays at $kept DPI." else ""
    },
    complain = complain,
)
