package no.nav.tsm.ocr.template.gcp

import javax.swing.JComboBox

/**
 * An editable combo box of [items] for a value: choose one, or type one and press Enter. Text [parse] accepts becomes
 * [value], shown as [format] gives it, and is passed to [store] and [onChange] when it differs. Text it does not
 * accept is explained to [complain], by [rejected] from the text and the value it keeps, and [value] is shown again.
 */
internal open class ParsedComboField<T>(
    items: List<String>,
    initial: T,
    prototype: String,
    private val parse: (String) -> Parsed<T>?,
    private val format: (T) -> String,
    private val store: (T) -> Unit,
    private val rejected: (typed: String, kept: T) -> String,
    private val complain: (String) -> Unit,
) : JComboBox<String>(items.toTypedArray()) {
    /** The last value chosen or typed that was accepted when it was. */
    var value: T = initial
        private set
    var onChange: (T) -> Unit = {}
    private var updating = false

    init {
        // Not `isEditable = true`: in a subclass that sets JComboBox's protected field of that name, and the look and
        // feel never adds the editor.
        setEditable(true)
        prototypeDisplayValue = prototype
        display(value)
        addActionListener { if (!updating) commit() }
    }

    /** The value typed or chosen, also when Enter has not been pressed; null, after saying why, when it is not accepted. */
    fun commit(): Parsed<T>? {
        val typed = (editor.item ?: selectedItem)?.toString().orEmpty()
        val parsed = parse(typed)
        if (parsed == null) {
            complain(rejected(typed.trim(), value))
            display(value)
        } else {
            select(parsed.value)
        }
        return parsed
    }

    /** Makes [choice] the value, as if it was typed. */
    fun select(choice: T) {
        if (choice != value) {
            value = choice
            store(choice)
            onChange(choice)
        }
        display(choice)
    }

    private fun display(choice: T) {
        updating = true
        try {
            val text = format(choice)
            selectedItem = text
            editor.item = text
        } finally {
            updating = false
        }
    }
}

/** A value [ParsedComboField] accepted; wrapped, as the value itself may be null. */
@JvmInline
internal value class Parsed<out T>(val value: T)
