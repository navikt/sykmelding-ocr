package no.nav.tsm.ocr.template.gcp

import no.nav.tsm.sykmelding.ocr.FieldText
import no.nav.tsm.sykmelding.ocr.PageRead
import no.nav.tsm.sykmelding.ocr.SykmeldingOcrMapping
import no.nav.tsm.sykmelding.ocr.TextRegion
import no.nav.tsm.sykmelding.template.Felter
import no.nav.tsm.sykmelding.template.Template
import no.nav.tsm.sykmelding.template.TemplateLibrary
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JLabel
import javax.swing.JTabbedPane
import javax.swing.JTable
import javax.swing.JTextArea
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.SwingUtilities
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OcrXmlComparisonViewTest {
    @TempDir
    lateinit var directory: File

    @Test
    fun `comparison button opens document report and stale XML disables exports`() {
        SwingUtilities.invokeAndWait {
            val view = BucketTemplateTestView(TemplateLibrary(directory), settings = null, testFolder = null)
            fun set(name: String, value: Any?) {
                view.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(view, value)
            }
            fun get(name: String): Any = view.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(view)
            fun refresh() = view.javaClass.getDeclaredMethod("refreshXmlComparison").apply { isAccessible = true }.invoke(view)
            try {
                val button = get("compareXml") as JButton
                val print = get("printComparison") as JButton
                val copy = get("copyComparison") as JButton
                val xmlPanel = get("xmlBesideOcr") as XmlPanel
                val sideBySide = (xmlPanel.components.filterIsInstance<JPanel>()
                    .flatMap { it.components.toList() }).filterIsInstance<JButton>().single { it.text == "Open side by side" }
                assertFalse(button.isEnabled)
                assertFalse(sideBySide.isEnabled)
                set("shown", BucketFile("example.pdf", 0, null))
                val page = LibraryPage("03.2019", File(directory, "mal.json"), 1, 1, File(directory, "page.json"),
                    Template("", "", emptyList(), ""))
                val field = Felter(0, 0, 1, 1, "pasient.fnr", "fnr")
                val read = PageRead(emptyList(), mapOf(field to FieldText(listOf(TextRegion(0, 0, 1, 1, "30898098466", 99f)))))
                set("shownResult", PdfTestResult(PdfStatus.MATCHED, outcomes = listOf(TemplateOutcome(page, null, 1.0)), reads = listOf(read),
                    sykmelding = SykmeldingOcrMapping.map("03.2019", listOf(read))))
                refresh()
                assertFalse(button.isEnabled)
                set("comparisonXml", XmlContent.of("""
                    <skanningmetadata><sykemeldinger><pasient><fnr>30898098466</fnr></pasient></sykemeldinger></skanningmetadata>
                """.trimIndent().toByteArray()))
                refresh()
                assertTrue(button.isEnabled)
                assertTrue(print.isEnabled)
                assertTrue(copy.isEnabled)
                assertTrue(sideBySide.isEnabled)
                val xmlViews = xmlPanel.javaClass.getDeclaredField("views").apply { isAccessible = true }.get(xmlPanel) as JTabbedPane
                assertEquals("Parsed XML", xmlViews.getTitleAt(0))
                assertEquals("Final OCR output", xmlViews.getTitleAt(2))
                val finalOutput = ((xmlViews.getComponentAt(2) as JScrollPane).viewport.view as JTextArea).text
                assertTrue(finalOutput.contains("\"value\" : \"30898098466\""), finalOutput)
                button.doClick()
                val tabs = get("tabs") as JTabbedPane
                assertEquals("OCR vs XML", tabs.getTitleAt(tabs.selectedIndex))
                val text = get("comparisonText") as JTextArea
                assertTrue(text.text.contains("MATCH  pasient.fnr"), text.text)
                assertTrue(text.text.contains("example.pdf"))
                view.javaClass.getDeclaredMethod("showXmlMessage", String::class.java, String::class.java, Boolean::class.javaPrimitiveType)
                    .apply { isAccessible = true }.invoke(view, "Loading another XML", "another.pdf", false)
                assertFalse(button.isEnabled)
                assertFalse(print.isEnabled)
                assertFalse(copy.isEnabled)
                assertFalse(sideBySide.isEnabled)
                assertFalse(text.text.contains("30898098466"))
                set("shownResult", null)
                set("comparisonXml", XmlContent.of("""
                    <skanningmetadata><sykemeldinger><pasient><fnr>30898098466</fnr></pasient></sykemeldinger></skanningmetadata>
                """.trimIndent().toByteArray()))
                refresh()
                assertTrue(sideBySide.isEnabled, "XML can be opened with an explanation even before OCR is ready")
                assertFalse(button.isEnabled)
                assertTrue(((xmlViews.getComponentAt(2) as JScrollPane).viewport.view as JTextArea).text.contains("Test the selected PDF first"))

                fun descendants(component: java.awt.Component): List<java.awt.Component> =
                    listOf(component) + if (component is java.awt.Container) component.components.flatMap(::descendants) else emptyList()
                val columnScroll = descendants(view).filterIsInstance<JScrollPane>()
                    .single { it.viewport.view is javax.swing.Scrollable && it.viewport.view.preferredSize.height == 1100 }
                columnScroll.setSize(320, 400)
                columnScroll.doLayout()
                columnScroll.viewport.doLayout()
                val column = columnScroll.viewport.view as javax.swing.Scrollable
                assertTrue(column.getScrollableTracksViewportWidth())
                assertFalse(column.getScrollableTracksViewportHeight())
                assertTrue(columnScroll.viewport.view.height >= 1100)
            } finally {
                view.close()
            }
        }
    }

    @Test
    fun `table puts fields to check first and can hide the correct ones`() {
        SwingUtilities.invokeAndWait {
            val comparison = OcrXmlComparison(listOf(
                OcrXmlFieldComparison("pasient.fnr", "01010112345", "01010112345", ComparisonStatus.MATCH),
                OcrXmlFieldComparison("OCR.pasient.fornavn", "Example", null, ComparisonStatus.NOT_COMPARABLE),
                OcrXmlFieldComparison("behandler.adresse", "First line\nSecond line", "Original address", ComparisonStatus.DIFFERENT),
                OcrXmlFieldComparison("behandler.HPR", null, "1234567", ComparisonStatus.MISSING_OCR),
            ), listOf("Example XML problem"))
            val panel = OcrXmlComparisonPanel(comparison)
            fun descendants(component: java.awt.Component): List<java.awt.Component> =
                listOf(component) + if (component is java.awt.Container) component.components.flatMap(::descendants) else emptyList()
            val table = descendants(panel).filterIsInstance<JTable>().single()
            fun column(index: Int) = (0 until table.rowCount).map { table.getValueAt(it, index) }
            assertEquals(listOf("behandler.adresse", "behandler.HPR", "pasient.fnr", "OCR.pasient.fornavn"), column(1))
            assertEquals(listOf("✗ Different", "✗ Missing in OCR", "✓ Correct", "Not compared"), column(0))
            assertEquals("First line\nSecond line", table.getValueAt(0, 2))
            assertEquals(OcrXmlComparisonPanel.MISSING, table.getValueAt(1, 2))
            val labels = descendants(panel).filterIsInstance<JLabel>().map { it.text.trim() }
            assertTrue("✗ Different: 1" in labels, labels.toString())
            assertTrue("✓ Correct: 1" in labels, labels.toString())
            assertTrue(descendants(panel).filterIsInstance<JTextArea>().any { it.text == "Example XML problem" })
            descendants(panel).filterIsInstance<JCheckBox>().single().doClick()
            assertEquals(listOf("behandler.adresse", "behandler.HPR"), column(1))
        }
    }
}
