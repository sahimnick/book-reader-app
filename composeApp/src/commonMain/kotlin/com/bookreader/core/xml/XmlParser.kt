package com.bookreader.core.xml

/**
 * A small, dependency-free XML/HTML tokenizer and tree builder.
 *
 * EPUB needs two different parsing modes from the same code:
 *  - strict-ish XML for `container.xml` and the OPF package document, and
 *  - tolerant HTML for the XHTML content documents, which in the wild contain
 *    unclosed tags, stray `&`, and void elements written without a slash.
 *
 * Rather than pull in a JVM-only parser (which would not survive the move to
 * Kotlin/Native for iOS), both modes share one tokenizer; [buildTree] applies
 * the tolerant nesting rules that HTML needs and XML simply never triggers.
 */

internal fun StringBuilder.appendCodePointCompat(codePoint: Int) {
    if (codePoint <= 0xFFFF) {
        append(codePoint.toChar())
    } else {
        val v = codePoint - 0x10000
        append((((v shr 10) and 0x3FF) + 0xD800).toChar())
        append(((v and 0x3FF) + 0xDC00).toChar())
    }
}

object Entities {
    private val named: Map<String, String> = mapOf(
        "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'",
        "nbsp" to " ", "mdash" to "—", "ndash" to "–",
        "hellip" to "…", "rsquo" to "’", "lsquo" to "‘",
        "ldquo" to "“", "rdquo" to "”", "sbquo" to "‚",
        "bdquo" to "„", "dagger" to "†", "Dagger" to "‡",
        "bull" to "•", "prime" to "′", "Prime" to "″",
        "copy" to "©", "reg" to "®", "trade" to "™",
        "deg" to "°", "plusmn" to "±", "frac12" to "½",
        "laquo" to "«", "raquo" to "»", "middot" to "·",
        "sect" to "§", "para" to "¶", "euro" to "€",
        "pound" to "£", "yen" to "¥", "cent" to "¢",
        "times" to "×", "divide" to "÷", "minus" to "−",
        "shy" to "­", "ensp" to " ", "emsp" to " ",
        "thinsp" to " ", "zwnj" to "‌", "zwj" to "‍",
        "lrm" to "‎", "rlm" to "‏",
    )

    /**
     * Decodes XML/HTML character references. Unknown or malformed references are
     * left verbatim — an EPUB with a bare `&` in the text should still render
     * that `&` rather than swallowing the rest of the paragraph.
     */
    fun decode(input: String): String {
        val amp = input.indexOf('&')
        if (amp < 0) return input

        val sb = StringBuilder(input.length)
        sb.append(input, 0, amp)
        var i = amp
        while (i < input.length) {
            val c = input[i]
            if (c != '&') {
                sb.append(c)
                i++
                continue
            }
            val semi = input.indexOf(';', i + 1)
            // A reference is short; a distant ';' means this '&' is literal text.
            if (semi < 0 || semi - i > 32) {
                sb.append('&')
                i++
                continue
            }
            val body = input.substring(i + 1, semi)
            val decoded: String? = when {
                body.startsWith("#x") || body.startsWith("#X") ->
                    body.substring(2).toIntOrNull(16)?.takeIf { it in 1..0x10FFFF }
                        ?.let { cp -> StringBuilder().also { it.appendCodePointCompat(cp) }.toString() }

                body.startsWith("#") ->
                    body.substring(1).toIntOrNull()?.takeIf { it in 1..0x10FFFF }
                        ?.let { cp -> StringBuilder().also { it.appendCodePointCompat(cp) }.toString() }

                else -> named[body]
            }
            if (decoded != null) {
                sb.append(decoded)
                i = semi + 1
            } else {
                sb.append('&')
                i++
            }
        }
        return sb.toString()
    }
}

sealed interface XmlToken {
    data class StartTag(
        val name: String,
        val attributes: Map<String, String>,
        val selfClosing: Boolean,
    ) : XmlToken

    data class EndTag(val name: String) : XmlToken

    data class Text(val content: String) : XmlToken
}

/** HTML elements that never have a closing tag. */
private val VOID_ELEMENTS = setOf(
    "area", "base", "br", "col", "embed", "hr", "img", "input",
    "link", "meta", "param", "source", "track", "wbr",
)

