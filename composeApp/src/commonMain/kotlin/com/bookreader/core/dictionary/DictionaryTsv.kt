package com.bookreader.core.dictionary

/** One row of dictionary data, before it is grouped into a [WordEntry]. */
data class DictionaryRow(
    val headword: String,
    val partOfSpeech: PartOfSpeech,
    val pronunciation: String?,
    val english: String,
    val persian: List<String>,
    val examples: List<String>,
    val synonyms: List<String> = emptyList(),
)

/**
 * Reads the tab-separated dictionary format used by both the bundled seed and
 * any dataset the user imports.
 *
 * Columns: `headword ⇥ pos ⇥ ipa ⇥ english ⇥ persian(|) ⇥ examples(|) ⇥ synonyms(|)`
 *
 * Parsing is lenient — a short row is padded rather than rejected — because the
 * import path exists to swallow third-party word lists, and one malformed line
 * in a hundred thousand should not abort the whole import.
 */
object DictionaryTsv {

    fun parseLine(line: String): DictionaryRow? {
        val trimmed = line.trim()
        if (trimmed.isEmpty() || trimmed.startsWith("#")) return null

        val cols = trimmed.split('\t')
        val headword = cols.getOrNull(0)?.trim()?.lowercase().orEmpty()
        if (headword.isEmpty()) return null

        fun multi(index: Int): List<String> =
            cols.getOrNull(index)
                ?.split('|')
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() }
                .orEmpty()

        return DictionaryRow(
            headword = headword,
            partOfSpeech = PartOfSpeech.parse(cols.getOrNull(1)),
            pronunciation = cols.getOrNull(2)?.trim()?.takeIf { it.isNotEmpty() },
            english = cols.getOrNull(3)?.trim().orEmpty(),
            persian = multi(4),
            examples = multi(5),
            synonyms = multi(6),
        )
    }

    fun parse(tsv: String): List<DictionaryRow> =
        tsv.lineSequence().mapNotNull(::parseLine).toList()

    /** Groups rows sharing a headword into a single multi-sense entry. */
    fun toEntries(rows: List<DictionaryRow>): Map<String, WordEntry> =
        rows.groupBy { it.headword }.mapValues { (headword, group) ->
            WordEntry(
                headword = headword,
                queried = headword,
                pronunciation = group.firstNotNullOfOrNull { it.pronunciation },
                spelling = spellOut(headword),
                senses = group.map {
                    Sense(
                        partOfSpeech = it.partOfSpeech,
                        english = it.english,
                        persian = it.persian,
                        examples = it.examples,
                        synonyms = it.synonyms,
                    )
                },
                source = "offline",
            )
        }
}

/**
 * An in-memory dictionary built from parsed rows.
 *
 * Used for the bundled seed, which is small enough that keeping it in a map
 * avoids a database round-trip on every tap. Larger imported datasets go to
 * SQLite instead.
 */
class InMemoryDictionary(
    rows: List<DictionaryRow>,
    override val name: String = "bundled",
) : DictionaryProvider {

    private val entries: Map<String, WordEntry> = DictionaryTsv.toEntries(rows)

    override val isOffline: Boolean = true

    val size: Int get() = entries.size

    override suspend fun lookup(word: String): WordEntry? = entries[word.trim().lowercase()]

    override suspend fun lookupPhrase(phrase: String): WordEntry? =
        entries[phrase.trim().lowercase()]
}
