package com.bookreader.verify

import com.bookreader.core.text.WordTokenizer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class WordTokenizerTest {

    @Test
    fun `finds the word under an offset`() {
        val text = "The quick brown fox"
        assertEquals("quick", WordTokenizer.wordAt(text, 5)?.text)
        assertEquals("brown", WordTokenizer.wordAt(text, 10)?.text)
    }

    @Test
    fun `keeps internal apostrophes`() {
        val text = "He doesn't care"
        assertEquals("doesn't", WordTokenizer.wordAt(text, 5)?.text)
    }

    @Test
    fun `keeps curly apostrophes`() {
        val text = "He doesn’t care"
        assertEquals("doesn’t", WordTokenizer.wordAt(text, 5)?.text)
    }

    @Test
    fun `keeps internal hyphens`() {
        val text = "a well-known author"
        assertEquals("well-known", WordTokenizer.wordAt(text, 4)?.text)
    }

    @Test
    fun `strips surrounding punctuation from the range`() {
        val text = "Wait, (really)!"
        assertEquals("Wait", WordTokenizer.wordAt(text, 1)?.text)
        assertEquals("really", WordTokenizer.wordAt(text, 8)?.text)
    }

    @Test
    fun `snaps to a nearby word when tapping whitespace`() {
        val text = "alpha beta"
        // Offset 5 is the space; the tap should still resolve to a word.
        assertEquals("alpha", WordTokenizer.wordAt(text, 5)?.text)
    }

    @Test
    fun `returns null when there is no word nearby`() {
        assertNull(WordTokenizer.wordAt("       ", 3))
        assertNull(WordTokenizer.wordAt("", 0))
    }

    @Test
    fun `expands a partial selection to whole words`() {
        val text = "he crossed the road quickly"
        val start = text.indexOf("ossed")
        val end = text.indexOf("oad") + 2
        assertEquals("crossed the road", WordTokenizer.phraseIn(text, start, end)?.text)
    }

    @Test
    fun `phrase with zero-width selection falls back to the word`() {
        val text = "single word here"
        assertEquals("word", WordTokenizer.phraseIn(text, 8, 8)?.text)
    }

    @Test
    fun `normalize produces a dictionary key`() {
        assertEquals("don't", WordTokenizer.normalize("“Don’t”"))
        assertEquals("hello", WordTokenizer.normalize("Hello,"))
        assertEquals("road", WordTokenizer.normalize("(road)."))
    }

    @Test
    fun `words enumerates every token`() {
        val words = WordTokenizer.words("One two three-four, five!")
        assertEquals(listOf("One", "two", "three-four", "five"), words.map { it.text })
    }

    @Test
    fun `handles persian words`() {
        val text = "این کتاب است"
        assertEquals("کتاب", WordTokenizer.wordAt(text, 5)?.text)
    }

    @Test
    fun `spans slice back to the source`() {
        val text = "Mapping spans back exactly"
        val span = WordTokenizer.wordAt(text, 10)!!
        assertEquals(span.text, text.substring(span.start, span.endExclusive))
    }
}
