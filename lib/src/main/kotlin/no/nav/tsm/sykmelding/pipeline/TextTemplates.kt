package no.nav.tsm.sykmelding.pipeline

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import no.nav.tsm.sykmelding.ocr.TextPart
import no.nav.tsm.sykmelding.template.FIELD_NUMBER_ORDER
import no.nav.tsm.sykmelding.template.FieldDefinition
import no.nav.tsm.sykmelding.template.TemplateFiles
import tools.jackson.module.kotlin.jacksonObjectMapper
import tools.jackson.module.kotlin.readValue
import java.io.File

enum class FieldLayout {
    INLINE,
    BELOW,
    NONE,
}

@JsonIgnoreProperties(ignoreUnknown = true)
data class LineField(
    val number: String,
    val name: String,
    val field: FieldDefinition? = null,
    val layout: FieldLayout = FieldLayout.INLINE,
)

data class TextTemplate(val name: String, val fields: List<LineField> = emptyList()) {

    fun synced(catalog: List<FieldDefinition>): TextTemplate {
        val byId = catalog.associateBy { it.id }
        return copy(fields = fields.map { field -> field.field?.let { byId[it.id] }?.let { field.copy(field = it) } ?: field })
    }

    fun inFormOrder(): TextTemplate = copy(fields = fields.sortedWith(compareBy(FIELD_NUMBER_ORDER) { it.number }))

    fun matches(found: Map<LineField, List<List<TextPart>>>): Boolean = found.isNotEmpty()
}

class TextTemplates(val directory: File = File("lib/src/main/resources/text-templates")) {
    companion object {
        private val mapper = jacksonObjectMapper()
    }

    fun names(): List<String> =
        directory.listFiles { file -> file.isFile && file.extension == "json" && !file.name.startsWith(".") }.orEmpty()
            .map { it.nameWithoutExtension }.sorted()

    fun exists(name: String): Boolean = file(name).exists()

    fun read(name: String): TextTemplate = file(name).let { mapper.readValue<TextTemplate>(it).copy(name = it.nameWithoutExtension) }

    fun write(template: TextTemplate) {
        directory.mkdirs()
        TemplateFiles.writeJson(file(template.name), template)
    }

    private fun file(name: String): File {
        val trimmed = name.trim()
        require(trimmed.isNotEmpty() && !trimmed.startsWith(".") && trimmed.none { it in "/\\:" }) { "Not a usable template name: '$name'" }
        return directory.resolve("$trimmed.json")
    }
}
