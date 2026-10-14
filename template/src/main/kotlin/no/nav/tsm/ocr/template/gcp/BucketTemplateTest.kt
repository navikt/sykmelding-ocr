package no.nav.tsm.ocr.template.gcp

import no.nav.tsm.ocr.template.testing.BatchResult
import no.nav.tsm.ocr.template.testing.BatchSettings
import no.nav.tsm.ocr.template.testing.BatchStatus
import no.nav.tsm.ocr.template.testing.DocumentPages
import no.nav.tsm.ocr.template.testing.DocumentText
import no.nav.tsm.ocr.template.testing.FeatureSettings
import no.nav.tsm.ocr.template.testing.TemplateCache
import no.nav.tsm.ocr.template.testing.pageCount
import no.nav.tsm.ocr.template.testing.testTemplatePage
import org.example.no.nav.tsm.ocr.template.template.Template
import org.example.no.nav.tsm.ocr.template.template.TemplateFiles
import org.example.no.nav.tsm.ocr.template.template.TemplateLibrary
import java.awt.image.BufferedImage
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.imageio.ImageIO

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

internal class PreparedTemplate(val page: LibraryPage, val reference: BufferedImage, val cache: TemplateCache)

/** Every testable template page, prepared with [features]. [problems] lists pages that could not be prepared. */
internal class PreparedTemplates(
    val library: LibraryPages,
    val features: FeatureSettings,
    val templates: List<PreparedTemplate>,
    val problems: List<String>,
) : AutoCloseable {
    /** The first page of each template, in library order: where a document's search for a template starts. */
    val firstPages: List<PreparedTemplate> get() = templates.filter { it.page.position == 1 }

    /** Page [position] of the template in [templateFile], if it could be prepared. */
    fun page(templateFile: File, position: Int): PreparedTemplate? =
        templates.firstOrNull { it.page.templateFile == templateFile && it.page.position == position }

    /** Whether these are still valid for the library as it is now, tested with [settings]. */
    fun matches(current: LibraryPages, settings: FeatureSettings): Boolean = library.fingerprint == current.fingerprint && features == settings

    override fun close() = templates.forEach { it.cache.close() }
}

/** Reads each template image and detects its features on [pool]; [progress] gets (prepared, total). */
internal fun prepareTemplates(
    library: LibraryPages,
    features: FeatureSettings,
    pool: ExecutorService,
    progress: (Int, Int) -> Unit = { _, _ -> },
): PreparedTemplates {
    val done = AtomicInteger()
    val futures = library.pages.map { page ->
        pool.submit(Callable {
            try {
                val reference = requireNotNull(ImageIO.read(File(page.template.templateBilde))) { "Unsupported template image format" }
                PreparedTemplate(page, reference, TemplateCache(reference, features))
            } finally {
                progress(done.incrementAndGet(), library.pages.size)
            }
        })
    }
    val prepared = mutableListOf<PreparedTemplate>()
    val problems = mutableListOf<String>()
    try {
        futures.zip(library.pages).forEach { (future, page) ->
            try {
                prepared += future.get()
            } catch (error: ExecutionException) {
                problems += "${page.label}: ${(error.cause ?: error).message}"
            }
        }
    } catch (error: Throwable) {
        prepared.forEach { it.cache.close() }
        throw error
    }
    return PreparedTemplates(library, features, prepared, problems)
}

internal enum class PdfStatus(private val label: String) {
    QUEUED("Queued"), RUNNING("Running …"), ALL_PAGES("✓ All pages"), SOME_PAGES("◐ Some pages"), NO_MATCH("✗ No match"), ERROR("Error");

    /** A final result rather than a run in progress. */
    val finished: Boolean get() = ordinal >= ALL_PAGES.ordinal

    override fun toString() = label
}

/** How a document page was tested. */
internal enum class PageRole(private val label: String) {
    /** Against the first page of each template, in library order, until one matched. */
    START("Start"),

    /** Against one page only: the next page of the template that an earlier page of the document started. */
    NEXT("Next page");

    override fun toString() = label
}

/**
 * PDF page [page] tested against [template] as [role]; [result] is the matching outcome for that page, and
 * its [BatchResult.text] the required-text check, if any.
 */
internal class TemplateOutcome(val page: Int, val template: LibraryPage, val role: PageRole, val result: BatchResult) {
    val matched: Boolean get() = result.status == BatchStatus.MATCH

