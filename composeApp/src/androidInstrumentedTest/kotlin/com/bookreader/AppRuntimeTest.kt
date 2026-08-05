package com.bookreader

import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.lifecycle.Lifecycle
import com.bookreader.core.ai.GeneratedCard
import com.bookreader.core.srs.ReviewGrade
import com.bookreader.data.BookFormat
import com.bookreader.platform.PlatformContext
import com.bookreader.platform.platformInflater
import com.bookreader.core.epub.EpubBook
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Exercises the app on a real device.
 *
 * Everything else in this project is either a pure-logic unit test or a
 * compile. These are the only checks that run the wiring: the SQLDelight
 * schema against real SQLite, the bundled dictionary import, the repositories,
 * and Compose actually composing. A broken `.sq` query or a crash in the first
 * composition shows up here and nowhere else.
 */
@RunWith(AndroidJUnit4::class)
class AppRuntimeTest {

    private fun container(): AppContainer =
        AppContainer(PlatformContext(ApplicationProvider.getApplicationContext()))

    @Test
    fun database_schema_creates_and_seeds_the_dictionary() = runBlocking {
        val app = container()
        app.warmUp()

        // Proves the CREATE TABLE statements and the seed import both ran.
        val hit = app.dictionary.lookup("book")
        assertNotNull(hit, "bundled dictionary returned nothing for 'book'")
        assertTrue(hit.senses.isNotEmpty(), "entry had no senses")
        assertTrue(
            hit.senses.first().persian.isNotEmpty(),
            "entry had no Persian meaning — the seed import or the column mapping is wrong",
        )
        assertEquals("B-O-O-K", hit.spelling)
    }

    @Test
    fun lookup_resolves_an_inflected_word_through_the_lemmatizer() = runBlocking {
        val app = container()
        app.warmUp()

        // "running" is not a headword; it must resolve via the lemmatizer.
        val hit = app.dictionary.lookup("running")
        assertNotNull(hit, "inflected form did not resolve to a dictionary entry")
        assertEquals("run", hit.headword)
        assertEquals("running", hit.queried)
    }

    @Test
    fun a_looked_up_word_can_be_saved_and_studied() = runBlocking {
        val app = container()
        app.warmUp()

        val entry = app.dictionary.lookup("word")
        assertNotNull(entry)

        val card = app.flashcards.save(
            entry = entry,
            sense = entry.senses.firstOrNull(),
            sourceBookId = null,
            sourceTitle = "Runtime test",
            sourceContext = "Choose your words carefully.",
        )
        assertTrue(app.flashcards.contains("word"), "card was not persisted")
        assertTrue(card.persian.isNotBlank(), "saved card lost its Persian meaning")

        // A new card is due immediately, and grading it must push it out.
        assertTrue(app.flashcards.due().any { it.id == card.id }, "new card was not due")
        val graded = app.flashcards.grade(card, ReviewGrade.GOOD)
        assertEquals(1, graded.review.repetitions)
        assertEquals(1, graded.review.intervalDays)
        assertTrue(graded.review.dueAtMillis > 0)

        app.flashcards.delete(card.id)
        assertTrue(!app.flashcards.contains("word"), "card was not deleted")
    }

    /**
     * The generated-material columns against real SQLite.
     *
     * They arrived with a schema migration, so this is the check that the new
     * columns exist, round-trip, and survive the card being re-saved — none of
     * which the JVM core tests can see, because the core has no database.
     */
    @Test
    fun generated_study_material_is_stored_on_the_card() = runBlocking {
        val app = container()
        app.warmUp()

        val entry = app.dictionary.lookup("cold")
        assertNotNull(entry)
        app.flashcards.save(
            entry = entry,
            sense = entry.senses.firstOrNull(),
            sourceBookId = null,
            sourceTitle = "Runtime test",
            sourceContext = "The morning was cold.",
        )

        val generated = GeneratedCard(
            cloze = "The morning was ___.",
            example = "The water is cold in winter.",
            mnemonic = "cold — سرد",
        )
        val updated = app.flashcards.attachGenerated("cold", generated)
        assertNotNull(updated, "attachGenerated found no card to attach to")
        assertEquals("The morning was ___.", updated.cloze)
        assertEquals("cold — سرد", updated.mnemonic)

        val reloaded = app.flashcards.all().first { it.word == "cold" }
        assertEquals("The morning was ___.", reloaded.cloze, "cloze did not survive the round trip")
        assertEquals("cold — سرد", reloaded.mnemonic)

        // Re-saving replaces the definition; generated material was paid for
        // and must not be collateral damage.
        app.flashcards.save(
            entry = entry,
            sense = entry.senses.firstOrNull(),
            sourceBookId = null,
            sourceTitle = "Runtime test",
            sourceContext = "A cold reception.",
        )
        val afterResave = app.flashcards.all().first { it.word == "cold" }
        assertEquals("The morning was ___.", afterResave.cloze, "re-saving wiped the cloze")

        app.flashcards.delete(afterResave.id)
    }

    @Test
    fun reading_position_survives_a_round_trip() = runBlocking {
        val app = container()
        val book = app.books.add(
            title = "Runtime Test Book",
            author = "Tester",
            format = BookFormat.EPUB,
            filePath = "/does/not/exist.epub",
            coverPath = null,
            unitCount = 3,
        )
        app.books.savePosition(
            book.id,
            com.bookreader.data.ReadingPosition(unitIndex = 2, blockIndex = 5, charOffset = 42),
        )
        val restored = app.books.position(book.id)
        assertNotNull(restored)
        assertEquals(2, restored.unitIndex)
        assertEquals(42, restored.charOffset)

        app.books.remove(book.id)
        assertTrue(app.books.all().none { it.id == book.id })
    }

    @Test
    fun epub_parsing_works_against_the_platform_inflater() {
        // The shared DEFLATE decoder is unit-tested on the JVM; this confirms
        // the Android inflater actual behaves identically on-device.
        val epub = TestEpub.bytes()
        val book = EpubBook.open(epub, platformInflater())
        assertEquals("Runtime Book", book.metadata.title)
        assertEquals(1, book.chapterCount)
        assertTrue(book.chapter(0).blocks.any { it.text.contains("It was a quiet morning") })
    }

    @Test
    fun main_activity_launches_without_crashing() {
        // Catches failures in the first Compose composition, which no amount
        // of compiling would reveal.
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { /* reached RESUMED without throwing */ }
            assertEquals(Lifecycle.State.RESUMED, scenario.state)
        }
    }
}
