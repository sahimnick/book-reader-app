package com.bookreader.data

import com.bookreader.core.dictionary.AiSense
import com.bookreader.core.dictionary.DictionaryProvider
import com.bookreader.core.dictionary.WordEntry
import com.bookreader.db.BookReaderDb
import com.bookreader.platform.currentTimeMillis
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** A plain HTTP POST returning the response body, or null on any failure. */
expect suspend fun httpPost(url: String, headers: Map<String, String>, body: String): String?

/**
 * Where the API key and model live.
 *
 * Stored in the app's own database rather than compiled in: a key shipped
 * inside an APK can be extracted by anyone who downloads it, so the only safe
 * arrangement for a personal app is that the reader supplies their own. The
 * database is app-private on both platforms; a Keychain/Keystore-backed store
 * would be stronger still and is the obvious next step if this ever ships.
 */
class AiSettings(private val db: BookReaderDb) {

    suspend fun apiKey(): String? = read(KEY_API)
    suspend fun setApiKey(value: String) = write(KEY_API, value.trim())

    suspend fun model(): String = read(KEY_MODEL) ?: DEFAULT_MODEL
    suspend fun setModel(value: String) = write(KEY_MODEL, value.trim())

    suspend fun isEnabled(): Boolean = !apiKey().isNullOrBlank()

    private suspend fun read(key: String): String? = withContext(Dispatchers.Default) {
        db.bookReaderQueries.getSetting(key).executeAsOneOrNull()?.takeIf { it.isNotBlank() }
    }

    private suspend fun write(key: String, value: String) = withContext(Dispatchers.Default) {
        db.bookReaderQueries.putSetting(key, value)
    }

    companion object {
        const val KEY_API = "ai.api_key"
        const val KEY_MODEL = "ai.model"
        const val DEFAULT_MODEL = "claude-sonnet-4-5"
    }
}

/**
 * Context-aware lookup: what the word means *in this sentence*.
 *
 * The bundled and online dictionaries answer "what can this word mean" and hand
 * back every sense, leaving the reader to guess which applies. Given the
 * sentence the word was tapped in, a model can pick the one that fits and give
 * a Persian gloss for that sense rather than for the word in the abstract.
 *
 * Placed last in the composite deliberately. It is the slowest and the only one
 * that costs money, so it answers only what the offline and free sources could
 * not. Every reply is cached by term plus context: readers meet the same words
 * constantly, so the hit rate climbs fast and the long tail is what actually
 * gets paid for.
 */
class AiDictionaryProvider(
    private val db: BookReaderDb,
    private val settings: AiSettings,
    private val endpoint: String = ANTHROPIC_ENDPOINT,
) : DictionaryProvider {

    override val name: String = "ai"
    override val isOffline: Boolean = false

    /** Set by the reader before each lookup so the model sees the sentence. */
    var context: String = ""

    override suspend fun lookup(word: String): WordEntry? = disambiguate(word)

    override suspend fun lookupPhrase(phrase: String): WordEntry? = disambiguate(phrase)

    private suspend fun disambiguate(term: String): WordEntry? {
        if (term.isBlank()) return null
        val key = settings.apiKey()?.takeIf { it.isNotBlank() } ?: return null

        val cacheKey = AiSense.cacheKey(term, context)
        cached(cacheKey)?.let { return AiSense.parse(it, term) }

        val reply = request(key, settings.model(), AiSense.buildPrompt(term, context))
            ?: return null
        val entry = AiSense.parse(reply, term) ?: return null

        store(cacheKey, reply)
        return entry
    }

    private suspend fun cached(key: String): String? = withContext(Dispatchers.Default) {
        db.bookReaderQueries.getAiCache(key).executeAsOneOrNull()
    }

    private suspend fun store(key: String, reply: String) = withContext(Dispatchers.Default) {
        db.bookReaderQueries.putAiCache(key, reply, currentTimeMillis())
    }

    /** Anthropic Messages API. Swap [endpoint] and this body for another provider. */
    private suspend fun request(apiKey: String, model: String, prompt: String): String? {
        val body = buildJsonBody(model, prompt)
        val response = httpPost(
            endpoint,
            mapOf(
                "content-type" to "application/json",
                "x-api-key" to apiKey,
                "anthropic-version" to "2023-06-01",
            ),
            body,
        ) ?: return null

        return runCatching {
            Json { ignoreUnknownKeys = true }
                .parseToJsonElement(response)
                .jsonObject["content"]
                ?.jsonArray
                ?.firstOrNull()
                ?.jsonObject
                ?.get("text")
                ?.jsonPrimitive
                ?.contentOrNull
        }.getOrNull()
    }

    private fun buildJsonBody(model: String, prompt: String): String =
        JsonObject(
            mapOf(
                "model" to JsonPrimitive(model),
                "max_tokens" to JsonPrimitive(400),
                "messages" to JsonArray(
                    listOf(
                        JsonObject(
                            mapOf(
                                "role" to JsonPrimitive("user"),
                                "content" to JsonPrimitive(prompt),
                            ),
                        ),
                    ),
                ),
            ),
        ).toString()

    companion object {
        const val ANTHROPIC_ENDPOINT = "https://api.anthropic.com/v1/messages"
    }
}
