package com.pocketboot.app

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.content.ContextCompat
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.zip.ZipInputStream
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.sync.Semaphore
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicLong

enum class ChecksumVerifyStatus {
    NONE,
    VERIFIED_MATCH,
    COMPUTED_ONLY,
    MISMATCH
}

data class DownloadCompletionEvent(
    val title: String,
    val fileName: String,
    val savedPath: String,
    val injectedMultiBootPath: String?,
    val computedSha256: String?,
    val expectedSha256: String?,
    val checksumStatus: ChecksumVerifyStatus,
    val autoMount: Boolean,
    val buildBenchmark: String? = null,
    val bootVerifiedLabel: String? = null
)

data class PendingResumableDownload(
    val metaFilePath: String,
    val partFilePath: String,
    val title: String,
    val fileName: String,
    val url: String,
    val targetDir: String,
    val targetMultiBootPath: String,
    val downloadedBytes: Long,
    val totalBytes: Long,
    val expectedSha256: String,
    val autoMount: Boolean
) {
    val progress: Float
        get() = if (totalBytes > 0L) (downloadedBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f) else 0f
}

data class TurboDownloadState(
    val title: String,
    val fileName: String,
    val url: String,
    val targetDir: String,
    val targetMultiBootPath: String = "",
    val downloadedBytes: Long = 0L,
    val totalBytes: Long = 0L,
    val speedBytesPerSec: Long = 0L,
    val etaSeconds: Long = -1L,
    val activeThreads: Int = 1,
    val supportsRange: Boolean = false,
    val isPaused: Boolean = false,
    val isVerifying: Boolean = false,
    val isInjectingMultiBoot: Boolean = false,
    val statusText: String = "Connecting..."
) {
    val progress: Float
        get() = if (totalBytes > 0L) (downloadedBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f) else 0f
}

class TurboDownloadService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notif = NotificationHelper.buildDownloadNotification(this, FastIsoDownloader.state.value)
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NotificationHelper.DL_NOTIF_ID,
                    notif,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                )
            } else {
                startForeground(NotificationHelper.DL_NOTIF_ID, notif)
            }
        }
        acquireLocks()
        return START_NOT_STICKY
    }

    private fun acquireLocks() {
        runCatching {
            if (wakeLock == null) {
                val pm = getSystemService(PowerManager::class.java)
                wakeLock = pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PocketBoot::TurboDownload")?.apply {
                    setReferenceCounted(false)
                }
            }
            if (wakeLock?.isHeld == false) wakeLock?.acquire()
        }
        runCatching {
            if (wifiLock == null) {
                val wm = applicationContext.getSystemService(WifiManager::class.java)
                val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    WifiManager.WIFI_MODE_FULL_LOW_LATENCY
                } else {
                    @Suppress("DEPRECATION")
                    WifiManager.WIFI_MODE_FULL_HIGH_PERF
                }
                wifiLock = wm?.createWifiLock(mode, "PocketBoot::TurboWifi")?.apply {
                    setReferenceCounted(false)
                }
            }
            if (wifiLock?.isHeld == false) wifiLock?.acquire()
        }
    }

    private fun releaseLocks() {
        runCatching { if (wakeLock?.isHeld == true) wakeLock?.release() }
        runCatching { if (wifiLock?.isHeld == true) wifiLock?.release() }
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        FastIsoDownloader.pause()
        releaseLocks()
        stopSelf(startId)
    }

    override fun onDestroy() {
        releaseLocks()
        super.onDestroy()
    }

    companion object {
        fun start(context: Context) {
            runCatching {
                ContextCompat.startForegroundService(context, Intent(context, TurboDownloadService::class.java))
            }
        }

        fun stop(context: Context) {
            runCatching {
                context.stopService(Intent(context, TurboDownloadService::class.java))
            }
        }
    }
}

object FastIsoDownloader {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow<TurboDownloadState?>(null)
    val state: StateFlow<TurboDownloadState?> = _state.asStateFlow()

    private val _lastCompletion = MutableStateFlow<DownloadCompletionEvent?>(null)
    val lastCompletion: StateFlow<DownloadCompletionEvent?> = _lastCompletion.asStateFlow()

    private var currentJob: Job? = null
    private val pausedFlag = AtomicBoolean(false)
    private val cancelledFlag = AtomicBoolean(false)

    private var chunkStarts: LongArray = LongArray(0)
    private var chunkEnds: LongArray = LongArray(0)
    private var chunkCursors: LongArray = LongArray(0)
    private var stagingFile: File? = null
    private var sidecarFile: File? = null
    private var cachedFinalUrl: String = ""
    private var cachedTotalBytes: Long = 0L
    private var cachedThreads: Int = 8
    private var cachedAutoMount: Boolean = true
    private var cachedAutoVerifySha256: Boolean = true
    private var cachedExpectedSha256: String = ""
    private var cachedTargetMultiBootPath: String = ""
    private var cachedOnComplete: ((DownloadCompletionEvent) -> Unit)? = null
    private var cachedOnError: ((String) -> Unit)? = null
    private var cachedOnLog: ((LogEntry) -> Unit)? = null
    @Volatile private var cachedUserAgent: String = BROWSER_UA
    private const val BROWSER_UA = "Mozilla/5.0 (Linux; Android 15) PocketBoot/4.6.4"
    private const val WGET_UA = "Wget/1.21.4"
    private const val CURL_UA = "curl/8.5.0"
    private fun logDl(msg: String, isError: Boolean = false) { cachedOnLog?.invoke(LogEntry(SimpleDateFormat("HH:mm:ss", Locale.US).format(Date()), "download", msg, isError)) }
    private var cachedContext: Context? = null

    fun publishExternalCompletion(event: DownloadCompletionEvent) {
        _lastCompletion.value = event
    }

    fun dismissLastCompletion() {
        _lastCompletion.value = null
    }

    fun formatSpeed(bytesPerSec: Long): String = when {
        bytesPerSec >= 1024L * 1024L -> String.format(Locale.US, "%.1f MB/s", bytesPerSec / (1024.0 * 1024.0))
        bytesPerSec >= 1024L -> String.format(Locale.US, "%.0f KB/s", bytesPerSec / 1024.0)
        bytesPerSec > 0L -> "$bytesPerSec B/s"
        else -> "0 KB/s"
    }

    fun formatEta(seconds: Long): String {
        if (seconds < 0L || seconds > 86_400L) return "--:--"
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        return if (h > 0) String.format(Locale.US, "%dh %02dm", h, m)
        else String.format(Locale.US, "%02d:%02d", m, s)
    }

    private fun stagingDir(context: Context): File {
        val baseDir = context.applicationContext.getExternalFilesDir(null) ?: context.applicationContext.cacheDir
        return File(baseDir, "turbo_dl").apply { mkdirs() }
    }

    fun scanPendingResumableDownloads(context: Context): List<PendingResumableDownload> {
        val dir = stagingDir(context)
        val activePartPath = stagingFile?.absolutePath
        return dir.listFiles { f -> f.isFile && f.name.endsWith(".pbmeta") }
            ?.mapNotNull { meta ->
                runCatching {
                    val obj = JSONObject(meta.readText(Charsets.UTF_8))
                    val partPath = obj.optString("partFilePath", "")
                    if (partPath.isBlank() || partPath == activePartPath) return@mapNotNull null
                    val partFile = File(partPath)
                    if (!partFile.exists()) {
                        meta.delete()
                        return@mapNotNull null
                    }
                    val starts = obj.optJSONArray("chunkStarts") ?: return@mapNotNull null
                    val cursors = obj.optJSONArray("chunkCursors") ?: return@mapNotNull null
                    var done = 0L
                    for (i in 0 until minOf(starts.length(), cursors.length())) {
                        done += (cursors.optLong(i) - starts.optLong(i)).coerceAtLeast(0L)
                    }
                    PendingResumableDownload(
                        metaFilePath = meta.absolutePath,
                        partFilePath = partPath,
                        title = obj.optString("title", partFile.name.removeSuffix(".part")),
                        fileName = obj.optString("fileName", partFile.name.removeSuffix(".part")),
                        url = obj.optString("url", ""),
                        targetDir = obj.optString("targetDir", "/data/media/0/Download"),
                        targetMultiBootPath = obj.optString("targetMultiBootPath", ""),
                        downloadedBytes = done,
                        totalBytes = obj.optLong("totalBytes", 0L),
                        expectedSha256 = obj.optString("expectedSha256", ""),
                        autoMount = obj.optBoolean("autoMount", true)
                    )
                }.getOrNull()
            }
            ?.sortedByDescending { it.downloadedBytes }
            .orEmpty()
    }

    fun discardPendingDownload(pending: PendingResumableDownload) {
        runCatching { File(pending.partFilePath).delete() }
        runCatching { File(pending.metaFilePath).delete() }
    }

