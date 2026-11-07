package com.majid.callrecorder

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.ContentResolver
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.CallLog
import android.telephony.PhoneStateListener
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

/**
 * Long-running foreground service (type: microphone). It is started while the app is visible,
 * so Android 14+ lets it keep microphone access in the background. It listens for call state
 * and records from inside itself, instead of starting a new service from a broadcast.
 */
class CallMonitorService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private var tm: TelephonyManager? = null
    private var callback: Any? = null
    private var legacyListener: PhoneStateListener? = null

    private var lastState = TelephonyManager.CALL_STATE_IDLE
    private var ringSeen = false

    private var recorder: MediaRecorder? = null
    private var outFile: File? = null
    private var sources: IntArray = intArrayOf()
    private var sourceIndex = 0
    private var activeSource = -1
    private var peak = 0
    private var startedAtElapsed = 0L
    private var sourceStartedAt = 0L
    private var startedAtWall = 0L
    private val trace = StringBuilder()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP_REC -> {
                stopRecording()
                return START_STICKY
            }
            ACTION_STOP_ALL -> {
                Prefs(this).autoRecord = false
                stopSelf()
                return START_NOT_STICKY
            }
        }
        if (!enterForeground()) {
            stopSelf()
            return START_NOT_STICKY
        }
        running = true
        Notifs.clearResumeReminder(this)
        registerCallListener()
        return START_STICKY
    }

    private fun enterForeground(): Boolean = try {
        val n = buildNotification()
        if (Build.VERSION.SDK_INT >= 30) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIF_ID, n)
        }
        true
    } catch (e: Exception) {
        Log.e(TAG, "startForeground failed", e)
        Notifs.postResumeReminder(this)
        false
    }

    // ---------- call state ----------

    @SuppressLint("MissingPermission")
    private fun registerCallListener() {
        if (callback != null || legacyListener != null) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "READ_PHONE_STATE not granted")
            return
        }
        val t = getSystemService(TelephonyManager::class.java)
        tm = t
        if (Build.VERSION.SDK_INT >= 31) {
            val cb = StateCallback()
            t.registerTelephonyCallback(mainExecutor, cb)
            callback = cb
        } else {
            @Suppress("DEPRECATION")
            val l = object : PhoneStateListener() {
                @Deprecated("Deprecated in Java")
                override fun onCallStateChanged(state: Int, phoneNumber: String?) {
                    handleState(state)
                }
            }
            @Suppress("DEPRECATION")
            t.listen(l, PhoneStateListener.LISTEN_CALL_STATE)
            legacyListener = l
        }
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private inner class StateCallback : TelephonyCallback(), TelephonyCallback.CallStateListener {
        override fun onCallStateChanged(state: Int) {
            handleState(state)
        }
    }

    private fun handleState(state: Int) {
        val prev = lastState
        lastState = state
        when (state) {
            TelephonyManager.CALL_STATE_RINGING -> ringSeen = true
            TelephonyManager.CALL_STATE_OFFHOOK -> {
                if (prev != TelephonyManager.CALL_STATE_OFFHOOK &&
                    recorder == null &&
                    Prefs(this).autoRecord
                ) {
                    startRecording(incomingGuess = ringSeen)
                }
            }
            TelephonyManager.CALL_STATE_IDLE -> {
                if (prev != TelephonyManager.CALL_STATE_IDLE) stopRecording()
                ringSeen = false
            }
        }
    }

    // ---------- recording ----------

    private fun startRecording(incomingGuess: Boolean) {
        val prefs = Prefs(this)
        sources = prefs.audioSource.ids
        trace.setLength(0)
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        outFile = File(
            RecordingStore.dir(this),
            "${stamp}_${if (incomingGuess) "in" else "out"}_unknown.m4a"
        )
        startedAtWall = System.currentTimeMillis()
        startedAtElapsed = SystemClock.elapsedRealtime()
        if (!beginWithSource(0)) {
            outFile = null
            prefs.lastDiag = "هیچ منبع صدایی شروع نشد: $trace"
            return
        }
        refreshNotification()
        handler.removeCallbacks(poll)
        handler.postDelayed(poll, 1000)
    }

    private fun beginWithSource(from: Int): Boolean {
        val f = outFile ?: return false
        val q = Prefs(this).quality
        for (i in from until sources.size) {
            val src = sources[i]
            val r = newRecorder()
            try {
                r.setAudioSource(src)
                r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                r.setAudioChannels(1)
                r.setAudioSamplingRate(q.sampleRate)
                r.setAudioEncodingBitRate(q.bitRate)
                r.setOutputFile(f.absolutePath)
                r.prepare()
                r.start()
                recorder = r
                sourceIndex = i
                activeSource = src
                peak = 0
                sourceStartedAt = SystemClock.elapsedRealtime()
                Log.i(TAG, "Recording with ${sourceName(src)} -> ${f.name}")
                return true
            } catch (e: Exception) {
                Log.w(TAG, "Source ${sourceName(src)} failed: ${e.message}")
                trace.append("${sourceName(src)}: خطا؛ ")
                try { r.release() } catch (_: Exception) {}
                f.delete()
            }
        }
        return false
    }

    /** Every second: track peak amplitude; if a source stays completely silent, try the next one. */
    private val poll = object : Runnable {
        override fun run() {
            val r = recorder ?: return
            try { peak = maxOf(peak, r.maxAmplitude) } catch (_: Exception) {}
            val silentFor = SystemClock.elapsedRealtime() - sourceStartedAt
            if (peak == 0 &&
                silentFor >= SILENCE_CHECK_MS &&
                sourceIndex + 1 < sources.size &&
                Prefs(this@CallMonitorService).audioSource == AudioSrc.AUTO
            ) {
                switchToNextSource()
            }
            if (recorder != null) handler.postDelayed(this, 1000)
        }
    }

    private fun switchToNextSource() {
        val old = recorder
        recorder = null
        trace.append("${sourceName(activeSource)}: بی‌صدا؛ ")
        try { old?.stop() } catch (_: Exception) {}
        try { old?.release() } catch (_: Exception) {}
        outFile?.delete()
        if (!beginWithSource(sourceIndex + 1)) {
            Prefs(this).lastDiag = "همه‌ی منابع بی‌صدا یا ناموفق بودند: $trace"
            outFile = null
        }
        refreshNotification()
    }

    private fun stopRecording() {
        handler.removeCallbacks(poll)
        val r = recorder
        if (r == null) {
            refreshNotification()
            return
        }
        recorder = null
        val f = outFile
        outFile = null
        try { peak = maxOf(peak, r.maxAmplitude) } catch (_: Exception) {}
        var ok = true
        try { r.stop() } catch (e: RuntimeException) { ok = false }
        try { r.release() } catch (_: Exception) {}
        val durationMs = SystemClock.elapsedRealtime() - startedAtElapsed

        Prefs(this).lastDiag =
            "${trace}منبع نهایی: ${sourceName(activeSource)} | بیشینه دامنه: $peak | مدت: ${durationMs / 1000} ثانیه"

        if (f != null) {
            if (!ok || durationMs < MIN_DURATION_MS) {
                f.delete()
            } else {
                val wallStart = startedAtWall
                handler.postDelayed({ thread { finalizeFromCallLog(f, wallStart) } }, 2500)
            }
        }
        refreshNotification()
    }

    private class CallInfo(val number: String, val type: Int, val date: Long, val durationSec: Long)

    private fun lastCall(wallStart: Long): CallInfo? = try {
        val args = Bundle().apply {
            putString(ContentResolver.QUERY_ARG_SQL_SORT_ORDER, "${CallLog.Calls.DATE} DESC")
            putInt(ContentResolver.QUERY_ARG_LIMIT, 1)
        }
        contentResolver.query(
            CallLog.Calls.CONTENT_URI,
            arrayOf(
                CallLog.Calls.NUMBER, CallLog.Calls.TYPE,
                CallLog.Calls.DATE, CallLog.Calls.DURATION
            ),
            args,
            null
        )?.use { c ->
            if (!c.moveToFirst()) {
                null
            } else {
                val date = c.getLong(2)
                if (date < wallStart - 60_000) null
                else CallInfo(c.getString(0).orEmpty(), c.getInt(1), date, c.getLong(3))
            }
        }
    } catch (e: Exception) {
        Log.w(TAG, "Call log lookup failed: ${e.message}")
        null
    }

    /**
     * Runs after the call ends (the call log is written a few seconds later).
     * - fills in number and direction in the file name;
     * - for outgoing calls, cuts away the ringback tone: only the last "talk time" seconds
     *   (taken from the call log) are kept; a call nobody answered is deleted.
     */
    private fun finalizeFromCallLog(f: File, wallStart: Long) {
        if (!f.exists()) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CALL_LOG)
            != PackageManager.PERMISSION_GRANTED
        ) return

        var info: CallInfo? = null
        for (attempt in 0 until 5) {
            info = lastCall(wallStart)
            if (info != null) break
            try { Thread.sleep(3000) } catch (_: InterruptedException) { return }
        }
        val call = info ?: return
        if (!f.exists()) return

        val outgoing = call.type == CallLog.Calls.OUTGOING_TYPE
        if (outgoing && Prefs(this).trimRingback) {
            if (call.durationSec <= 0) {
                f.delete() // never answered: only ringback tone was recorded
                return
            }
            val trimmed = AudioTrim.keepLast(f, call.durationSec * 1000)
            Log.i(TAG, "Trim to ${call.durationSec}s: $trimmed")
        }

        val dir = if (outgoing) "out" else "in"
        val clean = call.number.filter { it.isDigit() || it == '+' }.ifEmpty { "unknown" }
        val target = File(f.parentFile, "${f.name.take(15)}_${dir}_$clean.m4a")
        if (!target.exists()) f.renameTo(target)
    }

    @Suppress("DEPRECATION")
    private fun newRecorder(): MediaRecorder =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(this) else MediaRecorder()

    // ---------- notification ----------

    private fun pending(action: String, code: Int): PendingIntent = PendingIntent.getService(
        this, code,
        Intent(this, CallMonitorService::class.java).setAction(action),
        PendingIntent.FLAG_IMMUTABLE
    )

    private fun buildNotification(): Notification {
        Notifs.ensureChannels(this)
        val recording = recorder != null
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val b = NotificationCompat.Builder(this, Notifs.CH_MONITOR)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(if (recording) "در حال ضبط تماس" else "پایش تماس فعال است")
            .setContentText(
                if (recording) "ضبط تماس در جریان است"
                else "با شروع تماس، ضبط خودکار انجام می‌شود"
            )
            .setOngoing(true)
            .setContentIntent(open)
        if (recording) b.addAction(0, "توقف ضبط", pending(ACTION_STOP_REC, 1))
        b.addAction(0, "خاموش کردن", pending(ACTION_STOP_ALL, 2))
        return b.build()
    }

    private fun refreshNotification() {
        try {
            getSystemService(android.app.NotificationManager::class.java)
                .notify(NOTIF_ID, buildNotification())
        } catch (_: Exception) {
        }
    }

    override fun onDestroy() {
        stopRecording()
        try {
            if (Build.VERSION.SDK_INT >= 31) {
                (callback as? TelephonyCallback)?.let { tm?.unregisterTelephonyCallback(it) }
            } else {
                @Suppress("DEPRECATION")
                legacyListener?.let { tm?.listen(it, PhoneStateListener.LISTEN_NONE) }
            }
        } catch (_: Exception) {
        }
        callback = null
        legacyListener = null
        running = false
        super.onDestroy()
    }

    companion object {
        const val ACTION_STOP_REC = "com.majid.callrecorder.STOP_REC"
        const val ACTION_STOP_ALL = "com.majid.callrecorder.STOP_ALL"
        private const val NOTIF_ID = 1001
        private const val MIN_DURATION_MS = 2_000L
        private const val SILENCE_CHECK_MS = 4_000L
        private const val TAG = "CallMonitor"

        @Volatile
        var running = false
            private set

        private fun sourceName(id: Int): String = when (id) {
            MediaRecorder.AudioSource.VOICE_CALL -> "VOICE_CALL"
            MediaRecorder.AudioSource.VOICE_RECOGNITION -> "VOICE_RECOGNITION"
            MediaRecorder.AudioSource.MIC -> "MIC"
            MediaRecorder.AudioSource.VOICE_COMMUNICATION -> "VOICE_COMMUNICATION"
            else -> id.toString()
        }
    }
}
