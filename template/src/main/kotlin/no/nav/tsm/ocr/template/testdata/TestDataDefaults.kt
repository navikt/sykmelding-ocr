package no.nav.tsm.ocr.template.testdata

import no.nav.tsm.sykmelding.ocr.CustomTextTemplateParser
import java.awt.Rectangle

class DefaultsUsed(val typed: Int, val withoutDefault: List<String>, val ignoredRows: Int)

fun TestDataPage.useDefaults(read: CustomTextTemplateParser.Page, defaults: Map<String, String>): DefaultsUsed {
    if (read.rows.isEmpty()) return DefaultsUsed(0, emptyList(), read.ignored.size)
    val labels = read.rows.values.map { it.first() }
    val digitHeight = labels.map { it.first().line.height }.sorted().let { it[it.size / 2] }
    val form = read.heading + read.header + labels.flatten() + read.values.flatMap { it.parts }
    val top = form.minOf { it.line.y } - MARGIN * digitHeight
    val bottom = form.maxOf { it.line.y + it.line.height } + MARGIN * digitHeight
    if (top > 0) deleteArea(Rectangle(0, 0, background.width, top))
    if (bottom < background.height) deleteArea(Rectangle(0, bottom, background.width, background.height - bottom))

    read.values.forEach { value -> value.parts.forEach { deleteArea(box(it.line)) } }
    var typed = 0
    for (value in read.values) {
        val text = defaults[value.field] ?: continue
        val first = value.parts.firstOrNull()?.line
        val x = value.place?.x ?: first?.x ?: continue
        val baseline = value.place?.baseline ?: first?.baseline ?: continue
        type(text, x, baseline, digitHeight)
        typed++
    }
    val withoutDefault = read.values.filter { it.parts.isNotEmpty() && it.field !in defaults }.map { it.field }
    return DefaultsUsed(typed, withoutDefault, read.ignored.size)
}

private const val MARGIN = 2
