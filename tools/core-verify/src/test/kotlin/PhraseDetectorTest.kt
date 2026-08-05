package com.bookreader.verify

import com.bookreader.core.text.PhraseDetector
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PhraseDetectorTest {

    private fun keysAt(text: String, word: String): List<String> =
        PhraseDetector.candidateKeysAt(text, text.indexOf(word))

    @Test
    fun `tapping the particle finds the phrasal verb`() {
        val text = "He finally gave up."
        val keys = keysAt(text, "up")
        assertTrue("gave up" in keys, "expected 'gave up' in $keys")
        // The bare word must remain available as a fallback.
        assertEquals("up", keys.last())
    }

    @Test
    fun `tapping the verb also finds the phrasal verb`() {
        val keys = keysAt("He finally gave up.", "gave")
        assertTrue("gave up" in keys, "expected 'gave up' in $keys")
    }

    @Test
    fun `longer phrases are offered before shorter ones`() {
        val text = "I cannot put up with this."
        val keys = keysAt(text, "up")
        val three = keys.indexOf("put up with")
        val two = keys.indexOf("put up")
        assertTrue(three >= 0, "expected 'put up with' in $keys")
        assertTrue(two >= 0, "expected 'put up' in $keys")
        assertTrue(three < two, "longest phrase should come first: $keys")
    }

    @Test
    fun `separable phrasal verbs are detected across an object`() {
        val keys = keysAt("She gave it up for good.", "up")
        assertTrue("gave it up" in keys, "expected 'gave it up' in $keys")
    }

    @Test
    fun `a preposition starting a phrase is not treated as a verb`() {
        // "up the hill" is not a phrasal verb; only the bare word should return.
        val keys = keysAt("They walked up the hill.", "the")
        assertTrue(keys.none { it.startsWith("up ") }, "should not invent a phrase: $keys")
    }

    @Test
    fun `ordinary prose yields only the tapped word`() {
        val keys = keysAt("The morning was cold and bright.", "morning")
        assertEquals(listOf("morning"), keys)
    }

    @Test
    fun `middle words that are not fillers do not form a phrase`() {
        val keys = keysAt("He gave money away quietly.", "away")
        // "gave money away" — "money" is not a filler, so no phrase is proposed.
        assertTrue(keys.none { it.contains("money") }, "should not span an arbitrary noun: $keys")
    }

    @Test
    fun `phrase spans slice back out of the source text`() {
        val text = "I cannot put up with this."
        for (span in PhraseDetector.candidatesAt(text, text.indexOf("up"))) {
            assertEquals(span.text, text.substring(span.start, span.endExclusive))
        }
    }

    @Test
    fun `normalizePhrase collapses spacing and case`() {
        assertEquals("gave up", PhraseDetector.normalizePhrase("  Gave   Up  "))
    }

    @Test
    fun `empty text is handled`() {
        assertTrue(PhraseDetector.candidatesAt("", 0).isEmpty())
    }

    @Test
    fun `numeric head is never a phrase`() {
        val keys = keysAt("There were 3 up front.", "up")
        assertTrue(keys.none { it.startsWith("3 ") }, "digits are not verbs: $keys")
    }
}
