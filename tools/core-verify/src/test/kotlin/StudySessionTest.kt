package com.bookreader.verify

import com.bookreader.core.srs.ReviewGrade
import com.bookreader.core.srs.StudySession
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StudySessionTest {

    private fun session(vararg ids: String) = StudySession(ids.toList()) { it }

    @Test
    fun `serves cards in order`() {
        val s = session("a", "b", "c")
        assertEquals("a", s.current())
        s.grade(ReviewGrade.GOOD)
        assertEquals("b", s.current())
    }

    @Test
    fun `a correct answer removes the card from the session`() {
        val s = session("a", "b")
        s.grade(ReviewGrade.GOOD)
        s.grade(ReviewGrade.EASY)
        assertTrue(s.isFinished)
        assertNull(s.current())
    }

    @Test
    fun `a failed card comes back in the same session`() {
        val s = session("a", "b", "c", "d", "e")
        s.grade(ReviewGrade.AGAIN) // "a" fails
        // It must not be next — that would be echo, not recall.
        assertEquals("b", s.current())
        // But it must return before the session ends.
        val seen = generateSequence { s.current()?.also { s.grade(ReviewGrade.GOOD) } }
            .take(10).toList()
        assertTrue("a" in seen, "failed card never returned: $seen")
    }

    @Test
    fun `a failed card keeps returning until answered correctly`() {
        val s = session("a", "b")
        s.grade(ReviewGrade.AGAIN)
        s.grade(ReviewGrade.GOOD) // b
        // Only "a" is left, still unlearned.
        assertEquals("a", s.current())
        s.grade(ReviewGrade.AGAIN)
        assertEquals("a", s.current(), "still the only card, must be served again")
        s.grade(ReviewGrade.GOOD)
        assertTrue(s.isFinished)
    }

    @Test
    fun `progress counts distinct cards not repetitions`() {
        val s = session("a", "b", "c")
        assertEquals(3, s.total)
        s.grade(ReviewGrade.AGAIN) // a fails, no progress
        assertEquals(0, s.completed)
        s.grade(ReviewGrade.GOOD)  // b
        assertEquals(1, s.completed)
        s.grade(ReviewGrade.GOOD)  // c
        assertEquals(2, s.completed)
        s.grade(ReviewGrade.GOOD)  // a, finally
        assertEquals(3, s.completed)
        assertTrue(s.isFinished)
    }

    @Test
    fun `hard answers count as progress but still come back`() {
        val s = session("a", "b", "c")
        s.grade(ReviewGrade.HARD)
        assertEquals(1, s.completed)
        assertTrue(s.remaining >= 3, "a hard card should still be queued")
    }

    @Test
    fun `deleting a card removes it from the queue`() {
        val s = session("a", "b", "c")
        s.remove("b")
        s.grade(ReviewGrade.GOOD) // a
        assertEquals("c", s.current())
    }

    @Test
    fun `an empty session is finished immediately`() {
        val s = session()
        assertTrue(s.isFinished)
        assertNull(s.current())
        assertNull(s.grade(ReviewGrade.GOOD))
    }

    @Test
    fun `grading past the end is safe`() {
        val s = session("a")
        s.grade(ReviewGrade.GOOD)
        assertNull(s.grade(ReviewGrade.GOOD))
    }
}
