package no.nav.tsm.ocr.template.gcp

import no.nav.tsm.ocr.template.testing.TemplatePreviews
import no.nav.tsm.ocr.template.testing.alignmentPreview
import no.nav.tsm.ocr.template.testing.png
import no.nav.tsm.ocr.template.testing.previewScale
import no.nav.tsm.ocr.template.testing.resized
import no.nav.tsm.sykmelding.model.CheckboxState
import no.nav.tsm.sykmelding.model.OcrParserSykmelding
import no.nav.tsm.sykmelding.ocr.CHECKBOX_RULE
import no.nav.tsm.sykmelding.ocr.CheckboxReading
import no.nav.tsm.sykmelding.ocr.FieldText
import no.nav.tsm.sykmelding.ocr.FieldSegment
import no.nav.tsm.sykmelding.ocr.Ocr
import no.nav.tsm.sykmelding.ocr.OcrResult
import no.nav.tsm.sykmelding.ocr.PageRead
import no.nav.tsm.sykmelding.ocr.ParseSykmeldingOcr
import no.nav.tsm.sykmelding.ocr.TESSERACT_RESOLUTIONS
import no.nav.tsm.sykmelding.ocr.TextRegion
import no.nav.tsm.sykmelding.ocr.skipsOcr
import no.nav.tsm.sykmelding.template.MatchingMethods
import no.nav.tsm.sykmelding.template.TemplateMatchResult
import no.nav.tsm.sykmelding.template.TemplateMatcher
import no.nav.tsm.sykmelding.template.Felter
import no.nav.tsm.sykmelding.template.PdfTemplateImages
import no.nav.tsm.sykmelding.template.Template
import no.nav.tsm.sykmelding.template.TemplateFiles
import no.nav.tsm.sykmelding.template.TemplateGroup
import no.nav.tsm.sykmelding.template.TemplateLibrary
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.File
import java.util.Locale
import javax.imageio.ImageIO
import kotlin.math.roundToInt

internal class LibraryPage(
    val group: String,
    val templateFile: File,
    val position: Int,
    val templateSize: Int,
    val file: File,
    val template: Template,
) {
    val name: String get() = file.parentFile.name
    val label: String get() = "$group / $name"

    override fun toString() = label
}

internal class LibraryPages(val pages: List<LibraryPage>, val problems: List<String>, val fingerprint: List<String>) {
    val groups: Int get() = pages.map { it.templateFile }.distinct().size

    val description: String
        get() = if (pages.isEmpty()) "no template pages with an image" else
            "${pages.size} page${plural(pages.size)} in $groups template${plural(groups)}"

    companion object {
        /**
         * Reads every template separately, so one broken template does not hide the others. With [only], just the
         * templates whose folder name is in it; null = all.
         */
        fun load(library: TemplateLibrary, only: Set<String>? = null): LibraryPages {
            val pages = mutableListOf<LibraryPage>()
            val problems = mutableListOf<String>()
            val fingerprint = mutableListOf<String>()
            val folders = library.directory.listFiles()?.filter { it.isDirectory && it.resolve("mal.json").isFile }?.sortedBy { it.name }
                ?.filter { only == null || it.name in only }
                ?: return LibraryPages(emptyList(), listOf("No template folder at ${library.directory.absolutePath}"), emptyList())
            for (folder in folders) {
                val group = try { library.read(folder.resolve("mal.json")) } catch (error: Exception) {
                    problems += "${folder.name}: ${error.message}"
                    continue
                }
                if (group.pageFiles.isEmpty()) problems += "${group.manifest.navn}: has no pages"
                for ((index, file) in group.pageFiles.withIndex()) {
                    val label = "${group.manifest.navn} / ${file.parentFile.name}"
                    val template = try { TemplateFiles.read(file) } catch (error: Exception) {
                        problems += "$label: ${error.message}"
                        continue
                    }
                    val image = template.templateBilde.takeIf { it.isNotBlank() }?.let(::File)
                    when {
                        image == null -> problems += "$label: has no template image"
                        !image.isFile -> problems += "$label: template image is missing (${image.path})"
                        else -> {
                            pages += LibraryPage(group.manifest.navn, group.file, index + 1, group.pageFiles.size, file, template)
                            fingerprint += "${file.absolutePath}:${file.lastModified()}:${image.absolutePath}:${image.lastModified()}:${image.length()}"
                        }
                    }
                }
            }
            return LibraryPages(pages, problems, fingerprint)
        }
    }
}

