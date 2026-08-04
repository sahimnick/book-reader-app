package com.bookreader.core.model

/**
 * How a block should be rendered. Kept deliberately small — a reader needs
 * enough structure to look right and to let text-to-speech pause sensibly
 * between headings and body copy, not a faithful CSS box model.
 */
enum class BlockStyle {
    TITLE,
    HEADING_1,
    HEADING_2,
    HEADING_3,
    PARAGRAPH,
    QUOTE,
    LIST_ITEM,
    PREFORMATTED,
    CAPTION,
    IMAGE,
    SEPARATOR,
}

/**
 * One renderable unit of a chapter or PDF page.
 *
 * [charOffset] is the block's start position within the chapter's flattened
 * text. Text-to-speech runs over the flattened string, so this is what maps an
 * engine progress callback back to the block on screen.
 */
data class ContentBlock(
    val index: Int,
    val text: String,
    val style: BlockStyle,
    val charOffset: Int = 0,
    val imageHref: String? = null,
) {
    val isSpoken: Boolean
        get() = style != BlockStyle.IMAGE && style != BlockStyle.SEPARATOR && text.isNotBlank()
}

/** A chapter (EPUB spine item) or a single PDF page, ready to render and speak. */
data class ContentDocument(
    val id: String,
    val title: String?,
    val blocks: List<ContentBlock>,
) {
    /**
     * The blocks joined into one string, which is what the TTS engine speaks and
     * what sentence offsets are relative to. Blocks are separated by a newline so
     * the segmenter treats them as separate sentences.
     */
    val flattenedText: String by lazy {
        buildString {
            for (block in blocks) {
                if (!block.isSpoken) continue
                if (isNotEmpty()) append('\n')
                append(block.text)
            }
        }
    }

    companion object {
        val Empty = ContentDocument(id = "", title = null, blocks = emptyList())
    }
}
