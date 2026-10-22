package no.nav.tsm.sykmelding.model

data class OcrParserSykmelding(
    val formType: String?,
    val formVersion: String?,
    val vurderingType: OcrParserVurderingType?,
    val legemeldtFravaerStart: String?,
    val pasient: OcrParserPasient?,
    val arbeidsgiver: OcrParserArbeidsgiver?,
    val diagnose: OcrParserDiagnose?,
    val aktiviteter: List<OcrParserAktivitet>?,
    val prognose: OcrParserPrognose?,
    val bedreArbeidsevne: OcrParserBedreArbeidsevne?,
    val meldingTilArbeidsgiver: OcrParsedMeldingTilArbeidsgiver?,
    val tilleggsinformasjon: OcrParserTilleggsinformasjon?,
    val tilbakedatering: OcrParserTilbakedatering?,
    val sykmelder: OcrParserSykmelder?,
    val meta: OcrParserSykmeldingMeta?,
)


data class OcrParserSykmeldingMeta(
    val formId: String?,
    val formVersion: String?,
    val parserId: String,
    val warnings: List<String> = emptyList(),
)

/**
 * A checkbox as read from the form. [UNCERTAIN] is a faint or partial mark, or a box that could not be read: the
 * consumer must decide what it means (e.g. manual review). It is never turned into [UNCHECKED] by the lib.
 */
enum class CheckboxState { CHECKED, UNCHECKED, UNCERTAIN }

enum class OcrParserVurderingType {
    FORSTE_VURDERING,
    PAFOLGENDE_VURDERING,
    PAFOLGENDE_ANNEN_SYKMELDER,
}

enum class OcrParserAktivitetType {
    AKTIVITET_IKKE_MULIG,
    GRADERT,
    AVVENTENDE,
    BEHANDLINGSDAGER,
    REISETILSKUDD,
}

data class OcrParserPasient(
    val navn: String? = null,
    val etternavn: String? = null,
    val fornavn: String? = null,
    val fnr: String? = null,
    val telefon: String? = null,
    val adresse: String? = null,
    val postnrSted: String? = null,
    val fastlege: String? = null,
    val navKontor: String? = null,
)

data class OcrParserArbeidsgiver(
    val navn: String? = null,
    val yrkeStilling: String? = null,
    val stillingsprosent: Int? = null,
    val flereArbeidsgivere: Boolean = false,
)

data class OcrParserDiagnose(
    val hoveddiagnoseKodesystem: String? = null,
    val hoveddiagnoseKode: String? = null,
    val hoveddiagnoseTekst: String? = null,
    val bidiagnoser: List<Bidiagnose> = emptyList(),
    val lovfestetFraversgrunn: String? = null,
    val fraversgrunnBeskrivelse: String? = null,
    val svangerskap: CheckboxState = CheckboxState.UNCERTAIN,
    val yrkesskade: CheckboxState = CheckboxState.UNCERTAIN,
    val skadedato: String? = null,
    val skjermingPaakrevet: CheckboxState = CheckboxState.UNCERTAIN,
)

data class Bidiagnose(
    val kodesystem: String? = null,
    val kode: String? = null,
    val tekst: String? = null,
)

sealed interface OcrParserAktivitet {
    val fom: String?
    val tom: String?
    val type: OcrParserAktivitetType

    data class IkkeMulig(
        override val fom: String? = null,
        override val tom: String? = null,
        val medisinskArsak: CheckboxState = CheckboxState.UNCERTAIN,
        val medisinskArsakType: String? = null,
        val medisinskArsakBeskrivelse: String? = null,
        val arbeidsrelatertArsak: CheckboxState = CheckboxState.UNCERTAIN,
    ) : OcrParserAktivitet {
        override val type: OcrParserAktivitetType
            get() = OcrParserAktivitetType.AKTIVITET_IKKE_MULIG
    }


    data class Gradert(
        override val fom: String? = null,
        override val tom: String? = null,
        val grad: Int? = null,
        val reisetilskudd: CheckboxState = CheckboxState.UNCERTAIN,
    ) : OcrParserAktivitet {
        override val type: OcrParserAktivitetType
            get() = OcrParserAktivitetType.GRADERT
    }

    data class Avventende(
        override val fom: String? = null,
        override val tom: String? = null,
        val innspillTilArbeidsgiver: String? = null,
    ) : OcrParserAktivitet {
        override val type: OcrParserAktivitetType
            get() = OcrParserAktivitetType.AVVENTENDE
    }

    enum class Arbeidsgiver(
        val value: String
    ) {
        EN_ARBEIDSGIVER("En arbeisgiver"),
        FLERE_ARBEIDSGIVERE("Flere arbeidsgivere"), // må sjekke
        INGEN_ARBEIDSGIVERE("Ingen arbeidsgiver"), // må sjekke
    }

    data class Behandlingsdager(
        override val fom: String? = null,
        override val tom: String? = null,
        val antallBehandlingsdager: Int? = null,
    ) : OcrParserAktivitet {
        override val type: OcrParserAktivitetType
            get() = OcrParserAktivitetType.BEHANDLINGSDAGER
    }

    data class Reisetilskudd(override val fom: String? = null, override val tom: String? = null) :
        OcrParserAktivitet {
        override val type: OcrParserAktivitetType
            get() = OcrParserAktivitetType.REISETILSKUDD
    }
}

data class OcrParserPrognose(
    val arbeidsforEtterPeriode: CheckboxState = CheckboxState.UNCERTAIN,
    val beskrivHensyn : String? = null,
    )

data class OcrParserBedreArbeidsevne (
    val tilleggsinformasjonForArbeidsgiver: String? = null,
    val tiltakIRegiAvNav : String? = null,
    val eventuelleAndreInnspillTilNav : String? = null,
)

data class OcrParserTilleggsinformasjon(
    val bistandNavOnskes: CheckboxState = CheckboxState.UNCERTAIN,
    val beskrivBistandOnskes: String? = null,
)

data class OcrParsedMeldingTilArbeidsgiver(
    val innspillTilArbeidsgiver: String?,
)

data class OcrParserTilbakedatering(
    val kontaktDato: String? = null,
    val begrunnelse: String? = null,
)

data class OcrParserSykmelder(
    val dato: String? = null,
    val navn: String? = null,
    val hprNummer: String? = null,
    val adresse: String? = null,
    val telefon: String? = null,
)
