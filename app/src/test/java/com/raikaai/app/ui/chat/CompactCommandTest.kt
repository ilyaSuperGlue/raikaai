package com.raikaai.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CompactCommandTest {

    @Test
    fun defaultSlashCommandsContainsCompact() {
        val compactCmd = DEFAULT_SLASH_COMMANDS.find { it.command == "/compact" }
        assertNotNull("DEFAULT_SLASH_COMMANDS should contain /compact", compactCmd)
        assertEquals("/compact ", compactCmd?.promptTemplate)
    }

    @Test
    fun compactRegexMatchesStandardAndFocusedInputs() {
        val regex = Regex("""^/compact(?:\s+(.*))?$""", RegexOption.IGNORE_CASE)

        val matchBare = regex.find("/compact")
        assertNotNull(matchBare)
        assertEquals("", matchBare?.groupValues?.getOrNull(1)?.trim().orEmpty())

        val matchTrailingSpace = regex.find("/compact   ")
        assertNotNull(matchTrailingSpace)
        assertEquals("", matchTrailingSpace?.groupValues?.getOrNull(1)?.trim().orEmpty())

        val matchWithFocus = regex.find("/compact fokus ke bug database dan auth")
        assertNotNull(matchWithFocus)
        assertEquals("fokus ke bug database dan auth", matchWithFocus?.groupValues?.getOrNull(1)?.trim())

        val matchCaseInsensitive = regex.find("/COMPACT preserve python code")
        assertNotNull(matchCaseInsensitive)
        assertEquals("preserve python code", matchCaseInsensitive?.groupValues?.getOrNull(1)?.trim())
    }

    @Test
    fun roleCompactionConstantExists() {
        assertEquals("compaction", com.raikaai.app.data.ChatConstants.ROLE_COMPACTION)
    }

    @Test
    fun cachedRemoteModelsRoundtrip() {
        val models = listOf(
            com.raikaai.app.core.remote.RemoteModelItem(id = "9routerAll", name = "9router All", provider = "9router"),
            com.raikaai.app.core.remote.RemoteModelItem(id = "meta-llama/llama-3.3-70b", name = "Llama 3.3 70B", provider = "OpenRouter"),
        )
        val json = com.raikaai.app.data.cachedRemoteModelsToJson(models)
        val parsed = com.raikaai.app.data.parseCachedRemoteModels(json)
        assertEquals(2, parsed.size)
        assertEquals("9routerAll", parsed[0].id)
        assertEquals("9router", parsed[0].provider)
        assertEquals("meta-llama/llama-3.3-70b", parsed[1].id)
    }
}