    @Synchronized
    private fun saveSidecarMeta() {
        val cur = _state.value ?: return
        val part = stagingFile ?: return
        val meta = sidecarFile ?: return
        if (!cur.supportsRange || chunkStarts.isEmpty()) return
        runCatching {
            val obj = JSONObject().apply {
                put("title", cur.title)
                put("fileName", cur.fileName)
                put("url", cur.url)
                put("finalUrl", cachedFinalUrl)
                put("targetDir", cur.targetDir)
                put("targetMultiBootPath", cachedTargetMultiBootPath)
                put("partFilePath", part.absolutePath)
                put("totalBytes", cachedTotalBytes)
                put("expectedSha256", cachedExpectedSha256)
                put("autoMount", cachedAutoMount)
                put("autoVerifySha256", cachedAutoVerifySha256)
                put("threadCount", chunkStarts.size)
                put("chunkStarts", JSONArray().apply { chunkStarts.forEach { put(it) } })
                put("chunkEnds", JSONArray().apply { chunkEnds.forEach { put(it) } })
                put("chunkCursors", JSONArray().apply { chunkCursors.forEach { put(it) } })
            }
            meta.writeText(obj.toString(), Charsets.UTF_8)
        }
    }

    private fun normalizeDownloadUrl(rawUrl: String): String {
        val trimmed = rawUrl.trim(); val low = trimmed.lowercase()
        if (low.contains("sourceforge.net/projects/") && low.contains("/files/") && !low.endsWith("/download")) return trimmed.trimEnd('/') + "/download"
        if (low.contains("github.com/") && low.contains("/blob/")) return trimmed.replace("/blob/", "/raw/")
        return trimmed
    }
    private fun resolveDynamicApiUrl(rawUrl: String): Triple<String, String?, String?> {
        val low = rawUrl.lowercase()
        if (low.contains("api.pop-os.org/builds/")) {
            logDl("Resolving latest Pop!_OS ISO from System76 API...")
            val conn = openFollowingRedirects(rawUrl, "GET", userAgent = CURL_UA)
            try {
                if (conn.responseCode in 200..299) {
                    val obj = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
                    val isoUrl = obj.optString("url", "").trim(); val sha = obj.optString("sha_sum", "").trim()
                    if (isoUrl.startsWith("http")) { logDl("Pop!_OS resolved -> $isoUrl"); return Triple(isoUrl, sha.ifBlank { null }, isoUrl.substringAfterLast('/').substringBefore('?').ifBlank { null }) }
                }
            } finally { conn.disconnect() }
        }
        if (low.contains("tails.net/install/v2/tails/") && low.endsWith(".json")) {
            logDl("Resolving latest Tails stable image from tails.net...")
            val conn = openFollowingRedirects(rawUrl, "GET", userAgent = CURL_UA)
            try {
                if (conn.responseCode in 200..299) {
                    val paths = JSONObject(conn.inputStream.bufferedReader().use { it.readText() }).optJSONArray("installations")?.optJSONObject(0)?.optJSONArray("installation-paths")
                    if (paths != null) for (i in 0 until paths.length()) {
                        val tfs = paths.optJSONObject(i)?.optJSONArray("target-files") ?: continue
                        for (j in 0 until tfs.length()) {
                            val tf = tfs.optJSONObject(j) ?: continue; val u = tf.optString("url", "").trim(); val sha = tf.optString("sha256", "").trim()
                            if (u.endsWith(".img") || u.endsWith(".iso")) { logDl("Tails resolved -> $u"); return Triple(u, sha.ifBlank { null }, u.substringAfterLast('/').ifBlank { null }) }
                        }
                    }
                }
            } finally { conn.disconnect() }
        }
        return Triple(rawUrl, null, null)
    }
    private fun prefersCliUserAgent(urlStr: String): Boolean { val low = urlStr.lowercase(); return low.contains("sourceforge.net") || low.contains("osdn.net") || low.contains("fosshub.com") }
    private fun openFollowingRedirects(urlStr: String, method: String, rangeHeader: String? = null, userAgent: String = cachedUserAgent): HttpURLConnection {
        var current = urlStr; var redirects = 0; val cookies = LinkedHashMap<String, String>()
        while (redirects < 12) {
            val conn = (URL(current).openConnection() as HttpURLConnection).apply {
                requestMethod = method; instanceFollowRedirects = false; connectTimeout = 15_000; readTimeout = 25_000
                setRequestProperty("User-Agent", userAgent); setRequestProperty("Accept", "*/*"); setRequestProperty("Accept-Encoding", "identity"); setRequestProperty("Connection", "keep-alive"); setRequestProperty("Connection", "keep-alive")
                if (cookies.isNotEmpty()) setRequestProperty("Cookie", cookies.entries.joinToString("; ") { "${it.key}=${it.value}" })
                if (rangeHeader != null) setRequestProperty("Range", rangeHeader)
            }
            val code = conn.responseCode
            conn.headerFields["Set-Cookie"]?.forEach { rc -> val pair = rc.substringBefore(';').trim(); val eq = pair.indexOf('='); if (eq > 0) cookies[pair.substring(0, eq).trim()] = pair.substring(eq + 1).trim() }
            if (code in listOf(301, 302, 303, 307, 308)) { val loc = conn.getHeaderField("Location"); conn.disconnect(); if (loc.isNullOrBlank()) throw IllegalStateException("Redirect without Location header"); current = URL(URL(current), loc).toString(); redirects++ } else return conn
        }
        throw IllegalStateException("Too many redirects (12+)")
    }
    private data class ProbeResult(val finalUrl: String, val totalBytes: Long, val acceptsRanges: Boolean, val isHtmlPage: Boolean, val httpCode: Int = 200, val contentType: String = "", val negotiatedUserAgent: String = BROWSER_UA)
    private fun extractMirrorUrlFromHtml(baseUrl: String, html: String): String? {
        Regex("""<meta[^>]+http-equiv=["']?refresh["']?[^>]+content=["'][^"']*url=([^"'\s>]+)""", RegexOption.IGNORE_CASE).find(html)?.groupValues?.getOrNull(1)?.let { raw ->
            val clean = raw.replace("&amp;", "&").trim(); if (clean.startsWith("http") || clean.startsWith("/")) return runCatching { URL(URL(baseUrl), clean).toString() }.getOrNull()
        }
        return Regex("""href=["'](https?://[^"']+\.(?:iso|img|iso\.zip|img\.zip)(?:\?[^"']*)?)["']""", RegexOption.IGNORE_CASE).find(html)?.groupValues?.getOrNull(1)?.replace("&amp;", "&")
    }
    private fun probeWithUa(urlStr: String, ua: String): Pair<ProbeResult, String?> {
        runCatching {
            val conn = openFollowingRedirects(urlStr, "HEAD", userAgent = ua)
            try {
                val code = conn.responseCode; val cType = conn.contentType.orEmpty().lowercase()
                if (code in 200..299 && !cType.contains("text/html")) {
                    val len = conn.contentLengthLong; val acceptRanges = conn.getHeaderField("Accept-Ranges")?.equals("bytes", true) == true
                    if (len > 0L && acceptRanges) return ProbeResult(conn.url.toString(), len, true, false, code, cType, ua) to null
                }
            } finally { conn.disconnect() }
        }
        val conn = openFollowingRedirects(urlStr, "GET", rangeHeader = "bytes=0-32767", userAgent = ua)
        try {
            val code = conn.responseCode; val finalUrl = conn.url.toString(); val cType = conn.contentType.orEmpty().lowercase()
            if (cType.contains("text/html") || cType.contains("application/xhtml")) {
                val snippet = runCatching { val buf = ByteArray(32_768); val n = conn.inputStream.read(buf); if (n > 0) String(buf, 0, n, Charsets.UTF_8) else "" }.getOrDefault("")
                return ProbeResult(finalUrl, -1L, false, true, code, cType, ua) to extractMirrorUrlFromHtml(finalUrl, snippet)
            }
            if (code == 206) { val total = conn.getHeaderField("Content-Range").orEmpty().substringAfter('/', "").trim().toLongOrNull() ?: -1L; return ProbeResult(finalUrl, total, total > 0L, false, code, cType, ua) to null }
            if (code in 200..299) return ProbeResult(finalUrl, conn.contentLengthLong, false, false, code, cType, ua) to null
            throw IllegalStateException("Server returned HTTP $code ($finalUrl)")
        } finally { conn.disconnect() }
    }
    private fun probeServer(urlStr: String): ProbeResult {
        val uaOrder = if (prefersCliUserAgent(urlStr)) listOf(WGET_UA, CURL_UA, BROWSER_UA) else listOf(BROWSER_UA, WGET_UA, CURL_UA)
        var lastProbe = ProbeResult(urlStr, -1L, false, true, 0, "unknown", uaOrder.first()); var extractedMirror: String? = null; var lastException: Exception? = null
        for (ua in uaOrder) {
            try {
                val (res, mirror) = probeWithUa(urlStr, ua); lastProbe = res
                if (mirror != null && extractedMirror == null) extractedMirror = mirror
                if (!res.isHtmlPage) { if (ua != uaOrder.first()) logDl("Negotiated binary stream with UA=$ua -> ${res.finalUrl}"); return res }
                logDl("Host returned HTML (${res.httpCode}, ${res.contentType}) with UA=$ua; trying fallback...")
            } catch (e: Exception) { lastException = e; logDl("Probe attempt with UA=$ua failed: ${e.message}", isError = true) }
        }
        if (!extractedMirror.isNullOrBlank() && extractedMirror != urlStr) {
            logDl("Found mirror redirect in HTML -> $extractedMirror")
            runCatching { val (res, _) = probeWithUa(extractedMirror, WGET_UA); if (!res.isHtmlPage) return res }
        }
        if (lastProbe.httpCode == 0 && lastException != null) throw lastException
        return lastProbe
    }

