package no.nav.tsm.sykmelding.template

import tools.jackson.module.kotlin.jacksonObjectMapper
import tools.jackson.module.kotlin.readValue
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.util.UUID

data class FieldDefinition(val id: String, val navn: String, val type: FieldType = FieldType.TEXT)

/**
 * Shared definitions have no coordinates. Template assignments use the definition's stable ID.
 *
 * Kept in the repository next to the templates, like [TemplateLibrary], so every editor shares and commits them.
 */
class FieldCatalog(val file: File = File("lib/src/main/resources/fields.json")) {
    private val mapper = jacksonObjectMapper()

    fun read(): List<FieldDefinition> {
        if (!file.exists()) return emptyList()
        return mapper.readValue<List<FieldDefinition>>(file).also { definitions ->
            require(definitions.all { it.id.isNotBlank() && it.navn.isNotBlank() }) { "Invalid field catalog" }
            require(definitions.map { it.id }.distinct().size == definitions.size) { "Duplicate field IDs" }
        }
    }

    fun add(name: String, type: FieldType = FieldType.TEXT): FieldDefinition {
        val definitions = read()
        val trimmed = name.trim()
        require(trimmed.isNotEmpty()) { "Feltnavnet kan ikke være tomt." }
        require(definitions.none { it.navn.equals(trimmed, ignoreCase = true) }) { "Feltet finnes allerede i katalogen." }
        val field = FieldDefinition(UUID.randomUUID().toString(), trimmed, type)
        write(definitions + field)
        return field
    }

    fun rename(id: String, name: String) {
        val definitions = read()
        require(definitions.any { it.id == id }) { "Feltet finnes ikke lenger i katalogen." }
        val trimmed = name.trim()
        require(trimmed.isNotEmpty()) { "Feltnavnet kan ikke være tomt." }
        require(definitions.none { it.id != id && it.navn.equals(trimmed, ignoreCase = true) }) { "Feltet finnes allerede i katalogen." }
        write(definitions.map { if (it.id == id) it.copy(navn = trimmed) else it })
    }

    fun remove(id: String) = write(read().filterNot { it.id == id })

    fun setType(id: String, type: FieldType) {
        val definitions = read()
        require(definitions.any { it.id == id }) { "Feltet finnes ikke lenger i katalogen." }
        write(definitions.map { if (it.id == id) it.copy(type = type) else it })
    }

    private fun write(definitions: List<FieldDefinition>) {
        val destination = file.absoluteFile.toPath()
        val json = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(definitions)
        Files.createDirectories(destination.parent)
        val temporary = Files.createTempFile(destination.parent, ".fields-", ".json")
        try {
            Files.writeString(temporary, json)
            try {
                Files.move(temporary, destination, ATOMIC_MOVE, REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary, destination, REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temporary)
        }
    }
}
