package com.raikaai.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    @Test
    fun validCompactionSummaryPasses() {
        val good = "## Objective\n- goals\n\n## Requirements\n- reqs\n\n## Decisions\n- d\n\n## Work State\n- w"
        assertTrue(com.raikaai.app.ui.chat.isValidCompactionSummary(good))
        assertTrue(com.raikaai.app.ui.chat.isValidCompactionSummary("  \n" + good))
    }

    @Test
    fun malformedCompactionSummaryFails() {
        assertFalse(com.raikaai.app.ui.chat.isValidCompactionSummary(""))
        assertFalse(com.raikaai.app.ui.chat.isValidCompactionSummary("Here is your summary:\n## Objective\n- x"))
        assertFalse(com.raikaai.app.ui.chat.isValidCompactionSummary("## Objective\n- x\n\n## Decisions\n- y"))
    }

    @Test
    fun normalizeAndAlwaysAllowRoundtrip() {
        assertEquals(
            "apk add python3",
            com.raikaai.app.core.container.normalizeCommand("  apk   add   python3  "),
        )
        val set = setOf("ls /workspace", "apk add python3")
        val json = com.raikaai.app.core.container.alwaysAllowToJson(set)
        val back = com.raikaai.app.core.container.parseAlwaysAllow(json)
        assertTrue(back.containsAll(set))
        assertTrue(com.raikaai.app.core.container.parseAlwaysAllow("").isEmpty())
        assertTrue(com.raikaai.app.core.container.parseAlwaysAllow("nope").isEmpty())
    }
}
