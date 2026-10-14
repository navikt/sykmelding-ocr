package no.nav.tsm.ocr.template

import org.example.no.nav.tsm.ocr.template.template.Felter
import org.example.no.nav.tsm.ocr.template.template.Template
import org.example.no.nav.tsm.ocr.template.template.TemplateFiles
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse

class TemplateFilesTest {
    @org.junit.jupiter.api.io.TempDir
    lateinit var directory: File

    private fun template() = Template(
        "Norsk mal – æøå", "En beskrivelse",
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
    fun `legacy reference points are retained while freehand masks are dropped on save`() {
        val file = File(directory, "legacy.json")
        file.writeText(
            """{"navn":"Mal","beskrivelse":"","felter":[],"templateBilde":"/images/mal.png",
                "referansepunkter":["/images/navlogo.png"],"masks":[{"x":1,"y":2,"bredde":3,"hoyde":4}]}"""
        )
        val loaded = TemplateFiles.read(file)
        assertEquals("/images/mal.png", loaded.templateBilde)
        assertEquals(listOf("/images/navlogo.png"), loaded.referansepunkter)
        TemplateFiles.write(file, loaded)
        assertEquals(loaded, TemplateFiles.read(file))
        assertFalse(file.readText().contains("masks"))
    }

    @Test
    fun `image and reference paths survive save reopen and moving a page folder`() {
        val file = File(directory, "mal.json")
        val original = template().copy(referansepunkter = listOf(File(directory, "reference.png").absolutePath))
        TemplateFiles.write(file, original)
        assertEquals(original, TemplateFiles.read(file))
        assertFalse(file.readText().contains(directory.absolutePath))
        val moved = File(directory, "moved").apply { mkdir() }
        val loaded = TemplateFiles.read(file.copyTo(File(moved, file.name)))
        assertEquals(File(moved, "malbilde.png").absolutePath, loaded.templateBilde)
        assertEquals(listOf(File(moved, "reference.png").absolutePath), loaded.referansepunkter)
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
