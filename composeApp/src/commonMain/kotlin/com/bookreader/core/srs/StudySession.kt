package com.bookreader.core.srs

/**
 * An in-session study queue with relearning steps.
 *
 * Plain SM-2 only decides when a card comes back on a *later day*. On its own
 * that makes a session inefficient: a word you just failed vanishes until
 * tomorrow, so the one card you demonstrably do not know is the one you stop
 * practising. Every serious spaced-repetition tool layers short relearning
 * steps on top for this reason.
 *
 * This queue reinserts a failed card a few places back so it returns while the
 * answer is still fresh, and keeps reinserting until it is answered correctly.
 * The long-term schedule is still SM-2's job — [SpacedRepetition.review] is
 * what gets persisted; this only governs the order within one sitting.
 */
class StudySession<T>(
    cards: List<T>,
    private val idOf: (T) -> String,
) {
    private val queue: ArrayDeque<T> = ArrayDeque(cards)

    /** Ids answered correctly at least once, so repeats are not double-counted. */
    private val graduated = LinkedHashSet<String>()

    /** Total distinct cards the session started with. */
    val total: Int = cards.map(idOf).distinct().size

    /** Distinct cards answered correctly so far. */
    val completed: Int get() = graduated.size

    /** Cards still queued, including cards waiting to come round again. */
    val remaining: Int get() = queue.size

    val isFinished: Boolean get() = queue.isEmpty()

    fun current(): T? = queue.firstOrNull()

    /**
     * Applies [grade] to the current card and advances.
     *
     * A wrong answer sends the card back a few places rather than to the end:
     * far enough that it is recall rather than echo, near enough to still be in
     * the same sitting. A hard answer goes further back; a correct one leaves
     * the session.
     */
    fun grade(grade: ReviewGrade): T? {
        val card = queue.removeFirstOrNull() ?: return null

        when (grade) {
            ReviewGrade.AGAIN -> reinsert(card, AGAIN_GAP)
            ReviewGrade.HARD -> {
                graduated.add(idOf(card))
                reinsert(card, HARD_GAP)
            }
            ReviewGrade.GOOD, ReviewGrade.EASY -> graduated.add(idOf(card))
        }
        return current()
    }

    /** Drops a card entirely — used when the reader deletes it mid-session. */
    fun remove(id: String) {
        queue.removeAll { idOf(it) == id }
    }

    private fun reinsert(card: T, gap: Int) {
        val position = minOf(gap, queue.size)
        queue.add(position, card)
    }

    private companion object {
        /**
         * Places to push a failed card back. Three is far enough that answering
         * it again is recall rather than repeating what is still on screen.
         */
        const val AGAIN_GAP = 3

        /** A card recalled with effort still needs another pass, but later. */
        const val HARD_GAP = 8
    }
}
