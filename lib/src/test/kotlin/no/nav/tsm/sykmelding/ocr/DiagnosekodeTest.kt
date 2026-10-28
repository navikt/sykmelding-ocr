package no.nav.tsm.sykmelding.ocr

import kotlin.test.Test
import kotlin.test.assertEquals

class DiagnosekodeTest {
    @Test
    fun `letters read where digits must be are made digits`() {
        mapOf(
            "K01.1" to "K01.1",
            "KO1.1" to "K01.1",
            "KO0.1" to "K00.1",
            "K0I.I" to "K01.1",
            "KO11" to "K011",
            "KO1.1." to "K01.1",
            "K01-" to "K01",
            "KO01.1" to "K01.1",
            "K0O1.1" to "K01.1",
            "K001.1" to "K001.1",
            "KO01" to "K001",
            "BO112" to "B0112",
            "L84.12" to "L84.12",
            "l 84" to "L84",
            "Y01" to "Y01",
            "0O1" to "O01",
            "L-84" to "L-84",
        ).forEach { (read, expected) -> assertEquals(expected, normalizeDiagnosekode(read), read) }
    }

    @Test
    fun `what is not code-shaped is left as read`() {
        assertEquals(null, normalizeDiagnosekode(null))
        assertEquals("", normalizeDiagnosekode("  "))
        assertEquals("K01..1", normalizeDiagnosekode(" K01..1 "))
        assertEquals("ICPC", normalizeDiagnosekode("ICPC"))
    }
}
