package no.nav.tsm.ocr.template.gcp

import no.nav.tsm.sykmelding.model.CheckboxState
import no.nav.tsm.sykmelding.model.OcrParserAktivitet
import no.nav.tsm.sykmelding.model.OcrParserSykmelding
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.format.ResolverStyle

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
        appendLine("OCR: final OcrParserSykmelding output. XML: original values as written. Fields are aligned by XML path.")
        appendLine("Agreement is not ground truth. Differences and OCR-only values require checking the PDF.")
        appendLine("Only whitespace, supported date formats, numeric quantities and boolean encodings are normalized.")
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

/** Compares mapped production values with the XML as written, not JAXB's potentially lossy interpretation. */
internal fun compareMappedOcrWithXml(mapped: OcrParserSykmelding, xml: PapirsykmeldingXml): OcrXmlComparison {
    val values = comparisonValues(mapped)
    val xmlFields = xml.fields.associateBy { comparisonPath(it.path) }
    val paths = (values.keys + xmlFields.keys).distinct()
    val rows = paths.mapNotNull { path ->
        val mappedValue = values[path] ?: values[path.replace(Regex("""bidiagnose\[\d+]"""), "bidiagnose[1]")]?.copy(text = null)
        val field = xmlFields[path]
        val ocr = mappedValue?.text?.takeIf { it.isNotBlank() }
        val original = field?.text?.takeIf { it.isNotBlank() }
        val uncertain = mappedValue?.kind == ValueKind.BOOLEAN && ocr == CheckboxState.UNCERTAIN.name
        if (original == null && (ocr == null || uncertain) && field?.problem == null) return@mapNotNull null
        val status = when {
            mappedValue == null || mappedValue.kind == ValueKind.UNSUPPORTED -> ComparisonStatus.NOT_COMPARABLE
            field?.problem != null || (field?.note != null &&
                normalize(field.value.orEmpty(), mappedValue.kind) != normalize(field.text, mappedValue.kind)) -> ComparisonStatus.XML_PROBLEM
            uncertain -> ComparisonStatus.UNCERTAIN
            ocr == null -> ComparisonStatus.MISSING_OCR
            original == null -> ComparisonStatus.OCR_ONLY
            ocr == original -> ComparisonStatus.MATCH
            normalize(ocr, mappedValue.kind) == normalize(original, mappedValue.kind) -> ComparisonStatus.NORMALIZED_MATCH
            else -> ComparisonStatus.DIFFERENT
        }
        OcrXmlFieldComparison(path, ocr, original, status, field?.note ?: field?.problem
            ?: if (status == ComparisonStatus.NOT_COMPARABLE) "No equivalent field in the other model is mapped for comparison." else null)
    }
    return OcrXmlComparison(rows, xml.problems)
}

// The XML parser numbers only repeated elements; a single diagnosis still means the first diagnosis.
private fun comparisonPath(path: String): String = path
    .replace(Regex("""(medisinskVurdering\.(?:hovedDiagnose|bidiagnose))(?![\w\[])"""), "$1[1]")

private enum class ValueKind { TEXT, DATE, NUMBER, BOOLEAN, UNSUPPORTED }
private data class ComparisonValue(val text: String?, val kind: ValueKind)

