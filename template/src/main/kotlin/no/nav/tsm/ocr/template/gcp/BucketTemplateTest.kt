package no.nav.tsm.ocr.template.gcp

import no.nav.tsm.ocr.template.testing.TemplatePreviews
import no.nav.tsm.ocr.template.testing.alignmentPreview
import no.nav.tsm.ocr.template.testing.png
import no.nav.tsm.ocr.template.testing.previewScale
import no.nav.tsm.ocr.template.testing.resized
import no.nav.tsm.sykmelding.model.CheckboxState
import no.nav.tsm.sykmelding.ocr.CHECKBOX_RULE
import no.nav.tsm.sykmelding.ocr.CheckboxReading
import no.nav.tsm.sykmelding.ocr.FieldText
import no.nav.tsm.sykmelding.ocr.FieldSegment
import no.nav.tsm.sykmelding.ocr.Ocr
import no.nav.tsm.sykmelding.ocr.PageRead
import no.nav.tsm.sykmelding.ocr.TextRegion
import no.nav.tsm.sykmelding.ocr.readPage
import no.nav.tsm.sykmelding.ocr.skipsOcr
import no.nav.tsm.sykmelding.template.TemplateMatchResult
import no.nav.tsm.sykmelding.template.TemplateMatcher
import org.example.no.nav.tsm.ocr.template.template.Felter
import org.example.no.nav.tsm.ocr.template.template.PdfTemplateImages
import org.example.no.nav.tsm.ocr.template.template.Template
import org.example.no.nav.tsm.ocr.template.template.TemplateFiles
import org.example.no.nav.tsm.ocr.template.template.TemplateGroup
import org.example.no.nav.tsm.ocr.template.template.TemplateLibrary
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.File
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
                appendLine("Matched with lib's TemplateMatcher, as in production.")
                reads?.let { pages ->
                    appendLine()
                    appendLine("Fields read with lib's OCR, as production reads them: ${pages.sumOf { it.values.size }} on ${pages.size} page${plural(pages.size)}")
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
 * fields are read with lib's OCR right away ([readPage]), while the aligned pages are still in memory (they are dropped
 * afterwards; [reading] is called just before). Never throws: an unreadable
 * file becomes [PdfStatus.ERROR], and a match whose fields could not be read stays [PdfStatus.MATCHED] with the error
 * in its message.
 */
internal fun testAgainstTemplates(file: File, prepared: PreparedMatcher, reading: () -> Unit = {}): PdfTestResult {
    val started = System.nanoTime()
    fun elapsed() = (System.nanoTime() - started) / 1_000_000
    val pages = try { pageCount(file) } catch (error: Exception) {
        return PdfTestResult(PdfStatus.ERROR, "Could not read the file: ${error.message}", millis = elapsed())
    }
    return when (val result = prepared.matcher.matchPdf(file)) {
        is TemplateMatchResult.Matched -> {
            val outcomes = result.templateMatch.entries.mapIndexed { index, (template, aligned) -> outcome(result.template, index, template, aligned, prepared.previews) }
            reading()
            val ocrStarted = System.nanoTime()
            val reads = runCatching {
                val ocr = Ocr()
                result.templateMatch.map { (template, aligned) -> ocr.readPage(aligned, template.felter) }
            }
            val ocrMillis = (System.nanoTime() - ocrStarted) / 1_000_000
            val error = reads.exceptionOrNull()?.let { "The fields could not be read: ${it.message ?: it}" }
            PdfTestResult(PdfStatus.MATCHED, error.orEmpty(), pages, outcomes, elapsed(), reads.getOrNull(), ocrMillis, error)
        }
        TemplateMatchResult.NoMatch -> PdfTestResult(PdfStatus.NO_MATCH, "No template has every one of its pages in this file.", pages, millis = elapsed())
        is TemplateMatchResult.Error -> PdfTestResult(PdfStatus.ERROR, result.exception.message ?: result.exception.toString(), pages, millis = elapsed())
        is TemplateMatchResult.TooManyPages -> PdfTestResult(PdfStatus.NO_MATCH,
            "${result.pages} pages: the matcher takes at most ${result.maxPages}, so the file is not matched against any template.", pages, millis = elapsed())
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
