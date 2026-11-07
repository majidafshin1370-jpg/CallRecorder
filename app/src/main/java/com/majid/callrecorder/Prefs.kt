package com.majid.callrecorder

import android.content.Context
import android.media.MediaRecorder

enum class Quality(val title: String, val sampleRate: Int, val bitRate: Int) {
    STANDARD("استاندارد (۲۲ کیلوهرتز / ۶۴ kbps)", 22050, 64_000),
    HD("HD (۴۴.۱ کیلوهرتز / ۱۲۸ kbps)", 44100, 128_000),
    ULTRA("HD+ (۴۸ کیلوهرتز / ۲۵۶ kbps)", 48000, 256_000)
}

/** Which microphone/call source to record from. AUTO tries several and switches if one is silent. */
enum class AudioSrc(val title: String, val ids: IntArray) {
    AUTO(
        "خودکار (امتحان چند منبع)",
        intArrayOf(
            MediaRecorder.AudioSource.VOICE_CALL,
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            MediaRecorder.AudioSource.MIC,
            MediaRecorder.AudioSource.VOICE_COMMUNICATION
        )
    ),
    VOICE_CALL_ONLY("VOICE_CALL (هر دو طرف؛ معمولاً مسدود)", intArrayOf(MediaRecorder.AudioSource.VOICE_CALL)),
    RECOGNITION("VOICE_RECOGNITION", intArrayOf(MediaRecorder.AudioSource.VOICE_RECOGNITION)),
    MIC_ONLY("MIC (میکروفون)", intArrayOf(MediaRecorder.AudioSource.MIC)),
    COMMUNICATION("VOICE_COMMUNICATION", intArrayOf(MediaRecorder.AudioSource.VOICE_COMMUNICATION))
}

class Prefs(context: Context) {
    private val sp = context.applicationContext
        .getSharedPreferences("settings", Context.MODE_PRIVATE)

    var autoRecord: Boolean
        get() = sp.getBoolean("auto", true)
        set(v) = sp.edit().putBoolean("auto", v).apply()

    var quality: Quality
        get() = runCatching { Quality.valueOf(sp.getString("quality", Quality.HD.name)!!) }
            .getOrDefault(Quality.HD)
        set(v) = sp.edit().putString("quality", v.name).apply()

    var audioSource: AudioSrc
        get() = runCatching { AudioSrc.valueOf(sp.getString("source", AudioSrc.AUTO.name)!!) }
            .getOrDefault(AudioSrc.AUTO)
        set(v) = sp.edit().putString("source", v.name).apply()

    /** Short diagnostic text about the last recording (which source worked, peak amplitude). */
    var lastDiag: String
        get() = sp.getString("last_diag", "") ?: ""
        set(v) = sp.edit().putString("last_diag", v).apply()
}