/**
 * Lib's [TemplateMatcher] — the matcher production runs — built for the library as [fingerprint] describes it, with
 * the template previews its results are drawn with. Creating the matcher detects the features of every template page,
 * so it is kept between runs while no template changes.
 */
internal class PreparedMatcher(val fingerprint: List<String>, val matcher: TemplateMatcher, val previews: TemplatePreviews = TemplatePreviews()) : AutoCloseable {
    override fun close() = matcher.close()
}

/**
 * How a test runs: the PDF's pages are rendered at [renderDpi] and matched with [methods] (keypoints, match filter and
 * homography) by features found at [featureDpi], a match agreeing with the homography within [tolerance] template
 * pixels (null: auto, see [TemplateMatcher.reprojectionTolerance]); the fields of the pages matched are read (OCR) at
 * [ocrDpi]. [PRODUCTION] is what production uses.
 */
internal data class TestSettings(
    val renderDpi: Int,
    val featureDpi: Int,
    val ocrDpi: Int,
    val methods: MatchingMethods = MatchingMethods.PRODUCTION,
    val tolerance: Double? = null,
) {
    /** The tolerance the matcher uses: the one chosen, or auto's at [featureDpi]. */
    val effectiveTolerance: Double get() = tolerance ?: TemplateMatcher.reprojectionTolerance(featureDpi)

    private val productionMatching: Boolean
        get() = renderDpi == PRODUCTION.renderDpi && featureDpi == PRODUCTION.featureDpi && methods == PRODUCTION.methods &&
            effectiveTolerance == PRODUCTION.effectiveTolerance

    /** Whether [matcher] matches as these settings do. */
    fun matchesAs(matcher: TemplateMatcher): Boolean =
        matcher.renderDpi == renderDpi && matcher.featureDpi == featureDpi && matcher.methods == methods &&
            matcher.tolerance == effectiveTolerance

    /** How the file was matched, to follow "Matched with lib's TemplateMatcher". */
    val matchingText: String
        get() = if (productionMatching) "as in production (${methods.text} within ${pixels(effectiveTolerance)}, " +
                "PDF rendered at $renderDpi DPI, features found at $featureDpi DPI)."
            else "with ${methods.text} within ${pixels(effectiveTolerance)}, the PDF rendered at $renderDpi DPI and features found " +
                "at $featureDpi DPI (production: ${PRODUCTION.methods.text} within ${pixels(PRODUCTION.effectiveTolerance)}, " +
                "${PRODUCTION.renderDpi} and ${PRODUCTION.featureDpi} DPI)."

    /** How the fields were read, to follow "Fields read with lib's OCR". */
    val ocrText: String
        get() = (if (ocrDpi == PRODUCTION.ocrDpi) "as production reads them, at $ocrDpi DPI"
            else "at $ocrDpi DPI (production reads them at ${PRODUCTION.ocrDpi} DPI)") +
            if (ocrDpi < MIN_TESSERACT_DPI) "; Tesseract is told $MIN_TESSERACT_DPI DPI, the lowest it accepts" else ""

    companion object {
        val PRODUCTION = TestSettings(PdfTemplateImages.DPI, TemplateMatcher.DEFAULT_FEATURE_DPI, PdfTemplateImages.DPI)
        val RENDER_RANGE = 25..1200
        val OCR_RANGE = 25..1200
        private const val MIN_FEATURE_DPI = 10
        /** Below this, Tesseract warns and assumes it instead; the page is still read at the resolution chosen. */
        val MIN_TESSERACT_DPI = TESSERACT_RESOLUTIONS.first

        /** Features are found on the rendered page and the templates scaled down, so no higher than either. */
        fun featureRange(renderDpi: Int): IntRange = MIN_FEATURE_DPI..minOf(renderDpi, PdfTemplateImages.DPI)

        /** The tolerances that can be typed, in template pixels: from a tenth of a millimetre to a few. */
        val TOLERANCE_RANGE = 0.5..50.0
    }
}

/** [value] template pixels, as "4 px" or "2.5 px". */
internal fun pixels(value: Double): String =
    (if (value == Math.rint(value)) "%.0f" else "%.1f").format(Locale.ROOT, value) + " px"

