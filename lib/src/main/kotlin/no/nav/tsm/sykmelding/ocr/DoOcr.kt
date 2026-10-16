package no.nav.tsm.sykmelding.ocr

import no.nav.tsm.sykmelding.model.OcrParserSykmelding
import no.nav.tsm.sykmelding.template.TemplateMatchResult
import no.nav.tsm.sykmelding.template.TemplateMatcher
import java.io.File

interface IDoOcr {
    fun doOcr(pdf: File): OcrParserSykmelding?
}

class ParseSykmeldingOcr(
    private val ocr: Ocr,
    private val templateMatcher: TemplateMatcher = TemplateMatcher(),
) : IDoOcr {
    override fun doOcr(pdf: File): OcrParserSykmelding? {
        val sykmeldingTemplate = templateMatcher.matchPdf(pdf)
        if(sykmeldingTemplate !is TemplateMatchResult.Matched) {
            throw IllegalArgumentException(
                "No template matched for the given PDF: $sykmeldingTemplate",
                (sykmeldingTemplate as? TemplateMatchResult.Error)?.exception,
            )
        }
        val templateVersion = sykmeldingTemplate.template.manifest.navn
        sykmeldingTemplate.templateMatch.forEach { (template, image) ->
            println("template: $templateVersion, page: ${template.navn}")
            when (templateVersion) {
                "03.2019" -> {
                    val objekt = ocr.parse3pager02019(template, image)
                    objekt.forEach { pair, felter ->
                     println("Field : ${pair.first},\t type: ${felter.type}, \t value: ${pair.second}")
                    }
                //TODO map 0.2019 til objekt
                }
                "0.2019" -> {
                    // samme parser(?), egen mapper.
                }
                else -> {
                    throw IllegalArgumentException("No parser available for template: $templateVersion")
                }
            }
        }
                // sjekk alle typane og handter dei. OM vi har match må vi sjekke kva type vi har også sende den til rett parser. må ha fleire parsers clearly


        return null
    }
}