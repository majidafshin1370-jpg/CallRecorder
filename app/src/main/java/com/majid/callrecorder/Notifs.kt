package com.majid.callrecorder

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat

object Notifs {
    const val CH_MONITOR = "monitor"
    const val CH_REMINDER = "reminder"
    private const val REMINDER_ID = 2002

    fun ensureChannels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CH_MONITOR) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CH_MONITOR, "پایش و ضبط تماس", NotificationManager.IMPORTANCE_LOW)
            )
        }
        if (nm.getNotificationChannel(CH_REMINDER) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CH_REMINDER, "یادآوری فعال‌سازی", NotificationManager.IMPORTANCE_DEFAULT)
            )
        }
    }

    /** Android 14+ does not allow starting a microphone service from the background, so the user must tap this. */
    fun postResumeReminder(ctx: Context) {
        ensureChannels(ctx)
        val open = PendingIntent.getActivity(
            ctx, 2,
            Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE
        )
        val n = NotificationCompat.Builder(ctx, CH_REMINDER)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("ضبط خودکار تماس خاموش است")
            .setContentText("برای فعال شدن دوباره، اینجا را بزنید")
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        try {
            ctx.getSystemService(NotificationManager::class.java).notify(REMINDER_ID, n)
        } catch (_: Exception) {
        }
    }

    fun clearResumeReminder(ctx: Context) {
        try {
            ctx.getSystemService(NotificationManager::class.java).cancel(REMINDER_ID)
        } catch (_: Exception) {
        }
    }
}
