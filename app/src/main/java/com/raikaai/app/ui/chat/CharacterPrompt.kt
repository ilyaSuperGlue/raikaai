package com.raikaai.app.ui.chat

/**
 * Builds the effective system prompt for a character/persona.
 *
 * Placeholder substitution:
 * - `{{char}}` -> persona name
 * - `{{user}}` -> user name
 *
 * Optional [PersonaPreset] sections are appended as clearly delimited
 * blocks (skipped when blank) so small on-device models can tell
 * instructions apart from personality notes, scene setup, and style
 * examples:
 * - personality -> `[PERSONALITY]`
 * - scenario -> `[SCENARIO]`
 * - exampleDialogues -> `[EXAMPLE DIALOGUE]` (few-shot style reference)
 */
object CharacterPrompt {
    fun build(
        persona: PersonaPreset,
        userName: String = "User",
    ): String {
        val base =
            persona.systemPrompt
                .replace("{{char}}", persona.name)
                .replace("{{user}}", userName)
        val sb = StringBuilder(base.trimEnd())
        if (persona.personality.isNotBlank()) {
            sb.append("\n\n[PERSONALITY]\n").append(persona.personality.trim())
        }
        if (persona.scenario.isNotBlank()) {
            sb.append("\n\n[SCENARIO]\n").append(persona.scenario.trim())
        }
        if (persona.exampleDialogues.isNotBlank()) {
            sb.append("\n\n[EXAMPLE DIALOGUE]\n")
                .append("Match the voice, rhythm, and mannerisms shown below (do not repeat these lines verbatim):\n")
                .append(persona.exampleDialogues.trim())
        }
        return sb.toString()
    }
}