private fun comparisonValues(mapped: OcrParserSykmelding): Map<String, ComparisonValue> = buildMap {
    fun value(path: String, value: Any?, kind: ValueKind = ValueKind.TEXT) {
        put(path, ComparisonValue(value?.toString(), kind))
    }
    fun checkbox(path: String, state: CheckboxState?) = value(path, when (state) {
        CheckboxState.CHECKED -> "true"
        CheckboxState.UNCHECKED -> "false"
        CheckboxState.UNCERTAIN -> CheckboxState.UNCERTAIN.name
        null -> null
    }, ValueKind.BOOLEAN)
    fun unsupported(path: String, text: String?) = value("OCR.$path", text, ValueKind.UNSUPPORTED)

    value("syketilfelleStartDato", mapped.legemeldtFravaerStart, ValueKind.DATE)
    value("pasient.fnr", mapped.pasient?.fnr)
    mapped.pasient?.let {
        unsupported("pasient.navn", it.navn)
        unsupported("pasient.etternavn", it.etternavn)
        unsupported("pasient.fornavn", it.fornavn)
        unsupported("pasient.telefon", it.telefon)
        unsupported("pasient.adresse", it.adresse)
        unsupported("pasient.postnrSted", it.postnrSted)
        unsupported("pasient.fastlege", it.fastlege)
        unsupported("pasient.navKontor", it.navKontor)
    }
    value("arbeidsgiver.navnArbeidsgiver", mapped.arbeidsgiver?.navn)
    value("arbeidsgiver.yrkesbetegnelse", mapped.arbeidsgiver?.yrkeStilling)
    value("arbeidsgiver.stillingsprosent", mapped.arbeidsgiver?.stillingsprosent, ValueKind.NUMBER)
    // harArbeidsgiver is a category, not equivalent to the model's flereArbeidsgivere boolean.
    unsupported("arbeidsgiver.flereArbeidsgivere", mapped.arbeidsgiver?.flereArbeidsgivere?.toString())
    value("medisinskVurdering.hovedDiagnose[1].diagnosekodeSystem", mapped.diagnose?.hoveddiagnoseKodesystem)
    value("medisinskVurdering.hovedDiagnose[1].diagnosekode", mapped.diagnose?.hoveddiagnoseKode)
    value("medisinskVurdering.hovedDiagnose[1].diagnose", mapped.diagnose?.hoveddiagnoseTekst)
    // Include the first even when OCR has none, so a value in the XML is reported as missing OCR.
    val diagnoses = mapped.diagnose?.bidiagnoser.orEmpty()
    repeat(maxOf(1, diagnoses.size)) { index ->
        val diagnosis = diagnoses.getOrNull(index)
        val path = "medisinskVurdering.bidiagnose[${index + 1}]"
        value("$path.diagnosekodeSystem", diagnosis?.kodesystem)
        value("$path.diagnosekode", diagnosis?.kode)
        value("$path.diagnose", diagnosis?.tekst)
    }
    value("medisinskVurdering.annenFraversArsak", mapped.diagnose?.lovfestetFraversgrunn)
    value("medisinskVurdering.fraversBeskrivelse", mapped.diagnose?.fraversgrunnBeskrivelse)
    checkbox("medisinskVurdering.svangerskap", mapped.diagnose?.svangerskap)
    checkbox("medisinskVurdering.yrkesskade", mapped.diagnose?.yrkesskade)
    value("medisinskVurdering.yrkesskadedato", mapped.diagnose?.skadedato, ValueKind.DATE)
    checkbox("medisinskVurdering.skjermesForPasient", mapped.diagnose?.skjermingPaakrevet)

    val activities = mapped.aktiviteter.orEmpty()
    val seenActivityTypes = HashSet<no.nav.tsm.sykmelding.model.OcrParserAktivitetType>()
    activities.forEachIndexed { index, activity ->
        if (!seenActivityTypes.add(activity.type)) unsupported("aktiviteter[${index + 1}]", activity.toString())
    }
    val avventende = activities.filterIsInstance<OcrParserAktivitet.Avventende>().firstOrNull()
    val gradert = activities.filterIsInstance<OcrParserAktivitet.Gradert>().firstOrNull()
    val ikkeMulig = activities.filterIsInstance<OcrParserAktivitet.IkkeMulig>().firstOrNull()
    val behandling = activities.filterIsInstance<OcrParserAktivitet.Behandlingsdager>().firstOrNull()
    val reise = activities.filterIsInstance<OcrParserAktivitet.Reisetilskudd>().firstOrNull()
    for ((name, activity) in listOf(
        "avventendeSykmelding" to avventende, "gradertSykmelding" to gradert,
        "aktivitetIkkeMulig" to ikkeMulig, "behandlingsdager" to behandling, "reisetilskudd" to reise,
    )) {
        value("aktivitet.$name.periodeFOMDato", activity?.fom, ValueKind.DATE)
        value("aktivitet.$name.periodeTOMDato", activity?.tom, ValueKind.DATE)
    }
    value("aktivitet.innspillTilArbeidsgiver", avventende?.innspillTilArbeidsgiver)
    value("aktivitet.gradertSykmelding.sykmeldingsgrad", gradert?.grad, ValueKind.NUMBER)
    checkbox("aktivitet.gradertSykmelding.reisetilskudd", gradert?.reisetilskudd)
    checkbox("aktivitet.aktivitetIkkeMulig.medisinskeArsaker.medArsakerHindrer", ikkeMulig?.medisinskArsak)
    value("aktivitet.aktivitetIkkeMulig.medisinskeArsaker.medArsakerBesk", ikkeMulig?.medisinskArsakBeskrivelse)
    checkbox("aktivitet.aktivitetIkkeMulig.arbeidsplassen.arbeidsplassenHindrer", ikkeMulig?.arbeidsrelatertArsak)
    unsupported("aktivitet.ikkeMulig.medisinskArsakType", ikkeMulig?.medisinskArsakType)
    value("aktivitet.behandlingsdager.antallBehandlingsdager", behandling?.antallBehandlingsdager, ValueKind.NUMBER)
    checkbox("prognose.friskmelding.arbeidsforEtterEndtPeriode", mapped.prognose?.arbeidsforEtterPeriode)
    value("prognose.friskmelding.beskrivHensynArbeidsplassen", mapped.prognose?.beskrivHensyn)
    value("tiltak.tiltakArbeidsplassen", mapped.bedreArbeidsevne?.tilleggsinformasjonForArbeidsgiver)
    value("tiltak.tiltakNAV", mapped.bedreArbeidsevne?.tiltakIRegiAvNav)
    value("tiltak.andreTiltak", mapped.bedreArbeidsevne?.eventuelleAndreInnspillTilNav)
    checkbox("meldingTilNAV.bistandNAVUmiddelbart", mapped.tilleggsinformasjon?.bistandNavOnskes)
    value("meldingTilNAV.beskrivBistandNAV", mapped.tilleggsinformasjon?.beskrivBistandOnskes)
    value("meldingTilArbeidsgiver", mapped.meldingTilArbeidsgiver?.innspillTilArbeidsgiver)
    value("tilbakedatering.tilbakeDato", mapped.tilbakedatering?.kontaktDato, ValueKind.DATE)
    value("tilbakedatering.tilbakebegrunnelse", mapped.tilbakedatering?.begrunnelse)
    value("kontaktMedPasient.behandletDato", mapped.sykmelder?.dato, ValueKind.DATE)
    value("behandler.HPR", mapped.sykmelder?.hprNummer)
    value("behandler.telefon", mapped.sykmelder?.telefon)
    value("behandler.adresse", mapped.sykmelder?.adresse)
    unsupported("sykmelder.navn", mapped.sykmelder?.navn)
    unsupported("formType", mapped.formType)
    unsupported("formVersion", mapped.formVersion)
    unsupported("vurderingType", mapped.vurderingType?.name)
}

private val whitespace = Regex("\\s+")
private val paperDate = DateTimeFormatter.ofPattern("dd.MM.uuuu").withResolverStyle(ResolverStyle.STRICT)

private fun normalize(text: String, kind: ValueKind): String {
    val trimmed = text.trim().replace(whitespace, " ")
    return when (kind) {
        ValueKind.DATE -> try {
            LocalDate.parse(trimmed, if ('.' in trimmed) paperDate else DateTimeFormatter.ISO_LOCAL_DATE).toString()
        } catch (_: DateTimeParseException) {
            trimmed
        }
        ValueKind.NUMBER -> trimmed.toBigDecimalOrNull()?.stripTrailingZeros()?.toPlainString() ?: trimmed
        ValueKind.BOOLEAN -> when (trimmed) { "1" -> "true"; "0" -> "false"; else -> trimmed }
        else -> trimmed
    }
}
