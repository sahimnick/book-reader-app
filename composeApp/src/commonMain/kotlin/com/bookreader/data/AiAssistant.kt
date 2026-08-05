package com.bookreader.data

import com.bookreader.core.ai.AiTasks
import com.bookreader.core.ai.GeneratedCard
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

/** An answer about a passage: English, and Persian when the model supplied it. */
data class PassageAnswer(val english: String, val persian: String?)

/**
 * The reading assistant: study material, questions, recaps and translation.
 *
 * Everything here is optional and inert without an API key, so the app behaves
 * exactly as before for a reader who never sets one. Replies are cached by
 * task and input, because all four features are re-requested constantly — the
 * same chapter is resumed, the same card reopened — and a cached answer costs
 * nothing and returns instantly.
 *
 * Every method returns null on any failure. A model that is unreachable, slow,
 * or unparseable must leave the reader's page alone rather than surface an
 * error in the middle of a book.
 */
class AiAssistant(
    private val db: BookReaderDb,
    private val settings: AiSettings,
    private val endpoint: String = AiDictionaryProvider.ANTHROPIC_ENDPOINT,
) {

    suspend fun isEnabled(): Boolean = settings.isEnabled()

    /**
     * Extra study material for a saved word: a cloze from the sentence it was
     * met in, a simpler second example, and a mnemonic bridging the two
     * languages. SM-2 schedules cards well but cannot write them.
     */
    suspend fun generateCard(word: String, context: String, persian: String): GeneratedCard? {
        val reply = ask("card:$word:${context.hashCode()}", AiTasks.cardPrompt(word, context, persian))
            ?: return null
        return AiTasks.parseCard(reply)
    }

    /** Answers a question about the passage on screen, in English and Persian. */
    suspend fun askAboutPassage(passage: String, question: String): PassageAnswer? {
        val reply = ask(
            "passage:${passage.hashCode()}:${question.trim().lowercase().hashCode()}",
            AiTasks.passagePrompt(passage, question),
        ) ?: return null
        val (english, persian) = AiTasks.splitBilingual(reply)
        return if (english.isBlank()) null else PassageAnswer(english, persian)
    }

    /** "What happened so far", for a reader returning after a break. */
    suspend fun recapChapter(chapterText: String): String? {
        if (chapterText.isBlank()) return null
        val reply = ask("recap:${chapterText.hashCode()}", AiTasks.recapPrompt(chapterText))
            ?: return null
        return AiTasks.cleanText(reply)
    }

    /** Full Persian for a paragraph, where the dictionary only gives words. */
    suspend fun translate(text: String): String? {
        if (text.isBlank()) return null
        val reply = ask("tr:${text.hashCode()}", AiTasks.translationPrompt(text)) ?: return null
        return AiTasks.cleanText(reply)
    }

    // --- Shared request path ------------------------------------------------

    private suspend fun ask(cacheKey: String, prompt: String): String? {
        val apiKey = settings.apiKey()?.takeIf { it.isNotBlank() } ?: return null
        cached(cacheKey)?.let { return it }

        val response = httpPost(
            endpoint,
            mapOf(
                "content-type" to "application/json",
                "x-api-key" to apiKey,
                "anthropic-version" to "2023-06-01",
            ),
            body(settings.model(), prompt),
        ) ?: return null

        val text = runCatching {
            Json { ignoreUnknownKeys = true }
                .parseToJsonElement(response)
                .jsonObject["content"]
                ?.jsonArray
                ?.firstOrNull()
                ?.jsonObject
                ?.get("text")
                ?.jsonPrimitive
                ?.contentOrNull
        }.getOrNull() ?: return null

        store(cacheKey, text)
        return text
    }

    private fun body(model: String, prompt: String): String =
        JsonObject(
            mapOf(
                "model" to JsonPrimitive(model),
                "max_tokens" to JsonPrimitive(600),
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

    private suspend fun cached(key: String): String? = withContext(Dispatchers.Default) {
        db.bookReaderQueries.getAiCache(key).executeAsOneOrNull()
    }

    private suspend fun store(key: String, reply: String) = withContext(Dispatchers.Default) {
        db.bookReaderQueries.putAiCache(key, reply, currentTimeMillis())
    }
}
