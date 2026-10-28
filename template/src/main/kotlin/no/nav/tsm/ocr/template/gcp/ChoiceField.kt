package no.nav.tsm.ocr.template.gcp

import java.awt.Component
import java.util.prefs.Preferences
import javax.swing.DefaultListCellRenderer
import javax.swing.JComboBox
import javax.swing.JList

/**
 * A dropdown of [values], such as the keypoint detectors. Each is shown by its [label], production's ([production])
 * marked as such, with its [description] as tooltip. The choice is stored under [key] in [settings] by its name, and
 * read back from there; a name no longer among [values] gives [production]. [intro] heads the dropdown's tooltip.
 */
internal class ChoiceField<E : Enum<E>>(
    values: List<E>,
    private val production: E,
    private val settings: Preferences?,
    private val key: String,
    private val label: (E) -> String,
    private val description: (E) -> String,
    private val intro: String,
) : JComboBox<E>() {
    /** The value chosen. */
    val value: E get() = getItemAt(selectedIndex) ?: production

    init {
        values.forEach(::addItem)
        selectedItem = settings?.get(key, null)?.let { stored -> values.firstOrNull { it.name == stored } } ?: production
        renderer = object : DefaultListCellRenderer() {
            override fun getListCellRendererComponent(list: JList<*>?, item: Any?, index: Int, selected: Boolean, focus: Boolean): Component {
                @Suppress("UNCHECKED_CAST") val choice = item as? E
                val text = choice?.let { label(it) + if (it == production && index >= 0) " (production)" else "" }.orEmpty()
                return super.getListCellRendererComponent(list, text, index, selected, focus).also {
                    toolTipText = choice?.let(description)
                }
            }
        }
        addActionListener {
            settings?.put(key, value.name)
            describe()
        }
        describe()
    }

    /** Makes [choice] the value, as if it was chosen. */
    fun select(choice: E) {
        selectedItem = choice
    }

    private fun describe() {
        toolTipText = "<html>${escape(intro)}<br><b>${escape(label(value))}</b>: ${escape(description(value))}<br>" +
            "Production: ${escape(label(production))}.</html>"
    }

    private fun escape(text: String) = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}
