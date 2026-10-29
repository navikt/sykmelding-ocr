package no.nav.tsm.sykmelding.ocr

import no.nav.tsm.sykmelding.template.TemplateMatchResult
import no.nav.tsm.sykmelding.template.TemplateMatcher
import org.junit.jupiter.api.AfterEach
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class DoOcrResultTest {
    private val matcher = TemplateMatcher(templates = emptyList())
    private val parser = ParseSykmeldingOcr(Ocr(), matcher)

    @AfterEach
    fun closeMatcher() {
        matcher.close()
    }

    @Test
    fun `an unreadable PDF is Failed, not an exception`() {
        assertIs<OcrResult.Failed>(parser.doOcr("not a PDF".toByteArray()))
    }

    @Test
    fun `no match is NoMatch, not an exception`() {
        assertEquals(OcrResult.NoMatch, parser.doOcr(TemplateMatchResult.NoMatch))
    }

    @Test
    fun `too many pages is TooManyPages`() {
        assertEquals(OcrResult.TooManyPages(9, 4), parser.doOcr(TemplateMatchResult.TooManyPages(9, 4)))
    }
}
