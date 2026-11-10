package no.nav.tsm.ocr.template.pipeline

import no.nav.tsm.sykmelding.pipeline.FieldLayout
import no.nav.tsm.sykmelding.pipeline.LineField
import no.nav.tsm.sykmelding.pipeline.TextTemplate
import no.nav.tsm.sykmelding.pipeline.TextTemplates
import no.nav.tsm.sykmelding.template.FieldCatalog
import no.nav.tsm.sykmelding.template.FieldDefinition
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.GridLayout
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JLabel
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTable
import javax.swing.JTextField
import javax.swing.ListSelectionModel
import javax.swing.table.AbstractTableModel

class TextTemplatePanel(
    private val store: TextTemplates,
    private val status: (String) -> Unit,
    select: String? = null,
    private val changed: () -> Unit,
) : JPanel(BorderLayout(4, 4)) {

    var template: TextTemplate? = null
        private set

    private val fields: List<LineField> get() = template?.fields.orEmpty()

    private var loading = false
    private val chooser = JComboBox<String>().apply {
        addActionListener { if (!loading) choose(selectedItem as String?) }
    }
    private val model = FieldModel()
    private val table = JTable(model).apply {
        setSelectionMode(ListSelectionModel.SINGLE_SELECTION)
        columnModel.getColumn(0).preferredWidth = 50
        columnModel.getColumn(1).preferredWidth = 260
        columnModel.getColumn(2).preferredWidth = 60
        columnModel.getColumn(3).preferredWidth = 220
        addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) { if (e.clickCount == 2) editSelected() }
        })
    }

    init {
        border = BorderFactory.createTitledBorder("Text template")
        add(JPanel(FlowLayout(FlowLayout.LEFT)).apply {
            add(chooser)
            add(JButton("New…").apply { addActionListener { create() } })
        }, BorderLayout.NORTH)
        add(JScrollPane(table), BorderLayout.CENTER)
        add(JPanel(FlowLayout(FlowLayout.LEFT)).apply {
            add(JButton("Add field…").apply {
                toolTipText = "Add a field by hand: its number, name, where its value is, and its global field."
                addActionListener { addField() }
            })
            add(JButton("Edit…").apply {
                toolTipText = "Change the selected field's number, name, layout or global field (also by double-clicking it)."
                addActionListener { editSelected() }
            })
            add(JButton("Assign by number").apply {
                toolTipText = "Give every field without a global field the global field with its number (set in the template editor's Global fields)."
                addActionListener { assignAllByNumber() }
            })
            add(JButton("Remove").apply { addActionListener { removeSelected() } })
        }, BorderLayout.SOUTH)
        reload(select, notify = false)
    }

    fun select(name: String) = reload(name)

    private fun reload(select: String?, notify: Boolean = true) {
        val names = try {
            store.names()
        } catch (error: Exception) {
            status("Could not list text templates in ${store.directory}: ${error.message}")
            emptyList()
        }
        loading = true
        try {
            chooser.removeAllItems()
            names.forEach(chooser::addItem)
            chooser.selectedItem = select ?: names.firstOrNull()
        } finally {
            loading = false
        }
        choose(chooser.selectedItem as String?, notify)
    }

    private fun choose(name: String?, notify: Boolean = true) {
        val read = name?.let {
            try {
                store.read(it)
            } catch (error: Exception) {
                status("Could not read text template $it: ${error.message}")
                null
            }
        }
        template = read?.synced(catalog())?.inFormOrder()
        if (read != null && template != read) {
            try {
                store.write(template!!)
                status("Updated text template ${read.name}: its global fields as the catalog has them now, its fields in the form's order.")
            } catch (error: Exception) {
                status("Could not save text template ${read.name}: ${error.message}")
            }
        }
        model.fireTableDataChanged()
        if (notify) changed()
    }

    private fun create() {
        val name = JOptionPane.showInputDialog(this, "Name of the new text template:", "New text template", JOptionPane.PLAIN_MESSAGE)
            ?.trim()?.takeIf { it.isNotEmpty() } ?: return
        try {
            if (store.exists(name)) {
                status("There is already a text template called $name.")
                return
            }
            store.write(TextTemplate(name))
        } catch (error: Exception) {
            status("Could not create text template $name: ${error.message}")
            return
        }
        reload(name)
        status("Created text template $name in ${store.directory}.")
    }

    private fun assignAllByNumber() {
        if (template == null) return status("Create or choose a text template first.")
        val catalog = catalog()
        var assigned = emptyList<LineField>()
        val saved = save { stored ->
            val updated = stored.toMutableList()
            assigned = assignByNumber(updated, catalog)
            updated
        }
        if (!saved) return
        val without = fields.filter { it.field == null }.map { it.number }
        status((if (assigned.isEmpty()) "No field was given a global field by its number." else "By number: ${describe(assigned)}.") +
            (if (without.isEmpty()) "" else " Still without a global field: ${without.joinToString(", ")}."))
    }

    private fun assignByNumber(updated: MutableList<LineField>, catalog: List<FieldDefinition>): List<LineField> {
        val assigned = mutableListOf<LineField>()
        for (index in updated.indices) {
            val field = updated[index]
            if (field.field != null) continue
            val global = catalog.firstOrNull { it.number == field.number } ?: continue
            if (updated.any { it.field?.id == global.id }) continue
            updated[index] = field.copy(field = global)
            assigned += updated[index]
        }
        return assigned
    }

    private fun describe(fields: List<LineField>) = fields.joinToString(", ") { "${it.number} → ${it.field?.navn}" }

    private fun editSelected() {
        val selected = table.selectedRow.takeIf { it >= 0 }?.let { fields[it] } ?: return status("Select a field to edit.")
        val field = edit(selected, "Edit field") ?: return
        put(field, selected.number)
    }

    private fun addField() {
        if (template == null) return status("Create or choose a text template first.")
        val field = edit(LineField("", ""), "Add field") ?: return
        put(field, null)
    }

    private fun put(field: LineField, replaces: String?) {
        var moved = emptyList<String>()
        val saved = save { stored ->
            val updated = stored.toMutableList()
            val index = updated.indexOfFirst { it.number == (replaces ?: field.number) }
            if (replaces != null && index < 0) {
                status("Field $replaces is no longer in the template; nothing was changed.")
                return@save null
            }
            if (replaces != null && updated.indices.any { it != index && updated[it].number == field.number }) {
                status("There is already a field numbered ${field.number}; nothing was changed.")
                return@save null
            }
            val at = if (index < 0) { updated += field; updated.lastIndex } else { updated[index] = field; index }
            val global = field.field
            val others = updated.indices.filter { it != at && global != null && updated[it].field?.id == global.id }
            others.forEach { updated[it] = updated[it].copy(field = null) }
            moved = others.map { updated[it].number }
            updated
        }
        if (!saved) return
        fields.indexOfFirst { it.number == field.number }.takeIf { it >= 0 }?.let { table.setRowSelectionInterval(it, it) }
        if (moved.isNotEmpty()) status("${field.field?.navn} moved here from ${moved.joinToString(", ")}.")
    }

    private fun removeSelected() {
        val number = table.selectedRow.takeIf { it >= 0 }?.let { fields[it].number } ?: return status("Select a field to remove.")
        save { stored ->
            val index = stored.indexOfFirst { it.number == number }
            if (index < 0) {
                status("Field $number is no longer in the template; nothing was changed.")
                return@save null
            }
            stored.toMutableList().also { it.removeAt(index) }
        }
    }

    private fun save(change: (List<LineField>) -> List<LineField>?): Boolean {
        val name = template?.name ?: return false
        val read = try {
            store.read(name)
        } catch (error: Exception) {
            status("Could not read text template $name: ${error.message}")
            return false
        }
        val updated = change(read.fields)
        if (updated == null) {
            show(read.inFormOrder())
            return false
        }
        val next = read.copy(fields = updated).inFormOrder()
        if (next != read) {
            try {
                store.write(next)
            } catch (error: Exception) {
                status("Could not save text template $name: ${error.message}")
                return false
            }
        }
        show(next)
        return true
    }

    private fun show(next: TextTemplate) {
        val shown = template
        template = next
        model.fireTableDataChanged()
        if (next != shown) changed()
    }

    private fun edit(field: LineField, title: String): LineField? {
        val number = JTextField(field.number, 8)
        val name = JTextField(field.name, 30)
        val catalog = catalog()

        val removed = field.field?.takeIf { current -> catalog.none { it.id == current.id } }
        val choices = listOf(GlobalChoice(null)) + catalog.map { GlobalChoice(it) } + listOfNotNull(removed?.let { GlobalChoice(it, removed = true) })
        val global = JComboBox(choices.toTypedArray()).apply {
            selectedItem = choices.firstOrNull { it.definition?.id == field.field?.id } ?: choices.first()
            toolTipText = "The global field this value is, as in an image template: by it the value is mapped to a sykmelding " +
                "and compared with the XML. None for a field that is not one, such as a heading."
        }
        val layout = JComboBox(FieldLayout.entries.toTypedArray()).apply {
            selectedItem = field.layout
            toolTipText = "Where the value is: INLINE after the label on the field's row; BELOW on the next row under its number " +
                "(the headings of a row with several fields); NONE for a heading without a value."
        }
        val form = JPanel(GridLayout(0, 2, 4, 4)).apply {
            add(JLabel("Number (like 1.1)")); add(number)
            add(JLabel("Name")); add(name)
            add(JLabel("Value is")); add(layout)
            add(JLabel("Global field")); add(global)
        }
        var reason: String? = null
        while (true) {
            val message: Any = reason?.let { arrayOf<Any>(JLabel(it), form) } ?: form
            if (JOptionPane.showConfirmDialog(this, message, title, JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) return null
            val typed = fieldNumber(number.text)
            val typedName = name.text.trim()
            reason = when {
                !FIELD_NUMBER.matches(typed) -> "A field needs a number: digits with dots between, like 3.1.2."
                typedName.isEmpty() -> "A field needs a name."
                else -> return LineField(typed, typedName, (global.selectedItem as GlobalChoice).definition, layout.selectedItem as FieldLayout)
            }
        }
    }

    private fun catalog(): List<FieldDefinition> = try {
        FieldCatalog().read()
    } catch (error: Exception) {
        status("Could not read the global fields: ${error.message}")
        emptyList()
    }

    private class GlobalChoice(val definition: FieldDefinition?, val removed: Boolean = false) {
        override fun toString() = when {
            definition == null -> "(none)"
            removed -> "${label(definition)} — no longer a global field"
            else -> label(definition)
        }
    }

    private companion object {
        fun label(definition: FieldDefinition) = (definition.number?.let { "$it  " } ?: "") + "${definition.navn} (${definition.type})" +
            (definition.description?.let { " — $it" } ?: "")

        val FIELD_NUMBER = Regex("""\d+(\.\d+)*""")

        fun fieldNumber(typed: String) = typed.trim().replace(',', '.').removeSuffix(".")
    }

    private inner class FieldModel : AbstractTableModel() {
        private val columns = listOf("Number", "Name", "Value is", "Global field")
        override fun getRowCount() = fields.size
        override fun getColumnCount() = columns.size
        override fun getColumnName(column: Int) = columns[column]
        override fun getValueAt(row: Int, column: Int): Any = fields[row].let {
            when (column) {
                0 -> it.number
                1 -> it.name
                2 -> it.layout.name.lowercase()
                else -> it.field?.let(::label).orEmpty()
            }
        }
    }
}
