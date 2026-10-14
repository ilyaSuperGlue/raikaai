package com.raikaai.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure-JVM tests for per-character system prompt building.
 */
class CharacterPromptTest {

    private fun persona(prompt: String) =
        PersonaPreset(
            id = "custom_1",
            name = "Koder",
            systemPrompt = prompt,
        )

    @Test
    fun `substitutes char and user placeholders`() {
        val out = CharacterPrompt.build(persona("You are {{char}}, helping {{user}}."), userName = "Budi")

        assertEquals("You are Koder, helping Budi.", out)
    }

    @Test
    fun `defaults user placeholder to User`() {
        val out = CharacterPrompt.build(persona("Hi {{user}}, I am {{char}}."))

        assertEquals("Hi User, I am Koder.", out)
    }

    @Test
    fun `prompt without placeholders is unchanged`() {
        val prompt = "You are a helpful assistant."

        assertEquals(prompt, CharacterPrompt.build(persona(prompt)))
    }
}
