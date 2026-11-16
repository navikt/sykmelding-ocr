package no.nav.tsm.sykmelding.ocr

import no.nav.tsm.sykmelding.model.*
import no.nav.tsm.sykmelding.template.FIELD_NUMBER_ORDER
import no.nav.tsm.sykmelding.template.FieldLayout
import no.nav.tsm.sykmelding.template.FieldType
import no.nav.tsm.sykmelding.template.LineField
import no.nav.tsm.sykmelding.template.TextTemplate
import org.slf4j.LoggerFactory
import java.time.LocalDate
import kotlin.math.abs

object CustomTextTemplateParser {
    private val logger = LoggerFactory.getLogger(CustomTextTemplateParser::class.java)

    class Page(
        val result: OcrResult,
        val rows: Map<LineField, List<List<TextPart>>> = emptyMap(),
        val header: List<TextPart> = emptyList(),
        val ignored: List<IgnoredRow> = emptyList(),
        val values: List<FieldValue> = emptyList(),
        val heading: List<TextPart> = emptyList(),
    )

    fun read(
        parts: List<TextPart>,
        template: TextTemplate,
        reread: ((part: TextPart, allowedCharacters: String) -> String)? = null,
    ): Page {
        val fieldRows = fieldRows(parts, template, reread)
            ?: return Page(if (isSykmelding(parts)) OcrResult.NotYetSupported else OcrResult.NoMatch)
        val headerFnr = if (isSykmelding(parts) && isNavsCopy(parts)) fieldRows.getHeaderFnr() else null
        val top = fieldRows.header.minOfOrNull { it.line.y } ?: 0
        val heading = parts.filter { it.line.y < top && (isSykmelding(listOf(it)) || isNavsCopy(listOf(it))) }
        val result = if (headerFnr == null) OcrResult.NoMatch else OcrResult.Parsed(
            sykmelding = OcrParserSykmelding(
                templateVersion = template.name,
                syketilfelletStartDato = fieldRows.getSyketilfellestartDato(),
                pasient = fieldRows.getPasient(headerFnr),
                arbeidsgiver = fieldRows.getArbeidsgiver(),
                medisinskVurdering = fieldRows.getMedisinskVurdering(),
                aktivitet = fieldRows.getAktiviteter(),
                prognose = fieldRows.getPrognose(),
                tiltak = fieldRows.getTiltak(),
                bistandNav = fieldRows.getBistandNav(),
                meldingTilArbeidsgiver = fieldRows.getInlineFieldValue("9.1"),
                tilbakedatering = fieldRows.getTilbakedatering(),
                behandler = fieldRows.getBehandler(),
            ),
            emptyList()
        )
        return Page(result, fieldRows.rows, fieldRows.header, fieldRows.ignored, fieldRows.values(), heading)
    }

