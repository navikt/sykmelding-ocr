package no.nav.tsm.ocr.template

import no.nav.tsm.sykmelding.template.PageNumber
import no.nav.tsm.sykmelding.template.parsePageNumber
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PageNumberTest {
    @Test
    fun `extracts page and total from a text block with varied whitespace and casing`() {
        for (text in listOf("Sykmelding\nSide 2 av 3\nOther text", "SIDE 2\nAV\t3", "side\u00a02 av 3", "side2av3")) {
            assertEquals(PageNumber(2, 3), parsePageNumber(text))
        }
        assertEquals(PageNumber(12, 20), parsePageNumber("side 12 av 20"))
        assertEquals(PageNumber(2, 3), parsePageNumber("Side 2 av 3\nSide 2 av 3"))
    }

    @Test
    fun `rejects missing invalid overflowing and conflicting page numbers without guessing`() {
        for (text in listOf("", "Sykmelding", "side x av 3", "side 0 av 3", "side 4 av 3",
            "side 1 av 0", "side -1 av 3", "side 9999999999999999999 av 3",
            "side 1 av 9999999999999999999", "side 1 av 3\nside 2 av 3", "side 1 av 3\nside 1 av 4")) {
            assertFailsWith<IllegalArgumentException>(text) { parsePageNumber(text) }
        }
    }
}
