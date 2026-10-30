package no.nav.tsm.sykmelding.ocr

import no.nav.tsm.diagnoser.Diagnose
import no.nav.tsm.diagnoser.ICD10
import no.nav.tsm.diagnoser.ICPC2
import no.nav.tsm.sykmelding.model.HarArbeidsgiver
import no.nav.tsm.sykmelding.model.OcrCrossChecked
import no.nav.tsm.sykmelding.model.OcrReading
import no.nav.tsm.sykmelding.model.OcrValue
import no.nav.tsm.sykmelding.model.valueOrNull
import java.text.Normalizer
import java.time.DateTimeException
import java.time.LocalDate

// Every parser trims the field's text and gives null when it is blank. The text in what it gives is the whole trimmed text.

private const val NOT_A_DATE = "not a date"
private const val SEPARATOR = """(?:\s*[./-]\s*|\s+)"""
private val YEAR_FIRST = Regex("""(\d{4})$SEPARATOR(\d{1,2})$SEPARATOR(\d{1,2})""")      // 2026.01.20, 2026-01-20
private val DAY_FIRST = Regex("""(\d{1,2})$SEPARATOR(\d{1,2})$SEPARATOR(\d{4}|\d{2})""")  // 01.10.2026, 1/10-26, 16 09 2026
private val DIGITS_ONLY = Regex("""(\d{2})(\d{2})(\d{4}|\d{2})""")                         // 011026, 01102026
private val WHITESPACE = Regex("""\s+""")
private val MARKS = Regex("""\p{M}""")
private val TELEFON = Regex("""\+?\d{8,15}""")

/** The text trimmed, or null when it is blank. */
internal fun String?.trimmedOrNull(): String? = this?.trim()?.takeIf { it.isNotEmpty() }

private fun String.isDigits(): Boolean = isNotEmpty() && all { it in '0'..'9' }

/**
 * A date written as a whole field: day first (`01.10.2026`, `1/10-26`, `011026`) or year first (`2026.01.20`), with one
 * trailing `.` allowed. A two-digit year is 20yy, or 19yy when 20yy would be after [today].
 */
internal fun parseDate(text: String?, today: LocalDate? = null): OcrValue<LocalDate>? {
    val trimmed = text.trimmedOrNull() ?: return null
    val written = trimmed.removeSuffix(".")
    val (day, month, year) = YEAR_FIRST.matchEntire(written)?.destructured?.let { (year, month, day) -> Triple(day, month, year) }
        ?: (DAY_FIRST.matchEntire(written) ?: DIGITS_ONLY.matchEntire(written))?.destructured?.let { (day, month, year) -> Triple(day, month, year) }
        ?: return OcrValue.Unreadable(trimmed, NOT_A_DATE)
    if (year.length == 4 && year.toInt() !in 1900..2100) return OcrValue.Unreadable(trimmed, "year out of range 1900-2100")
    return try {
        val date = if (year.length == 2) {
            LocalDate.of(2000 + year.toInt(), month.toInt(), day.toInt()).let { in2000s ->
                if (today != null && in2000s.isAfter(today)) LocalDate.of(1900 + year.toInt(), month.toInt(), day.toInt()) else in2000s
            }
        } else {
            LocalDate.of(year.toInt(), month.toInt(), day.toInt())
        }
        OcrValue.Read(date, trimmed)
    } catch (_: DateTimeException) {
        OcrValue.Unreadable(trimmed, NOT_A_DATE)
    }
}

/** The date in a field that also holds other marks, like the signature field. The text is the whole field. */
internal fun parseDateIn(text: String?, today: LocalDate? = null): OcrValue<LocalDate>? {
    val trimmed = text.trimmedOrNull() ?: return null
    return when (val date = parseDate(dateIn(trimmed), today)) {
        is OcrValue.Read -> OcrValue.Read(date.value, trimmed)
        is OcrValue.Unreadable -> OcrValue.Unreadable(trimmed, date.reason)
        null -> null
    }
}

private const val MARK = """\s*[./-]\s*"""

/** A date as written: year first, or day first with a two- or four-digit year, split by `.`, `/` or `-`. */
private val writtenDate =
    Regex("""(?<!\d)(?:\d{4}$MARK\d{1,2}$MARK\d{1,2}|\d{1,2}$MARK\d{1,2}$MARK(?:\d{4}|\d{2}))(?!\d)""")

