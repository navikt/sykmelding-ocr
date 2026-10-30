package no.nav.tsm.sykmelding.ocr

import no.nav.tsm.diagnoser.Diagnose
import no.nav.tsm.diagnoser.DiagnoseType
import no.nav.tsm.sykmelding.model.CheckboxState
import no.nav.tsm.sykmelding.model.CheckboxState.CHECKED
import no.nav.tsm.sykmelding.model.CheckboxState.UNCERTAIN
import no.nav.tsm.sykmelding.model.CheckboxState.UNCHECKED
import no.nav.tsm.sykmelding.model.HarArbeidsgiver
import no.nav.tsm.sykmelding.model.LovfestetFravaersgrunn
import no.nav.tsm.sykmelding.model.OcrParserAktivitet
import no.nav.tsm.sykmelding.model.OcrParserAnnenFraversArsak
import no.nav.tsm.sykmelding.model.OcrParserDiagnose
import no.nav.tsm.sykmelding.model.OcrValue
import no.nav.tsm.sykmelding.template.FieldType
import no.nav.tsm.sykmelding.template.Felter
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull

class SykmeldingOcrMappingTest {
    private val pages = listOf(
        page(
            "pasient.fnr" to "30898098466", "tittel.fnr" to "30898098466",
            "pasient.etternavn" to "Historisk", "tittel.etternavn" to "HISTORISK",
            "pasient.fornavn" to "Jeger", "tittel.fornavn" to "Jeger",
            "pasient.telefon" to "+47 12 34 56 78",
            "startdatoSykefravar" to "01.10.2026",
            "arbeidsgiver.type" to "En arbeisgiver", "arbeidsgiver.arbeidsgiver" to "Example AS",
            "arbeidsgiver.stillingsprosent" to "100 %",
            "hoveddiagnose.system" to "ICPC-2", "hoveddiagnose.kode" to "R74", "hoveddiagnose.text" to "Forkjølelse",
            "bidiagnose.kode" to "L84", "bidiagnose.text" to "Vondt i ryggen",
            "diagnose.lovfestetfravarsgrunn" to "b) Under behandling",
            "diagnose.yrkesskade.skadedato" to "15.06.98",
            boxes = mapOf("diagnose.yrkesskade" to CHECKED, "diagnose.skjermes" to UNCHECKED),
        ),
        page(
            "tittel.fnr" to "308980 98466", "tittel.etternavn" to "Historisk", "tittel.fornavn" to "Jæger",
            "aktivitet.fom" to "2026.01.01", "aktivitet.tom" to "2026.01.20",
            "arbeidsevne.tiltakNav" to "Arbeidsrettet oppfølging",
            boxes = mapOf("aktivitet.medisinskeArsaker" to CHECKED, "prognose.arbeidsforEtterPerioden" to UNCHECKED),
        ),
        page(
            "tittel.fnr" to "30898098467", "tittel.etternavn" to "Histor1sk", "tittel.fornavn" to "Jæger",
            "bekreftelse.hpr" to "001234567", "bekreftelse.signatur" to "21.05.2026 å",
            "tilbakedatering.kontaktdato" to "31.02.2026",
            "melding.arbeidsgiver.andreInnspill" to "  Example  ",
        ),
    )
    private val mapped = assertNotNull(SykmeldingOcrMapping.map("03.2019", pages, today = LocalDate.of(2026, 10, 29)))

    @Test
    fun `the fnr is cross-checked over page 1 and every page title`() {
        val fnr = mapped.pasient.fnr
        assertEquals(OcrValue.Read("30898098466", "30898098466"), fnr.value)
        assertEquals(3, fnr.confirmations)
        assertEquals(
            listOf("pasient.fnr" to 1, "tittel.fnr" to 1, "tittel.fnr" to 2, "tittel.fnr" to 3),
            fnr.readings.map { it.field to it.page },
        )
        assertEquals(OcrValue.Unreadable("30898098467", "wrong control digits"), fnr.readings.last().value)
    }

