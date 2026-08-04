package com.bookreader

import com.bookreader.core.dictionary.CompositeDictionary
import com.bookreader.core.dictionary.DictionaryProvider
import com.bookreader.data.BookRepository
import com.bookreader.data.FlashcardRepository
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
class AppContainer(val platformContext: PlatformContext) {

    val database: BookReaderDb by lazy {
        BookReaderDb(DatabaseDriverFactory(platformContext).createDriver())
    }

    val fileStorage: FileStorage by lazy { FileStorage(platformContext) }

    val books: BookRepository by lazy { BookRepository(database) }

    val flashcards: FlashcardRepository by lazy { FlashcardRepository(database) }

    private val offlineDictionary: SqliteDictionary by lazy { SqliteDictionary(database) }

    val dictionary: DictionaryProvider by lazy {
        CompositeDictionary(listOf(offlineDictionary))
    }

    val speech: SpeechEngine by lazy { createSpeechEngine(platformContext) }

    /** Loads the bundled dictionary on first launch. */
    suspend fun warmUp() {
        offlineDictionary.ensureSeeded()
    }

    fun dispose() {
        speech.shutdown()
    }
}
