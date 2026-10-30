package no.nav.tsm.sykmelding.ocr

import no.nav.tsm.diagnoser.DiagnoseType
import no.nav.tsm.sykmelding.model.HarArbeidsgiver
import no.nav.tsm.sykmelding.model.LovfestetFravaersgrunn
import no.nav.tsm.sykmelding.model.LovfestetFravaersgrunn.BEHANDLING_FORHINDRER_ARBEID
import no.nav.tsm.sykmelding.model.LovfestetFravaersgrunn.BEHANDLING_STERILISERING
import no.nav.tsm.sykmelding.model.LovfestetFravaersgrunn.GODKJENT_HELSEINSTITUSJON
import no.nav.tsm.sykmelding.model.OcrCrossChecked
import no.nav.tsm.sykmelding.model.OcrReading
import no.nav.tsm.sykmelding.model.OcrValue
import no.nav.tsm.sykmelding.model.valueOrNull
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FieldValuesTest {
    private val today = LocalDate.of(2026, 10, 29)

    @Test
    fun `a blank field gives no value`() {
        val parsers = listOf<(String?) -> OcrValue<*>?>(
            { parseDate(it) }, { parseDateIn(it) }, ::parsePercent, ::parseCount, ::parseFnr, ::parseHpr, ::parseTelefon,
            ::parseHarArbeidsgiver, ::parseName,
        )
        for (blank in listOf(null, "", "  ", " \t\n")) {
            parsers.forEach { parse -> assertNull(parse(blank), "for '$blank'") }
        }
    }

    @Test
    fun `a date is read day first or year first`() {
        listOf("01.10.2026", "1.10.26", "01/10-2026", "01 10 2026", "011026", "01102026", "01.10.2026.").forEach {
            assertEquals(OcrValue.Read(LocalDate.of(2026, 10, 1), it), parseDate(it), it)
        }
        listOf("2026.01.20", "2026-01-20").forEach {
            assertEquals(OcrValue.Read(LocalDate.of(2026, 1, 20), it), parseDate(it), it)
        }
    }

    @Test
    fun `text that is no real date is unreadable`() {
        listOf("31.02.26", "1.10").forEach { assertEquals(OcrValue.Unreadable(it, "not a date"), parseDate(it), it) }
        assertEquals(OcrValue.Unreadable("01.10.1850", "year out of range 1900-2100"), parseDate("01.10.1850"))
    }

    @Test
    fun `a two-digit year is in the 1900s only when the 2000s would be after today`() {
        assertEquals(LocalDate.of(1998, 6, 15), parseDate("15.06.98", today).valueOrNull())
        assertEquals(LocalDate.of(2026, 6, 15), parseDate("15.06.26", today).valueOrNull())
        assertEquals(LocalDate.of(1926, 10, 30), parseDate("30.10.26", today).valueOrNull())
        assertEquals(LocalDate.of(2098, 6, 15), parseDate("15.06.98").valueOrNull())
    }

    @Test
    fun `a date is found among other marks and keeps the whole field as its text`() {
        assertEquals(OcrValue.Read(LocalDate.of(2026, 5, 21), "21.05.2026 å"), parseDateIn("21.05.2026 å"))
        assertEquals(OcrValue.Read(LocalDate.of(2026, 1, 20), "2026.01.20 å"), parseDateIn("2026.01.20 å"))
        assertEquals(OcrValue.Unreadable("ingen dato", "not a date"), parseDateIn("ingen dato"))
    }

    @Test
    fun `a percentage is a whole number from 1 to 100`() {
        mapOf("50" to 50, "100 %" to 100, "5 0" to 50).forEach { (text, value) ->
            assertEquals(OcrValue.Read(value, text), parsePercent(text), text)
        }
        listOf("0", "101", "+50", "50-").forEach {
            assertEquals(OcrValue.Unreadable(it, "not a percentage from 1 to 100"), parsePercent(it), it)
        }
    }

    @Test
    fun `a count is a whole number above 0`() {
        assertEquals(OcrValue.Read(2, "2"), parseCount("2"))
        listOf("0", "2%").forEach { assertEquals(OcrValue.Unreadable(it, "not a number above 0"), parseCount(it), it) }
    }

    @Test
    fun `an fnr needs 11 digits and valid control digits`() {
        listOf("30898098466", "308980 98466", "308980-98466").forEach {
            assertEquals(OcrValue.Read("30898098466", it), parseFnr(it), it)
        }
        // A d-number: its birth date is not checked.
        assertEquals(OcrValue.Read("41018012365", "41018012365"), parseFnr("41018012365"))
        assertEquals(OcrValue.Unreadable("30898098467", "wrong control digits"), parseFnr("30898098467"))
        assertEquals(OcrValue.Unreadable("3089809846", "not 11 digits"), parseFnr("3089809846"))
    }

    @Test
    fun `an hpr number has 1 to 9 digits and loses its leading zeros`() {
        listOf("001234567", "123 456 7").forEach { assertEquals(OcrValue.Read("1234567", it), parseHpr(it), it) }
        listOf("0000", "1234567890", "12+34").forEach {
            assertEquals(OcrValue.Unreadable(it, "not an HPR number"), parseHpr(it), it)
        }
    }

    @Test
    fun `a phone number is made compact without country normalization`() {
        mapOf(
            "12 34 56 78" to "12345678",
            "+47 123 45 678" to "+4712345678",
            "0047 12345678" to "004712345678",
            "+46 8 123 456 78" to "+46812345678",
        ).forEach { (text, value) -> assertEquals(OcrValue.Read(value, text), parseTelefon(text), text) }
        listOf("1234567", "12%45678").forEach {
            assertEquals(OcrValue.Unreadable(it, "not a phone number"), parseTelefon(it), it)
        }
    }

    @Test
    fun `har arbeidsgiver is read from the first word`() {
        mapOf(
            "En arbeisgiver" to HarArbeidsgiver.EN_ARBEIDSGIVER,
            "Én arbeidsgiver" to HarArbeidsgiver.EN_ARBEIDSGIVER,
            "Flere arbeidsgivere" to HarArbeidsgiver.FLERE_ARBEIDSGIVERE,
            "INGEN arbeidsgiver" to HarArbeidsgiver.INGEN_ARBEIDSGIVER,
        ).forEach { (text, value) -> assertEquals(OcrValue.Read(value, text), parseHarArbeidsgiver(text), text) }
        assertEquals(OcrValue.Unreadable("Kanskje", "not En, Flere or Ingen"), parseHarArbeidsgiver("Kanskje"))
    }

    @Test
    fun `a name has every run of whitespace inside it read as one space`() {
        assertEquals(OcrValue.Read("Ola Nordmann", "Ola \t Nordmann"), parseName(" Ola \t Nordmann "))
    }

    @Test
    fun `lovfestet fravaersgrunn is read from a first letter followed by a parenthesis`() {
        mapOf(
            BEHANDLING_FORHINDRER_ARBEID to listOf("b) Under behandling", "B)tekst", " b)", "b )", "b  ) tekst"),
            GODKJENT_HELSEINSTITUSJON to listOf("a) Innlagt"),
            BEHANDLING_STERILISERING to listOf("j) Sterilisering"),
        ).forEach { (expected, texts) -> texts.forEach { assertEquals(listOf(expected), LovfestetFravaersgrunn.fraTekst(it), it) } }
    }

    @Test
    fun `text without a letter a-j and a parenthesis first gives no lovfestet fravaersgrunn`() {
        listOf(
            "Under behandling", "b", "b: Under behandling", "(b) tekst", "a, b", "Behandling", "k) ikke en bokstav i loven", "",
        ).forEach { assertEquals(emptyList(), LovfestetFravaersgrunn.fraTekst(it), it) }
    }

    @Test
    fun `only ICD-10 and ICPC-2 are diagnosis systems`() {
        listOf("ICD-10", "ICD 10", "icpc-2", "ICPC2").forEach { assertTrue(isDiagnoseSystem(it), it) }
        listOf(null, "", "Velg", "Velg...", "Velg…", "ICPC-2B").forEach { assertFalse(isDiagnoseSystem(it), it) }
    }

    @Test
    fun `a diagnosis code is looked up in the written system's list`() {
        assertEquals(DiagnoseType.ICPC2 to "R74", resolved("ICPC-2", "R74", "Forkjølelse"))
        assertEquals(DiagnoseType.ICD10 to "K01.1", resolved("ICD 10", "k011"))
        assertEquals(DiagnoseType.ICPC2 to "R74", resolved("icpc-2", "R74"))
        assertEquals("k011", resolveDiagnose("ICD 10", "k011", null).text)
        assertEquals("not in ICD-10", reason("ICD-10", "R74"))
        // ICPC-2B is not supported, so its codes are not in ICPC-2.
        assertEquals("not in ICPC-2", reason("ICPC-2", "R74.0004"))
    }

    @Test
    fun `without a known system a code must be in one list or have its official text`() {
        assertEquals(DiagnoseType.ICPC2 to "R74", resolved("ICPC-2B", "R74"))
        assertEquals(DiagnoseType.ICD10 to "J06.9", resolved(null, "J06.9"))
        assertEquals(DiagnoseType.ICPC2 to "L84", resolved(null, "L84", "Ryggsyndrom uten smerteutstråling"))
        assertEquals(DiagnoseType.ICD10 to "L84", resolved(null, "L84", "liktorner  eller hornhud"))
        assertEquals("in both ICD-10 and ICPC-2", reason(null, "L84", "Vondt i ryggen"))
        // R05 is "Hoste" in both lists.
        assertEquals("in both ICD-10 and ICPC-2", reason(null, "R05", "Hoste"))
        assertEquals("not in ICD-10 or ICPC-2", reason(null, "K01.2"))
    }

    @Test
    fun `an fnr is confirmed by every readable reading and not blocked by an unreadable one`() {
        val four = fnrReadings("30898098466", "30898098466", "308980 98466", "30898098466")
        assertEquals(OcrCrossChecked(OcrValue.Read("30898098466", "30898098466"), four, 4), crossCheckFnr(four))

        val withMisread = fnrReadings("30898098466", "30898098466", "30898098466", "30898098467")
        val checked = crossCheckFnr(withMisread)
        assertEquals(OcrValue.Read("30898098466", "30898098466"), checked.value)
        assertEquals(3, checked.confirmations)
    }

    @Test
    fun `two valid fnr that differ make the fnr unreadable`() {
        val checked = crossCheckFnr(fnrReadings("30898098466", "01018012371"))
        assertEquals(
            OcrValue.Unreadable("30898098466", "different fnr: 30898098466 (tittel.fnr p1), 01018012371 (tittel.fnr p2)"),
            checked.value,
        )
        assertEquals(0, checked.confirmations)
    }

    @Test
    fun `an fnr without a readable reading is unreadable or missing`() {
        val invalid = fnrReadings("30898098467")
        assertEquals(OcrCrossChecked(OcrValue.Unreadable("30898098467", "wrong control digits"), invalid, 0), crossCheckFnr(invalid))
        assertEquals(OcrCrossChecked(null, emptyList(), 0), crossCheckFnr(emptyList()))
    }

    @Test
    fun `a name needs at least two agreeing readings and more than any other name`() {
        val checked = crossCheckName(nameReadings("Historisk", "HISTORISK", "Histor isk", "Histor1sk"))
        assertEquals(OcrValue.Read("Historisk", "Historisk"), checked.value)
        assertEquals(3, checked.confirmations)

        for (names in listOf(listOf("Jeger", "Jeger", "Jæger", "Jæger"), listOf("A", "B", "C"))) {
            val disagreeing = crossCheckName(nameReadings(*names.toTypedArray()))
            assertIs<OcrValue.Unreadable>(disagreeing.value, names.toString())
            assertEquals(0, disagreeing.confirmations, names.toString())
        }

        val single = nameReadings("Jeger")
        assertEquals(OcrCrossChecked(OcrValue.Read("Jeger", "Jeger"), single, 1), crossCheckName(single))
    }

    private fun resolved(system: String?, kode: String, tekst: String? = null): Pair<DiagnoseType, String>? =
        resolveDiagnose(system, kode, tekst).valueOrNull()?.let { it.system to it.code }

    private fun reason(system: String?, kode: String, tekst: String? = null): String? =
        (resolveDiagnose(system, kode, tekst) as? OcrValue.Unreadable)?.reason

    private fun fnrReadings(vararg texts: String): List<OcrReading<String>> =
        texts.mapIndexed { index, text -> OcrReading("tittel.fnr", index + 1, parseFnr(text)!!) }

    private fun nameReadings(vararg texts: String): List<OcrReading<String>> =
        texts.mapIndexed { index, text -> OcrReading("tittel.etternavn", index + 1, parseName(text)!!) }
}
