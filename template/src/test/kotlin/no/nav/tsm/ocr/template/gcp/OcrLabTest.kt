package no.nav.tsm.ocr.template.gcp

import no.nav.tsm.sykmelding.pipeline.TextTemplates
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts
import org.junit.jupiter.api.io.TempDir
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Container
import java.beans.PropertyChangeListener
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JLabel
import javax.swing.JTable
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OcrLabTest {
    @TempDir
    lateinit var directory: File

    @Test
    fun `OCR lists the parts of the page and rotation clears them`() {
        lateinit var lab: OcrLabView
        lateinit var run: JButton
        SwingUtilities.invokeAndWait {
            lab = lab(pdf())
            run = button(lab, "Run OCR")
        }
        awaitEnabled(run)
        SwingUtilities.invokeAndWait { run.doClick() }
        awaitEnabled(run)
        SwingUtilities.invokeAndWait {
            val regions = descendants(lab).filterIsInstance<JTable>().single { it.columnCount == 7 }
            assertTrue(regions.rowCount > 0)
            assertTrue((0 until regions.rowCount).joinToString(" ") { regions.getValueAt(it, 1).toString() }.contains("Test Person"))
            button(lab, "⟳").doClick()
            assertEquals(0, regions.rowCount)
            assertFalse(run.isEnabled, "Cannot read while the next image is rendering")
        }
        awaitEnabled(run)
    }

    @Test
    fun `pages can be turned once the PDF has been read`() {
        lateinit var run: JButton
        lateinit var previous: JButton
        lateinit var next: JButton
        SwingUtilities.invokeAndWait {
            val lab = lab(pdf(pages = 2))
            run = button(lab, "Run OCR")
            previous = button(lab, "◀ Page")
            next = button(lab, "Page ▶")
            assertFalse(previous.isEnabled || next.isEnabled, "No paging before the page count is known")
        }
        awaitEnabled(run)
        SwingUtilities.invokeAndWait {
            assertFalse(previous.isEnabled, "On the first page")
            assertTrue(next.isEnabled, "There is a second page")
            next.doClick()
        }
        awaitEnabled(run)
        SwingUtilities.invokeAndWait {
            assertTrue(previous.isEnabled, "On the second page")
            assertFalse(next.isEnabled, "There is no third page")
        }
    }

    @Test
    fun `a PDF that cannot be read is reported in the status line`() {
        lateinit var lab: OcrLabView
        lateinit var status: JLabel
        val reported = CountDownLatch(1)
        SwingUtilities.invokeAndWait {
            lab = lab("not a PDF".toByteArray())
            status = (lab.layout as BorderLayout).getLayoutComponent(BorderLayout.SOUTH) as JLabel
            status.addPropertyChangeListener("text") { reported.countDown() }
        }
        assertTrue(reported.await(45, TimeUnit.SECONDS), "Timed out waiting for the PDF to be read")
        SwingUtilities.invokeAndWait {
            assertTrue(status.text.startsWith("Could not read the PDF"), status.text)
            assertTrue(descendants(lab).filterIsInstance<JButton>().none { it.text in listOf("Run OCR", "◀ Page", "Page ▶") && it.isEnabled })
        }
    }

    private fun lab(pdf: ByteArray): OcrLabView = OcrLabView(pdf, TextTemplates(directory)).also { lab ->
        descendants(lab).filterIsInstance<JCheckBox>().single { it.text == "Auto rotate + straighten" }.isSelected = false
    }

    private fun button(lab: OcrLabView, text: String): JButton = descendants(lab).filterIsInstance<JButton>().single { it.text == text }

    private fun awaitEnabled(button: JButton) {
        val ready = CountDownLatch(1)
        val listener = PropertyChangeListener { if (it.newValue == true) ready.countDown() }
        SwingUtilities.invokeAndWait {
            button.addPropertyChangeListener("enabled", listener)
            if (button.isEnabled) ready.countDown()
        }
        try {
            assertTrue(ready.await(45, TimeUnit.SECONDS), "Timed out waiting for ${button.text}")
        } finally {
            SwingUtilities.invokeAndWait { button.removePropertyChangeListener("enabled", listener) }
        }
    }

    private fun descendants(parent: Container): List<Component> = parent.components.flatMap {
        listOf(it) + if (it is Container) descendants(it) else emptyList()
    }

    private fun pdf(pages: Int = 1): ByteArray = PDDocument().use { document ->
        repeat(pages) {
            val page = PDPage().also(document::addPage)
            PDPageContentStream(document, page).use {
                it.beginText()
                it.setFont(PDType1Font(Standard14Fonts.FontName.HELVETICA), 18f)
                it.newLineAtOffset(50f, 700f)
                it.showText("1.1 Name       Test Person")
                it.endText()
            }
        }
        ByteArrayOutputStream().also(document::save).toByteArray()
    }
}
