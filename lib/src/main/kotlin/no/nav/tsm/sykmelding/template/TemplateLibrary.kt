package org.example.no.nav.tsm.ocr.template.template

import no.nav.tsm.sykmelding.utils.tempDirectory
import no.nav.tsm.sykmelding.utils.writingToTemp
import tools.jackson.module.kotlin.jacksonObjectMapper
import tools.jackson.module.kotlin.readValue
import java.awt.image.BufferedImage
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.FileVisitResult
import java.nio.file.attribute.BasicFileAttributes
import java.util.UUID

data class TemplateManifest(
    val navn: String,
    val pages: List<String> = emptyList(),
)

data class TemplateGroup(val file: File, val manifest: TemplateManifest) {
    val pageFiles: List<File> get() = manifest.pages.map { file.parentFile.resolve(it) }
}

/**
 * The templates in [directory], by default the template sources (the editor runs from the repository root).
 * Use [fromResources] for the templates packaged with this library.
 */
class TemplateLibrary(val directory: File = File("lib/src/main/resources/templates")) {
    private val mapper = jacksonObjectMapper()

    companion object {
        /**
         * The templates packaged in this library's resources, for matching. Resources in a jar are not files, so the
         * files listed in /templates/index.txt (written by the build) are copied once to a folder in java.io.tmpdir,
         * which must be writable. Read only: changes are made to that copy and lost.
         */
        fun fromResources(): TemplateLibrary = TemplateLibrary(resourceCopy)

        private val resourceCopy: File by lazy {
            val target = tempDirectory("templates")
            try {
                val paths = resource("index.txt").bufferedReader().useLines { lines ->
                    lines.map(String::trim).filter(String::isNotEmpty).toList()
                }
                for (path in paths) {
                    val file = target.resolve(path).normalize()
                    require(file.startsWith(target) && file != target) { "Invalid path in /templates/index.txt: $path" }
                    resource(path).use { input ->
                        writingToTemp("copy the template file $path to $target") {
                            Files.createDirectories(file.parent)
                            Files.copy(input, file)
                        }
                    }
                }
            } catch (e: Exception) {
                // A partial copy is not reused: the next attempt starts again in a new folder.
                target.toFile().deleteRecursively()
                throw e
            }
            target.toFile()
        }

        private fun resource(path: String): InputStream =
            requireNotNull(TemplateLibrary::class.java.getResourceAsStream("/templates/$path")) {
                "Missing /templates/$path on the classpath"
            }
    }

    fun list(): List<TemplateGroup> {
        if (!directory.exists()) return emptyList()
        val folders = requireNotNull(directory.listFiles()) { "Could not read template folder: $directory" }
        return folders.filter { it.isDirectory && it.resolve("mal.json").isFile }
            .sortedBy { it.name }
            .map { read(it.resolve("mal.json")) }
    }

    fun read(file: File): TemplateGroup {
        val manifest = mapper.readValue<TemplateManifest>(file)
        require(manifest.navn.isNotBlank()) { "Template name is missing in $file" }
        require(manifest.pages.distinct().size == manifest.pages.size) { "Duplicate pages in $file" }
        manifest.pages.forEach { path ->
            require(path.matches(Regex("page-[1-9][0-9]*/page-[1-9][0-9]*\\.json"))) {
                "Invalid page reference in $file: $path"
            }
            val page = file.parentFile.resolve(path)
            require(page.parentFile.name + ".json" == page.name) { "Page folder and filename do not match: $path" }
            require(page.isFile) { "Missing template page: $page" }
        }
        return TemplateGroup(file.absoluteFile, manifest)
    }

    /** [name] trimmed, if it can be a template folder name; [create] uses it as is. */
    fun validName(name: String): String {
        val folderName = name.trim()
        require(folderName.isNotEmpty() && folderName !in listOf(".", "..") &&
            folderName.none { it == '/' || it == '\\' || it.isISOControl() }) {
            "Use a template folder name such as 03.2019, without path separators."
        }
        return folderName
    }

    /** Whether a template, or any other file, already has the folder [name]. */
    fun exists(name: String): Boolean = Files.exists(directory.resolve(name.trim()).toPath(), NOFOLLOW_LINKS)

