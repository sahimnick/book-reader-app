package com.bookreader.verify

/**
 * A minimal but structurally real EPUB 3, shared by the parser tests and the
 * inflater tests so both exercise the same archive.
 */
object EpubFixture {

    /** A minimal but structurally real EPUB 3 with an NCX fallback. */
    fun sample(): ByteArray {
        val container = """
            <?xml version="1.0" encoding="UTF-8"?>
            <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
              <rootfiles>
                <rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>
              </rootfiles>
            </container>
        """.trimIndent()

        val opf = """
            <?xml version="1.0" encoding="UTF-8"?>
            <package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="uid">
              <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                <dc:title>The Test Book</dc:title>
                <dc:creator>Ada Lovelace</dc:creator>
                <dc:language>en</dc:language>
                <dc:identifier id="uid">urn:uuid:1234</dc:identifier>
                <dc:publisher>Test Press</dc:publisher>
                <meta name="cover" content="cover-img"/>
              </metadata>
              <manifest>
                <item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
                <item id="ch1" href="text/chapter1.xhtml" media-type="application/xhtml+xml"/>
                <item id="ch2" href="text/chapter2.xhtml" media-type="application/xhtml+xml"/>
                <item id="cover-img" href="images/cover.png" media-type="image/png"/>
                <item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>
              </manifest>
              <spine toc="ncx">
                <itemref idref="ch1"/>
                <itemref idref="ch2"/>
              </spine>
            </package>
        """.trimIndent()

        val nav = """
            <?xml version="1.0" encoding="UTF-8"?>
            <html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops">
              <body>
                <nav epub:type="toc">
                  <ol>
                    <li><a href="text/chapter1.xhtml">Chapter One</a></li>
                    <li><a href="text/chapter2.xhtml">Chapter Two</a>
                      <ol><li><a href="text/chapter2.xhtml#s1">A Subsection</a></li></ol>
                    </li>
                  </ol>
                </nav>
              </body>
            </html>
        """.trimIndent()

        val chapter1 = """
            <?xml version="1.0" encoding="UTF-8"?>
            <html xmlns="http://www.w3.org/1999/xhtml">
              <head><title>Chapter One</title></head>
              <body>
                <h1>Chapter One</h1>
                <p>The morning was cold. Ada opened the book.</p>
                <p>She read for hours &amp; forgot the time.</p>
                <img src="../images/cover.png" alt="A cover"/>
              </body>
            </html>
        """.trimIndent()

        val chapter2 = """
            <?xml version="1.0" encoding="UTF-8"?>
            <html xmlns="http://www.w3.org/1999/xhtml">
              <body>
                <h1>Chapter Two</h1>
                <p>Dr. Babbage arrived at 9.30 sharp.</p>
                <blockquote><p>"We shall build it," he said.</p></blockquote>
              </body>
            </html>
        """.trimIndent()

        val ncx = """
            <?xml version="1.0" encoding="UTF-8"?>
            <ncx xmlns="http://www.daisy.org/z3986/2005/ncx/" version="2005-1">
              <navMap>
                <navPoint id="n1"><navLabel><text>Chapter One</text></navLabel>
                  <content src="text/chapter1.xhtml"/></navPoint>
                <navPoint id="n2"><navLabel><text>Chapter Two</text></navLabel>
                  <content src="text/chapter2.xhtml"/></navPoint>
              </navMap>
            </ncx>
        """.trimIndent()

        return buildZip(
            listOf(
                "mimetype" to "application/epub+zip".toByteArray(),
                "META-INF/container.xml" to container.toByteArray(),
                "OEBPS/content.opf" to opf.toByteArray(),
                "OEBPS/nav.xhtml" to nav.toByteArray(),
                "OEBPS/text/chapter1.xhtml" to chapter1.toByteArray(),
                "OEBPS/text/chapter2.xhtml" to chapter2.toByteArray(),
                "OEBPS/toc.ncx" to ncx.toByteArray(),
                "OEBPS/images/cover.png" to byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47),
            ),
            stored = setOf("mimetype"),
        )
    }
}
