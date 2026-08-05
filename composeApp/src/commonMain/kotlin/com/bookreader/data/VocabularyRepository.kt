package com.bookreader.data

import com.bookreader.core.text.WordTokenizer
import com.bookreader.core.vocab.ReaderProfile
import com.bookreader.core.vocab.TextFit
import com.bookreader.core.vocab.Vocabulary
import com.bookreader.core.vocab.WordStat
import com.bookreader.db.BookReaderDb
import com.bookreader.platform.currentTimeMillis
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The reading profile: what the reader looks up, and what that says about them.
 *
 * Every look-up is recorded, whether or not it becomes a flashcard — most are
 * not, and a word passed over is still evidence that it was not known. Cards
 * the reader keeps failing count too, and count for more.
 *
 * The profile is cached rather than rebuilt per word: marking a chapter asks
 * about several thousand tokens, and rebuilding from the database for each one
 * would make turning a page visibly slow.
 */
class VocabularyRepository(private val db: BookReaderDb) {

    private var cached: ReaderProfile? = null

    /** Records that the reader stopped on [word]. */
    suspend fun recordLookup(word: String) = withContext(Dispatchers.Default) {
        val key = WordTokenizer.normalize(word)
        // Phrases are looked up too, but a reading level is about single words:
        // "give up" being unknown says nothing about whether "up" is.
        if (key.isEmpty() || key.length < 3 || ' ' in key) return@withContext
        val now = currentTimeMillis()
        db.bookReaderQueries.transaction {
            db.bookReaderQueries.insertWordIfAbsent(key, now)
            db.bookReaderQueries.bumpWord(now, key)
        }
        cached = null
    }

    /** The reader's current profile, rebuilt only when the history changed. */
    suspend fun profile(): ReaderProfile = cached ?: withContext(Dispatchers.Default) {
        val stats = db.bookReaderQueries.selectWordHistory().executeAsList().map {
            WordStat(word = it.word, lookups = it.lookups.toInt())
        }
        val lapsed = db.bookReaderQueries.selectLapsedWords().executeAsList()
        Vocabulary.profile(stats, lapsed).also { cached = it }
    }

    /** Words in [text] this reader is likely not to know. */
    suspend fun likelyUnknown(text: String): Set<String> = withContext(Dispatchers.Default) {
        Vocabulary.likelyUnknown(text, profile())
    }

    /** How a book sits against the profile, for deciding what to read next. */
    suspend fun assess(text: String): TextFit = withContext(Dispatchers.Default) {
        Vocabulary.assess(text, profile())
    }

    suspend fun historySize(): Long = withContext(Dispatchers.Default) {
        db.bookReaderQueries.countWordHistory().executeAsOne()
    }

    /** The words looked up most often, for the profile screen. */
    suspend fun mostLookedUp(limit: Int = 20): List<WordStat> = withContext(Dispatchers.Default) {
        db.bookReaderQueries.selectWordHistory().executeAsList()
            .take(limit)
            .map { WordStat(word = it.word, lookups = it.lookups.toInt()) }
    }

    suspend fun clear() = withContext(Dispatchers.Default) {
        db.bookReaderQueries.clearWordHistory()
        cached = null
    }

    // --- Preference ---------------------------------------------------------

    /**
     * Whether likely-unknown words are marked while reading. On by default, and
     * a preference rather than a fixed behaviour because marks are a help while
     * studying and a distraction while reading for pleasure.
     */
    suspend fun isMarkingEnabled(): Boolean = withContext(Dispatchers.Default) {
        db.bookReaderQueries.getSetting(KEY_MARKING).executeAsOneOrNull() != "off"
    }

    suspend fun setMarkingEnabled(enabled: Boolean) = withContext(Dispatchers.Default) {
        db.bookReaderQueries.putSetting(KEY_MARKING, if (enabled) "on" else "off")
    }

    private companion object {
        const val KEY_MARKING = "vocab.mark_unknown"
    }
}
