package com.bookreader.verify

import com.bookreader.core.vocab.ReaderProfile
import com.bookreader.core.vocab.TextFit
import com.bookreader.core.vocab.Vocabulary
import com.bookreader.core.vocab.WordStat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VocabularyTest {

    // --- Difficulty --------------------------------------------------------

    @Test
    fun `everyday words are not difficult`() {
        for (word in listOf("the", "house", "water", "friend", "morning", "because")) {
            assertEquals(0f, Vocabulary.difficulty(word), "$word should score zero")
        }
    }

    @Test
    fun `an inflection of a common word stays easy`() {
        // "walked" is not in the list, but "walk" is — a reader who knows the
        // verb is not stopped by its past tense.
        assertTrue(
            Vocabulary.difficulty("walked") < 0.1f,
            "walked scored ${Vocabulary.difficulty("walked")}",
        )
        assertTrue(Vocabulary.difficulty("houses") < 0.1f)
    }

    @Test
    fun `academic words outscore plain ones`() {
        val plain = Vocabulary.difficulty("puddle")
        val academic = Vocabulary.difficulty("ubiquitous")
        assertTrue(academic > plain, "ubiquitous ($academic) should beat puddle ($plain)")
        assertTrue(academic > 0.6f, "ubiquitous scored only $academic")
    }

    @Test
    fun `difficulty stays inside its range`() {
        for (word in listOf("a", "antidisestablishmentarianism", "hypercircumtransmentation")) {
            val score = Vocabulary.difficulty(word)
            assertTrue(score in 0f..1f, "$word scored $score")
        }
    }

    @Test
    fun `numbers are not vocabulary`() {
        assertEquals(0f, Vocabulary.difficulty("1998"))
        assertEquals(0f, Vocabulary.difficulty("3rd"))
    }

    @Test
    fun `case and punctuation do not change the score`() {
        assertEquals(Vocabulary.difficulty("ubiquitous"), Vocabulary.difficulty("Ubiquitous,"))
    }

    // --- Syllables ---------------------------------------------------------

    @Test
    fun `syllables are counted by vowel group`() {
        assertEquals(1, Vocabulary.syllables("cat"))
        assertEquals(2, Vocabulary.syllables("water"))
        assertEquals(4, Vocabulary.syllables("ubiquitous"))
        // A silent final e is not its own syllable.
        assertEquals(1, Vocabulary.syllables("make"))
        // …but "le" is.
        assertEquals(2, Vocabulary.syllables("table"))
    }

    @Test
    fun `every word has at least one syllable`() {
        assertEquals(1, Vocabulary.syllables("rhythm"))
        assertEquals(1, Vocabulary.syllables("e"))
    }

    // --- Profile -----------------------------------------------------------

    @Test
    fun `a new reader gets the default threshold`() {
        val profile = Vocabulary.profile(emptyList())
        assertEquals(ReaderProfile.DEFAULT_THRESHOLD, profile.threshold)
        assertFalse(profile.isEstablished, "a profile with no data is not established")
    }

    @Test
    fun `too few lookups leave the threshold alone`() {
        val stats = listOf(WordStat("ubiquitous", 1), WordStat("ephemeral", 1))
        val profile = Vocabulary.profile(stats)
        assertEquals(ReaderProfile.DEFAULT_THRESHOLD, profile.threshold)
        assertFalse(profile.isEstablished)
    }

    @Test
    fun `a reader who looks up moderate words gets a lower threshold`() {
        // Fifteen ordinary-but-not-common words: this reader needs help earlier
        // than the default assumes.
        val stats = listOf(
            "puddle", "sturdy", "brittle", "meadow", "gravel", "hollow", "shabby",
            "linger", "murmur", "trudge", "quiver", "scowl", "rustle", "cellar", "flicker",
        ).map { WordStat(it, 1) }

        val profile = Vocabulary.profile(stats)
        assertTrue(profile.isEstablished, "15 look-ups should establish a profile")
        assertTrue(
            profile.threshold < ReaderProfile.DEFAULT_THRESHOLD,
            "threshold stayed at ${profile.threshold}",
        )
    }

    @Test
    fun `the threshold never drops far enough to mark ordinary prose`() {
        // A reader who looks up genuinely easy words must not end up with every
        // other word underlined.
        val stats = List(30) { WordStat("puddle", 5) }
        val profile = Vocabulary.profile(stats)
        assertTrue(profile.threshold >= 0.35f, "threshold collapsed to ${profile.threshold}")
    }

    @Test
    fun `repeated lookups weigh more than single ones`() {
        val once = Vocabulary.profile(
            List(12) { WordStat("word$it", 1) } + WordStat("puddle", 1),
        )
        val often = Vocabulary.profile(
            List(12) { WordStat("word$it", 1) } + WordStat("puddle", 5),
        )
        assertTrue(
            often.sampleSize > once.sampleSize,
            "a word looked up five times should carry more weight",
        )
    }

    // --- Marking words -----------------------------------------------------

    @Test
    fun `a word the reader already looked up is always marked`() {
        // "house" is common and scores zero, but they looked it up, so they
        // told us they did not know it. Direct evidence beats the estimate.
        val profile = Vocabulary.profile(listOf(WordStat("house", 2)))
        assertTrue(Vocabulary.isUnknown("house", profile))
    }

    @Test
    fun `an inflection of a looked-up word is marked too`() {
        val profile = Vocabulary.profile(listOf(WordStat("linger", 1)))
        assertTrue(
            Vocabulary.isUnknown("lingered", profile),
            "the past tense of a word they looked up is still unknown to them",
        )
    }

    @Test
    fun `a struggling word is marked`() {
        val profile = Vocabulary.profile(emptyList(), failedWords = listOf("meadow"))
        assertTrue(Vocabulary.isUnknown("meadow", profile))
    }

    @Test
    fun `short words are never marked`() {
        val profile = Vocabulary.profile(emptyList(), failedWords = listOf("ox"))
        assertFalse(Vocabulary.isUnknown("ox", profile), "two letters is not worth a mark")
    }

    @Test
    fun `a page of ordinary prose gets almost no marks`() {
        val text = "The morning was cold and the boy walked to the house by the river. " +
            "He opened the door and looked for his father, who was reading a book."
        val marks = Vocabulary.likelyUnknown(text, Vocabulary.profile(emptyList()))
        assertTrue(marks.isEmpty(), "marked words in plain prose: $marks")
    }

    @Test
    fun `hard words in a passage are found`() {
        val text = "The ubiquitous surveillance apparatus rendered his equivocation futile."
        val marks = Vocabulary.likelyUnknown(text, Vocabulary.profile(emptyList()))
        assertTrue(marks.contains("ubiquitous"), "marks were $marks")
        assertTrue(marks.contains("equivocation"), "marks were $marks")
    }

    // --- Assessing a book ---------------------------------------------------

    @Test
    fun `plain prose reads as comfortable`() {
        val text = "The boy walked to the house. He opened the door and looked for his " +
            "father. The morning was cold and the river was high after the rain."
        val fit = Vocabulary.assess(text, Vocabulary.profile(emptyList()))
        assertEquals(TextFit.Band.COMFORTABLE, fit.band, "ratio was ${fit.unknownRatio}")
        assertTrue(fit.totalWords > 20)
    }

    @Test
    fun `dense prose reads as hard`() {
        val text = "The ubiquitous apparatus of bureaucratic obfuscation rendered his " +
            "equivocation not merely superfluous but constitutionally indefensible."
        val fit = Vocabulary.assess(text, Vocabulary.profile(emptyList()))
        assertEquals(TextFit.Band.HARD, fit.band, "ratio was ${fit.unknownRatio}")
    }

    @Test
    fun `a repeated hard word counts every time it appears`() {
        val once = Vocabulary.assess(
            "The ubiquitous thing was here and there and then it was gone away.",
            Vocabulary.profile(emptyList()),
        )
        val thrice = Vocabulary.assess(
            "The ubiquitous ubiquitous ubiquitous thing was here and then gone away.",
            Vocabulary.profile(emptyList()),
        )
        assertTrue(
            thrice.unknownRatio > once.unknownRatio,
            "a word met three times is felt three times",
        )
        assertEquals(1, thrice.unknownWords.size, "but it is still one word to learn")
    }

    @Test
    fun `an empty text is assessable`() {
        val fit = Vocabulary.assess("", Vocabulary.profile(emptyList()))
        assertEquals(0, fit.totalWords)
        assertEquals(0f, fit.unknownRatio)
        assertEquals(TextFit.Band.COMFORTABLE, fit.band)
    }

    @Test
    fun `hardest words come first`() {
        val fit = Vocabulary.assess(
            "The ubiquitous apparatus and the shabby puddle in the meadow.",
            Vocabulary.profile(
                listOf(WordStat("puddle", 1), WordStat("shabby", 1), WordStat("meadow", 1)),
            ),
        )
        assertTrue(fit.unknownWords.isNotEmpty())
        assertEquals(
            "ubiquitous",
            fit.unknownWords.first(),
            "the list should lead with the hardest word: ${fit.unknownWords}",
        )
    }
}
