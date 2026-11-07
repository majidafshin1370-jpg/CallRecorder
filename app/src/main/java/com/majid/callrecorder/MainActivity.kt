package com.majid.callrecorder

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(
                colorScheme = lightColorScheme(
                    primary = Color(0xFFC62828),
                    secondary = Color(0xFF1E293B)
                )
            ) {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    CallRecorderApp()
                }
            }
        }
    }
}

// ---------- helpers ----------

private fun requiredPermissions(): Array<String> {
    val l = mutableListOf(
        Manifest.permission.RECORD_AUDIO,
        Manifest.permission.READ_PHONE_STATE,
        Manifest.permission.READ_CALL_LOG
    )
    if (Build.VERSION.SDK_INT >= 33) l += Manifest.permission.POST_NOTIFICATIONS
    return l.toTypedArray()
}

private fun hasCorePermissions(ctx: Context): Boolean =
    listOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.READ_PHONE_STATE).all {
        ContextCompat.checkSelfPermission(ctx, it) == PackageManager.PERMISSION_GRANTED
    }

/** Must be called while the app is visible: Android 14+ only allows starting a microphone service then. */
private fun startMonitor(ctx: Context) {
    try {
        ContextCompat.startForegroundService(ctx, Intent(ctx, CallMonitorService::class.java))
    } catch (e: Exception) {
        // Not allowed right now; the user can toggle the switch again while the app is open.
    }
}

private fun stopMonitor(ctx: Context) {
    ctx.stopService(Intent(ctx, CallMonitorService::class.java))
}

private fun isAccessibilityOn(ctx: Context): Boolean {
    val enabled = Settings.Secure.getString(
        ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
    ) ?: return false
    val cn = ComponentName(ctx, CallAccessibilityService::class.java)
    return enabled.split(':').any {
        it.equals(cn.flattenToString(), true) || it.equals(cn.flattenToShortString(), true)
    }
}

private val faFormat by lazy {
    android.icu.text.SimpleDateFormat(
        "yyyy/MM/dd - HH:mm",
        android.icu.util.ULocale("fa_IR@calendar=persian")
    )
}

private fun fmtDate(ms: Long): String = faFormat.format(Date(ms))

private fun fmtDur(ms: Long): String {
    val s = ms / 1000
    return String.format(Locale.US, "%02d:%02d", s / 60, s % 60)
}

private fun shareRecording(ctx: Context, rec: Recording) {
    val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", rec.file)
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "audio/mp4"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    ctx.startActivity(Intent.createChooser(send, "اشتراک‌گذاری ضبط"))
}

