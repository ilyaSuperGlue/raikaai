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

    @Test
    fun `blank optional sections are skipped`() {
        val out = CharacterPrompt.build(persona("Base instructions."))

        assertEquals("Base instructions.", out)
    }

    @Test
    fun `personality scenario and examples are appended as sections`() {
        val p =
            PersonaPreset(
                id = "custom_2",
                name = "Firefly",
                systemPrompt = "You are {{char}}.",
                personality = "Gentle, soft-spoken, warm.",
                scenario = "A quiet night in Penacony.",
                exampleDialogues = "{{user}}: Hi!\n{{char}}: Eh? You can see me...?",
            )
        val out = CharacterPrompt.build(p)

        assertEquals(
            "You are Firefly.\n\n[PERSONALITY]\nGentle, soft-spoken, warm.\n\n" +
                "[SCENARIO]\nA quiet night in Penacony.\n\n" +
                "[EXAMPLE DIALOGUE]\nMatch the voice, rhythm, and mannerisms shown below " +
                "(do not repeat these lines verbatim):\n{{user}}: Hi!\n{{char}}: Eh? You can see me...?",
            out,
        )
    }
}
