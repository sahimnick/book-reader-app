package com.bookreader.platform

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.bookreader.core.model.BlockStyle
import com.bookreader.core.model.ContentBlock
import com.bookreader.core.model.ContentDocument
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Opens a PDF using two libraries on purpose.
 *
 * Page *rendering* goes through the framework's [PdfRenderer], which is
 * hardware-accelerated and produces the same output as every other Android PDF
 * viewer. Page *text* goes through PDFBox, because [PdfRenderer] exposes no text
 * API at all — and text is what the read-aloud and tap-to-look-up features need.
 */
actual suspend fun openPdfDocument(context: PlatformContext, path: String): PdfDocumentSource? =
    withContext(Dispatchers.IO) {
        runCatching {
            PDFBoxResourceLoader.init(context.androidContext.applicationContext)
            val file = File(path)
            if (!file.exists()) return@runCatching null
            AndroidPdfDocument(file)
        }.getOrNull()
    }

private class AndroidPdfDocument(file: File) : PdfDocumentSource {

    private val descriptor: ParcelFileDescriptor =
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)

    private val renderer = PdfRenderer(descriptor)

    /** PdfRenderer allows only one open page at a time and is not thread-safe. */
    private val renderLock = Mutex()

    private val textDocument: PDDocument? = runCatching { PDDocument.load(file) }.getOrNull()
    private val textLock = Mutex()

    override val pageCount: Int = renderer.pageCount

    override suspend fun renderPage(pageIndex: Int, widthPx: Int): ImageBitmap? =
        withContext(Dispatchers.IO) {
            if (pageIndex !in 0 until pageCount) return@withContext null
            renderLock.withLock {
                runCatching {
                    renderer.openPage(pageIndex).use { page ->
                        val targetWidth = widthPx.coerceIn(1, 4096)
                        val scale = targetWidth.toFloat() / page.width
                        val targetHeight = (page.height * scale).toInt().coerceAtLeast(1)

                        val bitmap = Bitmap.createBitmap(
                            targetWidth,
                            targetHeight,
                            Bitmap.Config.ARGB_8888,
                        )
                        // PDFs assume paper; without this, transparent regions
                        // render black in dark theme.
                        bitmap.eraseColor(Color.WHITE)
                        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        bitmap.asImageBitmap()
                    }
                }.getOrNull()
            }
        }

    override suspend fun pageText(pageIndex: Int): PdfPageText = withContext(Dispatchers.IO) {
        val document = textDocument ?: return@withContext PdfPageText.Empty
        if (pageIndex !in 0 until pageCount) return@withContext PdfPageText.Empty

        textLock.withLock {
            runCatching {
                val stripper = WordBoxStripper().apply {
                    startPage = pageIndex + 1
                    endPage = pageIndex + 1
                    sortByPosition = true
                }
                val text = stripper.getText(document)
                if (text.isBlank()) return@runCatching PdfPageText.Empty

                PdfPageText(
                    document = ContentDocument(
                        id = "page-$pageIndex",
                        title = null,
                        blocks = blocksFrom(text),
                    ),
                    words = stripper.words,
                )
            }.getOrDefault(PdfPageText.Empty)
        }
    }

    /**
     * Turns a page's plain text into paragraph blocks.
     *
     * PDF has no paragraph structure — only positioned glyphs — so lines are
     * joined into a paragraph until a blank line appears. Hyphenated line breaks
     * are rejoined so read-aloud does not pronounce half-words.
     */
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

    override fun close() {
        runCatching { renderer.close() }
        runCatching { descriptor.close() }
        runCatching { textDocument?.close() }
    }
}

/**
 * Collects word bounding boxes while PDFBox walks the page.
 *
 * Boxes are normalised to 0..1 of the page so hit-testing a tap works at any
 * zoom level and against a bitmap rendered at any width.
 */
private class WordBoxStripper : PDFTextStripper() {

    val words = ArrayList<PdfWordBox>()
    private var runningOffset = 0

    override fun writeString(text: String, textPositions: List<TextPosition>) {
        val page = currentPage
        val pageWidth = page.mediaBox.width.takeIf { it > 0f } ?: 1f
        val pageHeight = page.mediaBox.height.takeIf { it > 0f } ?: 1f

        var i = 0
        while (i < textPositions.size) {
            // Skip separators; they advance the offset but form no word.
            if (i < text.length && text[i].isWhitespace()) {
                i++
                continue
            }
            val start = i
            var left = Float.MAX_VALUE
            var top = Float.MAX_VALUE
            var right = -Float.MAX_VALUE
            var bottom = -Float.MAX_VALUE
            val sb = StringBuilder()

            while (i < textPositions.size) {
                val ch = if (i < text.length) text[i] else ' '
                if (ch.isWhitespace()) break
                val tp = textPositions[i]
                sb.append(tp.unicode)
                left = minOf(left, tp.xDirAdj)
                top = minOf(top, tp.yDirAdj - tp.heightDir)
                right = maxOf(right, tp.xDirAdj + tp.widthDirAdj)
                bottom = maxOf(bottom, tp.yDirAdj)
                i++
            }

            val word = sb.toString()
            if (word.isNotBlank() && right > left) {
                words.add(
                    PdfWordBox(
                        text = word,
                        left = (left / pageWidth).coerceIn(0f, 1f),
                        top = (top / pageHeight).coerceIn(0f, 1f),
                        right = (right / pageWidth).coerceIn(0f, 1f),
                        bottom = (bottom / pageHeight).coerceIn(0f, 1f),
                        charOffset = runningOffset + start,
                    ),
                )
            }
        }
        runningOffset += text.length
        super.writeString(text, textPositions)
    }
}
