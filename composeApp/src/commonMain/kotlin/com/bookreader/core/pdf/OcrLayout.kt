package com.bookreader.core.pdf

import com.bookreader.core.model.BlockStyle
import com.bookreader.core.model.ContentBlock
import com.bookreader.core.model.ContentDocument

/**
 * One word as an OCR engine reported it, with its box normalised to 0..1 of the
 * page and a top-left origin.
 *
 * [charOffset] is assigned by [OcrLayout]; engines report words in whatever
 * order they happened to detect them, which is not reading order.
 */
data class RecognizedWord(
    val text: String,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val charOffset: Int = 0,
) {
    val centerY: Float get() = (top + bottom) / 2f
    val height: Float get() = bottom - top
}

/**
 * Turns loose OCR output into a page the reader can use.
 *
 * A scanned PDF has no text layer at all, so recognition is the only way it can
 * be read aloud, tapped for a definition, or selected. But engines emit words
 * grouped by detection region, not by reading order, and the rest of the app
 * depends on reading order: sentence segmentation splits the flattened text,
 * and drag-selection walks `charOffset` from the first tapped word to the last.
 * Get the ordering wrong and speech reads the page out of sequence while
 * selection grabs unrelated words.
 *
 * So this groups words into lines by vertical overlap, orders lines down the
 * page and words across each line, and assigns offsets from that order. It is
 * pure arithmetic, which is why it lives here and is tested rather than being
 * buried in platform code that only runs on a device.
 */
object OcrLayout {

    /**
     * Two words share a line when their vertical centres are closer than this
     * fraction of their height. Generous enough to survive the wobble of a
     * scanned page, tight enough not to merge adjacent lines.
     */
    private const val LINE_TOLERANCE = 0.6f

    /**
     * A vertical gap larger than this many line-heights starts a new paragraph.
     * Paragraphs matter because each becomes a block, and blocks are what the
     * speech plan and the highlight track.
     */
    private const val PARAGRAPH_GAP = 1.8f

    /** Groups words into lines, top to bottom, each ordered left to right. */
    fun toLines(words: List<RecognizedWord>): List<List<RecognizedWord>> {
        val usable = words.filter { it.text.isNotBlank() && it.right > it.left }
        if (usable.isEmpty()) return emptyList()

        val lines = ArrayList<MutableList<RecognizedWord>>()
        for (word in usable.sortedBy { it.centerY }) {
            val line = lines.lastOrNull()
            val reference = line?.lastOrNull()
            val tolerance = maxOf(word.height, reference?.height ?: word.height) * LINE_TOLERANCE
            if (line != null && reference != null &&
                kotlin.math.abs(reference.centerY - word.centerY) <= tolerance
            ) {
                line.add(word)
            } else {
                lines.add(mutableListOf(word))
            }
        }
        return lines.map { it.sortedBy { w -> w.left } }
    }

    /**
     * Builds a page from recognised words: paragraph blocks in reading order,
     * and the same words re-emitted with [RecognizedWord.charOffset] pointing
     * into the flattened text.
     */
    fun layout(words: List<RecognizedWord>, pageId: String): OcrPage {
        val lines = toLines(words)
        if (lines.isEmpty()) return OcrPage(ContentDocument(pageId, null, emptyList()), emptyList())

        // Split lines into paragraphs on an unusually large vertical gap.
        val paragraphs = ArrayList<MutableList<List<RecognizedWord>>>()
        for (line in lines) {
            val previous = paragraphs.lastOrNull()?.lastOrNull()
            val gap = if (previous == null) 0f else line.first().centerY - previous.first().centerY
            val lineHeight = line.first().height.takeIf { it > 0f } ?: 0.02f
            if (previous == null || gap > lineHeight * PARAGRAPH_GAP) {
                paragraphs.add(mutableListOf(line))
            } else {
                paragraphs.last().add(line)
            }
        }

        val blocks = ArrayList<ContentBlock>()
        val placed = ArrayList<RecognizedWord>()
        var offset = 0

        for (paragraph in paragraphs) {
            val builder = StringBuilder()
            for (line in paragraph) {
                for (word in line) {
                    if (builder.isNotEmpty()) builder.append(' ')
                    // Offset is relative to the flattened document, which is
                    // what tap and selection resolve against.
                    placed.add(word.copy(charOffset = offset + builder.length))
                    builder.append(word.text)
                }
            }
            val text = builder.toString()
            if (text.isBlank()) continue
            blocks.add(
                ContentBlock(
                    index = blocks.size,
                    text = text,
                    style = BlockStyle.PARAGRAPH,
                    charOffset = offset,
                ),
            )
            offset += text.length + 1 // +1 for the newline flattenedText inserts
        }

        return OcrPage(ContentDocument(pageId, null, blocks), placed)
    }
}

/** The result of laying out one recognised page. */
data class OcrPage(
    val document: ContentDocument,
    val words: List<RecognizedWord>,
) {
    val isEmpty: Boolean get() = words.isEmpty()
}
