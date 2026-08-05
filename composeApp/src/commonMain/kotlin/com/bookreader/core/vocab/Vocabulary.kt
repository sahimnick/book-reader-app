package com.bookreader.core.vocab

import com.bookreader.core.dictionary.Lemmatizer
import com.bookreader.core.text.WordTokenizer

/**
 * How often a word was looked up, and whether the reader kept it.
 *
 * A word looked up three times is a stronger signal than one glanced at once,
 * and a word that became a card the reader keeps failing is stronger still.
 */
data class WordStat(
    val word: String,
    val lookups: Int,
    /** Times the card for this word was graded "again". */
    val lapses: Int = 0,
)

/**
 * What the app has learnt about one reader's English.
 *
 * [threshold] is the difficulty at which this reader starts needing help,
 * inferred from the words they have actually looked up. Below it, marking words
 * would be noise; above it, the reader has told us by looking one up.
 */
data class ReaderProfile(
    /** Every word ever looked up, lemmatised. */
    val lookedUp: Set<String> = emptySet(),
    /** Words on cards the reader keeps failing. */
    val struggling: Set<String> = emptySet(),
    val threshold: Float = DEFAULT_THRESHOLD,
    val sampleSize: Int = 0,
) {
    /** True once enough words have been looked up for the threshold to mean anything. */
    val isEstablished: Boolean get() = sampleSize >= MIN_SAMPLE

    companion object {
        /**
         * Where an unknown reader is assumed to start.
         *
         * Deliberately high: on a fresh install nothing is known about the
         * reader, and a page covered in marks the first time a book is opened
         * is worse than no marks at all.
         */
        const val DEFAULT_THRESHOLD = 0.72f

        /** Below this many look-ups the threshold is a guess, not a measurement. */
        const val MIN_SAMPLE = 12
    }
}

/** How hard a text is expected to be for one reader. */
data class TextFit(
    /** Share of running words expected to be unknown, 0..1. */
    val unknownRatio: Float,
    /** Distinct words expected to be unknown. */
    val unknownWords: List<String>,
    val totalWords: Int,
) {
    /**
     * Bands chosen from what reading research broadly agrees on: around 2%
     * unknown words is comfortable independent reading, 5% is where
     * comprehension starts to break down without help.
     */
    val band: Band get() = when {
        unknownRatio < 0.02f -> Band.COMFORTABLE
        unknownRatio < 0.05f -> Band.STRETCHING
        else -> Band.HARD
    }

    enum class Band { COMFORTABLE, STRETCHING, HARD }
}

/**
 * A reading level built from the reader's own behaviour.
 *
 * There is no bundled frequency corpus here, and that is deliberate: the usable
 * ones are licensed, and a truncated one would misjudge exactly the long-tail
 * words that matter. Instead difficulty is estimated from a word's own surface
 * — length, syllable count, and the Latinate and Greek affixes that mark the
 * academic layer of English — calibrated against a list of the words no reader
 * of English novels looks up.
 *
 * This is a heuristic and is presented as one. It gets "ubiquitous" and
 * "the" right, and it will occasionally mark a short unusual word as easy. What
 * it never gets wrong is the part that matters most: a word the reader has
 * already looked up is always marked, because they told us.
 */
object Vocabulary {

    // --- Difficulty --------------------------------------------------------

    /**
     * A 0..1 estimate of how hard [word] is for a learner of English.
     *
     * Known-common words score 0. Everything else is scored from length,
     * syllables, and affixes — the features that separate "give up" from
     * "relinquish".
     */
    fun difficulty(word: String): Float {
        val key = WordTokenizer.normalize(word)
        if (key.isEmpty()) return 0f
        if (key.any { it.isDigit() }) return 0f
        if (key in COMMON_WORDS) return 0f
        // An inflection of a common word is common: "walked" is not a hard word.
        if (Lemmatizer.candidates(key).any { it in COMMON_WORDS }) return 0.05f

        var score = 0.30f

        // Length. Long words are not automatically hard, but short ones are
        // rarely the ones a reader stops on.
        score += when {
            key.length <= 4 -> -0.10f
            key.length <= 6 -> 0.00f
            key.length <= 8 -> 0.10f
            key.length <= 11 -> 0.20f
            else -> 0.28f
        }

        // Syllables track the Latinate layer of English closely enough to be
        // worth more than length alone.
        score += when (syllables(key)) {
            0, 1 -> -0.08f
            2 -> 0.02f
            3 -> 0.14f
            else -> 0.22f
        }

        if (ACADEMIC_SUFFIXES.any { key.endsWith(it) && key.length > it.length + 2 }) {
            score += 0.14f
        }
        if (LATINATE_PREFIXES.any { key.startsWith(it) && key.length > it.length + 3 }) {
            score += 0.08f
        }

        return score.coerceIn(0f, 1f)
    }

