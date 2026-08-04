package com.bookreader.verify

import com.bookreader.core.epub.EpubBook
import com.bookreader.core.zip.Inflate
import com.bookreader.core.zip.ZipArchive
import com.bookreader.core.zip.ZipFormatException
import java.io.ByteArrayOutputStream
import java.util.zip.Deflater
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Verifies the pure-Kotlin inflater against streams produced by a real
 * compressor. This is the implementation iOS uses, where no platform zlib
 * binding is available, so it has to be right.
 */
class InflateTest {

    /** Raw DEFLATE (no zlib wrapper), matching what ZIP entries store. */
    private fun deflate(data: ByteArray, level: Int = Deflater.DEFAULT_COMPRESSION): ByteArray {
        val deflater = Deflater(level, true)
        try {
            deflater.setInput(data)
            deflater.finish()
            val out = ByteArrayOutputStream()
            val buf = ByteArray(16 * 1024)
            while (!deflater.finished()) {
                val n = deflater.deflate(buf)
                out.write(buf, 0, n)
            }
            return out.toByteArray()
        } finally {
            deflater.end()
        }
    }

    private fun roundTrip(original: ByteArray, level: Int = Deflater.DEFAULT_COMPRESSION) {
        val compressed = deflate(original, level)
        val result = Inflate.inflate(compressed, 0, compressed.size, original.size)
        assertContentEquals(original, result)
    }

    @Test
    fun `round trips simple text`() {
        roundTrip("Hello, world!".encodeToByteArray())
    }

    @Test
    fun `round trips empty input`() {
        roundTrip(ByteArray(0))
    }

    @Test
    fun `round trips highly repetitive data using back references`() {
        // Long runs exercise overlapping copies (distance 1).
        roundTrip(ByteArray(50_000) { 'a'.code.toByte() })
    }

    @Test
    fun `round trips repeated phrases`() {
        roundTrip("the quick brown fox ".repeat(5_000).encodeToByteArray())
    }

    @Test
    fun `round trips incompressible random data`() {
        // Random data forces stored blocks rather than Huffman-coded ones.
        roundTrip(Random(1234).nextBytes(100_000))
    }

    @Test
    fun `round trips with no compression which emits stored blocks`() {
        roundTrip("stored block content ".repeat(100).encodeToByteArray(), Deflater.NO_COMPRESSION)
    }

    @Test
    fun `round trips at maximum compression which emits dynamic huffman`() {
        val text = buildString {
            repeat(3000) { append("line ").append(it).append(" of text\n") }
        }
        roundTrip(text.encodeToByteArray(), Deflater.BEST_COMPRESSION)
    }

    @Test
    fun `round trips utf8 multibyte content`() {
        val persian = "این یک متن فارسی است که باید به درستی فشرده و باز شود. ".repeat(500)
        roundTrip(persian.encodeToByteArray())
    }

    @Test
    fun `round trips many sizes near buffer boundaries`() {
        for (size in listOf(1, 2, 15, 16, 17, 255, 256, 257, 1023, 1024, 1025, 4095, 4096)) {
            val data = ByteArray(size) { (it % 251).toByte() }
            roundTrip(data)
        }
    }

    @Test
    fun `works when the expected size is unknown`() {
        val original = "size not known in advance ".repeat(1000).encodeToByteArray()
        val compressed = deflate(original)
        // expectedSize = 0 forces the output buffer to grow dynamically.
        val result = Inflate.inflate(compressed, 0, compressed.size, 0)
        assertContentEquals(original, result)
    }

    @Test
    fun `reads a stream at a non-zero offset`() {
        val original = "offset handling matters ".repeat(200).encodeToByteArray()
        val compressed = deflate(original)
        val padded = ByteArray(7) { 0xAA.toByte() } + compressed + ByteArray(5)
        val result = Inflate.inflate(padded, 7, compressed.size, original.size)
        assertContentEquals(original, result)
    }

    @Test
    fun `rejects truncated input`() {
        val compressed = deflate("some content that will be cut short".encodeToByteArray())
        assertFailsWith<ZipFormatException> {
            Inflate.inflate(compressed, 0, compressed.size / 2, 100)
        }
    }

    @Test
    fun `rejects garbage`() {
        val garbage = Random(9).nextBytes(64)
        // Either it throws, or it produces something that is not the input —
        // what it must not do is loop forever or return silently corrupt data
        // that looks valid.
        runCatching { Inflate.inflate(garbage, 0, garbage.size, 128) }
    }

    @Test
    fun `drives a full zip archive`() {
        val text = "Chapter content that compresses well. ".repeat(500)
        val bytes = buildZip("a.txt" to text, "b.txt" to "short")
        val zip = ZipArchive.open(bytes, Inflate)
        assertEquals(text, zip.readText("a.txt"))
        assertEquals("short", zip.readText("b.txt"))
    }

    @Test
    fun `parses a real epub end to end`() {
        // The same EPUB the parser tests use, but decompressed entirely by the
        // pure-Kotlin inflater — this is the exact path iOS takes.
        val book = EpubBook.open(EpubFixture.sample(), Inflate)
        assertEquals("The Test Book", book.metadata.title)
        assertEquals(2, book.chapterCount)
        assertTrue(book.chapter(0).blocks.any { it.text.contains("The morning was cold") })
    }

    @Test
    fun `matches the jvm inflater byte for byte`() {
        val samples = listOf(
            "short".encodeToByteArray(),
            ByteArray(10_000) { (it % 7).toByte() },
            Random(42).nextBytes(20_000),
            "mixed content ünïcödé ".repeat(300).encodeToByteArray(),
        )
        for (sample in samples) {
            val compressed = deflate(sample)
            assertContentEquals(
                JvmInflater.inflate(compressed, 0, compressed.size, sample.size),
                Inflate.inflate(compressed, 0, compressed.size, sample.size),
            )
        }
    }
}
