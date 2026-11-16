package no.nav.tsm.sykmelding.ocr

import no.nav.tsm.diagnoser.Diagnose
import no.nav.tsm.diagnoser.DiagnoseType
import no.nav.tsm.sykmelding.model.ArbeidsrelatertArsakType
import no.nav.tsm.sykmelding.model.CheckboxState
import no.nav.tsm.sykmelding.model.HarArbeidsgiver
import no.nav.tsm.sykmelding.model.MedisinskArsakType
import no.nav.tsm.sykmelding.model.OcrCrossChecked
import no.nav.tsm.sykmelding.model.OcrParserAktivitet
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
import no.nav.tsm.sykmelding.model.valueOrNull
import no.nav.tsm.sykmelding.template.TextTemplates
import org.apache.pdfbox.Loader
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject
import org.junit.jupiter.api.assertAll
import java.io.File
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt
import kotlin.test.assertEquals
import kotlin.test.assertIs

internal fun read(text: String) = OcrValue.Read(text, text)

internal fun date(text: String) = OcrValue.Read(LocalDate.parse(text, DateTimeFormatter.ofPattern("dd.MM.yyyy")), text)

private const val FNR = "22829297276"

internal val default = OcrParserSykmelding(
    templateVersion = "1pager",
    syketilfelletStartDato = date("01.01.2026"),
    pasient = OcrParserPasient(
        fnr = OcrCrossChecked(read(FNR), listOf(OcrReading("header.fnr", 1, read(FNR)), OcrReading("pasient.fnr", 1, read(FNR))), 2),
        fornavn = OcrCrossChecked(read("Test"), emptyList(), 0),
        etternavn = OcrCrossChecked(read("Testesen"), emptyList(), 0),
        telefon = read("12345678"),
        navnFastlege = "Fastlege Lege",
        navKontor = "NAV Testby",
    ),
    arbeidsgiver = OcrParserArbeidsgiver(
        harArbeidsgiver = OcrValue.Read(HarArbeidsgiver.EN_ARBEIDSGIVER, "En arbeidsgiver"),
        navn = "Arbeidsgiver AS",
        yrkesbetegnelse = "Medarbeider",
        stillingsprosent = OcrValue.Read(100, "100"),
    ),
    medisinskVurdering = OcrParserMedisinskVurdering(
        hovedDiagnose = OcrParserDiagnose(
            OcrValue.Read(Diagnose(DiagnoseType.ICPC2, "R74", "Akutt øvre luftveisinfeksjon"), "R74"), "ICPC-2", "Akutt øvre luftveisinfeksjon",
        ),
        biDiagnoser = listOf(
            OcrParserDiagnose(OcrValue.Read(Diagnose(DiagnoseType.ICPC2, "L84", "Ryggsyndrom uten smerteutstråling"), "L84"), "ICPC-2", "Ryggsyndrom"),
        ),
        annenFraversArsak = null,
        svangerskap = CheckboxState.UNCHECKED,
        yrkesskade = CheckboxState.UNCHECKED,
        yrkesskadeDato = date("20.12.2025"),
        skjermetForPasient = CheckboxState.UNCHECKED,
    ),
    aktivitet = listOf(
        OcrParserAktivitet.Avventende(date("02.01.2026"), date("08.01.2026"), "Tilrettelegging på jobb"),
        OcrParserAktivitet.Gradert(date("09.01.2026"), date("15.01.2026"), OcrValue.Read(50, "50"), CheckboxState.UNCHECKED),
        OcrParserAktivitet.IkkeMulig(
            fom = date("16.01.2026"),
            tom = date("29.01.2026"),
            medisinskArsak = CheckboxState.CHECKED,
            arbeidsrelatertArsak = CheckboxState.UNCHECKED,
            medisinskArsakGrunn = OcrValue.Read(MedisinskArsakType.TILSTAND_HINDRER_AKTIVITET, "Helsetilstanden hindrer aktivitet"),
            medisinskArsakBegrunn = "Medisinsk beskrivelse",
            arbeidsrelatertArsakGrunn = OcrValue.Read(ArbeidsrelatertArsakType.MANGLENDE_TILRETTELEGGING, "Manglende tilrettelegging"),
            arbeidsrelatertArsakBegrunn = "Forhold på arbeidsplassen",
        ),
        OcrParserAktivitet.Behandlingsdager(date("30.01.2026"), date("05.02.2026"), OcrValue.Read(3, "3")),
        OcrParserAktivitet.Reisetilskudd(date("06.02.2026"), date("12.02.2026")),
    ),
    prognose = OcrParserPrognose(arbeidsforEtterPeriode = CheckboxState.UNCHECKED, hensynArbeidsplassen = "Hensyn på arbeidsplassen"),
    tiltak = OcrParserTiltak(tiltakArbeidsplassen = "Tilpasset arbeidstid", tiltakNav = "Tiltak i regi av NAV", andreTiltak = "Andre innspill til NAV"),
    bistandNav = OcrParserBistandNav(bistandUmiddelbart = CheckboxState.UNCHECKED, beskrivBistand = "Begrunnelse til NAV"),
    meldingTilArbeidsgiver = "Innspill til arbeidsgiver",
    tilbakedatering = OcrParserTilbakedatering(kontaktDato = date("03.01.2026"), begrunnelse = "Pasienten var innlagt"),
    behandler = OcrParserBehandler(
        navn = "Sykmelders Navn",
        hpr = read("1234567"),
        telefon = read("87654321"),
        adresse = "Gate 1, 0123 Testby",
        behandletDato = date("05.01.2026"),
    ),
)