private val TOLERANCE_TEXT = Regex("""^\s*(\d+(?:[.,]\d)?)\s*(?:px)?\s*$""", RegexOption.IGNORE_CASE)
private val AUTO_TEXT = Regex("""^\s*(?:auto\b.*)?$""", RegexOption.IGNORE_CASE)

/** A tolerance typed or chosen: auto, or a number of template pixels. */
internal sealed interface ToleranceInput {
    data object Auto : ToleranceInput
    data class Pixels(val value: Double) : ToleranceInput
}

/**
 * [text] as a tolerance: [ToleranceInput.Auto] for "Auto" (also "Auto (4 px)") or nothing, a number of pixels as typed
 * ("4", "2.5", "2,5", "4 px"), with one decimal at most so that it is shown and stored as typed, if it is in
 * [TestSettings.TOLERANCE_RANGE]; null if neither.
 */
internal fun parseTolerance(text: String?): ToleranceInput? {
    val typed = text.orEmpty()
    if (AUTO_TEXT.matches(typed)) return ToleranceInput.Auto
    val value = TOLERANCE_TEXT.matchEntire(typed)?.groupValues?.get(1)?.replace(',', '.')?.toDoubleOrNull() ?: return null
    return ToleranceInput.Pixels(value).takeIf { value in TestSettings.TOLERANCE_RANGE }
}

private val DPI_TEXT = Regex("""^\s*(\d+)\s*(?:dpi)?\s*$""", RegexOption.IGNORE_CASE)

/** [text] as a whole number of DPI, as typed ("75", " 75 ", "75 dpi"), if it is in [range]; null if not. */
internal fun parseDpi(text: String?, range: IntRange): Int? =
    DPI_TEXT.matchEntire(text.orEmpty())?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it in range }

internal enum class PdfStatus(private val label: String) {
    QUEUED("Queued"), RUNNING("Running …"), MATCHED("✓ Matched"), NO_MATCH("✗ No match"), ERROR("Error");

    /** A final result rather than a run in progress. */
    val finished: Boolean get() = ordinal >= MATCHED.ordinal

    override fun toString() = label
}

/**
 * A template page the matcher found in the file. [preview] is a PNG of the PDF page aligned to it (black) with the
 * template's ink in red on top, [previewScale] × template pixels; null when the template image could not be read.
 */
internal class TemplateOutcome(val template: LibraryPage, val preview: ByteArray?, val previewScale: Double) {
    fun previewImage(): BufferedImage? = preview?.let { ImageIO.read(ByteArrayInputStream(it)) }
}

internal typealias OcrFields = Map<Felter, FieldText>

/**
 * One document's result. [MATCHED][PdfStatus.MATCHED]: every page of one template was found in the file, and
 * [outcomes] lists those template pages in template order. [reads] has the fields read from each of them, in the same
 * order; null when they could not be read ([message] says why). [millis] is the whole test, [ocrMillis] the OCR part.
 * [settings]: how it was tested (resolutions, matching methods); null when it was not tested (e.g. could not be downloaded).
 */
internal class PdfTestResult(
    val status: PdfStatus,
    val message: String = "",
    val pages: Int? = null,
    val outcomes: List<TemplateOutcome> = emptyList(),
    val millis: Long = 0,
    val reads: List<PageRead>? = null,
    val ocrMillis: Long = 0,
    val ocrError: String? = null,
    val settings: TestSettings? = null,
    val sykmelding: OcrParserSykmelding? = null,
) {
    val ocr: List<OcrFields>? get() = reads?.map { it.values }
    val checkboxes: List<Map<Felter, CheckboxReading>>? get() = reads?.map { it.checkboxes }
    val segments: List<List<FieldSegment>>? get() = reads?.map { it.segments }
    val statusText: String get() = if (status == PdfStatus.RUNNING && message.isNotEmpty()) message else status.toString()

    /** The matched template and its pages, for the file list; empty when nothing matched. */
    val templateText: String
        get() = outcomes.firstOrNull()?.let { first -> "${first.template.group}: " + outcomes.joinToString(", ") { it.template.name } }.orEmpty()

    /** A few lines: which template the file is, or why it is none of them; then the fields read, if any. */
    fun summary(): String = buildString {
        when (status) {
            PdfStatus.QUEUED -> appendLine("Queued for testing.")
            PdfStatus.RUNNING -> appendLine(message.ifEmpty { "Testing …" })
            else -> {
                append(status)
                pages?.let { append(" — $it page${plural(it)}") }
                if (millis > 0) append(" in %.1f s".format(millis / 1000.0))
                if (reads != null) append(" (OCR ${seconds(ocrMillis)})")
                appendLine()
                outcomes.forEach { appendLine("  ${it.template.label} — page ${it.template.position} of ${it.template.templateSize} found and aligned") }
                if (message.isNotEmpty()) appendLine(message)
                settings?.let { appendLine("Matched with lib's TemplateMatcher, " + it.matchingText) }
                reads?.let { pages ->
                    appendLine()
                    appendLine("Fields read with lib's OCR, ${settings?.ocrText ?: "as production reads them"}: " +
                        "${pages.sumOf { it.values.size }} on ${pages.size} page${plural(pages.size)}")
                    outcomes.zip(pages).forEach { (outcome, page) ->
                        appendLine("  ${outcome.template.label}:")
                        appendLine(fieldLines(page).trimEnd().prependIndent("    "))
                    }
                }
            }
        }
    }
}

