package no.nav.tsm.ocr.template

import no.nav.tsm.sykmelding.ocr.Ocr
import no.nav.tsm.sykmelding.ocr.ParseSykmeldingOcr
import no.nav.tsm.sykmelding.template.TemplateMatcher
import org.example.no.nav.tsm.ocr.template.template.TemplateLibrary
import org.junit.jupiter.api.assertDoesNotThrow
import java.io.File
import kotlin.test.Test

class ParseSykmeldingOcrTest {

    @Test
    public fun `doOcr prints fields from the svenskmann PDF using template 0_2019`() {
        val library = TemplateLibrary(File("../templates"))

        val pdf = File("/Users/asa/tsm/ocr/test2.pdf")

        val parser = ParseSykmeldingOcr(Ocr(), TemplateMatcher(library))
        assertDoesNotThrow { parser.doOcr(pdf) }
    }
}
