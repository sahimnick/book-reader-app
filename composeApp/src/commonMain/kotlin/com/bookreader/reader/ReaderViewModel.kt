package com.bookreader.reader

import com.bookreader.AppContainer
import com.bookreader.core.ai.AiTasks
import com.bookreader.core.ai.GeneratedCard
import com.bookreader.core.dictionary.Sense
import com.bookreader.core.dictionary.WordEntry
import com.bookreader.core.epub.EpubBook
import com.bookreader.core.model.ContentDocument
import com.bookreader.core.text.PhraseDetector
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
import kotlinx.coroutines.withTimeoutOrNull

/** What the reader is currently doing. */
enum class PlaybackState { IDLE, PREPARING, SPEAKING, PAUSED }

data class LookupResult(
    val entry: WordEntry,
    /** The sentence the word was found in, saved with the flashcard. */
    val context: String,
    val isSaved: Boolean = false,
    /** Generated study material, once the reader has asked for it. */
    val generated: GeneratedCard? = null,
    val isGenerating: Boolean = false,
    /** The whole sentence in Persian, not just the tapped word. */
    val contextPersian: String? = null,
    val isTranslating: Boolean = false,
)

/** Which assistant answer the panel is showing. */
enum class AssistantTask { RECAP, QUESTION, TRANSLATION }

/**
 * One answer from the reading assistant.
 *
 * All three tasks produce the same shape — some source text, an answer, and
 * possibly a Persian version of it — so they share one panel rather than three
 * near-identical sheets.
 */
