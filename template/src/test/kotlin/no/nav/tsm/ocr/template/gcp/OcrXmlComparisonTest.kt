package no.nav.tsm.ocr.template.gcp

import no.nav.tsm.sykmelding.model.CheckboxState
import no.nav.tsm.sykmelding.ocr.FieldText
import no.nav.tsm.sykmelding.ocr.PageRead
import no.nav.tsm.sykmelding.ocr.SykmeldingOcrMapping
import no.nav.tsm.sykmelding.ocr.TextRegion
import no.nav.tsm.sykmelding.template.Felter
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
            mapOf("pasient.fnr" to "30898098466"),
            mapOf("arbeidsgiver.arbeidsgiver" to "Example AS"),
            mapOf("bekreftelse.hpr" to "1234567"),
        ))
        val comparison = compareMappedOcrWithXml(mapped, xml(
            "pasient.fnr" to "30898098466",
            "arbeidsgiver.navnArbeidsgiver" to "Example AS",
            "behandler.HPR" to "1234567",
        ))
        assertEquals(3, comparison.fields.count { it.status == ComparisonStatus.MATCH })
        assertTrue(comparison.describe().contains("MATCH: 3"))
        assertNull(mapped(emptyMap(), version = "unsupported"))
    }

    @Test
    fun `reports missing different extra and uncertain values without counting empty pairs`() {
        val mapped = assertNotNull(mapped(mapOf(
            "pasient.fnr" to "30898098466", "pasient.fornavn" to "Example", "hoveddiagnose.system" to "ICPC-2",
            "hoveddiagnose.kode" to "R74",
        )))
        val rows = compareMappedOcrWithXml(mapped, xml(
            "pasient.fnr" to "01018012371",
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
    fun `typed OCR values compare as typed and keep their text visible`() {
        val mapped = assertNotNull(mapped(
            mapOf("diagnose.yrkesskade.skadedato" to "23.10.2026"),
            mapOf("aktivitet.gradert.fom" to "23.10.2026", "aktivitet.gradert.tom" to "31.10.2026", "aktivitet.gradert.grad" to "50"),
        )).let { it.copy(medisinskVurdering = it.medisinskVurdering.copy(svangerskap = CheckboxState.CHECKED)) }
        val rows = compareMappedOcrWithXml(mapped, xml(
            "medisinskVurdering.yrkesskadedato" to "2026-10-23",
            "medisinskVurdering.svangerskap" to "1",
            "aktivitet.gradertSykmelding.sykmeldingsgrad" to "50.0",
        )).fields.associateBy { it.path }
        val yrkesskadedato = rows.getValue("medisinskVurdering.yrkesskadedato")
        assertEquals(ComparisonStatus.MATCH, yrkesskadedato.status)
        assertEquals("2026-10-23", yrkesskadedato.ocr)
        assertEquals("OCR text: 23.10.2026", yrkesskadedato.note)
        for (path in listOf("medisinskVurdering.svangerskap", "aktivitet.gradertSykmelding.sykmeldingsgrad")) {
            assertEquals(ComparisonStatus.NORMALIZED_MATCH, rows.getValue(path).status, path)
        }
        val identifiers = compareMappedOcrWithXml(
            assertNotNull(mapped(mapOf("pasient.fnr" to "30898098466"))),
            xml("pasient.fnr" to "0898098466"),
        )
        assertEquals(ComparisonStatus.DIFFERENT, identifiers.fields.single { it.path == "pasient.fnr" }.status)
    }

    @Test
    fun `single and repeated diagnoses are aligned and additional XML diagnoses are not lost`() {
        val mapped = assertNotNull(mapped(mapOf("bidiagnose.system" to "ICPC-2", "bidiagnose.kode" to "R74")))
        val single = compareMappedOcrWithXml(mapped, xml("medisinskVurdering.bidiagnose.diagnosekode" to "R74"))
        assertEquals(ComparisonStatus.MATCH, single.fields.single { it.path.endsWith(".diagnosekode") }.status)
        val repeated = compareMappedOcrWithXml(mapped, xml(
            "medisinskVurdering.bidiagnose[1].diagnosekode" to "R74",
            "medisinskVurdering.bidiagnose[2].diagnosekode" to "R05",
        ))
        assertEquals(listOf(ComparisonStatus.MATCH, ComparisonStatus.MISSING_OCR),
            repeated.fields.filter { it.path.endsWith(".diagnosekode") }.map { it.status })
    }

    @Test
    fun `mapping keeps the duplicate check and collects page titles`() {
        assertFailsWith<IllegalArgumentException> {
            mapped(mapOf("pasient.fnr" to "30898098466"), mapOf("pasient.fnr" to "01018012371"))
        }
        val titles = assertNotNull(mapped(mapOf("tittel.fnr" to "30898098466"), mapOf("tittel.fnr" to "30898098466")))
        assertEquals(2, titles.pasient.fnr.confirmations)
        assertEquals(emptyMapped.copy(templateVersion = "0.2019"), assertNotNull(mapped(emptyMap(), version = "0.2019")))
    }

    @Test
    fun `spaces around punctuation in text are normalized`() {
        val mapped = assertNotNull(mapped(mapOf(
            "hoveddiagnose.system" to "ICPC-2",
            "hoveddiagnose.text" to "Vondt i rygg, nakke og\nskulder",
            "diagnose.fravarsgrunnBeskrivelse" to "a, b",
            "bidiagnose.system" to "ICPC-2",
            "bidiagnose.text" to "Tennisalbue/lateral epikondylitt",
        )))
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
        val mapped = assertNotNull(mapped(mapOf(
            "arbeidsgiver.yrke" to "Fagarbeider (fast)",
            "bekreftelse.telefon" to "+47 12 34 56 78",
            "bekreftelse.adresse" to "adresse 1 A, 1111 postbox",
        )))
        val rows = compareMappedOcrWithXml(mapped, xml(
            "arbeidsgiver.yrkesbetegnelse" to "Fagarbeider ( fast )",
            // The schema has the phone number as an integer, so the XML can't hold "+47" or spaces.
            "behandler.telefon" to "12345678",
            "behandler.adresse" to "adresse 1A,1111 postbox",
        )).fields.associateBy { it.path }
        assertEquals(ComparisonStatus.NORMALIZED_MATCH, rows.getValue("behandler.adresse").status)
        assertEquals(ComparisonStatus.NORMALIZED_MATCH, rows.getValue("arbeidsgiver.yrkesbetegnelse").status)
        assertEquals(ComparisonStatus.NORMALIZED_MATCH, rows.getValue("behandler.telefon").status)
    }

    @Test
    fun `an HPR number without its leading zeros matches`() {
        fun status(ocr: String, written: String) = compareMappedOcrWithXml(
            assertNotNull(mapped(mapOf("bekreftelse.hpr" to ocr))),
            xml("behandler.HPR" to written),
        ).fields.single { it.path == "behandler.HPR" }.status
        for (ocr in listOf("1234567", "001234567")) {
            assertEquals(ComparisonStatus.NORMALIZED_MATCH, status(ocr, "001234567"), ocr)
        }
        assertEquals(ComparisonStatus.DIFFERENT, status("1234568", "001234567"))
    }

    @Test
    fun `the dot in a diagnosis code is normalized as the XML leaves it out`() {
        val mapped = assertNotNull(mapped(mapOf(
            "hoveddiagnose.system" to "ICD-10", "hoveddiagnose.kode" to "K01.1",
            "bidiagnose.system" to "ICD-10", "bidiagnose.kode" to "J06.9",
        )))
        val rows = compareMappedOcrWithXml(mapped, xml(
            "medisinskVurdering.hovedDiagnose.diagnosekode" to "k011",
            "medisinskVurdering.bidiagnose.diagnosekode" to "J068",
        )).fields.associateBy { it.path }
        assertEquals(ComparisonStatus.NORMALIZED_MATCH, rows.getValue("medisinskVurdering.hovedDiagnose[1].diagnosekode").status)
        assertEquals(ComparisonStatus.DIFFERENT, rows.getValue("medisinskVurdering.bidiagnose[1].diagnosekode").status)
    }

    @Test
    fun `an unreadable OCR value is uncertain and shown as read with why`() {
        val mapped = assertNotNull(mapped(mapOf(
            "pasient.fnr" to "30898098467",
            "hoveddiagnose.system" to "ICPC-2",
            "hoveddiagnose.kode" to "l-84",
            "hoveddiagnose.text" to "Example\n text",
            "bidiagnose.system" to "ICPC-2",
            "bidiagnose.text" to "Example",
            "diagnose.yrkesskade.skadedato" to "31.02.2026",
        )))
        val rows = compareMappedOcrWithXml(mapped, xml(
            "pasient.fnr" to "30898098466",
            "medisinskVurdering.hovedDiagnose.diagnose" to "Example  text",
            "medisinskVurdering.hovedDiagnose.diagnosekode" to "L84",
            "medisinskVurdering.bidiagnose.diagnose" to "example",
            "medisinskVurdering.yrkesskadedato" to "2026-02-28",
        )).fields.associateBy { it.path }
        listOf(
            Triple("pasient.fnr", "30898098467", "Unreadable: wrong control digits"),
            Triple("medisinskVurdering.hovedDiagnose[1].diagnosekode", "l-84", "Unreadable: not in ICPC-2"),
            Triple("medisinskVurdering.yrkesskadedato", "31.02.2026", "Unreadable: not a date"),
        ).forEach { (path, text, note) ->
            val row = rows.getValue(path)
            assertEquals(ComparisonStatus.UNCERTAIN, row.status, path)
            assertEquals(text, row.ocr, path)
            assertEquals(note, row.note, path)
        }
        assertEquals(ComparisonStatus.NORMALIZED_MATCH, rows.getValue("medisinskVurdering.hovedDiagnose[1].diagnose").status)
        assertEquals(ComparisonStatus.DIFFERENT, rows.getValue("medisinskVurdering.bidiagnose[1].diagnose").status)
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
            "hoveddiagnose.system" to "ICPC-2", "hoveddiagnose.kode" to "L84", "bekreftelse.hpr" to "1234567",
        ))).let {
            it.copy(medisinskVurdering = it.medisinskVurdering.copy(svangerskap = CheckboxState.UNCHECKED, yrkesskade = CheckboxState.CHECKED))
        }
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
            assertNotNull(mapped(mapOf("arbeidsgiver.type" to ocr))),
            xml("arbeidsgiver.harArbeidsgiver" to xml),
        ).fields.single { it.path == "arbeidsgiver.harArbeidsgiver" }.status
        assertEquals(ComparisonStatus.NORMALIZED_MATCH, status("En arbeidsgiver", "En"))
        assertEquals(ComparisonStatus.NORMALIZED_MATCH, status("Flere arbeidsgivere", "Flere"))
        assertEquals(ComparisonStatus.NORMALIZED_MATCH, status("Én arbeidsgiver", "en"))
        assertEquals(ComparisonStatus.DIFFERENT, status("Ingen arbeidsgiver", "En"))
        assertEquals(ComparisonStatus.UNCERTAIN, status("Kanskje", "En"))
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
        assertEquals(ComparisonStatus.MATCH, rows.getValue("syketilfelleStartDato").status)
        assertEquals(ComparisonStatus.NORMALIZED_MATCH, rows.getValue("arbeidsgiver.harArbeidsgiver").status)
        assertEquals(ComparisonStatus.MATCH, rows.getValue("aktivitet.innspillTilArbeidsgiver").status)
        assertEquals(ComparisonStatus.MATCH, rows.getValue("tilbakedatering.tilbakeDato").status)
    }
}