    fun start(
        context: Context,
        title: String,
        url: String,
        fileName: String,
        targetDir: String,
        targetMultiBootPath: String = "",
        approxBytes: Long = 0L,
        expectedSha256: String = "",
        connections: Int = 8,
        autoMount: Boolean = true,
        autoVerifySha256: Boolean = true,
        onComplete: (DownloadCompletionEvent) -> Unit,
        onError: (String) -> Unit,
        onLog: (LogEntry) -> Unit = {}
    ) {
        cancel(deleteTemp = true)
        pausedFlag.set(false)
        cancelledFlag.set(false)

        val cleanName = fileName.trim().ifBlank { "downloaded-image.iso" }
            .replace(Regex("[^A-Za-z0-9._ -]"), "_")
        val clampedThreads = connections.coerceIn(1, 16)

        cachedContext = context.applicationContext
        cachedThreads = clampedThreads
        cachedAutoMount = autoMount
        cachedAutoVerifySha256 = autoVerifySha256
        cachedExpectedSha256 = expectedSha256.trim().lowercase()
        cachedTargetMultiBootPath = targetMultiBootPath.trim()
        cachedOnComplete = onComplete
        cachedOnError = onError
        cachedOnLog = onLog
        cachedUserAgent = BROWSER_UA

        _state.value = TurboDownloadState(
            title = title,
            fileName = cleanName,
            url = url.trim(),
            targetDir = targetDir,
            targetMultiBootPath = cachedTargetMultiBootPath,
            totalBytes = approxBytes,
            activeThreads = clampedThreads,
            statusText = "Probing server..."
        )
        TurboDownloadService.start(context.applicationContext)

        currentJob = scope.launch {
            try {
                val normalized = normalizeDownloadUrl(url)
                logDl("Starting download: $title ($normalized)")
                val (resolvedUrl, dynamicSha, dynamicName) = resolveDynamicApiUrl(normalized)
                if (!dynamicSha.isNullOrBlank() && cachedExpectedSha256.isBlank()) cachedExpectedSha256 = dynamicSha.lowercase()
                if (!dynamicName.isNullOrBlank() && (cleanName.startsWith("pop-os_latest", true) || cleanName.startsWith("tails-amd64-latest", true))) { _state.value = _state.value?.copy(fileName = dynamicName) }
                val probe = probeServer(resolvedUrl)
                if (probe.isHtmlPage) {
                    val isMsLink = resolvedUrl.contains("microsoft.com", true) || resolvedUrl.contains("software-static", true)
                    val hint = if (isMsLink) "Disable VPN or switch networks and retry, or tap Copy Link." else "Check Activity Log for the resolved URL or try another mirror."
                    val msg = "Server returned a web page (HTTP ${probe.httpCode}, ${probe.contentType.ifBlank { "text/html" }}) instead of an ISO file. $hint"
                    logDl("HTML rejection at ${probe.finalUrl} (HTTP ${probe.httpCode}, ${probe.contentType})", isError = true)
                    throw IllegalStateException(msg)
                }
                cachedUserAgent = probe.negotiatedUserAgent
                val total = if (probe.totalBytes > 0L) probe.totalBytes else approxBytes
                val useThreads = if (probe.acceptsRanges && probe.totalBytes >= 4L * 1024L * 1024L) clampedThreads else 1
                cachedFinalUrl = probe.finalUrl
                cachedTotalBytes = probe.totalBytes
                logDl("Resolved stream: ${probe.finalUrl} (${UsbGadgetEngine.formatBytes(total)}, range=${probe.acceptsRanges}, ${useThreads}x)")

                val dlDir = stagingDir(context)
                val tmpFile = File(dlDir, "$cleanName.part")
                val metaFile = File(dlDir, "$cleanName.part.pbmeta")
                if (tmpFile.exists()) tmpFile.delete()
                if (metaFile.exists()) metaFile.delete()
                stagingFile = tmpFile
                sidecarFile = metaFile

                if (useThreads > 1 && probe.totalBytes > 0L) {
                    RandomAccessFile(tmpFile, "rw").use { it.setLength(probe.totalBytes) }
                    chunkStarts = LongArray(useThreads)
                    chunkEnds = LongArray(useThreads)
                    chunkCursors = LongArray(useThreads)
                    val slice = probe.totalBytes / useThreads
                    for (i in 0 until useThreads) {
                        val s = i * slice
                        val e = if (i == useThreads - 1) probe.totalBytes - 1L else (s + slice - 1L)
                        chunkStarts[i] = s
                        chunkEnds[i] = e
                        chunkCursors[i] = s
                    }
                } else {
                    chunkStarts = longArrayOf(0L)
                    chunkEnds = longArrayOf(if (probe.totalBytes > 0L) probe.totalBytes - 1L else -1L)
                    chunkCursors = longArrayOf(0L)
                }

                _state.value = _state.value?.copy(
                    totalBytes = total,
                    activeThreads = useThreads,
                    supportsRange = probe.acceptsRanges,
                    statusText = if (useThreads > 1) "Turbo · $useThreads connections" else "Single-stream download"
                )
                saveSidecarMeta()

                runDownloadWorkers(useThreads, probe.acceptsRanges)
            } catch (_: CancellationException) {
                // Handled by pause() or cancel()
            } catch (e: Exception) {
                saveSidecarMeta()
                _state.value = null
                cachedContext?.let { TurboDownloadService.stop(it) }
                logDl("Download failed: ${e.message ?: "Network error"}", isError = true)
                onError("Download failed: ${e.message ?: "Network error"}")
            }
        }
    }

    fun resumeFromSidecar(
        context: Context,
        pending: PendingResumableDownload,
        autoVerifySha256: Boolean,
        onComplete: (DownloadCompletionEvent) -> Unit,
        onError: (String) -> Unit
    ) {
        val metaFile = File(pending.metaFilePath)
        val partFile = File(pending.partFilePath)
        if (!metaFile.exists() || !partFile.exists()) {
            onError("Resumable session files are no longer available.")
            return
        }
        val obj = runCatching { JSONObject(metaFile.readText(Charsets.UTF_8)) }.getOrNull()
        if (obj == null) {
            onError("Corrupted resume metadata.")
            return
        }

        val sArr = obj.optJSONArray("chunkStarts")
        val eArr = obj.optJSONArray("chunkEnds")
        val cArr = obj.optJSONArray("chunkCursors")
        if (sArr == null || eArr == null || cArr == null || sArr.length() == 0) {
            onError("Incomplete resume metadata.")
            return
        }

        cancel(deleteTemp = false)
        pausedFlag.set(false)
        cancelledFlag.set(false)

        val n = sArr.length()
        chunkStarts = LongArray(n) { sArr.optLong(it) }
        chunkEnds = LongArray(n) { eArr.optLong(it) }
        chunkCursors = LongArray(n) { cArr.optLong(it) }
        stagingFile = partFile
        sidecarFile = metaFile
        cachedContext = context.applicationContext
        cachedFinalUrl = obj.optString("finalUrl", pending.url).ifBlank { pending.url }
        cachedTotalBytes = obj.optLong("totalBytes", pending.totalBytes)
        cachedThreads = n
        cachedAutoMount = obj.optBoolean("autoMount", pending.autoMount)
        cachedAutoVerifySha256 = autoVerifySha256
        cachedExpectedSha256 = obj.optString("expectedSha256", pending.expectedSha256)
        cachedTargetMultiBootPath = obj.optString("targetMultiBootPath", pending.targetMultiBootPath)
        cachedOnComplete = onComplete
        cachedOnError = onError

        val done = (0 until n).sumOf { i -> (chunkCursors[i] - chunkStarts[i]).coerceAtLeast(0L) }
        _state.value = TurboDownloadState(
            title = pending.title,
            fileName = pending.fileName,
            url = pending.url,
            targetDir = pending.targetDir,
            targetMultiBootPath = cachedTargetMultiBootPath,
            downloadedBytes = done,
            totalBytes = cachedTotalBytes,
            activeThreads = n,
            supportsRange = true,
            isPaused = false,
            statusText = "Resuming · $n connections"
        )
        TurboDownloadService.start(context.applicationContext)

        currentJob = scope.launch {
            try {
                runCatching {
                    val probe = probeServer(pending.url)
                    if (!probe.isHtmlPage && probe.finalUrl.isNotBlank()) {
                        cachedFinalUrl = probe.finalUrl
                    }
                }
                runDownloadWorkers(n, true)
            } catch (_: CancellationException) {
                // Paused or cancelled
            } catch (e: Exception) {
                saveSidecarMeta()
                _state.value = null
                cachedContext?.let { TurboDownloadService.stop(it) }
                onError("Resume failed: ${e.message ?: "Network error"}")
            }
        }
    }

