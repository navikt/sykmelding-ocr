package no.nav.tsm.ocr.template.gcp

import no.nav.helse.papirsykemelding.SykemeldingerType
import no.nav.tsm.sykmelding.model.CheckboxState
import no.nav.tsm.sykmelding.model.HarArbeidsgiver
import no.nav.tsm.sykmelding.model.OcrCrossChecked
import no.nav.tsm.sykmelding.model.OcrParserAktivitet
import no.nav.tsm.sykmelding.model.OcrParserDiagnose
import no.nav.tsm.sykmelding.model.OcrParserSykmelding
import no.nav.tsm.sykmelding.model.OcrValue
import no.nav.tsm.sykmelding.model.valueOrNull
import java.text.Normalizer
import java.time.LocalDate

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
        appendLine("Normalized: whitespace (also around punctuation like , and /, and in house numbers like 11 A), numbers, booleans, the \".\" and case in diagnosis codes and leading zeros of HPR numbers, spaces in phone numbers. OCR values are typed (dates as ISO, diagnosis codes as tsm-diagnoser has them); an unreadable one is UNCERTAIN, shown as read, with the reason in its note.")
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
        val uncertainBox = pair.kind == ValueKind.BOOLEAN && ocr == CheckboxState.UNCERTAIN.name
        if (original == null && (ocr == null || uncertainBox) && field?.problem == null) return@mapNotNull null
        val status = when {
            pair.kind == ValueKind.UNSUPPORTED -> ComparisonStatus.NOT_COMPARABLE
            field?.problem != null || (field?.note != null &&
                normalize(field.value.orEmpty(), pair.kind) != normalize(field.text, pair.kind)) -> ComparisonStatus.XML_PROBLEM
            uncertainBox || pair.unreadable -> ComparisonStatus.UNCERTAIN
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

/**
 * An XML value at its [path] and the OCR value it is compared with; either is null when absent. [unreadable]: the OCR
 * value is [OcrValue.Unreadable], so [ocr] is its text as read.
 */
private data class FieldPair(
    val path: String,
    val ocr: String?,
    val xml: String?,
    val kind: ValueKind,
    val note: String? = null,
    val unreadable: Boolean = false,
)

private const val NO_OCR_FIELD = "OcrParserSykmelding has no field for this."
private const val NOT_ON_TEMPLATES = "Not on the current templates."
private const val NO_XML_FIELD = "The XML has no field for this."

private fun fieldPairs(mapped: OcrParserSykmelding, xml: SykemeldingerType?): List<FieldPair> = buildList {
    fun pair(path: String, ocr: Any?, xml: Any?, kind: ValueKind = ValueKind.TEXT) {
        add(FieldPair(path, ocr?.toString(), xml?.toString(), kind))
    }
    /** A typed OCR value: shown as [shown] gives it, with its text in the note when that differs, or as read when unreadable. */
    fun <T> read(path: String, ocr: OcrValue<T>?, xml: Any?, kind: ValueKind = ValueKind.TEXT, shown: (T) -> String = { it.toString() }) {
        add(when (ocr) {
            is OcrValue.Read -> shown(ocr.value).let { value ->
                FieldPair(path, value, xml?.toString(), kind, note = ocr.text.takeIf { it != value }?.let { "OCR text: $it" })
            }
            is OcrValue.Unreadable -> FieldPair(path, ocr.text, xml?.toString(), kind, "Unreadable: ${ocr.reason}", unreadable = true)
            null -> FieldPair(path, null, xml?.toString(), kind)
        })
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
    /** An OCR-only value: an unreadable one shows its text as read and why. */
    fun ocrText(value: OcrValue<*>?): String? = when (value) {
        is OcrValue.Read -> value.value.toString()
        is OcrValue.Unreadable -> "${value.text} (unreadable: ${value.reason})"
        null -> null
    }
    fun crossChecked(path: String, value: OcrCrossChecked<String>) {
        ocrOnly("$path.readings", value.readings.joinToString("\n") { reading ->
            "${reading.value.text} (${reading.field} p${reading.page})" +
                ((reading.value as? OcrValue.Unreadable)?.let { ": ${it.reason}" } ?: "")
        }.ifEmpty { null })
        ocrOnly("$path.confirmations", value.confirmations.takeIf { value.readings.isNotEmpty() })
    }
    fun diagnose(path: String, ocr: OcrParserDiagnose?, system: String?, kode: String?, tekst: String?) {
        pair("$path.diagnosekodeSystem", ocr?.system, system)
        read("$path.diagnosekode", ocr?.diagnose, kode, ValueKind.CODE) { it.code }
        pair("$path.diagnose", ocr?.tekst, tekst)
        ocrOnly("$path.official", ocr?.diagnose.valueOrNull()?.let { "${it.system} ${it.code} ${it.text}" })
    }
    fun period(name: String, ocr: OcrParserAktivitet?, fom: LocalDate?, tom: LocalDate?) {
        read("aktivitet.$name.periodeFOMDato", ocr?.fom, fom, ValueKind.DATE)
        read("aktivitet.$name.periodeTOMDato", ocr?.tom, tom, ValueKind.DATE)
    }

    read("syketilfelleStartDato", mapped.syketilfelletStartDato, xml?.syketilfelleStartDato, ValueKind.DATE)

    val pasient = mapped.pasient
    read("pasient.fnr", pasient.fnr.value, xml?.pasient?.fnr)
    crossChecked("pasient.fnr", pasient.fnr)
    ocrOnly("pasient.etternavn", ocrText(pasient.etternavn.value))
    crossChecked("pasient.etternavn", pasient.etternavn)
    ocrOnly("pasient.fornavn", ocrText(pasient.fornavn.value))
    crossChecked("pasient.fornavn", pasient.fornavn)
    ocrOnly("pasient.telefon", ocrText(pasient.telefon))
    ocrOnly("pasient.navnFastlege", pasient.navnFastlege)
    ocrOnly("pasient.navKontor", pasient.navKontor)

    val arbeidsgiver = xml?.arbeidsgiver
    read("arbeidsgiver.harArbeidsgiver", mapped.arbeidsgiver.harArbeidsgiver, arbeidsgiver?.harArbeidsgiver, ValueKind.CATEGORY) {
        it.formText
    }
    pair("arbeidsgiver.navnArbeidsgiver", mapped.arbeidsgiver.navn, arbeidsgiver?.navnArbeidsgiver)
    pair("arbeidsgiver.yrkesbetegnelse", mapped.arbeidsgiver.yrkesbetegnelse, arbeidsgiver?.yrkesbetegnelse)
    read("arbeidsgiver.stillingsprosent", mapped.arbeidsgiver.stillingsprosent, arbeidsgiver?.stillingsprosent, ValueKind.NUMBER)

    val vurdering = xml?.medisinskVurdering
    val medisinsk = mapped.medisinskVurdering
    // The OCR has one main diagnosis; further ones in the XML are reported as missing OCR.
    val hovedDiagnoser = vurdering?.hovedDiagnose.orEmpty()
    repeat(maxOf(1, hovedDiagnoser.size)) { index ->
        val hoved = hovedDiagnoser.getOrNull(index)
        diagnose("medisinskVurdering.hovedDiagnose[${index + 1}]", medisinsk.hovedDiagnose?.takeIf { index == 0 },
            hoved?.diagnosekodeSystem, hoved?.diagnosekode, hoved?.diagnose)
    }
    val bidiagnoser = vurdering?.bidiagnose.orEmpty()
    val ocrBidiagnoser = medisinsk.biDiagnoser
    repeat(maxOf(1, bidiagnoser.size, ocrBidiagnoser.size)) { index ->
        val bi = bidiagnoser.getOrNull(index)
        diagnose("medisinskVurdering.bidiagnose[${index + 1}]", ocrBidiagnoser.getOrNull(index),
            bi?.diagnosekodeSystem, bi?.diagnosekode, bi?.diagnose)
    }
    pair("medisinskVurdering.annenFraversArsak", medisinsk.annenFraversArsak?.tekst, vurdering?.annenFraversArsak)
    pair("medisinskVurdering.fraversBeskrivelse", medisinsk.annenFraversArsak?.beskrivelse, vurdering?.fraversBeskrivelse)
    ocrOnly("medisinskVurdering.annenFraversArsak.arsak", medisinsk.annenFraversArsak?.arsak?.joinToString()?.ifEmpty { null })
    checkbox("medisinskVurdering.svangerskap", medisinsk.svangerskap, vurdering?.isSvangerskap)
    checkbox("medisinskVurdering.yrkesskade", medisinsk.yrkesskade, vurdering?.isYrkesskade)
    read("medisinskVurdering.yrkesskadedato", medisinsk.yrkesskadeDato, vurdering?.yrkesskadedato, ValueKind.DATE)
    checkbox("medisinskVurdering.skjermesForPasient", medisinsk.skjermetForPasient, vurdering?.isSkjermesForPasient)

    val activities = mapped.aktivitet
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
    read("aktivitet.gradertSykmelding.sykmeldingsgrad", gradert?.grad, xmlGradert?.sykmeldingsgrad, ValueKind.NUMBER)
    checkbox("aktivitet.gradertSykmelding.reisetilskudd", gradert?.reisetilskudd, xmlGradert?.isReisetilskudd)

    val xmlIkkeMulig = aktivitet?.aktivitetIkkeMulig
    period("aktivitetIkkeMulig", ikkeMulig, xmlIkkeMulig?.periodeFOMDato, xmlIkkeMulig?.periodeTOMDato)
    checkbox("aktivitet.aktivitetIkkeMulig.medisinskeArsaker.medArsakerHindrer", ikkeMulig?.medisinskArsak,
        xmlIkkeMulig?.medisinskeArsaker?.isMedArsakerHindrer)
    xmlOnly("aktivitet.aktivitetIkkeMulig.medisinskeArsaker.medArsakerBesk", xmlIkkeMulig?.medisinskeArsaker?.medArsakerBesk,
        NOT_ON_TEMPLATES)
    checkbox("aktivitet.aktivitetIkkeMulig.arbeidsplassen.arbeidsplassenHindrer", ikkeMulig?.arbeidsrelatertArsak,
        xmlIkkeMulig?.arbeidsplassen?.isArbeidsplassenHindrer)
    xmlOnly("aktivitet.aktivitetIkkeMulig.arbeidsplassen.arbeidsplassenBesk", xmlIkkeMulig?.arbeidsplassen?.arbeidsplassenBesk)

    val xmlBehandling = aktivitet?.behandlingsdager
    period("behandlingsdager", behandling, xmlBehandling?.periodeFOMDato, xmlBehandling?.periodeTOMDato)
    read("aktivitet.behandlingsdager.antallBehandlingsdager", behandling?.antallBehandlingsdager,
        xmlBehandling?.antallBehandlingsdager, ValueKind.NUMBER)

    aktivitet?.reisetilskudd.let { period("reisetilskudd", reise, it?.periodeFOMDato, it?.periodeTOMDato) }

    val prognose = xml?.prognose
    checkbox("prognose.friskmelding.arbeidsforEtterEndtPeriode", mapped.prognose.arbeidsforEtterPeriode,
        prognose?.friskmelding?.isArbeidsforEtterEndtPeriode)
    pair("prognose.friskmelding.beskrivHensynArbeidsplassen", mapped.prognose.hensynArbeidsplassen,
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

    pair("tiltak.tiltakArbeidsplassen", mapped.tiltak.tiltakArbeidsplassen, xml?.tiltak?.tiltakArbeidsplassen)
    pair("tiltak.tiltakNAV", mapped.tiltak.tiltakNav, xml?.tiltak?.tiltakNAV)
    pair("tiltak.andreTiltak", mapped.tiltak.andreTiltak, xml?.tiltak?.andreTiltak)

    checkbox("meldingTilNAV.bistandNAVUmiddelbart", mapped.bistandNav.bistandUmiddelbart, xml?.meldingTilNAV?.isBistandNAVUmiddelbart)
    pair("meldingTilNAV.beskrivBistandNAV", mapped.bistandNav.beskrivBistand, xml?.meldingTilNAV?.beskrivBistandNAV)
    pair("meldingTilArbeidsgiver", mapped.meldingTilArbeidsgiver, xml?.meldingTilArbeidsgiver)

    read("tilbakedatering.tilbakeDato", mapped.tilbakedatering.kontaktDato, xml?.tilbakedatering?.tilbakeDato, ValueKind.DATE)
    pair("tilbakedatering.tilbakebegrunnelse", mapped.tilbakedatering.begrunnelse, xml?.tilbakedatering?.tilbakebegrunnelse)

    read("kontaktMedPasient.behandletDato", mapped.behandler.behandletDato, xml?.kontaktMedPasient?.behandletDato, ValueKind.DATE)
    read("behandler.HPR", mapped.behandler.hpr, xml?.behandler?.hpr, ValueKind.HPR)
    read("behandler.telefon", mapped.behandler.telefon, xml?.behandler?.telefon, ValueKind.PHONE)
    pair("behandler.adresse", mapped.behandler.adresse, xml?.behandler?.adresse)
    ocrOnly("behandler.navn", mapped.behandler.navn)
    ocrOnly("templateVersion", mapped.templateVersion)
}

/** The category in words, whose first word the XML has, e.g. "En". */
private val HarArbeidsgiver.formText: String
    get() = when (this) {
        HarArbeidsgiver.EN_ARBEIDSGIVER -> "En arbeidsgiver"
        HarArbeidsgiver.FLERE_ARBEIDSGIVERE -> "Flere arbeidsgivere"
        HarArbeidsgiver.INGEN_ARBEIDSGIVER -> "Ingen arbeidsgiver"
    }

private val whitespace = Regex("\\s+")
/** A house number and its letter, written apart or together: "11 A" is "11A". */
private val houseLetter = Regex("""(\d) (\p{L})(?!\p{L})""")
private val phoneMarks = Regex("""[\s.\-()/]""")
/** A space either side of punctuation, which the XML and the OCR often differ in: "rygg, nakke", "a / b". */
private val punctuationSpacing = Regex(""" ?([,./;:()\-]) ?""")
private val diacritics = Regex("\\p{M}")

private fun normalize(text: String, kind: ValueKind): String {
    val trimmed = text.trim().replace(whitespace, " ")
    return when (kind) {
        ValueKind.DATE -> runCatching { LocalDate.parse(trimmed).toString() }.getOrDefault(trimmed)
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