internal fun OcrParserSykmelding.hpr(number: String) = copy(behandler = behandler.copy(hpr = read(number)))

internal fun OcrParserSykmelding.only(fields: Set<String>): OcrParserSykmelding = with(Fields(fields)) {
    val fullName = "${pasient.etternavn.value.valueOrNull()}, ${pasient.fornavn.value.valueOrNull()}"
    fun name(field: String, name: OcrCrossChecked<String>, missing: String): OcrCrossChecked<String> = when {
        field in fields -> name
        "1.1" in fields -> name.copy(value = name.value.valueOrNull()?.let { OcrValue.Read(it, fullName) })
        else -> OcrCrossChecked(OcrValue.Unreadable("", missing), emptyList(), 0)
    }
    copy(
        syketilfelletStartDato = syketilfelletStartDato.on("0"),
        pasient = OcrParserPasient(
            fnr = crossCheckFnr(pasient.fnr.readings.filter { it.field == "header.fnr" || "1.2" in fields }),
            fornavn = name("1.1.2", pasient.fornavn, "Could not find first name"),
            etternavn = name("1.1.1", pasient.etternavn, "Could not find last name"),
            telefon = pasient.telefon.on("1.3"),
            navnFastlege = pasient.navnFastlege.on("1.4"),
            navKontor = pasient.navKontor.on("1.5"),
        ),
        arbeidsgiver = OcrParserArbeidsgiver(
            harArbeidsgiver = arbeidsgiver.harArbeidsgiver.on("2.1"),
            navn = arbeidsgiver.navn.on("2.2"),
            yrkesbetegnelse = arbeidsgiver.yrkesbetegnelse.on("2.3"),
            stillingsprosent = arbeidsgiver.stillingsprosent.on("2.4"),
        ),
        medisinskVurdering = medisinskVurdering.copy(
            hovedDiagnose = medisinskVurdering.hovedDiagnose.on("3.1.2")?.let { it.copy(system = it.system.on("3.1.1"), tekst = it.tekst.on("3.1.3")) },
            biDiagnoser = medisinskVurdering.biDiagnoser.on("3.2.2").orEmpty(),
            svangerskap = medisinskVurdering.svangerskap.box("3.4"),
            yrkesskade = medisinskVurdering.yrkesskade.box("3.5"),
            yrkesskadeDato = medisinskVurdering.yrkesskadeDato.on("3.6"),
            skjermetForPasient = medisinskVurdering.skjermetForPasient.box("3.7"),
        ),
        aktivitet = aktivitet.mapNotNull { only(it) },
        prognose = OcrParserPrognose(prognose.arbeidsforEtterPeriode.box("5.1"), prognose.hensynArbeidsplassen.on("5.1.1")),
        tiltak = OcrParserTiltak(tiltak.tiltakArbeidsplassen.on("7.1"), tiltak.tiltakNav.on("7.2"), tiltak.andreTiltak.on("7.3")),
        bistandNav = OcrParserBistandNav(bistandNav.bistandUmiddelbart.box("8.1"), bistandNav.beskrivBistand.on("8.2")),
        meldingTilArbeidsgiver = meldingTilArbeidsgiver.on("9.1"),
        tilbakedatering = OcrParserTilbakedatering(tilbakedatering.kontaktDato.on("11.1"), tilbakedatering.begrunnelse.on("11.2")),
        behandler = OcrParserBehandler(
            navn = behandler.navn.on("12.2"),
            hpr = behandler.hpr.on("12.4"),
            telefon = behandler.telefon.on("12.5"),
            adresse = behandler.adresse.on("12.3", "12.6"),
            behandletDato = behandler.behandletDato.on("12.1"),
        ),
    )
}

private class Fields(val fields: Set<String>) {
    fun <T : Any> T?.on(vararg numbers: String): T? = takeIf { numbers.any(fields::contains) }

    fun CheckboxState.box(number: String): CheckboxState = if (number in fields) this else CheckboxState.UNCHECKED