    fun pause() {
        val cur = _state.value ?: return
        if (!cur.supportsRange || cur.isPaused || cur.isVerifying || cur.isInjectingMultiBoot) return
        pausedFlag.set(true)
        currentJob?.cancel()
        currentJob = null
        saveSidecarMeta()
        val pausedState = cur.copy(isPaused = true, speedBytesPerSec = 0L, etaSeconds = -1L, statusText = "Paused")
        _state.value = pausedState
        cachedContext?.let { NotificationHelper.updateDownloadProgress(it, pausedState) }
    }

    fun resume() {
        val cur = _state.value ?: return
        if (!cur.isPaused) return
        pausedFlag.set(false)
        cancelledFlag.set(false)
        val resumedState = cur.copy(isPaused = false, statusText = "Resuming · ${cur.activeThreads} connections")
        _state.value = resumedState
        cachedContext?.let { TurboDownloadService.start(it) }
        currentJob = scope.launch {
            try {
                runDownloadWorkers(cur.activeThreads, cur.supportsRange)
            } catch (_: CancellationException) {
                // Paused or cancelled
            } catch (e: Exception) {
                saveSidecarMeta()
                _state.value = null
                cachedContext?.let { TurboDownloadService.stop(it) }
                cachedOnError?.invoke("Resume failed: ${e.message ?: "Network error"}")
            }
        }
    }

    fun updateAutoMount(value: Boolean) {
        cachedAutoMount = value
        saveSidecarMeta()
    }

    fun cancel(deleteTemp: Boolean = true) {
        cancelledFlag.set(true)
        pausedFlag.set(false)
        currentJob?.cancel()
        currentJob = null
        if (deleteTemp) {
            runCatching { stagingFile?.delete() }
            runCatching { sidecarFile?.delete() }
            stagingFile = null
            sidecarFile = null
        }
        _state.value = null
        cachedContext?.let { TurboDownloadService.stop(it) }
    }

    private suspend fun runDownloadWorkers(threadCount: Int, useRanges: Boolean) {
        val tmpFile = stagingFile ?: throw IllegalStateException("Missing staging file")
        val initialDone = (0 until threadCount).sumOf { i -> (chunkCursors[i] - chunkStarts[i]).coerceAtLeast(0L) }
        val totalDownloaded = AtomicLong(initialDone)

        val meterJob = scope.launch {
            var lastBytes = totalDownloaded.get()
            var lastTime = System.currentTimeMillis()
            var smoothedSpeed = 0.0
            var tickCounter = 0
            while (isActive && !pausedFlag.get() && !cancelledFlag.get()) {
                delay(400L)
                tickCounter++
                val now = System.currentTimeMillis()
                val curBytes = totalDownloaded.get()
                val dt = (now - lastTime).coerceAtLeast(1L)
                val instant = ((curBytes - lastBytes).coerceAtLeast(0L) * 1000.0) / dt.toDouble()
                smoothedSpeed = if (smoothedSpeed == 0.0) instant else (smoothedSpeed * 0.65 + instant * 0.35)
                lastBytes = curBytes
                lastTime = now

                val curState = _state.value ?: break
                val speed = smoothedSpeed.toLong()
                val remaining = (curState.totalBytes - curBytes).coerceAtLeast(0L)
                val eta = if (speed > 1024L && curState.totalBytes > 0L) remaining / speed else -1L
                val updated = curState.copy(
                    downloadedBytes = curBytes,
                    speedBytesPerSec = speed,
                    etaSeconds = eta
                )
                _state.value = updated
                if (tickCounter % 2 == 0) {
                    cachedContext?.let { NotificationHelper.updateDownloadProgress(it, updated) }
                }
                if (tickCounter % 5 == 0) {
                    saveSidecarMeta()
                }
            }
        }

        try {
            if (useRanges && threadCount > 1 && cachedTotalBytes > 0L) {
                coroutineScope {
                    (0 until threadCount).map { idx ->
                        async(Dispatchers.IO) {
                            downloadChunkWithRetries(tmpFile, idx, totalDownloaded)
                        }
                    }.awaitAll()
                }
            } else {
                downloadSingleStream(tmpFile, totalDownloaded)
            }
        } finally {
            meterJob.cancel()
        }

        if (pausedFlag.get() || cancelledFlag.get()) return

        val ctx = cachedContext ?: return
        val finalState = _state.value ?: return
        _state.value = finalState.copy(
            downloadedBytes = finalState.totalBytes.coerceAtLeast(totalDownloaded.get()),
            speedBytesPerSec = 0L,
            etaSeconds = 0L,
            statusText = "Saving to ${UsbGadgetEngine.friendlyFolderName(finalState.targetDir)}..."
        )

        val targetDir = UsbGadgetEngine.resolveDirectPath(finalState.targetDir).trimEnd('/')
        val finalDest = "$targetDir/${finalState.fileName}"
        val qSrc = "'${tmpFile.absolutePath.replace("'", "'\\''")}'"
        val qDir = "'${targetDir.replace("'", "'\\''")}'"
        val qDst = "'${finalDest.replace("'", "'\\''")}'"

        val movedViaRoot = runCatching {
            Shell.cmd("mkdir -p $qDir && mv -f $qSrc $qDst").exec().isSuccess
        }.getOrDefault(false)

        val savedPath = if (movedViaRoot) {
            UsbGadgetEngine.fixUserStoragePermissions(ctx, targetDir, isDir = true)
            UsbGadgetEngine.fixUserStoragePermissions(ctx, finalDest, isDir = false)
            val dlSz = Shell.cmd("stat -c '%s' $qDst 2>/dev/null || echo 0").exec().out.firstOrNull()?.trim()?.toLongOrNull() ?: 0L
            if (dlSz > 0L) UsbGadgetEngine.syncMediaStoreFileSize(ctx, finalDest, dlSz)
            finalDest
        } else {
            val fallback = File(targetDir.replaceFirst("/data/media/0", "/storage/emulated/0"), finalState.fileName)
            runCatching {
                fallback.parentFile?.mkdirs()
                tmpFile.copyTo(fallback, overwrite = true)
                tmpFile.delete()
                UsbGadgetEngine.fixUserStoragePermissions(ctx, finalDest, isDir = false)
                finalDest
            }.getOrElse { tmpFile.absolutePath }
        }

        runCatching { sidecarFile?.delete() }
        stagingFile = null; sidecarFile = null
        var effectiveSavedPath = savedPath; var effectiveFileName = finalState.fileName
        if (effectiveFileName.endsWith(".iso.zip", true) || effectiveFileName.endsWith(".img.zip", true)) {
            _state.value = finalState.copy(statusText = "Extracting ISO from zip archive...")
            extractImageFromZipArchive(ctx, effectiveSavedPath, targetDir)?.let { effectiveSavedPath = it; effectiveFileName = it.substringAfterLast('/'); logDl("Auto-extracted archive -> $effectiveFileName") }
        }

        var computedHash: String? = null
        var checksumStatus = ChecksumVerifyStatus.NONE
        if (cachedAutoVerifySha256 || cachedExpectedSha256.isNotBlank()) {
            val verifyState = finalState.copy(
                downloadedBytes = finalState.totalBytes.coerceAtLeast(totalDownloaded.get()),
                isVerifying = true,
                statusText = "Verifying SHA-256 checksum..."
            )
            _state.value = verifyState
            NotificationHelper.updateDownloadProgress(ctx, verifyState)

            computedHash = UsbGadgetEngine.computeSha256(effectiveSavedPath)?.trim()?.lowercase()
            checksumStatus = when {
                computedHash == null -> ChecksumVerifyStatus.NONE
                cachedExpectedSha256.isNotBlank() && computedHash.equals(cachedExpectedSha256, ignoreCase = true) ->
                    ChecksumVerifyStatus.VERIFIED_MATCH
                cachedExpectedSha256.isNotBlank() ->
                    ChecksumVerifyStatus.MISMATCH
                else -> ChecksumVerifyStatus.COMPUTED_ONLY
            }
        }

        var injectedMultiBoot: String? = null
        if (cachedTargetMultiBootPath.isNotBlank() && checksumStatus != ChecksumVerifyStatus.MISMATCH) {
            val mbPath = UsbGadgetEngine.resolveDirectPath(cachedTargetMultiBootPath)
            val statusNow = UsbGadgetEngine.getStatus()
            val mbMounted = statusNow.mountedLunFor(mbPath) != null
            if (!mbMounted) {
                val mbName = mbPath.substringAfterLast('/')
                val injState = finalState.copy(
                    isVerifying = false,
                    isInjectingMultiBoot = true,
                    statusText = "Moving ISO into $mbName..."
                )
                _state.value = injState
                NotificationHelper.updateDownloadProgress(ctx, injState)

                val addRes = VentoyImageBuilder.addIsoToVentoy(
                    context = ctx,
                    ventoyImgPath = mbPath,
                    sourceIsoPath = effectiveSavedPath,
                    moveFile = true,
                    onProgress = { p ->
                        val st = _state.value?.copy(statusText = p.detail.ifBlank { p.stage })
                        if (st != null) _state.value = st
                    }
                )
                if (addRes.success) {
                    injectedMultiBoot = mbPath
                }
            }
        }

        val completion = DownloadCompletionEvent(
            title = finalState.title,
            fileName = effectiveFileName,
            savedPath = effectiveSavedPath,
            injectedMultiBootPath = injectedMultiBoot,
            computedSha256 = computedHash,
            expectedSha256 = cachedExpectedSha256.ifBlank { null },
            checksumStatus = checksumStatus,
            autoMount = cachedAutoMount
        )
        _lastCompletion.value = completion
        _state.value = null
        TurboDownloadService.stop(ctx)

        val notifSub = when {
            injectedMultiBoot != null -> "Added to ${injectedMultiBoot.substringAfterLast('/')}"
            checksumStatus == ChecksumVerifyStatus.VERIFIED_MATCH -> "Verified genuine SHA-256 · Ready to mount"
            checksumStatus == ChecksumVerifyStatus.MISMATCH -> "Warning: SHA-256 checksum mismatch!"
            else -> "Saved to ${UsbGadgetEngine.friendlyFolderName(targetDir)}"
        }
        NotificationHelper.showDownloadComplete(ctx, finalState.title, notifSub)
        cachedOnComplete?.invoke(completion)
    }

