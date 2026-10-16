package no.nav.tsm.ocr.template

import no.nav.tsm.sykmelding.ocr.Ocr
import no.nav.tsm.sykmelding.ocr.ParseSykmeldingOcr
import no.nav.tsm.sykmelding.template.TemplateMatcher
import org.example.no.nav.tsm.ocr.template.template.TemplateLibrary
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@Tag("ocr-smoke")
class ParseSykmeldingOcrTest {

    @Test
    fun `doOcr prints fields from the svenskmann PDF using template 0_2019`() {
        val library = TemplateLibrary(File("../templates"))

        val pdf = File("src/test/resources/svenskmann.pdf")

        val parser = ParseSykmeldingOcr(Ocr(), TemplateMatcher(library))
        assertDoesNotThrow { parser.doOcr(pdf) }
    }
}
