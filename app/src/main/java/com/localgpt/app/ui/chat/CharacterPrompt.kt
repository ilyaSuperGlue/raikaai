package com.localgpt.app.ui.chat

/**
 * Builds the effective system prompt for a character/persona.
 *
 * Currently performs placeholder substitution only:
 * - `{{char}}` -> persona name
 * - `{{user}}` -> user name
 *
 * Designed so scenario text and example dialogue can be added later as
 * optional PersonaPreset fields (or extra parameters here) without changing
 * existing call sites.
 */
object CharacterPrompt {
    fun build(
        persona: PersonaPreset,
        userName: String = "User",
    ): String =
        persona.systemPrompt
            .replace("{{char}}", persona.name)
            .replace("{{user}}", userName)
}
