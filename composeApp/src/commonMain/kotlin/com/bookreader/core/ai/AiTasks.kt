package com.bookreader.core.ai

import com.bookreader.core.dictionary.AiSense
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Extra study material generated for a saved word. */
data class GeneratedCard(
    /** The source sentence with the word blanked out. */
    val cloze: String,
    /** A second example, simpler than the one in the book. */
    val example: String,
    /** A hook linking the English and Persian forms. */
    val mnemonic: String,
) {
    val isEmpty: Boolean get() = cloze.isBlank() && example.isBlank() && mnemonic.isBlank()
}

/**
 * Prompts and parsers for the reading-assistant features.
 *
 * They all share one shape — build a prompt, send it, make sense of the reply —
 * so they share one place. The prompts and the parsing live in the tested core
 * rather than beside the HTTP call because that is the part that breaks: a
 * model that answers in prose, wraps JSON in a fence, or returns nothing at all
 * must degrade to "no answer" and leave the reader's page alone.
 */
object AiTasks {

    private val lenient = Json { ignoreUnknownKeys = true; isLenient = true }

    /** Chapters run long; a recap only needs the shape of what happened. */
    const val MAX_RECAP_CHARS = 6000

    /** Enough passage for a question to be answerable without paying for a book. */
    const val MAX_PASSAGE_CHARS = 2000

    // --- Flashcard generation --------------------------------------------

    fun cardPrompt(word: String, context: String, persian: String): String = buildString {
        append("You help a Persian speaker study English vocabulary.\n")
        append("Word: ").append(word.trim()).append('\n')
        if (persian.isNotBlank()) append("Persian meaning: ").append(persian.trim()).append('\n')
        if (context.isNotBlank()) {
            append("Sentence from the book: ").append(context.trim().take(300)).append('\n')
        }
        append("\nReply with JSON only, no commentary, no code fence:\n")
        append("{\"cloze\":\"the book sentence with the word replaced by ___\",")
        append("\"example\":\"a simpler new example sentence using the word\",")
        append("\"mnemonic\":\"one short memory hook linking the English and Persian\"}")
    }

    fun parseCard(reply: String): GeneratedCard? {
        val json = AiSense.extractJsonObject(reply) ?: return null
        val root = runCatching { lenient.parseToJsonElement(json).jsonObject }.getOrNull() ?: return null
        fun str(key: String) = root[key]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        val card = GeneratedCard(
            cloze = str("cloze"),
            example = str("example"),
            mnemonic = str("mnemonic"),
        )
        return card.takeUnless { it.isEmpty }
    }

    // --- Ask about this passage ------------------------------------------

    fun passagePrompt(passage: String, question: String): String = buildString {
        append("A Persian-speaking reader is reading English and has a question ")
        append("about this passage.\n\n")
        append("Passage:\n").append(passage.trim().take(MAX_PASSAGE_CHARS)).append("\n\n")
        append("Question: ").append(question.trim()).append('\n')
        append("\nAnswer in two or three sentences. Answer in English, then give ")
        append("the same answer in Persian on a new line beginning with 'FA: '. ")
        append("Plain text only.")
    }

    // --- Chapter recap ----------------------------------------------------

    fun recapPrompt(chapterText: String): String = buildString {
        append("Summarise what happens in this chapter for a reader returning ")
        append("after a break. Four sentences at most. Do not reveal anything ")
        append("beyond the text given. Plain text only.\n\n")
        append(chapterText.trim().take(MAX_RECAP_CHARS))
    }

    // --- Sentence and paragraph translation --------------------------------

    fun translationPrompt(text: String): String = buildString {
        append("Translate the following English into natural Persian. ")
        append("Reply with the translation only — no notes, no transliteration, ")
        append("no quotation marks.\n\n")
        append(text.trim().take(MAX_PASSAGE_CHARS))
    }

    // --- Shared plain-text cleanup ----------------------------------------

    /**
     * Tidies a free-text reply.
     *
     * Models pad answers with lead-ins ("Sure! Here's a summary:"), wrap them in
     * fences, and quote them. None of that belongs on the page, and stripping it
     * here means every caller gets the same treatment.
     */
    fun cleanText(reply: String): String? {
        var text = reply.trim()
        if (text.isEmpty()) return null

        // Strip a surrounding code fence, keeping the content.
        if (text.startsWith("```")) {
            text = text.removePrefix("```")
                .substringAfter('\n', "")
                .substringBeforeLast("```")
                .trim()
        }

        // Drop a single lead-in line ending in a colon.
        val lines = text.lines()
        if (lines.size > 1 && lines.first().trimEnd().endsWith(":") && lines.first().length < 60) {
            text = lines.drop(1).joinToString("\n").trim()
        }

        text = text.trim().trim('"', '“', '”').trim()
        return text.takeIf { it.isNotEmpty() }
    }

    /**
     * Splits a bilingual answer into its English and Persian halves.
     *
     * The passage prompt asks for Persian on a line prefixed `FA:`; if the model
     * ignores that, the whole reply is returned as English rather than losing it.
     */
    fun splitBilingual(reply: String): Pair<String, String?> {
        val cleaned = cleanText(reply) ?: return "" to null
        val marker = cleaned.indexOf("FA:")
        if (marker < 0) return cleaned to null
        val english = cleaned.substring(0, marker).trim()
        val persian = cleaned.substring(marker + 3).trim().takeIf { it.isNotEmpty() }
        return english to persian
    }
}
