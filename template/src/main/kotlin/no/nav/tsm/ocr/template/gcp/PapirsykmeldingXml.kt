package no.nav.tsm.ocr.template.gcp

import no.nav.helse.papirsykemelding.Skanningmetadata
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.xml.sax.ErrorHandler
import org.xml.sax.SAXParseException
import java.io.ByteArrayInputStream
import java.io.StringWriter
import java.lang.reflect.Field
import java.lang.reflect.Modifier
import java.math.BigDecimal
import java.nio.charset.Charset
import java.time.format.DateTimeParseException
import javax.xml.XMLConstants
import javax.xml.bind.JAXBContext
import javax.xml.bind.JAXBElement
import javax.xml.bind.JAXBIntrospector
import javax.xml.bind.ValidationEvent
import javax.xml.bind.annotation.XmlAttribute
import javax.xml.bind.annotation.XmlElement
import javax.xml.bind.annotation.XmlEnumValue
import javax.xml.bind.annotation.XmlTransient
import javax.xml.bind.annotation.XmlType
import javax.xml.datatype.XMLGregorianCalendar
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.stream.XMLInputFactory
import javax.xml.stream.XMLStreamReader
import javax.xml.stream.util.StreamReaderDelegate
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult

/**
 * The XML syfosmpapirmottak stores next to some scanned PDFs: what the scanning read from the form, as a
 * `skanningmetadata` document (no.nav.helse.xml:papirsykemelding). [fields] are its values as written, each with what
 * production reads from it; [problems] are those that belong to no field.
 */
internal class PapirsykmeldingXml(val fields: List<XmlField>, val problems: List<String>) {

    /** The fields one per line, then the [problems]. */
    fun describe(): String = buildString {
        if (fields.isEmpty()) appendLine("The XML has no values.") else append(xmlFieldLines(fields))
        if (problems.isNotEmpty()) {
            appendLine()
            appendLine("Problems reading the XML:")
            problems.forEach { appendLine("  $it") }
        }
    }

    companion object {
        /** Thread-safe; unmarshallers are not, so each parse makes its own. */
        private val context: JAXBContext by lazy { JAXBContext.newInstance(Skanningmetadata::class.java) }

        /**
         * Reads [bytes] with the papirsykemelding classes, as production does, and keeps the text of every element
         * next to it: JAXB reads some values silently as something else (a boolean `ja` as false), or not at all. A
         * value JAXB cannot read is left out of what production gets. The encoding comes from the XML itself; DTDs and external
         * entities are not read. Throws when [bytes] is not well-formed XML or not a skanningmetadata document.
         */
        fun parse(bytes: ByteArray): PapirsykmeldingXml {
            val reader = ElementTrackingReader(secureInputFactory().createXMLStreamReader(ByteArrayInputStream(bytes)))
            // By element key (see ElementTrackingReader.key); "" = not in an element below the root.
            val problems = LinkedHashMap<String, String>()
            val unmarshaller = context.createUnmarshaller().apply {
                setEventHandler { event ->
                    val fatal = event.severity == ValidationEvent.FATAL_ERROR
                    if (!fatal) problems.putIfAbsent(reader.key, reason(event, inElement = reader.key.isNotEmpty()))
                    !fatal
                }
            }
            val root: Any? = try { unmarshaller.unmarshal(reader) } finally { reader.close() }
            val document = root?.let(JAXBIntrospector::getValue) as? Skanningmetadata
                ?: throw IllegalArgumentException("Not a skanningmetadata document" + (problems.values.firstOrNull()?.let { ": $it" } ?: "."))

            val read = document.sykemeldinger?.let(::xmlValues).orEmpty().associate { keyOf(it.path) to it.value }
            val used = HashSet<String>()
            val fields = reader.leaves.map { (key, text) ->
                // An element outside the schema is reported once, at its start; the elements in it are not.
                val problem = generateSequence(key) { it.substringBeforeLast('.', "").ifEmpty { null } }.firstOrNull { it in problems }
                problem?.let(used::add)
                XmlField(reader.path(key), text, read[key], problem?.let(problems::get))
            }
            val other = problems.filterKeys { it !in used }.map { (key, reason) -> if (key.isEmpty()) reason else "${reader.path(key)}: $reason" }
            return PapirsykmeldingXml(fields, other)
        }

        /** The key of a path [xmlValues] made below `sykemeldinger`: every element with its position. */
        private fun keyOf(path: String): String =
            "$SYKEMELDINGER[1]." + path.split('.').joinToString(".") { if (it.endsWith(']')) it else "$it[1]" }

        private fun reason(event: ValidationEvent, inElement: Boolean): String {
            val message = event.message.orEmpty()
            // JAXB's message lists every element it expected instead.
            if (inElement && message.startsWith("unexpected element")) return OUTSIDE_SCHEMA
            val causes = generateSequence(event.linkedException) { error -> error.cause?.takeIf { it !== error } }.toList()
            val reason = when {
                causes.any { it is NumberFormatException } -> "not a number"
                causes.any { it is DateTimeParseException } -> "not a date (yyyy-mm-dd)"
                else -> (event.linkedException?.message ?: message).ifEmpty { "could not be read" }
            }
            return if (inElement) reason else event.locator?.let { "Line ${it.lineNumber}, column ${it.columnNumber}: " }.orEmpty() + reason
        }
    }
}