    fun create(name: String): TemplateGroup {
        val folderName = validName(name)
        Files.createDirectories(directory.toPath())
        val folder = directory.resolve(folderName)
        Files.createDirectory(folder.toPath())
        val file = folder.resolve("mal.json")
        try {
            TemplateFiles.writeJson(file, TemplateManifest(folderName))
        } catch (e: Exception) {
            Files.delete(folder.toPath())
            throw e
        }
        return read(file)
    }

    fun nextPageNumber(group: TemplateGroup): Int =
        Math.addExact(read(group.file).manifest.pages.maxOfOrNull(::pageNumber) ?: 0, 1)

    private fun pageNumber(path: String): Int = path.substringBefore('/').removePrefix("page-").toInt()

    /** Whether a new page can be numbered [number]: no page in [group]'s mal.json has it, and no folder has its name. */
    fun isPageNumberFree(group: TemplateGroup, number: Int): Boolean =
        number > 0 && group.manifest.pages.none { pageNumber(it) == number } &&
            !Files.exists(group.file.parentFile.resolve("page-$number").toPath(), NOFOLLOW_LINKS)

    private fun requireAvailableNumber(group: TemplateGroup, number: Int) {
        require(number > 0) { "Page number must be greater than zero." }
        require(isPageNumberFree(group, number)) {
            "Page number $number is already in use. Choose an unused page number."
        }
    }

    fun addPage(group: TemplateGroup, number: Int? = null): File = createPage(group, number) { pageName, _ ->
        Template(pageName, "", emptyList(), "")
    }

    /** Adds a page whose template image is [image], saved as template.png (400 DPI) in the new page folder. */
    fun addImagePage(group: TemplateGroup, image: BufferedImage, number: Int? = null, description: String = ""): File =
        createPage(group, number) { pageName, folder ->
            val png = folder.resolve("template.png")
            PdfTemplateImages.writePng(image, png)
            Template(pageName, description, emptyList(), png.absolutePath)
        }

    /** Removes a template that has no pages left: its mal.json and its folder, which must then be empty. */
    fun deleteEmpty(group: TemplateGroup) {
        val current = read(group.file)
        val folder = current.file.parentFile
        require(folder.canonicalFile.parentFile == directory.canonicalFile) { "Template is not in this library: ${group.file}" }
        require(current.manifest.pages.isEmpty()) { "Template ${current.manifest.navn} still has pages." }
        Files.delete(current.file.toPath())
        Files.delete(folder.toPath())
    }

    fun importPage(group: TemplateGroup, source: File, number: Int? = null): File {
        val template = TemplateFiles.read(source)
        val image = template.templateBilde.takeIf { it.isNotBlank() }?.let { path ->
            File(path).canonicalFile.also { require(it.isFile) { "Missing template image: $path" } }
        }
        val references = template.referansepunkter.map { path ->
            File(path).canonicalFile.also { require(it.isFile) { "Missing reference point: $path" } }
        }
        return createPage(group, number) { _, folder ->
            template.copy(templateBilde = image?.let { asset ->
                val filename = "template" + if (asset.extension.isEmpty()) "" else ".${asset.extension}"
                folder.resolve(filename).also { Files.copy(asset.toPath(), it.toPath()) }.absolutePath
            }.orEmpty(), referansepunkter = references.mapIndexed { index, asset ->
                folder.resolve("reference-${index + 1}.${asset.extension.ifEmpty { "png" }}")
                    .also { Files.copy(asset.toPath(), it.toPath()) }.absolutePath
            })
        }
    }

