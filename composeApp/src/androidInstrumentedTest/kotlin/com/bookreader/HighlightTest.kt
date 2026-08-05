package com.bookreader

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.bookreader.data.BookFormat
import com.bookreader.platform.PlatformContext
import com.bookreader.platform.SpeechEngine
import com.bookreader.platform.SpeechEvent
import com.bookreader.platform.SpeechVoice
import com.bookreader.reader.PlaybackState
import com.bookreader.reader.ReaderViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Verifies that the sentence highlight actually advances as speech progresses.
 *
 * A CI emulator has no text-to-speech voice data, so nothing here can prove
 * audio is audible on a physical device. What it can prove — and what the bugs
 * live in — is the part between the engine and the screen: that each utterance
 * maps to the right sentence, that the highlight moves on, that word ranges
 * land inside the sentence being spoken, and that playback ends cleanly.
 * [FakeSpeechEngine] supplies exactly the callbacks a real engine emits.
 */
@RunWith(AndroidJUnit4::class)
class HighlightTest {

    /** Records what it was asked to speak and lets the test drive the callbacks. */
    private class FakeSpeechEngine : SpeechEngine {
        private val _events = MutableSharedFlow<SpeechEvent>(replay = 0, extraBufferCapacity = 64)
        override val events: SharedFlow<SpeechEvent> = _events

        val spoken = mutableListOf<Pair<String, String>>() // utteranceId to text
        var prepareResult = true

        override suspend fun prepare(): Boolean = prepareResult
        override fun speak(text: String, utteranceId: String) {
            spoken.add(utteranceId to text)
        }
        override fun stop() = Unit
        override fun setRate(rate: Float) = Unit
        override fun setPitch(pitch: Float) = Unit
        override fun setLanguage(languageTag: String): Boolean = true
        override suspend fun voices(): List<SpeechVoice> = emptyList()
        override fun shutdown() = Unit

        suspend fun emit(event: SpeechEvent) {
            _events.emit(event)
        }

        /** The utterance the reader is currently waiting on. */
        fun lastUtteranceId(): String? = spoken.lastOrNull()?.first
    }

    private lateinit var engine: FakeSpeechEngine
    private lateinit var container: AppContainer
    private lateinit var viewModel: ReaderViewModel
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    @Before
    fun setUp() = runBlocking {
        engine = FakeSpeechEngine()
        container = AppContainer(
            PlatformContext(ApplicationProvider.getApplicationContext()),
        ) { engine }
        container.warmUp()

        val path = container.fileStorage.writeFile(
            "highlight-${System.currentTimeMillis()}.epub",
            TestEpub.bytes(),
        )
        val book = container.books.add("Highlight Test", null, BookFormat.EPUB, path, null, 1)

        viewModel = ReaderViewModel(container, scope)
        viewModel.open(book)
        awaitUntil("book loaded") { !viewModel.state.value.isLoading }
        Unit
    }

    @After
    fun tearDown() {
        viewModel.close()
    }

    private fun awaitUntil(what: String, predicate: () -> Boolean) = runBlocking {
        val ok = withTimeoutOrNull(10_000) {
            while (!predicate()) delay(20)
            true
        }
        assertTrue(ok == true, "timed out waiting for: $what")
    }

    @Test
    fun playbackSpeaksTheFirstSentenceAndHighlightsIt() {
        viewModel.playFromBlock(0)
        awaitUntil("first utterance") { engine.spoken.isNotEmpty() }

        val (id, text) = engine.spoken.first()
        assertEquals("sentence-0", id, "first utterance should be sentence 0")
        assertTrue(text.isNotBlank(), "should have been given real text to speak")

        awaitUntil("highlight on sentence 0") { viewModel.state.value.currentSentence == 0 }
        assertEquals(PlaybackState.SPEAKING, viewModel.state.value.playback)
    }

    @Test
    fun highlightAdvancesWhenAnUtteranceFinishes() = runBlocking {
        viewModel.playFromBlock(0)
        awaitUntil("first utterance") { engine.spoken.isNotEmpty() }
        awaitUntil("sentence 0 highlighted") { viewModel.state.value.currentSentence == 0 }

        // The engine reports the sentence finished; the reader must move on.
        engine.emit(SpeechEvent.Done("sentence-0"))
        awaitUntil("highlight moved to sentence 1") { viewModel.state.value.currentSentence == 1 }

        assertEquals("sentence-1", engine.lastUtteranceId(), "should be speaking the next sentence")
    }

    @Test
    fun theHighlightedBlockTracksTheSpokenSentence() {
        viewModel.playFromBlock(0)
        awaitUntil("playing") { viewModel.state.value.currentSentence == 0 }

        val state = viewModel.state.value
        assertTrue(state.currentBlock >= 0, "a block should be highlighted, was ${state.currentBlock}")
        val block = state.document.blocks.getOrNull(state.currentBlock)
        assertNotNull(block, "highlighted block index must exist in the document")

        // The sentence being spoken must actually come from that block.
        val sentence = viewModel.sentenceAt(state.currentSentence)
        assertNotNull(sentence)
        assertTrue(
            sentence.start >= block.charOffset,
            "sentence at ${sentence.start} should sit inside block at ${block.charOffset}",
        )
    }

    @Test
    fun wordRangesLandInsideTheSpokenSentence() = runBlocking {
        viewModel.playFromBlock(0)
        awaitUntil("playing") { viewModel.state.value.currentSentence == 0 }

        // Engines report progress relative to the utterance, not the chapter.
        engine.emit(SpeechEvent.Range("sentence-0", 0, 3))
        awaitUntil("word highlight set") { viewModel.state.value.wordHighlight != null }

        val range = viewModel.state.value.wordHighlight!!
        val sentence = viewModel.sentenceAt(0)!!
        assertTrue(
            range.first >= sentence.start && range.last < sentence.endExclusive,
            "word range $range escaped its sentence ${sentence.start}..${sentence.endExclusive}",
        )
    }

    @Test
    fun stoppingClearsTheHighlight() {
        viewModel.playFromBlock(0)
        awaitUntil("playing") { viewModel.state.value.currentSentence == 0 }

        viewModel.stopSpeaking()
        awaitUntil("stopped") { viewModel.state.value.playback == PlaybackState.IDLE }

        val state = viewModel.state.value
        assertEquals(-1, state.currentSentence, "highlight should be cleared on stop")
        assertEquals(null, state.wordHighlight)
    }

    @Test
    fun anUnavailableEngineReportsInsteadOfHangingSilently() {
        // The bug this guards: prepare() that never returns left playback stuck
        // in PREPARING with no audio, no highlight and no message.
        engine.prepareResult = false
        viewModel.playFromBlock(0)

        awaitUntil("failure surfaced") {
            val s = viewModel.state.value
            s.error != null || s.playback == PlaybackState.IDLE
        }
        val state = viewModel.state.value
        assertTrue(state.playback != PlaybackState.PREPARING, "must not stay stuck in PREPARING")
        assertNotNull(state.error, "an unavailable engine must tell the reader why")
    }
}
