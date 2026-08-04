package com.bookreader.core.srs

import kotlin.math.roundToLong

/** How well the learner recalled a card. Maps onto the SM-2 quality scale. */
enum class ReviewGrade(val quality: Int) {
    /** No recall at all — the card restarts its learning steps. */
    AGAIN(0),

    /** Recalled, but only after real effort. */
    HARD(3),

    /** Recalled correctly. */
    GOOD(4),

    /** Recalled instantly. */
    EASY(5),
    ;

    val isCorrect: Boolean get() = quality >= 3
}

/**
 * Scheduling state for one card. Immutable — [SpacedRepetition.review] returns a
 * new instance so the caller can persist the transition atomically.
 */
data class ReviewState(
    /** Consecutive correct answers. Reset to zero on a lapse. */
    val repetitions: Int = 0,
    /** Days until the next review. */
    val intervalDays: Int = 0,
    /** SM-2 ease factor; 1.3 is the floor the algorithm defines. */
    val easeFactor: Double = 2.5,
    /** Epoch milliseconds when this card is next due. */
    val dueAtMillis: Long = 0L,
    /** Number of times the card has been forgotten after being learned. */
    val lapses: Int = 0,
) {
    val isNew: Boolean get() = repetitions == 0 && intervalDays == 0
}

/**
 * The SM-2 spaced repetition algorithm.
 *
 * SM-2 is used rather than a newer scheduler because it needs only four numbers
 * per card and no training data, so a freshly installed app schedules sensibly
 * from the very first review.
 */
object SpacedRepetition {

    const val MINIMUM_EASE = 1.3
    private const val MILLIS_PER_DAY = 24L * 60 * 60 * 1000

    /** Interval applied when a card is forgotten and has to be relearned. */
    private const val RELEARN_INTERVAL_DAYS = 1

    /**
     * Applies [grade] to [state] at [nowMillis], returning the updated schedule.
     *
     * A lapse (grade AGAIN) resets the repetition count and sends the card back
     * to a one-day interval, but keeps a reduced ease factor: a word the learner
     * has forgotten once is likely to be hard again, and SM-2 encodes that by
     * lowering ease permanently rather than by restarting from scratch.
     */
    fun review(
        state: ReviewState,
        grade: ReviewGrade,
        nowMillis: Long,
    ): ReviewState {
        val q = grade.quality

        // SM-2 ease update. The formula is defined for all grades; the floor at
        // 1.3 stops a repeatedly-failed card from collapsing to daily forever.
        val updatedEase = (state.easeFactor + (0.1 - (5 - q) * (0.08 + (5 - q) * 0.02)))
            .coerceAtLeast(MINIMUM_EASE)

        if (!grade.isCorrect) {
            return state.copy(
                repetitions = 0,
                intervalDays = RELEARN_INTERVAL_DAYS,
                easeFactor = updatedEase,
                dueAtMillis = nowMillis + RELEARN_INTERVAL_DAYS * MILLIS_PER_DAY,
                lapses = state.lapses + 1,
            )
        }

        val repetitions = state.repetitions + 1
        val interval = when (repetitions) {
            1 -> 1
            2 -> 6
            else -> (state.intervalDays * updatedEase).roundToLong()
                .coerceIn(1L, MAX_INTERVAL_DAYS.toLong())
                .toInt()
        }

        return state.copy(
            repetitions = repetitions,
            intervalDays = interval,
            easeFactor = updatedEase,
            dueAtMillis = nowMillis + interval.toLong() * MILLIS_PER_DAY,
            lapses = state.lapses,
        )
    }

    /** Ten years — a cap that keeps intervals from overflowing on mature cards. */
    const val MAX_INTERVAL_DAYS = 3650

    fun isDue(state: ReviewState, nowMillis: Long): Boolean =
        state.isNew || state.dueAtMillis <= nowMillis

    /**
     * Orders a study queue: overdue cards first (most overdue leading), then new
     * cards. Sorting by due date means a backlog is worked off oldest-first,
     * which is what keeps a lapsed card from being buried.
     */
    fun <T> buildQueue(
        cards: List<T>,
        nowMillis: Long,
        limit: Int = Int.MAX_VALUE,
        stateOf: (T) -> ReviewState,
    ): List<T> {
        val due = cards.filter { isDue(stateOf(it), nowMillis) }
        val (fresh, seen) = due.partition { stateOf(it).isNew }
        return (seen.sortedBy { stateOf(it).dueAtMillis } + fresh).take(limit)
    }

    /** A human-readable description of when a grade would next surface the card. */
    fun previewInterval(state: ReviewState, grade: ReviewGrade): Int =
        review(state, grade, nowMillis = 0L).intervalDays
}
