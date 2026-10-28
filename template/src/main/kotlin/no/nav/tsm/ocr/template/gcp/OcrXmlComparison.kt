package no.nav.tsm.ocr.template.gcp

import no.nav.helse.papirsykemelding.SykemeldingerType
import no.nav.tsm.sykmelding.model.CheckboxState
import no.nav.tsm.sykmelding.model.OcrParserAktivitet
import no.nav.tsm.sykmelding.model.OcrParserAktivitetType
import no.nav.tsm.sykmelding.model.OcrParserSykmelding
import java.text.Normalizer
import java.time.LocalDate
import java.time.format.DateTimeFormatter

internal enum class ComparisonStatus {
    MATCH, NORMALIZED_MATCH, DIFFERENT, MISSING_OCR, OCR_ONLY, UNCERTAIN, XML_PROBLEM, NOT_COMPARABLE,
}

internal data class OcrXmlFieldComparison(
    val path: String,
    val ocr: String?,
    val xml: String?,
    val status: ComparisonStatus,
    val note: String? = null,
)

internal class OcrXmlComparison(val fields: List<OcrXmlFieldComparison>, val problems: List<String>) {
    fun describe(): String = buildString {
        appendLine("Mapped OCR / Iron Mountain XML comparison (whole document)")
        appendLine("OCR: final OcrParserSykmelding output. XML: Skanningmetadata as production reads it; as written where that differs.")
        appendLine("Agreement is not ground truth. Differences and OCR-only values require checking the PDF.")
        appendLine("Normalized: whitespace (also around punctuation like , and /, and in house numbers like 11 A), dates (16.09.26, 16.09.2026, ISO), numbers, booleans, the \".\" and case in diagnosis codes and leading zeros of HPR numbers, spaces in phone numbers.")
        appendLine("Empty pairs are omitted. Uncertain and uncomparable fields are not counted as matches.")
        appendLine()
        ComparisonStatus.entries.forEach { status -> appendLine("$status: ${fields.count { it.status == status }}") }
        appendLine()
        fields.forEach { field ->
            appendLine("${field.status}  ${field.path}")
            appendLine("  OCR: ${field.ocr?.replace("\n", "\n       ") ?: "(missing)"}")
            appendLine("  XML: ${field.xml?.replace("\n", "\n       ") ?: "(missing)"}")
            field.note?.let { appendLine("  Note: $it") }
        }
        if (problems.isNotEmpty()) {
            appendLine()
            appendLine("XML problems:")
            problems.forEach { appendLine("  $it") }
        }
    }
}

/**
 * Pairs every value of the papirsykemelding classes ([PapirsykmeldingXml.document]) with the mapped production value
 * it corresponds to. Where production reads a field differently from its text, the text is compared and shown.
 */
internal fun compareMappedOcrWithXml(mapped: OcrParserSykmelding, xml: PapirsykmeldingXml): OcrXmlComparison {
    val xmlFields = xml.fields.associateBy { comparisonPath(it.path) }
    val pairs = fieldPairs(mapped, xml.document.sykemeldinger)
    val paired = pairs.mapTo(HashSet()) { it.path }
    // Elements outside the schema have no getter, so no pair; they are still shown.
    val unpaired = xmlFields.keys.filter { it !in paired }
        .map { FieldPair(it, null, null, ValueKind.UNSUPPORTED, "Not in the papirsykemelding classes.") }
    val rows = (pairs + unpaired).mapNotNull { pair ->
        val field = xmlFields[pair.path]
        val ocr = pair.ocr?.takeIf { it.isNotBlank() }
        val original = (if (field?.note != null) field.text else pair.xml ?: field?.text)?.takeIf { it.isNotBlank() }
        val uncertain = pair.kind == ValueKind.BOOLEAN && ocr == CheckboxState.UNCERTAIN.name
        if (original == null && (ocr == null || uncertain) && field?.problem == null) return@mapNotNull null
        val status = when {
            pair.kind == ValueKind.UNSUPPORTED -> ComparisonStatus.NOT_COMPARABLE
            field?.problem != null || (field?.note != null &&
                normalize(field.value.orEmpty(), pair.kind) != normalize(field.text, pair.kind)) -> ComparisonStatus.XML_PROBLEM
            uncertain -> ComparisonStatus.UNCERTAIN
            ocr == null -> ComparisonStatus.MISSING_OCR
            original == null -> ComparisonStatus.OCR_ONLY
            ocr == original -> ComparisonStatus.MATCH
            normalize(ocr, pair.kind) == normalize(original, pair.kind) -> ComparisonStatus.NORMALIZED_MATCH
            else -> ComparisonStatus.DIFFERENT
        }
        OcrXmlFieldComparison(pair.path, ocr, original, status, field?.note ?: field?.problem ?: pair.note)
    }
    return OcrXmlComparison(rows, xml.problems)
}