    private fun extractImageFromZipArchive(context: Context, zipPath: String, targetDir: String): String? = runCatching {
        val localZip = File(zipPath.replaceFirst("/data/media/0", "/storage/emulated/0"))
        val stagedZip = File(context.cacheDir, "pb_extract_in.zip")
        val qZip = "'${zipPath.replace("'", "'\\''")}'"
        if (!localZip.canRead()) Shell.cmd("cp -f $qZip '${stagedZip.absolutePath.replace("'", "'\\''")}' 2>/dev/null").exec()
        val srcFile = if (localZip.canRead()) localZip else stagedZip
        if (!srcFile.exists()) return null
        var extractedName: String? = null
        val outTmp = File(context.cacheDir, "pb_extract_out.bin")
        ZipInputStream(srcFile.inputStream().buffered()).use { zis ->
            while (true) {
                val entry = zis.nextEntry ?: break
                if (!entry.isDirectory) {
                    val ename = entry.name.substringAfterLast('/')
                    if (ename.endsWith(".iso", true) || ename.endsWith(".img", true)) { extractedName = ename; outTmp.outputStream().buffered().use { zis.copyTo(it) }; break }
                }
            }
        }
        stagedZip.delete()
        val finalName = extractedName ?: return null
        val finalOutPath = "${targetDir.trimEnd('/')}/$finalName"
        if (Shell.cmd("mv -f '${outTmp.absolutePath.replace("'", "'\\''")}' '${finalOutPath.replace("'", "'\\''")}' && rm -f $qZip").exec().isSuccess) { UsbGadgetEngine.fixUserStoragePermissions(context, finalOutPath, false); val zSz = Shell.cmd("stat -c '%s' '${finalOutPath.replace("'", "'\\''")}' 2>/dev/null || echo 0").exec().out.firstOrNull()?.trim()?.toLongOrNull() ?: 0L; if (zSz > 0L) UsbGadgetEngine.syncMediaStoreFileSize(context, finalOutPath, zSz); finalOutPath } else { outTmp.delete(); null }
    }.getOrNull()

    private fun downloadChunkWithRetries(file: File, idx: Int, totalCounter: AtomicLong) {
        val end = chunkEnds[idx]
        var attempt = 0
        val buf = ByteArray(262_144)
        while (chunkCursors[idx] <= end && !pausedFlag.get() && !cancelledFlag.get()) {
            try {
                val startPos = chunkCursors[idx]
                val conn = openFollowingRedirects(cachedFinalUrl, "GET", rangeHeader = "bytes=$startPos-$end")
                try {
                    val code = conn.responseCode
                    if (startPos > 0L && code == 200) {
                        throw IllegalStateException("Server ignored Range header (HTTP 200 at offset $startPos) on chunk $idx")
                    }
                    if (code != 206 && code != 200) {
                        throw IllegalStateException("HTTP $code on chunk $idx")
                    }
                    RandomAccessFile(file, "rw").use { raf ->
                        raf.seek(startPos)
                        conn.inputStream.use { input ->
                            while (!pausedFlag.get() && !cancelledFlag.get()) {
                                val read = input.read(buf)
                                if (read <= 0) break
                                val remainingInChunk = (end - chunkCursors[idx] + 1L).coerceAtLeast(0L)
                                val toWrite = minOf(read.toLong(), remainingInChunk).toInt()
                                if (toWrite <= 0) break
                                raf.write(buf, 0, toWrite)
                                chunkCursors[idx] += toWrite.toLong()
                                totalCounter.addAndGet(toWrite.toLong())
                            }
                        }
                    }
                } finally {
                    conn.disconnect()
                }
                attempt = 0
            } catch (e: Exception) {
                if (pausedFlag.get() || cancelledFlag.get()) return
                attempt++
                if (attempt > 5) throw e
                Thread.sleep(700L * attempt)
            }
        }
    }

    private fun downloadSingleStream(file: File, totalCounter: AtomicLong) {
        val buf = ByteArray(262_144)
        val conn = openFollowingRedirects(cachedFinalUrl, "GET")
        var written = 0L
        var expectedLen = -1L
        try {
            val code = conn.responseCode
            if (code !in 200..299) throw IllegalStateException("HTTP $code")
            expectedLen = conn.contentLengthLong
            if (expectedLen > 0L) {
                _state.value = _state.value?.copy(totalBytes = expectedLen)
            }
            RandomAccessFile(file, "rw").use { raf ->
                raf.setLength(0L)
                conn.inputStream.use { input ->
                    while (!pausedFlag.get() && !cancelledFlag.get()) {
                        val read = input.read(buf)
                        if (read <= 0) break
                        raf.write(buf, 0, read)
                        written += read.toLong()
                        totalCounter.addAndGet(read.toLong())
                    }
                }
            }
        } finally {
            conn.disconnect()
        }
        if (!pausedFlag.get() && !cancelledFlag.get()) {
            if (written <= 0L) throw IllegalStateException("Download stream returned 0 bytes")
            if (expectedLen > 0L && written != expectedLen) {
                throw IllegalStateException("Truncated download ($written / $expectedLen bytes)")
            }
        }
    }