/**
 * An element of the XML without child elements, at [path] below `sykemeldinger`, e.g. `behandler.telefon` (an
 * element that repeats has its position: `bidiagnose[2].diagnosekode`). [text] is as written; [value] is what
 * production's JAXB reads from it, null when it reads nothing; [problem] says why, when JAXB says.
 */
internal data class XmlField(val path: String, val text: String, val value: String?, val problem: String? = null) {

    /** Set when production reads something else from the field than its text, or nothing although it has text. */
    val note: String?
        get() = when {
            text.isBlank() -> null
            value == null -> "not read" + (problem?.let { ": $it" } ?: "")
            value.trim() != text.trim() -> "read as $value"
            else -> null
        }
}

/** One value as JAXB read it, at its element path, e.g. `pasient.fnr` or `medisinskVurdering.bidiagnose[2].diagnosekode`. */
internal data class XmlValue(val path: String, val value: String)

/** Shown for an element that is present but has no value. */
internal const val EMPTY_XML_VALUE = "(empty)"

/**
 * The values of [root], a JAXB object generated from a schema, in schema order, named by their elements below it.
 * Elements that are absent are left out; list items are numbered from 1.
 */
internal fun xmlValues(root: Any): List<XmlValue> = buildList { addChildren(root, "", this) }

/**
 * One line per field: its path, then its text; further lines of a multi-line text are indented to the text. A
 * field production reads differently ends with `⚠` and its [note][XmlField.note].
 */
internal fun xmlFieldLines(fields: List<XmlField>): String = buildString {
    val width = (fields.maxOfOrNull { it.path.length } ?: 0).coerceAtMost(MAX_PATH_WIDTH)
    val indent = "\n" + " ".repeat(width + 2)
    fields.forEach { field ->
        append(field.path.padEnd(width)).append("  ").append(field.text.trim().ifEmpty { EMPTY_XML_VALUE }.lines().joinToString(indent))
        field.note?.let { append("   ⚠ ").append(it) }
        appendLine()
    }
}

/**
 * The XML scanned with [pdf] among [files]: in the same folder, with the same journalpostId and dokumentInfoId, and
 * any file UUID. The newest when there are several; null when there is none or [pdf]'s name has no document in it.
 */
internal fun xmlFor(pdf: BucketFile, files: Iterable<BucketFile>): BucketFile? {
    val prefix = pdf.documentPrefix ?: return null
    return files.filter { it.isXml && !it.isPdf && it.documentPrefix == prefix }.maxWithOrNull(compareBy(nullsFirst()) { it.created })
}

/**
 * [bytes] indented two spaces per level, for reading. Unchanged (as text) when it is not well-formed XML, or has a
 * DTD, which is not read.
 */
internal fun prettyXml(bytes: ByteArray): String = runCatching { indentXml(bytes) }.getOrElse { xmlText(bytes) }

/** [bytes] as text, in the encoding its byte order mark or XML declaration names, UTF-8 otherwise. */
internal fun xmlText(bytes: ByteArray): String {
    val (charset, bom) = when {
        bytes.startsWith(0xEF, 0xBB, 0xBF) -> Charsets.UTF_8 to 3
        bytes.startsWith(0xFE, 0xFF) -> Charsets.UTF_16BE to 2
        bytes.startsWith(0xFF, 0xFE) -> Charsets.UTF_16LE to 2
        else -> declaredCharset(bytes) to 0
    }
    return String(bytes, bom, bytes.size - bom, charset)
}

private const val MAX_PATH_WIDTH = 60
private const val SYKEMELDINGER = "sykemeldinger"
private const val OUTSIDE_SCHEMA = "not in the papirsykemelding schema"
private const val NIL_XML_VALUE = "(nil)"
private const val DEFAULT_NAME = "##default"
private val DECLARED_ENCODING = Regex("""^\s*<\?xml[^>]*?encoding\s*=\s*["']([A-Za-z][A-Za-z0-9._-]*)["']""")

