package org.example.no.nav.tsm.ocr.template.template

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import no.nav.tsm.sykmelding.utils.GridEdit
import no.nav.tsm.sykmelding.utils.GridSettings
import java.awt.Rectangle

@JsonIgnoreProperties(value = ["masks"])
data class Template(
    val navn:  String,
    val beskrivelse: String,
    val felter: List<Felter>,
    val templateBilde: String,
    val gridSettings: GridSettings = GridSettings(),
    val gridEdits: List<GridEdit> = emptyList(),
    val referansepunkter: List<String> = emptyList(),
    )

data class Felter(
    val x: Int,
    val y: Int,
    val bredde: Int,
    val hoyde: Int,
    val navn: String,
    val id: String,
    val type: FieldType = FieldType.TEXT,
)

val Felter.rectangle: Rectangle get() = Rectangle(x, y, bredde, hoyde)

enum class FieldType {
    TEXT, CHECKBOX, DATE, IDENT, NUMBER, CODE;

    override fun toString() = when (this) {
        TEXT -> "Text"
        CHECKBOX -> "Checkbox"
        DATE -> "Date"
        IDENT -> "Ident"
        NUMBER -> "Number"
        CODE -> "Code"
    }
}
