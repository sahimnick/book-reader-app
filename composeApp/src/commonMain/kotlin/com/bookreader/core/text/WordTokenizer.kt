package com.bookreader.core.text

/** A token with its range in the source text. */
data class WordSpan(
    val start: Int,
    val endExclusive: Int,
    val text: String,
)

/**
 * Word and phrase boundaries for tap-to-look-up.
 *
 * Tapping a word has to yield the *dictionary* form, so the tokenizer keeps
 * internal apostrophes and hyphens ("don't", "well-known") but drops the
 * surrounding quotes and punctuation that would otherwise be sent to the
 * dictionary as part of the key.
 */
object WordTokenizer {

    /** Marks that join letters inside a single word rather than separating words. */
    private val INTERNAL_MARKS = charArrayOf('\'', '’', '-', '‑', '‌')

    private fun Char.isWordChar(): Boolean = isLetter() || isDigit()

    private fun Char.isInternalMark(): Boolean {
        for (m in INTERNAL_MARKS) if (this == m) return true
        return false
    }

    /**
     * Returns the word containing [offset], or the nearest word when the tap
     * lands on a space between words. Returns null if there is no word nearby.
     */
    fun wordAt(text: String, offset: Int): WordSpan? {
        if (text.isEmpty()) return null
        var pos = offset.coerceIn(0, text.length - 1)

        if (!text[pos].isWordChar()) {
            // Nudge to an adjacent word so an imprecise tap still resolves.
            val left = (pos - 1 downTo maxOf(0, pos - 3)).firstOrNull { text[it].isWordChar() }
            val right = (pos + 1 until minOf(text.length, pos + 4)).firstOrNull { text[it].isWordChar() }
            pos = when {
                left != null && right != null -> if (pos - left <= right - pos) left else right
                left != null -> left
                right != null -> right
                else -> return null
            }
        }

        var start = pos
        while (start > 0) {
            val prev = text[start - 1]
            if (prev.isWordChar()) { start--; continue }
            // An internal mark only counts when a letter sits on both sides.
            if (prev.isInternalMark() && start - 2 >= 0 && text[start - 2].isWordChar()) { start -= 2; continue }
            break
        }

        var end = pos + 1
        while (end < text.length) {
            val c = text[end]
            if (c.isWordChar()) { end++; continue }
            if (c.isInternalMark() && end + 1 < text.length && text[end + 1].isWordChar()) { end += 2; continue }
            break
        }

        if (end <= start) return null
        return WordSpan(start, end, text.substring(start, end))
    }

    /**
     * Expands an arbitrary selection to whole-word boundaries, so dragging over
     * "he cros|sed the ro|ad" looks up "crossed the road".
     */
    fun phraseIn(text: String, selectionStart: Int, selectionEnd: Int): WordSpan? {
        if (text.isEmpty()) return null
        val lo = minOf(selectionStart, selectionEnd).coerceIn(0, text.length)
        val hi = maxOf(selectionStart, selectionEnd).coerceIn(0, text.length)
        if (lo == hi) return wordAt(text, lo)

        val first = wordAt(text, lo) ?: return null
        val last = wordAt(text, (hi - 1).coerceAtLeast(lo)) ?: first
        val start = minOf(first.start, last.start)
        val end = maxOf(first.endExclusive, last.endExclusive)
        if (end <= start) return null
        return WordSpan(start, end, text.substring(start, end))
    }

    /** All words in [text], left to right. */
    fun words(text: String): List<WordSpan> {
        val out = ArrayList<WordSpan>()
        var i = 0
        while (i < text.length) {
            if (!text[i].isWordChar()) { i++; continue }
            val span = wordAt(text, i) ?: break
            out.add(span)
            i = span.endExclusive
        }
        return out
    }

    /**
     * The lookup key for a token: lowercased, stripped of edge punctuation, with
     * curly apostrophes folded to straight ones so "don’t" and "don't" hit the
     * same dictionary entry.
     */
    fun normalize(raw: String): String =
        raw.trim()
            .trim('"', '\'', '“', '”', '‘', '’', '(', ')', '[', ']', '.', ',', ';', ':', '!', '?', '«', '»')
            .replace('’', '\'')
            .replace('‑', '-')
            .lowercase()
}