/** One line per field: what was read in it, with confidence; for a checkbox, its state and ink ([checkboxText]). */
internal fun fieldLines(page: PageRead): String = buildString {
    page.values.forEach { (field, text) ->
        val value = if (field.type.skipsOcr) checkboxText(page.checkboxes[field]) else withConfidence(text.lines).ifEmpty { "–" }
        appendLine("${"${field.navn} (${field.type})".padEnd(36)} $value")
    }
}

internal fun leftOutOf(field: Felter, segments: List<FieldSegment>): List<String> {
    if (field.type.skipsOcr) return emptyList()
    val own = segments.filter { it.field == field }
    if (own.isEmpty()) return listOf(OcrFieldRow.NOTHING_FOUND)
    return own.flatMap { segment ->
        if (segment.lines.isEmpty()) listOf("read nothing")
        else segment.lines.mapNotNull { line -> line.leftOut?.let { "${withConfidence(listOf(line.region))} — ${it.description}" } }
    }
}

/**
 * The layout's line segments that gave their field nothing, and why: read as nothing, or each line read in it left
 * out, e.g. as a stamp. Empty when there are none; otherwise a heading and one line per segment.
 */
internal fun leftOutSegments(segments: List<FieldSegment>): String = buildString {
    val leftOut = segments.filter { segment -> segment.lines.none { it.leftOut == null } }
    if (leftOut.isEmpty()) return@buildString
    appendLine("Line segments that gave their field nothing (${leftOut.size}):")
    leftOut.forEach { appendLine("  ${"${it.field.navn} (${it.field.type})".padEnd(36)} ${segmentOutcome(it)}") }
}

/** What became of [segment]: that nothing was read in it, or each line's fate. */
internal fun segmentOutcome(segment: FieldSegment): String {
    if (segment.lines.isEmpty()) return "read nothing"
    return segment.lines.joinToString(" / ") { "${withConfidence(listOf(it.region))} — ${it.leftOut?.description ?: "used"}" }
}

internal fun pageCount(file: File): Int = PdfTemplateImages.pageCount(file)

/**
 * Matches [file] with [prepared]'s matcher, exactly as production does: the file matches a template when every page
 * of the template is found among its pages. Each found template page gets a preview of the aligned page, and its
 * fields are read with lib's [ParseSykmeldingOcr.doOcr] right away at [ocrDpi], while the aligned pages are still in memory
 * (they are dropped afterwards; [reading] is called just before). The result says how it was tested: the matcher's
 * resolutions and methods, and [ocrDpi]. Never throws: an unreadable file becomes [PdfStatus.ERROR], and a match whose fields
 * could not be read stays [PdfStatus.MATCHED] with the error in its message.
 */