// The XML parser numbers only repeated elements; a single diagnosis still means the first diagnosis.
private fun comparisonPath(path: String): String = path
    .replace(Regex("""(medisinskVurdering\.(?:hovedDiagnose|bidiagnose))(?![\w\[])"""), "$1[1]")

/**
 * [CATEGORY]: the XML may have only the first word of the form's text, e.g. "En" for "En arbeidsgiver".
 * [CODE]: a diagnosis code; the XML leaves out the `.`, e.g. "K011" for "K01.1".
 * [PHONE]: a phone number, compared by its digits: "12 34 56 78" is "12345678", "+47 12345678" too.
 * [HPR]: an HPR number, nine digits, often written without its leading zeros: "1234567" is "001234567".
 */
private enum class ValueKind { TEXT, DATE, NUMBER, BOOLEAN, CATEGORY, CODE, HPR, PHONE, UNSUPPORTED }

/** An XML value at its [path] and the OCR value it is compared with; either is null when absent. */
private data class FieldPair(val path: String, val ocr: String?, val xml: String?, val kind: ValueKind, val note: String? = null)

private const val NO_OCR_FIELD = "OcrParserSykmelding has no field for this."
private const val NOT_ON_TEMPLATES = "Not on the current templates."
private const val NO_XML_FIELD = "The XML has no field for this."

