package com.bookreader.verify

import com.bookreader.core.dictionary.Lemmatizer
import com.bookreader.core.epub.HtmlContentExtractor
import com.bookreader.core.model.ContentDocument
import com.bookreader.core.srs.ReviewGrade
import com.bookreader.core.srs.ReviewState
import com.bookreader.core.srs.SpacedRepetition
import com.bookreader.core.tts.SpeechPlan
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SpacedRepetitionTest {

    private val day = 24L * 60 * 60 * 1000

    @Test
    fun `first correct review schedules one day out`() {
        val next = SpacedRepetition.review(ReviewState(), ReviewGrade.GOOD, nowMillis = 0L)
        assertEquals(1, next.repetitions)
        assertEquals(1, next.intervalDays)
        assertEquals(day, next.dueAtMillis)
    }

    @Test
    fun `second correct review schedules six days out`() {
        var state = SpacedRepetition.review(ReviewState(), ReviewGrade.GOOD, 0L)
        state = SpacedRepetition.review(state, ReviewGrade.GOOD, 0L)
        assertEquals(2, state.repetitions)
        assertEquals(6, state.intervalDays)
    }

    @Test
    fun `third and later reviews multiply by the ease factor`() {
        var state = ReviewState()
        repeat(3) { state = SpacedRepetition.review(state, ReviewGrade.GOOD, 0L) }
        assertEquals(3, state.repetitions)
        // 6 days * ease (~2.5) rounds to 15.
        assertTrue(state.intervalDays in 14..16, "interval was ${state.intervalDays}")
    }

    @Test
    fun `easy grade raises ease and hard lowers it`() {
        val easy = SpacedRepetition.review(ReviewState(), ReviewGrade.EASY, 0L)
        val hard = SpacedRepetition.review(ReviewState(), ReviewGrade.HARD, 0L)
        assertTrue(easy.easeFactor > 2.5)
        assertTrue(hard.easeFactor < 2.5)
    }

    @Test
    fun `ease never falls below the floor`() {
        var state = ReviewState()
        repeat(20) { state = SpacedRepetition.review(state, ReviewGrade.AGAIN, 0L) }
        assertEquals(SpacedRepetition.MINIMUM_EASE, state.easeFactor)
    }

    @Test
    fun `a lapse resets repetitions and counts the lapse`() {
        var state = ReviewState()
        repeat(3) { state = SpacedRepetition.review(state, ReviewGrade.GOOD, 0L) }
        val lapsed = SpacedRepetition.review(state, ReviewGrade.AGAIN, 0L)
        assertEquals(0, lapsed.repetitions)
        assertEquals(1, lapsed.intervalDays)
        assertEquals(1, lapsed.lapses)
        // Ease is reduced but the card does not forget it was once hard.
        assertTrue(lapsed.easeFactor < state.easeFactor)
    }

    @Test
    fun `new cards are always due`() {
        assertTrue(SpacedRepetition.isDue(ReviewState(), nowMillis = 0L))
    }

    @Test
    fun `a scheduled card is not due before its date`() {
        val state = SpacedRepetition.review(ReviewState(), ReviewGrade.GOOD, 0L)
        assertTrue(!SpacedRepetition.isDue(state, nowMillis = day / 2))
        assertTrue(SpacedRepetition.isDue(state, nowMillis = day))
    }

    @Test
    fun `queue puts the most overdue first and new cards last`() {
        val overdueOld = ReviewState(repetitions = 2, intervalDays = 6, dueAtMillis = 100)
        val overdueRecent = ReviewState(repetitions = 2, intervalDays = 6, dueAtMillis = 500)
        val fresh = ReviewState()
        val notDue = ReviewState(repetitions = 1, intervalDays = 1, dueAtMillis = 10_000)

        val queue = SpacedRepetition.buildQueue(
            listOf(fresh, overdueRecent, notDue, overdueOld),
            nowMillis = 1_000,
        ) { it }

        assertEquals(listOf(overdueOld, overdueRecent, fresh), queue)
    }

    @Test
    fun `queue honours the limit`() {
        val cards = List(10) { ReviewState() }
        assertEquals(3, SpacedRepetition.buildQueue(cards, 0L, limit = 3) { it }.size)
    }

    @Test
    fun `intervals stay within the cap`() {
        var state = ReviewState()
        repeat(40) { state = SpacedRepetition.review(state, ReviewGrade.EASY, 0L) }
        assertTrue(state.intervalDays <= SpacedRepetition.MAX_INTERVAL_DAYS)
    }
}

class LemmatizerTest {

    private fun assertReaches(word: String, base: String) {
        val candidates = Lemmatizer.candidates(word)
        assertTrue(base in candidates, "expected '$base' among candidates for '$word': $candidates")
    }

    @Test
    fun `the word itself is always tried first`() {
        assertEquals("running", Lemmatizer.candidates("running").first())
    }