    /**
     * Counts syllables by vowel groups.
     *
     * Crude, and knowingly so — it over-counts "-ed" endings and under-counts
     * some diphthongs. It is used only to sort words into four bands, which it
     * does well enough that a more expensive method would not change the marks
     * on the page.
     */
    internal fun syllables(word: String): Int {
        if (word.isEmpty()) return 0
        var count = 0
        var previousWasVowel = false
        for (c in word) {
            val isVowel = c in "aeiouy"
            if (isVowel && !previousWasVowel) count++
            previousWasVowel = isVowel
        }
        // A silent final "e" is not a syllable, unless it is the only one.
        if (word.endsWith("e") && !word.endsWith("le") && count > 1) count--
        return count.coerceAtLeast(1)
    }

    // --- Profile -----------------------------------------------------------

    /**
     * Builds a profile from the reader's history.
     *
     * The threshold is the *lower quartile* of the difficulty of words they
     * looked up, not the mean: readers look up a few very hard words and many
     * moderate ones, so the mean sits above most of what they actually struggle
     * with and would leave the hardest quarter unmarked.
     */
    fun profile(stats: List<WordStat>, failedWords: Collection<String> = emptyList()): ReaderProfile {
        val lookedUp = stats.flatMap { keysFor(it.word) }.toSet()
        val struggling = failedWords.flatMap { keysFor(it) }.toSet()

        // Repeated look-ups count repeatedly: a word met and missed three times
        // says more about the reader's level than three words seen once.
        val difficulties = stats
            .flatMap { stat -> List(stat.lookups.coerceIn(1, 5)) { difficulty(stat.word) } }
            .filter { it > 0f }
            .sorted()

        val threshold = if (difficulties.size < ReaderProfile.MIN_SAMPLE) {
            ReaderProfile.DEFAULT_THRESHOLD
        } else {
            val quartile = difficulties[difficulties.size / 4]
            // Never drift so low that ordinary prose is covered in marks.
            quartile.coerceIn(0.35f, 0.85f)
        }

        return ReaderProfile(
            lookedUp = lookedUp,
            struggling = struggling,
            threshold = threshold,
            sampleSize = difficulties.size,
        )
    }

    // --- Applying the profile ----------------------------------------------

    /**
     * Words in [text] this reader is likely not to know.
     *
     * Returned as normalised keys so the caller can match them against tokens
     * however it renders them.
     */
    fun likelyUnknown(text: String, profile: ReaderProfile): Set<String> {
        val out = HashSet<String>()
        for (span in WordTokenizer.words(text)) {
            val key = WordTokenizer.normalize(span.text)
            if (key.length < 3) continue
            if (isUnknown(key, profile)) out.add(key)
        }
        return out
    }

    /** Whether one word should be marked for this reader. */
    fun isUnknown(word: String, profile: ReaderProfile): Boolean {
        val key = WordTokenizer.normalize(word)
        if (key.length < 3) return false
        // Direct evidence beats any estimate: they looked it up once already.
        val keys = keysFor(key)
        if (keys.any { it in profile.struggling }) return true
        if (keys.any { it in profile.lookedUp }) return true
        return difficulty(key) >= profile.threshold
    }

    /**
     * Every form under which a word might be recognised.
     *
     * A reader who looked up "linger" also does not know "lingered", so both
     * the word and its stems go into the profile *and* are tried against it.
     * Matching only the lemma would rely on stemming being a fixed point — it
     * is not: [Lemmatizer.lemma] of "linger" and of "lingered" are different
     * strings, so a reader would have been shown a word they had already asked
     * about.
     */
    private fun keysFor(word: String): List<String> {
        val key = WordTokenizer.normalize(word)
        if (key.isEmpty()) return emptyList()
        return Lemmatizer.candidates(key).ifEmpty { listOf(key) }
    }

    /**
     * How a whole text sits against a profile — for choosing the next book.
     *
     * Ratio is over running words, not distinct ones, because that is what a
     * reader experiences: a hard word repeated on every page is felt every
     * time.
     */
    fun assess(text: String, profile: ReaderProfile): TextFit {
        val words = WordTokenizer.words(text)
        if (words.isEmpty()) return TextFit(0f, emptyList(), 0)

        val unknown = HashSet<String>()
        var unknownOccurrences = 0
        val verdicts = HashMap<String, Boolean>()

        for (span in words) {
            val key = WordTokenizer.normalize(span.text)
            if (key.length < 3) continue
            val unknownHere = verdicts.getOrPut(key) { isUnknown(key, profile) }
            if (unknownHere) {
                unknown.add(key)
                unknownOccurrences++
            }
        }

        return TextFit(
            unknownRatio = unknownOccurrences.toFloat() / words.size,
            unknownWords = unknown.sortedByDescending { difficulty(it) },
            totalWords = words.size,
        )
    }

    // --- Data ---------------------------------------------------------------

    private val ACADEMIC_SUFFIXES = listOf(
        "tion", "sion", "ment", "ance", "ence", "ity", "ility", "ous", "ious",
        "eous", "ate", "ify", "ise", "ize", "ism", "ist", "itude", "acy",
        "escence", "ary", "ory", "ive", "ual",
    )

