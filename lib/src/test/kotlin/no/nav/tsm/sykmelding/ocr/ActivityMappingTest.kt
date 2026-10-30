package no.nav.tsm.sykmelding.ocr

import no.nav.tsm.sykmelding.model.CheckboxState.CHECKED
import no.nav.tsm.sykmelding.model.CheckboxState.UNCERTAIN
import no.nav.tsm.sykmelding.model.CheckboxState.UNCHECKED
import no.nav.tsm.sykmelding.model.OcrParserAktivitet
import no.nav.tsm.sykmelding.model.OcrValue
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

class ActivityMappingTest {
    private val prefixes = listOf(
        "aktivitet.avventende",
        "aktivitet.gradert",
        "aktivitet",
        "aktivitet.behandlingsdager",
        "aktivitet.reisetilskudd",
    )
    private val fom = "01.10.2026"
    private val tom = "31.10.2026"
    private val readFom = OcrValue.Read(LocalDate.of(2026, 10, 1), fom)
    private val readTom = OcrValue.Read(LocalDate.of(2026, 10, 31), tom)

    @Test
    fun `a blank period is left out`() {
        assertEquals(emptyList(), SykmeldingOcrMapping.mapThreePage2019Activities(emptyMap(), emptyMap()))

        for (blank in listOf(null, "", " \t\n")) {
            assertEquals(
                emptyList(),
                SykmeldingOcrMapping.mapThreePage2019Activities(dateFields(blank, blank), emptyMap()),
                "Expected no activities for dates '$blank'",
            )
        }
    }

    @Test
    fun `a period that is not blank is kept with its blank dates null`() {
        val fields = mapOf(
            "aktivitet.avventende.fom" to fom,
            "aktivitet.gradert.fom" to fom,
            "aktivitet.gradert.tom" to tom,
            "aktivitet.tom" to tom,
            "aktivitet.behandlingsdager.fom" to fom,
            "aktivitet.behandlingsdager.tom" to "",
            "aktivitet.reisetilskudd.fom" to fom,
            "aktivitet.reisetilskudd.tom" to tom,
        )

        assertEquals(
            listOf(
                OcrParserAktivitet.Avventende(readFom, null, null),
                OcrParserAktivitet.Gradert(readFom, readTom, null, UNCERTAIN),
                OcrParserAktivitet.IkkeMulig(null, readTom, UNCERTAIN, UNCERTAIN),
                OcrParserAktivitet.Behandlingsdager(readFom, null, null),
                OcrParserAktivitet.Reisetilskudd(readFom, readTom),
            ),
            SykmeldingOcrMapping.mapThreePage2019Activities(fields, emptyMap()),
        )
    }

    @Test
    fun `complete activities keep their order dates and other properties`() {
        val fields = dateFields(fom, tom) + mapOf(
            "aktivitet.gradert.grad" to "50",
            "aktivitet.behandlingsdager.dager" to "2",
        )
        val checkboxes = mapOf(
            "aktivitet.gradert.reisetilskudd" to CHECKED,
            "aktivitet.medisinskeArsaker" to UNCHECKED,
            "aktivitet.forholdArbeidsplassen" to UNCERTAIN,
        )

        assertEquals(
            listOf(
                OcrParserAktivitet.Avventende(readFom, readTom, null),
                OcrParserAktivitet.Gradert(readFom, readTom, OcrValue.Read(50, "50"), CHECKED),
                OcrParserAktivitet.IkkeMulig(readFom, readTom, UNCHECKED, UNCERTAIN),
                OcrParserAktivitet.Behandlingsdager(readFom, readTom, OcrValue.Read(2, "2")),
                OcrParserAktivitet.Reisetilskudd(readFom, readTom),
            ),
            SykmeldingOcrMapping.mapThreePage2019Activities(fields, checkboxes),
        )
    }

    @Test
    fun `a checkbox that was not read is uncertain, never unchecked`() {
        val activities = SykmeldingOcrMapping.mapThreePage2019Activities(dateFields(fom, tom), emptyMap())

        assertEquals(OcrParserAktivitet.Gradert(readFom, readTom, null, UNCERTAIN), activities[1])
        assertEquals(OcrParserAktivitet.IkkeMulig(readFom, readTom, UNCERTAIN, UNCERTAIN), activities[2])
    }

    @Test
    fun `an uncertain box alone keeps an otherwise blank period, an unchecked one does not`() {
        assertEquals(
            listOf(OcrParserAktivitet.Gradert(null, null, null, UNCERTAIN)),
            SykmeldingOcrMapping.mapThreePage2019Activities(emptyMap(), mapOf("aktivitet.gradert.reisetilskudd" to UNCERTAIN)),
        )
        assertEquals(
            emptyList(),
            SykmeldingOcrMapping.mapThreePage2019Activities(emptyMap(), mapOf("aktivitet.medisinskeArsaker" to UNCHECKED)),
        )
    }

    @Test
    fun `a period with an unreadable date is kept with the date unreadable`() {
        assertEquals(
            listOf(OcrParserAktivitet.IkkeMulig(OcrValue.Unreadable("31.02.2026", "not a date"), readTom, UNCERTAIN, UNCERTAIN)),
            SykmeldingOcrMapping.mapThreePage2019Activities(mapOf("aktivitet.fom" to "31.02.2026", "aktivitet.tom" to tom), emptyMap()),
        )
    }

    private fun dateFields(fom: String?, tom: String?): Map<String, String?> =
        prefixes.flatMap { prefix -> listOf("$prefix.fom" to fom, "$prefix.tom" to tom) }.toMap()
}
