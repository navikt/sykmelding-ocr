package no.nav.tsm.sykmelding.template

import com.fasterxml.jackson.annotation.JsonInclude
import tools.jackson.module.kotlin.jacksonObjectMapper
import tools.jackson.module.kotlin.readValue
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.util.*

@JsonInclude(JsonInclude.Include.NON_NULL)
data class FieldDefinition(
    val id: String,
    val navn: String,
    val type: FieldType = FieldType.TEXT,
    val number: String? = null,
    val description: String? = null,
    val receivedSykmeldingName: String? = null,
)

private val FIELD_NUMBER = Regex("""\d+(\.\d+)*""")

val FIELD_NUMBER_ORDER: Comparator<String?> = nullsLast(Comparator<String> { a, b -> compareNumbers(numberParts(a), numberParts(b)) })

private fun numberParts(number: String): List<Int> = number.split('.').map { it.trim().toIntOrNull() ?: Int.MAX_VALUE }

private fun compareNumbers(a: List<Int>, b: List<Int>): Int {
    for ((x, y) in a.zip(b)) if (x != y) return x.compareTo(y)
    return a.size.compareTo(b.size)
}

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
            val numbered = definitions.filter { it.number != null }
            val malformed = numbered.filterNot { FIELD_NUMBER.matches(it.number!!) }
            require(malformed.isEmpty()) { "Invalid field numbers: ${malformed.joinToString { "${it.navn} '${it.number}'" }}" }
            val duplicates = numbered.sortedWith(compareBy(FIELD_NUMBER_ORDER) { it.number }).zipWithNext()
                .filter { (a, b) -> FIELD_NUMBER_ORDER.compare(a.number, b.number) == 0 }
            require(duplicates.isEmpty()) {
                "Duplicate field numbers: ${duplicates.joinToString { (a, b) -> "${a.navn} ${a.number} and ${b.navn} ${b.number}" }}"
            }
        }
    }

    fun add(
        name: String,
        type: FieldType = FieldType.TEXT,
        number: String? = null,
        description: String? = null,
        receivedSykmeldingName: String? = null,
    ): FieldDefinition {
        val definitions = read()
        val trimmed = name.trim()
        require(trimmed.isNotEmpty()) { "Feltnavnet kan ikke være tomt." }
        require(definitions.none { it.navn.equals(trimmed, ignoreCase = true) }) { "Feltet finnes allerede i katalogen." }
        val field = FieldDefinition(UUID.randomUUID().toString(), trimmed, type, checkedNumber(definitions, null, number),
            description.trimmedOrNull(), receivedSykmeldingName.trimmedOrNull())
        write(definitions + field)
        return field
    }

    fun setDetails(id: String, number: String?, description: String?, receivedSykmeldingName: String?) {
        val definitions = read()
        require(definitions.any { it.id == id }) { "Feltet finnes ikke lenger i katalogen." }
        val checked = checkedNumber(definitions, id, number)
        write(definitions.map {
            if (it.id == id) it.copy(number = checked, description = description.trimmedOrNull(),
                receivedSykmeldingName = receivedSykmeldingName.trimmedOrNull()) else it
        })
    }

    private fun String?.trimmedOrNull(): String? = this?.trim()?.ifEmpty { null }


    private fun checkedNumber(definitions: List<FieldDefinition>, id: String?, number: String?): String? {
        val trimmed = number.trimmedOrNull() ?: return null
        require(FIELD_NUMBER.matches(trimmed)) { "Feltnummeret må være tall med punktum mellom, som 3.1.2." }
        val other = definitions.firstOrNull { it.id != id && it.number != null && FIELD_NUMBER_ORDER.compare(it.number, trimmed) == 0 }
        require(other == null) { "Feltnummer $trimmed er allerede ${other?.navn}." }
        return trimmed
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