    private val LATINATE_PREFIXES = listOf(
        "circum", "contra", "counter", "inter", "intra", "trans", "ultra",
        "super", "sub", "pre", "post", "anti", "auto", "hyper", "mono", "poly",
        "pseudo", "quasi", "retro", "semi", "syn", "peri", "hypo", "epi",
    )

    /**
     * The words a reader of English fiction does not look up.
     *
     * Function words, the core verbs, and the everyday nouns and adjectives —
     * roughly the first thousand words by frequency, which cover most of the
     * running text of ordinary prose. Anything not here is *scored* rather than
     * assumed hard, so the list being incomplete costs accuracy at the margin
     * rather than correctness.
     */
    internal val COMMON_WORDS: Set<String> = buildSet {
        addAll(
            (
                "a about above across after again against all almost alone along already also " +
                    "although always am among an and another answer any anyone anything are " +
                    "arm around as ask at away back bad bag ball be beautiful because bed been " +
                    "before begin behind being believe below beside best better between big bird " +
                    "birth bit black blood blue board boat body book both box boy bread break " +
                    "bring brother brown build business but buy by call can car care carry case " +
                    "cat catch cause centre certain chair chance change child church city class " +
                    "clean clear close cloth cold colour come common company complete cook copy " +
                    "corner cost could count country couple course cover cross cry cup cut dark " +
                    "daughter day dead deal dear death decide deep did die difference different " +
                    "difficult dinner direct do doctor does dog done door doubt down draw dream " +
                    "dress drink drive drop dry during each ear early earth east easy eat edge " +
                    "eight either else empty end enough enter even evening ever every everyone " +
                    "everything exact example expect eye face fact fail fall family far farm fast " +
                    "father fear feed feel feet few field fight fill film find fine finger finish " +
                    "fire first fish fit five fix floor flower fly follow food foot for force " +
                    "forget form former forward found four free fresh friend from front full fun " +
                    "further future game garden gas gave general get girl give glass go god gold " +
                    "gone good got govern grand great green grew ground group grow guess had " +
                    "hair half hand hang happen happy hard has hat hate have he head hear heard " +
                    "heart heat heavy held help her here herself hide high hill him himself his " +
                    "hit hold hole home hope horse hospital hot hour house how however human " +
                    "hundred husband i ice idea if ill important in inch include indeed inside " +
                    "instead into is it its itself join judge jump just keep kept key kill kind " +
                    "king kitchen knee knew know known lack lady laid land language large last " +
                    "late later laugh law lay lead learn least leave led left leg length less " +
                    "let letter level lie life lift light like line lip list listen little live " +
                    "local lock long look lord lose loss lost lot loud love low luck lunch " +
                    "machine made main make man many mark market marry matter may maybe me mean " +
                    "meant meet member men mention met middle might mile milk mind mine minute " +
                    "miss moment money month moon more morning most mother mountain mouth move " +
                    "movie much music must my myself name near necessary neck need neither never " +
                    "new news next nice night nine no nobody none nor north nose not note nothing " +
                    "notice now number of off offer office often oh oil old on once one only open " +
                    "or order other others our out outside over own page pain paint pair paper " +
                    "parent park part party pass past pay peace people perhaps person picture " +
                    "piece place plan plant play please point poor position possible power " +
                    "present press pretty price probably problem public pull push put quarter " +
                    "question quick quiet quite race radio rain raise ran rather reach read ready " +
                    "real reason receive record red remember rest result return rich ride right " +
                    "ring rise river road rock roll room round rule run safe said sail same sat " +
                    "save saw say school sea season seat second see seem seen sell send sense " +
                    "sent serve service set seven several shall shape share sharp she sheep ship " +
                    "shirt shoe shoot shop short should shoulder shout show shut sick side sign " +
                    "silence silver simple since sing single sir sister sit six size skin sky " +
                    "sleep slow small smell smile smoke snow so soft sold soldier some someone " +
                    "something sometimes son song soon sorry sort sound south space speak special " +
                    "speed spend spoke sport spot spread spring stair stand star start state stay " +
                    "step stick still stone stood stop store storm story straight strange street " +
                    "strong student study such sudden sugar summer sun sure surprise sweet swim " +
                    "table take talk tall taste teach team tear tell ten test than thank that the " +
                    "their them themselves then there these they thick thin thing think third " +
                    "this those though thought thousand three through throw thus tie time tire " +
                    "to today together told tomorrow tonight too took top total touch toward town " +
                    "train travel tree trip trouble true trust truth try turn twelve twenty two " +
                    "type uncle under understand until up upon us use usual very village visit " +
                    "voice wait walk wall want war warm was wash watch water wave way we wear " +
                    "weather week weight welcome well went were west wet what wheel when where " +
                    "whether which while white who whole whom whose why wide wife wild will win " +
                    "wind window wine wing winter wish with within without woman women wonder " +
                    "wood word work world worry worse worth would write wrong wrote yard year " +
                    "yes yesterday yet you young your yourself"
                ).split(' '),
        )
        remove("")
    }
}
