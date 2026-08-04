package com.bookreader.core.dictionary

/**
 * Reduces an inflected English word to candidate dictionary forms.
 *
 * A bundled dictionary stores base forms ("run", "child", "happy"), but readers
 * tap whatever is on the page ("running", "children", "happier"). Rather than
 * ship a full morphological analyser, this produces an ordered list of
 * candidates for the lookup to try in turn — the first one that hits wins.
 * Over-generating is harmless because a miss simply falls through to the next
 * candidate; missing a form is what would leave the reader with no definition.
 */
object Lemmatizer {

    /** Irregulars that no suffix rule can reach. */
    private val IRREGULAR: Map<String, String> = mapOf(
        // verbs
        "was" to "be", "were" to "be", "am" to "be", "is" to "be", "are" to "be",
        "been" to "be", "being" to "be",
        "had" to "have", "has" to "have", "having" to "have",
        "did" to "do", "does" to "do", "done" to "do", "doing" to "do",
        "went" to "go", "gone" to "go", "goes" to "go",
        "made" to "make", "making" to "make",
        "said" to "say", "says" to "say",
        "took" to "take", "taken" to "take", "taking" to "take",
        "came" to "come", "coming" to "come",
        "saw" to "see", "seen" to "see", "seeing" to "see",
        "knew" to "know", "known" to "know",
        "got" to "get", "gotten" to "get", "getting" to "get",
        "gave" to "give", "given" to "give", "giving" to "give",
        "found" to "find", "finding" to "find",
        "thought" to "think", "told" to "tell", "became" to "become",
        "left" to "leave", "felt" to "feel", "brought" to "bring",
        "began" to "begin", "begun" to "begin", "kept" to "keep",
        "held" to "hold", "wrote" to "write", "written" to "write",
        "stood" to "stand", "heard" to "hear", "let" to "let",
        "meant" to "mean", "met" to "meet", "ran" to "run", "running" to "run",
        "paid" to "pay", "sat" to "sit", "spoke" to "speak", "spoken" to "speak",
        "lay" to "lie", "led" to "lead", "grew" to "grow", "grown" to "grow",
        "lost" to "lose", "losing" to "lose", "fell" to "fall", "fallen" to "fall",
        "sent" to "send", "built" to "build", "understood" to "understand",
        "drew" to "draw", "drawn" to "draw", "broke" to "break", "broken" to "break",
        "spent" to "spend", "cut" to "cut", "rose" to "rise", "risen" to "rise",
        "driven" to "drive", "drove" to "drive", "bought" to "buy",
        "wore" to "wear", "worn" to "wear", "chose" to "choose", "chosen" to "choose",
        "ate" to "eat", "eaten" to "eat", "flew" to "fly", "flown" to "fly",
        "forgot" to "forget", "forgotten" to "forget", "slept" to "sleep",
        "sold" to "sell", "taught" to "teach", "caught" to "catch",
        "fought" to "fight", "threw" to "throw", "thrown" to "throw",
        // nouns
        "children" to "child", "men" to "man", "women" to "woman",
        "feet" to "foot", "teeth" to "tooth", "geese" to "goose",
        "mice" to "mouse", "people" to "person", "lives" to "life",
        "knives" to "knife", "wives" to "wife", "leaves" to "leaf",
        "wolves" to "wolf", "halves" to "half", "shelves" to "shelf",
        "thieves" to "thief", "loaves" to "loaf", "selves" to "self",
        "data" to "datum", "criteria" to "criterion", "phenomena" to "phenomenon",
        // adjectives
        "better" to "good", "best" to "good", "worse" to "bad", "worst" to "bad",
        "further" to "far", "furthest" to "far", "farther" to "far",
    )

    private val DOUBLING_CONSONANTS = "bdgklmnprtz"

    /**
     * Returns lookup candidates for [word], most likely first. The input itself
     * is always the first candidate, so an exact dictionary hit costs nothing.
     */
    fun candidates(word: String): List<String> {
        val lower = word.lowercase().trim()
        if (lower.isEmpty()) return emptyList()

        val out = LinkedHashSet<String>()
        out.add(lower)

        IRREGULAR[lower]?.let { out.add(it) }

        // A hyphenated compound may only exist under its head word.
        if ('-' in lower) {
            out.add(lower.replace("-", ""))
            out.add(lower.substringAfterLast('-'))
        }

        // Singular possessive: "dog's" -> "dog"
        if (lower.endsWith("'s") || lower.endsWith("’s")) {
            out.addAll(candidates(lower.dropLast(2)))
        } else if (lower.endsWith("'") || lower.endsWith("’")) {
            // Plural possessive: "dogs'" -> "dogs" -> "dog"
            out.addAll(candidates(lower.dropLast(1)))
        }

        addSuffixCandidates(lower, out)
        return out.filter { it.isNotEmpty() }
    }

    /** The single best guess at a base form. */
    fun lemma(word: String): String = candidates(word).lastOrNull() ?: word.lowercase()

    private fun addSuffixCandidates(w: String, out: MutableSet<String>) {
        fun addStem(stem: String) {
            if (stem.length >= 2) out.add(stem)
        }

        when {
            // plural / third person: -ies -> -y  (studies -> study)
            w.length > 3 && w.endsWith("ies") -> {
                addStem(w.dropLast(3) + "y")
                addStem(w.dropLast(2))
            }
            // -es after a sibilant (boxes, watches, buses)
            w.length > 3 && w.endsWith("es") -> {
                val stem = w.dropLast(2)
                if (stem.endsWith("s") || stem.endsWith("x") || stem.endsWith("z") ||
                    stem.endsWith("ch") || stem.endsWith("sh")
                ) {
                    addStem(stem)
                }
                addStem(w.dropLast(1))
            }
            // plain -s, but not -ss (glass stays glass)
            w.length > 2 && w.endsWith("s") && !w.endsWith("ss") -> addStem(w.dropLast(1))
        }

        if (w.length > 4 && w.endsWith("ing")) {
            val stem = w.dropLast(3)
            addStem(stem)              // reading -> read
            addStem(stem + "e")        // making  -> make
            addDoubledStem(stem, out)  // running -> run
        }

        if (w.length > 3 && w.endsWith("ed")) {
            val stem = w.dropLast(2)
            addStem(stem)              // walked  -> walk
            addStem(w.dropLast(1))     // liked   -> like
            addDoubledStem(stem, out)  // stopped -> stop
            if (stem.endsWith("i")) addStem(stem.dropLast(1) + "y") // tried -> try
        }

        if (w.length > 4 && w.endsWith("er")) {
            addStem(w.dropLast(2))
            addStem(w.dropLast(1))
            if (w.dropLast(2).endsWith("i")) addStem(w.dropLast(3) + "y")
        }

        if (w.length > 4 && w.endsWith("est")) {
            addStem(w.dropLast(3))
            addStem(w.dropLast(2))
            if (w.dropLast(3).endsWith("i")) addStem(w.dropLast(4) + "y")
        }

        if (w.length > 4 && w.endsWith("ly")) {
            addStem(w.dropLast(2))
            if (w.dropLast(2).endsWith("i")) addStem(w.dropLast(3) + "y") // happily -> happy
        }
    }

    /** Undoes consonant doubling: "runn" -> "run", but leaves "fall" alone. */
    private fun addDoubledStem(stem: String, out: MutableSet<String>) {
        if (stem.length < 3) return
        val last = stem[stem.length - 1]
        val secondLast = stem[stem.length - 2]
        if (last == secondLast && last in DOUBLING_CONSONANTS) {
            out.add(stem.dropLast(1))
        }
    }
}
