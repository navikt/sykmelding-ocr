package no.nav.tsm.ocr.template.gcp

import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Font
import javax.swing.BorderFactory
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTabbedPane
import javax.swing.JTextArea
import javax.swing.SwingConstants

class XmlPanel(title: String = "XML") : JPanel(BorderLayout(4, 4)) {
    private val heading = JLabel(" ").apply { font = font.deriveFont(Font.BOLD) }
    private val summary = JLabel(" ")
    private val parsed = textArea()
    private val raw = textArea()
    private val views = JTabbedPane().apply {
        addTab("Parsed", JScrollPane(parsed))
        addTab("Raw XML", JScrollPane(raw))
        setToolTipTextAt(PARSED, "The fields of the XML as written. ⚠ marks a value production's papirsykemelding classes " +
            "read differently, or not at all.")
    }
    private val message = JLabel("", SwingConstants.CENTER)
    private val cards = CardLayout()
    private val content = JPanel(cards)
    /** "Raw XML" was chosen because the XML could not be read, not by the user; the next XML that can be is shown parsed. */
    private var rawForUnreadable = false

    init {
        border = BorderFactory.createTitledBorder(title)
        add(JPanel(BorderLayout(0, 2)).apply { add(heading, BorderLayout.NORTH); add(summary, BorderLayout.SOUTH) }, BorderLayout.NORTH)
        content.add(views, VIEWS)
        content.add(message, MESSAGE)
        add(content, BorderLayout.CENTER)
        showMessage("Select a PDF to see its XML.")
    }

    fun showMessage(text: String, heading: String = " ") {
        reset(heading)
        message.text = "<html><div style='text-align:center'>" + escapeHtml(text).replace("\n", "<br>") + "</div></html>"
        cards.show(content, MESSAGE)
    }

    /** [content] of the XML [heading], at [location]. */
    fun show(heading: String, location: String, content: XmlContent) {
        reset(heading)
        this.heading.toolTipText = location
        summary.text = content.summary
        parsed.text = content.fields
        parsed.caretPosition = 0
        raw.text = content.raw
        raw.caretPosition = 0
        when {
            !content.parsed && views.selectedIndex == PARSED -> { views.selectedIndex = RAW; rawForUnreadable = true }
            content.parsed && rawForUnreadable && views.selectedIndex == RAW -> { views.selectedIndex = PARSED; rawForUnreadable = false }
            content.parsed -> rawForUnreadable = false
        }
        cards.show(this.content, VIEWS)
    }

    private fun reset(heading: String) {
        this.heading.text = heading
        this.heading.toolTipText = null
        summary.text = " "
        parsed.text = ""
        raw.text = ""
    }

    private companion object {
        const val PARSED = 0
        const val RAW = 1
        const val VIEWS = "views"
        const val MESSAGE = "message"

        fun textArea() = JTextArea().apply { isEditable = false; font = Font(Font.MONOSPACED, Font.PLAIN, 12) }
    }
}
class XmlContent(val fields: String, val raw: String, val summary: String, val parsed: Boolean) {
    companion object {
        /** Larger files are shown in part, unread; a scanned form's XML is a few kB. */
        private const val MAX_BYTES = 2 * 1024 * 1024

        fun of(bytes: ByteArray): XmlContent {
            if (bytes.size > MAX_BYTES) {
                val text = xmlText(bytes.copyOf(MAX_BYTES)) + "\n\n… (truncated)"
                return XmlContent("The file is ${formatSize(bytes.size.toLong())}, too large for a papirsykmelding XML; it is not read.",
                    text, "Not read: ${formatSize(bytes.size.toLong())}", parsed = false)
            }
            val raw = prettyXml(bytes)
            val xml = try { PapirsykmeldingXml.parse(bytes) } catch (error: Exception) {
                val reason = (error.message ?: error.toString()).lineSequence().map(String::trim).filter(String::isNotEmpty).joinToString(" ")
                return XmlContent("Could not read the file as a papirsykmelding XML (skanningmetadata):\n$reason\n\n" +
                    "The Raw XML tab shows it as written.", raw, "Not a papirsykmelding XML", parsed = false)
            }
            val fields = xml.fields.size
            val differ = xml.fields.count { it.note != null }
            val summary = "$fields field${plural(fields)} as written in the XML; " +
                (if (differ == 0) "production reads them as written." else "⚠ $differ that production reads differently or not at all.") +
                if (xml.problems.isEmpty()) "" else " ${xml.problems.size} other problem${plural(xml.problems.size)}."
            return XmlContent(xml.describe(), raw, summary, parsed = true)
        }
    }
}

private fun escapeHtml(text: String) = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
