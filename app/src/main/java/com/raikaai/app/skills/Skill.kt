package com.raikaai.app.skills

data class Skill(
    val id: String,
    val name: String,
    val description: String,
    val instructions: String,
    val iconCategory: String = "code", // "code", "terminal", "write", "search", "math", "palette", "sparkles"
    val isEnabled: Boolean = false,
    val isBuiltIn: Boolean = false,
    val category: String = "General", // "Coding", "Writing", "Reasoning", "Analysis", "General"
    val createdAt: Long = System.currentTimeMillis(),
)

/**
 * Gson bypasses the Kotlin constructor: fields missing from old/corrupt JSON
 * stay JVM-null even when declared non-null, which later crashes string
 * operations (e.g. instructions.isNotBlank()). Always pass Gson-parsed
 * skills through here before storing or using them.
 */
@Suppress("SENSELESS_COMPARISON")
internal fun Skill.normalize(): Skill {
    fun str(v: String?, fallback: String): String = if (v.isNullOrBlank()) fallback else v
    return Skill(
        id = str(id as String?, "skill_" + System.currentTimeMillis()),
        name = str(name as String?, "Custom Skill"),
        description = (description as String?).orEmpty(),
        instructions = (instructions as String?).orEmpty(),
        iconCategory = str(iconCategory as String?, "code"),
        isEnabled = (isEnabled as Boolean?) ?: false,
        isBuiltIn = (isBuiltIn as Boolean?) ?: false,
        category = str(category as String?, "General"),
        createdAt = (createdAt as Long?)?.takeIf { it > 0 } ?: System.currentTimeMillis(),
    )
}