    fun renumberPage(group: TemplateGroup, page: File, number: Int): File {
        val current = read(group.file)
        val target = requirePage(current, page)
        require(number > 0) { "Page number must be greater than zero." }
        val oldDirectory = target.parentFile
        if (oldDirectory.name == "page-$number") return target
        requireAvailableNumber(current, number)
        require(!Files.isSymbolicLink(target.toPath())) { "Cannot renumber a linked page JSON: $target" }
        val newDirectory = current.file.parentFile.resolve("page-$number")
        val destination = newDirectory.resolve("page-$number.json")
        val originalJson = target.readBytes()
        val template = TemplateFiles.read(target)
        val updated = template.copy(
            navn = if (template.navn == oldDirectory.name) newDirectory.name else template.navn,
            templateBilde = TemplateFiles.relocatePath(template.templateBilde, oldDirectory, newDirectory),
            referansepunkter = template.referansepunkter.map { TemplateFiles.relocatePath(it, oldDirectory, newDirectory) },
        )
        Files.move(oldDirectory.toPath(), newDirectory.toPath())
        var renamed = false
        try {
            Files.move(newDirectory.resolve(target.name).toPath(), destination.toPath())
            renamed = true
            TemplateFiles.write(destination, updated)
            TemplateFiles.writeJson(current.file, current.manifest.copy(
                pages = current.pageFiles.map {
                    if (it == target) "page-$number/page-$number.json"
                    else it.relativeTo(current.file.parentFile).invariantSeparatorsPath
                }.sortedBy(::pageNumber),
            ))
        } catch (e: Exception) {
            try {
                if (renamed) {
                    Files.write(destination.toPath(), originalJson)
                    Files.move(destination.toPath(), newDirectory.resolve(target.name).toPath())
                }
                Files.move(newDirectory.toPath(), oldDirectory.toPath())
            } catch (rollback: IOException) {
                e.addSuppressed(rollback)
            }
            throw e
        }
        return destination
    }

    private fun requirePage(group: TemplateGroup, page: File): File {
        require(group.file.parentFile.canonicalFile.parentFile == directory.canonicalFile) {
            "Template is not in this library: ${group.file}"
        }
        val target = page.absoluteFile.toPath().normalize().toFile()
        require(target in group.pageFiles) { "Page does not belong to this template: $page" }
        require(Files.isDirectory(target.parentFile.toPath(), NOFOLLOW_LINKS)) {
            "Not a regular page folder: ${target.parentFile}"
        }
        return target
    }

    fun deletePage(group: TemplateGroup, page: File) {
        val current = read(group.file)
        val folder = current.file.parentFile
        val target = requirePage(current, page)
        val index = current.pageFiles.indexOf(target)
        val pageDirectory = target.parentFile.toPath()
        val staged = folder.resolve(".deleted-page-${UUID.randomUUID()}").toPath()
        Files.move(pageDirectory, staged)
        try {
            TemplateFiles.writeJson(current.file, current.manifest.copy(
                pages = current.manifest.pages.filterIndexed { i, _ -> i != index },
            ))
        } catch (e: Exception) {
            try {
                Files.move(staged, pageDirectory)
            } catch (rollback: IOException) {
                e.addSuppressed(rollback)
            }
            throw e
        }
        try {
            Files.walkFileTree(staged, object : SimpleFileVisitor<Path>() {
                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    Files.delete(file)
                    return FileVisitResult.CONTINUE
                }

                override fun postVisitDirectory(dir: Path, error: IOException?): FileVisitResult {
                    if (error != null) throw error
                    Files.delete(dir)
                    return FileVisitResult.CONTINUE
                }
            })
        } catch (e: IOException) {
            throw IOException("Page removed from mal.json, but its remaining files could not be deleted at $staged", e)
        }
    }

    private fun createPage(group: TemplateGroup, requestedNumber: Int?, template: (String, File) -> Template): File {
        // Reload so another editor's page additions are not overwritten.
        val current = read(group.file)
        val folder = current.file.parentFile
        val number = requestedNumber ?: Math.addExact(current.manifest.pages.maxOfOrNull(::pageNumber) ?: 0, 1)
        requireAvailableNumber(current, number)
        val pageName = "page-$number"
        val pageDirectory = folder.resolve(pageName)
        Files.createDirectory(pageDirectory.toPath())
        val pageFile = pageDirectory.resolve("$pageName.json")
        try {
            TemplateFiles.write(pageFile, template(pageName, pageDirectory))
            TemplateFiles.writeJson(current.file, current.manifest.copy(
                pages = (current.manifest.pages + "$pageName/$pageName.json").sortedBy(::pageNumber),
            ))
        } catch (e: Exception) {
            val files = requireNotNull(pageDirectory.listFiles()) { "Could not read page folder: $pageDirectory" }
            files.forEach { Files.deleteIfExists(it.toPath()) }
            Files.delete(pageDirectory.toPath())
            throw e
        }
        return pageFile
    }
}