    /** Rejected for lacking the required text, without being aligned. */
    val rejectedByText: Boolean get() = result.text?.rejects == true && !result.aligned
}

/** The most template form lines on the aligned page first, then the largest share of agreeing matches. */
private val BEST_FIRST: Comparator<TemplateOutcome> =
    compareByDescending<TemplateOutcome> { it.result.lineFit ?: -1.0 }.thenByDescending { it.result.agreement ?: -1.0 }

/** Closest first among non-matches: pages that could be aligned (they show the template on top), then as [BEST_FIRST]. */
private val CLOSEST_FIRST: Comparator<TemplateOutcome> = compareByDescending<TemplateOutcome> { it.result.aligned }.then(BEST_FIRST)

/**
 * One document page. [tested] is in the order the template pages were tried. As [PageRole.NEXT], [expected] is the
 * only template page it may be. [skipped] says why it could not be tested at all.
 */
internal class PageResult(
    val page: Int,
    val role: PageRole,
    tested: List<TemplateOutcome>,
    val expected: LibraryPage? = null,
    val skipped: String? = null,
) {
    /** The template page this document page is: the first one tried that matched. */
    val match: TemplateOutcome? = tested.firstOrNull { it.matched }

    /** [match] first, then the other tested pages, closest first. */
    val outcomes: List<TemplateOutcome> = listOfNotNull(match) + tested.filter { it !== match }.sortedWith(CLOSEST_FIRST)

    /** [match], or else the outcome that came closest; its preview is kept when results are [compacted][PdfTestResult.compact]. */
    val shown: TemplateOutcome? get() = outcomes.firstOrNull()

    /** For the file list: the matching template page, "✗ page" for an expected page that did not match, or "–". */
    val shortText: String
        get() = match?.template?.label ?: expected?.let { "✗ ${it.label}" } ?: if (skipped != null) "?" else "–"

    fun describe(): String = "Page $page: " + when {
        skipped != null -> "not tested — $skipped"
        match != null && role == PageRole.START -> "${match.template.label} — first page of the template (${metrics(match.result)})"
        match != null -> "${match.template.label} — the template's next page (${metrics(match.result)})"
        role == PageRole.NEXT && expected != null ->
            "✗ is not ${expected.label}, the template's next page (${outcomes.firstOrNull()?.result?.let(::metrics) ?: "not tested"})"
        outcomes.isNotEmpty() && outcomes.all { it.rejectedByText } -> "no template's first page — none of their required text is on the page"
        else -> "no template's first page matched" + (outcomes.firstOrNull()?.takeIf { it.result.status != BatchStatus.ERROR }
            ?.let { "; closest ${it.template.label} (${metrics(it.result)})" } ?: "")
    }

    fun withOutcomes(transform: (TemplateOutcome) -> TemplateOutcome): PageResult =
        PageResult(page, role, outcomes.map(transform), expected, skipped)
}

/**
 * One document's result. While [PdfStatus.RUNNING], [done] of [total] pages are decided.
 * [ALL_PAGES][PdfStatus.ALL_PAGES]: every page matched a template page.
 */
internal class PdfTestResult(
    val status: PdfStatus,
    val message: String = "",
    val pages: Int? = null,
    val pageResults: List<PageResult> = emptyList(),
    val millis: Long = 0,
    val settings: BatchSettings? = null,
    val done: Int = 0,
    val total: Int = 0,
) {
    val statusText: String
        get() = when {
            status == PdfStatus.RUNNING && total > 0 -> "Running page ${minOf(done + 1, total)}/$total …"
            status == PdfStatus.RUNNING && message.isNotEmpty() -> message
            else -> status.toString()
        }

    /** The template page each document page matched, in page order. */
    val templatesPerPage: String get() = pageResults.joinToString(", ") { it.shortText }

    /** A copy that keeps only the preview of each page's [PageResult.shown] outcome, so long runs do not fill the heap. */
    fun compact(): PdfTestResult = PdfTestResult(status, message, pages,
        pageResults.map { page -> page.withOutcomes { if (it === page.shown) it else it.withoutPreview() } }, millis, settings, done, total)

    /** A few lines: what each page is, or why it is none of the templates. */
    fun summary(): String = buildString {
        when (status) {
            PdfStatus.QUEUED -> appendLine("Queued for testing.")
            PdfStatus.RUNNING -> appendLine(if (total > 0) "Testing: $done of $total pages done …" else message.ifEmpty { "Testing …" })
            else -> {
                append(status)
                pages?.let { append(" — $it page${plural(it)}") }
                val tests = pageResults.sumOf { it.outcomes.size }
                if (tests > 0) append(", $tests test${plural(tests)}")
                if (millis > 0) append(" in %.1f s".format(millis / 1000.0))
                appendLine()
                pageResults.forEach { appendLine("  ${it.describe()}") }
                if (message.isNotEmpty()) appendLine(message)
                settings?.let { appendLine("Settings: ${describeSettings(it)}") }
            }
        }
    }
}