/**
 * The date in [text], a field that also holds other marks, like the signature field: the signature's strokes are
 * read as stray letters around the date, e.g. `21.05.2026 å`. Without a date, [text] is given back as read.
 */
internal fun dateIn(text: String?): String? =
    text?.let { writtenDate.find(it)?.value?.filterNot(Char::isWhitespace) ?: it }

/** A percentage from 1 to 100, e.g. `50` or `100 %`. */
internal fun parsePercent(text: String?): OcrValue<Int>? {
    val trimmed = text.trimmedOrNull() ?: return null
    val value = trimmed.filterNot(Char::isWhitespace).removeSuffix("%").takeIf { it.isDigits() }?.toIntOrNull()
    return if (value != null && value in 1..100) OcrValue.Read(value, trimmed)
    else OcrValue.Unreadable(trimmed, "not a percentage from 1 to 100")
}

/** A whole number above 0, e.g. the number of behandlingsdager. */
internal fun parseCount(text: String?): OcrValue<Int>? {
    val trimmed = text.trimmedOrNull() ?: return null
    val value = trimmed.filterNot(Char::isWhitespace).takeIf { it.isDigits() }?.toIntOrNull()
    return if (value != null && value >= 1) OcrValue.Read(value, trimmed) else OcrValue.Unreadable(trimmed, "not a number above 0")
}

private val K1_WEIGHTS = intArrayOf(3, 7, 6, 1, 8, 9, 4, 5, 2)
private val K2_WEIGHTS = intArrayOf(5, 4, 3, 2, 7, 6, 5, 4, 3, 2)

/**
 * An fnr or D-number: 11 digits, maybe split by spaces or `-`, with valid mod11 control digits. The birth date is not
 * checked, so D-numbers and synthetic numbers are read.
 */
internal fun parseFnr(text: String?): OcrValue<String>? {
    val trimmed = text.trimmedOrNull() ?: return null
    val fnr = trimmed.filterNot { it.isWhitespace() || it == '-' }
    if (fnr.length != 11 || !fnr.isDigits()) return OcrValue.Unreadable(trimmed, "not 11 digits")
    val digits = fnr.map { it - '0' }
    fun control(weights: IntArray): Int? = when (val control = 11 - weights.indices.sumOf { weights[it] * digits[it] } % 11) {
        11 -> 0
        10 -> null
        else -> control
    }
    return if (control(K1_WEIGHTS) == digits[9] && control(K2_WEIGHTS) == digits[10]) OcrValue.Read(fnr, trimmed)
    else OcrValue.Unreadable(trimmed, "wrong control digits")
}

/** An HPR number: 1-9 digits, not all 0. Leading zeros are dropped, as tsm-behandler's normaliserHprNummer does. */
internal fun parseHpr(text: String?): OcrValue<String>? {
    val trimmed = text.trimmedOrNull() ?: return null
    val hpr = trimmed.filterNot(Char::isWhitespace)
    return if (hpr.length in 1..9 && hpr.isDigits() && hpr.any { it != '0' }) OcrValue.Read(hpr.trimStart('0'), trimmed)
    else OcrValue.Unreadable(trimmed, "not an HPR number")
}

/** A phone number: 8-15 digits, maybe after a `+`. It is given without spaces and `-`, with no country normalization. */
internal fun parseTelefon(text: String?): OcrValue<String>? {
    val trimmed = text.trimmedOrNull() ?: return null
    val telefon = trimmed.filterNot { it.isWhitespace() || it == '-' }
    return if (TELEFON.matches(telefon)) OcrValue.Read(telefon, trimmed) else OcrValue.Unreadable(trimmed, "not a phone number")
}

/** Field 2.1 by its first word, with accents and case ignored: "En arbeisgiver" (the form's own spelling), "Flere …", "Ingen …". */
internal fun parseHarArbeidsgiver(text: String?): OcrValue<HarArbeidsgiver>? {
    val trimmed = text.trimmedOrNull() ?: return null
    val word = trimmed.split(WHITESPACE).first().trimEnd { !it.isLetter() }
        .let { Normalizer.normalize(it, Normalizer.Form.NFD) }
        .replace(MARKS, "")
        .lowercase()
    return when (word) {
        "en" -> OcrValue.Read(HarArbeidsgiver.EN_ARBEIDSGIVER, trimmed)
        "flere" -> OcrValue.Read(HarArbeidsgiver.FLERE_ARBEIDSGIVERE, trimmed)
        "ingen" -> OcrValue.Read(HarArbeidsgiver.INGEN_ARBEIDSGIVER, trimmed)
        else -> OcrValue.Unreadable(trimmed, "not En, Flere or Ingen")
    }
}

