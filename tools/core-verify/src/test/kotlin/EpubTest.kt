package com.bookreader.verify

import com.bookreader.core.epub.EpubBook
import com.bookreader.core.epub.EpubParseException
import com.bookreader.core.model.BlockStyle
import com.bookreader.core.text.SentenceSegmenter
import com.bookreader.core.zip.ZipArchive
import com.bookreader.core.zip.ZipFormatException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ZipArchiveTest {

    @Test
    fun `reads deflated and stored entries`() {
        val longText = "compress me ".repeat(200)
        val bytes = buildZip(
            listOf(
                "stored.txt" to "plain".toByteArray(),
                "deflated.txt" to longText.toByteArray(),
            ),
            stored = setOf("stored.txt"),
        )
        val zip = ZipArchive.open(bytes, JvmInflater)
        assertEquals("plain", zip.readText("stored.txt"))
        assertEquals(longText, zip.readText("deflated.txt"))
    }

    @Test
    fun `enumerates entries`() {
        val zip = ZipArchive.open(buildZip("a.txt" to "a", "dir/b.txt" to "b"), JvmInflater)
        assertEquals(setOf("a.txt", "dir/b.txt"), zip.names)
        assertTrue(zip.contains("dir/b.txt"))
    }

    @Test
    fun `missing entry returns null`() {
        val zip = ZipArchive.open(buildZip("a.txt" to "a"), JvmInflater)
        assertNull(zip.read("nope.txt"))
    }

    @Test
    fun `strips a utf8 byte order mark`() {
        val withBom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) +
            "<?xml version=\"1.0\"?>".toByteArray()
        val zip = ZipArchive.open(buildZip(listOf("f.xml" to withBom)), JvmInflater)
        assertEquals("<?xml version=\"1.0\"?>", zip.readText("f.xml"))
    }

    @Test
    fun `handles unicode entry names`() {
        val zip = ZipArchive.open(buildZip("متن/فصل.xhtml" to "سلام"), JvmInflater)
        assertEquals("سلام", zip.readText("متن/فصل.xhtml"))
    }

    @Test
    fun `rejects data that is not a zip`() {
        assertFailsWith<ZipFormatException> {
            ZipArchive.open("this is definitely not a zip file".toByteArray(), JvmInflater)
        }
    }
}

class EpubBookTest {

    private fun sampleEpub(): ByteArray = EpubFixture.sample()

    @Test
    fun `reads package metadata`() {
        val book = EpubBook.open(sampleEpub(), JvmInflater)
        assertEquals("The Test Book", book.metadata.title)
        assertEquals("Ada Lovelace", book.metadata.author)
        assertEquals("en", book.metadata.language)
        assertEquals("Test Press", book.metadata.publisher)
        assertEquals("urn:uuid:1234", book.metadata.identifier)
    }

    @Test
    fun `resolves the cover through the meta pointer`() {
        val book = EpubBook.open(sampleEpub(), JvmInflater)
        assertEquals("OEBPS/images/cover.png", book.metadata.coverHref)
        assertNotNull(book.coverImage())
    }

    @Test
    fun `builds the spine in reading order`() {
        val book = EpubBook.open(sampleEpub(), JvmInflater)
        assertEquals(2, book.chapterCount)
        assertEquals("OEBPS/text/chapter1.xhtml", book.spine[0].href)
        assertEquals("OEBPS/text/chapter2.xhtml", book.spine[1].href)
    }

    @Test
    fun `prefers the epub3 nav document for the table of contents`() {
        val book = EpubBook.open(sampleEpub(), JvmInflater)
        assertEquals("Chapter One", book.toc[0].title)
        assertEquals(0, book.toc[0].spineIndex)
        assertEquals("Chapter Two", book.toc[1].title)
        // Nested list item is captured one level deeper.
        assertEquals("A Subsection", book.toc[2].title)
        assertEquals(1, book.toc[2].level)
    }

    @Test
    fun `parses a chapter into blocks`() {
        val book = EpubBook.open(sampleEpub(), JvmInflater)
        val chapter = book.chapter(0)
        assertEquals("Chapter One", chapter.title)

        val heading = chapter.blocks.first()
        assertEquals(BlockStyle.HEADING_1, heading.style)
        assertEquals("Chapter One", heading.text)

        // Entity decoded, whitespace collapsed.
        assertTrue(chapter.blocks.any { it.text == "She read for hours & forgot the time." })
    }

    @Test
    fun `rewrites chapter image paths to archive paths`() {
        val book = EpubBook.open(sampleEpub(), JvmInflater)
        val image = book.chapter(0).blocks.single { it.style == BlockStyle.IMAGE }
        // src was "../images/cover.png" relative to OEBPS/text/
        assertEquals("OEBPS/images/cover.png", image.imageHref)
        assertNotNull(book.resource(image.imageHref!!))
    }

    @Test
    fun `flattened chapter text feeds the segmenter`() {
        val book = EpubBook.open(sampleEpub(), JvmInflater)
        val sentences = SentenceSegmenter.segment(book.chapter(0).flattenedText)
        val texts = sentences.map { it.text }
        assertTrue("The morning was cold." in texts)
        assertTrue("Ada opened the book." in texts)
    }

    @Test
    fun `abbreviations inside a real chapter do not split`() {
        val book = EpubBook.open(sampleEpub(), JvmInflater)
        val sentences = SentenceSegmenter.segmentToStrings(book.chapter(1).flattenedText)
        assertTrue(sentences.any { it == "Dr. Babbage arrived at 9.30 sharp." })
    }

    @Test
    fun `out of range chapter yields an empty document`() {
        val book = EpubBook.open(sampleEpub(), JvmInflater)
        assertTrue(book.chapter(99).blocks.isEmpty())
    }

    @Test
    fun `rejects a non-epub file`() {
        assertFailsWith<EpubParseException> {
            EpubBook.open("not an epub".toByteArray(), JvmInflater)
        }
    }

    @Test
    fun `falls back to the ncx when no nav document is declared`() {
        // Same book with the nav property removed from the manifest.
        val withoutNav = String(sampleEpub().let { it }, Charsets.ISO_8859_1)
        // Rebuild rather than patch bytes: construct an EPUB whose only TOC is the NCX.
        val container = """
            <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
              <rootfiles><rootfile full-path="content.opf"/></rootfiles>
            </container>
        """.trimIndent()
        val opf = """
            <package version="2.0">
              <metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>Old Book</dc:title></metadata>
              <manifest>
                <item id="ch1" href="c1.xhtml" media-type="application/xhtml+xml"/>
                <item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>
              </manifest>
              <spine toc="ncx"><itemref idref="ch1"/></spine>
            </package>
        """.trimIndent()
        val ncx = """
            <ncx><navMap>
              <navPoint><navLabel><text>Opening</text></navLabel><content src="c1.xhtml"/></navPoint>
            </navMap></ncx>
        """.trimIndent()
        val bytes = buildZip(
            "META-INF/container.xml" to container,
            "content.opf" to opf,
            "toc.ncx" to ncx,
            "c1.xhtml" to "<html><body><p>Hello.</p></body></html>",
        )
        val book = EpubBook.open(bytes, JvmInflater)
        assertEquals("Old Book", book.metadata.title)
        assertEquals("Opening", book.toc[0].title)
        assertEquals(0, book.toc[0].spineIndex)
        assertTrue(withoutNav.isNotEmpty())
    }
}