private fun secureInputFactory(): XMLInputFactory = XMLInputFactory.newDefaultFactory().apply {
    setProperty(XMLInputFactory.SUPPORT_DTD, false)
    setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false)
}

/**
 * Passes the XML on to JAXB, and meanwhile keeps the text of every element without child elements ([leaves]) and
 * which element is being read ([key]), so what JAXB reads, and its problems, can be put next to the text.
 */
private class ElementTrackingReader(reader: XMLStreamReader) : StreamReaderDelegate(reader) {
    private class Frame(val key: String) {
        val children = HashMap<String, Int>()
        var hasChildren = false
    }

    private val elements = ArrayDeque<Frame>()
    private val characters = StringBuilder()
    /** Parent key + "/" + name of the elements that occur more than once in their parent. */
    private val repeated = HashSet<String>()
    private var leaving = false

    /** The elements without child elements, in document order: [key] and text. */
    val leaves = mutableListOf<Pair<String, String>>()

    /**
     * Of the element being read, below the root element, with every element's position among its siblings of the
     * same name: `sykemeldinger[1].medisinskVurdering[1].bidiagnose[2].diagnosekode[1]`. Empty at the root.
     */
    val key: String get() = elements.lastOrNull()?.key.orEmpty()

    /** [key] as shown: below `sykemeldinger`, with positions only for elements that repeat, e.g. `bidiagnose[2].diagnosekode`. */
    fun path(key: String): String {
        val segments = key.split('.')
        val names = segments.mapIndexed { index, segment ->
            val name = segment.substringBefore('[')
            if ("${segments.take(index).joinToString(".")}/$name" in repeated) segment else name
        }
        return (if (names.size > 1 && names.first() == SYKEMELDINGER) names.drop(1) else names).joinToString(".")
    }

    override fun next(): Int = track { super.next() }

    override fun nextTag(): Int = track { super.nextTag() }

    override fun getElementText(): String = super.getElementText().also {
        characters.setLength(0)
        characters.append(it)
        leave()
    }

    // An element is left only when the reader moves on from its end, so JAXB reads its value while it is [key].
    private fun track(move: () -> Int): Int {
        if (leaving) {
            elements.removeLastOrNull()
            characters.setLength(0)
            leaving = false
        }
        val event = move()
        when (event) {
            START_ELEMENT -> enter(localName)
            END_ELEMENT -> leave()
            CHARACTERS, CDATA, SPACE -> characters.append(text)
        }
        return event
    }

    private fun enter(name: String) {
        characters.setLength(0)
        val parent = elements.lastOrNull()
        if (parent == null) {
            elements.addLast(Frame(""))
            return
        }
        parent.hasChildren = true
        val position = (parent.children[name] ?: 0) + 1
        parent.children[name] = position
        if (position > 1) repeated += "${parent.key}/$name"
        elements.addLast(Frame(if (parent.key.isEmpty()) "$name[$position]" else "${parent.key}.$name[$position]"))
    }

    private fun leave() {
        val frame = elements.lastOrNull() ?: return
        if (!frame.hasChildren && frame.key.isNotEmpty()) leaves += frame.key to characters.toString()
        leaving = true
    }
}

private fun indentXml(bytes: ByteArray): String {
    val factory = DocumentBuilderFactory.newDefaultInstance().apply {
        setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
        setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        isXIncludeAware = false
        isExpandEntityReferences = false
        isNamespaceAware = true
    }
    val builder = factory.newDocumentBuilder().apply {
        // The default handler prints every error to stderr; failing is enough.
        setErrorHandler(object : ErrorHandler {
            override fun warning(exception: SAXParseException) {}
            override fun error(exception: SAXParseException) { throw exception }
            override fun fatalError(exception: SAXParseException) { throw exception }
        })
    }
    val document = builder.parse(ByteArrayInputStream(bytes))
    removeIndentation(document.documentElement)
    val transformer = TransformerFactory.newDefaultInstance().apply {
        setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "")
        setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "")
    }.newTransformer().apply {
        setOutputProperty(OutputKeys.INDENT, "yes")
        setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "2")
        setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes")
    }
    val body = StringWriter().also { transformer.transform(DOMSource(document), StreamResult(it)) }.toString().trim()
    // Written by hand: the transformer would claim the output's encoding, and not put the root on a line of its own.
    val declaration = document.xmlEncoding?.let { "<?xml version=\"${document.xmlVersion}\" encoding=\"$it\"?>\n" }.orEmpty()
    return declaration + body
}