// ---------- UI ----------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CallRecorderApp() {
    val ctx = LocalContext.current
    val prefs = remember { Prefs(ctx) }

    var autoRecord by remember { mutableStateOf(prefs.autoRecord) }
    var trimRingback by remember { mutableStateOf(prefs.trimRingback) }
    var quality by remember { mutableStateOf(prefs.quality) }
    var source by remember { mutableStateOf(prefs.audioSource) }
    var tick by remember { mutableIntStateOf(0) }
    var recordings by remember { mutableStateOf<List<Recording>>(emptyList()) }
    var granted by remember { mutableStateOf(hasCorePermissions(ctx)) }
    var a11yOn by remember { mutableStateOf(isAccessibilityOn(ctx)) }
    var monitoring by remember { mutableStateOf(CallMonitorService.running) }
    var diag by remember { mutableStateOf(prefs.lastDiag) }
    var playingPath by remember { mutableStateOf<String?>(null) }
    var player by remember { mutableStateOf<MediaPlayer?>(null) }
    var toDelete by remember { mutableStateOf<Recording?>(null) }

    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        granted = hasCorePermissions(ctx)
        if (granted && prefs.autoRecord) startMonitor(ctx)
    }

    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_RESUME) {
                tick++
                granted = hasCorePermissions(ctx)
                a11yOn = isAccessibilityOn(ctx)
                if (granted && prefs.autoRecord) startMonitor(ctx)
            }
        }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
    DisposableEffect(Unit) { onDispose { player?.release() } }

    LaunchedEffect(tick) {
        recordings = withContext(Dispatchers.IO) { RecordingStore.list(ctx) }
    }
    LaunchedEffect(Unit) {
        while (true) {
            monitoring = CallMonitorService.running
            diag = prefs.lastDiag
            delay(1000)
        }
    }

    fun stopPlayback() {
        player?.release()
        player = null
        playingPath = null
    }

    fun togglePlay(rec: Recording) {
        val same = playingPath == rec.file.absolutePath
        stopPlayback()
        if (same) return
        try {
            val mp = MediaPlayer()
            mp.setDataSource(rec.file.absolutePath)
            mp.setOnCompletionListener { stopPlayback() }
            mp.prepare()
            mp.start()
            player = mp
            playingPath = rec.file.absolutePath
        } catch (e: Exception) {
            stopPlayback()
        }
    }

    Scaffold(topBar = { TopAppBar(title = { Text("ضبط تماس") }) }) { pad ->
        LazyColumn(
            modifier = Modifier
                .padding(pad)
                .fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Permissions
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = if (granted) MaterialTheme.colorScheme.surfaceVariant
                        else MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                if (granted) Icons.Default.CheckCircle else Icons.Default.Warning,
                                contentDescription = null
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                if (granted) "مجوزهای لازم داده شده است"
                                else "برای ضبط تماس، مجوزها را بدهید",
                                fontWeight = FontWeight.Bold
                            )
                        }
                        if (!granted) {
                            Button(onClick = { permLauncher.launch(requiredPermissions()) }) {
                                Text("اعطای مجوزها")
                            }
                        }
                    }
                }
            }

            // Main settings
            item {
                SectionCard("تنظیمات") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("ضبط خودکار تماس‌ها")
                            Text(
                                if (monitoring) "پایش تماس: فعال ✓" else "پایش تماس: خاموش",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        Switch(checked = autoRecord, onCheckedChange = {
                            autoRecord = it
                            prefs.autoRecord = it
                            if (it) {
                                if (granted) startMonitor(ctx)
                            } else {
                                stopMonitor(ctx)
                            }
                        })
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("حذف آهنگ پیشواز در تماس خروجی")
                            Text(
                                "فقط از لحظه‌ی جواب دادن مخاطب نگه داشته می‌شود؛ تماس بی‌پاسخ پاک می‌شود.",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        Switch(checked = trimRingback, onCheckedChange = {
                            trimRingback = it
                            prefs.trimRingback = it
                        })
                    }
                    Text("کیفیت ضبط", fontWeight = FontWeight.Bold)
                    Quality.values().forEach { q ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { quality = q; prefs.quality = q },
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(selected = quality == q, onClick = { quality = q; prefs.quality = q })
                            Text(q.title)
                        }
                    }
                    Text("منبع صدا", fontWeight = FontWeight.Bold)
                    AudioSrc.values().forEach { s ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { source = s; prefs.audioSource = s },
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(selected = source == s, onClick = { source = s; prefs.audioSource = s })
                            Text(s.title)
                        }
                    }
                }
            }

            // Diagnostics
            item {
                SectionCard("گزارش آخرین ضبط") {
                    Text(
                        if (diag.isEmpty()) "هنوز تماسی ضبط نشده است." else diag,
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        "اگر «بیشینه دامنه» صفر باشد، سیستم صدا را به برنامه نداده و فایل بی‌صدا است. " +
                            "در حالت خودکار، برنامه پس از ۴ ثانیه سکوت، منبع بعدی را امتحان می‌کند.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }

            // Reliability
            item {
                SectionCard("پایداری ضبط") {
                    Text(
                        "برای اینکه سیستم برنامه را نبندد: تنظیمات ← باتری ← راه‌اندازی برنامه (App launch) را باز کنید، " +
                            "این برنامه را روی «مدیریت دستی» بگذارید و هر سه گزینه (راه‌اندازی خودکار، راه‌اندازی ثانویه، " +
                            "اجرا در پس‌زمینه) را روشن کنید. نام منوها در نسخه‌ی شما ممکن است کمی فرق کند.\n" +
                            "بعد از ریستارت گوشی، روی اعلان «ضبط خودکار تماس خاموش است» بزنید یا یک‌بار برنامه را باز کنید.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    OutlinedButton(onClick = {
                        ctx.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                    }) {
                        Text("استثنا کردن برنامه از بهینه‌سازی باتری")
                    }
                    Text(
                        "سرویس دسترس‌پذیری (اختیاری): در برخی گوشی‌ها ممکن است دسترسی به میکروفون در تماس را " +
                            "پایدارتر کند. هیچ اطلاعاتی از صفحه خوانده نمی‌شود.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    OutlinedButton(onClick = {
                        ctx.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    }) {
                        Text(if (a11yOn) "فعال است ✓  (تنظیمات)" else "فعال‌سازی در تنظیمات")
                    }
                }
            }

            // Notes
            item {
                SectionCard("نکات مهم") {
                    Text(
                        "• در اندروید جدید، سیستم ممکن است صدای طرف مقابل را به برنامه‌های عادی ندهد. " +
                            "در این صورت معمولاً فقط صدای شما ضبط می‌شود؛ تماس را روی بلندگو بگذارید.\n" +
                            "• هنگام ضبط، یک اعلان دائمی نمایش داده می‌شود.\n" +
                            "• ضبط مکالمه بدون اطلاع طرف مقابل در بسیاری از کشورها و شرایط ممنوع است؛ " +
                            "مسئولیت استفاده با شماست.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }

            item {
                Text(
                    "ضبط‌های من (${recordings.size})",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }
            if (recordings.isEmpty()) {
                item { Text("هنوز ضبطی وجود ندارد.", style = MaterialTheme.typography.bodyMedium) }
            }
            items(recordings, key = { it.file.absolutePath }) { rec ->
                RecordingRow(
                    rec = rec,
                    playing = playingPath == rec.file.absolutePath,
                    onPlay = { togglePlay(rec) },
                    onShare = { shareRecording(ctx, rec) },
                    onDelete = { toDelete = rec }
                )
            }
        }
    }

    toDelete?.let { rec ->
        AlertDialog(
            onDismissRequest = { toDelete = null },
            title = { Text("حذف ضبط؟") },
            text = { Text("این فایل برای همیشه پاک می‌شود.") },
            confirmButton = {
                TextButton(onClick = {
                    if (playingPath == rec.file.absolutePath) stopPlayback()
                    rec.file.delete()
                    toDelete = null
                    tick++
                }) { Text("حذف") }
            },
            dismissButton = { TextButton(onClick = { toDelete = null }) { Text("انصراف") } }
        )
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            content()
        }
    }
}

@Composable
private fun RecordingRow(
    rec: Recording,
    playing: Boolean,
    onPlay: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Call, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        if (rec.number == "unknown") "شماره نامشخص" else "⁦${rec.number}⁩",
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        "${if (rec.incoming) "ورودی" else "خروجی"} • ${fmtDate(rec.dateMillis)} • ${fmtDur(rec.durationMs)}",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onPlay) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null)
                    Spacer(Modifier.width(4.dp))
                    Text(if (playing) "توقف" else "پخش")
                }
                IconButton(onClick = onShare) { Icon(Icons.Default.Share, contentDescription = "اشتراک‌گذاری") }
                IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, contentDescription = "حذف") }
            }
        }
    }
}
