package no.nav.tsm.sykmelding.ocr

import kotlin.test.Test

class CustomTextTemplateParserTest {
    @Test
    fun `the saved page sykmelding reads as the default sykmelding`() = assertPage("sykmelding")
}
