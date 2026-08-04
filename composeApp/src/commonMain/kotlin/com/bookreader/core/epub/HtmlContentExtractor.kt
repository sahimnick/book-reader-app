package com.bookreader.core.epub

import com.bookreader.core.model.BlockStyle
import com.bookreader.core.model.ContentBlock
import com.bookreader.core.xml.XmlToken
import com.bookreader.core.xml.XmlTokenizer

/**
 * Turns an EPUB XHTML content document into renderable blocks.
 *
 * This is deliberately not a browser. It keeps the structure a reader actually
 * uses — headings, paragraphs, quotes, list items, images — and discards
 * everything else, because the alternative (a WebView per chapter) costs the
 * shared TTS highlighting and word-tap handling that this app is built around.
 */
object HtmlContentExtractor {

    private val BLOCK_ELEMENTS = setOf(
        "p", "div", "section", "article", "aside", "header", "footer", "main",
        "h1", "h2", "h3", "h4", "h5", "h6", "li", "dd", "dt", "blockquote",
        "pre", "figcaption", "caption", "td", "th", "tr", "table", "ul", "ol",
        "dl", "figure", "nav", "body", "hgroup", "address",
    )

    /**
     * Elements whose *text* must be skipped. The tokenizer already discards
     * `<script>`/`<style>` bodies wholesale (and emits no matching end tag for
     * them), so those are handled separately in [RAW_TEXT_ELEMENTS] — counting
     * them here would leave the skip depth permanently stuck above zero.
     */
    private val SKIPPED_ELEMENTS = setOf("head", "title", "meta", "link")

    private val RAW_TEXT_ELEMENTS = setOf("script", "style")

    private fun styleFor(tag: String, inQuote: Boolean, inPre: Boolean): BlockStyle = when {
        inPre -> BlockStyle.PREFORMATTED
        tag == "h1" -> BlockStyle.HEADING_1
        tag == "h2" -> BlockStyle.HEADING_2
        tag == "h3" || tag == "h4" || tag == "h5" || tag == "h6" -> BlockStyle.HEADING_3
        tag == "li" || tag == "dd" || tag == "dt" -> BlockStyle.LIST_ITEM
        tag == "figcaption" || tag == "caption" -> BlockStyle.CAPTION
        inQuote -> BlockStyle.QUOTE
        else -> BlockStyle.PARAGRAPH
    }

    /**
     * Extracts blocks from [xhtml]. [resolveHref] maps a relative image `src`
     * onto a path inside the EPUB archive; returning null drops the image.
     */
    fun extract(
        xhtml: String,
        resolveHref: (String) -> String? = { it },
    ): List<ContentBlock> {
        val blocks = ArrayList<ContentBlock>()
        val current = StringBuilder()
        var charOffset = 0

        // Tag context. Depth counters survive the unbalanced markup that the
        // tolerant tokenizer happily emits.
        var quoteDepth = 0
        var preDepth = 0
        var skipDepth = 0
        var currentTag = "p"
        val openBlocks = ArrayList<String>()

        fun flush(style: BlockStyle? = null) {
            val text = if (preDepth > 0) current.toString().trimEnd() else normalize(current.toString())
            current.clear()
            if (text.isBlank()) return
            val resolved = style ?: styleFor(currentTag, quoteDepth > 0, preDepth > 0)
            blocks.add(
                ContentBlock(
                    index = blocks.size,
                    text = text,
                    style = resolved,
                    charOffset = charOffset,
                ),
            )
            charOffset += text.length + 1 // +1 for the newline flattenedText inserts
        }

        for (token in XmlTokenizer.tokenize(xhtml)) {
            when (token) {
                is XmlToken.StartTag -> {
                    val tag = token.name
                    if (tag in RAW_TEXT_ELEMENTS) continue
                    if (tag in SKIPPED_ELEMENTS) {
                        if (!token.selfClosing) skipDepth++
                        continue
                    }
                    if (skipDepth > 0) continue

                    when {
                        tag == "br" -> current.append('\n')

                        tag == "hr" -> {
                            flush()
                            blocks.add(
                                ContentBlock(
                                    index = blocks.size,
                                    text = "",
                                    style = BlockStyle.SEPARATOR,
                                    charOffset = charOffset,
                                ),
                            )
                        }

                        tag == "img" || tag == "image" -> {
                            val src = token.attributes["src"]
                                ?: token.attributes["href"]
                                ?: token.attributes["xlink:href"]
                            val alt = token.attributes["alt"].orEmpty()
                            flush()
                            val resolved = src?.let(resolveHref)
                            if (resolved != null) {
                                blocks.add(
                                    ContentBlock(
                                        index = blocks.size,
                                        text = alt,
                                        style = BlockStyle.IMAGE,
                                        charOffset = charOffset,
                                        imageHref = resolved,
                                    ),
                                )
                            }
                        }

                        tag in BLOCK_ELEMENTS -> {
                            flush()
                            if (!token.selfClosing) {
                                openBlocks.add(tag)
                                currentTag = tag
                                if (tag == "blockquote") quoteDepth++
                                if (tag == "pre") preDepth++
                            }
                        }
                        // Inline elements (em, strong, a, span…) just contribute text.
                    }
                }

                is XmlToken.EndTag -> {
                    val tag = token.name
                    if (tag in RAW_TEXT_ELEMENTS) continue
                    if (tag in SKIPPED_ELEMENTS) {
                        if (skipDepth > 0) skipDepth--
                        continue
                    }
                    if (skipDepth > 0) continue

                    if (tag in BLOCK_ELEMENTS) {
                        flush()
                        if (tag == "blockquote" && quoteDepth > 0) quoteDepth--
                        if (tag == "pre" && preDepth > 0) preDepth--
                        val idx = openBlocks.indexOfLast { it == tag }
                        if (idx >= 0) {
                            while (openBlocks.size > idx) openBlocks.removeAt(openBlocks.size - 1)
                        }
                        currentTag = openBlocks.lastOrNull() ?: "p"
                    }
                }

                is XmlToken.Text -> {
                    if (skipDepth > 0) continue
                    current.append(token.content)
                }
            }
        }

        flush()
        return blocks
    }

    /** Collapses HTML whitespace: runs of space/newline become a single space. */
    private fun normalize(raw: String): String {
        val sb = StringBuilder(raw.length)
        var pendingSpace = false
        for (c in raw) {
            if (c.isWhitespace()) {
                pendingSpace = sb.isNotEmpty()
                continue
            }
            if (pendingSpace) {
                sb.append(' ')
                pendingSpace = false
            }
            sb.append(c)
        }
        return sb.toString()
    }
}
