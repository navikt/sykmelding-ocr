package no.nav.tsm.sykmelding.template

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class TemplateMatcherTest {
    @Test
    fun `a PDF in memory matches the packaged templates as the same PDF read from a file does`() {
        val bytes = requireNotNull(javaClass.getResourceAsStream("/test.pdf")) { "Missing /test.pdf" }.use { it.readBytes() }
        val file = File(requireNotNull(javaClass.getResource("/test.pdf")) { "Missing /test.pdf" }.toURI())
        TemplateMatcher().use { matcher ->
            val fromBytes = assertIs<TemplateMatchResult.Matched>(matcher.matchPdf(bytes))
            val fromFile = assertIs<TemplateMatchResult.Matched>(matcher.matchPdf(file))
            assertEquals(fromFile.template, fromBytes.template)
            assertEquals(fromFile.templateMatch.keys.toList(), fromBytes.templateMatch.keys.toList())
        }
    }

    @Test
    fun `bytes that are not a PDF are an error result`() {
        TemplateMatcher(templates = emptyList()).use { matcher ->
            assertIs<TemplateMatchResult.Error>(matcher.matchPdf("not a PDF".toByteArray()))
        }
    }
}
