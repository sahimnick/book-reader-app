package com.bookreader.verify

import com.bookreader.core.ai.AiTasks
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AiTasksTest {

    // --- Flashcard generation --------------------------------------------

    @Test
    fun `parses generated card material`() {
        val card = AiTasks.parseCard(
            """{"cloze":"The morning was ___ and bright.",
               "example":"The water is cold in winter.",
               "mnemonic":"cold sounds like سرد when you shiver"}""",
        )!!
        assertEquals("The morning was ___ and bright.", card.cloze)
        assertTrue(card.example.isNotBlank())
        assertTrue(card.mnemonic.isNotBlank())
    }

    @Test
    fun `card parsing survives a fenced reply`() {
        val card = AiTasks.parseCard(
            "```json\n{\"cloze\":\"a ___ b\",\"example\":\"x\",\"mnemonic\":\"y\"}\n```",
        )
        assertEquals("a ___ b", card!!.cloze)
    }

    @Test
    fun `an all empty card is no card`() {
        assertNull(AiTasks.parseCard("""{"cloze":"","example":"","mnemonic":""}"""))
    }

    @Test
    fun `prose instead of json yields no card`() {
        assertNull(AiTasks.parseCard("I couldn't generate that, sorry."))
    }

    @Test
    fun `the card prompt carries word, meaning and sentence`() {
        val prompt = AiTasks.cardPrompt("cold", "The morning was cold.", "سرد")
        assertTrue(prompt.contains("cold"))
        assertTrue(prompt.contains("سرد"))
        assertTrue(prompt.contains("The morning was cold."))
    }

    // --- Plain-text cleanup ----------------------------------------------

    @Test
    fun `strips a lead-in line`() {
        assertEquals(
            "She left the house at dawn.",
            AiTasks.cleanText("Here's a summary:\nShe left the house at dawn."),
        )
    }

    @Test
    fun `strips a code fence`() {
        assertEquals("Plain answer.", AiTasks.cleanText("```\nPlain answer.\n```"))
    }

    @Test
    fun `strips surrounding quotes`() {
        assertEquals("A quoted answer.", AiTasks.cleanText("“A quoted answer.”"))
    }

    @Test
    fun `keeps a multi-line answer that has no lead-in`() {
        val text = "First point.\nSecond point."
        assertEquals(text, AiTasks.cleanText(text))
    }

    @Test
    fun `empty replies yield nothing`() {
        assertNull(AiTasks.cleanText("   \n  "))
    }

    // --- Bilingual answers ------------------------------------------------

    @Test
    fun `splits an english and persian answer`() {
        val (english, persian) = AiTasks.splitBilingual(
            "He is describing the river.\nFA: او رودخانه را توصیف می‌کند.",
        )
        assertEquals("He is describing the river.", english)
        assertEquals("او رودخانه را توصیف می‌کند.", persian)
    }

    @Test
    fun `a reply without the marker is kept whole`() {
        // Losing the answer because the model ignored the format would be worse
        // than showing it untranslated.
        val (english, persian) = AiTasks.splitBilingual("He is describing the river.")
        assertEquals("He is describing the river.", english)
        assertNull(persian)
    }

    // --- Prompt bounds -----------------------------------------------------

    @Test
    fun `the recap prompt bounds chapter length`() {
        val prompt = AiTasks.recapPrompt("word ".repeat(10_000))
        assertTrue(
            prompt.length < AiTasks.MAX_RECAP_CHARS + 500,
            "recap prompt should stay bounded, was ${prompt.length}",
        )
    }

    @Test
    fun `the passage prompt bounds passage length and keeps the question`() {
        val prompt = AiTasks.passagePrompt("x".repeat(10_000), "What does this mean?")
        assertTrue(prompt.length < AiTasks.MAX_PASSAGE_CHARS + 500)
        assertTrue(prompt.contains("What does this mean?"), "the question must survive truncation")
    }

    @Test
    fun `the translation prompt asks for the translation alone`() {
        val prompt = AiTasks.translationPrompt("The morning was cold.")
        assertTrue(prompt.contains("The morning was cold."))
        assertTrue(prompt.contains("translation only"))
    }
}
