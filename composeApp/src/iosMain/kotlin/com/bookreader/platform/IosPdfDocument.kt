package com.bookreader.platform

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import com.bookreader.core.model.BlockStyle
import com.bookreader.core.model.ContentBlock
import com.bookreader.core.model.ContentDocument
import kotlinx.cinterop.CValue
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.cValue
import kotlinx.cinterop.useContents
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Image as SkiaImage
import platform.CoreGraphics.CGRect
import platform.CoreGraphics.CGSize
import platform.Foundation.NSURL
import platform.PDFKit.PDFDisplayBox
import platform.PDFKit.PDFDocument
import platform.PDFKit.PDFPage
import platform.PDFKit.kPDFDisplayBoxMediaBox
import platform.UIKit.UIImagePNGRepresentation

@OptIn(ExperimentalForeignApi::class)
actual suspend fun openPdfDocument(context: PlatformContext, path: String): PdfDocumentSource? =
    withContext(Dispatchers.Default) {
        val url = NSURL.fileURLWithPath(path)
        val document = PDFDocument(uRL = url) ?: return@withContext null
        IosPdfDocument(document)
    }

/**
 * PDF support through PDFKit.
 *
 * Unlike Android, one framework covers both jobs here: PDFKit renders pages and
 * also exposes the text layer with per-character geometry, which is what makes
 * tap-to-look-up possible on a PDF.
 */
@OptIn(ExperimentalForeignApi::class)
private class IosPdfDocument(private val document: PDFDocument) : PdfDocumentSource {

    override val pageCount: Int = document.pageCount.toInt()

    override suspend fun renderPage(pageIndex: Int, widthPx: Int): ImageBitmap? =
        withContext(Dispatchers.Default) {
            val page = document.pageAtIndex(pageIndex.toULong()) ?: return@withContext null
            val bounds = page.boundsForBox(displayBox)

            val (pageWidth, pageHeight) = bounds.useContents {
                size.width to size.height
            }
            if (pageWidth <= 0.0 || pageHeight <= 0.0) return@withContext null

            val targetWidth = widthPx.coerceIn(1, 4096).toDouble()
            val targetHeight = targetWidth * (pageHeight / pageWidth)

            val size: CValue<CGSize> = cValue {
                width = targetWidth
                height = targetHeight
            }
            val uiImage = page.thumbnailOfSize(size, forBox = displayBox)

            // PNG is the simplest lossless bridge from UIImage into Skia, which
            // is what Compose renders through on iOS.
            val png = UIImagePNGRepresentation(uiImage) ?: return@withContext null
            runCatching {
                SkiaImage.makeFromEncoded(png.toByteArray()).toComposeImageBitmap()
            }.getOrNull()
        }

    override suspend fun pageText(pageIndex: Int): PdfPageText = withContext(Dispatchers.Default) {
        val page = document.pageAtIndex(pageIndex.toULong()) ?: return@withContext PdfPageText.Empty
        val text = page.string ?: return@withContext PdfPageText.Empty
        if (text.isBlank()) return@withContext PdfPageText.Empty

        PdfPageText(
            document = ContentDocument(
                id = "page-$pageIndex",
                title = null,
                blocks = blocksFrom(text),
            ),
            words = wordBoxes(page, text),
        )
    }

    /**
     * Groups characters into words and unions their bounding boxes.
     *
     * PDFKit reports geometry per character, so the word boxes the reader taps
     * are assembled here. Coordinates are flipped and normalised to 0..1 with a
     * top-left origin to match the rest of the app; PDF's own origin is
     * bottom-left.
     */
    private fun wordBoxes(page: PDFPage, text: String): List<PdfWordBox> {
        var originX = 0.0
        var originY = 0.0
        var pageWidth = 0.0
        var pageHeight = 0.0
        page.boundsForBox(displayBox).useContents {
            originX = origin.x
            originY = origin.y
            pageWidth = size.width
            pageHeight = size.height
        }
        if (pageWidth <= 0.0 || pageHeight <= 0.0) return emptyList()

        val characterCount = page.numberOfCharacters.toInt()
        val limit = minOf(characterCount, text.length)

        val words = ArrayList<PdfWordBox>()
        var i = 0
        while (i < limit) {
            if (text[i].isWhitespace()) {
                i++
                continue
            }
            val start = i
            var left = Double.MAX_VALUE
            var top = Double.MAX_VALUE
            var right = -Double.MAX_VALUE
            var bottom = -Double.MAX_VALUE
            val sb = StringBuilder()

            while (i < limit && !text[i].isWhitespace()) {
                sb.append(text[i])
                val rect: CValue<CGRect> = page.characterBoundsAtIndex(i.toLong())
                rect.useContents {
                    val x0 = origin.x
                    val y0 = origin.y
                    val x1 = x0 + size.width
                    val y1 = y0 + size.height
                    if (x0 < left) left = x0
                    if (x1 > right) right = x1
                    if (y0 < top) top = y0
                    if (y1 > bottom) bottom = y1
                }
                i++
            }

            val word = sb.toString()
            if (word.isNotBlank() && right > left) {
                // Flip Y: PDF measures up from the bottom of the page.
                val normTop = ((pageHeight - (bottom - originY)) / pageHeight)
                val normBottom = ((pageHeight - (top - originY)) / pageHeight)
                words.add(
                    PdfWordBox(
                        text = word,
                        left = (((left - originX) / pageWidth).coerceIn(0.0, 1.0)).toFloat(),
                        top = (normTop.coerceIn(0.0, 1.0)).toFloat(),
                        right = (((right - originX) / pageWidth).coerceIn(0.0, 1.0)).toFloat(),
                        bottom = (normBottom.coerceIn(0.0, 1.0)).toFloat(),
                        charOffset = start,
                    ),
                )
            }
        }
        return words
    }

    /** Same paragraph reconstruction as Android, so both platforms read alike. */
    private fun blocksFrom(text: String): List<ContentBlock> {
        val blocks = ArrayList<ContentBlock>()
        var offset = 0
        for (chunk in text.split(Regex("\\n\\s*\\n"))) {
            val joined = buildString {
                for (line in chunk.lines()) {
                    val trimmed = line.trim()
                    if (trimmed.isEmpty()) continue
                    if (isEmpty()) {
                        append(trimmed)
                    } else if (endsWith("-")) {
                        setLength(length - 1)
                        append(trimmed)
                    } else {
                        append(' ')
                        append(trimmed)
                    }
                }
            }
            if (joined.isBlank()) continue
            blocks.add(
                ContentBlock(
                    index = blocks.size,
                    text = joined,
                    style = BlockStyle.PARAGRAPH,
                    charOffset = offset,
                ),
            )
            offset += joined.length + 1
        }
        return blocks
    }

    override fun close() = Unit

    private val displayBox: PDFDisplayBox get() = kPDFDisplayBoxMediaBox
}
