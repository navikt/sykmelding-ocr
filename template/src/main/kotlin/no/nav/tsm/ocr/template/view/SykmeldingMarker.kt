package no.nav.tsm.ocr.template.view

internal fun isSykmeldingMarker(text: String): Boolean {
    val marker = text.trim()
    require(marker in setOf("A", "B", "C", "D")) {
        "Document type undetermined; expected a single A, B, C or D."
    }
    return marker == "A"
}
