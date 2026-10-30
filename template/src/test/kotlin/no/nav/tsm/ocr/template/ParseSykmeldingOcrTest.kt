package no.nav.tsm.ocr.template

import no.nav.tsm.diagnoser.Diagnose
import no.nav.tsm.diagnoser.DiagnoseType
import no.nav.tsm.sykmelding.model.CheckboxState.CHECKED
import no.nav.tsm.sykmelding.model.CheckboxState.UNCHECKED
import no.nav.tsm.sykmelding.model.HarArbeidsgiver
import no.nav.tsm.sykmelding.model.OcrParserAktivitet
import no.nav.tsm.sykmelding.model.valueOrNull
import no.nav.tsm.sykmelding.ocr.Ocr
import no.nav.tsm.sykmelding.ocr.OcrResult
import no.nav.tsm.sykmelding.ocr.ParseSykmeldingOcr
import no.nav.tsm.sykmelding.template.TemplateMatcher
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.assertAll
import java.time.LocalDate
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
            val result = assertIs<OcrResult.Parsed>(ParseSykmeldingOcr(Ocr(), matcher).doOcr(pdf)).sykmelding
            val pasient = result.pasient
            val arbeidsgiver = result.arbeidsgiver
            val medisinsk = result.medisinskVurdering
            val hoved = assertNotNull(medisinsk.hovedDiagnose, "hoveddiagnose")

            assertAll(
                "Synthetic patient (Dolly ident) from test.pdf",
                { assertEquals("03.2019", result.templateVersion, "templateVersion") },
                { assertEquals("30898098466", pasient.fnr.value.valueOrNull(), "pasient.fnr: ${pasient.fnr.readings}") },
                { assertEquals(4, pasient.fnr.confirmations, "pasient.fnr: ${pasient.fnr.readings}") },
                { assertEquals("Historisk", pasient.etternavn.value.valueOrNull(), "pasient.etternavn: ${pasient.etternavn.readings}") },
                { assertEquals(4, pasient.etternavn.confirmations, "pasient.etternavn: ${pasient.etternavn.readings}") },
                { assertEquals("Jeger", pasient.fornavn.value.valueOrNull(), "pasient.fornavn: ${pasient.fornavn.readings}") },
                { assertEquals(4, pasient.fornavn.confirmations, "pasient.fornavn: ${pasient.fornavn.readings}") },
                {
                    assertEquals(HarArbeidsgiver.EN_ARBEIDSGIVER, arbeidsgiver.harArbeidsgiver.valueOrNull(),
                        "arbeidsgiver.type: ${arbeidsgiver.harArbeidsgiver}")
                },
                {
                    assertEquals(100, arbeidsgiver.stillingsprosent.valueOrNull(),
                        "arbeidsgiver.stillingsprosent: ${arbeidsgiver.stillingsprosent}")
                },
                {
                    assertEquals(Diagnose(DiagnoseType.ICPC2, "R74", "Akutt øvre luftveisinfeksjon"), hoved.diagnose.valueOrNull(),
                        "hoveddiagnose.kode: ${hoved.diagnose}")
                },
                { assertEquals("ICPC-2", hoved.system, "hoveddiagnose.system") },
                { assertEquals("Forkjølelse", hoved.tekst, "hoveddiagnose.text") },
                {
                    assertEquals("R74", medisinsk.biDiagnoser.singleOrNull()?.diagnose.valueOrNull()?.code,
                        "bidiagnose: ${medisinsk.biDiagnoser}")
                },
                { assertEquals(CHECKED, medisinsk.svangerskap, "diagnose.svangerskapsrelatert") },
                { assertEquals(UNCHECKED, medisinsk.yrkesskade, "diagnose.yrkesskade") },
                { assertEquals(CHECKED, medisinsk.skjermetForPasient, "diagnose.skjermes") },
                {
                    val ikkeMulig = assertIs<OcrParserAktivitet.IkkeMulig>(result.aktivitet.singleOrNull(), "aktivitet: ${result.aktivitet}")
                    assertEquals(LocalDate.of(2026, 1, 1), ikkeMulig.fom.valueOrNull(), "aktivitet.fom: ${ikkeMulig.fom}")
                    assertEquals(LocalDate.of(2026, 1, 20), ikkeMulig.tom.valueOrNull(), "aktivitet.tom: ${ikkeMulig.tom}")
                },
            )
        }
    }
}