private fun fieldPairs(mapped: OcrParserSykmelding, xml: SykemeldingerType?): List<FieldPair> = buildList {
    fun pair(path: String, ocr: Any?, xml: Any?, kind: ValueKind = ValueKind.TEXT) {
        add(FieldPair(path, ocr?.toString(), xml?.toString(), kind))
    }
    fun checkbox(path: String, state: CheckboxState?, xml: Boolean?) = pair(path, when (state) {
        CheckboxState.CHECKED -> "true"
        CheckboxState.UNCHECKED -> "false"
        CheckboxState.UNCERTAIN -> CheckboxState.UNCERTAIN.name
        null -> null
    }, xml, ValueKind.BOOLEAN)
    fun xmlOnly(path: String, xml: Any?, note: String = NO_OCR_FIELD) =
        add(FieldPair(path, null, xml?.toString(), ValueKind.UNSUPPORTED, note))
    fun ocrOnly(path: String, ocr: Any?) = add(FieldPair("OCR.$path", ocr?.toString(), null, ValueKind.UNSUPPORTED, NO_XML_FIELD))
    fun period(name: String, ocr: OcrParserAktivitet?, fom: LocalDate?, tom: LocalDate?) {
        pair("aktivitet.$name.periodeFOMDato", ocr?.fom, fom, ValueKind.DATE)
        pair("aktivitet.$name.periodeTOMDato", ocr?.tom, tom, ValueKind.DATE)
    }

    pair("syketilfelleStartDato", mapped.legemeldtFravaerStart, xml?.syketilfelleStartDato, ValueKind.DATE)

    pair("pasient.fnr", mapped.pasient?.fnr, xml?.pasient?.fnr)
    mapped.pasient?.let {
        ocrOnly("pasient.navn", it.navn)
        ocrOnly("pasient.etternavn", it.etternavn)
        ocrOnly("pasient.fornavn", it.fornavn)
        ocrOnly("pasient.telefon", it.telefon)
        ocrOnly("pasient.adresse", it.adresse)
        ocrOnly("pasient.postnrSted", it.postnrSted)
        ocrOnly("pasient.fastlege", it.fastlege)
        ocrOnly("pasient.navKontor", it.navKontor)
    }

    val arbeidsgiver = xml?.arbeidsgiver
    pair("arbeidsgiver.harArbeidsgiver", mapped.arbeidsgiver?.harArbeidsgiver, arbeidsgiver?.harArbeidsgiver, ValueKind.CATEGORY)
    pair("arbeidsgiver.navnArbeidsgiver", mapped.arbeidsgiver?.navn, arbeidsgiver?.navnArbeidsgiver)
    pair("arbeidsgiver.yrkesbetegnelse", mapped.arbeidsgiver?.yrkeStilling, arbeidsgiver?.yrkesbetegnelse)
    pair("arbeidsgiver.stillingsprosent", mapped.arbeidsgiver?.stillingsprosent, arbeidsgiver?.stillingsprosent, ValueKind.NUMBER)

    val vurdering = xml?.medisinskVurdering
    val diagnose = mapped.diagnose
    // The OCR has one main diagnosis; further ones in the XML are reported as missing OCR.
    val hovedDiagnoser = vurdering?.hovedDiagnose.orEmpty()
    repeat(maxOf(1, hovedDiagnoser.size)) { index ->
        val path = "medisinskVurdering.hovedDiagnose[${index + 1}]"
        val ocr = diagnose?.takeIf { index == 0 }
        val hoved = hovedDiagnoser.getOrNull(index)
        pair("$path.diagnosekodeSystem", ocr?.hoveddiagnoseKodesystem, hoved?.diagnosekodeSystem)
        pair("$path.diagnosekode", ocr?.hoveddiagnoseKode, hoved?.diagnosekode, ValueKind.CODE)
        pair("$path.diagnose", ocr?.hoveddiagnoseTekst, hoved?.diagnose)
    }
    val bidiagnoser = vurdering?.bidiagnose.orEmpty()
    val ocrBidiagnoser = diagnose?.bidiagnoser.orEmpty()
    repeat(maxOf(1, bidiagnoser.size, ocrBidiagnoser.size)) { index ->
        val path = "medisinskVurdering.bidiagnose[${index + 1}]"
        val ocr = ocrBidiagnoser.getOrNull(index)
        val bi = bidiagnoser.getOrNull(index)
        pair("$path.diagnosekodeSystem", ocr?.kodesystem, bi?.diagnosekodeSystem)
        pair("$path.diagnosekode", ocr?.kode, bi?.diagnosekode, ValueKind.CODE)
        pair("$path.diagnose", ocr?.tekst, bi?.diagnose)
    }
    pair("medisinskVurdering.annenFraversArsak", diagnose?.lovfestetFraversgrunn, vurdering?.annenFraversArsak)
    pair("medisinskVurdering.fraversBeskrivelse", diagnose?.fraversgrunnBeskrivelse, vurdering?.fraversBeskrivelse)
    checkbox("medisinskVurdering.svangerskap", diagnose?.svangerskap, vurdering?.isSvangerskap)
    checkbox("medisinskVurdering.yrkesskade", diagnose?.yrkesskade, vurdering?.isYrkesskade)
    pair("medisinskVurdering.yrkesskadedato", diagnose?.skadedato, vurdering?.yrkesskadedato, ValueKind.DATE)
    checkbox("medisinskVurdering.skjermesForPasient", diagnose?.skjermingPaakrevet, vurdering?.isSkjermesForPasient)

    val activities = mapped.aktiviteter.orEmpty()
    val seenActivityTypes = HashSet<OcrParserAktivitetType>()
    activities.forEachIndexed { index, activity ->
        if (!seenActivityTypes.add(activity.type)) ocrOnly("aktiviteter[${index + 1}]", activity)
    }
    val avventende = activities.filterIsInstance<OcrParserAktivitet.Avventende>().firstOrNull()
    val gradert = activities.filterIsInstance<OcrParserAktivitet.Gradert>().firstOrNull()
    val ikkeMulig = activities.filterIsInstance<OcrParserAktivitet.IkkeMulig>().firstOrNull()
    val behandling = activities.filterIsInstance<OcrParserAktivitet.Behandlingsdager>().firstOrNull()
    val reise = activities.filterIsInstance<OcrParserAktivitet.Reisetilskudd>().firstOrNull()
    val aktivitet = xml?.aktivitet

    aktivitet?.avventendeSykmelding.let { period("avventendeSykmelding", avventende, it?.periodeFOMDato, it?.periodeTOMDato) }
    pair("aktivitet.innspillTilArbeidsgiver", avventende?.innspillTilArbeidsgiver, aktivitet?.innspillTilArbeidsgiver)

    val xmlGradert = aktivitet?.gradertSykmelding
    period("gradertSykmelding", gradert, xmlGradert?.periodeFOMDato, xmlGradert?.periodeTOMDato)
    pair("aktivitet.gradertSykmelding.sykmeldingsgrad", gradert?.grad, xmlGradert?.sykmeldingsgrad, ValueKind.NUMBER)
    checkbox("aktivitet.gradertSykmelding.reisetilskudd", gradert?.reisetilskudd, xmlGradert?.isReisetilskudd)

    val xmlIkkeMulig = aktivitet?.aktivitetIkkeMulig
    period("aktivitetIkkeMulig", ikkeMulig, xmlIkkeMulig?.periodeFOMDato, xmlIkkeMulig?.periodeTOMDato)
    checkbox("aktivitet.aktivitetIkkeMulig.medisinskeArsaker.medArsakerHindrer", ikkeMulig?.medisinskArsak,
        xmlIkkeMulig?.medisinskeArsaker?.isMedArsakerHindrer)
    pair("aktivitet.aktivitetIkkeMulig.medisinskeArsaker.medArsakerBesk", ikkeMulig?.medisinskArsakBeskrivelse,
        xmlIkkeMulig?.medisinskeArsaker?.medArsakerBesk)
    checkbox("aktivitet.aktivitetIkkeMulig.arbeidsplassen.arbeidsplassenHindrer", ikkeMulig?.arbeidsrelatertArsak,
        xmlIkkeMulig?.arbeidsplassen?.isArbeidsplassenHindrer)
    xmlOnly("aktivitet.aktivitetIkkeMulig.arbeidsplassen.arbeidsplassenBesk", xmlIkkeMulig?.arbeidsplassen?.arbeidsplassenBesk)
    ocrOnly("aktivitet.ikkeMulig.medisinskArsakType", ikkeMulig?.medisinskArsakType)

    val xmlBehandling = aktivitet?.behandlingsdager
    period("behandlingsdager", behandling, xmlBehandling?.periodeFOMDato, xmlBehandling?.periodeTOMDato)
    pair("aktivitet.behandlingsdager.antallBehandlingsdager", behandling?.antallBehandlingsdager,
        xmlBehandling?.antallBehandlingsdager, ValueKind.NUMBER)

    aktivitet?.reisetilskudd.let { period("reisetilskudd", reise, it?.periodeFOMDato, it?.periodeTOMDato) }

    val prognose = xml?.prognose
    checkbox("prognose.friskmelding.arbeidsforEtterEndtPeriode", mapped.prognose?.arbeidsforEtterPeriode,
        prognose?.friskmelding?.isArbeidsforEtterEndtPeriode)
    pair("prognose.friskmelding.beskrivHensynArbeidsplassen", mapped.prognose?.beskrivHensyn,
        prognose?.friskmelding?.beskrivHensynArbeidsplassen)
    prognose?.medArbeidsgiver.let {
        xmlOnly("prognose.medArbeidsgiver.tilbakeSammeArbeidsgiver", it?.isTilbakeSammeArbeidsgiver, NOT_ON_TEMPLATES)
        xmlOnly("prognose.medArbeidsgiver.tilbakeDato", it?.tilbakeDato, NOT_ON_TEMPLATES)
        xmlOnly("prognose.medArbeidsgiver.tilbakeAnnenArbeidsgiver", it?.isTilbakeAnnenArbeidsgiver, NOT_ON_TEMPLATES)
        xmlOnly("prognose.medArbeidsgiver.datoNyTilbakemelding", it?.datoNyTilbakemelding, NOT_ON_TEMPLATES)
    }
    prognose?.utenArbeidsgiver.let {
        xmlOnly("prognose.utenArbeidsgiver.tilbakeArbeid", it?.isTilbakeArbeid, NOT_ON_TEMPLATES)
        xmlOnly("prognose.utenArbeidsgiver.tilbakeDato", it?.tilbakeDato, NOT_ON_TEMPLATES)
        xmlOnly("prognose.utenArbeidsgiver.datoNyTilbakemelding", it?.datoNyTilbakemelding, NOT_ON_TEMPLATES)
    }

    xml?.utdypendeOpplysninger.let {
        xmlOnly("utdypendeOpplysninger.sykehistorie", it?.sykehistorie, NOT_ON_TEMPLATES)
        xmlOnly("utdypendeOpplysninger.arbeidsevne", it?.arbeidsevne, NOT_ON_TEMPLATES)
        xmlOnly("utdypendeOpplysninger.behandlingsresultat", it?.behandlingsresultat, NOT_ON_TEMPLATES)
        xmlOnly("utdypendeOpplysninger.planlagtBehandling", it?.planlagtBehandling, NOT_ON_TEMPLATES)
    }

    pair("tiltak.tiltakArbeidsplassen", mapped.bedreArbeidsevne?.tilleggsinformasjonForArbeidsgiver, xml?.tiltak?.tiltakArbeidsplassen)
    pair("tiltak.tiltakNAV", mapped.bedreArbeidsevne?.tiltakIRegiAvNav, xml?.tiltak?.tiltakNAV)
    pair("tiltak.andreTiltak", mapped.bedreArbeidsevne?.eventuelleAndreInnspillTilNav, xml?.tiltak?.andreTiltak)

    checkbox("meldingTilNAV.bistandNAVUmiddelbart", mapped.tilleggsinformasjon?.bistandNavOnskes, xml?.meldingTilNAV?.isBistandNAVUmiddelbart)
    pair("meldingTilNAV.beskrivBistandNAV", mapped.tilleggsinformasjon?.beskrivBistandOnskes, xml?.meldingTilNAV?.beskrivBistandNAV)
    pair("meldingTilArbeidsgiver", mapped.meldingTilArbeidsgiver?.innspillTilArbeidsgiver, xml?.meldingTilArbeidsgiver)

    pair("tilbakedatering.tilbakeDato", mapped.tilbakedatering?.kontaktDato, xml?.tilbakedatering?.tilbakeDato, ValueKind.DATE)
    pair("tilbakedatering.tilbakebegrunnelse", mapped.tilbakedatering?.begrunnelse, xml?.tilbakedatering?.tilbakebegrunnelse)

    pair("kontaktMedPasient.behandletDato", mapped.sykmelder?.dato, xml?.kontaktMedPasient?.behandletDato, ValueKind.DATE)
    pair("behandler.HPR", mapped.sykmelder?.hprNummer, xml?.behandler?.hpr, ValueKind.HPR)
    pair("behandler.telefon", mapped.sykmelder?.telefon, xml?.behandler?.telefon, ValueKind.PHONE)
    pair("behandler.adresse", mapped.sykmelder?.adresse, xml?.behandler?.adresse)
    ocrOnly("sykmelder.navn", mapped.sykmelder?.navn)
    ocrOnly("formType", mapped.formType)
    ocrOnly("formVersion", mapped.formVersion)
    ocrOnly("vurderingType", mapped.vurderingType?.name)
}