    private fun fieldRows(
        parts: List<TextPart>,
        template: TextTemplate,
        reread: ((part: TextPart, allowedCharacters: String) -> String)?,
    ): FieldRows? {
        
        val sortedParts = parts.filter { it.line.isLevel() }.filter { it.line.text != "|" }.sortedBy { it.line.baseline }

        if (sortedParts.size < parts.size) {
            logger.debug("removed unlevel parts {} parts", parts.size - sortedParts.size)
        }
        val folketrygden = sortedParts.firstOrNull { it.line.text.lowercase() == "folketrygden" }
        if (folketrygden == null) {
            return null
        }

        val textHeight = sortedParts.map { it.line.height }.sorted()[sortedParts.size / 2]

        val partsToAdd = sortedParts.filter { it.line.baseline >= folketrygden.line.baseline - (it.line.height / 2) }
            .filter { it.line.height < textHeight * 2.5 }.filter { it.line.height > textHeight / 2 }

        if(partsToAdd.isEmpty()) {
            return null
        }

        val tolerance = textHeight / 2
        val names = template.fields.map { it.name.lowercase() }
        val read = columns(partsToAdd, folketrygden, names, tolerance)

        val rowParts = read?.let { partsToAdd.flatMap { part -> if (part.line.x in it.number) cutBefore(part, it.name) else listOf(part) } }
        val found = if (rowParts?.size == partsToAdd.size) read else rowParts?.let { columns(it, folketrygden, names, tolerance) }
        if (rowParts == null || found == null) {
            logger.debug("did not find columns")
            return null
        }
        val columns = found.all
        val numberColumn = found.number
        val nameColumn = found.name
        val valueColumn = found.value
        logger.debug("columns at x: numbers {}, names {}, values {}", numberColumn.first(), nameColumn.first(), valueColumn.first())

        val lines = mutableMapOf<Int, MutableList<TextPart>>()

        var currentLineBaseLine = 0

        rowParts.forEach { part ->
            if (currentLineBaseLine > part.line.baseline - (part.line.height / 2)) {
                val currentLine = lines[currentLineBaseLine]
                if (currentLine == null) {
                    lines[part.line.baseline] = mutableListOf(part)
                } else {
                    currentLine.add(part)
                }
            } else {
                lines[part.line.baseline] = mutableListOf(part)
                currentLineBaseLine = part.line.baseline
            }
        }

        var rowsByNumber = 0
        var matchedAbove: LineField? = null
        var lastMatchedField: LineField? = null
        var header = emptyList<TextPart>()
        val fieldRowMap = mutableMapOf<LineField, MutableList<List<TextPart>>>()

        val ignored = mutableListOf<MutableList<List<TextPart>>>()
        var ignoredAbove: MutableList<List<TextPart>>? = null
        lines.values.forEach { parts ->
            val row = parts.sortedBy { it.line.x }
            val number = row.firstOrNull { it.line.x in numberColumn }?.line?.text?.lowercase()
            val name = row.firstOrNull { it.line.x in nameColumn }?.line?.text?.lowercase()
            val named = template.fields.filter { it.name.lowercase() == name }
            val above = matchedAbove
            val byNumber = template.fields.firstOrNull { it.number.lowercase() == number }
                ?: number?.let(::fieldNumberDigits)?.let { digits -> template.fields.singleOrNull { fieldNumberDigits(it.number) == digits } }
            val matchingField =
                named.singleOrNull()
                    ?: byNumber
                    ?: named.firstAfter(above)
                    ?: name?.let { nearlyNamed(template.fields, it) }?.firstAfter(above)

            val cutNumber = number == null && row.any { it.line.x < nameColumn.first() && it.line.text.any(Char::isDigit) }
            if (number == HEADER) {
                header = row
                lastMatchedField = null
                ignoredAbove = mutableListOf()
            } else if (matchingField != null) {
                lastMatchedField = matchingField
                ignoredAbove = null
                matchedAbove = matchingField
                if (matchingField == byNumber) rowsByNumber++
                fieldRowMap[matchingField] = mutableListOf(row)
                logger.debug("found matching field: {}", matchingField.number)
            } else if (lastMatchedField != null && number == null && !cutNumber) {
                fieldRowMap[lastMatchedField]?.add(row)
                logger.debug("adding row to field: {}", lastMatchedField.number)
            } else {
                lastMatchedField = null
                val runsOn = ignoredAbove?.takeIf { number == null && !cutNumber }
                if (runsOn != null) runsOn += row else ignoredAbove = mutableListOf(row).also { ignored += it }
            }
        }

        if (rowsByNumber < MIN_ROWS_BY_NUMBER) return null

        val afterNames = columns.drop(columns.indexOf(nameColumn) + 1)
        val valuesOfRows = fieldRowMap.filterKeys { it.layout == FieldLayout.INLINE }.values
            .mapNotNull { rows -> rows.first().firstNotNullOfOrNull { part -> afterNames.firstOrNull { part.line.x in it } } }
            .groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
        val values = (valuesOfRows ?: valueColumn).toMutableList()

        val subHeaders = template.fields.filter { it.layout == FieldLayout.BELOW }.mapNotNull { fieldNumberDigits(it.number) }.toSet()
        for (rows in fieldRowMap.values) {
            rows[0] = rows[0].flatMap { cutBeforeNumbers(it, subHeaders) }
            rows.replaceAll { row ->
                row.flatMap { part ->
                    if (part.line.x !in numberColumn && part.line.x !in nameColumn) return@flatMap listOf(part)
                    cutBefore(part, values).also { pieces -> if (pieces.size > 1) values += pieces.last().line.x }
                }
            }
        }
        val ignoredRows = ignored.mapNotNull { rows ->
            val number = rows.first().firstOrNull { it.line.x in numberColumn }
            val name = rows.first().firstOrNull { it.line.x in nameColumn }
            if (name == null && number?.line?.text?.any(Char::isDigit) != true) return@mapNotNull null
            val value = rows.flatten().filter { it !== name && it.line.x >= nameColumn.first() }.joinToString(" ") { it.line.text }
            IgnoredRow(number?.line?.text, name?.line?.text, value)
        }
        return FieldRows(fieldRowMap, values, numberColumn, nameColumn, template.fields, reread, ignoredRows, header)
    }
}