    suspend fun downloadFileTurbo(
        url: String,
        partFile: File,
        metaFile: File,
        expectedSize: Long,
        expectedSha1: String,
        maxConnections: Int,
        pausedFlag: AtomicBoolean,
        cancelledFlag: AtomicBoolean,
        totalCounter: AtomicLong,
        activeStreamsOut: AtomicInteger? = null,
        onThrottle: ((Int) -> Unit)? = null
    ): Boolean = withContext(Dispatchers.IO) {
        val cleanSha1 = expectedSha1.trim().lowercase()
        val bufSize = 262_144

        val wantsMultiChunk = expectedSize >= 16L * 1024L * 1024L && maxConnections > 1
        if (!wantsMultiChunk) {
            var attempt = 0
            val buf = ByteArray(bufSize)
            while (!cancelledFlag.get() && !pausedFlag.get()) {
                var attemptWritten = 0L
                try {
                    val md = if (cleanSha1.length == 40) MessageDigest.getInstance("SHA-1") else null
                    val conn = openFollowingRedirects(url, "GET")
                    try {
                        val code = conn.responseCode
                        if (code !in 200..299) throw IllegalStateException("HTTP $code")
                        partFile.parentFile?.mkdirs()
                        RandomAccessFile(partFile, "rw").use { raf ->
                            raf.setLength(0L)
                            conn.inputStream.use { input ->
                                while (!cancelledFlag.get() && !pausedFlag.get()) {
                                    val r = input.read(buf)
                                    if (r <= 0) break
                                    raf.write(buf, 0, r)
                                    md?.update(buf, 0, r)
                                    attemptWritten += r.toLong()
                                    totalCounter.addAndGet(r.toLong())
                                }
                            }
                        }
                    } finally {
                        conn.disconnect()
                    }
                    if (cancelledFlag.get() || pausedFlag.get()) {
                        partFile.delete()
                        totalCounter.addAndGet(-attemptWritten)
                        return@withContext false
                    }
                    if (md != null) {
                        val hex = md.digest().joinToString("") { "%02x".format(it) }
                        if (!hex.equals(cleanSha1, ignoreCase = true)) {
                            throw IllegalStateException("SHA-1 mismatch")
                        }
                    }
                    metaFile.delete()
                    return@withContext true
                } catch (e: CancellationException) {
                    partFile.delete()
                    if (attemptWritten > 0L) totalCounter.addAndGet(-attemptWritten)
                    throw e
                } catch (e: Exception) {
                    partFile.delete()
                    if (attemptWritten > 0L) totalCounter.addAndGet(-attemptWritten)
                    if (cancelledFlag.get() || pausedFlag.get()) return@withContext false
                    attempt++
                    if (attempt > 5) throw e
                    val isThrottle = e.message?.contains("429") == true || e.message?.contains("503") == true
                    delay(if (isThrottle) 1500L * attempt else 800L * attempt)
                }
            }
            return@withContext false
        }

        val probe = runCatching { probeServer(url) }.getOrNull()
        val finalUrl = probe?.finalUrl?.ifBlank { url } ?: url
        val totalLen = when {
            probe != null && probe.totalBytes > 0L -> probe.totalBytes
            expectedSize > 0L -> expectedSize
            else -> -1L
        }
        val canRange = (probe?.acceptsRanges == true) && totalLen >= 16L * 1024L * 1024L
        if (!canRange) {
            return@withContext downloadFileTurbo(
                url = finalUrl,
                partFile = partFile,
                metaFile = metaFile,
                expectedSize = 0L,
                expectedSha1 = cleanSha1,
                maxConnections = 1,
                pausedFlag = pausedFlag,
                cancelledFlag = cancelledFlag,
                totalCounter = totalCounter,
                activeStreamsOut = activeStreamsOut,
                onThrottle = onThrottle
            )
        }

        val numChunks = maxConnections.coerceIn(2, 16)
        var starts = LongArray(0)
        var ends = LongArray(0)
        var cursors = LongArray(0)

        var restored = false
        if (partFile.exists() && metaFile.exists() && partFile.length() == totalLen) {
            runCatching {
                val obj = JSONObject(metaFile.readText(Charsets.UTF_8))
                if (obj.optLong("totalBytes") == totalLen) {
                    val sArr = obj.optJSONArray("chunkStarts")
                    val eArr = obj.optJSONArray("chunkEnds")
                    val cArr = obj.optJSONArray("chunkCursors")
                    if (sArr != null && eArr != null && cArr != null && sArr.length() > 0) {
                        val n = sArr.length()
                        starts = LongArray(n) { sArr.optLong(it) }
                        ends = LongArray(n) { eArr.optLong(it) }
                        cursors = LongArray(n) { cArr.optLong(it) }
                        val alreadyDone = (0 until n).sumOf { i -> (cursors[i] - starts[i]).coerceAtLeast(0L) }
                        totalCounter.addAndGet(alreadyDone)
                        restored = true
                    }
                }
            }
        }

        if (!restored) {
            partFile.parentFile?.mkdirs()
            RandomAccessFile(partFile, "rw").use { it.setLength(totalLen) }
            starts = LongArray(numChunks)
            ends = LongArray(numChunks)
            cursors = LongArray(numChunks)
            val slice = totalLen / numChunks
            for (i in 0 until numChunks) {
                val s = i * slice
                val e = if (i == numChunks - 1) totalLen - 1L else (s + slice - 1L)
                starts[i] = s
                ends[i] = e
                cursors[i] = s
            }
        }

        fun persistMeta() {
            runCatching {
                val obj = JSONObject().apply {
                    put("totalBytes", totalLen)
                    put("chunkStarts", JSONArray().apply { starts.forEach { put(it) } })
                    put("chunkEnds", JSONArray().apply { ends.forEach { put(it) } })
                    put("chunkCursors", JSONArray().apply { cursors.forEach { put(it) } })
                }
                metaFile.writeText(obj.toString(), Charsets.UTF_8)
            }
        }
        persistMeta()

        val activeCap = AtomicInteger(starts.size)
        val permitsToDrop = AtomicInteger(0)
        val throttledCount = AtomicInteger(0)
        val permitPool = Semaphore(starts.size)

        fun consumeOneDropToken(): Boolean {
            while (true) {
                val cur = permitsToDrop.get()
                if (cur <= 0) return false
                if (permitsToDrop.compareAndSet(cur, cur - 1)) return true
            }
        }

        val checkpointJob = launch {
            while (isActive && !pausedFlag.get() && !cancelledFlag.get()) {
                delay(1500L)
                persistMeta()
            }
        }

        try {
            coroutineScope {
                starts.indices.map { idx ->
                    async(Dispatchers.IO) {
                        val buf = ByteArray(bufSize)
                        val end = ends[idx]
                        var attempt = 0
                        while (cursors[idx] <= end && !pausedFlag.get() && !cancelledFlag.get()) {
                            var acquired = false
                            try {
                                permitPool.acquire()
                                acquired = true
                                activeStreamsOut?.incrementAndGet()
                                val startPos = cursors[idx]
                                val conn = openFollowingRedirects(finalUrl, "GET", rangeHeader = "bytes=$startPos-$end")
                                try {
                                    val code = conn.responseCode
                                    if (code == 429 || code == 503) throw IllegalStateException("THROTTLE_HTTP_$code")
                                    val needsPartial = startPos > 0L || end < totalLen - 1L
                                    if (needsPartial && code != 206) {
                                        throw IllegalStateException("Expected HTTP 206 for byte range on chunk $idx (got HTTP $code)")
                                    }
                                    if (code != 206 && code != 200) throw IllegalStateException("HTTP $code on chunk $idx")
                                    RandomAccessFile(partFile, "rw").use { raf ->
                                        raf.seek(startPos)
                                        conn.inputStream.use { input ->
                                            while (!pausedFlag.get() && !cancelledFlag.get()) {
                                                val r = input.read(buf)
                                                if (r <= 0) break
                                                val rem = (end - cursors[idx] + 1L).coerceAtLeast(0L)
                                                val toWrite = minOf(r.toLong(), rem).toInt()
                                                if (toWrite <= 0) break
                                                raf.write(buf, 0, toWrite)
                                                cursors[idx] += toWrite.toLong()
                                                totalCounter.addAndGet(toWrite.toLong())
                                            }
                                        }
                                    }
                                } finally {
                                    conn.disconnect()
                                }
                                attempt = 0
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                if (pausedFlag.get() || cancelledFlag.get()) return@async
                                attempt++
                                val isThrottle = e.message?.startsWith("THROTTLE_HTTP_") == true
                                if (isThrottle) {
                                    val hits = throttledCount.incrementAndGet()
                                    val prev = activeCap.get()
                                    if (hits % 2 == 1 && prev > 2) {
                                        val nextCap = (prev / 2).coerceAtLeast(2)
                                        if (activeCap.compareAndSet(prev, nextCap)) {
                                            permitsToDrop.addAndGet(prev - nextCap)
                                            onThrottle?.invoke(nextCap)
                                        }
                                    }
                                    if (attempt > 6) throw e
                                    delay(1600L * attempt)
                                } else {
                                    if (attempt > 5) throw e
                                    delay(750L * attempt)
                                }
                            } finally {
                                if (acquired) {
                                    activeStreamsOut?.decrementAndGet()
                                    if (!consumeOneDropToken()) {
                                        permitPool.release()
                                    }
                                }
                            }
                        }
                    }
                }.awaitAll()
            }
        } finally {
            checkpointJob.cancel()
            persistMeta()
        }

        if (pausedFlag.get() || cancelledFlag.get()) return@withContext false

        if (cleanSha1.length == 40) {
            val md = MessageDigest.getInstance("SHA-1")
            val vBuf = ByteArray(bufSize)
            partFile.inputStream().use { input ->
                while (true) {
                    val r = input.read(vBuf)
                    if (r <= 0) break
                    md.update(vBuf, 0, r)
                }
            }
            val hex = md.digest().joinToString("") { "%02x".format(it) }
            if (!hex.equals(cleanSha1, ignoreCase = true)) {
                partFile.delete()
                metaFile.delete()
                throw IllegalStateException("SHA-1 verification failed after multi-chunk download")
            }
        }

        metaFile.delete()
        true
    }

    data class TurboBatchItem(
        val id: String,
        val url: String,
        val partFile: File,
        val metaFile: File,
        val expectedSize: Long,
        val expectedSha1: String,
        val urlExpiryEpoch: Long = 0L
    )

