package no.nav.tsm.ocr.template

import no.nav.tsm.sykmelding.model.CheckboxState.CHECKED
import no.nav.tsm.sykmelding.model.CheckboxState.UNCHECKED
import no.nav.tsm.sykmelding.model.OcrParserAktivitet
import no.nav.tsm.sykmelding.ocr.Ocr
import no.nav.tsm.sykmelding.ocr.ParseSykmeldingOcr
import no.nav.tsm.sykmelding.template.TemplateMatcher
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull

@Tag("ocr-smoke")
class ParseSykmeldingOcrTest {

    @Test
    fun `doOcr maps patient, diagnoses and activity from the synthetic test PDF`() {
        // As production: the PDF in memory, and the templates packaged in lib's resources.
        val pdf = requireNotNull(javaClass.getResourceAsStream("/test.pdf")) { "Missing test PDF: /test.pdf" }.use { it.readBytes() }

        TemplateMatcher().use { matcher ->
            val result = assertNotNull(ParseSykmeldingOcr(Ocr(), matcher).doOcr(pdf))
            val pasient = assertNotNull(result.pasient)
            val diagnose = assertNotNull(result.diagnose)
            val aktiviteter = assertNotNull(result.aktiviteter)

            assertAll(
                "Synthetic patient (Dolly ident) from test.pdf",
                { assertEquals("Historisk", pasient.etternavn, "pasient.etternavn") },
                { assertEquals("Jeger", pasient.fornavn, "pasient.fornavn") },
                { assertEquals("30898098466", pasient.fnr, "pasient.fnr") },
                { assertEquals("ICPC-2", diagnose.hoveddiagnoseKodesystem, "diagnose.hoveddiagnoseKodesystem") },
                { assertEquals("R74", diagnose.hoveddiagnoseKode, "diagnose.hoveddiagnoseKode") },
                { assertEquals("Forkjølelse", diagnose.hoveddiagnoseTekst, "diagnose.hoveddiagnoseTekst") },
                { assertEquals("R74", diagnose.bidiagnoser.single().kode, "bidiagnose.kode") },
                { assertEquals(CHECKED, diagnose.svangerskap, "diagnose.svangerskapsrelatert") },
                { assertEquals(UNCHECKED, diagnose.yrkesskade, "diagnose.yrkesskade") },
                { assertEquals(CHECKED, diagnose.skjermingPaakrevet, "diagnose.skjermes") },
                { assertIs<OcrParserAktivitet.IkkeMulig>(aktiviteter.single(), "aktiviteter") },
            )
        }
    }
}
