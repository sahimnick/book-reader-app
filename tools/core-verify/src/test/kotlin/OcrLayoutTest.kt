package com.bookreader.verify

import com.bookreader.core.pdf.OcrLayout
import com.bookreader.core.pdf.RecognizedWord
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OcrLayoutTest {

    /** A word on a notional line, sized like real OCR output. */
    private fun word(text: String, x: Float, y: Float, w: Float = 0.08f, h: Float = 0.02f) =
        RecognizedWord(text, left = x, top = y, right = x + w, bottom = y + h)

    @Test
    fun `words on the same line are ordered left to right`() {
        // Deliberately supplied out of order, as an engine may report them.
        val lines = OcrLayout.toLines(
            listOf(word("world", 0.3f, 0.1f), word("Hello", 0.1f, 0.1f)),
        )
        assertEquals(1, lines.size)
        assertEquals(listOf("Hello", "world"), lines[0].map { it.text })
    }

    @Test
    fun `lines are ordered down the page`() {
        val lines = OcrLayout.toLines(
            listOf(word("second", 0.1f, 0.20f), word("first", 0.1f, 0.10f)),
        )
        assertEquals(listOf("first", "second"), lines.map { it[0].text })
    }

    @Test
    fun `slight vertical wobble still counts as one line`() {
        // Scanned pages are never perfectly level.
        val lines = OcrLayout.toLines(
            listOf(word("a", 0.1f, 0.100f), word("b", 0.2f, 0.104f), word("c", 0.3f, 0.098f)),
        )
        assertEquals(1, lines.size, "a slightly skewed line should not split")
        assertEquals(listOf("a", "b", "c"), lines[0].map { it.text })
    }

    @Test
    fun `separate lines are not merged`() {
        val lines = OcrLayout.toLines(
            listOf(word("top", 0.1f, 0.10f), word("bottom", 0.1f, 0.14f)),
        )
        assertEquals(2, lines.size)
    }

    @Test
    fun `a large vertical gap starts a new paragraph`() {
        val page = OcrLayout.layout(
            listOf(
                word("First", 0.1f, 0.10f),
                word("paragraph.", 0.2f, 0.10f),
                word("Second", 0.1f, 0.20f), // far below — new paragraph
                word("paragraph.", 0.2f, 0.20f),
            ),
            "page-0",
        )
        assertEquals(2, page.document.blocks.size)
        assertEquals("First paragraph.", page.document.blocks[0].text)
        assertEquals("Second paragraph.", page.document.blocks[1].text)
    }

    @Test
    fun `consecutive lines join into one paragraph`() {
        val page = OcrLayout.layout(
            listOf(
                word("one", 0.1f, 0.100f),
                word("two", 0.1f, 0.125f),
                word("three", 0.1f, 0.150f),
            ),
            "page-0",
        )
        assertEquals(1, page.document.blocks.size)
        assertEquals("one two three", page.document.blocks[0].text)
    }

    @Test
    fun `char offsets point at the word in the flattened text`() {
        val page = OcrLayout.layout(
            listOf(
                word("alpha", 0.1f, 0.10f),
                word("beta", 0.3f, 0.10f),
                word("gamma", 0.1f, 0.20f),
            ),
            "page-0",
        )
        val flattened = page.document.flattenedText
        // This is what tap-to-look-up and drag-selection depend on.
        for (w in page.words) {
            assertEquals(
                w.text,
                flattened.substring(w.charOffset, w.charOffset + w.text.length),
                "offset ${w.charOffset} did not point at '${w.text}' in '$flattened'",
            )
        }
    }

    @Test
    fun `offsets increase in reading order`() {
        val page = OcrLayout.layout(
            listOf(
                word("third", 0.1f, 0.20f),
                word("second", 0.3f, 0.10f),
                word("first", 0.1f, 0.10f),
            ),
            "page-0",
        )
        assertEquals(listOf("first", "second", "third"), page.words.map { it.text })
        val offsets = page.words.map { it.charOffset }
        assertEquals(offsets.sorted(), offsets, "offsets must follow reading order")
    }

    @Test
    fun `blank and degenerate words are discarded`() {
        val page = OcrLayout.layout(
            listOf(
                word("real", 0.1f, 0.10f),
                RecognizedWord("   ", 0.3f, 0.10f, 0.35f, 0.12f),
                RecognizedWord("zero", 0.5f, 0.10f, 0.5f, 0.12f), // no width
            ),
            "page-0",
        )
        assertEquals(listOf("real"), page.words.map { it.text })
    }

    @Test
    fun `an empty page yields an empty result`() {
        val page = OcrLayout.layout(emptyList(), "page-0")
        assertTrue(page.isEmpty)
        assertTrue(page.document.blocks.isEmpty())
    }
}