    fun only(aktivitet: OcrParserAktivitet): OcrParserAktivitet? = when (aktivitet) {
        is OcrParserAktivitet.Avventende -> aktivitet.on("4.1")?.copy(
            fom = aktivitet.fom.on("4.1.1"), tom = aktivitet.tom.on("4.1.2"), innspillTilArbeidsgiver = aktivitet.innspillTilArbeidsgiver.on("4.1.3"),
        )
        is OcrParserAktivitet.Gradert -> aktivitet.on("4.2")?.copy(
            fom = aktivitet.fom.on("4.2.1"), tom = aktivitet.tom.on("4.2.2"), grad = aktivitet.grad.on("4.2.3"), reisetilskudd = aktivitet.reisetilskudd.box("4.2.4"),
        )
        is OcrParserAktivitet.IkkeMulig -> aktivitet.on("4.3")?.copy(
            fom = aktivitet.fom.on("4.3.1"),
            tom = aktivitet.tom.on("4.3.2"),
            medisinskArsak = aktivitet.medisinskArsak.box("4.3.3"),
            arbeidsrelatertArsak = aktivitet.arbeidsrelatertArsak.box("4.3.4"),
            medisinskArsakGrunn = aktivitet.medisinskArsakGrunn.on("4.3.3.1"),
            medisinskArsakBegrunn = aktivitet.medisinskArsakBegrunn.on("4.3.3.2"),
            arbeidsrelatertArsakGrunn = aktivitet.arbeidsrelatertArsakGrunn.on("4.3.4.1"),
            arbeidsrelatertArsakBegrunn = aktivitet.arbeidsrelatertArsakBegrunn.on("4.3.4.2"),
        )
        is OcrParserAktivitet.Behandlingsdager -> aktivitet.on("4.4")?.copy(
            fom = aktivitet.fom.on("4.4.1"), tom = aktivitet.tom.on("4.4.2"), antallBehandlingsdager = aktivitet.antallBehandlingsdager.on("4.4.3"),
        )
        is OcrParserAktivitet.Reisetilskudd -> aktivitet.on("4.5")?.copy(fom = aktivitet.fom.on("4.5.1"), tom = aktivitet.tom.on("4.5.2"))
    }
}

internal fun assertPage(name: String, expected: OcrParserSykmelding = default, ignored: List<String?> = emptyList()) {
    val read = Loader.loadPDF(File("src/test/resources/ocr-testdata/$name.pdf")).use { document ->
        val page = document.getPage(0)
        val image = page.resources.xObjectNames.firstNotNullOf { page.resources.getXObject(it) as? PDImageXObject }.image
        TextPage(image, (image.width * 72 / page.mediaBox.width).roundToInt()).read(template)
    }
    val sykmelding = assertIs<OcrResult.Parsed>(read.result, "$name is read as the template").sykmelding
    assertAll(
        name,
        { assertEquals(ignored, read.ignored.map { it.number }, "rows that match no field") },
        { assertSykmelding(expected.only(read.fields()), sykmelding) },
    )
}

internal fun CustomTextTemplateParser.Page.fields(): Set<String> = rows.keys.map { it.number }.toSet() + values.map { it.field }

internal fun assertSykmelding(expected: OcrParserSykmelding, actual: OcrParserSykmelding) {
    val wanted = expected.withoutSentences()
    val found = actual.withoutSentences()
    assertAll(
        { assertEquals(wanted.templateVersion, found.templateVersion, "templateVersion") },
        { assertEquals(wanted.syketilfelletStartDato, found.syketilfelletStartDato, "syketilfelletStartDato") },
        { assertEquals(wanted.pasient, found.pasient, "pasient") },
        { assertEquals(wanted.arbeidsgiver, found.arbeidsgiver, "arbeidsgiver") },
        { assertEquals(wanted.medisinskVurdering, found.medisinskVurdering, "medisinskVurdering") },
        { assertEquals(wanted.aktivitet, found.aktivitet, "aktivitet") },
        { assertEquals(wanted.prognose, found.prognose, "prognose") },
        { assertEquals(wanted.tiltak, found.tiltak, "tiltak") },
        { assertEquals(wanted.bistandNav, found.bistandNav, "bistandNav") },
        { assertEquals(wanted.meldingTilArbeidsgiver, found.meldingTilArbeidsgiver, "meldingTilArbeidsgiver") },
        { assertEquals(wanted.tilbakedatering, found.tilbakedatering, "tilbakedatering") },
        { assertEquals(wanted.behandler, found.behandler, "behandler") },
    )
}

private fun OcrParserSykmelding.withoutSentences(): OcrParserSykmelding = copy(
    arbeidsgiver = arbeidsgiver.copy(harArbeidsgiver = arbeidsgiver.harArbeidsgiver?.withoutText()),
    aktivitet = aktivitet.map {
        if (it is OcrParserAktivitet.IkkeMulig) it.copy(
            medisinskArsakGrunn = it.medisinskArsakGrunn?.withoutText(),
            arbeidsrelatertArsakGrunn = it.arbeidsrelatertArsakGrunn?.withoutText(),
        ) else it
    },
)

private fun <T> OcrValue<T>.withoutText(): OcrValue<T> = when (this) {
    is OcrValue.Read -> copy(text = "")
    is OcrValue.Unreadable -> this
}

private val template = TextTemplates(File("src/main/resources/text-templates")).read("1pager")
