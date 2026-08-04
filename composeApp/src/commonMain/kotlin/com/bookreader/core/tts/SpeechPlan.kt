package com.bookreader.core.tts

import com.bookreader.core.model.ContentBlock
import com.bookreader.core.model.ContentDocument
import com.bookreader.core.text.Sentence
import com.bookreader.core.text.SentenceSegmenter

/**
 * The read-aloud script for one chapter or page: the sentences to speak, in
 * order, each tied back to the block it came from.
 *
 * Speech is driven one sentence per utterance rather than by handing the engine
 * a whole chapter. Both Android and iOS report progress as character ranges,
 * but only within the string they were given, and neither guarantees range
 * callbacks at all (Android's `onRangeStart` needs API 26 and a cooperating
 * engine). Speaking sentence by sentence makes the highlight exact everywhere,
 * and makes pause, resume and "skip to next sentence" trivial to implement.
 */
class SpeechPlan(val document: ContentDocument) {

    /** The text the sentences index into — identical to what gets spoken. */
    val text: String = document.flattenedText

    val sentences: List<Sentence> = SentenceSegmenter.segment(text)

    /** Blocks that contribute to [text], in the same order. */
    private val spokenBlocks: List<ContentBlock> = document.blocks.filter { it.isSpoken }

    val size: Int get() = sentences.size

    val isEmpty: Boolean get() = sentences.isEmpty()

    fun sentenceAt(index: Int): Sentence? = sentences.getOrNull(index)

    /**
     * The block a sentence belongs to, as an index into [ContentDocument.blocks].
     * Returns -1 when the plan is empty or the sentence cannot be placed.
     */
    fun blockIndexForSentence(sentenceIndex: Int): Int {
        val sentence = sentences.getOrNull(sentenceIndex) ?: return -1
        val block = spokenBlocks.lastOrNull { it.charOffset <= sentence.start } ?: return -1
        return block.index
    }

    /**
     * The first sentence of a block — used when the reader taps a paragraph to
     * start speaking from there.
     */
    fun firstSentenceOfBlock(blockIndex: Int): Int {
        val block = document.blocks.getOrNull(blockIndex) ?: return 0
        if (!block.isSpoken) return 0
        val index = sentences.indexOfFirst { it.start >= block.charOffset }
        return if (index >= 0) index else 0
    }

    /** The sentence covering a character offset in [text]. */
    fun sentenceIndexAtChar(offset: Int): Int {
        if (sentences.isEmpty()) return -1
        val hit = sentences.firstOrNull { offset in it }
        if (hit != null) return hit.index
        // Offsets that land in the whitespace between sentences resolve to the
        // sentence that just finished, so the highlight never blanks out.
        return sentences.lastOrNull { it.endExclusive <= offset }?.index ?: 0
    }

    /**
     * Translates an engine range report, which is relative to the single spoken
     * sentence, into an absolute range in [text] for word-level highlighting.
     */
    fun absoluteRange(sentenceIndex: Int, localStart: Int, localEnd: Int): IntRange? {
        val sentence = sentences.getOrNull(sentenceIndex) ?: return null
        val start = (sentence.start + localStart).coerceIn(sentence.start, sentence.endExclusive)
        val end = (sentence.start + localEnd).coerceIn(start, sentence.endExclusive)
        if (end <= start) return null
        return start until end
    }

    companion object {
        val Empty = SpeechPlan(ContentDocument.Empty)
    }
}