    @Test
    fun `regular plurals`() {
        assertReaches("books", "book")
        assertReaches("boxes", "box")
        assertReaches("studies", "study")
    }

    @Test
    fun `does not strip double s`() {
        assertEquals(listOf("glass"), Lemmatizer.candidates("glass"))
    }

    @Test
    fun `verb forms`() {
        assertReaches("walked", "walk")
        assertReaches("liked", "like")
        assertReaches("stopped", "stop")
        assertReaches("tried", "try")
        assertReaches("reading", "read")
        assertReaches("making", "make")
        assertReaches("running", "run")
    }

    @Test
    fun `irregular verbs and nouns`() {
        assertReaches("went", "go")
        assertReaches("children", "child")
        assertReaches("mice", "mouse")
        assertReaches("better", "good")
    }

    @Test
    fun `comparatives and adverbs`() {
        assertReaches("happily", "happy")
        assertReaches("quickly", "quick")
        assertReaches("happiest", "happy")
    }

    @Test
    fun `possessives`() {
        assertReaches("dog's", "dog")
        assertReaches("dogs'", "dog")
    }

    @Test
    fun `hyphenated compounds offer the head word`() {
        assertReaches("well-known", "known")
    }

    @Test
    fun `empty input yields nothing`() {
        assertTrue(Lemmatizer.candidates("   ").isEmpty())
    }
}

class SpeechPlanTest {

    private fun planOf(html: String): SpeechPlan =
        SpeechPlan(ContentDocument("d", null, HtmlContentExtractor.extract(html)))

    @Test
    fun `splits a document into sentences`() {
        val plan = planOf("<h1>Title</h1><p>One two. Three four.</p>")
        assertEquals(listOf("Title", "One two.", "Three four."), plan.sentences.map { it.text })
    }

    @Test
    fun `a heading is its own sentence`() {
        // Without this the heading is spoken glued to the first paragraph.
        val plan = planOf("<h1>Chapter One</h1><p>It began.</p>")
        assertEquals("Chapter One", plan.sentences.first().text)
    }

    @Test
    fun `maps sentences back to their block`() {
        val plan = planOf("<h1>Title</h1><p>One two. Three four.</p><p>Five.</p>")
        assertEquals(0, plan.blockIndexForSentence(0)) // Title -> block 0
        assertEquals(1, plan.blockIndexForSentence(1)) // "One two." -> block 1
        assertEquals(1, plan.blockIndexForSentence(2)) // "Three four." -> block 1
        assertEquals(2, plan.blockIndexForSentence(3)) // "Five." -> block 2
    }

    @Test
    fun `tapping a block starts at its first sentence`() {
        val plan = planOf("<p>A one. A two.</p><p>B one. B two.</p>")
        assertEquals(0, plan.firstSentenceOfBlock(0))
        assertEquals(2, plan.firstSentenceOfBlock(1))
    }

    @Test
    fun `resolves a character offset to a sentence`() {
        val plan = planOf("<p>Alpha beta. Gamma delta.</p>")
        val gammaAt = plan.text.indexOf("Gamma")
        assertEquals(1, plan.sentenceIndexAtChar(gammaAt))
        assertEquals(0, plan.sentenceIndexAtChar(0))
    }

    @Test
    fun `converts an engine range into an absolute highlight range`() {
        val plan = planOf("<p>Alpha beta. Gamma delta.</p>")
        val sentence = plan.sentenceAt(1)!!
        // Engine reports "Gamma" as chars 0..5 of the sentence it was given.
        val range = plan.absoluteRange(1, 0, 5)
        assertNotNull(range)
        assertEquals("Gamma", plan.text.substring(range.first, range.last + 1))
        assertEquals(sentence.start, range.first)
    }

    @Test
    fun `engine ranges are clamped to the sentence`() {
        val plan = planOf("<p>Short one.</p>")
        val range = plan.absoluteRange(0, 0, 9999)
        assertNotNull(range)
        assertTrue(range.last < plan.text.length)
    }

    @Test
    fun `every sentence slices back out of the spoken text`() {
        val plan = planOf("<h1>T</h1><p>One. Two. Three.</p><blockquote><p>Quoted here.</p></blockquote>")
        for (s in plan.sentences) {
            assertEquals(s.text, plan.text.substring(s.start, s.endExclusive))
        }
    }

    @Test
    fun `an empty document produces an empty plan`() {
        assertTrue(SpeechPlan.Empty.isEmpty)
        assertEquals(-1, SpeechPlan.Empty.blockIndexForSentence(0))
    }

    @Test
    fun `images do not contribute spoken text`() {
        val plan = SpeechPlan(
            ContentDocument(
                "d",
                null,
                HtmlContentExtractor.extract("""<p>Before.</p><img src="a.png" alt="Alt text"/><p>After.</p>"""),
            ),
        )
        assertTrue(plan.sentences.none { it.text.contains("Alt text") })
        assertEquals(listOf("Before.", "After."), plan.sentences.map { it.text })
    }
}
