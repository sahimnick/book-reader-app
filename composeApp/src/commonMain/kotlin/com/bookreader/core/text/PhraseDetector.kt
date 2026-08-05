package com.bookreader.core.text

import com.bookreader.core.dictionary.Lemmatizer

/**
 * Finds multi-word phrases around a tapped word.
 *
 * Tapping "up" in "he finally gave up" should look up *give up*, not *up* — the
 * particle on its own is meaningless and the phrasal verb often means something
 * unrelated to its parts ("give up" is not a kind of giving). The reader taps
 * one word, so the app has to work out which phrase that word belongs to.
 *
 * Candidates are returned longest first, so a caller can try the phrase against
 * the dictionary and fall back to the single word when nothing matches. That
 * ordering is the whole contract: over-generating is safe because a miss just
 * falls through, whereas missing the phrase gives the reader a definition of
 * "up".
 */
object PhraseDetector {

    /** Particles and prepositions that form phrasal verbs. */
    private val PARTICLES = setOf(
        "up", "down", "in", "out", "on", "off", "over", "under", "away", "back",
        "through", "along", "around", "about", "across", "apart", "aside",
        "forward", "ahead", "together", "by", "for", "to", "with", "into",
        "onto", "upon", "after", "against", "at", "from", "of", "behind",
        "beyond", "past", "round", "toward", "towards", "without",
    )

    /**
     * Pronouns and articles that can sit between a verb and its particle:
     * "give it up", "put the fire out". Kept short deliberately — a long list
     * would start swallowing real objects and inventing phrases.
     */
    private val SEPARABLE_FILLERS = setOf(
        "it", "them", "him", "her", "me", "us", "you", "this", "that",
        "these", "those", "the", "a", "an", "my", "your", "his", "their", "our",
    )

    /**
     * Verbs that actually form phrasal verbs.
     *
     * Without this, any noun before a particle becomes a phrase — "money away"
     * out of "he gave money away". English has no way to tell a verb from a
     * noun by shape, so precision here needs a list. It covers the heads that
     * carry the overwhelming majority of phrasal verbs; an unlisted verb simply
     * falls back to single-word lookup rather than producing a wrong phrase.
     */
    private val PHRASAL_VERB_HEADS = setOf(
        "act", "add", "ask", "back", "be", "bear", "beat", "blow", "break",
        "bring", "build", "burn", "call", "calm", "care", "carry", "catch",
        "check", "cheer", "clean", "clear", "close", "come", "count", "cut",
        "deal", "die", "do", "draw", "dress", "drink", "drive", "drop", "eat",
        "end", "face", "fall", "feel", "figure", "fill", "find", "fit", "fix",
        "follow", "get", "give", "go", "grow", "hand", "hang", "have", "head",
        "hold", "join", "jump", "keep", "kick", "knock", "lay", "lead", "leave",
        "let", "lie", "live", "lock", "log", "look", "make", "mix", "move",
        "open", "pack", "pass", "pay", "pick", "play", "point", "pull", "push",
        "put", "read", "ring", "rule", "run", "rush", "save", "see", "sell",
        "send", "set", "settle", "share", "show", "shut", "sign", "sit",
        "sleep", "slow", "sort", "sound", "speak", "split", "stand", "start",
        "stay", "step", "stick", "stop", "sum", "switch", "take", "talk",
        "team", "tear", "tell", "think", "throw", "tidy", "tie", "top", "try",
        "turn", "use", "wake", "walk", "warm", "wash", "watch", "wear", "wind",
        "wipe", "work", "write",
    )

    /** Longest sensible phrase to propose, in words ("put up with" is three). */
    private const val MAX_PHRASE_WORDS = 3

    /**
     * Phrase candidates covering the word at [offset], longest first, with the
     * single tapped word always last so there is always something to look up.
     */
    fun candidatesAt(text: String, offset: Int): List<WordSpan> {
        val words = WordTokenizer.words(text)
        if (words.isEmpty()) return emptyList()

        val tapped = words.indexOfFirst { offset in it.start until it.endExclusive }
            .takeIf { it >= 0 }
            ?: words.indexOfFirst { it.start > offset }.takeIf { it > 0 }?.minus(1)
            ?: words.lastIndex

        val out = LinkedHashSet<WordSpan>()

        for (size in MAX_PHRASE_WORDS downTo 2) {
            // Every window of this size that still contains the tapped word.
            for (start in (tapped - size + 1)..tapped) {
                val end = start + size - 1
                if (start < 0 || end > words.lastIndex) continue
                val window = words.subList(start, end + 1)
                if (!isPhrase(window)) continue
                val span = WordSpan(
                    start = window.first().start,
                    endExclusive = window.last().endExclusive,
                    text = text.substring(window.first().start, window.last().endExclusive),
                )
                out.add(span)
            }
        }

        out.add(words[tapped])
        return out.toList()
    }

    /** The normalised lookup keys for [candidatesAt], in the same order. */
    fun candidateKeysAt(text: String, offset: Int): List<String> =
        candidatesAt(text, offset).map { normalizePhrase(it.text) }

    /**
     * Decides whether a run of words looks like a phrasal verb.
     *
     * The rule is deliberately conservative: the run must end in a particle,
     * must not *start* with one (that would make "up the hill" a phrase), and
     * any middle word must be a particle or a short filler. Without these
     * guards ordinary prose produces a phrase candidate at almost every tap.
     */
    private fun isPhrase(window: List<WordSpan>): Boolean {
        if (window.size < 2) return false
        val keys = window.map { normalizeWord(it.text) }
        if (keys.any { it.isEmpty() }) return false

        val head = keys.first()
        if (head in PARTICLES) return false
        // A head that is purely numeric is never a verb.
        if (head.all { it.isDigit() }) return false
        // Inflected forms must resolve to a listed verb: "gave" -> "give".
        if (Lemmatizer.candidates(head).none { it in PHRASAL_VERB_HEADS }) return false

        if (keys.last() !in PARTICLES) return false

        for (middle in keys.subList(1, keys.size - 1)) {
            if (middle !in PARTICLES && middle !in SEPARABLE_FILLERS) return false
        }
        return true
    }

    private fun normalizeWord(raw: String): String = WordTokenizer.normalize(raw)

    /** Collapses whitespace and lowercases, so "Gave   Up" keys as "gave up". */
    fun normalizePhrase(raw: String): String =
        raw.trim()
            .split(' ', '\t', '\n')
            .filter { it.isNotBlank() }
            .joinToString(" ") { WordTokenizer.normalize(it) }
            .trim()
}
