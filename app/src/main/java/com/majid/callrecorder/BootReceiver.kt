package com.majid.callrecorder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * After a reboot the microphone service cannot be started from the background (Android 14+),
 * so we only post a notification; tapping it opens the app, which restarts the monitor.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED && Prefs(context).autoRecord) {
            Notifs.postResumeReminder(context)
        }
    }
}
