package no.nav.tsm.ocr.template.gcp

import no.nav.tsm.sykmelding.model.Bidiagnose
import no.nav.tsm.sykmelding.model.CheckboxState
import no.nav.tsm.sykmelding.model.OcrParserAktivitet
import no.nav.tsm.sykmelding.model.OcrParserDiagnose
import no.nav.tsm.sykmelding.model.OcrParserPasient
import no.nav.tsm.sykmelding.ocr.FieldText
import no.nav.tsm.sykmelding.ocr.PageRead
import no.nav.tsm.sykmelding.ocr.SykmeldingOcrMapping
import no.nav.tsm.sykmelding.ocr.TextRegion
import org.example.no.nav.tsm.ocr.template.template.Felter
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OcrXmlComparisonTest {
    private fun mapped(vararg pages: Map<String, String>, version: String = "03.2019") =
        SykmeldingOcrMapping.map(version, pages.map { fields ->
            PageRead(emptyList(), fields.mapKeys { (name, _) -> Felter(0, 0, 1, 1, name, name) }
                .mapValues { (_, value) -> FieldText(listOf(TextRegion(0, 0, 1, 1, value, 99f))) })
        })

    private val emptyMapped = assertNotNull(mapped(emptyMap()))

    private fun xml(vararg fields: Pair<String, String>) =
        PapirsykmeldingXml(fields.map { (path, text) -> XmlField(path, text, text) }, emptyList())

    @Test
    fun `compares production mapped values across all pages`() {
        val mapped = assertNotNull(mapped(
            mapOf("pasient.fnr" to "01010112345"),
            mapOf("arbeidsgiver.arbeidsgiver" to "Example AS"),
            mapOf("bekreftelse.hpr" to "1234567"),
        ))
        val comparison = compareMappedOcrWithXml(mapped, xml(
            "pasient.fnr" to "01010112345",
            "arbeidsgiver.navnArbeidsgiver" to "Example AS",
            "behandler.HPR" to "1234567",
        ))
        assertEquals(3, comparison.fields.count { it.status == ComparisonStatus.MATCH })
        assertTrue(comparison.describe().contains("MATCH: 3"))
        assertNull(mapped(emptyMap(), version = "unsupported"))
    }

    @Test
    fun `reports missing different extra and uncertain values without counting empty pairs`() {
        val mapped = emptyMapped.copy(
            pasient = OcrParserPasient(fnr = "01010112345", fornavn = "Example"),
            diagnose = OcrParserDiagnose(hoveddiagnoseKode = "L84"),
        )
        val rows = compareMappedOcrWithXml(mapped, xml(
            "pasient.fnr" to "01010112346",
            "arbeidsgiver.navnArbeidsgiver" to "Example AS",
            "medisinskVurdering.svangerskap" to "false",
            "behandler.telefon" to "",
            "unknown.field" to "unknown",
        )).fields.associateBy { it.path }
        assertEquals(ComparisonStatus.DIFFERENT, rows.getValue("pasient.fnr").status)
        assertEquals(ComparisonStatus.MISSING_OCR, rows.getValue("arbeidsgiver.navnArbeidsgiver").status)
        assertEquals(ComparisonStatus.OCR_ONLY, rows.getValue("medisinskVurdering.hovedDiagnose[1].diagnosekode").status)
        assertEquals(ComparisonStatus.UNCERTAIN, rows.getValue("medisinskVurdering.svangerskap").status)
        assertEquals(ComparisonStatus.NOT_COMPARABLE, rows.getValue("unknown.field").status)
        assertEquals(ComparisonStatus.NOT_COMPARABLE, rows.getValue("OCR.pasient.fornavn").status)
        assertFalse("behandler.telefon" in rows)
    }

    @Test
    fun `normalization is conservative and keeps original values visible`() {
        val mapped = emptyMapped.copy(
            diagnose = OcrParserDiagnose(skadedato = "23.10.2026", svangerskap = CheckboxState.CHECKED),
            aktiviteter = listOf(OcrParserAktivitet.Gradert("23.10.2026", "31.10.2026", 50)),
        )
        val rows = compareMappedOcrWithXml(mapped, xml(
            "medisinskVurdering.yrkesskadedato" to "2026-10-23",
            "medisinskVurdering.svangerskap" to "1",
            "aktivitet.gradertSykmelding.sykmeldingsgrad" to "50.0",
        )).fields.associateBy { it.path }
        for (path in listOf("medisinskVurdering.yrkesskadedato", "medisinskVurdering.svangerskap", "aktivitet.gradertSykmelding.sykmeldingsgrad")) {
            assertEquals(ComparisonStatus.NORMALIZED_MATCH, rows.getValue(path).status)
        }
        assertEquals("23.10.2026", rows.getValue("medisinskVurdering.yrkesskadedato").ocr)
        assertEquals("2026-10-23", rows.getValue("medisinskVurdering.yrkesskadedato").xml)
        val identifiers = compareMappedOcrWithXml(
            emptyMapped.copy(pasient = OcrParserPasient(fnr = "01010112345")),
            xml("pasient.fnr" to "1010112345"),
        )
        assertEquals(ComparisonStatus.DIFFERENT, identifiers.fields.single { it.path == "pasient.fnr" }.status)
    }

    @Test
    fun `single and repeated diagnoses are aligned and additional XML diagnoses are not lost`() {
        val mapped = emptyMapped.copy(diagnose = OcrParserDiagnose(bidiagnoser = listOf(Bidiagnose(kode = "L84"))))
        val single = compareMappedOcrWithXml(mapped, xml("medisinskVurdering.bidiagnose.diagnosekode" to "L84"))
        assertEquals(ComparisonStatus.MATCH, single.fields.single { it.path.startsWith("medisinskVurdering") }.status)
        val repeated = compareMappedOcrWithXml(mapped, xml(
            "medisinskVurdering.bidiagnose[1].diagnosekode" to "L84",
            "medisinskVurdering.bidiagnose[2].diagnosekode" to "R05",
        ))
        assertEquals(listOf(ComparisonStatus.MATCH, ComparisonStatus.MISSING_OCR),
            repeated.fields.filter { it.path.startsWith("medisinskVurdering") }.map { it.status })
    }

    @Test
    fun `mapping preserves duplicate detection and excludes repeated page titles`() {
        assertFailsWith<IllegalArgumentException> {
            mapped(mapOf("pasient.fnr" to "01010112345"), mapOf("pasient.fnr" to "01010112346"))
        }
        assertNotNull(mapped(mapOf("tittel.fnr" to "01010112345"), mapOf("tittel.fnr" to "01010112345")))
        assertEquals(emptyMapped, assertNotNull(mapped(emptyMap(), version = "0.2019")))
    }

    @Test
    fun `whitespace is normalized but case punctuation and invalid dates are not corrected`() {
        val mapped = emptyMapped.copy(
            pasient = OcrParserPasient(fnr = "uncertain"),
            diagnose = OcrParserDiagnose(hoveddiagnoseTekst = "Example\n text", skadedato = "31.02.2026", hoveddiagnoseKode = "l-84"),
        )
        val rows = compareMappedOcrWithXml(mapped, xml(
            "pasient.fnr" to "UNCERTAIN",
            "medisinskVurdering.hovedDiagnose.diagnose" to "Example  text",
            "medisinskVurdering.hovedDiagnose.diagnosekode" to "L84",
            "medisinskVurdering.yrkesskadedato" to "2026-02-28",
        )).fields.associateBy { it.path }
        assertEquals(ComparisonStatus.NORMALIZED_MATCH, rows.getValue("medisinskVurdering.hovedDiagnose[1].diagnose").status)
        assertEquals(ComparisonStatus.DIFFERENT, rows.getValue("medisinskVurdering.hovedDiagnose[1].diagnosekode").status)
        assertEquals(ComparisonStatus.DIFFERENT, rows.getValue("medisinskVurdering.yrkesskadedato").status)
        assertEquals(ComparisonStatus.DIFFERENT, rows.getValue("pasient.fnr").status)
    }

    @Test
    fun `parses real schema XML and flags invalid values rather than trusting JAXB coercion`() {
        val content = XmlContent.of("""
            <skanningmetadata><sykemeldinger>
              <pasient><fnr>01010112345</fnr></pasient>
              <medisinskVurdering>
                <hovedDiagnose><diagnosekode>L84</diagnosekode></hovedDiagnose>
                <svangerskap>ja</svangerskap><yrkesskade>1</yrkesskade>
              </medisinskVurdering>
              <behandler><HPR>1234567</HPR></behandler>
            </sykemeldinger></skanningmetadata>
        """.trimIndent().toByteArray())
        assertTrue(content.parsed, content.fields)
        val mapped = assertNotNull(mapped(mapOf(
            "pasient.fnr" to "01010112345", "hoveddiagnose.kode" to "L84", "bekreftelse.hpr" to "1234567",
        ))).copy(diagnose = OcrParserDiagnose(hoveddiagnoseKode = "L84", svangerskap = CheckboxState.UNCHECKED, yrkesskade = CheckboxState.CHECKED))
        val rows = compareMappedOcrWithXml(mapped, assertNotNull(content.document)).fields.associateBy { it.path }
        assertEquals(ComparisonStatus.MATCH, rows.getValue("medisinskVurdering.hovedDiagnose[1].diagnosekode").status)
        assertEquals(ComparisonStatus.MATCH, rows.getValue("behandler.HPR").status)
        assertEquals(ComparisonStatus.XML_PROBLEM, rows.getValue("medisinskVurdering.svangerskap").status)
        assertEquals(ComparisonStatus.NORMALIZED_MATCH, rows.getValue("medisinskVurdering.yrkesskade").status)
        assertNull(XmlContent.of("<invalid".toByteArray()).document)
    }
}
