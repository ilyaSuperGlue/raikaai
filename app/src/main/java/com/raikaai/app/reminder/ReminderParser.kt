package com.raikaai.app.reminder

import java.time.Instant
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/**
 * Parsed reminder command.
 *
 * @property triggerAtMillis trigger time in epoch millis.
 * @property note note content shown in the notification.
 */
data class ReminderSpec(
    val triggerAtMillis: Long,
    val note: String,
)

/**
 * Pure parser (no Android dependencies, unit-testable) for reminder commands
 * in Indonesian and English.
 *
 * Supported patterns (examples are literal match inputs, not UI copy):
 * - "ingatkan saya dalam 10 menit untuk minum obat"
 * - "remind me in 2 hours to call mom"
 * - "ingatkan saya jam 15:30 untuk meeting" (past time today rolls to tomorrow)
 * - "ingatkan saya besok jam 9 untuk lari pagi"
 * - "remind me tomorrow at 9am to join standup"
 *
 * Returns null when the text matches no pattern.
 */
object ReminderParser {

    private val RELATIVE_ID = Regex(
        """^(?:tolong\s+)?ingatkan\s+(?:saya|aku)\s+dalam\s+(\d+)\s+(detik|menit|jam|hari)\s+(?:untuk|buat|agar)\s+(.+)$""",
        RegexOption.IGNORE_CASE,
    )
    private val RELATIVE_EN = Regex(
        """^remind\s+me\s+in\s+(\d+)\s+(seconds?|minutes?|hours?|days?)\s+to\s+(.+)$""",
        RegexOption.IGNORE_CASE,
    )
    private val ABSOLUTE_TODAY_ID = Regex(
        """^(?:tolong\s+)?ingatkan\s+(?:saya|aku)\s+(?:jam|pukul)\s+(\d{1,2})[:.](\d{2})\s+(?:untuk|buat|agar)\s+(.+)$""",
        RegexOption.IGNORE_CASE,
    )
    private val TOMORROW_ID = Regex(
        """^(?:tolong\s+)?ingatkan\s+(?:saya|aku)\s+besok\s+(?:(?:jam|pukul)\s+)?(\d{1,2})(?::(\d{2}))?\s+(?:untuk|buat|agar)\s+(.+)$""",
        RegexOption.IGNORE_CASE,
    )
    private val TOMORROW_EN = Regex(
        """^remind\s+me\s+tomorrow\s+at\s+(\d{1,2})(?::(\d{2}))?\s*(am|pm)?\s+to\s+(.+)$""",
        RegexOption.IGNORE_CASE,
    )

    /**
     * @param input raw command text from the user.
     * @param nowMillis "now" in epoch millis; injectable for deterministic tests.
     * @return [ReminderSpec] on match, null otherwise.
     */
    fun parse(input: String, nowMillis: Long = System.currentTimeMillis()): ReminderSpec? {
        val text = input.trim()
        if (text.isEmpty()) return null
        return parseRelativeId(text, nowMillis)
            ?: parseRelativeEn(text, nowMillis)
            ?: parseAbsoluteTodayId(text, nowMillis)
            ?: parseTomorrowId(text, nowMillis)
            ?: parseTomorrowEn(text, nowMillis)
    }

    private fun parseRelativeId(text: String, nowMillis: Long): ReminderSpec? {
        val m = RELATIVE_ID.matchEntire(text) ?: return null
        val amount = m.groupValues[1].toLongOrNull() ?: return null
        val unitMillis = when (m.groupValues[2].lowercase()) {
            "detik" -> 1_000L
            "menit" -> 60_000L
            "jam" -> 3_600_000L
            "hari" -> 86_400_000L
            else -> return null
        }
        val note = m.groupValues[3].trim()
        if (note.isEmpty()) return null
        return ReminderSpec(triggerAtMillis = nowMillis + amount * unitMillis, note = note)
    }

    private fun parseRelativeEn(text: String, nowMillis: Long): ReminderSpec? {
        val m = RELATIVE_EN.matchEntire(text) ?: return null
        val amount = m.groupValues[1].toLongOrNull() ?: return null
        val unitMillis = when (m.groupValues[2].lowercase()) {
            "second", "seconds" -> 1_000L
            "minute", "minutes" -> 60_000L
            "hour", "hours" -> 3_600_000L
            "day", "days" -> 86_400_000L
            else -> return null
        }
        val note = m.groupValues[3].trim()
        if (note.isEmpty()) return null
        return ReminderSpec(triggerAtMillis = nowMillis + amount * unitMillis, note = note)
    }

    private fun parseAbsoluteTodayId(text: String, nowMillis: Long): ReminderSpec? {
        val m = ABSOLUTE_TODAY_ID.matchEntire(text) ?: return null
        val hour = m.groupValues[1].toIntOrNull() ?: return null
        val minute = m.groupValues[2].toIntOrNull() ?: return null
        if (hour !in 0..23 || minute !in 0..59) return null
        val note = m.groupValues[3].trim()
        if (note.isEmpty()) return null
        val zone = ZoneId.systemDefault()
        val today = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()
        val time = LocalTime.of(hour, minute)
        var trigger = LocalDateTime.of(today, time).atZone(zone).toInstant().toEpochMilli()
        // Past time today rolls over to tomorrow.
        if (trigger <= nowMillis) {
            trigger = LocalDateTime.of(today.plusDays(1), time).atZone(zone).toInstant().toEpochMilli()
        }
        return ReminderSpec(triggerAtMillis = trigger, note = note)
    }

    private fun parseTomorrowId(text: String, nowMillis: Long): ReminderSpec? {
        val m = TOMORROW_ID.matchEntire(text) ?: return null
        val hour = m.groupValues[1].toIntOrNull() ?: return null
        val minute = m.groupValues[2].takeIf { it.isNotEmpty() }?.toIntOrNull() ?: 0
        if (hour !in 0..23 || minute !in 0..59) return null
        val note = m.groupValues[3].trim()
        if (note.isEmpty()) return null
        val zone = ZoneId.systemDefault()
        val tomorrow = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate().plusDays(1)
        val trigger = LocalDateTime.of(tomorrow, LocalTime.of(hour, minute))
            .atZone(zone).toInstant().toEpochMilli()
        return ReminderSpec(triggerAtMillis = trigger, note = note)
    }

    private fun parseTomorrowEn(text: String, nowMillis: Long): ReminderSpec? {
        val m = TOMORROW_EN.matchEntire(text) ?: return null
        var hour = m.groupValues[1].toIntOrNull() ?: return null
        val minute = m.groupValues[2].takeIf { it.isNotEmpty() }?.toIntOrNull() ?: 0
        when (m.groupValues[3].lowercase()) {
            "am" -> if (hour == 12) hour = 0
            "pm" -> if (hour < 12) hour += 12
        }
        if (hour !in 0..23 || minute !in 0..59) return null
        val note = m.groupValues[4].trim()
        if (note.isEmpty()) return null
        val zone = ZoneId.systemDefault()
        val daysToAdd = if (hour == 0) 2L else 1L
        val targetDate = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate().plusDays(daysToAdd)
        val trigger = LocalDateTime.of(targetDate, LocalTime.of(hour, minute))
            .atZone(zone).toInstant().toEpochMilli()
        return ReminderSpec(triggerAtMillis = trigger, note = note)
    }
}
