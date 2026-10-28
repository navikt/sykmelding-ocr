package no.nav.tsm.sykmelding.ocr

fun normalizeDiagnosekode(raw: String?): String? {
    if (raw == null) return null
    // Stray marks after the code, like the full stop in "K01.1.", are not part of it.
    val compact = raw.filterNot { it.isWhitespace() }.uppercase().trimEnd { !it.isLetterOrDigit() }
    if (compact.isEmpty()) return raw.trim()
    val first = compact.first().let { LETTER_FOR_DIGIT[it] ?: it }
    val rest = collapseDoubleZero(compact.drop(1)).map { DIGIT_FOR_LETTER[it] ?: it }.joinToString("")
    val code = first + rest
    return if (CODE.matches(code)) code else raw.trim()
}

private fun collapseDoubleZero(afterLetter: String): String {
    val dot = afterLetter.indexOf('.')
    if (dot < 0) return afterLetter
    val head = afterLetter.substring(0, dot).removePrefix("-")
    if (head.length <= 2) return afterLetter
    val pair = Regex("O0|0O").find(head) ?: return afterLetter
    val prefix = afterLetter.substring(0, dot - head.length)
    return prefix + head.replaceRange(pair.range, "0") + afterLetter.substring(dot)
}

private val CODE = Regex("""[A-Z]-?\d+(\.\d+)?""")

private val DIGIT_FOR_LETTER = mapOf(
    'O' to '0', 'Q' to '0', 'D' to '0', 'U' to '0',
    'I' to '1', 'L' to '1', 'J' to '1', 'T' to '1',
    'Z' to '2',
    'S' to '5',
    'G' to '6',
    'B' to '8',
)

private val LETTER_FOR_DIGIT = mapOf(
    '0' to 'O',
    '1' to 'I',
    '2' to 'Z',
    '5' to 'S',
    '6' to 'G',
    '8' to 'B',
)
