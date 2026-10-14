package com.raikaai.app.reminder

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.raikaai.app.MainActivity
import com.raikaai.app.R
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Notification channel ID for scheduled reminders. */
const val REMINDER_CHANNEL_ID = "reminders"

/** WorkManager tag for all reminder jobs. */
const val REMINDER_WORK_TAG = "raikaai_reminder"

internal const val KEY_NOTE = "note"

/**
 * Schedules a one-shot reminder that posts a notification when
 * [ReminderSpec.triggerAtMillis] arrives.
 *
 * The [android.Manifest.permission.POST_NOTIFICATIONS] permission (Android 13+)
 * must be requested and granted by the caller, otherwise the notification
 * is silently dropped by the system.
 *
 * @return the created WorkRequest UUID; used for individual cancellation via [cancelReminder].
 */
fun scheduleReminder(context: Context, spec: ReminderSpec): UUID {
    val appContext = context.applicationContext
    val delayMillis = (spec.triggerAtMillis - System.currentTimeMillis()).coerceAtLeast(0L)
    val request = OneTimeWorkRequestBuilder<ReminderWorker>()
        .setInitialDelay(delayMillis, TimeUnit.MILLISECONDS)
        .addTag(REMINDER_WORK_TAG)
        .setInputData(workDataOf(KEY_NOTE to spec.note))
        .build()
    WorkManager.getInstance(appContext).enqueue(request)
    return request.id
}

/**
 * Cancels a single scheduled reminder by the UUID returned from
 * [scheduleReminder].
 */
fun cancelReminder(context: Context, id: UUID) {
    WorkManager.getInstance(context.applicationContext).cancelWorkById(id)
}

/** Cancels all scheduled reminders. */
fun cancelAllReminders(context: Context) {
    WorkManager.getInstance(context.applicationContext).cancelAllWorkByTag(REMINDER_WORK_TAG)
}

internal fun showReminderNotification(context: Context, note: String) {
    val notificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        val channel = NotificationChannel(
            REMINDER_CHANNEL_ID,
            "Reminder",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply { description = "Scheduled reminder from RaikaAI" }
        notificationManager.createNotificationChannel(channel)
    }
    val openIntent = Intent(context, MainActivity::class.java).apply {
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
    }
    val openPendingIntent = PendingIntent.getActivity(
        context,
        0,
        openIntent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
    val notification = NotificationCompat.Builder(context, REMINDER_CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_reminder)
        .setContentTitle("RaikaAI Reminder")
        .setContentText(note)
        .setStyle(NotificationCompat.BigTextStyle().bigText(note))
        .setPriority(NotificationCompat.PRIORITY_HIGH)
        .setAutoCancel(true)
        .setContentIntent(openPendingIntent)
        .addAction(R.drawable.ic_reminder, "Open app", openPendingIntent)
        .build()
    // Unique ID per notification so concurrent reminders don't overwrite each other.
    val notificationId = (System.currentTimeMillis() % Int.MAX_VALUE).toInt()
    notificationManager.notify(notificationId, notification)
}