private fun TemplateOutcome.withoutPreview(): TemplateOutcome {
    val full = result
    if (full.preview == null) return this
    return TemplateOutcome(page, template, role, BatchResult(
        file = full.file,
        status = full.status,
        message = full.message,
        page = full.page,
        inliers = full.inliers,
        matches = full.matches,
        transform = full.transform,
        previewScale = full.previewScale,
        millis = full.millis,
        outcomes = full.outcomes,
        lineFit = full.lineFit,
        aligned = full.aligned,
        text = full.text,
    ))
}

private fun metrics(result: BatchResult): String = listOfNotNull(
    result.lineFit?.let { "form lines %.0f %%".format(100 * it) },
    result.agreement?.let { "${result.inliers} / ${result.matches} agree" },
).joinToString(", ").ifEmpty { result.message.lineSequence().firstOrNull().orEmpty().take(100) }

/** [BatchSettings.description] without the page, which differs per test here. */
internal fun describeSettings(settings: BatchSettings): String =
    "${settings.features.description}, ${settings.model.toString().substringBefore(" +")}, " +
        "form lines ≥ %.0f %%".format(100 * settings.minLineFit) +
        if (settings.checkText) ", required text checked first" else ", required text not checked"

/**
 * Matches the pages of [file] to template pages, in order:
 *  1. A page is tested against the first page of each template, in library order, until one matches. If none does,
 *     the next document page is tried at step 1.
 *  2. Once page n matches the first page of a template, page n + 1 must be its page 2, n + 2 its page 3, and so on:
 *     each is tested against that one template page only (these tests run at the same time on [pool]). A page that
 *     does not match its expected page is reported as such; the following pages still follow the template.
 *  3. After the template's last page, the next document page starts again at step 1, so a document with several forms
 *     in it is matched form by form.
 * The first pages in step 1 are also tried [pool]-parallel, but the first matching one in library order is chosen.
 * A template page with required text is aligned only to pages that have the text; each page is read once for all of
 * them. Each page is rendered and its features detected once for all template pages it is tested against. Everything
 * but the page comes from [settings]. Never throws: an unreadable file becomes [PdfStatus.ERROR].
 * Returns null when [cancelled] before every page was decided. [progress] gets (decided pages, pages).
 */
