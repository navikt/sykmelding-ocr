package no.nav.tsm.sykmelding.ocr

import no.nav.tsm.sykmelding.model.Bidiagnose
import no.nav.tsm.sykmelding.model.CheckboxState
import no.nav.tsm.sykmelding.model.OcrParsedMeldingTilArbeidsgiver
import no.nav.tsm.sykmelding.model.OcrParserAktivitet
import no.nav.tsm.sykmelding.model.OcrParserAktivitet.Arbeidsgiver
import no.nav.tsm.sykmelding.model.OcrParserArbeidsgiver
import no.nav.tsm.sykmelding.model.OcrParserBedreArbeidsevne
import no.nav.tsm.sykmelding.model.OcrParserDiagnose
import no.nav.tsm.sykmelding.model.OcrParserPasient
import no.nav.tsm.sykmelding.model.OcrParserPrognose
import no.nav.tsm.sykmelding.model.OcrParserSykmelder
import no.nav.tsm.sykmelding.model.OcrParserSykmelding
import no.nav.tsm.sykmelding.model.OcrParserTilbakedatering
import no.nav.tsm.sykmelding.model.OcrParserTilleggsinformasjon
import no.nav.tsm.sykmelding.template.TemplateMatchResult
import no.nav.tsm.sykmelding.template.TemplateMatcher
import org.example.no.nav.tsm.ocr.template.template.Felter
import org.slf4j.LoggerFactory
import java.io.File

