package com.bookreader.core.dictionary

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Prompt construction and response parsing for context-aware lookup.
 *
 * A dictionary answers "what can this word mean"; a reader needs "what does it
 * mean *here*". Tapping "bank" in "she sat on the bank and watched the current"
 * should give the river sense and a Persian gloss that fits it — not a list of
 * every sense with the finance one first.
 *
 * The prompt and the parsing live here, apart from any HTTP, because they are
 * the parts that break and the parts worth testing. A model that returns prose
 * instead of JSON, or wraps it in a code fence, must degrade to "no answer" and
 * let the offline dictionary stand — never to a crash mid-read.
 */
object AiSense {

    private val lenient = Json { ignoreUnknownKeys = true; isLenient = true }

    /** Keeps prompts small; a sentence is enough context and costs little. */
    private const val MAX_CONTEXT_CHARS = 400

    /**
     * The instruction sent to the model. Asks for one sense, not a survey, and
     * for strict JSON so the reply can be parsed rather than interpreted.
     */
    fun buildPrompt(term: String, context: String): String {
        val trimmed = context.trim().take(MAX_CONTEXT_CHARS)
        return buildString {
            append("You are a bilingual English-Persian reading dictionary.\n")
            append("Give the meaning of the term AS USED in the sentence, not every possible meaning.\n\n")
            append("Term: ").append(term.trim()).append('\n')
            if (trimmed.isNotEmpty()) {
                append("Sentence: ").append(trimmed).append('\n')
            }
            append('\n')
            append("Reply with JSON only, no commentary, no code fence:\n")
            append("{\"headword\":\"dictionary form\",")
            append("\"pos\":\"noun|verb|adjective|adverb|phrase|other\",")
            append("\"ipa\":\"IPA or empty\",")
            append("\"english\":\"one concise definition for this usage\",")
            append("\"persian\":[\"معنی\"],")
            append("\"example\":\"a short example sentence or empty\"}")
        }
    }

    /**
     * A stable cache key. Context is folded to lowercase words so trivially
     * different renderings of the same sentence — spacing, punctuation, case —
     * reuse one cached answer instead of paying for another call.
     */
    fun cacheKey(term: String, context: String): String {
        val normalizedTerm = term.trim().lowercase()
        val normalizedContext = context.lowercase()
            .filter { it.isLetterOrDigit() || it.isWhitespace() }
            .split(' ', '\n', '\t')
            .filter { it.isNotBlank() }
            .joinToString(" ")
            .take(MAX_CONTEXT_CHARS)
        return "$normalizedTerm|${normalizedContext.hashCode()}"
    }

    /**
     * Parses a model reply into an entry, tolerating the ways models wrap JSON.
     * Returns null when the reply cannot be understood, so the caller falls
     * back rather than showing nonsense.
     */
    fun parse(reply: String, queried: String): WordEntry? {
        val json = extractJsonObject(reply) ?: return null
        val root = runCatching { lenient.parseToJsonElement(json).jsonObject }.getOrNull() ?: return null

        fun str(key: String): String? =
            root[key]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }

        val english = str("english")
        val persian = root["persian"]?.let { element ->
            runCatching {
                element.jsonArray.mapNotNull { it.jsonPrimitive.contentOrNull?.trim() }
                    .filter { it.isNotEmpty() }
            }.getOrNull()
        } ?: listOfNotNull(str("persian"))

        // An answer with neither side is not an answer.
        if (english == null && persian.isEmpty()) return null

        val examples = listOfNotNull(str("example"))
        return WordEntry(
            headword = str("headword") ?: queried.trim().lowercase(),
            queried = queried,
            pronunciation = str("ipa"),
            spelling = spellOut(queried),
            senses = listOf(
                Sense(
                    partOfSpeech = PartOfSpeech.parse(str("pos")),
                    english = english.orEmpty(),
                    persian = persian,
                    examples = examples,
                ),
            ),
            source = "ai",
        )
    }

    /**
     * Pulls the first balanced JSON object out of a reply.
     *
     * Models routinely wrap JSON in ```json fences or add a sentence before it.
     * Scanning for balanced braces handles every such case without needing the
     * model to behave, and returns null rather than guessing when it cannot.
     */
    internal fun extractJsonObject(reply: String): String? {
        val start = reply.indexOf('{')
        if (start < 0) return null
        var depth = 0
        var inString = false
        var escaped = false
        for (i in start until reply.length) {
            val c = reply[i]
            when {
                escaped -> escaped = false
                c == '\\' && inString -> escaped = true
                c == '"' -> inString = !inString
                inString -> Unit
                c == '{' -> depth++
                c == '}' -> {
                    depth--
                    if (depth == 0) return reply.substring(start, i + 1)
                }
            }
        }
        return null
    }
}
