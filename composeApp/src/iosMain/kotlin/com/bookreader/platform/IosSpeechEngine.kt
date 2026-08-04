package com.bookreader.platform

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import platform.AVFAudio.AVAudioSession
import platform.AVFAudio.AVAudioSessionCategoryPlayback
import platform.AVFAudio.AVSpeechBoundaryImmediate
import platform.AVFAudio.AVSpeechSynthesisVoice
import platform.AVFAudio.AVSpeechSynthesizer
import platform.AVFAudio.AVSpeechSynthesizerDelegateProtocol
import platform.AVFAudio.AVSpeechUtterance
import platform.AVFAudio.AVSpeechUtteranceDefaultSpeechRate
import platform.Foundation.NSRange
import platform.darwin.NSObject

actual fun createSpeechEngine(context: PlatformContext): SpeechEngine = IosSpeechEngine()

/**
 * iOS speech via `AVSpeechSynthesizer`.
 *
 * Utterances carry no identifier of their own, so the id given to [speak] is
 * tracked alongside the utterance object and resolved in the delegate callbacks
 * — that is what lets a `Done` event be matched to the sentence that finished.
 */
@OptIn(ExperimentalForeignApi::class)
private class IosSpeechEngine : SpeechEngine {

    private val _events = MutableSharedFlow<SpeechEvent>(
        replay = 0,
        extraBufferCapacity = 64,
    )
    override val events: SharedFlow<SpeechEvent> = _events

    private val synthesizer = AVSpeechSynthesizer()

    private var rate: Float = AVSpeechUtteranceDefaultSpeechRate
    private var pitch: Float = 1.0f
    private var languageTag: String = "en-US"

    /** Maps the live utterance back to the id the caller supplied. */
    private var currentUtterance: AVSpeechUtterance? = null
    private var currentId: String? = null

    private val delegate = object : NSObject(), AVSpeechSynthesizerDelegateProtocol {

        override fun speechSynthesizer(
            synthesizer: AVSpeechSynthesizer,
            didStartSpeechUtterance: AVSpeechUtterance,
        ) {
            currentId?.let { _events.tryEmit(SpeechEvent.Started(it)) }
        }

        override fun speechSynthesizer(
            synthesizer: AVSpeechSynthesizer,
            didFinishSpeechUtterance: AVSpeechUtterance,
        ) {
            val id = currentId
            currentUtterance = null
            currentId = null
            id?.let { _events.tryEmit(SpeechEvent.Done(it)) }
        }

        override fun speechSynthesizer(
            synthesizer: AVSpeechSynthesizer,
            didCancelSpeechUtterance: AVSpeechUtterance,
        ) {
            currentUtterance = null
            currentId = null
        }

        override fun speechSynthesizer(
            synthesizer: AVSpeechSynthesizer,
            willSpeakRangeOfSpeechString: kotlinx.cinterop.CValue<NSRange>,
            utterance: AVSpeechUtterance,
        ) {
            val id = currentId ?: return
            kotlinx.cinterop.useContents<NSRange, Unit>(willSpeakRangeOfSpeechString) {
                val start = location.toInt()
                val end = start + this.length.toInt()
                _events.tryEmit(SpeechEvent.Range(id, start, end))
            }
        }
    }

    init {
        synthesizer.delegate = delegate
        // Playback category keeps speech audible when the ring switch is silent,
        // which is what a reader expects from a read-aloud feature.
        runCatching {
            AVAudioSession.sharedInstance().setCategory(AVAudioSessionCategoryPlayback, null)
            AVAudioSession.sharedInstance().setActive(true, null)
        }
    }

    override suspend fun prepare(): Boolean {
        _events.tryEmit(SpeechEvent.Ready)
        return true
    }

    override fun speak(text: String, utteranceId: String) {
        if (text.isBlank()) {
            _events.tryEmit(SpeechEvent.Done(utteranceId))
            return
        }
        if (synthesizer.speaking) synthesizer.stopSpeakingAtBoundary(AVSpeechBoundaryImmediate)

        val utterance = AVSpeechUtterance.speechUtteranceWithString(text).apply {
            setRate(this@IosSpeechEngine.rate)
            setPitchMultiplier(this@IosSpeechEngine.pitch)
            AVSpeechSynthesisVoice.voiceWithLanguage(languageTag)?.let { setVoice(it) }
        }
        currentUtterance = utterance
        currentId = utteranceId
        synthesizer.speakUtterance(utterance)
    }

    override fun stop() {
        if (synthesizer.speaking) synthesizer.stopSpeakingAtBoundary(AVSpeechBoundaryImmediate)
        currentUtterance = null
        currentId = null
    }

    override fun setRate(rate: Float) {
        // Android treats 1.0 as normal; AVSpeechUtterance uses its own scale.
        this.rate = (AVSpeechUtteranceDefaultSpeechRate * rate).coerceIn(0.0f, 1.0f)
    }

    override fun setPitch(pitch: Float) {
        this.pitch = pitch.coerceIn(0.5f, 2.0f)
    }

    override fun setLanguage(languageTag: String): Boolean {
        val voice = AVSpeechSynthesisVoice.voiceWithLanguage(languageTag)
        return if (voice != null) {
            this.languageTag = languageTag
            true
        } else {
            false
        }
    }

    override suspend fun voices(): List<SpeechVoice> =
        AVSpeechSynthesisVoice.speechVoices()
            .filterIsInstance<AVSpeechSynthesisVoice>()
            .map {
                SpeechVoice(
                    id = it.identifier,
                    languageTag = it.language,
                    displayName = "${it.language} — ${it.name}",
                )
            }
            .sortedBy { it.languageTag }

    override fun shutdown() {
        stop()
        synthesizer.delegate = null
    }
}
