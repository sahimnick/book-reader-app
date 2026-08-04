package com.bookreader.verify

import com.bookreader.core.text.SentenceSegmenter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SentenceSegmenterTest {

    private fun texts(input: String) = SentenceSegmenter.segmentToStrings(input)

    @Test
    fun `splits simple sentences`() {
        assertEquals(
            listOf("The sky was clear.", "We walked home.", "It was late!"),
            texts("The sky was clear. We walked home. It was late!"),
        )
    }

    @Test
    fun `keeps decimals intact`() {
        assertEquals(
            listOf("Pi is about 3.14 in most cases.", "That is enough."),
            texts("Pi is about 3.14 in most cases. That is enough."),
        )
    }

    @Test
    fun `does not split on titles`() {
        assertEquals(
            listOf("Dr. Watson met Mr. Holmes on Baker St. that evening.", "They talked."),
            texts("Dr. Watson met Mr. Holmes on Baker St. that evening. They talked."),
        )
    }

    @Test
    fun `does not split on initials`() {
        assertEquals(
            listOf("The book is by J. R. R. Tolkien.", "It is long."),
            texts("The book is by J. R. R. Tolkien. It is long."),
        )
    }

    @Test
    fun `does not split inside dotted acronyms`() {
        assertEquals(
            listOf("She moved to the U.S.A. last year.", "Then she left."),
            texts("She moved to the U.S.A. last year. Then she left."),
        )
    }

    @Test
    fun `absorbs terminator runs and closing quotes`() {
        assertEquals(
            listOf("\"Stop!\"", "He ran away...", "Nobody followed."),
            texts("\"Stop!\" He ran away... Nobody followed."),
        )
    }

    @Test
    fun `treats blank line as a boundary without punctuation`() {
        assertEquals(
            listOf("Chapter One", "It began quietly."),
            texts("Chapter One\n\nIt began quietly."),
        )
    }

    @Test
    fun `handles persian text with caseless letters`() {
        val result = texts("این یک جمله است. این جمله دوم است.")
        assertEquals(2, result.size)
        assertEquals("این یک جمله است.", result[0])
        assertEquals("این جمله دوم است.", result[1])
    }

    @Test
    fun `handles persian question mark`() {
        val result = texts("حال شما چطور است؟ من خوبم.")
        assertEquals(2, result.size)
    }

    @Test
    fun `offsets map back into the source exactly`() {
        val source = "First one. Second one? Third one!"
        val sentences = SentenceSegmenter.segment(source)
        assertEquals(3, sentences.size)
        for (s in sentences) {
            // The offsets are what the TTS highlight relies on; they must slice
            // the original string back to the same text.
            assertEquals(s.text, source.substring(s.start, s.endExclusive))
        }
        assertEquals(0, sentences[0].index)
        assertEquals(2, sentences[2].index)
    }

    @Test
    fun `sentenceAt finds the sentence covering an offset`() {
        val source = "Alpha beta. Gamma delta. Epsilon."
        val sentences = SentenceSegmenter.segment(source)
        val hit = SentenceSegmenter.sentenceAt(sentences, source.indexOf("Gamma") + 2)
        assertEquals("Gamma delta.", hit?.text)
    }

    @Test
    fun `empty and blank input yields nothing`() {
        assertTrue(texts("").isEmpty())
        assertTrue(texts("   \n  \t ").isEmpty())
    }

    @Test
    fun `single sentence without terminator is still returned`() {
        assertEquals(listOf("No terminator here"), texts("No terminator here"))
    }

    @Test
    fun `lowercase after period is treated as an abbreviation not a boundary`() {
        // "approx." is in the abbreviation list, and the lowercase "five" that
        // follows confirms the sentence continues.
        assertEquals(1, texts("It weighed approx. five kilograms").size)
    }
}