internal fun testAgainstTemplates(
    file: File,
    templates: PreparedTemplates,
    settings: BatchSettings,
    pool: ExecutorService,
    cancelled: () -> Boolean = { false },
    progress: (Int, Int) -> Unit = { _, _ -> },
): PdfTestResult? {
    val started = System.nanoTime()
    fun elapsed() = (System.nanoTime() - started) / 1_000_000
    val pages = try { pageCount(file) } catch (error: Exception) {
        return PdfTestResult(PdfStatus.ERROR, "Could not read the file: ${error.message}", millis = elapsed(), settings = settings)
    }
    val firstPages = templates.firstPages
    if (firstPages.isEmpty()) {
        return PdfTestResult(PdfStatus.ERROR, "No template has a first page that can be tested.", pages, settings = settings)
    }
    val texts = DocumentText(file)
    return DocumentPages(file, settings.features).use { documentPages ->
        val results = mutableListOf<PageResult>()
        progress(0, pages)
        var page = 1
        while (page <= pages) {
            val start = findStart(file, page, firstPages, settings, pool, texts, documentPages, cancelled) ?: return null
            val anchor = start.match?.template
            results += start
            progress(results.size, pages)
            texts.release(page)
            documentPages.release(page)
            page++
            if (anchor == null) continue
            // Which document page is which template page is now known: page n + i is the template's page 1 + i.
            class Follower(val page: Int, val position: Int, val expected: PreparedTemplate?, val test: Future<TemplateOutcome?>?)
            val followers = (1..anchor.templateSize - anchor.position).map { offset -> page + offset - 1 to anchor.position + offset }
                .filter { (documentPage, _) -> documentPage <= pages }
                .map { (documentPage, position) ->
                    val expected = templates.page(anchor.templateFile, position)
                    Follower(documentPage, position, expected,
                        expected?.let { submit(pool, file, documentPage, it, PageRole.NEXT, settings, texts, documentPages, cancelled) })
                }
            // Every test is awaited before giving up, so none still reads the file when the caller deletes it.
            val next = followers.map { follower ->
                val expected = follower.expected
                val test = follower.test
                if (expected == null || test == null) {
                    PageResult(follower.page, PageRole.NEXT, emptyList(),
                        skipped = "page ${follower.position} of ${anchor.group} has no template image that could be prepared")
                } else {
                    await(test, file, follower.page, expected, PageRole.NEXT)?.let { PageResult(follower.page, PageRole.NEXT, listOf(it), expected.page) }
                }
            }
            followers.forEach { texts.release(it.page); documentPages.release(it.page) }
            if (next.any { it == null }) return null
            next.forEach { results += checkNotNull(it); progress(results.size, pages) }
            page += followers.size
        }
        val matched = results.count { it.match != null }
        val outcomes = results.flatMap { it.outcomes }
        val status = when {
            matched == 0 && outcomes.isNotEmpty() && outcomes.all { it.result.status == BatchStatus.ERROR } -> PdfStatus.ERROR
            matched == 0 -> PdfStatus.NO_MATCH
            matched == pages -> PdfStatus.ALL_PAGES
            else -> PdfStatus.SOME_PAGES
        }
        val message = if (status == PdfStatus.ERROR) outcomes.first().result.message else ""
        PdfTestResult(status, message, pages, results, elapsed(), settings)
    }
}

/**
 * Step 1 for document page [page]: the [firstPages] are queued in library order and the first matching one is chosen;
 * those not started by then are skipped. Null when [cancelled] before every first page was tried without a match.
 */
private fun findStart(file: File, page: Int, firstPages: List<PreparedTemplate>, settings: BatchSettings, pool: ExecutorService,
                      texts: DocumentText, documentPages: DocumentPages, cancelled: () -> Boolean): PageResult? {
    val found = AtomicBoolean()
    val futures = firstPages.map { candidate ->
        submit(pool, file, page, candidate, PageRole.START, settings, texts, documentPages) { found.get() || cancelled() }
    }
    val tested = mutableListOf<TemplateOutcome>()
    for ((future, candidate) in futures.zip(firstPages)) {
        // Every future is awaited, also after a match, so no test still reads the file afterwards.
        val outcome = await(future, file, page, candidate, PageRole.START) ?: continue
        tested += outcome
        if (outcome.matched) found.set(true)
    }
    if (!found.get() && tested.size < firstPages.size) return null
    return PageResult(page, PageRole.START, tested)
}

/** Queues one test; it is skipped (its future gives null) if [skip] is true when it would start. */
private fun submit(pool: ExecutorService, file: File, page: Int, candidate: PreparedTemplate, role: PageRole, settings: BatchSettings,
                   texts: DocumentText, documentPages: DocumentPages, skip: () -> Boolean): Future<TemplateOutcome?> =
    pool.submit(Callable<TemplateOutcome?> {
        if (skip()) return@Callable null
        TemplateOutcome(page, candidate.page, role, testTemplatePage(candidate.page.template, candidate.reference, file, page, settings,
            documentPages, candidate.cache, texts))
    })

/** The test's outcome; a test that failed outside the pipeline's own error handling becomes an error outcome. */
private fun await(future: Future<TemplateOutcome?>, file: File, page: Int, candidate: PreparedTemplate, role: PageRole): TemplateOutcome? = try {
    future.get()
} catch (error: ExecutionException) {
    val cause = error.cause ?: error
    TemplateOutcome(page, candidate.page, role, BatchResult(file, BatchStatus.ERROR, cause.message ?: cause.toString(), page = page))
}

internal fun plural(count: Int) = if (count == 1) "" else "s"