/** A name, with every run of whitespace inside it read as one space. */
internal fun parseName(text: String?): OcrValue<String>? {
    val trimmed = text.trimmedOrNull() ?: return null
    return OcrValue.Read(trimmed.replace(WHITESPACE, " "), trimmed)
}

internal fun isDiagnoseSystem(system: String?): Boolean =
    system?.filterNot { it == '.' || it == '-' || it.isWhitespace() }?.uppercase() in setOf("ICD10", "ICPC2")

/**
 * The diagnosis [kode] names in tsm-diagnoser's ICD-10 or ICPC-2 list. [kode] is the field's trimmed, non-blank text.
 * The written [system] picks the list. Without a known system, the code must be in one list only, or [tekst] must be
 * the official text in one of them. ICPC-2B is not supported.
 */
internal fun resolveDiagnose(system: String?, kode: String, tekst: String?): OcrValue<Diagnose> {
    val code = normalizeDiagnosekode(kode)!!
    val found: (Diagnose?, String) -> OcrValue<Diagnose> = { diagnose, list ->
        diagnose?.let { OcrValue.Read(it, kode) } ?: OcrValue.Unreadable(kode, "not in $list")
    }
    return when (system?.filterNot { it == '.' || it == '-' || it.isWhitespace() }?.uppercase()) {
        "ICD10" -> found(ICD10[code], "ICD-10")
        "ICPC2" -> found(ICPC2[code], "ICPC-2")
        else -> {
            val candidates = listOfNotNull(ICD10[code], ICPC2[code])
            when {
                candidates.isEmpty() -> OcrValue.Unreadable(kode, "not in ICD-10 or ICPC-2")
                candidates.size == 1 -> OcrValue.Read(candidates.single(), kode)
                else -> candidates.singleOrNull { tekst != null && sameText(it.text, tekst) }?.let { OcrValue.Read(it, kode) }
                    ?: OcrValue.Unreadable(kode, "in both ICD-10 and ICPC-2")
            }
        }
    }
}

private fun sameText(a: String, b: String): Boolean =
    a.trim().replace(WHITESPACE, " ").equals(b.trim().replace(WHITESPACE, " "), ignoreCase = true)

/**
 * The fnr that every readable reading gives. Unreadable readings neither block nor count; readable ones that disagree
 * make the fnr unreadable.
 */
internal fun crossCheckFnr(readings: List<OcrReading<String>>): OcrCrossChecked<String> {
    val reads = readings.filter { it.value is OcrValue.Read }
    return when {
        reads.isEmpty() -> OcrCrossChecked(readings.firstOrNull()?.value, readings, 0)
        reads.distinctBy { it.value.valueOrNull() }.size == 1 -> OcrCrossChecked(reads.first().value, readings, reads.size)
        else -> OcrCrossChecked(OcrValue.Unreadable(readings.first().value.text, "different fnr: ${reads.listed()}"), readings, 0)
    }
}

/**
 * The name most readable readings give, with case and whitespace ignored. It needs at least two readings that agree and
 * more than any other name has: a tie makes the name unreadable.
 */
internal fun crossCheckName(readings: List<OcrReading<String>>): OcrCrossChecked<String> {
    val reads = readings.filter { it.value is OcrValue.Read }
    if (reads.isEmpty()) return OcrCrossChecked(readings.firstOrNull()?.value, readings, 0)
    if (reads.size == 1) return OcrCrossChecked(reads.single().value, readings, 1)
    val groups = reads.groupBy { it.value.valueOrNull().orEmpty().lowercase().filterNot(Char::isWhitespace) }.values
    val largest = groups.maxBy { it.size }
    return if (largest.size >= 2 && groups.count { it.size == largest.size } == 1) {
        OcrCrossChecked(largest.first().value, readings, largest.size)
    } else {
        OcrCrossChecked(OcrValue.Unreadable(readings.first().value.text, "readings disagree: ${reads.listed()}"), readings, 0)
    }
}

private fun List<OcrReading<String>>.listed(): String = joinToString { "${it.value.valueOrNull()} (${it.field} p${it.page})" }
