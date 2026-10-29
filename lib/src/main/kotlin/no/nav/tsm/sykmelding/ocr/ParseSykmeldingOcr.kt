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
import no.nav.tsm.sykmelding.template.PdfTemplateImages
import no.nav.tsm.sykmelding.template.TemplateMatchResult
import no.nav.tsm.sykmelding.template.TemplateMatcher
import no.nav.tsm.sykmelding.template.Felter
import org.slf4j.LoggerFactory
import java.io.File

/** What [ParseSykmeldingOcr.doOcr] made of a PDF. It never throws: every way it can fail is a case here. */
sealed interface OcrResult {
    /** [pages] are the raw reads the [sykmelding] was mapped from, one per template page. */
    data class Parsed(val sykmelding: OcrParserSykmelding, val pages: List<PageRead>) : OcrResult
    data object NoMatch : OcrResult
    data class TooManyPages(val pages: Int, val maxPages: Int) : OcrResult
    data class Failed(val reason: String, val cause: Throwable? = null) : OcrResult
}

class ParseSykmeldingOcr(
    private val ocr: Ocr,
    private val templateMatcher: TemplateMatcher = TemplateMatcher(),
) {

    private val logger = LoggerFactory.getLogger(javaClass)

    fun doOcr(pdf: File): OcrResult = doOcr(templateMatcher.matchPdf(pdf))

    /** As [doOcr], for a PDF in memory, so it need not be saved first. */
    fun doOcr(pdf: ByteArray): OcrResult = doOcr(templateMatcher.matchPdf(pdf))

    /** As [doOcr], for a PDF already matched, with its fields read at [ocrDpi]. */
    fun doOcr(match: TemplateMatchResult, ocrDpi: Int = PdfTemplateImages.DPI): OcrResult = when (match) {
        is TemplateMatchResult.Matched -> try {
            parse(match, ocrDpi)
        } catch (e: Exception) {
            logger.warn("Could not read the PDF with template ${match.template.manifest.navn}", e)
            OcrResult.Failed(e.message ?: e.toString(), e)
        }
        TemplateMatchResult.NoMatch -> OcrResult.NoMatch
        is TemplateMatchResult.TooManyPages -> OcrResult.TooManyPages(match.pages, match.maxPages)
        is TemplateMatchResult.Error -> OcrResult.Failed(match.exception.message ?: match.exception.toString(), match.exception)
    }

    private fun parse(sykmeldingTemplate: TemplateMatchResult.Matched, ocrDpi: Int): OcrResult {
        val pages = readPages(sykmeldingTemplate, sykmeldingTemplate.template.manifest.navn, ocrDpi)

        val checkboxReadings = byFieldName(pages.map { it.checkboxes })
        checkboxReadings.filterValues { it.state == CheckboxState.UNCERTAIN }.takeIf { it.isNotEmpty() }?.let { uncertain ->
            logger.warn("Uncertain checkboxes in template ${sykmeldingTemplate.template.manifest.navn}: " +
                uncertain.entries.joinToString { (name, reading) -> "$name (${reading.coverageText})" })
        }
        val sykmelding = SykmeldingOcrMapping.map(sykmeldingTemplate.template.manifest.navn, pages)
            ?: return OcrResult.Failed("No parser for template ${sykmeldingTemplate.template.manifest.navn}")
        return OcrResult.Parsed(sykmelding, pages)
    }

    internal fun mapThreePage2019Activities(
        valuesByName: Map<String, String?>,
        checkboxes: Map<String, CheckboxState>,
    ): List<OcrParserAktivitet> = SykmeldingOcrMapping.mapThreePage2019Activities(valuesByName, checkboxes)

    private fun readPages(
        sykmeldingTemplate: TemplateMatchResult.Matched,
        templateVersion: String,
        ocrDpi: Int,
    ): List<PageRead> = sykmeldingTemplate.templateMatch.map { (template, image) ->
        logger.info("template: $templateVersion, page: ${template.navn}")
        when (templateVersion) {
            "03.2019", "0.2019" -> ocr.readPage(image, template.felter, ocrDpi)
            else -> throw IllegalArgumentException("No parser available for template: $templateVersion")
        }
    }
}

