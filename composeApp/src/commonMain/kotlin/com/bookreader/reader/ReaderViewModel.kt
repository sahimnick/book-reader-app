package com.bookreader.reader

import com.bookreader.AppContainer
import com.bookreader.core.dictionary.Sense
import com.bookreader.core.dictionary.WordEntry
import com.bookreader.core.epub.EpubBook
import com.bookreader.core.model.ContentDocument
import com.bookreader.core.text.WordTokenizer
import com.bookreader.core.tts.SpeechPlan
import com.bookreader.data.BookFormat
import com.bookreader.data.LibraryBook
import com.bookreader.data.ReadingPosition
import com.bookreader.platform.PdfDocumentSource
import com.bookreader.platform.PdfWordBox
import com.bookreader.platform.SpeechEvent
import com.bookreader.platform.openPdfDocument
import com.bookreader.platform.platformInflater
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What the reader is currently doing. */
enum class PlaybackState { IDLE, PREPARING, SPEAKING, PAUSED }

data class LookupResult(
    val entry: WordEntry,
    /** The sentence the word was found in, saved with the flashcard. */
    val context: String,
    val isSaved: Boolean = false,
)

data class ReaderUiState(
    val book: LibraryBook? = null,
    val document: ContentDocument = ContentDocument.Empty,
    val unitIndex: Int = 0,
    val unitCount: Int = 0,
    val chapterTitle: String? = null,
    val isLoading: Boolean = true,
    val error: String? = null,

    // Read-aloud
    val playback: PlaybackState = PlaybackState.IDLE,
    val currentSentence: Int = -1,
    val currentBlock: Int = -1,
    val wordHighlight: IntRange? = null,
    val speechRate: Float = 1.0f,

    // PDF
    val pdfPage: Int = 0,
    val pdfWords: List<PdfWordBox> = emptyList(),

    // Lookup
    val lookup: LookupResult? = null,
    val isLookingUp: Boolean = false,
)

/**
 * Drives one open book: paging, read-aloud, and word lookup.
 *
 * Speech advances a sentence at a time. Each sentence is a separate utterance
 * whose id encodes its index, so a `Done` event identifies exactly which
 * sentence finished even if the engine drops or reorders callbacks — the
 * alternative, tracking position in a mutable field, desynchronises the
 * highlight the first time an utterance is cancelled mid-word.
 */