private val whitespace = Regex("\\s+")
/** A house number and its letter, written apart or together: "11 A" is "11A". */
private val houseLetter = Regex("""(\d) (\p{L})(?!\p{L})""")
private val phoneMarks = Regex("""[\s.\-()/]""")
/** A space either side of punctuation, which the XML and the OCR often differ in: "rygg, nakke", "a / b". */
private val punctuationSpacing = Regex(""" ?([,./;:()\-]) ?""")
private val diacritics = Regex("\\p{M}")

/** A date as written on paper: day, month, year, split by `.`, `/`, `-` or space, or not at all (`160926`). */
private val paperDate = Regex("""(\d{1,2})[./\- ]+(\d{1,2})[./\- ]+(\d{2}|\d{4})""")
private val paperDateDigits = Regex("""(\d{2})(\d{2})(\d{2}|\d{4})""")

/**
 * [text] as a date: ISO (`2026-09-16`) or as on paper (`16.09.2026`, `16.09.26`, `16/9-26`, `160926`). A two-digit
 * year is in this century. Dates that do not exist, like `31.02.2026`, are not dates.
 */
private fun parseDate(text: String): LocalDate? {
    runCatching { return LocalDate.parse(text, DateTimeFormatter.ISO_LOCAL_DATE) }
    val (day, month, year) = (paperDate.matchEntire(text) ?: paperDateDigits.matchEntire(text))?.destructured ?: return null
    val fullYear = if (year.length == 2) 2000 + year.toInt() else year.toInt()
    return try {
        LocalDate.of(fullYear, month.toInt(), day.toInt())
    } catch (_: java.time.DateTimeException) {
        null
    }
}