class ParseSykmeldingOcr(
    private val ocr: Ocr,
    private val templateMatcher: TemplateMatcher = TemplateMatcher(),
) {

    private val logger = LoggerFactory.getLogger(javaClass)

    fun doOcr(pdf: File): OcrParserSykmelding? = parse(templateMatcher.matchPdf(pdf))

    /** As [doOcr], for a PDF in memory, so it need not be saved first. */
    fun doOcr(pdf: ByteArray): OcrParserSykmelding? = parse(templateMatcher.matchPdf(pdf))

    private fun parse(match: TemplateMatchResult): OcrParserSykmelding? {
        val sykmeldingTemplate = requireMatch(match)
        if (sykmeldingTemplate !is TemplateMatchResult.Matched) {
            return null
        }
        val pages = readPages(sykmeldingTemplate, sykmeldingTemplate.template.manifest.navn)

        val valuesByName = byFieldName(pages.map { page ->
            page.values.filterKeys { !it.type.skipsOcr }.mapValues { (_, text) -> text.text }
        })
        val checkboxReadings = byFieldName(pages.map { it.checkboxes })
        checkboxReadings.filterValues { it.state == CheckboxState.UNCERTAIN }.takeIf { it.isNotEmpty() }?.let { uncertain ->
            logger.warn("Uncertain checkboxes in template ${sykmeldingTemplate.template.manifest.navn}: " +
                uncertain.entries.joinToString { (name, reading) -> "$name (${reading.coverageText})" })
        }
        val checkboxesByName = checkboxReadings.mapValues { it.value.state }

        if(sykmeldingTemplate.template.manifest.navn == "0.2019" || sykmeldingTemplate.template.manifest.navn == "03.2019") {
            val sykmelding = threePage2019TemplateMapToOcrParsedSykmelding(valuesByName, checkboxesByName)
            return sykmelding
        }

        return null
    }

    private fun <T> byFieldName(pages: List<Map<Felter, T>>): Map<String, T> = buildMap {
        pages.forEach { fieldsOnPage ->
            fieldsOnPage
                .filterKeys { it.navn !in EXCLUDED_FIELDS }
                .forEach { (field, value) ->
                    require(!containsKey(field.navn)) {
                        "Duplicate field name: ${field.navn}"
                    }
                    put(field.navn, value)
                }
        }
    }

    private fun threePage2019TemplateMapToOcrParsedSykmelding(
        valuesByName: Map<String, String?>,
        checkboxes: Map<String, CheckboxState>,
    ): OcrParserSykmelding {
        val pasient = OcrParserPasient(
            etternavn = valuesByName["pasient.etternavn"],
            fornavn = valuesByName["pasient.fornavn"],
            fnr = valuesByName["pasient.fnr"],
            telefon = valuesByName["pasient.telefon"],
            fastlege = valuesByName["pasient.fastlege"],
            navKontor = valuesByName["pasient.navkontor"],
            )

        val arbeidsgiver = OcrParserArbeidsgiver(
            flereArbeidsgivere = valuesByName["arbeidsgiver.type"] == Arbeidsgiver.EN_ARBEIDSGIVER.name, // vil vi egentlig sette flereArbeidsgivere sånn?
            navn = valuesByName["arbeidsgiver.arbeidsgiver"],
            yrkeStilling = valuesByName["arbeidsgiver.yrke"],
            stillingsprosent = valuesByName["arbeidsgiver.stillingsprosent"]?.toIntOrNull()
        )

        val diagnose = OcrParserDiagnose(
            hoveddiagnoseKodesystem = valuesByName["hoveddiagnose.system"],
            hoveddiagnoseKode = valuesByName["hoveddiagnose.kode"],
            hoveddiagnoseTekst = valuesByName["hoveddiagnose.text"],
            // bør vi støtte liste her? kan det være liste på papir? korleis ser det ut? kva nummer er då felta? vi støtter vel berre 1 value per boks.
            bidiagnoser = listOf(
                Bidiagnose(
                    kodesystem = valuesByName["bidiagnose.system"],
                    kode = valuesByName["bidiagnose.kode"],
                    tekst = valuesByName["bidiagnose.text"],
                )
            ),
            lovfestetFraversgrunn = valuesByName["diagnose.lovfestetfravarsgrunn"],
            fraversgrunnBeskrivelse = valuesByName["diagnose.fravarsgrunnBeskrivelse"],
            svangerskap = checkboxes.state("diagnose.svangerskapsrelatert"),
            yrkesskade = checkboxes.state("diagnose.yrkesskade"),
            skadedato = valuesByName["diagnose.yrkesskade.skadedato"],
            skjermingPaakrevet = checkboxes.state("diagnose.skjermes"),
        )

        val aktiviteter = mapThreePage2019Activities(valuesByName, checkboxes)

        val ocrParserPrognose = OcrParserPrognose(
            arbeidsforEtterPeriode = checkboxes.state("prognose.arbeidsforEtterPerioden"),
            beskrivHensyn = valuesByName["prognose.arbeidsforEtterPerioden.beskrivelse"],
        )

        val ocrParserBedreArbeidsevne = OcrParserBedreArbeidsevne(
            tilleggsinformasjonForArbeidsgiver = valuesByName["arbeidsevne.tilretteleggingArbeidsplassen"],
            tiltakIRegiAvNav = valuesByName["arbeidsevne.tiltakNav"],
            eventuelleAndreInnspillTilNav = valuesByName["arbeidsevne.innspillNav"],
        )

        val ocrParsedTilleggsinformasjon = OcrParserTilleggsinformasjon(
            bistandNavOnskes = checkboxes.state("melding.nav.bistand"),
            beskrivBistandOnskes = valuesByName["melding.nav.beskrivelse"],
        )

        val ocrParsedMeldingTilArbeidsgiver = OcrParsedMeldingTilArbeidsgiver(
            innspillTilArbeidsgiver = valuesByName["melding.arbeidsgiver.andreInnspill"],
        )

        val ocrParsedTilbakedatering = OcrParserTilbakedatering(
            kontaktDato = valuesByName["tilbakedatering.kontaktDato"],
            begrunnelse = valuesByName["tilbakedatering.beskrivelse"],
        )

        val ocrParsedSykmelder = OcrParserSykmelder(
            dato = valuesByName["bekreftelse.signatur"],
            navn = valuesByName["bekreftelse.navn"],
            hprNummer = valuesByName["bekreftelse.hpr"],
            telefon = valuesByName["bekreftelse.telefon"],
            adresse = valuesByName["bekreftelse.adresse"]
        )

        val ocrParsedSykmelding = OcrParserSykmelding(
            pasient = pasient,
            arbeidsgiver = arbeidsgiver,
            diagnose = diagnose,
            aktiviteter = aktiviteter,
            prognose = ocrParserPrognose,
            bedreArbeidsevne = ocrParserBedreArbeidsevne,
            tilleggsinformasjon = ocrParsedTilleggsinformasjon,
            meldingTilArbeidsgiver = ocrParsedMeldingTilArbeidsgiver,
            tilbakedatering = ocrParsedTilbakedatering,
            sykmelder = ocrParsedSykmelder,
            formType = null,
            formVersion = null,
            vurderingType = null,
            legemeldtFravaerStart = null,
            meta = null
        )

        return ocrParsedSykmelding
    }

    internal fun mapThreePage2019Activities(
        valuesByName: Map<String, String?>,
        checkboxes: Map<String, CheckboxState>,
    ): List<OcrParserAktivitet> =
        listOf(
            OcrParserAktivitet.Avventende(
                fom = valuesByName["aktivitet.avventende.fom"],
                tom = valuesByName["aktivitet.avventende.tom"],
            ),
            OcrParserAktivitet.Gradert(
                fom = valuesByName["aktivitet.gradert.fom"],
                tom = valuesByName["aktivitet.gradert.tom"],
                grad = valuesByName["aktivitet.gradert.grad"]?.toIntOrNull(),
                reisetilskudd = checkboxes.state("aktivitet.gradert.reisetilskudd"),
            ),
            OcrParserAktivitet.IkkeMulig(
                fom = valuesByName["aktivitet.fom"],
                tom = valuesByName["aktivitet.tom"],
                medisinskArsak = checkboxes.state("aktivitet.medisinskeArsaker"),
                arbeidsrelatertArsak = checkboxes.state("aktivitet.forholdArbeidsplassen"),
            ),
            OcrParserAktivitet.Behandlingsdager(
                fom = valuesByName["aktivitet.behandlingsdager.fom"],
                tom = valuesByName["aktivitet.behandlingsdager.tom"],
                antallBehandlingsdager = valuesByName["aktivitet.behandlingsdager.dager"]?.toIntOrNull(),
            ),
            OcrParserAktivitet.Reisetilskudd(
                fom = valuesByName["aktivitet.reisetilskudd.fom"],
                tom = valuesByName["aktivitet.reisetilskudd.tom"],
            ),
        ).filter { !it.fom.isNullOrBlank() && !it.tom.isNullOrBlank() }

    private fun requireMatch(sykmeldingTemplate: TemplateMatchResult): TemplateMatchResult {
        if (sykmeldingTemplate !is TemplateMatchResult.Matched) {
            throw IllegalArgumentException(
                "No template matched for the given PDF: $sykmeldingTemplate",
                (sykmeldingTemplate as? TemplateMatchResult.Error)?.exception,
            )
        }
        return sykmeldingTemplate
    }


    private fun readPages(
        sykmeldingTemplate: TemplateMatchResult.Matched,
        templateVersion: String
    ): List<PageRead> = sykmeldingTemplate.templateMatch.map { (template, image) ->
        logger.info("template: $templateVersion, page: ${template.navn}")
        when (templateVersion) {
            "03.2019", "0.2019" -> {
                ocr.readPage(image, template.felter)
            }

            else -> {
                throw IllegalArgumentException("No parser available for template: $templateVersion")
            }
        }
    }

    private companion object {
        val EXCLUDED_FIELDS = setOf("tittel.etternavn", "tittel.fornavn", "tittel.fnr")
    }
}
private fun Map<String, CheckboxState>.state(name: String): CheckboxState = this[name] ?: CheckboxState.UNCERTAIN
