package com.bookreader.verify

import com.bookreader.core.dictionary.AiSense
import com.bookreader.core.dictionary.PartOfSpeech
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AiSenseTest {

    private val reply = """
        {"headword":"bank","pos":"noun","ipa":"/bæŋk/",
         "english":"the land alongside a river","persian":["ساحل","کناره"],
         "example":"They walked along the bank."}
    """.trimIndent()

    @Test
    fun `parses a well formed reply`() {
        val entry = AiSense.parse(reply, "bank")!!
        assertEquals("bank", entry.headword)
        assertEquals("/bæŋk/", entry.pronunciation)
        assertEquals("B-A-N-K", entry.spelling)
        assertEquals(PartOfSpeech.NOUN, entry.senses[0].partOfSpeech)
        assertEquals("the land alongside a river", entry.senses[0].english)
        assertEquals(listOf("ساحل", "کناره"), entry.senses[0].persian)
        assertEquals("They walked along the bank.", entry.senses[0].examples[0])
    }

    @Test
    fun `returns a single sense, not a survey`() {
        // The whole point: one meaning for this usage.
        assertEquals(1, AiSense.parse(reply, "bank")!!.senses.size)
    }

    @Test
    fun `unwraps a fenced reply`() {
        val fenced = "Here you go:\n```json\n$reply\n```\nHope that helps!"
        assertEquals("bank", AiSense.parse(fenced, "bank")!!.headword)
    }

    @Test
    fun `handles braces inside strings`() {
        val tricky = """{"headword":"set","pos":"verb","english":"to place {like this}",
            "persian":["گذاشتن"],"ipa":"","example":""}"""
        val entry = AiSense.parse(tricky, "set")!!
        assertEquals("to place {like this}", entry.senses[0].english)
    }

    @Test
    fun `accepts persian as a bare string`() {
        val loose = """{"headword":"book","english":"a written work","persian":"کتاب"}"""
        assertEquals(listOf("کتاب"), AiSense.parse(loose, "book")!!.senses[0].persian)
    }

    @Test
    fun `prose with no json yields nothing`() {
        assertNull(AiSense.parse("I'm sorry, I can't help with that.", "bank"))
    }

    @Test
    fun `an empty answer yields nothing`() {
        // Neither definition nor gloss: not an answer, so fall back.
        assertNull(AiSense.parse("""{"headword":"bank","english":"","persian":[]}""", "bank"))
    }

    @Test
    fun `truncated json yields nothing rather than throwing`() {
        assertNull(AiSense.parse("""{"headword":"bank","english":"the land al""", "bank"))
    }

    @Test
    fun `falls back to the queried term when no headword is given`() {
        val entry = AiSense.parse("""{"english":"a meaning","persian":["معنی"]}""", "Running")!!
        assertEquals("running", entry.headword)
        assertEquals("Running", entry.queried)
    }

    @Test
    fun `the prompt carries both term and sentence`() {
        val prompt = AiSense.buildPrompt("bank", "She sat on the bank and watched the current.")
        assertTrue(prompt.contains("bank"))
        assertTrue(prompt.contains("watched the current"))
        assertTrue(prompt.contains("AS USED"), "must ask for the contextual sense")
    }

    @Test
    fun `the prompt bounds context length`() {
        val prompt = AiSense.buildPrompt("word", "x".repeat(5000))
        assertTrue(prompt.length < 1200, "prompt should stay small, was ${prompt.length}")
    }

    @Test
    fun `cache keys ignore incidental differences`() {
        val a = AiSense.cacheKey("Bank", "She sat on the bank.")
        val b = AiSense.cacheKey("bank", "she sat on the  bank!")
        assertEquals(a, b, "case and punctuation should not cost another call")
    }

    @Test
    fun `cache keys separate different senses of the same word`() {
        val river = AiSense.cacheKey("bank", "She sat on the bank and watched the current.")
        val money = AiSense.cacheKey("bank", "He deposited the cheque at the bank.")
        assertNotEquals(river, money, "different context must be a different answer")
    }
}
