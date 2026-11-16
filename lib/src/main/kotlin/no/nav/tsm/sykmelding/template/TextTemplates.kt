package no.nav.tsm.sykmelding.template

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import tools.jackson.module.kotlin.jacksonObjectMapper
import tools.jackson.module.kotlin.readValue
import java.io.File
import java.io.InputStream

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
}

class TextTemplates(val directory: File = File("lib/src/main/resources/text-templates")) {
    companion object {
        private val mapper = jacksonObjectMapper()

        fun fromResources(): List<TextTemplate> = resource("index.txt").bufferedReader().useLines { lines ->
            lines.map(String::trim).filter { it.endsWith(".json") }.toList()
        }.map { path -> resource(path).use { mapper.readValue<TextTemplate>(it).copy(name = path.removeSuffix(".json")) } }

        private fun resource(path: String): InputStream =
            requireNotNull(TextTemplates::class.java.getResourceAsStream("/text-templates/$path")) { "Missing /text-templates/$path on the classpath" }
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

    private fun file(name: String): File = directory.resolve("${TemplateFiles.fileName(name, "template")}.json")
}
