package com.raikaai.app.reminder

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

/**
 * Worker that posts the reminder notification when the trigger time arrives.
 *
 * Scheduled via [scheduleReminder]; never enqueue directly.
 */
class ReminderWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val note = inputData.getString(KEY_NOTE)?.trim().orEmpty()
        if (note.isEmpty()) return Result.failure()
        showReminderNotification(applicationContext, note)
        return Result.success()
    }
}
