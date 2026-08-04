package com.bookreader.core.text

/**
 * A sentence with its character range in the source text.
 *
 * The offsets matter as much as the text: the TTS engine reports progress as
 * character ranges inside the string it was handed, and the reader maps those
 * back onto the rendered page to draw the highlight. Losing the offsets would
 * mean re-searching for the sentence, which breaks as soon as a book repeats a
 * line ("Yes." appears a hundred times in a novel).
 */
data class Sentence(
    val index: Int,
    val start: Int,
    val endExclusive: Int,
    val text: String,
) {
    val length: Int get() = endExclusive - start

    operator fun contains(offset: Int): Boolean = offset in start until endExclusive
}

/**
 * Splits prose into sentences without any platform text-boundary API.
 *
 * `java.text.BreakIterator` exists on Android and `NSLinguisticTagger` on iOS,
 * but they disagree on edge cases, which would make the highlight land on
 * different sentences per platform for the same book. A single shared
 * implementation keeps playback identical everywhere and is directly testable.
 */
object SentenceSegmenter {

    private val TERMINATORS = charArrayOf('.', '!', '?', '…', '؟', '۔', '。', '！', '？')

    /** Closers that belong to the sentence they follow: `He said "stop." Then…` */
    private val TRAILING_CLOSERS = charArrayOf('"', '\'', ')', ']', '}', '»', '”', '’', '›')

    /**
     * Words that end in a period without ending a sentence. Stored without the
     * trailing dot and lowercased.
     */
    private val ABBREVIATIONS = setOf(
        "mr", "mrs", "ms", "dr", "prof", "sr", "jr", "st", "mt", "rev", "hon",
        "pres", "gen", "col", "capt", "lt", "sgt", "gov", "sen", "rep", "adm",
        "vs", "etc", "al", "inc", "ltd", "co", "corp", "dept", "est", "fig",
        "no", "vol", "pp", "ed", "eds", "cf", "viz", "approx", "appt", "apt",
        "jan", "feb", "mar", "apr", "jun", "jul", "aug", "sep", "sept", "oct",
        "nov", "dec", "mon", "tue", "tues", "wed", "thu", "thur", "thurs",
        "fri", "sat", "sun",
    )

    /**
     * Segments [text] into sentences.
     *
     * A newline always ends a sentence even without punctuation. Chapters are
     * flattened by joining blocks with `\n`, and a heading rarely carries a full
     * stop — without this rule "Chapter One" would be spoken as part of the
     * first paragraph and highlighted as one unit with it. Within a block,
     * whitespace is already collapsed, so the only newlines that survive are
     * genuine structural breaks.
     */
    fun segment(text: String): List<Sentence> {
        if (text.isBlank()) return emptyList()

        val sentences = ArrayList<Sentence>()
        var sentenceStart = 0
        var i = 0

        while (i < text.length) {
            val c = text[i]

            if (c == '\n') {
                val end = i
                emit(text, sentenceStart, end, sentences)
                i = skipWhitespace(text, i)
                sentenceStart = i
                continue
            }

            if (!c.isTerminator()) {
                i++
                continue
            }

            if (c == '.' && isNonBoundaryPeriod(text, i)) {
                i++
                continue
            }

            // Absorb runs like "?!" and "..." into one boundary.
            var end = i + 1
            while (end < text.length && text[end].isTerminator()) end++
            // Then any quotes/brackets that close the sentence.
            while (end < text.length && text[end] in TRAILING_CLOSERS) end++

            if (!isBoundaryAt(text, end, c)) {
                i = end
                continue
            }

            emit(text, sentenceStart, end, sentences)
            i = skipWhitespace(text, end)
            sentenceStart = i
        }

        if (sentenceStart < text.length) emit(text, sentenceStart, text.length, sentences)
        return sentences
    }

    /** Convenience for callers that only need the strings. */
    fun segmentToStrings(text: String): List<String> = segment(text).map { it.text }

    /** The sentence containing [offset], or null when the offset falls in trailing space. */
    fun sentenceAt(sentences: List<Sentence>, offset: Int): Sentence? =
        sentences.firstOrNull { offset in it } ?: sentences.lastOrNull { it.endExclusive <= offset }

    private fun Char.isTerminator(): Boolean {
        for (t in TERMINATORS) if (this == t) return true
        return false
    }

    private fun emit(source: String, start: Int, endExclusive: Int, out: MutableList<Sentence>) {
        var s = start
        var e = endExclusive
        while (s < e && source[s].isWhitespace()) s++
        while (e > s && source[e - 1].isWhitespace()) e--
        if (e <= s) return
        out.add(Sentence(index = out.size, start = s, endExclusive = e, text = source.substring(s, e)))
    }

    private fun skipWhitespace(text: String, from: Int): Int {
        var i = from
        while (i < text.length && text[i].isWhitespace()) i++
        return i
    }

    /**
     * Detects periods that do not end a sentence: decimals (`3.14`), initials
     * (`J. R. R. Tolkien`), dotted acronyms (`U.S.A.`) and known abbreviations.
     */
    private fun isNonBoundaryPeriod(text: String, dot: Int): Boolean {
        val prev = text.getOrNull(dot - 1)
        val next = text.getOrNull(dot + 1)

        // 3.14 — digit on both sides.
        if (prev != null && next != null && prev.isDigit() && next.isDigit()) return true

        // Walk back over the token that precedes the dot. Acronyms are caught by
        // the internal-dot check below, not by looking ahead: a following '.'
        // means an ellipsis ("away...") far more often than an acronym.
        var start = dot
        while (start > 0 && (text[start - 1].isLetter() || text[start - 1] == '.')) start--
        val token = text.substring(start, dot)
        if (token.isEmpty()) return false

        // Single letter → an initial, not a sentence end.
        if (token.length == 1 && token[0].isLetter() && token[0].isUpperCase()) return true

        // Internal dots (e.g., i.e., U.S.) mean an acronym.
        if (token.contains('.')) return true

        return token.lowercase() in ABBREVIATIONS
    }

    /**
     * Decides whether position [end] really starts a new sentence.
     *
     * A lowercase Latin letter after a period is almost always an abbreviation
     * that slipped past the word list, so it is not treated as a boundary.
     * Caseless scripts (Persian, Arabic) have no such signal and always qualify.
     */
    private fun isBoundaryAt(text: String, end: Int, terminator: Char): Boolean {
        if (end >= text.length) return true

        // CJK terminators are not followed by a space.
        val cjk = terminator == '。' || terminator == '！' || terminator == '？'

        var j = end
        var sawSpace = false
        while (j < text.length && text[j].isWhitespace()) { sawSpace = true; j++ }
        if (!sawSpace && !cjk) return false
        if (j >= text.length) return true

        val c = text[j]
        return when {
            c.isDigit() -> true
            c.isUpperCase() -> true
            // Caseless letter (Persian/Arabic/CJK): no case signal available.
            c.isLetter() && !c.isLowerCase() -> true
            c == '"' || c == '\'' || c == '“' || c == '‘' || c == '«' || c == '(' || c == '[' -> true
            c == '—' || c == '–' -> true
            else -> false
        }
    }
}
