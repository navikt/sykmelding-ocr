package org.example.no.nav.tsm.ocr.template.template

import tools.jackson.module.kotlin.jacksonObjectMapper
import tools.jackson.module.kotlin.readValue
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING

object TemplateFiles {
    private val mapper = jacksonObjectMapper()

    fun read(file: File): Template {
        val template = mapper.readValue<Template>(file)
        val directory = file.absoluteFile.parentFile
        fun resolve(path: String): String =
            if (path.isBlank()) path else directory.resolve(path).normalize().absolutePath
        return template.copy(
            templateBilde = resolve(template.templateBilde),
            referansepunkter = template.referansepunkter.map(::resolve),
        )
    }

    fun write(file: File, template: Template) {
        val destination = file.absoluteFile.toPath().normalize()
        val directory = destination.parent.toFile()
        fun relative(path: String): String =
            if (path.isBlank()) path else File(path).absoluteFile.normalize().toRelativeString(directory)
        val stored = template.copy(
            templateBilde = relative(template.templateBilde),
            referansepunkter = template.referansepunkter.map(::relative),
        )
        // Serialize first and replace only after writing succeeds, preserving an existing template on failure.
        val json = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(stored)
        val temporary = Files.createTempFile(destination.parent, ".template-", ".json")
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