data class AssistantPanel(
    val task: AssistantTask,
    val isLoading: Boolean = false,
    /** The passage the answer is about, shown so the reader can check it. */
    val source: String = "",
    val question: String = "",
    val english: String? = null,
    val persian: String? = null,
    val error: String? = null,
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

    // Reading assistant
    /** True once an API key is set; every assistant action is hidden without one. */
    val assistantAvailable: Boolean = false,
    val assistant: AssistantPanel? = null,
    /** The last paragraph the reader touched — what "this paragraph" means. */
    val focusBlock: Int = -1,
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

        /**
         * How far back a recap reaches. Bounded because each unit costs a parse
         * — and on a scanned PDF, a page of OCR — while [AiTasks.recapWindow]
         * would discard most of it anyway.
         */
        const val RECAP_UNITS = 6

        /**
         * Every assistant call returns null on any failure — no key, no
         * network, an unparseable reply — so one message covers all of them
         * rather than guessing which it was.
         */
        const val UNAVAILABLE = "The assistant could not answer just now. " +
            "Check your key in Settings and your connection."
    }

    init {
        observeSpeech()
    }

    // --- Loading ---------------------------------------------------------

    fun open(book: LibraryBook) {
        scope.launch {
            _state.update { it.copy(isLoading = true, error = null, book = book) }
            container.books.touch(book.id)
            _state.update { it.copy(assistantAvailable = container.assistant.isEnabled()) }
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
                            focusBlock = -1,
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
                            focusBlock = -1,
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
        _state.update { it.copy(focusBlock = blockIndex) }
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
                // A platform engine that never reports init must not strand
                // playback in PREPARING with no feedback.
                speechReady = withTimeoutOrNull(5_000) { container.speech.prepare() } ?: false
                if (!speechReady) {
                    _state.update {
                        it.copy(
                            playback = PlaybackState.IDLE,
                            error = "Text-to-speech is unavailable. Install or enable a " +
                                "speech engine in Android settings, then try again.",
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

    /**
     * Looks up a tapped word, trying the phrase it belongs to first.
     *
     * Tapping "up" in "he gave up" must answer *give up*, not *up*.
     * [PhraseDetector] proposes candidates longest-first; the first one the
     * dictionary recognises wins, and the bare word is always last so there is
     * always an answer.
     */
    fun lookupWord(blockIndex: Int, blockText: String, offsetInBlock: Int) {
        val candidates = PhraseDetector.candidatesAt(blockText, offsetInBlock)
        // Even a tap on empty space marks the paragraph, so "translate this
        // paragraph" has something to work on after a miss.
        _state.update { it.copy(focusBlock = blockIndex) }
        if (candidates.isEmpty()) return
        val anchor = candidates.last()

        scope.launch {
            _state.update { it.copy(isLookingUp = true) }
            val context = contextOf(blockText, anchor.start)
            // The sentence is what makes the AI lookup context-aware; without
            // it the model is answering the same question as a plain dictionary.
            container.aiDictionary.context = context

            var hit: WordEntry? = null
            var matched = anchor
            for (candidate in candidates) {
                val key = PhraseDetector.normalizePhrase(candidate.text)
                val entry = if (key.contains(' ')) {
                    container.dictionary.lookupPhrase(key)
                } else {
                    container.dictionary.lookup(key)
                }
                if (entry != null && !entry.isEmpty) {
                    hit = entry.copy(queried = candidate.text)
                    matched = candidate
                    break
                }
            }

            val entry = hit ?: WordEntry.notFound(anchor.text)
            val alreadySaved = container.flashcards.contains(matched.text.lowercase())
            _state.update {
                it.copy(isLookingUp = false, lookup = LookupResult(entry, context, alreadySaved))
            }
        }
    }

    /**
     * Looks up a word or phrase tapped on a PDF page.
     *
     * Word boxes carry their offset into the page's extracted text, so the
     * sentence the word sits in can be recovered — the same context an EPUB tap
     * gets. Without it a PDF look-up would be saved against the top of the page
     * rather than the line it came from, and the AI lookup would have nothing
     * to disambiguate with.
     */
    fun lookupPdfWord(text: String, charOffset: Int) {
        val document = _state.value.document
        val block = document.blocks.indexOfLast { it.charOffset <= charOffset }
        if (block >= 0) _state.update { it.copy(focusBlock = block) }
        lookup(text, contextOf(document.flattenedText, charOffset))
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
            // Material generated before saving would otherwise be thrown away
            // the moment the reader tapped "add".
            result.generated?.let {
                container.flashcards.attachGenerated(result.entry.queried, it)
            }
            updateLookup { it.copy(isSaved = true) }
        }
    }

    // --- Reading assistant -------------------------------------------------

    /**
     * Writes study material for the word in the lookup sheet.
     *
     * A dictionary gives a definition; what a card needs is the sentence the
     * word was met in with the word taken out, a second example, and something
     * to hang the Persian on. SM-2 schedules cards well but cannot write them.
     */
    fun generateStudyMaterial() {
        val result = _state.value.lookup ?: return
        if (result.isGenerating || result.generated != null) return
        val word = result.entry.queried
        val persian = result.entry.senses.firstOrNull()?.persian?.joinToString("، ").orEmpty()

        scope.launch {
            updateLookup { it.copy(isGenerating = true) }
            val generated = container.assistant.generateCard(word, result.context, persian)
            updateLookup { it.copy(isGenerating = false, generated = generated) }
            // If the word is already a card, the material belongs on it now
            // rather than only in this sheet.
            if (generated != null && _state.value.lookup?.isSaved == true) {
                container.flashcards.attachGenerated(word, generated)
            }
        }
    }

    /** The whole sentence in Persian, for when the words alone do not explain it. */
    fun translateContext() {
        val result = _state.value.lookup ?: return
        if (result.isTranslating || result.contextPersian != null) return
        if (result.context.isBlank()) return

        scope.launch {
            updateLookup { it.copy(isTranslating = true) }
            val persian = container.assistant.translate(result.context)
            updateLookup { it.copy(isTranslating = false, contextPersian = persian) }
        }
    }

    private fun updateLookup(transform: (LookupResult) -> LookupResult) =
        _state.update { state -> state.lookup?.let { state.copy(lookup = transform(it)) } ?: state }

    /** "What happened so far", covering the chapters just read. */
    fun requestRecap() {
        scope.launch {
            _state.update {
                it.copy(assistant = AssistantPanel(AssistantTask.RECAP, isLoading = true))
            }
            val text = recentText()
            val recap = container.assistant.recapChapter(text)
            _state.update {
                it.copy(
                    assistant = AssistantPanel(
                        task = AssistantTask.RECAP,
                        english = recap,
                        error = if (recap == null) UNAVAILABLE else null,
                    ),
                )
            }
        }
    }

    /** Opens the question box; the answer arrives through [askQuestion]. */
    fun startQuestion() = _state.update {
        it.copy(assistant = AssistantPanel(AssistantTask.QUESTION, source = passageText()))
    }

    fun askQuestion(question: String) {
        if (question.isBlank()) return
        val passage = _state.value.assistant?.source?.takeIf { it.isNotBlank() } ?: passageText()

        scope.launch {
            _state.update {
                it.copy(
                    assistant = AssistantPanel(
                        task = AssistantTask.QUESTION,
                        isLoading = true,
                        source = passage,
                        question = question,
                    ),
                )
            }
            val answer = container.assistant.askAboutPassage(passage, question)
            _state.update {
                it.copy(
                    assistant = AssistantPanel(
                        task = AssistantTask.QUESTION,
                        source = passage,
                        question = question,
                        english = answer?.english,
                        persian = answer?.persian,
                        error = if (answer == null) UNAVAILABLE else null,
                    ),
                )
            }
        }
    }

    /** Full Persian for the paragraph the reader last touched. */
    fun translateParagraph() {
        val paragraph = focusedParagraph()
        if (paragraph.isBlank()) return

        scope.launch {
            _state.update {
                it.copy(
                    assistant = AssistantPanel(
                        task = AssistantTask.TRANSLATION,
                        isLoading = true,
                        source = paragraph,
                    ),
                )
            }
            val persian = container.assistant.translate(paragraph)
            _state.update {
                it.copy(
                    assistant = AssistantPanel(
                        task = AssistantTask.TRANSLATION,
                        source = paragraph,
                        persian = persian,
                        error = if (persian == null) UNAVAILABLE else null,
                    ),
                )
            }
        }
    }

    fun dismissAssistant() = _state.update { it.copy(assistant = null) }

    /**
     * The paragraph "this paragraph" refers to: the one last tapped or read
     * aloud, falling back to the top of the page when the reader has touched
     * nothing yet.
     */
    private fun focusedParagraph(): String {
        val current = _state.value
        val blocks = current.document.blocks
        if (blocks.isEmpty()) return ""
        val index = listOf(current.focusBlock, current.currentBlock)
            .firstOrNull { it in blocks.indices }
            ?: 0
        return blocks[index].text
    }

    /** What a question is asked about: the current chapter or page, bounded. */
    private fun passageText(): String =
        _state.value.document.flattenedText.take(AiTasks.MAX_PASSAGE_CHARS)

    /**
     * The text of the last few units, oldest first.
     *
     * Recapping only the open chapter answers "what am I reading", not "what
     * happened so far", so the units before it are gathered too and
     * [AiTasks.recapWindow] trims the result to what a recap can use.
     */
    private suspend fun recentText(): String {
        val from = _state.value.unitIndex
        val epubBook = epub
        val pdfDoc = pdf
        val texts = ArrayDeque<String>()
        var budget = AiTasks.MAX_RECAP_CHARS

        var index = from
        while (index >= 0 && budget > 0 && from - index < RECAP_UNITS) {
            val text = runCatching {
                when {
                    epubBook != null -> epubBook.chapter(index).flattenedText
                    pdfDoc != null -> pdfDoc.pageText(index).document.flattenedText
                    else -> ""
                }
            }.getOrDefault("")
            texts.addFirst(text)
            budget -= text.length
            index--
        }
        return AiTasks.recapWindow(texts.toList())
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
