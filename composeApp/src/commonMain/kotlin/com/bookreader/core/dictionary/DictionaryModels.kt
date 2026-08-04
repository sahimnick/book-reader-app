package com.bookreader.core.dictionary

/** Part of speech, kept coarse because that is all a reader's popup needs. */
enum class PartOfSpeech(val label: String) {
    NOUN("noun"),
    VERB("verb"),
    ADJECTIVE("adjective"),
    ADVERB("adverb"),
    PRONOUN("pronoun"),
    PREPOSITION("preposition"),
    CONJUNCTION("conjunction"),
    INTERJECTION("interjection"),
    PHRASE("phrase"),
    OTHER("other"),
    ;

    companion object {
        fun parse(raw: String?): PartOfSpeech {
            val v = raw?.trim()?.lowercase().orEmpty()
            return when {
                v.startsWith("n") -> NOUN
                v.startsWith("v") -> VERB
                v.startsWith("adj") -> ADJECTIVE
                v.startsWith("adv") -> ADVERB
                v.startsWith("pron") -> PRONOUN
                v.startsWith("prep") -> PREPOSITION
                v.startsWith("conj") -> CONJUNCTION
                v.startsWith("interj") -> INTERJECTION
                v.startsWith("phr") -> PHRASE
                else -> OTHER
            }
        }
    }
}

/** One meaning of a headword. */
data class Sense(
    val partOfSpeech: PartOfSpeech,
    /** English definition. */
    val english: String,
    /** Persian meaning(s), most common first. */
    val persian: List<String>,
    /** Example sentences showing the word in use. */
    val examples: List<String> = emptyList(),
    val synonyms: List<String> = emptyList(),
)

/**
 * A dictionary result.
 *
 * [headword] is the form actually found, which may differ from what the reader
 * tapped ("running" -> "run"); the UI shows both so the lookup is never
 * silently answering a different question than the one asked.
 */
data class WordEntry(
    val headword: String,
    /** Exactly what the user tapped or selected. */
    val queried: String,
    /** IPA pronunciation, e.g. /ˈrʌnɪŋ/. */
    val pronunciation: String? = null,
    /** Spelled-out form for the "spelling" affordance: R-U-N-N-I-N-G. */
    val spelling: String = spellOut(queried),
    val senses: List<Sense> = emptyList(),
    val source: String? = null,
) {
    val isEmpty: Boolean get() = senses.isEmpty()

    /** The single best Persian gloss, for compact display. */
    val primaryPersian: String? get() = senses.firstNotNullOfOrNull { it.persian.firstOrNull() }

    /** The single best English definition. */
    val primaryEnglish: String? get() = senses.firstOrNull()?.english

    companion object {
        fun notFound(queried: String): WordEntry = WordEntry(headword = queried, queried = queried)
    }
}

/** Renders a word as separated letters for the spelling affordance. */
fun spellOut(word: String): String =
    word.trim()
        .filter { it.isLetter() || it.isDigit() || it == '-' || it == '\'' }
        .map { it.uppercaseChar() }
        .joinToString("-")

/**
 * A source of dictionary data.
 *
 * Implementations are tried in order by [CompositeDictionary], so a bundled
 * offline database can answer instantly while an online source fills the gaps
 * only when the device has connectivity.
 */
interface DictionaryProvider {
    val name: String

    /** True when this provider can answer without a network round-trip. */
    val isOffline: Boolean

    /** Looks up a single word. Returns null when the provider has no entry. */
    suspend fun lookup(word: String): WordEntry?

    /** Looks up a multi-word phrase. Defaults to no result. */
    suspend fun lookupPhrase(phrase: String): WordEntry? = null
}

/**
 * Tries each provider in turn, applying [Lemmatizer] candidates so an inflected
 * word still resolves.
 *
 * Offline providers are consulted first regardless of declaration order: a
 * reader tapping a word expects an instant answer, and falling back to the
 * network only when the local database misses keeps the common case fast.
 */
class CompositeDictionary(
    providers: List<DictionaryProvider>,
) : DictionaryProvider {

    private val ordered = providers.sortedByDescending { it.isOffline }

    override val name: String = "composite"
    override val isOffline: Boolean = ordered.any { it.isOffline }

    override suspend fun lookup(word: String): WordEntry? {
        val candidates = Lemmatizer.candidates(word)
        if (candidates.isEmpty()) return null

        for (provider in ordered) {
            for (candidate in candidates) {
                val hit = runCatching { provider.lookup(candidate) }.getOrNull()
                if (hit != null && !hit.isEmpty) {
                    // Report what the reader actually tapped, not the lemma.
                    return hit.copy(queried = word, spelling = spellOut(word))
                }
            }
        }
        return null
    }

    override suspend fun lookupPhrase(phrase: String): WordEntry? {
        val normalized = phrase.trim().lowercase()
        if (normalized.isEmpty()) return null

        for (provider in ordered) {
            val hit = runCatching { provider.lookupPhrase(normalized) }.getOrNull()
            if (hit != null && !hit.isEmpty) return hit.copy(queried = phrase)
        }
        // A phrase with no entry of its own still deserves an answer: fall back
        // to the head word so the reader gets something rather than nothing.
        val words = normalized.split(' ', '‌').filter { it.isNotBlank() }
        if (words.size > 1) {
            val head = words.last()
            val hit = lookup(head) ?: return null
            return hit.copy(queried = phrase, spelling = spellOut(phrase))
        }
        return null
    }
}
