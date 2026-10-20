package no.nav.tsm.ocr.template

import no.nav.tsm.sykmelding.ocr.Ocr
import no.nav.tsm.sykmelding.ocr.ParseSykmeldingOcr
import no.nav.tsm.sykmelding.template.TemplateMatcher
import org.example.no.nav.tsm.ocr.template.template.TemplateLibrary
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.assertAll
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

@Tag("ocr-smoke")
class ParseSykmeldingOcrTest {

    @Test
    fun `doOcr maps patient and main diagnosis from the svenskmann PDF`() {
        val library = TemplateLibrary(File("../templates"))
        val pdf = File(requireNotNull(javaClass.getResource("/svenskmann.pdf")) {
            "Missing test PDF: /svenskmann.pdf"
        }.toURI())

        TemplateMatcher(library).use { matcher ->
            val result = assertNotNull(ParseSykmeldingOcr(Ocr(), matcher).doOcr(pdf))
            val pasient = assertNotNull(result.pasient)
            val diagnose = assertNotNull(result.diagnose)
            val sykmelder = assertNotNull(result.sykmelder)
            val arbeidsgiver = assertNotNull(result.arbeidsgiver)
            val aktiviteter = assertNotNull(result.aktiviteter)

            assertAll(
                "Patient and main diagnosis from svenskmann.pdf",
                { assertEquals("Svenskmann", pasient.etternavn, "pasient.etternavn") },
                { assertEquals("Ola", pasient.fornavn, "pasient.fornavn") },
                { assertEquals("Svenskmann", pasient.etternavn, "pasient.etternavn") },
                { assertEquals("01019112159", pasient.fnr, "pasient.fnr") },
                { assertEquals("Lærer", arbeidsgiver.yrkeStilling, "arbeidsgiver.yrke") },
                { assertEquals("ICD-10", diagnose.hoveddiagnoseKodesystem, "diagnose.hoveddiagnoseKodesystem") },
                { assertEquals("Y01", diagnose.hoveddiagnoseKode, "diagnose.hoveddiagnoseKode") },
                { assertEquals("Smerter i penis", diagnose.hoveddiagnoseTekst, "diagnose.hoveddiagnoseTekst") },
                { assertEquals(5, aktiviteter.size, "aktiviteter.size") },
                { assertEquals("Helge Legesen", sykmelder.navn, "bekreftelse.navn") },
                { assertEquals("777888", sykmelder.hprNummer, "bekreftelse.hpr") },
            )
        }
    }
}
