package com.raikaai.app.data

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Audit regression tests: legacy/corrupt conversation JSON must normalize
 * to safe non-null values instead of crashing (title.isBlank, messages
 * iteration, role/content string ops).
 */
class ConversationNormalizeAuditTest {

    private val gson = Gson()

    @Test
    fun `null title messages role content normalize safely`() {
        val parsed = gson.fromJson(
            """{"id":"c1","title":null,"messages":[{"role":null,"content":null}]}""",
            Conversation::class.java,
        )
        val out = ChatRepository.normalize(parsed)

        assertEquals("c1", out.id)
        assertEquals("", out.title)
        assertEquals(1, out.messages.size)
        assertEquals("", out.messages[0].role)
        assertEquals("", out.messages[0].content)
    }

    @Test
    fun `missing messages key becomes empty list`() {
        val parsed = gson.fromJson(
            """{"id":"c2","title":"T"}""",
            Conversation::class.java,
        )
        val out = ChatRepository.normalize(parsed)

        assertEquals("c2", out.id)
        assertTrue(out.messages.isEmpty())
    }

    @Test
    fun `blank id is regenerated`() {
        val parsed = gson.fromJson(
            """{"id":"","title":"T","messages":[]}""",
            Conversation::class.java,
        )
        val out = ChatRepository.normalize(parsed)

        assertTrue(out.id.isNotBlank())
    }
}