/** Drops the whitespace between elements, which the transformer would keep next to its own indentation. */
private fun removeIndentation(element: Element) {
    val children = (0 until element.childNodes.length).map { element.childNodes.item(it) }
    if (children.any { it.nodeType == Node.ELEMENT_NODE }) {
        children.filter { it.nodeType == Node.TEXT_NODE && it.nodeValue.isBlank() }.forEach { element.removeChild(it) }
    }
    children.filterIsInstance<Element>().forEach(::removeIndentation)
}

private fun declaredCharset(bytes: ByteArray): Charset {
    val head = String(bytes, 0, minOf(bytes.size, 200), Charsets.ISO_8859_1)
    val name = DECLARED_ENCODING.find(head)?.groupValues?.get(1)
    return name?.let { runCatching { Charset.forName(it) }.getOrNull() } ?: Charsets.UTF_8
}

private fun ByteArray.startsWith(vararg prefix: Int): Boolean =
    size >= prefix.size && prefix.indices.all { this[it] == prefix[it].toByte() }

private class BoundProperty(val name: String, val field: Field)

/** The schema properties of a generated class, superclass first, each class's in its `propOrder`; attributes first. */
private val boundProperties = object : ClassValue<List<BoundProperty>>() {
    override fun computeValue(type: Class<*>): List<BoundProperty> =
        generateSequence(type) { it.superclass }.takeWhile(::isBound).toList().asReversed().flatMap(::ownProperties)
}

private fun ownProperties(type: Class<*>): List<BoundProperty> {
    val order = type.getAnnotation(XmlType::class.java)?.propOrder.orEmpty().filter(String::isNotEmpty)
    return type.declaredFields
        .filter { !Modifier.isStatic(it.modifiers) && !Modifier.isTransient(it.modifiers) && !it.isSynthetic && !it.isAnnotationPresent(XmlTransient::class.java) }
        .sortedBy { if (it.isAnnotationPresent(XmlAttribute::class.java)) -1 else order.indexOf(it.name).takeIf { index -> index >= 0 } ?: order.size }
        .filter { it.trySetAccessible() }
        .map { BoundProperty(xmlName(it), it) }
}

/** A class generated from the schema, whose value is its properties; not an enum, which is a single value. */
private fun isBound(type: Class<*>): Boolean = !type.isEnum && type.isAnnotationPresent(XmlType::class.java)

private fun xmlName(field: Field): String {
    val attribute: XmlAttribute? = field.getAnnotation(XmlAttribute::class.java)
    if (attribute != null) return "@" + declaredName(attribute.name, field)
    val element: XmlElement? = field.getAnnotation(XmlElement::class.java)
    return declaredName(element?.name, field)
}

/** [name] from an annotation; the field's own name when the annotation leaves it to the default. */
private fun declaredName(name: String?, field: Field): String = if (name == null || name == DEFAULT_NAME) field.name else name

private fun addChildren(node: Any, prefix: String, into: MutableList<XmlValue>) {
    for (property in boundProperties.get(node.javaClass)) {
        when (val value = property.field.get(node)) {
            null -> {}
            is Collection<*> -> value.filterNotNull().forEachIndexed { index, item -> addValue(item, "$prefix${property.name}[${index + 1}]", into) }
            else -> addValue(value, prefix + property.name, into)
        }
    }
}

private fun addValue(value: Any, path: String, into: MutableList<XmlValue>) {
    when {
        value is JAXBElement<*> -> {
            val inner = value.value
            if (value.isNil || inner == null) into += XmlValue(path, NIL_XML_VALUE) else addValue(inner, path, into)
        }
        isBound(value.javaClass) -> {
            val before = into.size
            addChildren(value, "$path.", into)
            if (into.size == before) into += XmlValue(path, EMPTY_XML_VALUE)
        }
        else -> into += XmlValue(path, formatXmlValue(value))
    }
}

private fun formatXmlValue(value: Any): String = when (value) {
    is String -> value.ifBlank { EMPTY_XML_VALUE }
    is BigDecimal -> value.toPlainString()
    is XMLGregorianCalendar -> value.toXMLFormat()
    is Enum<*> -> runCatching { value.declaringJavaClass.getField(value.name).getAnnotation(XmlEnumValue::class.java)?.value }.getOrNull() ?: value.name
    is ByteArray -> "${value.size} bytes"
    else -> value.toString()
}