internal fun testAgainstTemplates(file: File, prepared: PreparedMatcher, ocrDpi: Int = PdfTemplateImages.DPI, reading: () -> Unit = {}): PdfTestResult {
    val started = System.nanoTime()
    fun elapsed() = (System.nanoTime() - started) / 1_000_000
    val matcher = prepared.matcher
    val settings = TestSettings(matcher.renderDpi, matcher.featureDpi, ocrDpi, matcher.methods, matcher.tolerance)
    val pages = try { pageCount(file) } catch (error: Exception) {
        return PdfTestResult(PdfStatus.ERROR, "Could not read the file: ${error.message}", millis = elapsed(), settings = settings)
    }
    return when (val result = prepared.matcher.matchPdf(file)) {
        is TemplateMatchResult.Matched -> {
            val outcomes = result.templateMatch.entries.mapIndexed { index, (template, aligned) -> outcome(result.template, index, template, aligned, prepared.previews) }
            reading()
            val ocrStarted = System.nanoTime()
            val parsed = runCatching { ParseSykmeldingOcr(Ocr(), matcher).doOcr(result, ocrDpi) }
                .getOrElse { OcrResult.Failed(it.message ?: it.toString(), it) }
            val ocrMillis = (System.nanoTime() - ocrStarted) / 1_000_000
            val error = (parsed as? OcrResult.Failed)?.let { "The fields could not be read: ${it.reason}" }
            val ok = parsed as? OcrResult.Parsed
            PdfTestResult(PdfStatus.MATCHED, error.orEmpty(), pages, outcomes, elapsed(), ok?.pages, ocrMillis, error, settings, ok?.sykmelding)
        }
        TemplateMatchResult.NoMatch -> PdfTestResult(PdfStatus.NO_MATCH, "No template has every one of its pages in this file.", pages, millis = elapsed(), settings = settings)
        is TemplateMatchResult.Error -> PdfTestResult(PdfStatus.ERROR, result.exception.message ?: result.exception.toString(), pages, millis = elapsed(), settings = settings)
        is TemplateMatchResult.TooManyPages -> PdfTestResult(PdfStatus.NO_MATCH,
            "${result.pages} pages: the matcher takes at most ${result.maxPages}, so the file is not matched against any template.", pages, millis = elapsed(), settings = settings)
    }
}

/** Template page [index] of [group] (the matcher lists them in the manifest's order) with [aligned] drawn under its ink. */
private fun outcome(group: TemplateGroup, index: Int, template: Template, aligned: BufferedImage, previews: TemplatePreviews): TemplateOutcome {
    val pageFile = group.pageFiles[index]
    val resolved = template.copy(templateBilde = pageFile.parentFile.resolve(template.templateBilde).normalize().absolutePath)
    val page = LibraryPage(group.manifest.navn, group.file, index + 1, group.pageFiles.size, pageFile, resolved)
    return try {
        val (templatePreview, scale) = previews.of(resolved.templateBilde)
        TemplateOutcome(page, png(alignmentPreview(templatePreview, aligned, scale)), scale)
    } catch (_: Exception) {
        // The aligned page alone is still worth seeing.
        val scale = previewScale(aligned.width)
        TemplateOutcome(page, png(resized(aligned, scale)), scale)
    }
}

internal fun plural(count: Int) = if (count == 1) "" else "s"

/** How a checkbox is read: by the ink inside its frame, not by OCR ([skipsOcr]), and how its state is decided. */
internal val CHECKBOX_READ = "Checkbox: read by the ink inside its frame, not by OCR. $CHECKBOX_RULE"

/**
 * A checkbox as shown in place of a value: its state and how much ink is in it, `[x] checked (23.4 % dark, 25.0 % ink)`.
 * [reading] is null when the page's checkboxes were not read.
 */
internal fun checkboxText(reading: CheckboxReading?): String {
    if (reading == null) return "not read"
    return when (reading.state) {
        CheckboxState.CHECKED -> "[x] checked (${reading.coverageText})"
        CheckboxState.UNCHECKED -> "[ ] unchecked (${reading.coverageText})"
        CheckboxState.UNCERTAIN -> "[?] uncertain (${reading.coverageText}): a faint or partial mark, check by hand"
    }
}

/** [lines] on one line, separated by " / ", each with Tesseract's confidence in it: `Storgata 1 [93%] / 0150 Oslo [71%]`. */
internal fun withConfidence(lines: List<TextRegion>): String = lines.joinToString(" / ") { "${oneLine(it.text)} [${it.confidence.roundToInt()}%]" }
internal fun seconds(millis: Long): String = "%.1f s".format(millis / 1000.0)
private fun oneLine(text: String): String = text.trim().replace(Regex("\\s+"), " ")
