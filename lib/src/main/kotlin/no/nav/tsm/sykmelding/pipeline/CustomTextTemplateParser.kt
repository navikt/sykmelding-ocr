package no.nav.tsm.sykmelding.pipeline

import no.nav.tsm.sykmelding.ocr.TextPart
import org.slf4j.LoggerFactory

object CustomTextTemplateParser {
    private val logger = LoggerFactory.getLogger(CustomTextTemplateParser::class.java)

    fun parse(
        parts: List<TextPart>,
        template: TextTemplate? = null
    ): Map<LineField, List<List<TextPart>>> {

        val levelParts = parts.filter { it.line.isLevel() }
        if (levelParts.size < parts.size) {
            logger.debug("removed unlevel parts {} parts", parts.size - levelParts.size)
        }
        val navsEksemplar = levelParts.firstOrNull { it.line.text.lowercase() == "navs eksemplar" }
        if (navsEksemplar == null) {
            logger.debug("did not find navs eksemplar")
        }

        val folketrygden = levelParts.firstOrNull { it.line.text.lowercase() == "folketrygden" }

        if (folketrygden == null) {
            logger.debug("did not find folketrygden")
            return emptyMap()
        }

        val lines = mutableMapOf<Int, MutableList<TextPart>>()
        val partsToAdd =
            levelParts.filter { it.line.baseline > folketrygden.line.baseline + (folketrygden.line.height / 2) }
        var currentLineBaseLine = 0

        partsToAdd.forEach { part ->
            if (currentLineBaseLine > part.line.baseline - (part.line.height / 2)) {
                val currentLine = lines[currentLineBaseLine]
                if (currentLine == null) {
                    lines[part.line.baseline] = mutableListOf(part)
                } else {
                    currentLine.add(part)
                }
            } else {
                lines[part.line.baseline] = mutableListOf(part)
                currentLineBaseLine = part.line.baseline
            }
        }

        if (template == null) {
            return emptyMap()
        }

        var lastMatchedField: LineField? = null
        val fieldRowMap = mutableMapOf<LineField, MutableList<MutableList<TextPart>>>()
        lines.values.forEachIndexed { index, parts ->
            val sortedParts = parts.sortedBy { it.line.x }.toMutableList()
            val matchingField = template.fields.firstOrNull {
                it.number in sortedParts.map { it.line.text } || it.name in sortedParts.map { it.line.text }
            }
            if (matchingField != null) {
                lastMatchedField = matchingField
                fieldRowMap[matchingField] = mutableListOf(sortedParts)
                logger.debug("found matching field: {}", matchingField.number)
            } else {
                if(lastMatchedField != null && lastMatchedField.layout == FieldLayout.BELOW) {
                    fieldRowMap[lastMatchedField]?.add(sortedParts)
                    logger.debug("found matching field: {} for lastMatchedField, adding row", lastMatchedField.number)
                } else {
                    logger.debug("no matching part, either multiline or end")
                }
            }
        }

        logger.debug("{} rows below folketrygden", lines.size)
        // TODO: Implement mapping to something usefull
        return fieldRowMap
    }
}
