package com.bookreader.verify

import com.bookreader.core.epub.HtmlContentExtractor
import com.bookreader.core.epub.percentDecode
import com.bookreader.core.epub.resolvePath
import com.bookreader.core.model.BlockStyle
import com.bookreader.core.xml.Entities
import com.bookreader.core.xml.parseXml
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EntitiesTest {

    @Test
    fun `decodes named references`() {
        assertEquals("a&b<c>d", Entities.decode("a&amp;b&lt;c&gt;d"))
        assertEquals("caf e", Entities.decode("caf&nbsp;e"))
    }

    @Test
    fun `decodes numeric and hex references`() {
        assertEquals("A", Entities.decode("&#65;"))
        assertEquals("A", Entities.decode("&#x41;"))
    }

    @Test
    fun `decodes astral plane characters via surrogate pairs`() {
        val decoded = Entities.decode("&#x1F600;")
        assertEquals(2, decoded.length)
        assertEquals(0x1F600, decoded.codePointAt(0))
    }

    @Test
    fun `leaves bare ampersands alone`() {
        assertEquals("Tom & Jerry", Entities.decode("Tom & Jerry"))
        assertEquals("a &notarealentity; b", Entities.decode("a &notarealentity; b"))
    }
}

class XmlParserTest {

    @Test
    fun `builds a tree and strips namespaces`() {
        val doc = parseXml(
            """
            <?xml version="1.0"?>
            <package xmlns:dc="http://purl.org/dc/elements/1.1/">
              <metadata><dc:title>My Book</dc:title><dc:creator>Ada</dc:creator></metadata>
            </package>
            """.trimIndent(),
        )
        val pkg = doc.child("package")!!
        assertEquals("My Book", pkg.descendants("title").first().allText())
        assertEquals("Ada", pkg.descendants("creator").first().allText())
    }

    @Test
    fun `reads attributes with either quote style and no quotes`() {
        val doc = parseXml("""<item id="a" href='b.xhtml' media-type=text/plain/>""")
        val item = doc.descendants("item").first()
        assertEquals("a", item.attr("id"))
        assertEquals("b.xhtml", item.attr("href"))
        assertEquals("text/plain", item.attr("media-type"))
    }

    @Test
    fun `ignores comments doctype and cdata markers`() {
        val doc = parseXml("<!DOCTYPE html><!-- hi --><p><![CDATA[raw & text]]></p>")
        assertEquals("raw & text", doc.descendants("p").first().allText())
    }

    @Test
    fun `survives unclosed tags`() {
        val doc = parseXml("<div><p>one<p>two</div>")
        val paragraphs = doc.descendants("p")
        assertTrue(paragraphs.size >= 2)
    }

    @Test
    fun `ignores a stray end tag`() {
        val doc = parseXml("<div>text</span></div>")
        assertEquals("text", doc.descendants("div").first().allText())
    }

    @Test
    fun `does not end a tag on a quoted angle bracket`() {
        val doc = parseXml("""<a title="1 > 0">link</a>""")
        val a = doc.descendants("a").first()
        assertEquals("1 > 0", a.attr("title"))
        assertEquals("link", a.allText())
    }
}

class HtmlContentExtractorTest {

    @Test
    fun `extracts paragraphs and headings`() {
        val blocks = HtmlContentExtractor.extract(
            """
            <html><body>
              <h1>The Title</h1>
              <p>First paragraph.</p>
              <p>Second <em>paragraph</em> here.</p>
            </body></html>
            """.trimIndent(),
        )
        assertEquals(3, blocks.size)
        assertEquals(BlockStyle.HEADING_1, blocks[0].style)
        assertEquals("The Title", blocks[0].text)
        assertEquals("First paragraph.", blocks[1].text)
        // Inline elements are flattened into the paragraph text.
        assertEquals("Second paragraph here.", blocks[2].text)
    }

    @Test
    fun `drops script and style content`() {
        val blocks = HtmlContentExtractor.extract(
            "<body><style>p { color: red; }</style><script>var x = 1;</script><p>Kept.</p></body>",
        )
        assertEquals(1, blocks.size)
        assertEquals("Kept.", blocks[0].text)
    }