    @Test
    fun `names are cross-checked with case ignored and a tie is unreadable`() {
        assertEquals(OcrValue.Read("Historisk", "Historisk"), mapped.pasient.etternavn.value)
        assertEquals(3, mapped.pasient.etternavn.confirmations)
        assertIs<OcrValue.Unreadable>(mapped.pasient.fornavn.value)
        assertEquals(0, mapped.pasient.fornavn.confirmations)
    }

    @Test
    fun `values are typed and keep the text they were read from`() {
        assertEquals("03.2019", mapped.templateVersion)
        assertEquals(OcrValue.Read("+4712345678", "+47 12 34 56 78"), mapped.pasient.telefon)
        assertEquals(OcrValue.Read(LocalDate.of(2026, 10, 1), "01.10.2026"), mapped.syketilfelletStartDato)
        assertEquals(OcrValue.Read(HarArbeidsgiver.EN_ARBEIDSGIVER, "En arbeisgiver"), mapped.arbeidsgiver.harArbeidsgiver)
        assertEquals(OcrValue.Read(100, "100 %"), mapped.arbeidsgiver.stillingsprosent)
        assertEquals("Example AS", mapped.arbeidsgiver.navn)
        assertEquals(OcrValue.Read(LocalDate.of(1998, 6, 15), "15.06.98"), mapped.medisinskVurdering.yrkesskadeDato)
        assertEquals(OcrValue.Read("1234567", "001234567"), mapped.behandler.hpr)
        assertEquals(OcrValue.Read(LocalDate.of(2026, 5, 21), "21.05.2026 å"), mapped.behandler.behandletDato)
        assertEquals(OcrValue.Unreadable("31.02.2026", "not a date"), mapped.tilbakedatering.kontaktDato)
        assertEquals("Example", mapped.meldingTilArbeidsgiver)
        assertEquals("Arbeidsrettet oppfølging", mapped.tiltak.tiltakNav)
    }

    @Test
    fun `diagnoses are looked up and the fravaersgrunn letter is read`() {
        val hoved = mapped.medisinskVurdering.hovedDiagnose
        assertEquals(
            OcrParserDiagnose(
                OcrValue.Read(Diagnose(DiagnoseType.ICPC2, "R74", "Akutt øvre luftveisinfeksjon"), "R74"),
                "ICPC-2",
                "Forkjølelse",
            ),
            hoved,
        )

        assertEquals(emptyList(), mapped.medisinskVurdering.biDiagnoser)
        assertEquals(
            OcrParserAnnenFraversArsak(listOf(LovfestetFravaersgrunn.BEHANDLING_FORHINDRER_ARBEID), "b) Under behandling", null),
            mapped.medisinskVurdering.annenFraversArsak,
        )
    }

    @Test
    fun `checkboxes and periods are read from every page and a missing box is uncertain`() {
        assertEquals(CHECKED, mapped.medisinskVurdering.yrkesskade)
        assertEquals(UNCERTAIN, mapped.medisinskVurdering.svangerskap)
        assertEquals(UNCHECKED, mapped.medisinskVurdering.skjermetForPasient)
        assertEquals(UNCHECKED, mapped.prognose.arbeidsforEtterPeriode)
        assertEquals(UNCERTAIN, mapped.bistandNav.bistandUmiddelbart)
        assertEquals(
            listOf(
                OcrParserAktivitet.IkkeMulig(
                    OcrValue.Read(LocalDate.of(2026, 1, 1), "2026.01.01"),
                    OcrValue.Read(LocalDate.of(2026, 1, 20), "2026.01.20"),
                    CHECKED,
                    UNCERTAIN,
                ),
            ),
            mapped.aktivitet,
        )
    }

    private fun page(vararg fields: Pair<String, String>, boxes: Map<String, CheckboxState> = emptyMap()) = PageRead(
        emptyList(),
        fields.associate { (name, text) -> Felter(0, 0, 1, 1, name, name) to FieldText(listOf(TextRegion(0, 0, 1, 1, text, 99f))) },
        boxes.entries.associate { (name, state) -> Felter(0, 0, 1, 1, name, name, FieldType.CHECKBOX) to CheckboxReading(state, null, null) },
    )
}
