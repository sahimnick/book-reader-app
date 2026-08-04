package com.bookreader.platform

import androidx.compose.ui.graphics.ImageBitmap
import app.cash.sqldelight.db.SqlDriver
import com.bookreader.core.model.ContentDocument
import com.bookreader.core.zip.RawInflater
import kotlinx.coroutines.flow.SharedFlow

/**
 * Handle to whatever the platform needs to reach its services. On Android this
 * is the application `Context`; on iOS nothing is required and it is empty.
 */
expect class PlatformContext

expect fun currentTimeMillis(): Long

expect fun randomUuid(): String

/** The platform's DEFLATE implementation, used to read EPUB archives. */
expect fun platformInflater(): RawInflater

expect fun platformName(): String

// --- Persistence ---------------------------------------------------------

expect class DatabaseDriverFactory(context: PlatformContext) {
    fun createDriver(): SqlDriver
}

/**
 * Where imported books live and how bytes get in and out.
 *
 * Books are copied into app-private storage on import: on both platforms the
 * URI handed over by a document picker is a temporary grant that will not
 * survive a restart, so keeping only the original path would give the reader a
 * library full of files it can no longer open.
 */
expect class FileStorage(context: PlatformContext) {
    /** Copies [sourceUri] into app storage and returns the stored path. */
    suspend fun importBook(sourceUri: String, suggestedName: String): String?

    suspend fun readFile(path: String): ByteArray?

    suspend fun writeFile(name: String, bytes: ByteArray): String

    fun exists(path: String): Boolean

    fun delete(path: String): Boolean

    fun fileName(path: String): String
}

// --- Text to speech ------------------------------------------------------

sealed interface SpeechEvent {
    /** The engine finished initialising and can accept utterances. */
    data object Ready : SpeechEvent

    data class Started(val utteranceId: String) : SpeechEvent

    /**
     * The engine is about to speak [start] until [end] of the utterance text.
     * Used for word-level highlighting inside the current sentence.
     */
    data class Range(val utteranceId: String, val start: Int, val end: Int) : SpeechEvent

    data class Done(val utteranceId: String) : SpeechEvent

    data class Failed(val utteranceId: String?, val message: String) : SpeechEvent
}

data class SpeechVoice(
    val id: String,
    val languageTag: String,
    val displayName: String,
)

/**
 * A platform speech synthesiser.
 *
 * Utterances are single sentences (see `SpeechPlan`), so [Done] is the signal to
 * advance the highlight to the next sentence.
 */
interface SpeechEngine {
    val events: SharedFlow<SpeechEvent>

    /** Suspends until the underlying engine is initialised. */
    suspend fun prepare(): Boolean

    fun speak(text: String, utteranceId: String)

    fun stop()

    /** 1.0 is the engine's normal speed. */
    fun setRate(rate: Float)

    fun setPitch(pitch: Float)

    /** BCP-47 tag, e.g. "en-US" or "fa-IR". */
    fun setLanguage(languageTag: String): Boolean

    suspend fun voices(): List<SpeechVoice>

    fun shutdown()
}

expect fun createSpeechEngine(context: PlatformContext): SpeechEngine

// --- PDF -----------------------------------------------------------------

/**
 * A word laid out on a PDF page, in page coordinates normalised to 0..1.
 *
 * Normalised coordinates keep hit-testing independent of the zoom level and of
 * the bitmap size the page happened to be rendered at.
 */
data class PdfWordBox(
    val text: String,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    /** Offset of this word in the page's extracted text. */
    val charOffset: Int,
) {
    fun contains(x: Float, y: Float): Boolean = x in left..right && y in top..bottom
}

data class PdfPageText(
    val document: ContentDocument,
    val words: List<PdfWordBox>,
) {
    companion object {
        val Empty = PdfPageText(ContentDocument.Empty, emptyList())
    }
}

interface PdfDocumentSource {
    val pageCount: Int

    /** Renders a page to a bitmap [widthPx] wide, preserving aspect ratio. */
    suspend fun renderPage(pageIndex: Int, widthPx: Int): ImageBitmap?

    /**
     * Extracted text and word boxes for a page. Returns [PdfPageText.Empty] for
     * scanned pages that carry no text layer.
     */
    suspend fun pageText(pageIndex: Int): PdfPageText

    fun close()
}

expect suspend fun openPdfDocument(context: PlatformContext, path: String): PdfDocumentSource?

// --- Images --------------------------------------------------------------

/**
 * Decodes an encoded image (PNG/JPEG/GIF) into a Compose bitmap. Used for EPUB
 * illustrations, which arrive as raw bytes out of the ZIP archive. Returns null
 * for formats the platform cannot decode rather than throwing, so one bad image
 * cannot take down a chapter.
 */
expect fun decodeImage(bytes: ByteArray): ImageBitmap?