    @Test
    fun `collapses whitespace`() {
        val blocks = HtmlContentExtractor.extract("<p>   lots\n\n  of   space   </p>")
        assertEquals("lots of space", blocks[0].text)
    }

    @Test
    fun `marks list items and quotes`() {
        val blocks = HtmlContentExtractor.extract(
            "<ul><li>one</li><li>two</li></ul><blockquote><p>quoted</p></blockquote>",
        )
        assertEquals(BlockStyle.LIST_ITEM, blocks[0].style)
        assertEquals(BlockStyle.LIST_ITEM, blocks[1].style)
        assertEquals(BlockStyle.QUOTE, blocks[2].style)
    }

    @Test
    fun `captures images through the resolver`() {
        val blocks = HtmlContentExtractor.extract(
            """<p>before</p><img src="pics/a.png" alt="A picture"/><p>after</p>""",
        ) { "OEBPS/$it" }
        val image = blocks.single { it.style == BlockStyle.IMAGE }
        assertEquals("OEBPS/pics/a.png", image.imageHref)
        assertEquals("A picture", image.text)
    }

    @Test
    fun `drops images the resolver rejects`() {
        val blocks = HtmlContentExtractor.extract("""<img src="missing.png"/><p>text</p>""") { null }
        assertTrue(blocks.none { it.style == BlockStyle.IMAGE })
    }

    @Test
    fun `char offsets line up with the flattened text`() {
        val blocks = HtmlContentExtractor.extract(
            "<p>Alpha beta.</p><p>Gamma delta.</p><p>Epsilon.</p>",
        )
        val flattened = blocks.filter { it.isSpoken }.joinToString("\n") { it.text }
        // The offsets drive TTS highlighting, so each must index its own text.
        for (block in blocks.filter { it.isSpoken }) {
            assertEquals(
                block.text,
                flattened.substring(block.charOffset, block.charOffset + block.text.length),
            )
        }
    }

    @Test
    fun `preserves preformatted whitespace`() {
        val blocks = HtmlContentExtractor.extract("<pre>line one\n  indented</pre>")
        assertEquals(BlockStyle.PREFORMATTED, blocks[0].style)
        assertTrue(blocks[0].text.contains("\n  indented"))
    }

    @Test
    fun `treats br as a line break inside a block`() {
        val blocks = HtmlContentExtractor.extract("<p>one<br/>two</p>")
        assertEquals(1, blocks.size)
        assertEquals("one two", blocks[0].text)
    }
}

class PathResolutionTest {

    @Test
    fun `resolves relative hrefs against the base directory`() {
        assertEquals("OEBPS/ch1.xhtml", resolvePath("OEBPS", "ch1.xhtml"))
        assertEquals("OEBPS/text/ch1.xhtml", resolvePath("OEBPS/text", "ch1.xhtml"))
    }

    @Test
    fun `walks up with dot dot`() {
        assertEquals("OEBPS/images/a.png", resolvePath("OEBPS/text", "../images/a.png"))
        assertEquals("a.png", resolvePath("OEBPS", "../a.png"))
    }

    @Test
    fun `drops fragments and query strings`() {
        assertEquals("OEBPS/ch1.xhtml", resolvePath("OEBPS", "ch1.xhtml#section2"))
    }

    @Test
    fun `handles an absolute href`() {
        assertEquals("OEBPS/ch1.xhtml", resolvePath("somewhere/else", "/OEBPS/ch1.xhtml"))
    }

    @Test
    fun `percent decodes so names match zip entries`() {
        assertEquals("OEBPS/chapter 1.xhtml", resolvePath("OEBPS", "chapter%201.xhtml"))
        assertEquals("فصل.xhtml", percentDecode("%D9%81%D8%B5%D9%84.xhtml"))
    }

    @Test
    fun `empty base directory works`() {
        assertEquals("content.opf", resolvePath("", "content.opf"))
    }
}
