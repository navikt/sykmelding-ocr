package no.nav.tsm.sykmelding.ocr

import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.instrumentation.annotations.WithSpan
import no.nav.tsm.sykmelding.model.CheckboxState
import no.nav.tsm.sykmelding.model.LovfestetFravaersgrunn
import no.nav.tsm.sykmelding.model.OcrParserAktivitet
import no.nav.tsm.sykmelding.model.OcrParserAnnenFraversArsak
import no.nav.tsm.sykmelding.model.OcrParserArbeidsgiver
import no.nav.tsm.sykmelding.model.OcrParserBehandler
import no.nav.tsm.sykmelding.model.OcrParserBistandNav
import no.nav.tsm.sykmelding.model.OcrParserDiagnose
import no.nav.tsm.sykmelding.model.OcrParserMedisinskVurdering
import no.nav.tsm.sykmelding.model.OcrParserPasient
import no.nav.tsm.sykmelding.model.OcrParserPrognose
import no.nav.tsm.sykmelding.model.OcrParserSykmelding
import no.nav.tsm.sykmelding.model.OcrParserTilbakedatering
import no.nav.tsm.sykmelding.model.OcrParserTiltak
import no.nav.tsm.sykmelding.model.OcrReading
import no.nav.tsm.sykmelding.model.OcrValue
import no.nav.tsm.sykmelding.template.PdfTemplateImages
import no.nav.tsm.sykmelding.template.TemplateMatchResult
import no.nav.tsm.sykmelding.template.TemplateMatcher
import no.nav.tsm.sykmelding.template.Felter
import org.slf4j.LoggerFactory
import java.io.File
import java.time.LocalDate

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

    @WithSpan
    fun doOcr(pdf: File): OcrResult {
        val span = Span.current()

        span.setAttribute("pdf.path", pdf.absolutePath)
        span.setAttribute("pdf.size", pdf.length())

        return doOcr(templateMatcher.matchPdf(pdf))
    }

    /** As [doOcr], for a PDF in memory, so it need not be saved first. */
    @WithSpan
    fun doOcr(pdf: ByteArray): OcrResult {
        val span = Span.current()

        span.setAttribute("pdf.path", "in-memory")
        span.setAttribute("pdf.size", pdf.size.toLong())

        return doOcr(templateMatcher.matchPdf(pdf))
    }

    /** As [doOcr], for a PDF already matched, with its fields read at [ocrDpi]. */
    @WithSpan
    fun doOcr(match: TemplateMatchResult, ocrDpi: Int = PdfTemplateImages.DPI): OcrResult = when (match) {
        is TemplateMatchResult.Matched -> try {
            parse(match, ocrDpi)
        } catch (e: Exception) {
            logger.warn("Could not read the PDF with template ${match.template.manifest.navn}", e)
            OcrResult.Failed(e.message ?: e.toString(), e)
        }

        TemplateMatchResult.NoMatch -> OcrResult.NoMatch
        is TemplateMatchResult.TooManyPages -> OcrResult.TooManyPages(match.pages, match.maxPages)
        is TemplateMatchResult.Error -> OcrResult.Failed(
            match.exception.message ?: match.exception.toString(),
            match.exception
        )
    }

    @WithSpan
    private fun parse(sykmeldingTemplate: TemplateMatchResult.Matched, ocrDpi: Int): OcrResult {
        val pages = readPages(sykmeldingTemplate, sykmeldingTemplate.template.manifest.navn, ocrDpi)

        val span = Span.current()
        span.setAttribute("ocr.pages", pages.size.toString())
        span.setAttribute("ocr.dpi", ocrDpi.toString())
        span.setAttribute("ocr.template", sykmeldingTemplate.template.manifest.navn)

        val checkboxReadings = byFieldName(pages.map { it.checkboxes })
        checkboxReadings.filterValues { it.state == CheckboxState.UNCERTAIN }.takeIf { it.isNotEmpty() }
            ?.let { uncertain ->
                logger.warn(
                    "Uncertain checkboxes in template ${sykmeldingTemplate.template.manifest.navn}: " +
                            uncertain.entries.joinToString { (name, reading) -> "$name (${reading.coverageText})" })
            }
        val sykmelding = SykmeldingOcrMapping.map(sykmeldingTemplate.template.manifest.navn, pages)
        if (sykmelding == null) {
            span.setStatus(StatusCode.ERROR)
            span.setAttribute("ocr.error", "No parser for template")
            return OcrResult.Failed("No parser for template ${sykmeldingTemplate.template.manifest.navn}")
        }

        span.setAttribute("ocr.parsed", "true")
        span.setAttribute("ocr.parsed.template", sykmelding.templateVersion)

        return OcrResult.Parsed(sykmelding, pages)
    }

    @WithSpan
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
    /** [today] decides the century of a two-digit year in the yrkesskade date, the only date that can be long ago. */
    fun map(templateVersion: String, pages: List<PageRead>, today: LocalDate = LocalDate.now()): OcrParserSykmelding? {
        if (templateVersion != "0.2019" && templateVersion != "03.2019") return null
        val texts =
            pages.map { page -> page.values.filterKeys { !it.type.skipsOcr }.mapValues { (_, text) -> text.text } }
        val values = byFieldName(texts)
        val checkboxes = byFieldName(pages.map { page -> page.checkboxes.mapValues { it.value.state } })
        return threePage2019(templateVersion, texts, values, checkboxes, today)
    }

    private fun threePage2019(
        templateVersion: String,
        pages: List<Map<Felter, String>>,
        valuesByName: Map<String, String?>,
        checkboxes: Map<String, CheckboxState>,
        today: LocalDate,
    ): OcrParserSykmelding {
        fun text(name: String): String? = valuesByName[name].trimmedOrNull()

        fun diagnose(prefix: String): OcrParserDiagnose? {
            val system = text("$prefix.system")
            if (!isDiagnoseSystem(system)) return null
            val kode = text("$prefix.kode")
            val tekst = text("$prefix.text")
            return OcrParserDiagnose(kode?.let { resolveDiagnose(system, it, tekst) }, system, tekst)
        }

        val lovfestet = text("diagnose.lovfestetfravarsgrunn")
        val fraversBeskrivelse = text("diagnose.fravarsgrunnBeskrivelse")

        return OcrParserSykmelding(
            templateVersion = templateVersion,
            syketilfelletStartDato = parseDate(valuesByName["startdatoSykefravar"]),
            pasient = OcrParserPasient(
                fnr = crossCheckFnr(readingsOf(pages, "pasient.fnr", "tittel.fnr", ::parseFnr)),
                fornavn = crossCheckName(readingsOf(pages, "pasient.fornavn", "tittel.fornavn", ::parseName)),
                etternavn = crossCheckName(readingsOf(pages, "pasient.etternavn", "tittel.etternavn", ::parseName)),
                telefon = parseTelefon(valuesByName["pasient.telefon"]),
                navnFastlege = text("pasient.fastlege"),
                navKontor = text("pasient.navkontor"),
            ),
            arbeidsgiver = OcrParserArbeidsgiver(
                harArbeidsgiver = parseHarArbeidsgiver(valuesByName["arbeidsgiver.type"]),
                navn = text("arbeidsgiver.arbeidsgiver"),
                yrkesbetegnelse = text("arbeidsgiver.yrke"),
                stillingsprosent = parsePercent(valuesByName["arbeidsgiver.stillingsprosent"]),
            ),
            medisinskVurdering = OcrParserMedisinskVurdering(
                hovedDiagnose = diagnose("hoveddiagnose"),
                biDiagnoser = listOfNotNull(diagnose("bidiagnose")),
                annenFraversArsak = if (lovfestet == null && fraversBeskrivelse == null) null else OcrParserAnnenFraversArsak(
                    LovfestetFravaersgrunn.fraTekst(lovfestet.orEmpty()), lovfestet, fraversBeskrivelse,
                ),
                svangerskap = checkboxes.state("diagnose.svangerskapsrelatert"),
                yrkesskade = checkboxes.state("diagnose.yrkesskade"),
                // A Text field: the date can share it with other marks.
                yrkesskadeDato = parseDateIn(valuesByName["diagnose.yrkesskade.skadedato"], today),
                skjermetForPasient = checkboxes.state("diagnose.skjermes"),
            ),
            aktivitet = mapThreePage2019Activities(valuesByName, checkboxes),
            prognose = OcrParserPrognose(
                arbeidsforEtterPeriode = checkboxes.state("prognose.arbeidsforEtterPerioden"),
                hensynArbeidsplassen = text("prognose.arbeidsforEtterPerioden.beskrivelse"),
            ),
            tiltak = OcrParserTiltak(
                tiltakArbeidsplassen = text("arbeidsevne.tilretteleggingArbeidsplassen"),
                tiltakNav = text("arbeidsevne.tiltakNav"),
                andreTiltak = text("arbeidsevne.innspillNav"),
            ),
            bistandNav = OcrParserBistandNav(
                bistandUmiddelbart = checkboxes.state("melding.nav.bistand"),
                beskrivBistand = text("melding.nav.beskrivelse"),
            ),
            meldingTilArbeidsgiver = text("melding.arbeidsgiver.andreInnspill"),
            tilbakedatering = OcrParserTilbakedatering(
                kontaktDato = parseDate(valuesByName["tilbakedatering.kontaktdato"]),
                begrunnelse = text("tilbakedatering.beskrivelse"),
            ),
            behandler = OcrParserBehandler(
                navn = text("bekreftelse.navn"),
                hpr = parseHpr(valuesByName["bekreftelse.hpr"]),
                telefon = parseTelefon(valuesByName["bekreftelse.telefon"]),
                adresse = text("bekreftelse.adresse"),
                behandletDato = parseDateIn(valuesByName["bekreftelse.signatur"]),
            ),
        )
    }

    /** Every non-blank [form] field, then every non-blank [title] field, each in page order. */
    private fun readingsOf(
        pages: List<Map<Felter, String>>,
        form: String,
        title: String,
        parse: (String?) -> OcrValue<String>?,
    ): List<OcrReading<String>> = listOf(form, title).flatMap { name ->
        pages.flatMapIndexed { index, fields ->
            fields.filterKeys { it.navn == name }.values.mapNotNull { text ->
                parse(text)?.let {
                    OcrReading(
                        name,
                        index + 1,
                        it
                    )
                }
            }
        }
    }

    /**
     * The periods in section 4, in form order. A period is left out only when it is blank: none of its text fields
     * hold text, and none of its boxes is [CheckboxState.CHECKED] or [CheckboxState.UNCERTAIN]. A box missing from
     * [checkboxes] doesn't count. A kept period has null for a blank date.
     */
    internal fun mapThreePage2019Activities(
        valuesByName: Map<String, String?>,
        checkboxes: Map<String, CheckboxState>,
    ): List<OcrParserAktivitet> {
        fun period(
            prefix: String,
            texts: List<String>,
            boxes: List<String>,
            aktivitet: (fom: OcrValue<LocalDate>?, tom: OcrValue<LocalDate>?) -> OcrParserAktivitet,
        ): OcrParserAktivitet? {
            val dates = listOf("$prefix.fom", "$prefix.tom")
            val blank = (dates + texts).all { valuesByName[it].isNullOrBlank() } &&
                    boxes.none { checkboxes[it] == CheckboxState.CHECKED || checkboxes[it] == CheckboxState.UNCERTAIN }
            return if (blank) null else aktivitet(parseDate(valuesByName[dates[0]]), parseDate(valuesByName[dates[1]]))
        }

        return listOfNotNull(
            period("aktivitet.avventende", listOf("aktivitet.innspillArbeidsgiver"), emptyList()) { fom, tom ->
                OcrParserAktivitet.Avventende(fom, tom, valuesByName["aktivitet.innspillArbeidsgiver"].trimmedOrNull())
            },
            period(
                "aktivitet.gradert",
                listOf("aktivitet.gradert.grad"),
                listOf("aktivitet.gradert.reisetilskudd")
            ) { fom, tom ->
                OcrParserAktivitet.Gradert(
                    fom,
                    tom,
                    parsePercent(valuesByName["aktivitet.gradert.grad"]),
                    checkboxes.state("aktivitet.gradert.reisetilskudd"),
                )
            },
            period(
                "aktivitet",
                emptyList(),
                listOf("aktivitet.medisinskeArsaker", "aktivitet.forholdArbeidsplassen")
            ) { fom, tom ->
                OcrParserAktivitet.IkkeMulig(
                    fom,
                    tom,
                    checkboxes.state("aktivitet.medisinskeArsaker"),
                    checkboxes.state("aktivitet.forholdArbeidsplassen"),
                )
            },
            period("aktivitet.behandlingsdager", listOf("aktivitet.behandlingsdager.dager"), emptyList()) { fom, tom ->
                OcrParserAktivitet.Behandlingsdager(
                    fom,
                    tom,
                    parseCount(valuesByName["aktivitet.behandlingsdager.dager"])
                )
            },
            period("aktivitet.reisetilskudd", emptyList(), emptyList()) { fom, tom ->
                OcrParserAktivitet.Reisetilskudd(
                    fom,
                    tom
                )
            },
        )
    }
}

/** Written in every page's title: collected per page as cross-check readings, never by name. */
private val TITLE_FIELDS = setOf("tittel.etternavn", "tittel.fornavn", "tittel.fnr")

private fun <T> byFieldName(pages: List<Map<Felter, T>>): Map<String, T> = buildMap {
    pages.forEach { fieldsOnPage ->
        fieldsOnPage.filterKeys { it.navn !in TITLE_FIELDS }.forEach { (field, value) ->
            require(!containsKey(field.navn)) { "Duplicate field name: ${field.navn}" }
            put(field.navn, value)
        }
    }
}

private fun Map<String, CheckboxState>.state(name: String): CheckboxState = this[name] ?: CheckboxState.UNCERTAIN
