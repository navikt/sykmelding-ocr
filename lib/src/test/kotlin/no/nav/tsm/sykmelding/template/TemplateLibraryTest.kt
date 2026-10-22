package no.nav.tsm.sykmelding.template

import org.example.no.nav.tsm.ocr.template.template.TemplateFiles
import org.example.no.nav.tsm.ocr.template.template.TemplateLibrary
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TemplateLibraryTest {
    @Test
    fun `the templates packaged in the resources are the template sources, with their pages and images`() {
        val packaged = TemplateLibrary.fromResources().list()
        val sources = TemplateLibrary(File("src/main/resources/templates")).list()
        assertEquals(sources.map { it.manifest }, packaged.map { it.manifest })
        assertEquals(listOf("0.2019", "03.2019"), packaged.map { it.manifest.navn })
        for (page in packaged.flatMap { it.pageFiles }) {
            assertTrue(File(TemplateFiles.read(page).templateBilde).isFile, "Image of $page")
        }
    }
}