class ReaderViewModel(
    private val container: AppContainer,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow(ReaderUiState())
    val state: StateFlow<ReaderUiState> = _state.asStateFlow()

    private var epub: EpubBook? = null
    private var pdf: PdfDocumentSource? = null
    private var plan: SpeechPlan = SpeechPlan.Empty
    private var speechJob: Job? = null
    private var speechReady = false

    private companion object {
        const val UTTERANCE_PREFIX = "sentence-"
    }

    init {
        observeSpeech()
    }

    // --- Loading ---------------------------------------------------------

    fun open(book: LibraryBook) {
        scope.launch {
            _state.update { it.copy(isLoading = true, error = null, book = book) }
            container.books.touch(book.id)
            val saved = container.books.position(book.id)

            when (book.format) {
                BookFormat.EPUB -> openEpub(book, saved)
                BookFormat.PDF -> openPdf(book, saved)
            }
        }
    }

    private suspend fun openEpub(book: LibraryBook, saved: ReadingPosition?) {
        val bytes = container.fileStorage.readFile(book.filePath)
        if (bytes == null) {
            _state.update { it.copy(isLoading = false, error = "Could not read ${book.title}") }
            return
        }
        val parsed = runCatching { EpubBook.open(bytes, platformInflater()) }
        val opened = parsed.getOrElse { e ->
            _state.update {
                it.copy(isLoading = false, error = e.message ?: "This EPUB could not be opened")
            }
            return
        }
        epub = opened
        val index = (saved?.unitIndex ?: 0).coerceIn(0, opened.chapterCount - 1)
        showUnit(index)
    }

    private suspend fun openPdf(book: LibraryBook, saved: ReadingPosition?) {
        val document = openPdfDocument(container.platformContext, book.filePath)
        if (document == null) {
            _state.update { it.copy(isLoading = false, error = "This PDF could not be opened") }
            return
        }
        pdf = document
        val index = (saved?.unitIndex ?: 0).coerceIn(0, (document.pageCount - 1).coerceAtLeast(0))
        showUnit(index)
    }

    /** Loads a chapter (EPUB) or page (PDF) and rebuilds the speech plan. */
    fun showUnit(index: Int) {
        scope.launch {
            stopSpeaking()
            _state.update { it.copy(isLoading = true) }

            val epubBook = epub
            val pdfDoc = pdf

            when {
                epubBook != null -> {
                    val bounded = index.coerceIn(0, epubBook.chapterCount - 1)
                    val document = epubBook.chapter(bounded)
                    plan = SpeechPlan(document)
                    _state.update {
                        it.copy(
                            document = document,
                            unitIndex = bounded,
                            unitCount = epubBook.chapterCount,
                            chapterTitle = document.title,
                            isLoading = false,
                            currentSentence = -1,
                            currentBlock = -1,
                            wordHighlight = null,
                            pdfWords = emptyList(),
                        )
                    }
                    persistPosition(bounded)
                }

                pdfDoc != null -> {
                    val bounded = index.coerceIn(0, (pdfDoc.pageCount - 1).coerceAtLeast(0))
                    val pageText = pdfDoc.pageText(bounded)
                    plan = SpeechPlan(pageText.document)
                    _state.update {
                        it.copy(
                            document = pageText.document,
                            unitIndex = bounded,
                            pdfPage = bounded,
                            unitCount = pdfDoc.pageCount,
                            chapterTitle = "Page ${bounded + 1}",
                            pdfWords = pageText.words,
                            isLoading = false,
                            currentSentence = -1,
                            currentBlock = -1,
                            wordHighlight = null,
                        )
                    }
                    persistPosition(bounded)
                }

                else -> _state.update { it.copy(isLoading = false) }
            }
        }
    }

    fun nextUnit() = showUnit(_state.value.unitIndex + 1)

    fun previousUnit() = showUnit(_state.value.unitIndex - 1)

    suspend fun renderPdfPage(widthPx: Int) = pdf?.renderPage(_state.value.unitIndex, widthPx)

    val tableOfContents get() = epub?.toc.orEmpty()

    fun epubResource(path: String): ByteArray? = epub?.resource(path)

    /** The sentence at [index] of the current plan, for drawing the highlight. */
    fun sentenceAt(index: Int) = plan.sentenceAt(index)

    private suspend fun persistPosition(index: Int) {
        val bookId = _state.value.book?.id ?: return
        container.books.savePosition(
            bookId,
            ReadingPosition(unitIndex = index, blockIndex = 0, charOffset = 0),
        )
    }

    // --- Read aloud ------------------------------------------------------

    private fun observeSpeech() {
        speechJob = scope.launch {
            container.speech.events.collect { event ->
                when (event) {
                    is SpeechEvent.Ready -> speechReady = true

                    is SpeechEvent.Started -> {
                        val index = event.utteranceId.sentenceIndex() ?: return@collect
                        _state.update {
                            it.copy(
                                playback = PlaybackState.SPEAKING,
                                currentSentence = index,
                                currentBlock = plan.blockIndexForSentence(index),
                                wordHighlight = null,
                            )
                        }
                    }

                    is SpeechEvent.Range -> {
                        val index = event.utteranceId.sentenceIndex() ?: return@collect
                        _state.update {
                            it.copy(wordHighlight = plan.absoluteRange(index, event.start, event.end))
                        }
                    }

                    is SpeechEvent.Done -> {
                        val index = event.utteranceId.sentenceIndex() ?: return@collect
                        // Only advance if still playing; a stop cancels in-flight events.
                        if (_state.value.playback == PlaybackState.SPEAKING) {
                            speakSentence(index + 1)
                        }
                    }

                    is SpeechEvent.Failed -> _state.update {
                        it.copy(playback = PlaybackState.IDLE, error = event.message)
                    }
                }
            }
        }
    }

    private fun String.sentenceIndex(): Int? =
        removePrefix(UTTERANCE_PREFIX).toIntOrNull()

    fun playFromBlock(blockIndex: Int) {
        val sentence = plan.firstSentenceOfBlock(blockIndex)
        startSpeaking(sentence)
    }

    fun togglePlayback() {
        when (_state.value.playback) {
            PlaybackState.SPEAKING -> pauseSpeaking()
            PlaybackState.PAUSED -> startSpeaking(_state.value.currentSentence.coerceAtLeast(0))
            else -> startSpeaking(_state.value.currentSentence.coerceAtLeast(0))
        }
    }

    private fun startSpeaking(fromSentence: Int) {
        if (plan.isEmpty) return
        scope.launch {
            _state.update { it.copy(playback = PlaybackState.PREPARING) }
            if (!speechReady) {
                speechReady = container.speech.prepare()
                if (!speechReady) {
                    _state.update {
                        it.copy(
                            playback = PlaybackState.IDLE,
                            error = "Text-to-speech is unavailable on this device",
                        )
                    }
                    return@launch
                }
            }
            container.speech.setRate(_state.value.speechRate)
            _state.update { it.copy(playback = PlaybackState.SPEAKING) }
            speakSentence(fromSentence.coerceIn(0, plan.size - 1))
        }
    }

    private fun speakSentence(index: Int) {
        val sentence = plan.sentenceAt(index)
        if (sentence == null) {
            // End of chapter: roll on to the next one so playback continues.
            _state.update { it.copy(playback = PlaybackState.IDLE, wordHighlight = null) }
            if (_state.value.unitIndex + 1 < _state.value.unitCount) {
                scope.launch {
                    showUnit(_state.value.unitIndex + 1)
                    startSpeaking(0)
                }
            }
            return
        }
        _state.update {
            it.copy(
                currentSentence = index,
                currentBlock = plan.blockIndexForSentence(index),
            )
        }
        container.speech.speak(sentence.text, "$UTTERANCE_PREFIX$index")
    }

    fun pauseSpeaking() {
        container.speech.stop()
        _state.update { it.copy(playback = PlaybackState.PAUSED, wordHighlight = null) }
    }

    fun stopSpeaking() {
        container.speech.stop()
        _state.update {
            it.copy(
                playback = PlaybackState.IDLE,
                currentSentence = -1,
                currentBlock = -1,
                wordHighlight = null,
            )
        }
    }

    fun skipToNextSentence() {
        val next = (_state.value.currentSentence + 1).coerceAtMost(plan.size - 1)
        if (_state.value.playback == PlaybackState.SPEAKING) startSpeaking(next)
        else _state.update { it.copy(currentSentence = next, currentBlock = plan.blockIndexForSentence(next)) }
    }

    fun skipToPreviousSentence() {
        val previous = (_state.value.currentSentence - 1).coerceAtLeast(0)
        if (_state.value.playback == PlaybackState.SPEAKING) startSpeaking(previous)
        else _state.update {
            it.copy(currentSentence = previous, currentBlock = plan.blockIndexForSentence(previous))
        }
    }

    fun setSpeechRate(rate: Float) {
        _state.update { it.copy(speechRate = rate) }
        container.speech.setRate(rate)
        // Rate only takes effect on the next utterance, so restart the current one.
        if (_state.value.playback == PlaybackState.SPEAKING) {
            startSpeaking(_state.value.currentSentence.coerceAtLeast(0))
        }
    }

    // --- Lookup ----------------------------------------------------------

    /** Looks up a tapped word, using its sentence as the saved context. */
    fun lookupWord(blockText: String, offsetInBlock: Int) {
        val span = WordTokenizer.wordAt(blockText, offsetInBlock) ?: return
        lookup(span.text, contextOf(blockText, span.start))
    }

    fun lookupSelection(blockText: String, start: Int, end: Int) {
        val span = WordTokenizer.phraseIn(blockText, start, end) ?: return
        lookup(span.text, contextOf(blockText, span.start))
    }

    fun lookup(text: String, context: String) {
        scope.launch {
            _state.update { it.copy(isLookingUp = true) }
            val query = text.trim()
            val entry = if (query.contains(' ')) {
                container.dictionary.lookupPhrase(query)
            } else {
                container.dictionary.lookup(WordTokenizer.normalize(query))
            } ?: WordEntry.notFound(query)

            val alreadySaved = container.flashcards.contains(query.lowercase())
            _state.update {
                it.copy(
                    isLookingUp = false,
                    lookup = LookupResult(entry, context, alreadySaved),
                )
            }
        }
    }

    private fun contextOf(blockText: String, offset: Int): String {
        val sentences = com.bookreader.core.text.SentenceSegmenter.segment(blockText)
        return sentences.firstOrNull { offset in it }?.text ?: blockText.take(200)
    }

    fun dismissLookup() = _state.update { it.copy(lookup = null) }

    fun saveCurrentLookup(sense: Sense?) {
        val result = _state.value.lookup ?: return
        val book = _state.value.book
        scope.launch {
            container.flashcards.save(
                entry = result.entry,
                sense = sense,
                sourceBookId = book?.id,
                sourceTitle = book?.title,
                sourceContext = result.context,
            )
            _state.update { it.copy(lookup = result.copy(isSaved = true)) }
        }
    }

    fun clearError() = _state.update { it.copy(error = null) }

    fun close() {
        stopSpeaking()
        speechJob?.cancel()
        pdf?.close()
        pdf = null
        epub = null
    }
}
