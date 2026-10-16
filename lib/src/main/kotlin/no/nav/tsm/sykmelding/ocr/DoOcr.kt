package no.nav.tsm.sykmelding.ocr
import no.nav.tsm.sykmelding.model.OcrParserSykmelding
import no.nav.tsm.sykmelding.template.TemplateMatchResult
import no.nav.tsm.sykmelding.template.TemplateMatcher
import org.example.no.nav.tsm.ocr.template.template.Felter
import org.example.no.nav.tsm.ocr.template.template.Template
import org.slf4j.LoggerFactory
import java.awt.image.BufferedImage
import java.io.File

interface IDoOcr {
    fun doOcr(pdf: File): OcrParserSykmelding?
    fun readOcrFields(templateMatchResult: TemplateMatchResult.Matched): List<TemplateOcrFiledResult>
}

class TemplateOcrFiledResult(
    val template: Template,
    val fields: Map<Felter, String?>,
)

class ParseSykmeldingOcr(
    private val ocr: Ocr,
    private val templateMatcher: TemplateMatcher = TemplateMatcher(),
) : IDoOcr {

    private val logger = LoggerFactory.getLogger(javaClass)
    override fun doOcr(pdf: File): OcrParserSykmelding? {
        val sykmeldingTemplate = matchTemplate(pdf)
        if(sykmeldingTemplate !is TemplateMatchResult.Matched) {
            return null
        }
        val ocrValues = readOcrValues(sykmeldingTemplate, sykmeldingTemplate.template.manifest.navn)

        return null //TODO map ocrvalues and return OcrParserSykmelding
    }

    override fun readOcrFields(
       templateMatchResult: TemplateMatchResult.Matched
    ): List<TemplateOcrFiledResult> {
        return templateMatchResult.templateMatch.map {
            TemplateOcrFiledResult(template = it.key, ocr.parsePageWithFields(it.key, it.value))
        }
    }

    private fun matchTemplate(pdf: File): TemplateMatchResult {
        val sykmeldingTemplate = templateMatcher.matchPdf(pdf)
        if (sykmeldingTemplate !is TemplateMatchResult.Matched) {
            throw IllegalArgumentException(
                "No template matched for the given PDF: $sykmeldingTemplate",
                (sykmeldingTemplate as? TemplateMatchResult.Error)?.exception,
            )
        }
        return sykmeldingTemplate
    }


    private fun readOcrValues(
        sykmeldingTemplate: TemplateMatchResult.Matched,
        templateVersion: String
    ): List<Map<Felter, String?>> = sykmeldingTemplate.templateMatch.map { (template, image) ->
        logger.info("template: $templateVersion, page: ${template.navn}")
        when (templateVersion) {
            "03.2019", "0.2019" -> {
                ocr.parsePageWithFields(template, image)
            }

            else -> {
                throw IllegalArgumentException("No parser available for template: $templateVersion")
            }
        }
    }
}