/** Elements whose content is raw text, not markup. */
private val RAW_TEXT_ELEMENTS = setOf("script", "style")

object XmlTokenizer {

    fun tokenize(input: String): List<XmlToken> {
        val tokens = ArrayList<XmlToken>()
        var i = 0
        val n = input.length

        while (i < n) {
            val lt = input.indexOf('<', i)
            if (lt < 0) {
                appendText(tokens, input.substring(i))
                break
            }
            if (lt > i) appendText(tokens, input.substring(i, lt))

            // Comments, CDATA, doctype and processing instructions carry no text.
            if (input.startsWith("<!--", lt)) {
                val end = input.indexOf("-->", lt + 4)
                i = if (end < 0) n else end + 3
                continue
            }
            if (input.startsWith("<![CDATA[", lt)) {
                val end = input.indexOf("]]>", lt + 9)
                val content = if (end < 0) input.substring(lt + 9) else input.substring(lt + 9, end)
                if (content.isNotEmpty()) tokens.add(XmlToken.Text(content))
                i = if (end < 0) n else end + 3
                continue
            }
            if (input.startsWith("<!", lt) || input.startsWith("<?", lt)) {
                val end = input.indexOf('>', lt)
                i = if (end < 0) n else end + 1
                continue
            }

            val gt = findTagEnd(input, lt)
            if (gt < 0) {
                // Unterminated '<' — treat the remainder as text.
                appendText(tokens, input.substring(lt))
                break
            }
            val raw = input.substring(lt + 1, gt).trim()
            if (raw.isEmpty()) {
                appendText(tokens, "<")
                i = gt + 1
                continue
            }

            if (raw.startsWith("/")) {
                val name = raw.substring(1).trim().lowercase()
                if (name.isNotEmpty()) tokens.add(XmlToken.EndTag(name))
                i = gt + 1
                continue
            }

            val selfClosing = raw.endsWith("/")
            val body = if (selfClosing) raw.dropLast(1) else raw
            val (name, attrs) = parseTag(body)
            if (name.isEmpty()) {
                i = gt + 1
                continue
            }
            val isVoid = name in VOID_ELEMENTS
            tokens.add(XmlToken.StartTag(name, attrs, selfClosing || isVoid))
            i = gt + 1

            // <script>/<style> bodies are raw text; skip to the matching close.
            if (!selfClosing && !isVoid && name in RAW_TEXT_ELEMENTS) {
                val close = indexOfClosingTag(input, name, i)
                i = if (close < 0) n else close
            }
        }
        return tokens
    }

    private fun appendText(tokens: MutableList<XmlToken>, raw: String) {
        if (raw.isEmpty()) return
        tokens.add(XmlToken.Text(Entities.decode(raw)))
    }

    /** Finds the '>' that ends a tag, skipping any '>' inside quoted attributes. */
    private fun findTagEnd(input: String, start: Int): Int {
        var i = start + 1
        var quote = ' '
        while (i < input.length) {
            val c = input[i]
            when {
                quote != ' ' -> if (c == quote) quote = ' '
                c == '"' || c == '\'' -> quote = c
                c == '>' -> return i
            }
            i++
        }
        return -1
    }

    private fun indexOfClosingTag(input: String, name: String, from: Int): Int {
        var i = from
        while (i < input.length) {
            val lt = input.indexOf("</", i)
            if (lt < 0) return -1
            val gt = input.indexOf('>', lt)
            if (gt < 0) return -1
            if (input.substring(lt + 2, gt).trim().lowercase() == name) return gt + 1
            i = gt + 1
        }
        return -1
    }

