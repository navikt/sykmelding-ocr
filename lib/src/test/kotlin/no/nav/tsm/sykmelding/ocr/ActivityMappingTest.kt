package no.nav.tsm.sykmelding.ocr

import no.nav.tsm.sykmelding.model.OcrParserAktivitet
import no.nav.tsm.sykmelding.template.TemplateMatcher
import org.junit.jupiter.api.AfterEach
import kotlin.test.Test
import kotlin.test.assertEquals

class ActivityMappingTest {
    private val matcher = TemplateMatcher(templates = emptyList())
    private val parser = ParseSykmeldingOcr(Ocr(), matcher)
    private val prefixes = listOf(
        "aktivitet.avventende",
        "aktivitet.gradert",
        "aktivitet",
        "aktivitet.behandlingsdager",
        "aktivitet.reisetilskudd",
    )
    private val fom = "01.10.2026"
    private val tom = "31.10.2026"

    @AfterEach
    fun closeMatcher() {
        matcher.close()
    }

    @Test
    fun `activities are omitted when either date is missing or blank`() {
        assertEquals(emptyList(), parser.mapThreePage2019Activities(emptyMap()))

        for (missing in listOf(null, "", " \t\n")) {
            for ((start, end) in listOf(missing to tom, fom to missing, missing to missing)) {
                assertEquals(
                    emptyList(),
                    parser.mapThreePage2019Activities(dateFields(start, end)),
                    "Expected no activities for fom=$start and tom=$end",
                )
            }
        }
    }

    @Test
    fun `complete activities retain their order dates and other properties`() {
        val fields = dateFields(fom, tom) + mapOf(
            "aktivitet.gradert.grad" to "50",
            "aktivitet.gradert.reisetilskudd" to "true",
            "aktivitet.medisinskeArsaker" to "true",
            "aktivitet.forholdArbeidsplassen" to "true",
            "aktivitet.behandlingsdager.dager" to "2",
        )

        assertEquals(
            listOf(
                OcrParserAktivitet.Avventende(fom, tom),
                OcrParserAktivitet.Gradert(fom, tom, grad = 50, reisetilskudd = true),
                OcrParserAktivitet.IkkeMulig(fom, tom, medisinskArsak = true, arbeidsrelatertArsak = true),
                OcrParserAktivitet.Behandlingsdager(fom, tom, antallBehandlingsdager = 2),
                OcrParserAktivitet.Reisetilskudd(fom, tom),
            ),
            parser.mapThreePage2019Activities(fields),
        )
    }

    @Test
    fun `only complete activities are kept when periods are mixed`() {
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
                OcrParserAktivitet.Gradert(fom, tom),
                OcrParserAktivitet.Reisetilskudd(fom, tom),
            ),
            parser.mapThreePage2019Activities(fields),
        )
    }

    private fun dateFields(fom: String?, tom: String?): Map<String, String?> =
        prefixes.flatMap { prefix -> listOf("$prefix.fom" to fom, "$prefix.tom" to tom) }.toMap()
}