    /**
     * Global work-stealing multi-file Turbo engine with Mutex-locked URL auto-refresh.
     * Splits files >= 4 MB into dynamic 4–8 MB byte-range slices and keeps all `connections`
     * workers saturated across files. When Microsoft CDN links expire (HTTP 403/410 or near P1= expiry),
     * a single Mutex-guarded refresh updates URLs for all concurrent workers seamlessly.
     */
    suspend fun downloadBatchTurbo(
        items: List<TurboBatchItem>,
        connections: Int,
        pausedFlag: AtomicBoolean,
        cancelledFlag: AtomicBoolean,
        totalCounter: AtomicLong,
        activeStreamsOut: AtomicInteger,
        onRefreshUrls: (suspend () -> Map<String, Pair<String, Long>>)? = null,
        onFileVerified: suspend (TurboBatchItem) -> Unit
    ) = withContext(Dispatchers.IO) {
        if (items.isEmpty()) return@withContext
        val workerCount = connections.coerceIn(1, 16)
        val bufSize = 262_144

        val liveUrls = ConcurrentHashMap<String, String>()
        val liveExpiries = ConcurrentHashMap<String, Long>()
        items.forEach {
            liveUrls[it.id] = it.url
            if (it.urlExpiryEpoch > 0L) liveExpiries[it.id] = it.urlExpiryEpoch
        }
        val refreshMutex = Mutex()
        val lastRefreshMs = AtomicLong(0L)

        suspend fun resolveFreshUrl(item: TurboBatchItem, forceRefresh: Boolean): String {
            val nowSec = System.currentTimeMillis() / 1000L
            val expSec = liveExpiries[item.id] ?: item.urlExpiryEpoch
            val nearExpiry = expSec > 0L && nowSec >= (expSec - 45L)
            if (onRefreshUrls != null && (forceRefresh || nearExpiry)) {
                refreshMutex.withLock {
                    val nowMs = System.currentTimeMillis()
                    val latestExp = liveExpiries[item.id] ?: item.urlExpiryEpoch
                    val stillNeeds = forceRefresh || (latestExp > 0L && (nowMs / 1000L) >= (latestExp - 45L))
                    if (stillNeeds && (nowMs - lastRefreshMs.get() >= 15_000L)) {
                        runCatching {
                            val refreshed = onRefreshUrls.invoke()
                            if (refreshed.isNotEmpty()) {
                                refreshed.forEach { (id, pair) ->
                                    if (pair.first.startsWith("http")) {
                                        liveUrls[id] = pair.first
                                        if (pair.second > 0L) liveExpiries[id] = pair.second
                                    }
                                }
                                lastRefreshMs.set(System.currentTimeMillis())
                                logDl("Auto-refreshed ${refreshed.size} Microsoft CDN download URLs mid-batch")
                            }
                        }
                    }
                }
            }
            return liveUrls[item.id] ?: item.url
        }

        class FileTracker(val item: TurboBatchItem, val sliceCount: Int) {
            val completedSlices = ConcurrentHashMap.newKeySet<Int>()
            val remainingSlices = AtomicInteger(sliceCount)
            val fallbackSingleStream = AtomicBoolean(false)
            val finalized = AtomicBoolean(false)
            val verifyRetries = AtomicInteger(0)
            val lock = Any()

            fun persistMeta() {
                if (sliceCount <= 1) return
                synchronized(lock) {
                    runCatching {
                        val obj = JSONObject().apply {
                            put("totalBytes", item.expectedSize)
                            put("sliceCount", sliceCount)
                            put("doneSlices", JSONArray().apply { completedSlices.sorted().forEach { put(it) } })
                        }
                        item.metaFile.writeText(obj.toString(), Charsets.UTF_8)
                    }
                }
            }
        }

        data class WorkSlice(
            val tracker: FileTracker,
            val sliceIndex: Int,
            val start: Long,
            val end: Long,
            val isRanged: Boolean
        )

        val queue = ConcurrentLinkedQueue<WorkSlice>()
        val trackers = mutableListOf<FileTracker>()

        val ordered = items.sortedByDescending { it.expectedSize }
        for (item in ordered) {
            val sz = item.expectedSize
            if (sz >= 4L * 1024L * 1024L && workerCount > 1) {
                val sliceSize = if (sz >= 64L * 1024L * 1024L) 8L * 1024L * 1024L else 4L * 1024L * 1024L
                val count = ((sz + sliceSize - 1L) / sliceSize).toInt().coerceAtLeast(1)
                val tracker = FileTracker(item, count)

                val doneSet = mutableSetOf<Int>()
                if (item.partFile.exists() && item.metaFile.exists() && item.partFile.length() == sz) {
                    runCatching {
                        val obj = JSONObject(item.metaFile.readText(Charsets.UTF_8))
                        if (obj.optLong("totalBytes") == sz && obj.optInt("sliceCount") == count) {
                            val arr = obj.optJSONArray("doneSlices")
                            if (arr != null) {
                                for (k in 0 until arr.length()) {
                                    val idx = arr.optInt(k, -1)
                                    if (idx in 0 until count) doneSet.add(idx)
                                }
                            }
                        }
                    }
                }

                if (doneSet.isEmpty()) {
                    item.partFile.parentFile?.mkdirs()
                    RandomAccessFile(item.partFile, "rw").use { it.setLength(sz) }
                } else {
                    tracker.completedSlices.addAll(doneSet)
                    tracker.remainingSlices.set((count - doneSet.size).coerceAtLeast(0))
                    val restoredBytes = doneSet.sumOf { idx ->
                        val s = idx.toLong() * sliceSize
                        val e = minOf(sz - 1L, s + sliceSize - 1L)
                        (e - s + 1L)
                    }
                    totalCounter.addAndGet(restoredBytes)
                }

                if (tracker.remainingSlices.get() == 0) {
                    val resumeErr = runCatching { verifyAndFinalize(tracker.item, bufSize) }.exceptionOrNull()
                    if (resumeErr == null) {
                        onFileVerified(tracker.item)
                    } else {
                        logDl("Cached partial ${item.id} failed verification (${resumeErr.message}) — re-downloading cleanly")
                        totalCounter.addAndGet(-sz)
                        item.partFile.delete()
                        item.metaFile.delete()
                        tracker.completedSlices.clear()
                        tracker.remainingSlices.set(count)
                        trackers.add(tracker)
                        item.partFile.parentFile?.mkdirs()
                        RandomAccessFile(item.partFile, "rw").use { it.setLength(sz) }
                        for (idx in 0 until count) {
                            val s = idx.toLong() * sliceSize
                            val e = minOf(sz - 1L, s + sliceSize - 1L)
                            queue.add(WorkSlice(tracker, idx, s, e, isRanged = true))
                        }
                    }
                } else {
                    trackers.add(tracker)
                    for (idx in 0 until count) {
                        if (idx in doneSet) continue
                        val s = idx.toLong() * sliceSize
                        val e = minOf(sz - 1L, s + sliceSize - 1L)
                        queue.add(WorkSlice(tracker, idx, s, e, isRanged = true))
                    }
                }
            } else {
                val tracker = FileTracker(item, 1)
                trackers.add(tracker)
                queue.add(WorkSlice(tracker, 0, 0L, (sz - 1L).coerceAtLeast(0L), isRanged = false))
            }
        }

        if (queue.isEmpty()) return@withContext

        coroutineScope {
            (0 until workerCount).map {
                async(Dispatchers.IO) {
                    val buf = ByteArray(bufSize)
                    while (!pausedFlag.get() && !cancelledFlag.get()) {
                        val task = queue.poll() ?: break
                        val tracker = task.tracker
                        val item = tracker.item

                        if (task.isRanged && tracker.fallbackSingleStream.get()) continue

                        activeStreamsOut.incrementAndGet()
                        try {
                            if (task.isRanged) {
                                var attempt = 0
                                var sliceDone = false
                                while (!sliceDone && !pausedFlag.get() && !cancelledFlag.get()) {
                                    var writtenThisAttempt = 0L
                                    try {
                                        val activeUrl = resolveFreshUrl(item, forceRefresh = false)
                                        val conn = openFollowingRedirects(activeUrl, "GET", rangeHeader = "bytes=${task.start}-${task.end}")
                                        var keepAliveReusable = false
                                        try {
                                            val code = conn.responseCode
                                            if (code == 403 || code == 410) throw IllegalStateException("EXPIRED_$code")
                                            if (code == 429 || code == 503) throw IllegalStateException("THROTTLE_$code")
                                            if (code == 200 && task.start > 0L) {
                                                if (tracker.fallbackSingleStream.compareAndSet(false, true)) {
                                                    val isolatedPart = File(item.partFile.parentFile, "${item.partFile.name}.single")
                                                    val isolatedItem = item.copy(partFile = isolatedPart)
                                                    val okSingle = downloadSingleKeepAlive(isolatedItem, buf, pausedFlag, cancelledFlag, totalCounter, ::resolveFreshUrl)
                                                    if (okSingle && tracker.finalized.compareAndSet(false, true)) {
                                                        item.partFile.delete()
                                                        if (!isolatedPart.renameTo(item.partFile)) {
                                                            isolatedPart.copyTo(item.partFile, overwrite = true)
                                                            isolatedPart.delete()
                                                        }
                                                        item.metaFile.delete()
                                                        onFileVerified(item)
                                                    } else {
                                                        isolatedPart.delete()
                                                    }
                                                }
                                                sliceDone = true
                                                break
                                            }
                                            if (code != 206 && code != 200) throw IllegalStateException("HTTP $code")
                                            RandomAccessFile(item.partFile, "rw").use { raf ->
                                                raf.seek(task.start)
                                                val expectedLen = task.end - task.start + 1L
                                                conn.inputStream.use { input ->
                                                    while (writtenThisAttempt < expectedLen && !pausedFlag.get() && !cancelledFlag.get()) {
                                                        val toRead = minOf(buf.size.toLong(), expectedLen - writtenThisAttempt).toInt()
                                                        val r = input.read(buf, 0, toRead)
                                                        if (r <= 0) break
                                                        raf.write(buf, 0, r)
                                                        writtenThisAttempt += r.toLong()
                                                        totalCounter.addAndGet(r.toLong())
                                                    }
                                                }
                                                if (!pausedFlag.get() && !cancelledFlag.get() && writtenThisAttempt < expectedLen) {
                                                    throw IllegalStateException("Truncated range slice ($writtenThisAttempt/$expectedLen)")
                                                }
                                                if (!pausedFlag.get() && !cancelledFlag.get()) keepAliveReusable = true
                                            }
                                        } finally {
                                            if (!keepAliveReusable) conn.disconnect()
                                        }
                                        if (pausedFlag.get() || cancelledFlag.get()) {
                                            totalCounter.addAndGet(-writtenThisAttempt)
                                            break
                                        }
                                        sliceDone = true
                                    } catch (e: CancellationException) {
                                        if (writtenThisAttempt > 0L) totalCounter.addAndGet(-writtenThisAttempt)
                                        throw e
                                    } catch (e: Exception) {
                                        if (writtenThisAttempt > 0L) totalCounter.addAndGet(-writtenThisAttempt)
                                        if (pausedFlag.get() || cancelledFlag.get()) break
                                        attempt++
                                        if (attempt > 6) throw IllegalStateException("${item.partFile.name}: ${e.message}")
                                        val isExpired = e.message?.startsWith("EXPIRED_") == true
                                        if (isExpired && onRefreshUrls != null) {
                                            resolveFreshUrl(item, forceRefresh = true)
                                            delay(400L)
                                        } else {
                                            val isThrottle = e.message?.startsWith("THROTTLE_") == true
                                            val jitter = (System.nanoTime() and 0xFFL)
                                            delay((if (isThrottle) 700L * attempt else 500L * attempt) + jitter)
                                        }
                                    }
                                }
                                if (sliceDone && !tracker.fallbackSingleStream.get() && !pausedFlag.get() && !cancelledFlag.get()) {
                                    tracker.completedSlices.add(task.sliceIndex)
                                    val left = tracker.remainingSlices.decrementAndGet()
                                    if (left > 0) {
                                        if (tracker.completedSlices.size % 2 == 0) tracker.persistMeta()
                                    } else if (tracker.finalized.compareAndSet(false, true)) {
                                        val verifyErr = runCatching { verifyAndFinalize(item, bufSize) }.exceptionOrNull()
                                        if (verifyErr == null) {
                                            onFileVerified(item)
                                        } else {
                                            val retryNum = tracker.verifyRetries.incrementAndGet()
                                            if (retryNum > 2) {
                                                throw IllegalStateException("${item.id}: ${verifyErr.message ?: "Integrity check failed"} (after $retryNum retries)")
                                            }
                                            logDl("Integrity mismatch on ${item.id} (${verifyErr.message}) — retrying clean single-stream download ($retryNum/2)")
                                            totalCounter.addAndGet(-item.expectedSize.coerceAtLeast(0L))
                                            item.partFile.delete()
                                            item.metaFile.delete()
                                            tracker.completedSlices.clear()
                                            tracker.remainingSlices.set(1)
                                            tracker.finalized.set(false)
                                            tracker.fallbackSingleStream.set(true)
                                            queue.add(WorkSlice(tracker, 0, 0L, (item.expectedSize - 1L).coerceAtLeast(0L), isRanged = false))
                                        }
                                    }
                                }
                            } else {
                                val ok = downloadSingleKeepAlive(item, buf, pausedFlag, cancelledFlag, totalCounter, ::resolveFreshUrl)
                                if (ok && tracker.finalized.compareAndSet(false, true)) {
                                    item.metaFile.delete()
                                    onFileVerified(item)
                                }
                            }
                        } finally {
                            activeStreamsOut.decrementAndGet()
                        }
                    }
                }
            }.awaitAll()
        }

        if (pausedFlag.get()) {
            trackers.forEach { if (!it.finalized.get()) it.persistMeta() }
        }
    }

