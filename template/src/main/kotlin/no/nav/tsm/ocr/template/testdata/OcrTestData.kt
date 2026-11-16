package no.nav.tsm.ocr.template.testdata

import no.nav.tsm.sykmelding.template.TemplateFiles
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory
import tools.jackson.module.kotlin.jacksonObjectMapper
import tools.jackson.module.kotlin.readValue
import java.awt.image.BufferedImage
import java.io.File

internal class OcrTestData(val image: BufferedImage, val dpi: Int) {

    fun write(directory: File, name: String) {
        val file = validName(name)
        directory.mkdirs()
        PDDocument().use { document ->
            val width = image.width * POINTS_PER_INCH / dpi
            val height = image.height * POINTS_PER_INCH / dpi
            val page = PDPage(PDRectangle(width, height)).also(document::addPage)
            val pixels = LosslessFactory.createFromImage(document, image)
            PDPageContentStream(document, page).use { it.drawImage(pixels, 0f, 0f, width, height) }
            document.save(File(directory, "$file.pdf"))
        }
    }

    companion object {
        private val mapper = jacksonObjectMapper()
        private const val POINTS_PER_INCH = 72f

        val DIRECTORY = File("lib/src/test/resources/ocr-testdata")

        fun defaultValues(directory: File): Map<String, String> =
            File(directory, "default-values.json").takeIf { it.isFile }?.let { mapper.readValue<Map<String, String>>(it) }.orEmpty()

        fun validName(name: String): String = TemplateFiles.fileName(name, "test data")
    }
}
