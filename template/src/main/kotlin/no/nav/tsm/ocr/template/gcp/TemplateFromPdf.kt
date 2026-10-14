package no.nav.tsm.ocr.template.gcp

import org.apache.pdfbox.Loader
import org.example.no.nav.tsm.ocr.template.template.PageRotation
import org.example.no.nav.tsm.ocr.template.template.PdfTemplateImages
import org.example.no.nav.tsm.ocr.template.template.TemplateGroup
import org.example.no.nav.tsm.ocr.template.template.TemplateLibrary
import java.io.File

/** Where "Create template from" puts the chosen pages. */
internal sealed interface TemplateTarget {
    /** A new template folder [name] in the library; the pages become its page-1, page-2, … */
    data class New(val name: String) : TemplateTarget

    /** More pages for [group], numbered from [firstNumber] on. */
    data class Existing(val group: TemplateGroup, val firstNumber: Int) : TemplateTarget
}

/**
 * What "Create template from" was asked to do. [pages] are one-based PDF page numbers, in template page order.
 * [rotations] turns or straightens pages before their template image is saved.
 */
internal data class TemplateFromPdfRequest(
    val target: TemplateTarget,
    val pages: List<Int>,
    val openInEditor: Boolean = false,
    val rotations: Map<Int, PageRotation> = emptyMap(),
)

/** The template after "Create template from", and the page JSON files that were added to it, in request order. */
internal class CreatedTemplate(val group: TemplateGroup, val pageFiles: List<File>)

/**
 * Renders [pages] of [pdf] at 400 DPI, as the template editor's PDF import does, turns each by its entry in
 * [rotations], and adds it as a page of [target] with that image and no fields yet. Either everything is added or
 * nothing: if a page fails, the pages added before it, and a
 * new template's folder, are removed again. [progress] gets (pages done, pages) before each page and at the end.
 * Call off the EDT; each page takes about a second.
 */
internal fun createTemplateFromPdf(
    library: TemplateLibrary,
    pdf: ByteArray,
    target: TemplateTarget,
    pages: List<Int>,
    rotations: Map<Int, PageRotation> = emptyMap(),
    progress: (Int, Int) -> Unit = { _, _ -> },
): CreatedTemplate = Loader.loadPDF(pdf).use { document ->
    require(pages.isNotEmpty()) { "Choose at least one page." }
    require(pages.distinct().size == pages.size) { "A PDF page can only be added once." }
    pages.forEach { require(it in 1..document.numberOfPages) { "The PDF has no page $it." } }
    val first = when (target) {
        is TemplateTarget.New -> 1
        is TemplateTarget.Existing -> target.firstNumber.also { require(it > 0) { "Page numbers start at 1." } }
    }
    val group = when (target) {
        is TemplateTarget.New -> {
            val name = library.validName(target.name)
            require(!library.exists(name)) { "A template named $name already exists. Choose another name, or add the pages to it." }
            library.create(name)
        }
        is TemplateTarget.Existing -> library.read(target.group.file).also { current ->
            val taken = pages.indices.map { first + it }.filterNot { library.isPageNumberFree(current, it) }
            require(taken.isEmpty()) {
                "${current.manifest.navn} already has page ${taken.joinToString(", ")}. Choose another first page number."
            }
        }
    }
    val added = mutableListOf<File>()
    try {
        pages.forEachIndexed { index, page ->
            progress(index, pages.size)
            val rotation = rotations[page]?.takeUnless { it.isNone() }
            val image = PdfTemplateImages.render(document, page).let { rotation?.apply(it) ?: it }
            added += library.addImagePage(group, image, first + index, "Fra PDF-side $page" + (rotation?.let { ", ${it.describe()}" } ?: ""))
        }
        progress(pages.size, pages.size)
    } catch (error: Throwable) {
        added.asReversed().forEach { page -> runCatching { library.deletePage(group, page) }.onFailure(error::addSuppressed) }
        if (target is TemplateTarget.New) runCatching { library.deleteEmpty(group) }.onFailure(error::addSuppressed)
        throw error
    }
    CreatedTemplate(library.read(group.file), added)
}
