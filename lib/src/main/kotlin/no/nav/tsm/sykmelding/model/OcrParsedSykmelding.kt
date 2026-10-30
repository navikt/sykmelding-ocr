package no.nav.tsm.sykmelding.model

import no.nav.tsm.diagnoser.Diagnose
import java.time.LocalDate

data class OcrParserSykmelding(
    val templateVersion: String,
    val syketilfelletStartDato: OcrValue<LocalDate>?,
    val pasient: OcrParserPasient,
    val arbeidsgiver: OcrParserArbeidsgiver,
    val medisinskVurdering: OcrParserMedisinskVurdering,
    val aktivitet: List<OcrParserAktivitet>,
    val prognose: OcrParserPrognose,
    val tiltak: OcrParserTiltak,
    val bistandNav: OcrParserBistandNav,
    val meldingTilArbeidsgiver: String?,
    val tilbakedatering: OcrParserTilbakedatering,
    val behandler: OcrParserBehandler,
)

enum class CheckboxState { CHECKED, UNCHECKED, UNCERTAIN }

sealed interface OcrValue<out T> {
    val text: String

    data class Read<out T>(val value: T, override val text: String) : OcrValue<T>
    data class Unreadable(override val text: String, val reason: String) : OcrValue<Nothing>
}

fun <T> OcrValue<T>?.valueOrNull(): T? = when (this) {
    is OcrValue.Read -> value
    else -> null
}

data class OcrReading<out T>(val field: String, val page: Int, val value: OcrValue<T>)

data class OcrCrossChecked<out T>(val value: OcrValue<T>?, val readings: List<OcrReading<T>>, val confirmations: Int)

enum class HarArbeidsgiver { EN_ARBEIDSGIVER, FLERE_ARBEIDSGIVERE, INGEN_ARBEIDSGIVER }

enum class LovfestetFravaersgrunn(val bokstav: Char) {
    GODKJENT_HELSEINSTITUSJON('a'),
    BEHANDLING_FORHINDRER_ARBEID('b'),
    ARBEIDSRETTET_TILTAK('c'),
    MOTTAR_TILSKUDD_GRUNNET_HELSETILSTAND('d'),
    NODVENDIG_KONTROLLUNDENRSOKELSE('e'),
    SMITTEFARE('f'),
    ABORT('g'),
    UFOR_GRUNNET_BARNLOSHET('h'),
    DONOR('i'),
    BEHANDLING_STERILISERING('j');

    companion object {
        /**
         * The grunn whose letter [tekst] starts with when a `)` follows it, e.g. "b) Under behandling" or "b )"; empty
         * otherwise. The field is too small for the whole text of the grunn, so the letter is what tells them apart.
         */
        fun fraTekst(tekst: String): List<LovfestetFravaersgrunn> {
            val trimmed = tekst.trim()
            if (trimmed.length < 2 || !trimmed.substring(1).trimStart().startsWith(')')) return emptyList()
            val bokstav = trimmed[0].lowercaseChar()
            return entries.filter { it.bokstav == bokstav }
        }
    }
}

data class OcrParserPasient(
    val fnr: OcrCrossChecked<String>,
    val fornavn: OcrCrossChecked<String>,
    val etternavn: OcrCrossChecked<String>,
    val telefon: OcrValue<String>?,
    val navnFastlege: String?,
    val navKontor: String?,
)

data class OcrParserArbeidsgiver(
    val harArbeidsgiver: OcrValue<HarArbeidsgiver>?,
    val navn: String?,
    val yrkesbetegnelse: String?,
    val stillingsprosent: OcrValue<Int>?,
)

data class OcrParserMedisinskVurdering(
    val hovedDiagnose: OcrParserDiagnose?,
    val biDiagnoser: List<OcrParserDiagnose>,
    val annenFraversArsak: OcrParserAnnenFraversArsak?,
    val svangerskap: CheckboxState,
    val yrkesskade: CheckboxState,
    val yrkesskadeDato: OcrValue<LocalDate>?,
    val skjermetForPasient: CheckboxState,
)

data class OcrParserDiagnose(val diagnose: OcrValue<Diagnose>?, val system: String?, val tekst: String?)

data class OcrParserAnnenFraversArsak(val arsak: List<LovfestetFravaersgrunn>, val tekst: String?, val beskrivelse: String?)

sealed interface OcrParserAktivitet {
    val fom: OcrValue<LocalDate>?
    val tom: OcrValue<LocalDate>?

    data class Avventende(
        override val fom: OcrValue<LocalDate>?,
        override val tom: OcrValue<LocalDate>?,
        val innspillTilArbeidsgiver: String?,
    ) : OcrParserAktivitet

    data class Gradert(
        override val fom: OcrValue<LocalDate>?,
        override val tom: OcrValue<LocalDate>?,
        val grad: OcrValue<Int>?,
        val reisetilskudd: CheckboxState,
    ) : OcrParserAktivitet

    data class IkkeMulig(
        override val fom: OcrValue<LocalDate>?,
        override val tom: OcrValue<LocalDate>?,
        val medisinskArsak: CheckboxState,
        val arbeidsrelatertArsak: CheckboxState,
    ) : OcrParserAktivitet

    data class Behandlingsdager(
        override val fom: OcrValue<LocalDate>?,
        override val tom: OcrValue<LocalDate>?,
        val antallBehandlingsdager: OcrValue<Int>?,
    ) : OcrParserAktivitet

    data class Reisetilskudd(
        override val fom: OcrValue<LocalDate>?,
        override val tom: OcrValue<LocalDate>?,
    ) : OcrParserAktivitet
}

data class OcrParserPrognose(val arbeidsforEtterPeriode: CheckboxState, val hensynArbeidsplassen: String?)

data class OcrParserTiltak(val tiltakArbeidsplassen: String?, val tiltakNav: String?, val andreTiltak: String?)

data class OcrParserBistandNav(val bistandUmiddelbart: CheckboxState, val beskrivBistand: String?)

data class OcrParserTilbakedatering(val kontaktDato: OcrValue<LocalDate>?, val begrunnelse: String?)

data class OcrParserBehandler(
    val navn: String?,
    val hpr: OcrValue<String>?,
    val telefon: OcrValue<String>?,
    val adresse: String?,
    val behandletDato: OcrValue<LocalDate>?,
)
