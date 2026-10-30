package no.nav.tsm.sykmelding.ocr

import kotlin.test.Test
import kotlin.test.assertEquals

class DateInTest {
    @Test
    fun `the date is taken out of what the signature field also holds`() {
        mapOf(
            "21.05.2026 å" to "21.05.2026",
            "a 21.5.26 a" to "21.5.26",
            "21 . 05 . 2026" to "21.05.2026",
            "21/05-2026" to "21/05-2026",
            "2026.01.20 å" to "2026.01.20",
            "x 2026.1.5" to "2026.1.5",
            "ingen dato" to "ingen dato",
        ).forEach { (read, expected) -> assertEquals(expected, dateIn(read), read) }
        assertEquals(null, dateIn(null))
    }
}
