package com.bookreader.data

import com.bookreader.core.dictionary.DictionaryProvider
import com.bookreader.core.dictionary.PartOfSpeech
import com.bookreader.core.dictionary.Sense
import com.bookreader.core.dictionary.WordEntry
import com.bookreader.core.dictionary.spellOut
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * A plain HTTP GET, supplied by the platform.
 *
 * Returns null on any failure — no connectivity, a non-200, a timeout. Lookups
 * fall through to the next provider rather than surfacing a network error to
 * someone who is just trying to read a book.
 */
expect suspend fun httpGet(url: String): String?

private val lenientJson = Json { ignoreUnknownKeys = true; isLenient = true }

/** Percent-encodes a lookup term for use in a query string. */
private fun urlEncode(value: String): String = buildString {
    for (byte in value.encodeToByteArray()) {
        val b = byte.toInt() and 0xFF
        val c = b.toChar()
        if (c.isLetterOrDigit() && b < 0x80 || c == '-' || c == '_' || c == '.' || c == '~') {
            append(c)
        } else {
            append('%')
            append("0123456789ABCDEF"[b shr 4])
            append("0123456789ABCDEF"[b and 0x0F])
        }
    }
}

/**
 * English definitions, IPA and examples from the Free Dictionary API.
 *
 * This is the open, key-free source of Longman-style entries: part of speech,
 * numbered senses, example sentences and phonetics. Longman itself has no
 * public API — its data is licensed — so this stands in for it. If you have a
 * Longman/Oxford licence, implement [DictionaryProvider] against their endpoint
 * and add it to the composite; nothing else needs to change.
 */
class FreeDictionaryProvider : DictionaryProvider {

    override val name: String = "dictionaryapi.dev"
    override val isOffline: Boolean = false

    override suspend fun lookup(word: String): WordEntry? {
        val term = word.trim().lowercase()
        if (term.isEmpty()) return null

        val body = httpGet("https://api.dictionaryapi.dev/api/v2/entries/en/${urlEncode(term)}")
            ?: return null

        val root = runCatching { lenientJson.parseToJsonElement(body) }.getOrNull() ?: return null
        val entries = root as? JsonArray ?: return null
        if (entries.isEmpty()) return null

        val first = entries.first().jsonObject
        val phonetic = first["phonetic"]?.jsonPrimitive?.contentOrNull
            ?: first["phonetics"]?.jsonArray
                ?.firstNotNullOfOrNull { it.jsonObject["text"]?.jsonPrimitive?.contentOrNull }

        val senses = ArrayList<Sense>()
        for (entry in entries) {
            val meanings = entry.jsonObject["meanings"]?.jsonArray ?: continue
            for (meaning in meanings) {
                val obj = meaning.jsonObject
                val pos = PartOfSpeech.parse(obj["partOfSpeech"]?.jsonPrimitive?.contentOrNull)
                val definitions = obj["definitions"]?.jsonArray ?: continue
                val examples = ArrayList<String>()
                var english = ""
                for (d in definitions.take(3)) {
                    val dObj = d.jsonObject
                    val text = dObj["definition"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    if (english.isEmpty()) english = text
                    dObj["example"]?.jsonPrimitive?.contentOrNull?.let(examples::add)
                }
                val synonyms = obj["synonyms"]?.jsonArray
                    ?.mapNotNull { it.jsonPrimitive.contentOrNull }
                    ?.take(6)
                    .orEmpty()
                if (english.isNotEmpty()) {
                    senses.add(Sense(pos, english, persian = emptyList(), examples = examples, synonyms = synonyms))
                }
            }
        }
        if (senses.isEmpty()) return null

        return WordEntry(
            headword = first["word"]?.jsonPrimitive?.contentOrNull ?: term,
            queried = word,
            pronunciation = phonetic,
            spelling = spellOut(word),
            senses = senses,
            source = name,
        )
    }

    override suspend fun lookupPhrase(phrase: String): WordEntry? =
        if (phrase.trim().contains(' ')) null else lookup(phrase)
}

/**
 * Persian translation via Google Translate's public endpoint.
 *
 * Uses the key-free `translate_a/single` endpoint, which returns a nested array
 * rather than an object. It handles phrases as well as single words, which is
 * what makes select-a-phrase lookup useful — a bilingual dictionary only has
 * headwords.
 *
 * This is an undocumented endpoint and can rate-limit or change. It is placed
 * after the offline dictionary in the composite so a failure only costs the
 * Persian gloss for words the bundled data does not already cover.
 */
class GoogleTranslateProvider(
    private val sourceLanguage: String = "en",
    private val targetLanguage: String = "fa",
) : DictionaryProvider {

    override val name: String = "translate.google"
    override val isOffline: Boolean = false

    private suspend fun translate(text: String): String? {
        val body = httpGet(
            "https://translate.googleapis.com/translate_a/single" +
                "?client=gtx&sl=$sourceLanguage&tl=$targetLanguage&dt=t&q=${urlEncode(text)}",
        ) ?: return null

        // Shape: [[["ترجمه","word",null,null,10], ...], null, "en", ...]
        val root = runCatching { lenientJson.parseToJsonElement(body) }.getOrNull() ?: return null
        val segments = (root as? JsonArray)?.firstOrNull() as? JsonArray ?: return null
        val translated = buildString {
            for (segment in segments) {
                val piece = (segment as? JsonArray)?.firstOrNull()?.jsonPrimitive?.contentOrNull
                if (piece != null) append(piece)
            }
        }.trim()
        return translated.takeIf { it.isNotEmpty() && !it.equals(text, ignoreCase = true) }
    }

    override suspend fun lookup(word: String): WordEntry? {
        val persian = translate(word.trim()) ?: return null
        return WordEntry(
            headword = word.trim().lowercase(),
            queried = word,
            pronunciation = null,
            spelling = spellOut(word),
            senses = listOf(
                Sense(PartOfSpeech.OTHER, english = "", persian = listOf(persian)),
            ),
            source = name,
        )
    }

    override suspend fun lookupPhrase(phrase: String): WordEntry? {
        val persian = translate(phrase.trim()) ?: return null
        return WordEntry(
            headword = phrase.trim(),
            queried = phrase,
            pronunciation = null,
            spelling = spellOut(phrase),
            senses = listOf(
                Sense(PartOfSpeech.PHRASE, english = "", persian = listOf(persian)),
            ),
            source = name,
        )
    }
}

/**
 * Merges an English-definition provider with a Persian-translation provider so
 * one lookup shows both, which is the whole point of the feature: the bundled
 * dictionary has both columns, but the online sources each supply only one.
 */
class MergingOnlineDictionary(
    private val english: DictionaryProvider,
    private val persian: DictionaryProvider,
) : DictionaryProvider {

    override val name: String = "online"
    override val isOffline: Boolean = false

    override suspend fun lookup(word: String): WordEntry? {
        val definitions = runCatching { english.lookup(word) }.getOrNull()
        val translation = runCatching { persian.lookup(word) }.getOrNull()
        val gloss = translation?.primaryPersian

        return when {
            definitions != null && gloss != null ->
                // Attach the Persian gloss to every sense; the free endpoint
                // cannot tell us which sense it corresponds to.
                definitions.copy(senses = definitions.senses.map { it.copy(persian = listOf(gloss)) })

            definitions != null -> definitions
            translation != null -> translation
            else -> null
        }
    }

    override suspend fun lookupPhrase(phrase: String): WordEntry? =
        runCatching { persian.lookupPhrase(phrase) }.getOrNull()
            ?: runCatching { english.lookupPhrase(phrase) }.getOrNull()
}
