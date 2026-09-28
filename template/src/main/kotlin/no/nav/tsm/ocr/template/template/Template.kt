package org.example.no.nav.tsm.ocr.template.template

data class Template(
    val navn:  String,
    val referansepunkter: List<String>,
    val beskrivelse: String,
    val felter: List<Felter>,
    val templateBilde: String,

    )

data class Felter(
    val x: Int,
    val y: Int,
    val bredde: Int,
    val hoyde: Int,
    val navn: String,
    val id: String,
)

data class Felt(
    val id: String,
    val verdi: String
)

