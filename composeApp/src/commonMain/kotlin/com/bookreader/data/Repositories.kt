package com.bookreader.data

import com.bookreader.core.dictionary.DictionaryProvider
import com.bookreader.core.dictionary.DictionaryTsv
import com.bookreader.core.dictionary.PartOfSpeech
import com.bookreader.core.dictionary.Sense
import com.bookreader.core.dictionary.WordEntry
import com.bookreader.core.dictionary.spellOut
import com.bookreader.core.srs.ReviewGrade
import com.bookreader.core.srs.ReviewState
import com.bookreader.core.srs.SpacedRepetition
import com.bookreader.db.BookReaderDb
import com.bookreader.platform.currentTimeMillis
import com.bookreader.platform.randomUuid
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

enum class BookFormat { EPUB, PDF;

    companion object {
        fun fromPath(path: String): BookFormat? = when {
            path.endsWith(".epub", ignoreCase = true) -> EPUB
            path.endsWith(".pdf", ignoreCase = true) -> PDF
            else -> null
        }
    }
}

data class LibraryBook(
    val id: String,
    val title: String,
    val author: String?,
    val format: BookFormat,
    val filePath: String,
    val coverPath: String?,
    val unitCount: Int,
    val addedAt: Long,
    val lastOpenedAt: Long?,
)

data class ReadingPosition(
    val unitIndex: Int,
    val blockIndex: Int,
    val charOffset: Int,
)

class BookRepository(private val db: BookReaderDb) {

    suspend fun all(): List<LibraryBook> = withContext(Dispatchers.Default) {
        db.bookReaderQueries.selectAllBooks().executeAsList().map(::toBook)
    }

    suspend fun byPath(path: String): LibraryBook? = withContext(Dispatchers.Default) {
        db.bookReaderQueries.selectBookByPath(path).executeAsOneOrNull()?.let(::toBook)
    }

    suspend fun byId(id: String): LibraryBook? = withContext(Dispatchers.Default) {
        db.bookReaderQueries.selectBook(id).executeAsOneOrNull()?.let(::toBook)
    }

    suspend fun add(
        title: String,
        author: String?,
        format: BookFormat,
        filePath: String,
        coverPath: String?,
        unitCount: Int,
    ): LibraryBook = withContext(Dispatchers.Default) {
        val book = LibraryBook(
            id = randomUuid(),
            title = title,
            author = author,
            format = format,
            filePath = filePath,
            coverPath = coverPath,
            unitCount = unitCount,
            addedAt = currentTimeMillis(),
            lastOpenedAt = null,
        )
        db.bookReaderQueries.insertBook(
            id = book.id,
            title = book.title,
            author = book.author,
            format = book.format.name,
            filePath = book.filePath,
            coverPath = book.coverPath,
            unitCount = book.unitCount.toLong(),
            addedAt = book.addedAt,
            lastOpenedAt = null,
        )
        book
    }

    suspend fun touch(id: String) = withContext(Dispatchers.Default) {
        db.bookReaderQueries.touchBook(currentTimeMillis(), id)
    }

    suspend fun remove(id: String) = withContext(Dispatchers.Default) {
        db.bookReaderQueries.deleteProgress(id)
        db.bookReaderQueries.deleteBook(id)
    }

    suspend fun savePosition(bookId: String, position: ReadingPosition) =
        withContext(Dispatchers.Default) {
            db.bookReaderQueries.upsertProgress(
                bookId = bookId,
                unitIndex = position.unitIndex.toLong(),
                blockIndex = position.blockIndex.toLong(),
                charOffset = position.charOffset.toLong(),
                updatedAt = currentTimeMillis(),
            )
        }

    suspend fun position(bookId: String): ReadingPosition? = withContext(Dispatchers.Default) {
        db.bookReaderQueries.selectProgress(bookId).executeAsOneOrNull()?.let {
            ReadingPosition(
                unitIndex = it.unitIndex.toInt(),
                blockIndex = it.blockIndex.toInt(),
                charOffset = it.charOffset.toInt(),
            )
        }
    }

    private fun toBook(row: com.bookreader.db.Book) = LibraryBook(
        id = row.id,
        title = row.title,
        author = row.author,
        format = runCatching { BookFormat.valueOf(row.format) }.getOrDefault(BookFormat.EPUB),
        filePath = row.filePath,
        coverPath = row.coverPath,
        unitCount = row.unitCount.toInt(),
        addedAt = row.addedAt,
        lastOpenedAt = row.lastOpenedAt,
    )
}

/**
 * The offline dictionary backed by SQLite.
 *
 * The bundled seed is loaded into the same table as any imported dataset, so
 * lookups have one code path regardless of where the data came from.
 */
class SqliteDictionary(private val db: BookReaderDb) : DictionaryProvider {

    override val name: String = "offline"
    override val isOffline: Boolean = true

    suspend fun entryCount(): Long = withContext(Dispatchers.Default) {
        db.bookReaderQueries.dictionarySize().executeAsOne()
    }

    /** Loads rows, replacing anything already stored. */
    suspend fun import(tsv: String): Int = withContext(Dispatchers.Default) {
        val rows = DictionaryTsv.parse(tsv)
        db.bookReaderQueries.transaction {
            db.bookReaderQueries.clearDictionary()
            for (row in rows) {
                db.bookReaderQueries.insertDictionaryEntry(
                    headword = row.headword,
                    partOfSpeech = row.partOfSpeech.label,
                    pronunciation = row.pronunciation,
                    english = row.english,
                    persian = row.persian.joinToString("|"),
                    examples = row.examples.joinToString("|"),
                    synonyms = row.synonyms.joinToString("|"),
                )
            }
        }
        rows.size
    }