    private suspend fun downloadSingleKeepAlive(
        item: TurboBatchItem,
        buf: ByteArray,
        pausedFlag: AtomicBoolean,
        cancelledFlag: AtomicBoolean,
        totalCounter: AtomicLong,
        urlResolver: suspend (TurboBatchItem, Boolean) -> String
    ): Boolean {
        val cleanSha1 = item.expectedSha1.trim().lowercase()
        var attempt = 0
        while (!pausedFlag.get() && !cancelledFlag.get()) {
            var written = 0L
            try {
                val md = if (cleanSha1.length == 40) MessageDigest.getInstance("SHA-1") else null
                val activeUrl = urlResolver(item, false)
                val conn = openFollowingRedirects(activeUrl, "GET")
                var keepAliveReusable = false
                try {
                    val code = conn.responseCode
                    if (code == 403 || code == 410) throw IllegalStateException("EXPIRED_$code")
                    if (code == 429 || code == 503) throw IllegalStateException("THROTTLE_$code")
                    if (code !in 200..299) throw IllegalStateException("HTTP $code")
                    item.partFile.parentFile?.mkdirs()
                    RandomAccessFile(item.partFile, "rw").use { raf ->
                        raf.setLength(0L)
                        conn.inputStream.use { input ->
                            while (!pausedFlag.get() && !cancelledFlag.get()) {
                                val r = input.read(buf)
                                if (r <= 0) break
                                raf.write(buf, 0, r)
                                md?.update(buf, 0, r)
                                written += r.toLong()
                                totalCounter.addAndGet(r.toLong())
                            }
                        }
                    }
                    if (!pausedFlag.get() && !cancelledFlag.get()) keepAliveReusable = true
                } finally {
                    if (!keepAliveReusable) conn.disconnect()
                }
                if (pausedFlag.get() || cancelledFlag.get()) {
                    item.partFile.delete()
                    totalCounter.addAndGet(-written)
                    return false
                }
                if (written <= 0L) {
                    throw IllegalStateException("Empty download (0 bytes)")
                }
                if (item.expectedSize > 0L && written != item.expectedSize) {
                    throw IllegalStateException("Size mismatch ($written/${item.expectedSize})")
                }
                if (md != null) {
                    val hex = md.digest().joinToString("") { "%02x".format(it) }
                    if (!hex.equals(cleanSha1, ignoreCase = true)) {
                        throw IllegalStateException("SHA-1 mismatch")
                    }
                }
                return true
            } catch (e: CancellationException) {
                item.partFile.delete()
                if (written > 0L) totalCounter.addAndGet(-written)
                throw e
            } catch (e: Exception) {
                item.partFile.delete()
                if (written > 0L) totalCounter.addAndGet(-written)
                if (pausedFlag.get() || cancelledFlag.get()) return false
                attempt++
                if (attempt > 6) throw IllegalStateException("${item.partFile.name}: ${e.message}")
                val isExpired = e.message?.startsWith("EXPIRED_") == true
                if (isExpired) {
                    urlResolver(item, true)
                    delay(400L)
                } else {
                    val isThrottle = e.message?.startsWith("THROTTLE_") == true
                    delay(if (isThrottle) 800L * attempt else 500L * attempt)
                }
            }
        }
        return false
    }

    private fun verifyAndFinalize(item: TurboBatchItem, bufSize: Int) {
        val actualLen = if (item.partFile.exists()) item.partFile.length() else -1L
        if (actualLen <= 0L || (item.expectedSize > 0L && actualLen != item.expectedSize)) {
            item.partFile.delete()
            item.metaFile.delete()
            throw IllegalStateException("Size mismatch on ${item.partFile.name} ($actualLen/${item.expectedSize})")
        }
        val cleanSha1 = item.expectedSha1.trim().lowercase()
        if (cleanSha1.length == 40) {
            val md = MessageDigest.getInstance("SHA-1")
            val vBuf = ByteArray(bufSize)
            item.partFile.inputStream().use { input ->
                while (true) {
                    val r = input.read(vBuf)
                    if (r <= 0) break
                    md.update(vBuf, 0, r)
                }
            }
            val hex = md.digest().joinToString("") { "%02x".format(it) }
            if (!hex.equals(cleanSha1, ignoreCase = true)) {
                item.partFile.delete()
                item.metaFile.delete()
                throw IllegalStateException("SHA-1 mismatch on ${item.partFile.name}")
            }
        }
        item.metaFile.delete()
    }
}
