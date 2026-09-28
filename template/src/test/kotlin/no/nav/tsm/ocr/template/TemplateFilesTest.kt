package no.nav.tsm.ocr.template

import org.example.no.nav.tsm.ocr.template.template.Felter
import org.example.no.nav.tsm.ocr.template.template.Template
import org.example.no.nav.tsm.ocr.template.template.TemplateFiles
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse

class TemplateFilesTest {
    @TempDir
    lateinit var directory: File

    private fun template() = Template(
        "Norsk mal – æøå", listOf(File(directory, "navlogo.png").absolutePath), "En beskrivelse",
        listOf(Felter(50, 20, 100, 60, "Fornavn", "name")),
        File(directory, "malbilde.png").absolutePath,
    )

    @Test
    fun `template and edits survive saving and reopening`() {
        val file = File(directory, "mal.json")
        val original = template()
        TemplateFiles.write(file, original)
        assertEquals(original, TemplateFiles.read(file))
        val updated = original.copy(navn = "Endret", felter = original.felter.map { it.copy(x = 70) })
        TemplateFiles.write(file, updated)
        assertEquals(updated, TemplateFiles.read(file))
    }

    @Test
    fun `asset paths resolve relative to template location after moving`() {
        val file = File(directory, "mal.json")
        TemplateFiles.write(file, template())
        assertFalse(file.readText().contains(directory.absolutePath))
        val movedDirectory = File(directory, "moved").apply { mkdir() }
        val moved = file.copyTo(File(movedDirectory, "mal.json"))
        val loaded = TemplateFiles.read(moved)
        assertEquals(File(movedDirectory, "malbilde.png").absolutePath, loaded.templateBilde)
        assertEquals(listOf(File(movedDirectory, "navlogo.png").absolutePath), loaded.referansepunkter)
    }

    @Test
    fun `existing absolute asset paths are accepted`() {
        val file = File(directory, "mal.json")
        file.writeText("""
            {"navn":"Mal","beskrivelse":"","felter":[],
             "referansepunkter":["/images/navlogo.png"],"templateBilde":"/images/mal.png"}
        """.trimIndent())
        val loaded = TemplateFiles.read(file)
        assertEquals("/images/mal.png", loaded.templateBilde)
        assertEquals(listOf("/images/navlogo.png"), loaded.referansepunkter)
    }

    @Test
    fun `invalid and incomplete templates are rejected`() {
        val file = File(directory, "invalid.json")
        for (json in listOf("not json", "{}", "[]")) {
            file.writeText(json)
            assertFails { TemplateFiles.read(file) }
        }
    }
}
