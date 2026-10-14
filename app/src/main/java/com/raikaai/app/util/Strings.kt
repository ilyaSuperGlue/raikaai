package com.raikaai.app.util

/**
 * Null/blank-safe fallback for strings arriving from Gson-parsed JSON,
 * which bypasses Kotlin null-safety (fields arrive as JVM nulls).
 * (The stdlib `ifNullOrBlank` is unavailable on this toolchain.)
 */
internal fun String?.orFallback(fallback: String): String = if (this.isNullOrBlank()) fallback else this
