package no.nav.tsm.ocr.template.gcp

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.File

class TemplateMatchResultTest {

    @Test
    fun `Should be able to create template match result`() {
        val templateMatcher = TemplateMatcher()
        val testPdf = File("src/test/resources/test.pdf")

        val result = templateMatcher.matchPdf(testPdf)
        assertTrue { result is TemplateMatchResult.Matched }
    }
}