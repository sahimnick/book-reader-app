package com.bookreader.data

import com.bookreader.core.epub.EpubBook
import com.bookreader.platform.FileStorage
import com.bookreader.platform.PlatformContext
import com.bookreader.platform.openPdfDocument
import com.bookreader.platform.platformInflater
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Pulls a representative chunk of text out of a book.
 *
 * Used to judge whether a book is at the reader's level without reading the
 * whole thing. The sample comes from a third of the way in rather than the
 * start: front matter is a title page, a copyright notice and a dedication,
 * none of which resemble the prose the reader would actually be facing.
 */
object BookSampler {

    /** Enough text for a difficulty estimate to be stable. */
    private const val SAMPLE_CHARS = 4000

    suspend fun sample(
        context: PlatformContext,
        storage: FileStorage,
        book: LibraryBook,
    ): String? = withContext(Dispatchers.Default) {
        runCatching {
            when (book.format) {
                BookFormat.EPUB -> sampleEpub(storage, book)
                BookFormat.PDF -> samplePdf(context, book)
            }
        }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    private suspend fun sampleEpub(storage: FileStorage, book: LibraryBook): String? {
        val bytes = storage.readFile(book.filePath) ?: return null
        val epub = EpubBook.open(bytes, platformInflater())
        if (epub.chapterCount == 0) return null

        // Walk forward from a third in until enough prose turns up; some
        // chapters are a single heading.
        val start = epub.chapterCount / 3
        val text = StringBuilder()
        var index = start
        while (index < epub.chapterCount && text.length < SAMPLE_CHARS) {
            text.append(epub.chapter(index).flattenedText).append('\n')
            index++
        }
        return text.take(SAMPLE_CHARS).toString()
    }

    private suspend fun samplePdf(context: PlatformContext, book: LibraryBook): String? {
        val document = openPdfDocument(context, book.filePath) ?: return null
        try {
            if (document.pageCount == 0) return null
            // One page only. On a scanned PDF this triggers OCR, which is slow
            // enough that sampling several would be felt.
            val page = document.pageCount / 3
            return document.pageText(page).document.flattenedText.take(SAMPLE_CHARS)
        } finally {
            document.close()
        }
    }
}