private const val HEADER = "folketrygden"

data class IgnoredRow(val number: String?, val name: String?, val value: String)

class FieldValue(val field: String, val parts: List<TextPart>, val place: TextRegion?)

const val HEADER_FNR = "fnr"

private class Columns(val all: List<List<Int>>, val number: List<Int>, val name: List<Int>, val value: List<Int>)

private fun columns(parts: List<TextPart>, folketrygden: TextPart, names: List<String>, tolerance: Int): Columns? {
    val all = findColumns(parts, tolerance)
    val numberIndex = all.indexOfFirst { folketrygden.line.x in it }
    if (numberIndex == -1) return null
    val name = all.drop(numberIndex + 1).maxByOrNull { column -> parts.count { it.line.x in column && it.line.text.lowercase() in names } } ?: return null
    val value = all.drop(all.indexOf(name) + 1).maxByOrNull { it.size } ?: return null
    return Columns(all, all[numberIndex], name, value)
}

private fun cutBefore(part: TextPart, column: List<Int>): List<TextPart> {
    val words = part.words.sortedBy { it.x }
    val at = (1 until words.size).firstOrNull { index -> column.any { abs(it - words[index].x) <= COLUMN_START } } ?: return listOf(part)
    return listOf(words.subList(0, at), words.subList(at, words.size)).map { part.piece(it) }
}

private fun cutBeforeNumbers(part: TextPart, numbers: Set<String>): List<TextPart> {
    val words = part.words.sortedBy { it.x }
    val starts = listOf(0) + (1 until words.size).filter { fieldNumberDigits(words[it].text) in numbers }
    if (starts.size == 1) return listOf(part)
    return starts.mapIndexed { index, start -> part.piece(words.subList(start, starts.getOrElse(index + 1) { words.size })) }
}

private fun TextPart.piece(words: List<TextRegion>): TextPart {
    val top = words.minOf { it.y }
    val line = line.copy(
        x = words.first().x,
        y = top,
        width = words.last().x + words.last().width - words.first().x,
        height = words.maxOf { it.y + it.height } - top,
        text = words.joinToString(" ") { it.text },
    )
    return TextPart(line, words)
}

private const val COLUMN_START = 5
private const val MIN_ROWS_BY_NUMBER = 3
private fun fieldNumberDigits(text: String): String? =
    text.takeIf { it.none(Char::isLetter) }?.filter(Char::isDigit)?.ifEmpty { null }

private fun isSykmelding(parts: List<TextPart>): Boolean = parts.any { it.line.text.contains("sykmelding", ignoreCase = true) }

private fun isNavsCopy(parts: List<TextPart>): Boolean =
    parts.any { it.line.text.filter(Char::isLetter).equals("navseksemplar", ignoreCase = true) }

private fun List<LineField>.firstAfter(above: LineField?): LineField? =
    filter { above == null || FIELD_NUMBER_ORDER.compare(above.number, it.number) < 0 }.minWithOrNull(compareBy(FIELD_NUMBER_ORDER) { it.number })

