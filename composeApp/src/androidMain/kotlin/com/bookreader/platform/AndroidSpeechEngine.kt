package com.bookreader.platform

import android.os.Build
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.Locale
import kotlin.coroutines.resume

actual fun createSpeechEngine(context: PlatformContext): SpeechEngine =
    AndroidSpeechEngine(context)

/**
 * Android [TextToSpeech] wrapped as a [SpeechEngine].
 *
 * `onRangeStart` is only delivered from API 26 and only by engines that opt in,
 * so it is treated as an enhancement for word-level highlighting. Sentence
 * highlighting relies solely on utterance start/done, which every engine
 * reports.
 */
private class AndroidSpeechEngine(context: PlatformContext) : SpeechEngine {

    private val _events = MutableSharedFlow<SpeechEvent>(
        replay = 0,
        extraBufferCapacity = 64,
    )
    override val events: SharedFlow<SpeechEvent> = _events

    @Volatile private var initialised = false
    private var pendingInit: ((Boolean) -> Unit)? = null

    private val tts = TextToSpeech(context.androidContext) { status ->
        initialised = status == TextToSpeech.SUCCESS
        if (initialised) {
            _events.tryEmit(SpeechEvent.Ready)
        } else {
            _events.tryEmit(SpeechEvent.Failed(null, "Text-to-speech engine unavailable"))
        }
        pendingInit?.invoke(initialised)
        pendingInit = null
    }

    init {
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                utteranceId?.let { _events.tryEmit(SpeechEvent.Started(it)) }
            }

            override fun onDone(utteranceId: String?) {
                utteranceId?.let { _events.tryEmit(SpeechEvent.Done(it)) }
            }

            @Deprecated("Superseded by onError(String, int)", ReplaceWith(""))
            override fun onError(utteranceId: String?) {
                _events.tryEmit(SpeechEvent.Failed(utteranceId, "Speech failed"))
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                _events.tryEmit(SpeechEvent.Failed(utteranceId, "Speech failed (code $errorCode)"))
            }

            override fun onRangeStart(utteranceId: String?, start: Int, end: Int, frame: Int) {
                utteranceId?.let { _events.tryEmit(SpeechEvent.Range(it, start, end)) }
            }
        })
    }

    override suspend fun prepare(): Boolean {
        if (initialised) return true
        return suspendCancellableCoroutine { cont ->
            if (initialised) {
                cont.resume(true)
            } else {
                pendingInit = { ok -> if (cont.isActive) cont.resume(ok) }
            }
        }
    }

    override fun speak(text: String, utteranceId: String) {
        if (!initialised) return
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
    }

    override fun stop() {
        if (initialised) tts.stop()
    }

    override fun setRate(rate: Float) {
        tts.setSpeechRate(rate.coerceIn(0.25f, 3.0f))
    }

    override fun setPitch(pitch: Float) {
        tts.setPitch(pitch.coerceIn(0.5f, 2.0f))
    }

    override fun setLanguage(languageTag: String): Boolean {
        val locale = Locale.forLanguageTag(languageTag)
        val result = tts.setLanguage(locale)
        return result != TextToSpeech.LANG_MISSING_DATA && result != TextToSpeech.LANG_NOT_SUPPORTED
    }

    override suspend fun voices(): List<SpeechVoice> {
        if (!initialised) return emptyList()
        val voices: Set<Voice> = runCatching { tts.voices }.getOrNull() ?: return emptyList()
        return voices
            .filterNot { it.isNetworkConnectionRequired && it.features.contains("notInstalled") }
            .map {
                SpeechVoice(
                    id = it.name,
                    languageTag = it.locale.toLanguageTag(),
                    displayName = "${it.locale.displayName} — ${it.name}",
                )
            }
            .sortedBy { it.languageTag }
    }

    override fun shutdown() {
        runCatching {
            tts.stop()
            tts.shutdown()
        }
        initialised = false
    }

    private companion object {
        // Referenced so the API-level guard is explicit for readers of this file.
        val SUPPORTS_RANGE_EVENTS = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
    }
}
