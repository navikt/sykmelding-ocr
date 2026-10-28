package no.nav.tsm.ocr.template.gcp

import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING

/** Sub-folders of [root] (default `~/ocr/gcp`) that bucket files are sorted into, one level deep. */
class SaveFolders(val root: File = DEFAULT_ROOT) {

    /** Existing sub-folders, sorted by name. */
    fun folders(): List<String> =
        root.listFiles()?.filter { it.isDirectory && !it.name.startsWith(".") }?.map { it.name }?.sortedWith(String.CASE_INSENSITIVE_ORDER)
            ?: emptyList()

    fun folder(name: String): File = File(root, validName(name))

    fun create(name: String): File = folder(name).apply {
        if (!isDirectory && !mkdirs()) error("Could not create folder $path")
    }

    fun target(folder: String, objectName: String): File = File(folder(folder), localName(objectName))

    /** Writes via a temporary file in the same folder, so a failed download never leaves a partial PDF. */
    fun save(folder: String, objectName: String, bytes: ByteArray): File {
        create(folder)
        val destination = target(folder, objectName).toPath()
        val temporary = Files.createTempFile(destination.parent, ".download-", ".part")
        try {
            Files.write(temporary, bytes)
            try {
                Files.move(temporary, destination, ATOMIC_MOVE, REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary, destination, REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temporary)
        }
        return destination.toFile()
    }

    /** Local file name → the folders it is already saved in. */
    fun savedIndex(): Map<String, List<String>> = buildMap<String, MutableList<String>> {
        for (folder in folders()) {
            File(root, folder).listFiles()?.filter { it.isFile && !it.name.startsWith(".") }?.forEach {
                getOrPut(it.name) { mutableListOf() }.add(folder)
            }
        }
    }

    companion object {
        val DEFAULT_ROOT = File(System.getProperty("user.home"), "ocr/gcp")

        /** Object names may contain `/`; keep the full name so files from different prefixes never collide. */
        fun localName(objectName: String): String = objectName.trim('/').replace('/', '_')

        fun validName(name: String): String {
            val trimmed = name.trim()
            require(trimmed.isNotEmpty()) { "Choose or type a folder name." }
            require(trimmed != "." && trimmed != ".." && !trimmed.startsWith(".")) { "Folder name cannot start with '.': $trimmed" }
            require(trimmed.none { it == '/' || it == '\\' || it == ':' || it.isISOControl() }) {
                "Folder name cannot contain '/', '\\' or ':': $trimmed"
            }
            return trimmed
        }
    }
}
