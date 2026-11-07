package com.majid.callrecorder

import android.content.Context
import android.media.MediaMetadataRetriever
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale

data class Recording(
    val file: File,
    val dateMillis: Long,
    val number: String,
    val incoming: Boolean,
    val durationMs: Long
)

object RecordingStore {

    private val NAME = Regex("""(\d{8}_\d{6})_(in|out)_(.*)\.m4a""")

    fun dir(ctx: Context): File {
        val base = ctx.getExternalFilesDir("recordings") ?: File(ctx.filesDir, "recordings")
        if (!base.exists()) base.mkdirs()
        return base
    }

    fun list(ctx: Context): List<Recording> {
        val files = dir(ctx).listFiles { f -> f.isFile && f.extension == "m4a" } ?: return emptyList()
        val fmt = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
        return files.map { f ->
            val m = NAME.matchEntire(f.name)
            val date = m?.let { runCatching { fmt.parse(it.groupValues[1])?.time }.getOrNull() }
                ?: f.lastModified()
            Recording(
                file = f,
                dateMillis = date,
                number = m?.groupValues?.get(3) ?: "unknown",
                incoming = m?.groupValues?.get(2) != "out",
                durationMs = duration(f)
            )
        }.sortedByDescending { it.dateMillis }
    }

    private fun duration(f: File): Long = try {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(f.absolutePath)
            r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        } finally {
            r.release()
        }
    } catch (e: Exception) {
        0L
    }
}