    /** Populates the seed data the first time the app runs. */
    suspend fun ensureSeeded() {
        if (entryCount() == 0L) import(SeedDictionary.TSV)
    }

    override suspend fun lookup(word: String): WordEntry? = withContext(Dispatchers.Default) {
        val key = word.trim().lowercase()
        if (key.isEmpty()) return@withContext null
        val rows = db.bookReaderQueries.lookupWord(key).executeAsList()
        if (rows.isEmpty()) return@withContext null

        WordEntry(
            headword = key,
            queried = word,
            pronunciation = rows.firstNotNullOfOrNull { it.pronunciation },
            spelling = spellOut(word),
            senses = rows.map { row ->
                Sense(
                    partOfSpeech = PartOfSpeech.parse(row.partOfSpeech),
                    english = row.english,
                    persian = row.persian.split('|').filter { it.isNotBlank() },
                    examples = row.examples.split('|').filter { it.isNotBlank() },
                    synonyms = row.synonyms.split('|').filter { it.isNotBlank() },
                )
            },
            source = "offline",
        )
    }

    override suspend fun lookupPhrase(phrase: String): WordEntry? = lookup(phrase)
}

data class Flashcard(
    val id: String,
    val word: String,
    val headword: String,
    val pronunciation: String?,
    val english: String,
    val persian: String,
    val example: String,
    val sourceBookId: String?,
    val sourceTitle: String?,
    val sourceContext: String,
    val createdAt: Long,
    val review: ReviewState,
)

class FlashcardRepository(private val db: BookReaderDb) {

    suspend fun all(): List<Flashcard> = withContext(Dispatchers.Default) {
        db.bookReaderQueries.selectAllFlashcards().executeAsList().map(::toCard)
    }

    suspend fun due(nowMillis: Long = currentTimeMillis()): List<Flashcard> =
        withContext(Dispatchers.Default) {
            db.bookReaderQueries.selectDueFlashcards(nowMillis).executeAsList().map(::toCard)
        }

    suspend fun count(): Long = withContext(Dispatchers.Default) {
        db.bookReaderQueries.countFlashcards().executeAsOne()
    }

    suspend fun dueCount(nowMillis: Long = currentTimeMillis()): Long =
        withContext(Dispatchers.Default) {
            db.bookReaderQueries.countDueFlashcards(nowMillis).executeAsOne()
        }

    suspend fun contains(word: String): Boolean = withContext(Dispatchers.Default) {
        db.bookReaderQueries.selectFlashcardByWord(word.lowercase()).executeAsOneOrNull() != null
    }

    /**
     * Saves a looked-up word as a card. Re-saving the same word replaces it,
     * which is what a reader expects when they tap "add" a second time after
     * seeing a better definition.
     */
    suspend fun save(
        entry: WordEntry,
        sense: Sense?,
        sourceBookId: String?,
        sourceTitle: String?,
        sourceContext: String,
    ): Flashcard = withContext(Dispatchers.Default) {
        val chosen = sense ?: entry.senses.firstOrNull()
        val word = entry.queried.trim().lowercase()
        val existingId = db.bookReaderQueries.selectFlashcardByWord(word)
            .executeAsOneOrNull()?.id

        val card = Flashcard(
            id = existingId ?: randomUuid(),
            word = word,
            headword = entry.headword,
            pronunciation = entry.pronunciation,
            english = chosen?.english.orEmpty(),
            persian = chosen?.persian?.joinToString("، ").orEmpty(),
            example = chosen?.examples?.firstOrNull().orEmpty(),
            sourceBookId = sourceBookId,
            sourceTitle = sourceTitle,
            sourceContext = sourceContext,
            createdAt = currentTimeMillis(),
            review = ReviewState(),
        )
        db.bookReaderQueries.insertFlashcard(
            id = card.id,
            word = card.word,
            headword = card.headword,
            pronunciation = card.pronunciation,
            english = card.english,
            persian = card.persian,
            example = card.example,
            sourceBookId = card.sourceBookId,
            sourceTitle = card.sourceTitle,
            sourceContext = card.sourceContext,
            createdAt = card.createdAt,
            repetitions = card.review.repetitions.toLong(),
            intervalDays = card.review.intervalDays.toLong(),
            easeFactor = card.review.easeFactor,
            dueAtMillis = card.review.dueAtMillis,
            lapses = card.review.lapses.toLong(),
        )
        card
    }

    /** Applies a review grade and persists the new schedule. */
    suspend fun grade(card: Flashcard, grade: ReviewGrade): Flashcard =
        withContext(Dispatchers.Default) {
            val updated = SpacedRepetition.review(card.review, grade, currentTimeMillis())
            db.bookReaderQueries.updateSchedule(
                repetitions = updated.repetitions.toLong(),
                intervalDays = updated.intervalDays.toLong(),
                easeFactor = updated.easeFactor,
                dueAtMillis = updated.dueAtMillis,
                lapses = updated.lapses.toLong(),
                id = card.id,
            )
            card.copy(review = updated)
        }

    suspend fun delete(id: String) = withContext(Dispatchers.Default) {
        db.bookReaderQueries.deleteFlashcard(id)
    }

    private fun toCard(row: com.bookreader.db.Flashcard) = Flashcard(
        id = row.id,
        word = row.word,
        headword = row.headword,
        pronunciation = row.pronunciation,
        english = row.english,
        persian = row.persian,
        example = row.example,
        sourceBookId = row.sourceBookId,
        sourceTitle = row.sourceTitle,
        sourceContext = row.sourceContext,
        createdAt = row.createdAt,
        review = ReviewState(
            repetitions = row.repetitions.toInt(),
            intervalDays = row.intervalDays.toInt(),
            easeFactor = row.easeFactor,
            dueAtMillis = row.dueAtMillis,
            lapses = row.lapses.toInt(),
        ),
    )
}