private fun nearlyNamed(fields: List<LineField>, name: String): List<LineField> {
    var nearest = Int.MAX_VALUE
    val found = mutableListOf<LineField>()
    for (field in fields) {
        val fieldName = field.name.lowercase()
        if (fieldName.length < MIN_NEAR_NAME) continue
        val most = (fieldName.length * NEAR_NAME_SHARE).toInt()
        val distance = when {
            name.length >= MIN_NAME_START && fieldName.startsWith(name) -> 0
            fieldName.length >= MIN_NAME_START && name.startsWith(fieldName) -> 0
            abs(fieldName.length - name.length) > most -> continue
            else -> editDistance(name, fieldName).takeIf { it <= most } ?: continue
        }
        if (distance < nearest) {
            nearest = distance
            found.clear()
        }
        if (distance == nearest) found.add(field)
    }
    return found
}

private fun editDistance(a: String, b: String): Int {
    var above = IntArray(b.length + 1) { it }
    var row = IntArray(b.length + 1)
    for (i in 1..a.length) {
        row[0] = i
        for (j in 1..b.length) {
            row[j] = minOf(above[j] + 1, row[j - 1] + 1, above[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
        }
        above = row.also { row = above }
    }
    return above[b.length]
}

private const val MIN_NEAR_NAME = 10
private const val NEAR_NAME_SHARE = 0.15
private const val MIN_NAME_START = 20

private fun findColumns(parts: List<TextPart>, tolerance: Int): List<List<Int>> {
    val columns = mutableListOf<MutableList<Int>>()
    parts.map { it.line.x }.sorted().forEach { x ->
        val column = columns.lastOrNull()
        if (column != null && x - column.last() <= tolerance) column.add(x) else columns.add(mutableListOf(x))
    }
    return columns
}

private class FieldRows(
    val rows: Map<LineField, List<List<TextPart>>>,
    val valueColumn: List<Int>,
    val numberColumn: List<Int>,
    val nameColumn: List<Int>,
    val fields: List<LineField>,
    val reread: ((part: TextPart, allowedCharacters: String) -> String)?,
    val ignored: List<IgnoredRow>,
    val header: List<TextPart>,
)

private fun FieldRows.getRows(field: String): List<List<TextPart>>? =
    rows.entries.firstOrNull { it.key.number.equals(field, ignoreCase = true) }?.value

private fun FieldRows.typeOf(field: String): FieldType? = fields.firstOrNull { it.number.equals(field, ignoreCase = true) }?.field?.type

private fun FieldRows.read(part: TextPart, field: String): String = readAs(part, typeOf(field))

private fun FieldRows.readAs(part: TextPart, type: FieldType?): String {
    val allowedCharacters = type?.allowedCharacters
    if (allowedCharacters == null || reread == null) return part.line.text
    return reread(part, allowedCharacters).ifBlank { part.line.text }
}

private fun date(text: String, today: LocalDate? = null): OcrValue<LocalDate>? = parseDate(dateIn(text), today)

private fun FieldRows.getBehandler(): OcrParserBehandler {
    return OcrParserBehandler(
        navn = getInlineFieldValue("12.2"),
        hpr = getInlineFieldValue("12.4")?.let { parseHpr(it) },
        telefon = getInlineFieldValue("12.5")?.let { parseTelefon(it) },
        adresse = getInlineFieldValue("12.3") ?: getInlineFieldValue("12.6"),
        behandletDato = getInlineFieldValue("12.1")?.let { date(it) },
    )
}

private fun FieldRows.getTilbakedatering(): OcrParserTilbakedatering {
    return OcrParserTilbakedatering(
        kontaktDato = getInlineFieldValue("11.1")?.let { date(it) },
        begrunnelse = getInlineFieldValue("11.2"),
    )
}

private fun FieldRows.getBistandNav(): OcrParserBistandNav {
    return OcrParserBistandNav(
        bistandUmiddelbart = getCheckboxState("8.1"),
        beskrivBistand = getInlineFieldValue("8.2"),
    )
}

private fun FieldRows.getTiltak(): OcrParserTiltak {
    return OcrParserTiltak(
        tiltakArbeidsplassen = getInlineFieldValue("7.1"),
        tiltakNav = getInlineFieldValue("7.2"),
        andreTiltak = getInlineFieldValue("7.3"),
    )
}

private fun FieldRows.getPrognose(): OcrParserPrognose {
    return OcrParserPrognose(
        arbeidsforEtterPeriode = getCheckboxState("5.1"),
        hensynArbeidsplassen = getInlineFieldValue("5.1.1"),
    )
}

private fun FieldRows.getAktiviteter(): List<OcrParserAktivitet> {
    val avventendeAktivitet = if (getRows("4.1") != null) OcrParserAktivitet.Avventende(
        fom = getBelowFieldValue("4.1", "4.1.1", "f.o.m.")?.let { date(it) },
        tom = getBelowFieldValue("4.1", "4.1.2", "t.o.m.")?.let { date(it) },
        innspillTilArbeidsgiver = getInlineFieldValue("4.1.3"),
    ) else null

    val gradertAktivitet = if (getRows("4.2") != null) OcrParserAktivitet.Gradert(
        fom = getBelowFieldValue("4.2", "4.2.1", "f.o.m.")?.let { date(it) },
        tom = getBelowFieldValue("4.2", "4.2.2", "t.o.m.")?.let { date(it) },
        grad = getInlineFieldValue("4.2.3")?.let { parsePercent(it) },
        reisetilskudd = getCheckboxState("4.2.4"),
    ) else null

    val aktivitetIkkeMulig = if (getRows("4.3") != null) OcrParserAktivitet.IkkeMulig(
        fom = getBelowFieldValue("4.3", "4.3.1", "f.o.m.")?.let { date(it) },
        tom = getBelowFieldValue("4.3", "4.3.2", "t.o.m.")?.let { date(it) },
        medisinskArsak = getCheckboxState("4.3.3"),
        medisinskArsakGrunn = getInlineValue("4.3.3.1")?.let { parseMedisinskArsakGrunn(it) },
        medisinskArsakBegrunn = getInlineValue("4.3.3.2"),
        arbeidsrelatertArsak = getCheckboxState("4.3.4"),
        arbeidsrelatertArsakGrunn = getInlineValue("4.3.4.1")?.let { parseArbeidsrelatertArsakGrunn(it) },
        arbeidsrelatertArsakBegrunn = getInlineValue("4.3.4.2")
    ) else null

    val behandlingsdager = if (getRows("4.4") != null) OcrParserAktivitet.Behandlingsdager(
        fom = getBelowFieldValue("4.4", "4.4.1", "f.o.m.")?.let { date(it) },
        tom = getBelowFieldValue("4.4", "4.4.2", "t.o.m.")?.let { date(it) },
        antallBehandlingsdager = getInlineFieldValue("4.4.3")?.let { parseCount(it) },
    ) else null

    val reisetilskudd = if (getRows("4.5") != null) OcrParserAktivitet.Reisetilskudd(
        fom = getBelowFieldValue("4.5", "4.5.1", "f.o.m.")?.let { date(it) },
        tom = getBelowFieldValue("4.5", "4.5.2", "t.o.m.")?.let { date(it) },
    ) else null

    return listOfNotNull(avventendeAktivitet, gradertAktivitet, aktivitetIkkeMulig, behandlingsdager, reisetilskudd)
}

private fun FieldRows.getCheckboxState(field: String): CheckboxState {
    if (getRows(field) == null) return CheckboxState.UNCHECKED
    return when (getInlineFieldValue(field)?.filter(Char::isLetter)?.lowercase()) {
        null, "ja" -> CheckboxState.CHECKED
        "nei" -> CheckboxState.UNCHECKED
        else -> CheckboxState.UNCERTAIN
    }
}

private fun FieldRows.getMedisinskVurdering(): OcrParserMedisinskVurdering {
    val system = getBelowFieldValue("3.1", "3.1.1", "Kodesystem")
    val kode = getBelowFieldValue("3.1", "3.1.2", "Kode")

    val text = getBelowFieldValue("3.1", "3.1.3", "Diagnose")
    val diagnose = if (kode != null) OcrParserDiagnose(resolveDiagnose(system, kode, text), system, text) else null

    return OcrParserMedisinskVurdering(
        hovedDiagnose = diagnose,
        biDiagnoser = getBiDiagnoser(),
        annenFraversArsak = null,
        svangerskap = getCheckboxState("3.4"),
        yrkesskade = getCheckboxState("3.5"),
        yrkesskadeDato = getInlineFieldValue("3.6")?.let { date(it) },
        skjermetForPasient = getCheckboxState("3.7"),
    )
}

private fun FieldRows.getBiDiagnoser(): List<OcrParserDiagnose> {
    val systems = partsBelow("3.2", "3.2.1", "Kodesystem")
    val codes = partsBelow("3.2", "3.2.2", "Kode")
    val texts = partsBelow("3.2", "3.2.3", "Diagnose")
    return codes.mapIndexed { index, code ->
        val top = code.line.baseline - code.line.height / 2
        val bottom = codes.getOrNull(index + 1)?.let { it.line.baseline - it.line.height / 2 } ?: Int.MAX_VALUE
        fun List<TextPart>.ofDiagnose(number: String): String? =
            filter { it.line.baseline in top until bottom }.joinToString(" ") { read(it, number) }.ifEmpty { null }
        val system = systems.ofDiagnose("3.2.1")
        val text = texts.ofDiagnose("3.2.3")
        OcrParserDiagnose(resolveDiagnose(system, read(code, "3.2.2"), text), system, text)
    }
}

private fun FieldRows.getArbeidsgiver(): OcrParserArbeidsgiver {
    return OcrParserArbeidsgiver(
        harArbeidsgiver = getInlineFieldValue("2.1")?.let { parseHarArbeidsgiver(it) },
        navn = getInlineFieldValue("2.2"),
        yrkesbetegnelse = getInlineFieldValue("2.3"),
        stillingsprosent = getInlineFieldValue("2.4")?.let { parsePercent(it) }
    )
}

private fun FieldRows.getInlineFieldValue(field: String): String? {
    val fieldRows = getRows(field) ?: return null
    val values = fieldRows.flatten().filter { it.line.x in valueColumn }
    return if (values.isEmpty()) valueInPartAbove(field, fieldRows.first()) else values.joinToString(" ") { read(it, field) }.ifEmpty { null }
}
private fun FieldRows.valueInPartAbove(field: String, row: List<TextPart>): String? {
    val reread = reread ?: return null
    val top = row.minOf { it.line.y }
    val bottom = row.maxOf { it.line.y + it.line.height }
    val above = rows.values.asSequence().flatten().flatten()
        .firstOrNull { it.line.x in valueColumn && it.line.y < top && it.line.y + it.line.height > (top + bottom) / 2 } ?: return null
    val strip = above.line.copy(y = top, height = bottom - top, baseline = bottom, text = "")
    return reread(TextPart(strip, emptyList()), typeOf(field)?.allowedCharacters.orEmpty()).ifBlank { null }
}

private fun FieldRows.getInlineValue(field: String): String? =
    getRows(field)?.flatten()?.filter { it.line.x !in nameColumn + numberColumn  }?.joinToString(" ") { read(it, field) }?.ifEmpty { null }

private fun FieldRows.getBelowFieldValue(field: String, number: String, name: String): String? =
    partsBelow(field, number, name).joinToString(" ") { read(it, number) }.ifEmpty { null }

private fun FieldRows.partsBelow(field: String, number: String, name: String): List<TextPart> {
    val parts = fromSubHeader(field, number, name)
    val headerLine = parts.firstOrNull()?.line ?: return emptyList()
    return parts.drop(1).filter { abs(it.line.x - headerLine.x) <= headerLine.height / 2 }
}

private fun FieldRows.fromSubHeader(field: String, number: String, name: String): List<TextPart> {
    val parts = getRows(field)?.flatten() ?: return emptyList()
    val digits = number.filter(Char::isDigit)
    val header = parts.indexOfLast {
        val text = it.line.text
        text.takeWhile { mark -> !mark.isLetter() }.filter(Char::isDigit) == digits ||
            text.equals(name, ignoreCase = true) || text.endsWith(" $name", ignoreCase = true)
    }
    return if (header == -1) emptyList() else parts.drop(header)
}

private fun FieldRows.values(): List<FieldValue> = listOfNotNull(headerFnrValue()) + fields.mapNotNull { field ->
    when (field.layout) {
        FieldLayout.INLINE -> getRows(field.number)?.let { rows ->
            val names = nameColumn.max()
            FieldValue(field.number, together(rows).flatten().filter { it.line.x > names }, place(valueColumn.min(), rows.first().first()))
        }
        FieldLayout.BELOW -> {
            val parent = field.number.substringBeforeLast('.')
            if (fromSubHeader(parent, field.number, field.name).isEmpty()) null else {
                val near = getRows(parent)?.let(::together).orEmpty().flatten()
                FieldValue(field.number, partsBelow(parent, field.number, field.name).filter { it in near }, null)
            }
        }
        FieldLayout.NONE -> null
    }
}

private fun together(rows: List<List<TextPart>>): List<List<TextPart>> {
    var above = rows.first()
    return rows.takeWhile { row ->
        (row.minOf { it.line.baseline } - above.maxOf { it.line.baseline } <= ROW_GAP * above.maxOf { it.line.height }).also { above = row }
    }
}

private const val ROW_GAP = 6

private fun FieldRows.headerFnrValue(): FieldValue? {
    val last = header.lastOrNull() ?: return null
    val words = last.words.sortedBy { it.x }.dropWhile { word -> word.text.none(Char::isDigit) }
    return if (words.isEmpty()) FieldValue(HEADER_FNR, emptyList(), place(last.line.x + last.line.width + last.line.height, last))
    else FieldValue(HEADER_FNR, listOf(last.piece(words)), null)
}

private fun place(x: Int, label: TextPart): TextRegion =
    label.line.copy(x = x, width = 0, text = "", confidence = 0f)

private fun FieldRows.getHeaderFnr(): OcrValue<String>? {
    val text = header.lastOrNull()?.let { part -> readAs(part, FieldType.IDENT).dropWhile { !it.isDigit() } } ?: return null
    return parseFnr(text)?.takeIf { text.count(Char::isDigit) == FNR_DIGITS }
}

private const val FNR_DIGITS = 11

private fun FieldRows.getPasient(headerFnr: OcrValue<String>): OcrParserPasient {
    val pasientNavn = getInlineFieldValue("1.1")
    val pasientFnr = getInlineFieldValue("1.2")?.let { parseFnr(it) }
    val pasientTlf = getInlineFieldValue("1.3")?.let { parseTelefon(it) }
    val pasientFastlege = getInlineFieldValue("1.4")
    val navKontor = getInlineFieldValue("1.5")
    val name = parseFullName(text = pasientNavn)
    val etternavn = parseName(getInlineFieldValue("1.1.1"))?.let { OcrCrossChecked(it, emptyList(), 0) }
    val fornavn = parseName(getInlineFieldValue("1.1.2"))?.let { OcrCrossChecked(it, emptyList(), 0) }
    return OcrParserPasient(
        fnr = crossCheckFnr(
            listOfNotNull(
                OcrReading("header.fnr", 1, headerFnr),
                pasientFnr?.let { OcrReading("pasient.fnr", 1, it) },
            )
        ),
        fornavn = fornavn ?: name.first,
        etternavn = etternavn ?: name.second,
        navnFastlege = pasientFastlege,
        telefon = pasientTlf,
        navKontor = navKontor,
    )
}

private fun FieldRows.getSyketilfellestartDato(): OcrValue<LocalDate>? =
    getInlineFieldValue("0")?.let { date(it, LocalDate.now()) }