    private fun parseTag(body: String): Pair<String, Map<String, String>> {
        var i = 0
        while (i < body.length && !body[i].isWhitespace()) i++
        val name = body.substring(0, i).lowercase()
        if (i >= body.length) return name to emptyMap()

        val attrs = LinkedHashMap<String, String>()
        while (i < body.length) {
            while (i < body.length && (body[i].isWhitespace() || body[i] == '/')) i++
            if (i >= body.length) break

            val keyStart = i
            while (i < body.length && !body[i].isWhitespace() && body[i] != '=') i++
            if (i == keyStart) { i++; continue }
            val key = body.substring(keyStart, i).lowercase()

            while (i < body.length && body[i].isWhitespace()) i++
            if (i >= body.length || body[i] != '=') {
                attrs[key] = ""
                continue
            }
            i++ // consume '='
            while (i < body.length && body[i].isWhitespace()) i++
            if (i >= body.length) {
                attrs[key] = ""
                break
            }

            val value: String
            val c = body[i]
            if (c == '"' || c == '\'') {
                val end = body.indexOf(c, i + 1)
                if (end < 0) {
                    value = body.substring(i + 1)
                    i = body.length
                } else {
                    value = body.substring(i + 1, end)
                    i = end + 1
                }
            } else {
                val start = i
                while (i < body.length && !body[i].isWhitespace()) i++
                value = body.substring(start, i)
            }
            attrs[key] = Entities.decode(value)
        }
        return name to attrs
    }
}

/**
 * A minimal element tree. Namespace prefixes are stripped on both element and
 * attribute names because EPUB packages disagree wildly about whether they use
 * `opf:`, `dc:`, or no prefix at all, and the reader only cares about local names.
 */
class XmlElement(
    val name: String,
    val attributes: Map<String, String>,
    val children: MutableList<XmlElement> = mutableListOf(),
    var text: String = "",
) {
    /**
     * Lookups are case-insensitive.
     *
     * The tokenizer folds tag names to lower case, which is what HTML wants but
     * not what XML vocabularies expect: an EPUB 2 NCX spells its elements
     * `navMap`/`navPoint`/`navLabel`, and a case-sensitive match against the
     * folded tree would silently find nothing and drop the table of contents.
     */
    fun child(name: String): XmlElement? {
        val key = name.lowercase()
        return children.firstOrNull { it.name == key }
    }

    fun childrenNamed(name: String): List<XmlElement> {
        val key = name.lowercase()
        return children.filter { it.name == key }
    }

    fun attr(name: String): String? = attributes[name.lowercase()]

    /** Depth-first search over the whole subtree. */
    fun descendants(name: String): List<XmlElement> {
        val key = name.lowercase()
        val out = ArrayList<XmlElement>()
        fun walk(e: XmlElement) {
            if (e.name == key) out.add(e)
            e.children.forEach(::walk)
        }
        children.forEach(::walk)
        return out
    }

    /** Concatenated text of this element and everything under it. */
    fun allText(): String = buildString {
        fun walk(e: XmlElement) {
            append(e.text)
            e.children.forEach(::walk)
        }
        walk(this@XmlElement)
    }
}

private fun stripNamespace(name: String): String {
    val colon = name.indexOf(':')
    return if (colon >= 0) name.substring(colon + 1) else name
}

/**
 * Builds an element tree from [xml]. Unmatched end tags are ignored and unclosed
 * elements are closed implicitly at end of input, so malformed XHTML still yields
 * a usable tree instead of throwing.
 */
fun parseXml(xml: String): XmlElement {
    val root = XmlElement("#document", emptyMap())
    val stack = ArrayList<XmlElement>()
    stack.add(root)

    for (token in XmlTokenizer.tokenize(xml)) {
        when (token) {
            is XmlToken.StartTag -> {
                val attrs = token.attributes.mapKeys { stripNamespace(it.key) }
                val element = XmlElement(stripNamespace(token.name), attrs)
                stack.last().children.add(element)
                if (!token.selfClosing) stack.add(element)
            }

            is XmlToken.EndTag -> {
                val name = stripNamespace(token.name)
                // Close up to the nearest matching open element; ignore if absent.
                val idx = stack.indexOfLast { it.name == name }
                if (idx > 0) {
                    while (stack.size > idx) stack.removeAt(stack.size - 1)
                }
            }

            is XmlToken.Text -> {
                val current = stack.last()
                if (current.children.isEmpty()) {
                    current.text += token.content
                } else {
                    // Text after a child element belongs to a synthetic text node so
                    // ordering is preserved for allText().
                    current.children.add(
                        XmlElement("#text", emptyMap(), text = token.content),
                    )
                }
            }
        }
    }
    return root
}