/** Maps already-read pages exactly as production does, without matching or running OCR again. */
object SykmeldingOcrMapping {
    fun map(templateVersion: String, pages: List<PageRead>): OcrParserSykmelding? {
        if (templateVersion != "0.2019" && templateVersion != "03.2019") return null
        val values = byFieldName(pages.map { page ->
            page.values.filterKeys { !it.type.skipsOcr }.mapValues { (_, text) -> text.text }
        })
        val checkboxes = byFieldName(pages.map { page -> page.checkboxes.mapValues { it.value.state } })
        return threePage2019TemplateMapToOcrParsedSykmelding(values, checkboxes)
    }

    private fun threePage2019TemplateMapToOcrParsedSykmelding(
        valuesByName: Map<String, String?>,
        checkboxes: Map<String, CheckboxState>,
    ): OcrParserSykmelding {
        val pasient = OcrParserPasient(
            etternavn = valuesByName["pasient.etternavn"],
            fornavn = valuesByName["pasient.fornavn"],
            fnr = valuesByName["pasient.fnr"]?.filter { it in '0'..'9' },
            telefon = valuesByName["pasient.telefon"],
            fastlege = valuesByName["pasient.fastlege"],
            navKontor = valuesByName["pasient.navkontor"],
            )

        val arbeidsgiver = OcrParserArbeidsgiver(
            flereArbeidsgivere = valuesByName["arbeidsgiver.type"] == Arbeidsgiver.EN_ARBEIDSGIVER.name, // vil vi egentlig sette flereArbeidsgivere sånn?
            navn = valuesByName["arbeidsgiver.arbeidsgiver"],
            yrkeStilling = valuesByName["arbeidsgiver.yrke"],
            stillingsprosent = valuesByName["arbeidsgiver.stillingsprosent"]?.toIntOrNull(),
            harArbeidsgiver = valuesByName["arbeidsgiver.type"],
        )

        val diagnose = OcrParserDiagnose(
            hoveddiagnoseKodesystem = valuesByName["hoveddiagnose.system"],
            hoveddiagnoseKode = normalizeDiagnosekode(valuesByName["hoveddiagnose.kode"]),
            hoveddiagnoseTekst = valuesByName["hoveddiagnose.text"],
            // bør vi støtte liste her? kan det være liste på papir? korleis ser det ut? kva nummer er då felta? vi støtter vel berre 1 value per boks.
            bidiagnoser = listOf(
                Bidiagnose(
                    kodesystem = valuesByName["bidiagnose.system"],
                    kode = normalizeDiagnosekode(valuesByName["bidiagnose.kode"]),
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
            kontaktDato = valuesByName["tilbakedatering.kontaktdato"],
            begrunnelse = valuesByName["tilbakedatering.beskrivelse"],
        )

        val ocrParsedSykmelder = OcrParserSykmelder(
            dato = dateIn(valuesByName["bekreftelse.signatur"]),
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
            legemeldtFravaerStart = valuesByName["startdatoSykefravar"],
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
                innspillTilArbeidsgiver = valuesByName["aktivitet.innspillArbeidsgiver"],
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

}

private val EXCLUDED_FIELDS = setOf("tittel.etternavn", "tittel.fornavn", "tittel.fnr")

private fun <T> byFieldName(pages: List<Map<Felter, T>>): Map<String, T> = buildMap {
    pages.forEach { fieldsOnPage ->
        fieldsOnPage.filterKeys { it.navn !in EXCLUDED_FIELDS }.forEach { (field, value) ->
            require(!containsKey(field.navn)) { "Duplicate field name: ${field.navn}" }
            put(field.navn, value)
        }
    }
}

private fun Map<String, CheckboxState>.state(name: String): CheckboxState = this[name] ?: CheckboxState.UNCERTAIN

/** A date as written: day, month and year split by `.`, `/` or `-`, e.g. `21.05.2026` or `21.5.26`. */
private val writtenDate = Regex("""\d{1,2}\s*[./-]\s*\d{1,2}\s*[./-]\s*(?:\d{4}|\d{2})(?!\d)""")

/**
 * The date in [text], a field that also holds other marks, like the signature field: the signature's strokes are
 * read as stray letters around the date, e.g. `21.05.2026 å`. Without a date, [text] is given back as read.
 */
internal fun dateIn(text: String?): String? =
    text?.let { writtenDate.find(it)?.value?.filterNot(Char::isWhitespace) ?: it }
