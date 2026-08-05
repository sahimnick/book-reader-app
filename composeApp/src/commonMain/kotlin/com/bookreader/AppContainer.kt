package com.bookreader

import com.bookreader.core.dictionary.CompositeDictionary
import com.bookreader.core.dictionary.DictionaryProvider
import com.bookreader.data.AiDictionaryProvider
import com.bookreader.data.AiSettings
import com.bookreader.data.BookRepository
import com.bookreader.data.FlashcardRepository
import com.bookreader.data.FreeDictionaryProvider
import com.bookreader.data.GoogleTranslateProvider
import com.bookreader.data.MergingOnlineDictionary
import com.bookreader.data.SqliteDictionary
import com.bookreader.db.BookReaderDb
import com.bookreader.platform.DatabaseDriverFactory
import com.bookreader.platform.FileStorage
import com.bookreader.platform.PlatformContext
import com.bookreader.platform.SpeechEngine
import com.bookreader.platform.createSpeechEngine

/**
 * Manual dependency wiring.
 *
 * A DI framework would add a Kotlin/Native-compatible dependency and a
 * compile-time processor for a graph this small; constructing it by hand keeps
 * the iOS build simple and the startup path obvious.
 */
class AppContainer(
    val platformContext: PlatformContext,
    /**
     * How the speech engine is built. Injectable so tests can drive playback
     * with scripted events: a CI emulator has no voice data, so the only way to
     * verify that the sentence highlight actually advances is to supply the
     * events a real engine would emit.
     */
    private val speechEngineFactory: (PlatformContext) -> SpeechEngine = ::createSpeechEngine,
) {

    val database: BookReaderDb by lazy {
        BookReaderDb(DatabaseDriverFactory(platformContext).createDriver())
    }

    val fileStorage: FileStorage by lazy { FileStorage(platformContext) }

    val books: BookRepository by lazy { BookRepository(database) }

    val flashcards: FlashcardRepository by lazy { FlashcardRepository(database) }

    private val offlineDictionary: SqliteDictionary by lazy { SqliteDictionary(database) }

    /**
     * Offline first, then online.
     *
     * [CompositeDictionary] always consults offline providers before network
     * ones, so a word in the bundled data answers instantly and the network is
     * only touched for the long tail. Google Translate also handles multi-word
     * phrases, which a headword dictionary cannot.
     */
    val aiSettings: AiSettings by lazy { AiSettings(database) }

    /**
     * Context-aware lookup. Inert until the reader supplies an API key, and
     * placed last so it only answers what the offline and free sources could
     * not — it is the slowest and the only one that costs money.
     */
    val aiDictionary: AiDictionaryProvider by lazy { AiDictionaryProvider(database, aiSettings) }

    val dictionary: DictionaryProvider by lazy {
        CompositeDictionary(
            listOf(
                offlineDictionary,
                MergingOnlineDictionary(
                    english = FreeDictionaryProvider(),
                    persian = GoogleTranslateProvider(),
                ),
                aiDictionary,
            ),
        )
    }

    val speech: SpeechEngine by lazy { speechEngineFactory(platformContext) }

    /** Drops cached AI answers, e.g. after switching model. */
    suspend fun clearAiCache() = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
        database.bookReaderQueries.clearAiCache()
    }

    /** Loads the bundled dictionary on first launch. */
    suspend fun warmUp() {
        offlineDictionary.ensureSeeded()
    }

    fun dispose() {
        speech.shutdown()
    }
}
