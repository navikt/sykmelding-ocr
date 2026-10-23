package no.nav.tsm.ocr.template.gcp

import no.nav.tsm.sykmelding.ocr.FieldText
import no.nav.tsm.sykmelding.ocr.PageRead
import no.nav.tsm.sykmelding.ocr.TextRegion
import org.example.no.nav.tsm.ocr.template.template.Felter
import org.example.no.nav.tsm.ocr.template.template.Template
import org.example.no.nav.tsm.ocr.template.template.TemplateLibrary
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import javax.swing.JButton
import javax.swing.JTabbedPane
import javax.swing.JTextArea
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JSplitPane
import javax.swing.border.TitledBorder
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
                val read = PageRead(emptyList(), mapOf(field to FieldText(listOf(TextRegion(0, 0, 1, 1, "01010112345", 99f)))))
                set("shownResult", PdfTestResult(PdfStatus.MATCHED, outcomes = listOf(TemplateOutcome(page, null, 1.0)), reads = listOf(read)))
                refresh()
                assertFalse(button.isEnabled)
                set("comparisonXml", XmlContent.of("""
                    <skanningmetadata><sykemeldinger><pasient><fnr>01010112345</fnr></pasient></sykemeldinger></skanningmetadata>
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
                assertTrue(finalOutput.contains("\"fnr\" : \"01010112345\""), finalOutput)
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
                assertFalse(text.text.contains("01010112345"))
                set("shownResult", null)
                set("comparisonXml", XmlContent.of("""
                    <skanningmetadata><sykemeldinger><pasient><fnr>01010112345</fnr></pasient></sykemeldinger></skanningmetadata>
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

            @Test
            fun `side by side identifies final output and original XML and aligns multiline fields`() {
                SwingUtilities.invokeAndWait {
                    val comparison = OcrXmlComparison(listOf(
                        OcrXmlFieldComparison("behandler.adresse", "First line\nSecond line", "Original address", ComparisonStatus.DIFFERENT),
                        OcrXmlFieldComparison("pasient.fnr", null, "01010112345", ComparisonStatus.MISSING_OCR),
                    ), listOf("Example XML problem"))
                    val panel = OcrXmlComparisonPanel(comparison)
                    val split = panel.components.filterIsInstance<JSplitPane>().single()
                    val left = split.leftComponent as JScrollPane
                    val right = split.rightComponent as JScrollPane
                    assertEquals("Final OCR output - OcrParserSykmelding", (left.border as TitledBorder).title)
                    assertEquals("Iron Mountain XML - original values", (right.border as TitledBorder).title)
                    val ocr = (left.viewport.view as JTextArea).text.lines()
                    val xml = (right.viewport.view as JTextArea).text.lines()
                    assertEquals(ocr.size, xml.size)
                    assertEquals(ocr.indexOf("MISSING_OCR  pasient.fnr"), xml.indexOf("MISSING_OCR  pasient.fnr"))
                    assertTrue("Second line" in ocr)
                    assertFalse("Original address" in ocr)
                    assertTrue("Original address" in xml)
                    assertFalse("Second line" in xml)
                    assertTrue("(missing)" in ocr)
                    assertTrue(left.verticalScrollBar.model === right.verticalScrollBar.model)
                    assertFalse((left.viewport.view as JTextArea).isEditable)
                    assertFalse((right.viewport.view as JTextArea).isEditable)
                }
            }
        }
    }
}