private fun normalize(text: String, kind: ValueKind): String {
    val trimmed = text.trim().replace(whitespace, " ")
    return when (kind) {
        ValueKind.DATE -> parseDate(trimmed)?.toString() ?: trimmed
        ValueKind.NUMBER -> trimmed.toBigDecimalOrNull()?.stripTrailingZeros()?.toPlainString() ?: trimmed
        ValueKind.BOOLEAN -> when (trimmed) { "1" -> "true"; "0" -> "false"; else -> trimmed }
        ValueKind.CATEGORY -> Normalizer.normalize(trimmed.substringBefore(' '), Normalizer.Form.NFD)
            .replace(diacritics, "").lowercase()
        ValueKind.CODE -> trimmed.replace(" ", "").replace(".", "").uppercase().trimEnd { !it.isLetterOrDigit() }
        ValueKind.PHONE -> trimmed.replace(phoneMarks, "").removePrefix("+47").removePrefix("0047")
            .takeIf { it.isNotEmpty() && it.all(Char::isDigit) } ?: trimmed
        ValueKind.HPR -> trimmed.replace(" ", "").takeIf { it.isNotEmpty() && it.all(Char::isDigit) }?.trimStart('0') ?: trimmed
        ValueKind.TEXT -> trimmed.replace(punctuationSpacing, "$1").replace(houseLetter, "$1$2")
        else -> trimmed
    }
}
