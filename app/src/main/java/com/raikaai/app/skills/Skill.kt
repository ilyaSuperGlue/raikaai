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
