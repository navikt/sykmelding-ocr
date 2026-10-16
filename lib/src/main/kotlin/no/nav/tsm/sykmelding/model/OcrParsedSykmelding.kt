package no.nav.tsm.sykmelding.model

data class OcrParserSykmelding(
    val formType: String,
    val formVersion: String?,
    val vurderingType: OcrParserVurderingType?,
    val legemeldtFravaerStart: String?,
    val pasient: OcrParserPasient,
    val arbeidsgiver: OcrParserArbeidsgiver,
    val diagnose: OcrParserDiagnose,
    val aktiviteter: List<OcrParserAktivitet>,
    val prognose: OcrParserPrognose,
    val tilleggsinformasjon: OcrParserTilleggsinformasjon,
    val tilbakedatering: OcrParserTilbakedatering,
    val sykmelder: OcrParserSykmelder,
    val meta: OcrParserSykmeldingMeta,
)


data class OcrParserSykmeldingMeta(
    val formId: String?,
    val formVersion: String?,
    val parserId: String,
    val warnings: List<String> = emptyList(),
)

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
    val annenFravarsarsak: String? = null,
    val svangerskap: Boolean = false,
    val yrkesskade: Boolean = false,
    val skadedato: String? = null,
    val skjermingPaakrevet: Boolean = false,
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
        val medisinskArsak: Boolean = false,
        val medisinskArsakType: String? = null,
        val medisinskArsakBeskrivelse: String? = null,
        val arbeidsrelatertArsak: Boolean = false,
    ) : OcrParserAktivitet {
        override val type: OcrParserAktivitetType
            get() = OcrParserAktivitetType.AKTIVITET_IKKE_MULIG
    }


    data class Gradert(
        override val fom: String? = null,
        override val tom: String? = null,
        val grad: Int? = null,
        val reisetilskudd: Boolean = false,
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

data class OcrParserPrognose(val arbeidsforEtterPeriode: Boolean = false)

data class OcrParserTilleggsinformasjon(val bistandNavOnskes: Boolean = false)

data class OcrParserTilbakedatering(
    val kontaktDato: String? = null,
    val beskrivelse: String? = null,
)

data class OcrParserSykmelder(
    val dato: String? = null,
    val navn: String? = null,
    val hprNummer: String? = null,
    val adresse: String? = null,
    val telefon: String? = null,
)
