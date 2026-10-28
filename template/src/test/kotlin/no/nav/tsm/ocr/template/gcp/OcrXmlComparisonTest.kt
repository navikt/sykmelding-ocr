package no.nav.tsm.ocr.template.gcp

import no.nav.tsm.sykmelding.model.Bidiagnose
import no.nav.tsm.sykmelding.model.CheckboxState
import no.nav.tsm.sykmelding.model.OcrParserAktivitet
import no.nav.tsm.sykmelding.model.OcrParserArbeidsgiver
import no.nav.tsm.sykmelding.model.OcrParserDiagnose
import no.nav.tsm.sykmelding.model.OcrParserSykmelder
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

    /** Parses a skanningmetadata document with the [fields] at their paths below `sykemeldinger`, as production reads it. */
    private fun xml(vararg fields: Pair<String, String>): PapirsykmeldingXml {
        val root = LinkedHashMap<String, Any>()
        for ((path, text) in fields) {
            val segments = path.split('.').map { if (it.endsWith(']')) it else "$it[1]" }
            @Suppress("UNCHECKED_CAST")
            val parent = segments.dropLast(1).fold(root) { node, segment ->
                node.getOrPut(segment) { LinkedHashMap<String, Any>() } as LinkedHashMap<String, Any>
            }
            parent[segments.last()] = text
        }
        fun render(node: Map<*, *>): String = node.entries.joinToString("") { (segment, value) ->
            val name = (segment as String).substringBefore('[')
            "<$name>${if (value is Map<*, *>) render(value) else value}</$name>"
        }
        return PapirsykmeldingXml.parse("<skanningmetadata><sykemeldinger>${render(root)}</sykemeldinger></skanningmetadata>".toByteArray())
    }

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
            "behandler.adresse" to "",
            "unknown.field" to "unknown",
        )).fields.associateBy { it.path }
        assertEquals(ComparisonStatus.DIFFERENT, rows.getValue("pasient.fnr").status)
        assertEquals(ComparisonStatus.MISSING_OCR, rows.getValue("arbeidsgiver.navnArbeidsgiver").status)
        assertEquals(ComparisonStatus.OCR_ONLY, rows.getValue("medisinskVurdering.hovedDiagnose[1].diagnosekode").status)
        assertEquals(ComparisonStatus.UNCERTAIN, rows.getValue("medisinskVurdering.svangerskap").status)
        assertEquals(ComparisonStatus.NOT_COMPARABLE, rows.getValue("unknown.field").status)
        assertEquals(ComparisonStatus.NOT_COMPARABLE, rows.getValue("OCR.pasient.fornavn").status)
        assertFalse("behandler.adresse" in rows)
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
    fun `spaces around punctuation in text are normalized`() {
        val mapped = emptyMapped.copy(diagnose = OcrParserDiagnose(
            hoveddiagnoseTekst = "Vondt i rygg, nakke og\nskulder", fraversgrunnBeskrivelse = "a, b",
            bidiagnoser = listOf(Bidiagnose(tekst = "Tennisalbue/lateral epikondylitt")),
        ))
        val rows = compareMappedOcrWithXml(mapped, xml(
            "medisinskVurdering.hovedDiagnose.diagnose" to "Vondt i rygg,nakke og skulder",
            "medisinskVurdering.fraversBeskrivelse" to "a,c",
            "medisinskVurdering.bidiagnose.diagnose" to "Tennisalbue / lateral epikondylitt",
        )).fields.associateBy { it.path }
        assertEquals(ComparisonStatus.NORMALIZED_MATCH, rows.getValue("medisinskVurdering.hovedDiagnose[1].diagnose").status)
        assertEquals(ComparisonStatus.NORMALIZED_MATCH, rows.getValue("medisinskVurdering.bidiagnose[1].diagnose").status)
        assertEquals(ComparisonStatus.DIFFERENT, rows.getValue("medisinskVurdering.fraversBeskrivelse").status)
    }

    @Test
    fun `spaces inside brackets and phone numbers are normalized`() {
        val mapped = emptyMapped.copy(
            arbeidsgiver = OcrParserArbeidsgiver(yrkeStilling = "Fagarbeider (fast)"),
            sykmelder = OcrParserSykmelder(telefon = "12 34 56 78", adresse = "adresse 1 A, 1111 postbox"),
        )
        val rows = compareMappedOcrWithXml(mapped, xml(
            "arbeidsgiver.yrkesbetegnelse" to "Fagarbeider ( fast )",
            "behandler.telefon" to "12345678",
            "behandler.adresse" to "adresse 1A,1111 postbox",
        )).fields.associateBy { it.path }
        assertEquals(ComparisonStatus.NORMALIZED_MATCH, rows.getValue("behandler.adresse").status)
        assertEquals(ComparisonStatus.NORMALIZED_MATCH, rows.getValue("arbeidsgiver.yrkesbetegnelse").status)
        assertEquals(ComparisonStatus.NORMALIZED_MATCH, rows.getValue("behandler.telefon").status)
    }

    @Test
    fun `an HPR number without its leading zeros matches`() {
        listOf("1234567" to "001234567", "001234567" to "1234567").forEach { (ocr, written) ->
            val mapped = emptyMapped.copy(sykmelder = OcrParserSykmelder(hprNummer = ocr))
            val rows = compareMappedOcrWithXml(mapped, xml("behandler.HPR" to written)).fields.associateBy { it.path }
            assertEquals(ComparisonStatus.NORMALIZED_MATCH, rows.getValue("behandler.HPR").status, ocr)
        }
        val mapped = emptyMapped.copy(sykmelder = OcrParserSykmelder(hprNummer = "1234568"))
        val rows = compareMappedOcrWithXml(mapped, xml("behandler.HPR" to "001234567")).fields.associateBy { it.path }
        assertEquals(ComparisonStatus.DIFFERENT, rows.getValue("behandler.HPR").status)
    }

    @Test
    fun `dates written with a short year or other separators match the ISO date`() {
        listOf("16.09.26", "16.09.2026", "16/9-26", "160926", "16 09 2026").forEach { written ->
            val mapped = emptyMapped.copy(diagnose = OcrParserDiagnose(skadedato = written))
            val rows = compareMappedOcrWithXml(mapped, xml("medisinskVurdering.yrkesskadedato" to "2026-09-16")).fields.associateBy { it.path }
            assertEquals(ComparisonStatus.NORMALIZED_MATCH, rows.getValue("medisinskVurdering.yrkesskadedato").status, written)
        }
    }

    @Test
    fun `the dot in a diagnosis code is normalized as the XML leaves it out`() {
        val mapped = emptyMapped.copy(diagnose = OcrParserDiagnose(hoveddiagnoseKode = "K01.1", bidiagnoser = listOf(Bidiagnose(kode = "K01.2"))))
        val rows = compareMappedOcrWithXml(mapped, xml(
            "medisinskVurdering.hovedDiagnose.diagnosekode" to "k011",
            "medisinskVurdering.bidiagnose.diagnosekode" to "K013",
        )).fields.associateBy { it.path }
        assertEquals(ComparisonStatus.NORMALIZED_MATCH, rows.getValue("medisinskVurdering.hovedDiagnose[1].diagnosekode").status)
        assertEquals(ComparisonStatus.DIFFERENT, rows.getValue("medisinskVurdering.bidiagnose[1].diagnosekode").status)
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

    @Test
    fun `harArbeidsgiver compares the XML's first word with the form's text`() {
        fun status(ocr: String, xml: String) = compareMappedOcrWithXml(
            emptyMapped.copy(arbeidsgiver = OcrParserArbeidsgiver(harArbeidsgiver = ocr)),
            xml("arbeidsgiver.harArbeidsgiver" to xml),
        ).fields.single { it.path == "arbeidsgiver.harArbeidsgiver" }.status
        assertEquals(ComparisonStatus.NORMALIZED_MATCH, status("En arbeidsgiver", "En"))
        assertEquals(ComparisonStatus.NORMALIZED_MATCH, status("Flere arbeidsgivere", "Flere"))
        assertEquals(ComparisonStatus.NORMALIZED_MATCH, status("Én arbeidsgiver", "en"))
        assertEquals(ComparisonStatus.DIFFERENT, status("Ingen arbeidsgiver", "En"))
    }

    @Test
    fun `start date, kontaktdato, innspill and arbeidsgiver type are mapped from the template`() {
        val mapped = assertNotNull(mapped(
            mapOf("startdatoSykefravar" to "01.10.2026", "arbeidsgiver.type" to "En arbeidsgiver"),
            mapOf("aktivitet.avventende.fom" to "01.10.2026", "aktivitet.avventende.tom" to "10.10.2026",
                "aktivitet.innspillArbeidsgiver" to "Example"),
            mapOf("tilbakedatering.kontaktdato" to "02.10.2026"),
        ))
        val rows = compareMappedOcrWithXml(mapped, xml(
            "syketilfelleStartDato" to "2026-10-01",
            "arbeidsgiver.harArbeidsgiver" to "En",
            "aktivitet.innspillTilArbeidsgiver" to "Example",
            "tilbakedatering.tilbakeDato" to "2026-10-02",
        )).fields.associateBy { it.path }
        assertEquals(ComparisonStatus.NORMALIZED_MATCH, rows.getValue("syketilfelleStartDato").status)
        assertEquals(ComparisonStatus.NORMALIZED_MATCH, rows.getValue("arbeidsgiver.harArbeidsgiver").status)
        assertEquals(ComparisonStatus.MATCH, rows.getValue("aktivitet.innspillTilArbeidsgiver").status)
        assertEquals(ComparisonStatus.NORMALIZED_MATCH, rows.getValue("tilbakedatering.tilbakeDato").status)
    }
}
