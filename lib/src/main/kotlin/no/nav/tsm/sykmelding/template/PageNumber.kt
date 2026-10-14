package no.nav.tsm.sykmelding.template

data class PageNumber(val page: Int, val total: Int)

private val pageNumberPattern = Regex("""(?U)\bside\s*(\d+)\s*av\s*(\d+)\b""", RegexOption.IGNORE_CASE)

fun parsePageNumber(text: String): PageNumber {
    val matches = pageNumberPattern.findAll(text).map { match ->
        val page = requireNotNull(match.groupValues[1].toIntOrNull()) { "Page number is too large." }
        val total = requireNotNull(match.groupValues[2].toIntOrNull()) { "Page count is too large." }
        require(page >= 1 && total >= page) { "Invalid page number: $page of $total." }
        PageNumber(page, total)
    }.toSet()
    require(matches.isNotEmpty()) { "No page number found; expected 'side x av y'." }
    require(matches.size == 1) { "Conflicting page numbers found." }
    return matches.single()
}
