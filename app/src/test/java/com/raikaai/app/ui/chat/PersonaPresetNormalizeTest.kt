package com.raikaai.app.ui.chat

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests: legacy persona JSON (missing fields) must normalize
 * to safe non-null values instead of crashing on JVM nulls.
 */
class PersonaPresetNormalizeTest {

    private val gson = Gson()

    @Test
    fun `legacy json without new fields normalizes safely`() {
        val parsed = gson.fromJson(
            """{"name":"Lama","systemPrompt":"Hai"}""",
            PersonaPreset::class.java,
        ).normalize()

        assertEquals("Lama", parsed.name)
        assertEquals("Hai", parsed.systemPrompt)
        assertEquals("", parsed.description)
        assertEquals("", parsed.greeting)
        assertEquals("", parsed.personality)
        assertEquals("", parsed.scenario)
        assertEquals("", parsed.exampleDialogues)
        assertEquals("custom", parsed.iconType)
        assertEquals("", parsed.avatarUri)
        assertTrue(parsed.id.isNotBlank())
        assertNull(parsed.temperature)
        assertNull(parsed.topK)
    }

    @Test
    fun `explicit nulls fall back to defaults`() {
        val parsed = gson.fromJson(
            """{"id":null,"name":null,"systemPrompt":null,"temperature":999.0,"topK":-5}""",
            PersonaPreset::class.java,
        ).normalize()

        assertTrue(parsed.id.isNotBlank())
        assertEquals("Custom Persona", parsed.name)
        assertEquals("", parsed.systemPrompt)
        assertEquals(2f, parsed.temperature)
        assertNull(parsed.topK)
    }

    @Test
    fun `complete json round-trips unchanged`() {
        val full = PersonaPreset(
            id = "custom_9",
            name = "A",
            iconType = "coder",
            description = "d",
            greeting = "g",
            systemPrompt = "s",
            avatarUri = "u",
            temperature = 0.5f,
            topK = 20,
            personality = "p",
            scenario = "sc",
            exampleDialogues = "ex",
        )
        val parsed = gson.fromJson(gson.toJson(full), PersonaPreset::class.java).normalize()

        assertEquals(full, parsed)
    }
}
