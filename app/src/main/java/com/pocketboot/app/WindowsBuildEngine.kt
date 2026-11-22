package com.pocketboot.app

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

enum class WinFamilyFilter(val label: String, val shortTag: String) {
    WIN11("Windows 11", "Win11"), WIN10("Windows 10", "Win10"), SERVER("Windows Server", "Server")
}

enum class WinFeatureVersion(
    val family: WinFamilyFilter, val label: String, val searchTerms: List<String>, val buildPrefixes: List<String>, val recommended: Boolean = false
) {
    W11_26H2(WinFamilyFilter.WIN11, "26H2", listOf("Windows 11 26H2", "26300", "26320"), listOf("26300", "26320", "26340"), recommended = true),
    W11_26H1(WinFamilyFilter.WIN11, "26H1", listOf("Windows 11 26H1", "28000", "28020"), listOf("28000", "28020", "28120")),
    W11_25H2(WinFamilyFilter.WIN11, "25H2", listOf("Windows 11 25H2", "26200"), listOf("26200")),
    W11_24H2(WinFamilyFilter.WIN11, "24H2", listOf("Windows 11 24H2", "26100"), listOf("26100")),
    W11_23H2(WinFamilyFilter.WIN11, "23H2", listOf("Windows 11 23H2", "22631"), listOf("22631", "22635")),
    W11_22H2(WinFamilyFilter.WIN11, "22H2", listOf("Windows 11 22H2", "22621"), listOf("22621")),
    W11_21H2(WinFamilyFilter.WIN11, "21H2", listOf("Windows 11 21H2", "22000"), listOf("22000")),
    W11_INSIDER(WinFamilyFilter.WIN11, "Insider", listOf("Windows 11 Insider", "Canary"), listOf("29", "27", "26340", "28120")),
    W10_22H2(WinFamilyFilter.WIN10, "22H2", listOf("Windows 10 22H2", "19045"), listOf("19045"), recommended = true),
    W10_21H2(WinFamilyFilter.WIN10, "21H2 / LTSC", listOf("Windows 10 21H2", "19044"), listOf("19044")),
    W10_21H1(WinFamilyFilter.WIN10, "21H1 / 20H2", listOf("Windows 10 21H1", "19043", "19042"), listOf("19043", "19042")),
    W10_1909(WinFamilyFilter.WIN10, "1909 / 1809", listOf("Windows 10 1909", "18363", "17763"), listOf("18363", "17763")),
    SRV_2025(WinFamilyFilter.SERVER, "2025", listOf("Windows Server 2025", "Server 26100"), listOf("26100", "26300", "28000"), recommended = true),
    SRV_2022(WinFamilyFilter.SERVER, "2022", listOf("Windows Server 2022", "20348"), listOf("20348")),
    SRV_2019(WinFamilyFilter.SERVER, "2019", listOf("Windows Server 2019", "17763"), listOf("17763")),
    SRV_2016(WinFamilyFilter.SERVER, "2016 / vNext", listOf("Windows Server", "26040", "14393"), listOf("26040", "14393"));
    companion object { fun forFamily(family: WinFamilyFilter): List<WinFeatureVersion> = entries.filter { it.family == family } }
}

enum class WinBuildTier(val sectionTitle: String, val badgeLabel: String) {
    STABLE("Recommended · Stable & Latest", "Stable"),
    PREVIEW("Release Preview & Beta", "Preview"),
    INSIDER("Dev & Canary Insider", "Insider")
}

data class GroupedWindowsBuilds(
    val stable: List<WindowsChannelBuild> = emptyList(),
    val preview: List<WindowsChannelBuild> = emptyList(),
    val insider: List<WindowsChannelBuild> = emptyList(),
    val hiddenOlderCount: Int = 0
) {
    val isEmpty: Boolean get() = stable.isEmpty() && preview.isEmpty() && insider.isEmpty()
    val totalCount: Int get() = stable.size + preview.size + insider.size
    val allBuilds: List<WindowsChannelBuild> get() = (stable + preview + insider).sortedByDescending { it.createdEpoch }

    fun groupedByYear(): List<Pair<Int, List<WindowsChannelBuild>>> =
        allBuilds
            .groupBy { it.createdYear }
            .entries
            .sortedWith(compareByDescending<Map.Entry<Int, List<WindowsChannelBuild>>> { it.key > 0 }.thenByDescending { it.key })
            .map { it.key to it.value }
}

enum class WinDownloadMethod(val label: String, val subtitle: String) {
    DOWNLOAD_AND_BUILD("Download & Build ISO / USB", "Downloads via Turbo then builds in separate Builder queue"),
    DOWNLOAD_PACKAGE_SET("Download UUP Package Set Only", "Saves raw SHA-1 verified .esd/.cab folder"),
    DOWNLOAD_UPDATES_ONLY("Download Additional Updates Only", "Saves only .msu / KB*.cab / .NET update files")
}

enum class WinIsoCompression(val label: String, val subtitle: String, val ext: String, val wimlibArg: String, val recommended: Boolean = false) {
    WIM("WIM · Fast", "~2–4 min · low RAM usage", "wim", "--compress=maximum", recommended = true),
    ESD("ESD · Solid Compact", "~5–9 min · smallest ISO (LZMS solid)", "esd", "--compress=LZMS --solid --threads=2", recommended = false)
}

enum class WinBuildOutputMode(val label: String, val subtitle: String, val recommended: Boolean = false) {
    STANDARD_ISO("Bootable ISO (.iso)", "Standard UEFI + Legacy BIOS installer", recommended = true),
    DIRECT_MULTIBOOT("Add to Multi-Boot (.img)", "Moves finished ISO into your Multi-Boot drive"),
    DIRECT_NTFS_USB("Direct Bootable USB (.img)", "GPT/MBR NTFS + full Experience, WinUtil & Drivers")
}

data class WindowsChannelBuild(
    val uuid: String, val title: String, val build: String, val arch: String,
    val createdEpoch: Long, val tier: WinBuildTier = WinBuildTier.STABLE, val channelBadge: String = "Stable"
) {
    val archDisplay: String get() = when (arch.lowercase()) { "amd64" -> "x64"; "arm64" -> "ARM64"; "x86" -> "x86"; else -> arch.uppercase() }
    val createdYear: Int get() = if (createdEpoch > 0L) runCatching {
        Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = createdEpoch * 1000L }.get(Calendar.YEAR)
    }.getOrDefault(0) else 0
    val createdDateLabel: String get() = if (createdEpoch > 0L) runCatching { SimpleDateFormat("MMM d, yyyy", Locale.US).format(Date(createdEpoch * 1000L)) }.getOrDefault("") else ""
    val dateDisplay: String get() = createdDateLabel
}

data class WinBuildLangOption(val code: String, val displayName: String)
data class WinBuildEditionOption(val code: String, val displayName: String)
enum class UupDataSource(val label: String) { UUPDUMP_LIVE("UUPdump Live"), DIRECT_MICROSOFT("Direct Microsoft"), OFFLINE_CATALOG("Offline Catalog") }
enum class WinPackageKind(val badge: String) { BASE_OS("Base OS"), UPDATE("Update"), STORE_APP("Store App"), NETFX3(".NET 3.5") }
data class WinPackageFileEntry(val name: String, val url: String, val sizeBytes: Long, val sha1: String, val kind: WinPackageKind = WinPackageKind.BASE_OS, val expireEpochSec: Long = 0L)
data class WinManifestEstimate(val fileCount: Int, val totalBytes: Long)

data class WinDetailedManifest(
    val baseFiles: List<WinPackageFileEntry> = emptyList(),
    val updateFiles: List<WinPackageFileEntry> = emptyList(),
    val appFiles: List<WinPackageFileEntry> = emptyList(),
    val netFx3Files: List<WinPackageFileEntry> = emptyList(),
    val allFiles: List<WinPackageFileEntry> = emptyList()
) {
    val baseBytes: Long get() = baseFiles.sumOf { it.sizeBytes }
    val updateBytes: Long get() = updateFiles.sumOf { it.sizeBytes }
    val appBytes: Long get() = appFiles.sumOf { it.sizeBytes }
    val netFx3Bytes: Long get() = netFx3Files.sumOf { it.sizeBytes }
    val hasUpdates: Boolean get() = updateFiles.isNotEmpty()
    val hasApps: Boolean get() = appFiles.isNotEmpty()
    val hasNetFx3: Boolean get() = netFx3Files.isNotEmpty()
}

data class WindowsChannelBuildConfig(
    val build: WindowsChannelBuild, val lang: WinBuildLangOption, val editions: List<WinBuildEditionOption>,
    val compression: WinIsoCompression = WinIsoCompression.WIM,
    val outputMode: WinBuildOutputMode = WinBuildOutputMode.STANDARD_ISO,
    val downloadMethod: WinDownloadMethod = WinDownloadMethod.DOWNLOAD_AND_BUILD,
    val targetDir: String, val customOutputName: String = "", val targetMultiBootPath: String = "",
    val connections: Int = 8, val autoMount: Boolean = true,
    val includeUpdates: Boolean = false, val includeStoreApps: Boolean = false,
    val integrateNetFx3: Boolean = false, val runComponentCleanup: Boolean = false,
    val skipWinRe: Boolean = false, val eiCfgUnlock: Boolean = false, val keepPackagesAfterBuild: Boolean = false,
    val scheme: PartitionScheme = PartitionScheme.GPT,
    val experience: WindowsExperienceConfig = WindowsExperienceConfig(),
    val winUtil: WinUtilTweaksConfig = WinUtilTweaksConfig(),
    val bypassRequirements: Boolean = experience.bypassRequirements,
    val bypassMicrosoftAccount: Boolean = experience.bypassMicrosoftAccount,
    val disableDataCollection: Boolean = experience.disableDataCollection,
    val disableBitLocker: Boolean = experience.disableBitLocker,
    val extraDriversDir: String = "",
    val virtualEditions: List<String> = emptyList(),
    val localUupDirPath: String = ""
)

data class PendingWindowsBuild(
    val workRootPath: String, val workPrefix: String, val title: String, val outputName: String,
    val cachedBytes: Long, val totalBytes: Long, val isCachedReady: Boolean = false, val config: WindowsChannelBuildConfig
) {
    val progress: Float get() = if (totalBytes > 0L) (cachedBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f) else 0f
}

data class WindowsBuildJobState(
    val title: String, val stage: String, val detail: String, val progress: Float = 0f,
    val downloadedBytes: Long = 0L, val totalBytes: Long = 0L, val speedBytesPerSec: Long = 0L,
    val etaSeconds: Long = -1L, val activeThreads: Int = 8, val filesDone: Int = 0, val filesTotal: Int = 0,
    val isConverting: Boolean = false, val isPaused: Boolean = false, val autoMount: Boolean = true, val workDirPath: String = ""
)

data class WindowsConversionJobState(
    val title: String, val outputFileName: String, val outputMode: WinBuildOutputMode,
    val currentStep: Int = 1, val stageLabel: String = "Preparing build...", val detailText: String = "",
    val overallProgress: Float = 0f, val diskSpeedBytesPerSec: Long = 0L, val elapsedSeconds: Long = 0L,
    val etaSeconds: Long = -1L, val currentOutputBytes: Long = 0L, val freeSpaceBytes: Long = 0L,
    val activeBinary: String = "", val batteryPct: Int = 100, val stepDurationsSec: Map<Int, Long> = emptyMap(),
    val consoleTail: List<String> = emptyList(), val queuedCount: Int = 0, val queuedTitles: List<String> = emptyList(),
    val autoMount: Boolean = true, val workDirPath: String = ""
)

data class WindowsBuildToolsStatus(val cabextract: String?, val wimlib: String?, val chntpw: String?, val xorriso: String?) {
    val canConvertIso: Boolean get() = cabextract != null && wimlib != null && chntpw != null && xorriso != null
    val canBuildDirectUsb: Boolean get() = cabextract != null && wimlib != null && chntpw != null
}

private data class QueuedConversionJob(
    val context: Context, val config: WindowsChannelBuildConfig, val workRootPath: String, val workPrefix: String,
    val jobTitle: String, val totalDownloadBytes: Long, val hasUpdates: Boolean, val hasApps: Boolean, val hasNetFx3: Boolean,
    val isReadOnlySource: Boolean = false, val pkgDirOverride: String = "",
    val onComplete: (DownloadCompletionEvent) -> Unit, val onError: (String) -> Unit, val onLog: (LogEntry) -> Unit
)

object WindowsBuildEngine {
    private const val DEFAULT_API_BASE = "https://api.uupdump.net"
    private const val GITHUB_RAW_BASE = "https://raw.githubusercontent.com/bilered/PocketBoot/main"
    private const val UA = "Mozilla/5.0 (Linux; Android 15) PocketBoot/4.5.0"

    @Volatile private var apiBaseUrl: String = DEFAULT_API_BASE
    @Volatile private var preferDirectMicrosoft: Boolean = false
    @Volatile private var uupDumpBackoffUntilMs: Long = 0L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _downloadState = MutableStateFlow<WindowsBuildJobState?>(null)
    val downloadState: StateFlow<WindowsBuildJobState?> = _downloadState.asStateFlow()

    private val _conversionState = MutableStateFlow<WindowsConversionJobState?>(null)
    val conversionState: StateFlow<WindowsConversionJobState?> = _conversionState.asStateFlow()

    private val _activeSource = MutableStateFlow(UupDataSource.UUPDUMP_LIVE)
    val activeSource: StateFlow<UupDataSource> = _activeSource.asStateFlow()

    private val langsCache = ConcurrentHashMap<String, List<WinBuildLangOption>>()
    private val editionsCache = ConcurrentHashMap<String, List<WinBuildEditionOption>>()
    private val detailedManifestCache = ConcurrentHashMap<String, WinDetailedManifest>()
    private val uupDbManifestCache = ConcurrentHashMap<String, Pair<List<WinBuildLangOption>, List<DirectSyncFileMeta>>>()
    private val catalogMetaByUuid = ConcurrentHashMap<String, Triple<String, Int, String>>() // ring, sku, build

    fun configureNetwork(customApiUrl: String, preferDirectMs: Boolean) {
        apiBaseUrl = customApiUrl.trim().trimEnd('/').let {
            if (it.startsWith("http://") || it.startsWith("https://")) it else DEFAULT_API_BASE
        }
        preferDirectMicrosoft = preferDirectMs
        if (preferDirectMs) _activeSource.value = UupDataSource.DIRECT_MICROSOFT
    }

    private var activeDownloadJob: Job? = null
    private val dlCancelledFlag = AtomicBoolean(false)
    private val dlPausedFlag = AtomicBoolean(false)
    @Volatile private var dlAutoMountLive: Boolean = true
    private var dlContext: Context? = null
    private var dlConfig: WindowsChannelBuildConfig? = null
    private var dlOnComplete: ((DownloadCompletionEvent) -> Unit)? = null
    private var dlOnError: ((String) -> Unit)? = null
    private var dlOnLog: ((LogEntry) -> Unit)? = null
    private var activeOnLog: ((LogEntry) -> Unit)? = null
    private var dlWorkRootPath: String = ""
    private var dlWorkPrefix: String = ""

    private val conversionQueue = ConcurrentLinkedQueue<QueuedConversionJob>()
    private var activeConversionJob: Job? = null
    private val convCancelledFlag = AtomicBoolean(false)
    @Volatile private var convAutoMountLive: Boolean = true
    private var convContext: Context? = null
    private var convOnLog: ((LogEntry) -> Unit)? = null

    private val JUNK_TITLE_PATTERNS = listOf(
        "critical oobe", "dynamic update", "safe os", "setup dynamic",
        ".net framework", "microsoft edge", "security intelligence", "defender",
        "windows update stack", "servicing stack", "language pack", "feature on demand", "hotpatch"
    )

    private fun nowTs(): String = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())

    private fun shellQuote(value: String): String = "'${value.replace("'", "'\\''")}'"

    /**
     * Executes a multi-line script inside an isolated child `/system/bin/sh` process.
     * Prevents `set -e` or `exec` from killing libsu's persistent root shell and always
     * merges stderr into stdout so errors are never blank.
     */
    internal fun runIsolatedRootScript(context: Context, tag: String, scriptBody: String): Shell.Result {
        val stamp = System.nanoTime()
        val localScript = File(context.applicationContext.cacheDir, "pb_${tag}_$stamp.sh")
        val rootScriptPath = "/data/local/tmp/pb_${tag}_$stamp.sh"
        val qLocal = shellQuote(localScript.absolutePath)
        val qRoot = shellQuote(rootScriptPath)
        return try {
            localScript.writeText("#!/system/bin/sh\n$scriptBody\n", Charsets.UTF_8)
            val prep = Shell.cmd("cp -f $qLocal $qRoot 2>&1 && chmod 700 $qRoot 2>&1").exec()
            if (!prep.isSuccess) return prep
            Shell.cmd("sh $qRoot 2>&1").exec()
        } finally {
            localScript.delete()
            Shell.cmd("rm -f $qRoot 2>/dev/null || true").exec()
        }
    }

    private fun stageCacheDir(context: Context, workPrefix: String): File {
        val base = context.applicationContext.getExternalFilesDir(null) ?: context.applicationContext.cacheDir
        return File(File(base, "pb_winbuild_cache"), workPrefix).apply { mkdirs() }
    }

    private fun emitLog(tag: String, msg: String, isError: Boolean = false) {
        activeOnLog?.invoke(LogEntry(nowTs(), tag, msg, isError))
    }

    fun inspectTools(context: Context): WindowsBuildToolsStatus = WindowsBuildToolsStatus(
        cabextract = WindowsImageBuilder.findBundledBinary(context, "libcabextract.so", listOf("cabextract")),
        wimlib = WindowsImageBuilder.findBundledBinary(context, "libwimlib_imagex.so", listOf("wimlib-imagex")),
        chntpw = WindowsImageBuilder.findBundledBinary(context, "libchntpw.so", listOf("chntpw", "reged")),
        xorriso = WindowsImageBuilder.findBundledBinary(context, "libxorriso.so", listOf("xorriso", "mkisofs", "genisoimage"))
    )

    fun readBatteryPercent(context: Context): Pair<Int, Boolean> {
        return runCatching {
            val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
            val plugged = (intent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
            val pct = if (level >= 0 && scale > 0) (level * 100) / scale else 100
            pct to plugged
        }.getOrDefault(100 to false)
    }

    fun defaultOutputFileName(
        family: WinFamilyFilter,
        featureVer: WinFeatureVersion,
        build: WindowsChannelBuild,
        langCode: String,
        outputMode: WinBuildOutputMode
    ): String {
        val verTag = featureVer.label.replace(Regex("[^A-Za-z0-9]"), "")
        val slug = "${family.shortTag}_${verTag}_${build.build}_${build.archDisplay}_${langCode}"
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
        return if (outputMode == WinBuildOutputMode.DIRECT_NTFS_USB) "${slug}_USB.img" else "$slug.iso"
    }

    private fun fetchJsonWithRetry(urlStr: String, timeoutMs: Int = 10_000): JSONObject? {
        for (attempt in 0..2) {
            val res = runCatching {
                val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = timeoutMs
                    readTimeout = timeoutMs + 6_000
                    instanceFollowRedirects = true
                    setRequestProperty("User-Agent", UA)
                    setRequestProperty("Accept", "application/json")
                }
                try {
                    val code = conn.responseCode
                    if (code == 429) return@runCatching "RATE_LIMIT" to null
                    if (code in 200..299) {
                        val txt = conn.inputStream.bufferedReader().use { it.readText() }
                        val obj = JSONObject(txt)
                        val err = obj.optJSONObject("response")?.optString("error", "").orEmpty()
                        if (err.equals("TOO_MANY_REQUESTS", true)) return@runCatching "RATE_LIMIT" to null
                        "OK" to obj
                    } else {
                        "ERR" to null
                    }
                } finally {
                    conn.disconnect()
                }
            }.getOrDefault("ERR" to null)

            if (res.first == "OK") return res.second
            if (res.first == "RATE_LIMIT") {
                if (attempt < 2) {
                    Thread.sleep(1400L * (attempt + 1))
                    continue
                }
                // Back off UUPdump for 90s on persistent 429 so Direct Microsoft takes over immediately
                uupDumpBackoffUntilMs = System.currentTimeMillis() + 90_000L
                return null
            }
            if (attempt < 1) Thread.sleep(450L) else break
        }
        return null
    }

    private fun shouldUseUupDumpApi(): Boolean =
        !preferDirectMicrosoft && System.currentTimeMillis() >= uupDumpBackoffUntilMs

    private fun isJunkStubTitle(title: String): Boolean {
        val low = title.lowercase()
        return JUNK_TITLE_PATTERNS.any { low.contains(it) }
    }

    private fun isStandaloneCumulativeUpdate(title: String): Boolean {
        val low = title.lowercase().trim()
        return low.startsWith("update for ") ||
            low.startsWith("cumulative update") ||
            (Regex("""^20\d{2}-\d{2}\s+""").containsMatchIn(low) && low.contains("update for"))
    }

    private fun cleanNormalizedTitleKey(title: String, build: String, arch: String): String {
        val noDupSuffix = title.replace(Regex("""\s*\(\d+\)\s*$"""), "").trim().lowercase()
        return "$noDupSuffix|$build|${arch.lowercase()}"
    }

    private fun classifyBuildTier(title: String, build: String): Pair<WinBuildTier, String> {
        val low = title.lowercase()
        val major = build.substringBefore('.').toIntOrNull() ?: 0
        return when {
            low.contains("canary") || major >= 29000 || major in 27000..27999 -> WinBuildTier.INSIDER to "Canary"
            low.contains("dev") || major in 26340..26999 || major in 28100..28999 -> WinBuildTier.INSIDER to "Dev"
            low.contains("beta") || major in 26320..26339 || major in 28020..28099 || major == 22635 -> WinBuildTier.INSIDER to "Beta"
            low.contains("insider") -> WinBuildTier.INSIDER to "Insider"
            low.contains("release preview") || low.contains("preview update") || low.contains("rp") -> WinBuildTier.PREVIEW to "Preview"
            else -> WinBuildTier.STABLE to "Stable"
        }
    }

    /** Loads offline baseline builds from bundled assets/uup-builds.json + cached JSON + live SyncUpdates memory cache. */
    fun loadOfflineCatalogBuilds(context: Context?, cachedJson: String = ""): List<WindowsChannelBuild> {
        val out = LinkedHashMap<String, WindowsChannelBuild>()
        fun ingestJson(raw: String) {
            if (raw.isBlank()) return
            runCatching {
                val arr = JSONObject(raw).optJSONArray("builds") ?: return@runCatching
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val uuid = o.optString("uuid", "").trim()
                    val title = o.optString("title", "").trim()
                    val build = o.optString("build", "").trim()
                    val arch = o.optString("arch", "amd64").trim()
                    val created = o.optLong("created", 0L)
                    if (uuid.isEmpty() || title.isEmpty()) continue
                    val ring = o.optString("ring", "RETAIL")
                    val sku = o.optInt("sku", if (title.contains("server", true)) 7 else 48)
                    catalogMetaByUuid[uuid] = Triple(ring, sku, "10.0.$build")
                    val (tier, badge) = classifyBuildTier(title, build)
                    val chBadge = o.optString("channel", badge).ifBlank { badge }
                    val effTier = when (chBadge.lowercase()) {
                        "preview" -> WinBuildTier.PREVIEW
                        "canary", "dev", "beta", "insider" -> WinBuildTier.INSIDER
                        else -> tier
                    }
                    out[uuid] = WindowsChannelBuild(uuid, title, build, arch, created, effTier, chBadge)
                }
            }
        }
        context?.let { ctx ->
            runCatching { ctx.assets.open("uup-builds.json").bufferedReader(Charsets.UTF_8).use { it.readText() } }
                .getOrNull()?.let { ingestJson(it) }
        }
        ingestJson(cachedJson)
        MicrosoftUupClient.syncMetaCache.values.forEach { res ->
            catalogMetaByUuid[res.build.uuid] = Triple(res.ring, res.sku, res.checkBuild)
            out[res.build.uuid] = res.build
        }
        return out.values.sortedByDescending { it.createdEpoch }
    }

    /** Quietly fetches the daily-synced uup-builds.json from GitHub so offline fallback stays fresh even when uupdump is down. */
    suspend fun syncOfflineCatalogFromGitHub(): String? = withContext(Dispatchers.IO) {
        val obj = fetchJsonWithRetry("$GITHUB_RAW_BASE/uup-builds.json", timeoutMs = 8_000) ?: return@withContext null
        val arr = obj.optJSONArray("builds")
        if (arr != null && arr.length() > 0) obj.toString() else null
    }

    private fun fetchGitHubBuildManifest(updateId: String): Pair<List<WinBuildLangOption>, List<DirectSyncFileMeta>>? {
        uupDbManifestCache[updateId]?.let { return it }
        val obj = fetchJsonWithRetry("$GITHUB_RAW_BASE/uup-db/${URLEncoder.encode(updateId, "UTF-8")}.json", timeoutMs = 8_000)
            ?: return null
        val filesObj = obj.optJSONObject("files") ?: return null
        val files = mutableListOf<DirectSyncFileMeta>()
        val keys = filesObj.keys()
        while (keys.hasNext()) {
            val name = keys.next()
            if (MicrosoftUupClient.isUnusablePackageName(name)) continue
            val fObj = filesObj.optJSONObject(name) ?: continue
            val sz = fObj.optLong("size", 0L)
            val sha1 = fObj.optString("sha1", "").lowercase()
            if (sha1.length == 40) files.add(DirectSyncFileMeta(name, sz, sha1))
        }
        if (files.isEmpty()) return null

        val langsArr = obj.optJSONArray("langs")
        val langNames = obj.optJSONObject("langNames") ?: JSONObject()
        val langs = mutableListOf<WinBuildLangOption>()
        if (langsArr != null) {
            for (i in 0 until langsArr.length()) {
                val c = langsArr.optString(i, "").trim()
                if (c.isNotEmpty()) langs.add(WinBuildLangOption(c, langNames.optString(c, c)))
            }
        }
        val effectiveLangs = if (langs.isNotEmpty()) langs else MicrosoftUupClient.deriveLangsFromFileNames(files.map { it.name })
        val pair = effectiveLangs to files
        uupDbManifestCache[updateId] = pair
        return pair
    }

    internal fun filterBuildsToGroups(
        rawBuilds: List<WindowsChannelBuild>,
        family: WinFamilyFilter,
        featureVer: WinFeatureVersion,
        archFilter: String,
        cleanQuery: String,
        hideCumulativeUpdates: Boolean,
        hideOlderBuilds: Boolean = true
    ): GroupedWindowsBuilds {
        val collected = mutableListOf<WindowsChannelBuild>()
        val seenUuids = mutableSetOf<String>()
        val seenNormKeys = mutableSetOf<String>()

        for (b in rawBuilds) {
            if (archFilter != "all" && !b.arch.equals(archFilter, ignoreCase = true)) continue
            if (isJunkStubTitle(b.title)) continue
            if (hideCumulativeUpdates && isStandaloneCumulativeUpdate(b.title)) continue

            val lowTitle = b.title.lowercase()
            val isServerTitle = lowTitle.contains("server") || lowTitle.contains("azure stack")
            val matchesFamily = when (family) {
                WinFamilyFilter.SERVER -> isServerTitle
                WinFamilyFilter.WIN10 -> !isServerTitle && (lowTitle.contains("windows 10") || b.build.startsWith("1904"))
                WinFamilyFilter.WIN11 -> !isServerTitle && !lowTitle.contains("windows 10")
            }
            if (!matchesFamily) continue

            if (cleanQuery.isBlank()) {
                val matchesVersion = featureVer.buildPrefixes.any { pref -> b.build.startsWith(pref) } ||
                    lowTitle.contains(featureVer.label.substringBefore(' ').lowercase())
                if (!matchesVersion) continue
            } else {
                val qLow = cleanQuery.lowercase()
                if (!lowTitle.contains(qLow) && !b.build.lowercase().contains(qLow) && !b.uuid.lowercase().contains(qLow)) continue
            }

            val normKey = cleanNormalizedTitleKey(b.title, b.build, b.arch)
            if (!seenUuids.add(b.uuid) || !seenNormKeys.add(normKey)) continue
            collected.add(b)
        }

        val sortedAll = collected.sortedByDescending { it.createdEpoch }
        val cutoffEpoch = (System.currentTimeMillis() / 1000L) - (365L * 86400L)
        var hiddenOlder = 0
        val effective = if (hideOlderBuilds && cleanQuery.isBlank()) {
            val recent = sortedAll.filter { it.createdEpoch <= 0L || it.createdEpoch >= cutoffEpoch }
            val older = sortedAll.filter { it.createdEpoch in 1L until cutoffEpoch }
            if (recent.isNotEmpty()) {
                hiddenOlder = older.size
                recent
            } else {
                // Legacy Feature Version (all builds >12m old): keep latest 5 so list is not blank
                hiddenOlder = (older.size - 5).coerceAtLeast(0)
                older.take(5)
            }
        } else {
            sortedAll
        }
        val perTierCap = if (hideOlderBuilds) 30 else 80
        return GroupedWindowsBuilds(
            stable = effective.filter { it.tier == WinBuildTier.STABLE }.take(perTierCap),
            preview = effective.filter { it.tier == WinBuildTier.PREVIEW }.take(perTierCap),
            insider = effective.filter { it.tier == WinBuildTier.INSIDER }.take(perTierCap),
            hiddenOlderCount = hiddenOlder
        )
    }

    /** Direct on-phone Microsoft Windows Update query (zero UUPdump dependency). */
    suspend fun fetchLatestDirectFromMicrosoft(
        family: WinFamilyFilter,
        featureVer: WinFeatureVersion,
        archFilter: String = "amd64"
    ): List<WindowsChannelBuild> = withContext(Dispatchers.IO) {
        val effArch = if (archFilter == "all") "amd64" else archFilter
        val sku = if (family == WinFamilyFilter.SERVER) 7 else 48
        val baseline = when (featureVer) {
            WinFeatureVersion.W11_26H2 -> "10.0.26300.1"
            WinFeatureVersion.W11_26H1 -> "10.0.28000.1"
            WinFeatureVersion.W11_25H2 -> "10.0.26200.1"
            WinFeatureVersion.W11_24H2 -> "10.0.26100.1"
            WinFeatureVersion.W11_23H2 -> "10.0.22631.1"
            WinFeatureVersion.W11_22H2 -> "10.0.22621.1"
            WinFeatureVersion.W11_21H2 -> "10.0.22000.1"
            WinFeatureVersion.W11_INSIDER -> "10.0.26300.1"
            WinFeatureVersion.W10_22H2 -> "10.0.19045.1"
            WinFeatureVersion.W10_21H2 -> "10.0.19044.1"
            WinFeatureVersion.W10_21H1 -> "10.0.19043.1"
            WinFeatureVersion.W10_1909 -> "10.0.18363.1"
            WinFeatureVersion.SRV_2025 -> "10.0.26100.1"
            WinFeatureVersion.SRV_2022 -> "10.0.20348.1"
            WinFeatureVersion.SRV_2019 -> "10.0.17763.1"
            WinFeatureVersion.SRV_2016 -> "10.0.14393.1"
        }
        val rings = if (featureVer == WinFeatureVersion.W11_INSIDER) listOf("DEV", "CANARY", "BETA") else listOf("RETAIL", "RP")
        val found = LinkedHashMap<String, WindowsChannelBuild>()
        for (ring in rings) {
            val res = MicrosoftUupClient.syncLatestBuildsDirect(arch = effArch, ring = ring, baselineBuild = baseline, sku = sku)
            res.forEach { item ->
                catalogMetaByUuid[item.build.uuid] = Triple(item.ring, item.sku, item.checkBuild)
                found[item.build.uuid] = item.build
            }
        }
        if (found.isNotEmpty()) _activeSource.value = UupDataSource.DIRECT_MICROSOFT
        found.values.toList()
    }

    suspend fun fetchGroupedBuilds(
        family: WinFamilyFilter,
        featureVer: WinFeatureVersion,
        archFilter: String = "amd64",
        customQuery: String = "",
        context: Context? = null,
        cachedCatalogJson: String = "",
        hideCumulativeUpdates: Boolean = true,
        hideOlderBuilds: Boolean = true
    ): GroupedWindowsBuilds = withContext(Dispatchers.IO) {
        val cleanQuery = customQuery.trim()
        val rawQueries = if (cleanQuery.isNotBlank()) listOf(cleanQuery) else featureVer.searchTerms

        // Layer 1: Try configured UUPdump API if enabled and not rate-limited
        if (shouldUseUupDumpApi()) {
            val collected = mutableListOf<WindowsChannelBuild>()
            val seenUuids = mutableSetOf<String>()
            var anyApiResponse = false

            for (qTerm in rawQueries) {
                val encoded = URLEncoder.encode(qTerm, "UTF-8")
                val root = fetchJsonWithRetry("$apiBaseUrl/listid.php?search=$encoded&sortByDate=1") ?: continue
                anyApiResponse = true
                val respObj = root.optJSONObject("response") ?: continue
                val buildEntries = mutableListOf<JSONObject>()
                respObj.optJSONObject("builds")?.let { bo ->
                    val keys = bo.keys()
                    while (keys.hasNext()) bo.optJSONObject(keys.next())?.let { buildEntries.add(it) }
                } ?: respObj.optJSONArray("builds")?.let { ba ->
                    for (idx in 0 until ba.length()) ba.optJSONObject(idx)?.let { buildEntries.add(it) }
                }
                for (b in buildEntries) {
                    val uuid = b.optString("uuid", "").trim()
                    val title = b.optString("title", "").trim()
                    val build = b.optString("build", "").trim()
                    val arch = b.optString("arch", "amd64").trim()
                    val created = b.optLong("created", 0L)
                    if (uuid.isEmpty() || title.isEmpty() || !seenUuids.add(uuid)) continue
                    val (tier, badge) = classifyBuildTier(title, build)
                    val cleanTitle = title.replace(Regex("""\s*\(\d+\)\s*$"""), "").trim()
                    val ring = when (badge) { "Preview" -> "RP"; "Canary" -> "CANARY"; "Dev" -> "DEV"; "Beta" -> "BETA"; else -> "RETAIL" }
                    val sku = if (title.contains("server", true)) 7 else 48
                    catalogMetaByUuid[uuid] = Triple(ring, sku, "10.0.$build")
                    collected.add(WindowsChannelBuild(uuid, cleanTitle, build, arch, created, tier, badge))
                }
            }
            if (anyApiResponse && collected.isNotEmpty()) {
                val grouped = filterBuildsToGroups(collected, family, featureVer, archFilter, cleanQuery, hideCumulativeUpdates, hideOlderBuilds)
                if (!grouped.isEmpty) {
                    _activeSource.value = UupDataSource.UUPDUMP_LIVE
                    return@withContext grouped
                }
            }
        }

        // Layer 2: Automatic Failover -> Local/Bundled/GitHub Catalog + Direct Microsoft SyncUpdates
        val offlineList = loadOfflineCatalogBuilds(context, cachedCatalogJson).toMutableList()
        var groupedOffline = filterBuildsToGroups(offlineList, family, featureVer, archFilter, cleanQuery, hideCumulativeUpdates, hideOlderBuilds)

        if (groupedOffline.isEmpty || preferDirectMicrosoft) {
            val liveMs = fetchLatestDirectFromMicrosoft(family, featureVer, archFilter)
            if (liveMs.isNotEmpty()) {
                offlineList.addAll(0, liveMs)
                groupedOffline = filterBuildsToGroups(offlineList, family, featureVer, archFilter, cleanQuery, hideCumulativeUpdates, hideOlderBuilds)
                _activeSource.value = UupDataSource.DIRECT_MICROSOFT
                return@withContext groupedOffline
            }
        }
        _activeSource.value = if (MicrosoftUupClient.syncMetaCache.isNotEmpty()) UupDataSource.DIRECT_MICROSOFT else UupDataSource.OFFLINE_CATALOG
        groupedOffline
    }

    suspend fun fetchLanguages(updateId: String): List<WinBuildLangOption> = withContext(Dispatchers.IO) {
        langsCache[updateId]?.let { return@withContext it }

        if (shouldUseUupDumpApi()) {
            val root = fetchJsonWithRetry("$apiBaseUrl/listlangs.php?id=${URLEncoder.encode(updateId, "UTF-8")}")
            val resp = root?.optJSONObject("response")
            val fancy = resp?.optJSONObject("langFancyNames") ?: JSONObject()
            val list = resp?.optJSONArray("langList")
            if (list != null && list.length() > 0) {
                val out = mutableListOf<WinBuildLangOption>()
                for (i in 0 until list.length()) {
                    val code = list.optString(i, "").trim()
                    if (code.isNotEmpty()) out.add(WinBuildLangOption(code, fancy.optString(code, code)))
                }
                val sorted = sortLangOptions(out)
                if (sorted.isNotEmpty()) {
                    langsCache[updateId] = sorted
                    return@withContext sorted
                }
            }
        }

        // Fallback: Direct Microsoft SyncUpdates cache or GitHub uup-db/<uuid>.json
        val syncFiles = MicrosoftUupClient.syncFilesCache[updateId]
        val derived = when {
            !syncFiles.isNullOrEmpty() -> MicrosoftUupClient.deriveLangsFromFileNames(syncFiles.map { it.name })
            else -> fetchGitHubBuildManifest(updateId)?.first ?: listOf(WinBuildLangOption("en-us", "English (United States)"))
        }
        val sorted = sortLangOptions(derived)
        langsCache[updateId] = sorted
        sorted
    }

    private fun sortLangOptions(list: List<WinBuildLangOption>): List<WinBuildLangOption> = list.sortedWith(
        compareBy<WinBuildLangOption> { !it.code.equals("en-us", true) }
            .thenBy { !it.displayName.startsWith("English", true) }
            .thenBy { it.displayName.lowercase() }
    )

    suspend fun fetchEditions(updateId: String, langCode: String): List<WinBuildEditionOption> = withContext(Dispatchers.IO) {
        val key = "$updateId|$langCode"
        editionsCache[key]?.let { return@withContext it }

        if (shouldUseUupDumpApi()) {
            val url = "$apiBaseUrl/listeditions.php?id=${URLEncoder.encode(updateId, "UTF-8")}&lang=${URLEncoder.encode(langCode, "UTF-8")}"
            val root = fetchJsonWithRetry(url)
            val resp = root?.optJSONObject("response")
            val fancy = resp?.optJSONObject("editionFancyNames") ?: JSONObject()
            val list = resp?.optJSONArray("editionList")
            if (list != null && list.length() > 0) {
                val out = mutableListOf<WinBuildEditionOption>()
                for (i in 0 until list.length()) {
                    val code = list.optString(i, "").trim()
                    if (code.isNotEmpty()) out.add(WinBuildEditionOption(code, fancy.optString(code, code)))
                }
                val sorted = sortEditionOptions(out)
                if (sorted.isNotEmpty()) {
                    editionsCache[key] = sorted
                    return@withContext sorted
                }
            }
        }

        // Fallback: Derive editions directly from SyncUpdates or GitHub uup-db/<uuid>.json
        val syncFiles = MicrosoftUupClient.syncFilesCache[updateId] ?: fetchGitHubBuildManifest(updateId)?.second
        val derived = if (!syncFiles.isNullOrEmpty()) {
            MicrosoftUupClient.deriveEditionsFromFileNames(syncFiles.map { it.name }, langCode)
        } else {
            listOf(
                WinBuildEditionOption("PROFESSIONAL", "Windows Pro"),
                WinBuildEditionOption("CORE", "Windows Home")
            )
        }
        val sorted = sortEditionOptions(derived)
        editionsCache[key] = sorted
        sorted
    }

    private fun sortEditionOptions(list: List<WinBuildEditionOption>): List<WinBuildEditionOption> = list.sortedWith(
        compareBy<WinBuildEditionOption> {
            when (it.code.uppercase()) { "PROFESSIONAL" -> 0; "CORE" -> 1; "ENTERPRISE" -> 2; else -> 3 }
        }.thenBy { it.displayName.lowercase() }
    )

    private fun isSkippableNonEditionCab(fileName: String): Boolean {
        val low = fileName.lowercase()
        if (!low.endsWith(".cab")) return false
        return low.contains("windows10.0-kb") || low.contains("windows11.0-kb") ||
            low.startsWith("ssu-") || low.contains("-ssu-") ||
            low.contains("desktopdeployment") || low.contains("aggregatedmetadata") ||
            low.contains("baseless") || low.contains("safeos") ||
            low.contains("dynamicupdate") || low.contains("netfx3-ondemand") ||
            low.contains("-express.cab")
    }

    private fun classifyPackageFile(fileName: String): WinPackageKind {
        val low = fileName.lowercase()
        return when {
            low.contains("netfx3-ondemand") && low.endsWith(".cab") -> WinPackageKind.NETFX3
            low.endsWith(".msu") || (low.endsWith(".cab") && isSkippableNonEditionCab(low)) -> WinPackageKind.UPDATE
            low.endsWith(".msixbundle") || low.endsWith(".appxbundle") || low.endsWith(".msix") || low.endsWith(".appx") ||
                (low.endsWith(".xml") && (low.contains("license") || low.contains("app"))) -> WinPackageKind.STORE_APP
            else -> WinPackageKind.BASE_OS
        }
    }

    private fun buildDetailedManifestFromEntries(entries: List<WinPackageFileEntry>): WinDetailedManifest {
        val base = mutableListOf<WinPackageFileEntry>(); val updates = mutableListOf<WinPackageFileEntry>()
        val apps = mutableListOf<WinPackageFileEntry>(); val netfx = mutableListOf<WinPackageFileEntry>()
        val all = mutableListOf<WinPackageFileEntry>()
        entries.forEach { entry ->
            if (MicrosoftUupClient.isUnusablePackageName(entry.name)) return@forEach
            all.add(entry)
            when (entry.kind) {
                WinPackageKind.BASE_OS -> if (entry.name.lowercase().endsWith(".esd") || entry.name.lowercase().endsWith(".cab")) base.add(entry)
                WinPackageKind.UPDATE -> updates.add(entry)
                WinPackageKind.STORE_APP -> apps.add(entry)
                WinPackageKind.NETFX3 -> netfx.add(entry)
            }
        }
        return WinDetailedManifest(
            base.sortedBy { it.name.lowercase() },
            updates.sortedBy { it.name.lowercase() },
            apps.sortedBy { it.name.lowercase() },
            netfx.sortedBy { it.name.lowercase() },
            all.sortedBy { it.name.lowercase() }
        )
    }

    private fun parseDetailedManifest(root: JSONObject, requireUrl: Boolean): WinDetailedManifest {
        val filesObj = root.optJSONObject("response")?.optJSONObject("files") ?: return WinDetailedManifest()
        val entries = mutableListOf<WinPackageFileEntry>()
        val keys = filesObj.keys()
        while (keys.hasNext()) {
            val name = keys.next()
            if (MicrosoftUupClient.isUnusablePackageName(name)) continue
            val item = filesObj.optJSONObject(name) ?: continue
            val url = item.optString("url", "")
            if (requireUrl && url.isBlank()) continue
            val size = item.optString("size", "0").toLongOrNull() ?: item.optLong("size", 0L)
            val sha1 = item.optString("sha1", "").lowercase()
            val expFromJson = item.optString("expire", "0").toLongOrNull() ?: item.optLong("expire", 0L)
            val expSec = if (expFromJson > 0L) expFromJson else MicrosoftUupClient.parseUrlExpiryEpoch(url)
            entries.add(WinPackageFileEntry(name, url, size, sha1, classifyPackageFile(name), expSec))
        }
        return buildDetailedManifestFromEntries(entries)
    }

    suspend fun fetchDetailedManifest(updateId: String, langCode: String, editionCodes: List<String>): WinDetailedManifest? = withContext(Dispatchers.IO) {
        val edParam = editionCodes.sorted().joinToString(";") { it.lowercase() }
        val key = "$updateId|$langCode|$edParam"
        detailedManifestCache[key]?.let { return@withContext it }

        if (shouldUseUupDumpApi()) {
            val url = "$apiBaseUrl/get.php?id=${URLEncoder.encode(updateId, "UTF-8")}&lang=${URLEncoder.encode(langCode, "UTF-8")}&edition=${URLEncoder.encode(edParam, "UTF-8")}&noLinks=1"
            val root = fetchJsonWithRetry(url)
            if (root != null) {
                val parsed = parseDetailedManifest(root, false)
                if (parsed.allFiles.isNotEmpty()) {
                    detailedManifestCache[key] = parsed
                    return@withContext parsed
                }
            }
        }

        // Fallback: Direct Microsoft SyncUpdates cache or GitHub uup-db/<uuid>.json
        val rawFiles = MicrosoftUupClient.syncFilesCache[updateId] ?: fetchGitHubBuildManifest(updateId)?.second ?: return@withContext null
        val filtered = MicrosoftUupClient.filterFilesForSelection(rawFiles, langCode, editionCodes)
        val entries = filtered.map { WinPackageFileEntry(it.name, "", it.sizeBytes, it.sha1Hex, classifyPackageFile(it.name), 0L) }
        val parsed = buildDetailedManifestFromEntries(entries)
        if (parsed.allFiles.isEmpty()) return@withContext null
        detailedManifestCache[key] = parsed
        parsed
    }

    suspend fun fetchManifestEstimate(updateId: String, langCode: String, editionCodes: List<String>): WinManifestEstimate? =
        fetchDetailedManifest(updateId, langCode, editionCodes)?.let { WinManifestEstimate(it.baseFiles.size, it.baseBytes) }

    suspend fun fetchFullDownloadManifest(
        updateId: String,
        langCode: String,
        editionCodes: List<String>,
        archHint: String = "amd64"
    ): WinDetailedManifest = withContext(Dispatchers.IO) {
        // Layer 1: UUPdump API if enabled and healthy
        if (shouldUseUupDumpApi()) {
            val edParam = editionCodes.joinToString(";") { it.lowercase() }
            val url = "$apiBaseUrl/get.php?id=${URLEncoder.encode(updateId, "UTF-8")}&lang=${URLEncoder.encode(langCode, "UTF-8")}&edition=${URLEncoder.encode(edParam, "UTF-8")}"
            val root = fetchJsonWithRetry(url)
            if (root != null) {
                val parsed = parseDetailedManifest(root, true)
                if (parsed.allFiles.isNotEmpty()) {
                    _activeSource.value = UupDataSource.UUPDUMP_LIVE
                    return@withContext parsed
                }
            }
        }

        // Layer 2: Direct Microsoft GetExtendedUpdateInfo2 matched against Extended / SyncUpdates / GitHub uup-db/<uuid>.json by SHA-1
        val cleanUid = updateId.substringBefore("_rev.")
        val (ring, sku, checkBuild) = catalogMetaByUuid[updateId] ?: catalogMetaByUuid[cleanUid] ?: Triple("RETAIL", 48, "10.0.19041.1")
        val rev = MicrosoftUupClient.syncMetaCache[updateId]?.revision ?: MicrosoftUupClient.syncMetaCache[cleanUid]?.revision ?: 1
        val urlMap = MicrosoftUupClient.fetchFileUrlsForUpdate(
            updateId = updateId,
            revision = rev,
            arch = archHint,
            ring = ring,
            checkBuild = checkBuild,
            sku = sku
        )
        if (urlMap.isEmpty()) return@withContext WinDetailedManifest()

        var rawMetaFiles = MicrosoftUupClient.syncFilesCache[updateId]
            ?: MicrosoftUupClient.syncFilesCache[cleanUid]
            ?: fetchGitHubBuildManifest(cleanUid)?.second
        if (rawMetaFiles.isNullOrEmpty()) {
            // Last-resort live SyncUpdates query to populate filenames for this branch
            val synced = MicrosoftUupClient.syncLatestBuildsDirect(arch = archHint, ring = ring, baselineBuild = checkBuild, sku = sku)
            rawMetaFiles = synced.firstOrNull { it.build.uuid.equals(cleanUid, true) }?.files
                ?: MicrosoftUupClient.syncFilesCache[cleanUid]
        }

        val matched = if (!rawMetaFiles.isNullOrEmpty()) {
            val filtered = MicrosoftUupClient.filterFilesForSelection(rawMetaFiles, langCode, editionCodes)
            filtered.mapNotNull { fm ->
                val direct = urlMap[fm.sha1Hex.lowercase()] ?: return@mapNotNull null
                WinPackageFileEntry(fm.name, direct.url, fm.sizeBytes, fm.sha1Hex, classifyPackageFile(fm.name), direct.expireEpochSec)
            }
        } else {
            emptyList()
        }

        if (matched.isNotEmpty()) {
            _activeSource.value = UupDataSource.DIRECT_MICROSOFT
            return@withContext buildDetailedManifestFromEntries(matched)
        }
        WinDetailedManifest()
    }

    fun buildExternalPageLink(build: WindowsChannelBuild, langCode: String): String =
        "https://uupdump.net/selectlang.php?id=${URLEncoder.encode(build.uuid, "UTF-8")}"

    private fun saveBuildJobJson(context: Context, workRootPath: String, config: WindowsChannelBuildConfig, totalBytes: Long, isCachedReady: Boolean = false) {
        runCatching {
            val obj = JSONObject().apply {
                put("uuid", config.build.uuid); put("title", config.build.title); put("build", config.build.build)
                put("arch", config.build.arch); put("createdEpoch", config.build.createdEpoch)
                put("langCode", config.lang.code); put("langName", config.lang.displayName)
                put("editions", JSONArray().apply { config.editions.forEach { put(JSONObject().put("code", it.code).put("name", it.displayName)) } })
                put("compression", config.compression.name); put("outputMode", config.outputMode.name); put("downloadMethod", config.downloadMethod.name)
                put("targetDir", config.targetDir); put("customOutputName", config.customOutputName); put("targetMultiBootPath", config.targetMultiBootPath)
                put("connections", config.connections); put("autoMount", config.autoMount)
                put("includeUpdates", config.includeUpdates); put("includeStoreApps", config.includeStoreApps)
                put("integrateNetFx3", config.integrateNetFx3); put("runComponentCleanup", config.runComponentCleanup)
                put("skipWinRe", config.skipWinRe); put("eiCfgUnlock", config.eiCfgUnlock); put("keepPackagesAfterBuild", config.keepPackagesAfterBuild)
                put("scheme", config.scheme.name); put("bypassReq", config.experience.bypassRequirements); put("bypassMsa", config.experience.bypassMicrosoftAccount)
                put("disableTelemetry", config.experience.disableDataCollection); put("disableBitLocker", config.experience.disableBitLocker)
                put("qol", config.experience.qolImprovements)
                put("disableSponsoredApps", config.experience.disableSponsoredApps)
                put("classicContextMenu", config.experience.classicContextMenu)
                put("skipWifiSetup", config.experience.skipWifiSetup)
                put("createLocalUser", config.experience.createLocalUser)
                put("localUsername", config.experience.localUsername)
                put("skipOobe", config.experience.skipOobe)
                put("useCa2023Bootloaders", config.experience.useCa2023Bootloaders)
                put("applySkuSiPolicy", config.experience.applySkuSiPolicy)
                put("customPostInstallScriptPath", config.experience.customPostInstallScriptPath)
                put("extraDriversDir", config.extraDriversDir)
                put("virtualEditions", JSONArray(config.virtualEditions))
                put("winUtilJson", config.winUtil.toJsonString()); put("totalBytes", totalBytes); put("isCachedReady", isCachedReady)
            }
            val tmp = File(context.applicationContext.cacheDir, "pb_build_meta.json").apply { writeText(obj.toString(), Charsets.UTF_8) }
            Shell.cmd("mkdir -p ${shellQuote(workRootPath)} && cp -f ${shellQuote(tmp.absolutePath)} ${shellQuote("$workRootPath/pb_build.json")}").exec()
            tmp.delete()
        }
    }

    suspend fun scanPendingBuilds(context: Context, scanDirs: List<String>): List<PendingWindowsBuild> = withContext(Dispatchers.IO) {
        val results = mutableListOf<PendingWindowsBuild>()
        val activeDl = dlWorkRootPath; val activeConv = _conversionState.value?.workDirPath.orEmpty()
        scanDirs.map { UsbGadgetEngine.resolveDirectPath(it).trimEnd('/') }.distinct().forEach { baseDir ->
            val found = Shell.cmd("ls -d ${shellQuote("$baseDir/.pocketboot_tmp")}/pb_uup_*/pb_build.json 2>/dev/null || true").exec().out
            found.forEach { metaPath ->
                val cleanMeta = metaPath.trim()
                if (cleanMeta.isBlank()) return@forEach
                val workRoot = cleanMeta.substringBeforeLast('/')
                if (workRoot == activeDl || workRoot == activeConv) return@forEach
                val raw = Shell.cmd("cat ${shellQuote(cleanMeta)} 2>/dev/null").exec().out.joinToString("\n")
                if (raw.isBlank()) return@forEach
                runCatching {
                    val o = JSONObject(raw)
                    val edArr = o.optJSONArray("editions") ?: JSONArray()
                    val eds = mutableListOf<WinBuildEditionOption>()
                    for (i in 0 until edArr.length()) { val eo = edArr.optJSONObject(i) ?: continue; eds.add(WinBuildEditionOption(eo.optString("code"), eo.optString("name"))) }
                    if (eds.isEmpty()) return@runCatching
                    val bld = WindowsChannelBuild(o.optString("uuid"), o.optString("title", "Windows"), o.optString("build", ""), o.optString("arch", "amd64"), o.optLong("createdEpoch", 0L))
                    val lang = WinBuildLangOption(o.optString("langCode", "en-us"), o.optString("langName", "English (United States)"))
                    val comp = runCatching { WinIsoCompression.valueOf(o.optString("compression", "WIM")) }.getOrDefault(WinIsoCompression.WIM)
                    val mode = runCatching { WinBuildOutputMode.valueOf(o.optString("outputMode", "STANDARD_ISO")) }.getOrDefault(WinBuildOutputMode.STANDARD_ISO)
                    val method = runCatching { WinDownloadMethod.valueOf(o.optString("downloadMethod", "DOWNLOAD_AND_BUILD")) }.getOrDefault(WinDownloadMethod.DOWNLOAD_AND_BUILD)
                    val scheme = runCatching { PartitionScheme.valueOf(o.optString("scheme", "GPT")) }.getOrDefault(PartitionScheme.GPT)
                    val exp = WindowsExperienceConfig(
                        bypassRequirements = o.optBoolean("bypassReq", true),
                        bypassMicrosoftAccount = o.optBoolean("bypassMsa", true),
                        disableDataCollection = o.optBoolean("disableTelemetry", true),
                        disableBitLocker = o.optBoolean("disableBitLocker", true),
                        qolImprovements = o.optBoolean("qol", true),
                        disableSponsoredApps = o.optBoolean("disableSponsoredApps", false),
                        classicContextMenu = o.optBoolean("classicContextMenu", false),
                        skipWifiSetup = o.optBoolean("skipWifiSetup", true),
                        createLocalUser = o.optBoolean("createLocalUser", false),
                        localUsername = o.optString("localUsername", ""),
                        skipOobe = o.optBoolean("skipOobe", false),
                        useCa2023Bootloaders = o.optBoolean("useCa2023Bootloaders", false),
                        applySkuSiPolicy = o.optBoolean("applySkuSiPolicy", false),
                        customPostInstallScriptPath = o.optString("customPostInstallScriptPath", "")
                    )
                    val veList = mutableListOf<String>(); o.optJSONArray("virtualEditions")?.let { va -> for (k in 0 until va.length()) va.optString(k, "").trim().takeIf { it.isNotEmpty() }?.let { veList.add(it) } }
                    val cfg = WindowsChannelBuildConfig(bld, lang, eds, comp, mode, method, o.optString("targetDir", baseDir), o.optString("customOutputName", ""), o.optString("targetMultiBootPath", ""), o.optInt("connections", 8), o.optBoolean("autoMount", true), o.optBoolean("includeUpdates", false), o.optBoolean("includeStoreApps", false), o.optBoolean("integrateNetFx3", false), o.optBoolean("runComponentCleanup", false), o.optBoolean("skipWinRe", false), o.optBoolean("eiCfgUnlock", false), o.optBoolean("keepPackagesAfterBuild", false), scheme, exp, WinUtilTweaksConfig.fromJsonString(o.optString("winUtilJson", "")), extraDriversDir = o.optString("extraDriversDir", ""), virtualEditions = veList)
                    val workPrefix = workRoot.substringAfterLast('/')
                    val pkgBytes = Shell.cmd("du -sb ${shellQuote("$workRoot/PKGS")} 2>/dev/null | awk '{print \$1}'").exec().out.firstOrNull()?.trim()?.toLongOrNull() ?: 0L
                    val stageBytes = stageCacheDir(context, workPrefix).walkTopDown().filter { it.isFile && it.name.endsWith(".part") }.sumOf { it.length() }
                    val totalBytes = o.optLong("totalBytes", 0L).coerceAtLeast(pkgBytes)
                    if (pkgBytes > 0L || stageBytes > 0L) {
                        results.add(PendingWindowsBuild(workRoot, workPrefix, "Win ${bld.build} (${bld.archDisplay})", cfg.customOutputName.ifBlank { "Win_${bld.build}_${bld.archDisplay}_${lang.code}${if (mode == WinBuildOutputMode.DIRECT_NTFS_USB) "_USB.img" else ".iso"}" }, (pkgBytes + stageBytes).coerceAtMost(totalBytes.coerceAtLeast(pkgBytes + stageBytes)), totalBytes, o.optBoolean("isCachedReady", false), cfg))
                    }
                }
            }
        }
        results.distinctBy { it.workRootPath }
    }

    fun discardPendingBuild(context: Context, pending: PendingWindowsBuild) {
        scope.launch { UsbGadgetEngine.cleanupTempWorkDir(pending.workRootPath); runCatching { stageCacheDir(context, pending.workPrefix).deleteRecursively() } }
    }

    private fun pruneOldWorkDirs(targetDir: String, keepPrefix: String) {
        val tmpRoot = "${UsbGadgetEngine.resolveDirectPath(targetDir).trimEnd('/')}/.pocketboot_tmp"
        val activeConv = _conversionState.value?.workDirPath?.substringAfterLast('/').orEmpty()
        Shell.cmd("if [ -d ${shellQuote(tmpRoot)} ]; then for d in ${shellQuote(tmpRoot)}/pb_uup_*; do [ -d \"\$d\" ] || continue; bn=\$(basename \"\$d\"); if [ \"\$bn\" != ${shellQuote(keepPrefix)} ] && [ \"\$bn\" != ${shellQuote(activeConv)} ]; then if ! grep -q '\"isCachedReady\":true' \"\$d/pb_build.json\" 2>/dev/null; then rm -rf \"\$d\" 2>/dev/null || true; fi; fi; done; fi").exec()
    }

    fun updateDownloadAutoMount(value: Boolean) { dlAutoMountLive = value; _downloadState.value = _downloadState.value?.copy(autoMount = value) }
    fun updateConversionAutoMount(value: Boolean) { convAutoMountLive = value; _conversionState.value = _conversionState.value?.copy(autoMount = value) }

    fun pauseDownload() {
        val cur = _downloadState.value ?: return
        if (cur.isPaused) return
        dlPausedFlag.set(true); activeDownloadJob?.cancel()
        val pausedState = cur.copy(isPaused = true, speedBytesPerSec = 0L, etaSeconds = -1L, stage = "Paused · Tap Resume anytime")
        _downloadState.value = pausedState
        dlContext?.let { ctx -> NotificationHelper.updateDownloadProgress(ctx, TurboDownloadState(pausedState.title, pausedState.stage, "", dlConfig?.targetDir.orEmpty(), "", pausedState.downloadedBytes, pausedState.totalBytes, 0L, -1L, 0, true, true, false, false, "Paused")) }
        emitLog("win-dl", "Paused Windows package download (${UsbGadgetEngine.formatBytes(cur.downloadedBytes)} cached)")
    }

    fun resumeDownload() {
        val ctx = dlContext ?: return; val cfg = dlConfig ?: return; val oc = dlOnComplete ?: return; val oe = dlOnError ?: return; val ol = dlOnLog ?: return
        startBuild(ctx, cfg, oc, oe, ol)
    }

    fun cancelDownload(discardTemp: Boolean = true) {
        dlCancelledFlag.set(true); dlPausedFlag.set(false); activeDownloadJob?.cancel()
        val workPath = dlWorkRootPath; val prefix = dlWorkPrefix
        _downloadState.value = null; dlWorkRootPath = ""; dlWorkPrefix = ""
        dlContext?.let { ctx ->
            TurboDownloadService.stop(ctx)
            if (discardTemp) {
                if (workPath.isNotBlank()) UsbGadgetEngine.cleanupTempWorkDir(workPath)
                if (prefix.isNotBlank()) runCatching { stageCacheDir(ctx, prefix).deleteRecursively() }
            }
        }
    }

    fun cancelConversion() {
        convCancelledFlag.set(true); conversionQueue.clear(); activeConversionJob?.cancel()
        val curWork = _conversionState.value?.workDirPath.orEmpty()
        _conversionState.value = null
        convContext?.let { IsoBuilderService.stop(it) }
        scope.launch {
            Shell.cmd("pkill -f 'libwimlib_imagex|libxorriso|libcabextract' 2>/dev/null || true; rm -rf /data/local/tmp/pb_uup_ext /data/local/tmp/pb_uup_hive /data/local/tmp/pb_wim_prog.log /data/local/tmp/pb_ca2023 /data/local/tmp/pb_win7_efi 2>/dev/null || true").exec()
            if (curWork.isNotBlank()) Shell.cmd("rm -rf ${shellQuote("$curWork/ISODIR")} ${shellQuote("$curWork/TMP")} 2>/dev/null || true").exec()
        }
        emitConvLog("win-build", "Cancelled active ISO/USB build and cleared scratch files.", true)
    }

    private fun emitConvLog(tag: String, msg: String, isError: Boolean = false) { convOnLog?.invoke(LogEntry(nowTs(), tag, msg, isError)) }

    private fun batchVerifyExistingPackages(
        pkgDirPath: String,
        files: List<WinPackageFileEntry>
    ): Set<String> {
        val qPkg = shellQuote(pkgDirPath)
        val expectedByName = files.associateBy { it.name }
        val lines = Shell.cmd(
            """
            if [ -d $qPkg ]; then
                for f in $qPkg/*; do
                    [ -f "${'$'}f" ] || continue
                    bn=${'$'}(basename "${'$'}f")
                    sz=${'$'}(stat -c '%s' "${'$'}f" 2>/dev/null || echo 0)
                    h=${'$'}(sha1sum "${'$'}f" 2>/dev/null | awk '{print ${'$'}1}')
                    printf '%s|%s|%s\n' "${'$'}bn" "${'$'}sz" "${'$'}h"
                done
            fi
            """.trimIndent()
        ).exec().out

        val verified = mutableSetOf<String>()
        val corruptToRemove = mutableListOf<String>()
        lines.forEach { line ->
            val parts = line.split("|", limit = 3)
            if (parts.size < 3) return@forEach
            val name = parts[0]
            val size = parts[1].toLongOrNull() ?: 0L
            val sha1 = parts[2].trim().lowercase()
            val entry = expectedByName[name] ?: return@forEach
            val sizeOk = entry.sizeBytes <= 0L || size == entry.sizeBytes
            val hashOk = entry.sha1.length != 40 || sha1 == entry.sha1.lowercase()
            if (size > 0L && sizeOk && hashOk) {
                verified.add(name)
            } else {
                corruptToRemove.add("$pkgDirPath/$name")
            }
        }
        if (corruptToRemove.isNotEmpty()) {
            val rmArgs = corruptToRemove.joinToString(" ") { shellQuote(it) }
            Shell.cmd("rm -f $rmArgs 2>/dev/null || true").exec()
        }
        return verified
    }

    fun startBuild(context: Context, config: WindowsChannelBuildConfig, onComplete: (DownloadCompletionEvent) -> Unit, onError: (String) -> Unit, onLog: (LogEntry) -> Unit) {
        cancelDownload(discardTemp = false)
        dlCancelledFlag.set(false); dlPausedFlag.set(false); dlAutoMountLive = config.autoMount
        val appCtx = context.applicationContext
        dlContext = appCtx; dlConfig = config; dlOnComplete = onComplete; dlOnError = onError; dlOnLog = onLog; activeOnLog = onLog

        val jobTitle = "Win ${config.build.build} (${config.build.archDisplay})"
        val maxConns = config.connections.coerceIn(1, 16)
        _downloadState.value = WindowsBuildJobState(jobTitle, "Fetching package list...", config.build.title, 0.01f, activeThreads = maxConns, autoMount = dlAutoMountLive)
        TurboDownloadService.start(appCtx)

        activeDownloadJob = scope.launch {
            val shortUuid = config.build.uuid.take(8).replace(Regex("[^A-Za-z0-9]"), "")
            val edHash = config.editions.map { it.code.lowercase() }.sorted().joinToString("_").hashCode().toUInt().toString(16).take(6)
            val workPrefix = "pb_uup_${config.build.build.replace(".", "_")}_${shortUuid}_${config.lang.code.lowercase()}_$edHash"
            val workRootPath = UsbGadgetEngine.createSafeTempWorkDir(appCtx, config.targetDir, workPrefix)
            dlWorkRootPath = workRootPath; dlWorkPrefix = workPrefix
            val pkgDir = File(workRootPath, "PKGS"); val appStageDir = stageCacheDir(appCtx, workPrefix)

            try {
                if (config.downloadMethod == WinDownloadMethod.DOWNLOAD_AND_BUILD) {
                    val tools = inspectTools(appCtx)
                    if (config.outputMode == WinBuildOutputMode.DIRECT_NTFS_USB && !tools.canBuildDirectUsb) throw IllegalStateException("Bundled build tools missing. Reinstall the latest APK.")
                    if (config.outputMode != WinBuildOutputMode.DIRECT_NTFS_USB && !tools.canConvertIso) throw IllegalStateException("Bundled ISO build tools (cabextract/wimlib/chntpw/xorriso) missing.")
                }
                pruneOldWorkDirs(config.targetDir, workPrefix)
                Shell.cmd("mkdir -p ${shellQuote(pkgDir.absolutePath)}").exec()

                val manifest = fetchFullDownloadManifest(config.build.uuid, config.lang.code, config.editions.map { it.code }, archHint = config.build.arch)
                if (manifest.allFiles.isEmpty()) throw IllegalStateException("Could not load package list from UUPdump or Microsoft servers.")

                val buildMajor = config.build.build.substringBefore('.').toIntOrNull() ?: 0
                val autoSecHealthApps = if (buildMajor >= 22621 && !config.includeStoreApps) {
                    manifest.appFiles.filter { it.name.contains("sechealthui", ignoreCase = true) }
                } else emptyList()

                val targetFiles = when (config.downloadMethod) {
                    WinDownloadMethod.DOWNLOAD_UPDATES_ONLY -> manifest.updateFiles
                    else -> buildList {
                        addAll(manifest.baseFiles)
                        if (config.includeUpdates) addAll(manifest.updateFiles)
                        if (config.includeStoreApps) addAll(manifest.appFiles)
                        else if (autoSecHealthApps.isNotEmpty()) addAll(autoSecHealthApps)
                        if (config.integrateNetFx3) addAll(manifest.netFx3Files)
                    }
                }.distinctBy { it.name }
                val hasEffectiveApps = (config.includeStoreApps && manifest.hasApps) || autoSecHealthApps.isNotEmpty()
                if (autoSecHealthApps.isNotEmpty()) emitLog("win-dl", "Auto-included ${autoSecHealthApps.size} Windows Security (SecHealthUI) package(s) for Win 11 22H2+")
                if (targetFiles.isEmpty()) throw IllegalStateException("No downloadable packages matched the selected options.")

                val totalBytes = targetFiles.sumOf { it.sizeBytes }.coerceAtLeast(1L)
                val freeNow = UsbGadgetEngine.getFreeSpaceBytes(config.targetDir)
                val estExtraBuildBytes = if (config.downloadMethod == WinDownloadMethod.DOWNLOAD_AND_BUILD) (totalBytes + (if (config.skipWinRe) 0L else 520L * 1024L * 1024L) + 600L * 1024L * 1024L) else 256L * 1024L * 1024L
                val existingPkgBytes = Shell.cmd("du -sb ${shellQuote(pkgDir.absolutePath)} 2>/dev/null | awk '{print \$1}'").exec().out.firstOrNull()?.trim()?.toLongOrNull() ?: 0L
                val neededFree = ((totalBytes - existingPkgBytes).coerceAtLeast(0L)) + estExtraBuildBytes
                if (freeNow > 0L && freeNow < neededFree) throw IllegalStateException("Not enough free space. Need ~${UsbGadgetEngine.formatBytes(neededFree)}, have ${UsbGadgetEngine.formatBytes(freeNow)} free.")

                saveBuildJobJson(appCtx, workRootPath, config, totalBytes, false)
                emitLog("win-dl", "Found ${targetFiles.size} package(s) (${UsbGadgetEngine.formatBytes(totalBytes)}) · Method=${config.downloadMethod.name}")

                _downloadState.value = WindowsBuildJobState(jobTitle, "Checking cached packages...", "Verifying SHA-1 hashes", 0.03f, 0L, totalBytes, activeThreads = maxConns, filesTotal = targetFiles.size, autoMount = dlAutoMountLive, workDirPath = workRootPath)
                val alreadyVerified = batchVerifyExistingPackages(pkgDir.absolutePath, targetFiles)
                val cachedBytes = targetFiles.filter { it.name in alreadyVerified }.sumOf { it.sizeBytes }
                val filesDoneCounter = AtomicInteger(alreadyVerified.size)
                val downloadedCounter = AtomicLong(cachedBytes)
                val liveThreadsCounter = AtomicInteger(0)
                if (alreadyVerified.isNotEmpty()) emitLog("win-dl", "Resumed ${alreadyVerified.size}/${targetFiles.size} verified packages (${UsbGadgetEngine.formatBytes(cachedBytes)})")

                val meterJob = launch {
                    var prevBytes = downloadedCounter.get(); var prevMs = System.currentTimeMillis(); var smoothSpeed = 0L
                    while (isActive && !dlPausedFlag.get() && !dlCancelledFlag.get()) {
                        delay(500L)
                        val nowMs = System.currentTimeMillis(); val cur = downloadedCounter.get(); val dt = (nowMs - prevMs).coerceAtLeast(1L)
                        val inst = ((cur - prevBytes).coerceAtLeast(0L) * 1000L) / dt
                        smoothSpeed = if (smoothSpeed == 0L) inst else ((smoothSpeed * 6 + inst * 4) / 10)
                        prevBytes = cur; prevMs = nowMs
                        val eta = if (smoothSpeed > 0L && totalBytes > cur) (totalBytes - cur) / smoothSpeed else -1L
                        val shownThreads = liveThreadsCounter.get().coerceAtLeast(1)
                        val st = WindowsBuildJobState(jobTitle, "Downloading (${filesDoneCounter.get()}/${targetFiles.size} files)", "${UsbGadgetEngine.formatBytes(cur)} / ${UsbGadgetEngine.formatBytes(totalBytes)} · ${FastIsoDownloader.formatSpeed(smoothSpeed)}", (cur.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f), cur, totalBytes, smoothSpeed, eta, shownThreads, filesDoneCounter.get(), targetFiles.size, false, false, dlAutoMountLive, workRootPath)
                        _downloadState.value = st
                        NotificationHelper.updateDownloadProgress(appCtx, TurboDownloadState(st.title, st.stage, "", config.targetDir, "", cur, totalBytes, smoothSpeed, eta, shownThreads, true, false, false, false, st.detail))
                    }
                }
                try {
                    val batchItems = targetFiles.filter { it.name !in alreadyVerified }.map { entry ->
                        val safeName = entry.name.replace(Regex("[^A-Za-z0-9._-]"), "_")
                        FastIsoDownloader.TurboBatchItem(entry.name, entry.url, File(appStageDir, "$safeName.part"), File(appStageDir, "$safeName.part.pbmeta"), entry.sizeBytes, entry.sha1, entry.expireEpochSec)
                    }
                    val refreshCallback: suspend () -> Map<String, Pair<String, Long>> = {
                        val freshMf = fetchFullDownloadManifest(config.build.uuid, config.lang.code, config.editions.map { it.code }, archHint = config.build.arch)
                        freshMf.allFiles.associate { it.name to (it.url to it.expireEpochSec) }
                    }
                    FastIsoDownloader.downloadBatchTurbo(batchItems, maxConns, dlPausedFlag, dlCancelledFlag, downloadedCounter, liveThreadsCounter, onRefreshUrls = refreshCallback) { doneItem ->
                        val mvRes = Shell.cmd("mv -f ${shellQuote(doneItem.partFile.absolutePath)} ${shellQuote("${pkgDir.absolutePath}/${doneItem.id}")} 2>&1").exec()
                        if (!mvRes.isSuccess) throw IllegalStateException("Move failed for ${doneItem.id}")
                        filesDoneCounter.incrementAndGet()
                    }
                } finally { meterJob.cancel() }

                if (dlPausedFlag.get() || dlCancelledFlag.get()) return@launch
                runCatching { appStageDir.deleteRecursively() }
                emitLog("win-dl", "Downloaded & SHA-1 verified all ${targetFiles.size} packages")

                dlWorkRootPath = ""; dlWorkPrefix = ""; _downloadState.value = null; TurboDownloadService.stop(appCtx)

                if (config.downloadMethod != WinDownloadMethod.DOWNLOAD_AND_BUILD) {
                    val folderSuffix = if (config.downloadMethod == WinDownloadMethod.DOWNLOAD_UPDATES_ONLY) "Updates" else "UUP_Packages"
                    val cleanFolder = config.customOutputName.trim().substringBeforeLast('.').replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "Windows_${config.build.build}_${config.build.archDisplay}_$folderSuffix" }
                    val destFolder = "${UsbGadgetEngine.resolveDirectPath(config.targetDir).trimEnd('/')}/$cleanFolder"
                    Shell.cmd("mkdir -p ${shellQuote(destFolder)} && mv -f ${shellQuote(pkgDir.absolutePath)}/* ${shellQuote(destFolder)}/ 2>&1").exec()
                    UsbGadgetEngine.fixUserStoragePermissions(appCtx, destFolder, isDir = true)
                    UsbGadgetEngine.cleanupTempWorkDir(workRootPath)
                    val completion = DownloadCompletionEvent(jobTitle, cleanFolder, destFolder, null, null, null, ChecksumVerifyStatus.NONE, false, "${targetFiles.size} verified files · ${UsbGadgetEngine.formatBytes(totalBytes)}")
                    FastIsoDownloader.publishExternalCompletion(completion)
                    NotificationHelper.showDownloadComplete(appCtx, jobTitle, "Saved ${targetFiles.size} files to $cleanFolder/")
                    onComplete(completion)
                    return@launch
                }

                enqueueConversion(QueuedConversionJob(appCtx, config.copy(autoMount = dlAutoMountLive), workRootPath, workPrefix, jobTitle, totalBytes, config.includeUpdates && manifest.hasUpdates, hasEffectiveApps, config.integrateNetFx3 && manifest.hasNetFx3, false, "", onComplete, onError, onLog))
            } catch (_: CancellationException) {
                if (!dlPausedFlag.get()) { _downloadState.value = null; TurboDownloadService.stop(appCtx) }
            } catch (e: Exception) {
                _downloadState.value = null; TurboDownloadService.stop(appCtx)
                val msg = "Windows download failed: ${e.message ?: "Unexpected error"}"
                emitLog("win-dl", msg, true); onError(msg)
            }
        }
    }

    /**
     * 100% offline Layer-3 builder: compiles an ISO or Direct Bootable USB directly from
     * any user-selected folder of .esd/.cab files without ever modifying or deleting it.
     */
    fun startLocalUupFolderBuild(
        context: Context,
        localFolderPath: String,
        config: WindowsChannelBuildConfig,
        onComplete: (DownloadCompletionEvent) -> Unit,
        onError: (String) -> Unit,
        onLog: (LogEntry) -> Unit
    ) {
        val appCtx = context.applicationContext
        val resolvedFolder = UsbGadgetEngine.resolveDirectPath(localFolderPath).trimEnd('/')
        scope.launch {
            try {
                val tools = inspectTools(appCtx)
                if (config.outputMode == WinBuildOutputMode.DIRECT_NTFS_USB && !tools.canBuildDirectUsb) {
                    throw IllegalStateException("Bundled build tools missing.")
                }
                if (config.outputMode != WinBuildOutputMode.DIRECT_NTFS_USB && !tools.canConvertIso) {
                    throw IllegalStateException("Bundled ISO build tools (cabextract/wimlib/chntpw/xorriso) missing.")
                }
                val esdCount = Shell.cmd("ls ${shellQuote(resolvedFolder)}/*.esd ${shellQuote(resolvedFolder)}/*.ESD 2>/dev/null | wc -l").exec().out.firstOrNull()?.trim()?.toIntOrNull() ?: 0
                if (esdCount <= 0) {
                    throw IllegalStateException("No Windows .esd files found inside ${resolvedFolder.replace("/data/media/0", "/sdcard")}.")
                }
                val folderBytes = Shell.cmd("du -sb ${shellQuote(resolvedFolder)} 2>/dev/null | awk '{print \$1}'").exec().out.firstOrNull()?.trim()?.toLongOrNull() ?: (4L * 1024L * 1024L * 1024L)
                val hasUpd = Shell.cmd("ls ${shellQuote(resolvedFolder)}/*.msu ${shellQuote(resolvedFolder)}/*-KB*.cab ${shellQuote(resolvedFolder)}/*SSU*.cab 2>/dev/null | head -n 1").exec().out.any { it.isNotBlank() }
                val hasApps = Shell.cmd("ls ${shellQuote(resolvedFolder)}/*.msixbundle ${shellQuote(resolvedFolder)}/*.appxbundle ${shellQuote(resolvedFolder)}/*.msix ${shellQuote(resolvedFolder)}/*.appx 2>/dev/null | head -n 1").exec().out.any { it.isNotBlank() }
                val hasFx3 = Shell.cmd("ls ${shellQuote(resolvedFolder)}/*netfx3-ondemand*.cab ${shellQuote(resolvedFolder)}/*NetFx3*.cab 2>/dev/null | head -n 1").exec().out.any { it.isNotBlank() }

                val prefix = "pb_local_${System.currentTimeMillis().toString().takeLast(6)}"
                val workRootPath = UsbGadgetEngine.createSafeTempWorkDir(appCtx, config.targetDir, prefix)
                val title = "Local UUP (${resolvedFolder.substringAfterLast('/')})"
                onLog(LogEntry(nowTs(), "win-build", "Queued offline Local UUP build from $resolvedFolder (read-only source, $esdCount ESD file(s))"))
                enqueueConversion(
                    QueuedConversionJob(
                        context = appCtx,
                        config = config.copy(localUupDirPath = resolvedFolder),
                        workRootPath = workRootPath,
                        workPrefix = prefix,
                        jobTitle = title,
                        totalDownloadBytes = folderBytes,
                        hasUpdates = config.includeUpdates && hasUpd,
                        hasApps = hasApps,
                        hasNetFx3 = config.integrateNetFx3 && hasFx3,
                        isReadOnlySource = true,
                        pkgDirOverride = resolvedFolder,
                        onComplete = onComplete,
                        onError = onError,
                        onLog = onLog
                    )
                )
            } catch (e: Exception) {
                val msg = "Local UUP build failed: ${e.message ?: "Invalid folder"}"
                onLog(LogEntry(nowTs(), "win-build", msg, true))
                onError(msg)
            }
        }
    }

    private fun enqueueConversion(job: QueuedConversionJob) {
        conversionQueue.add(job)
        val queued = conversionQueue.toList()
        _conversionState.value = _conversionState.value?.copy(queuedCount = queued.size, queuedTitles = queued.map { it.jobTitle })
        if (activeConversionJob == null || activeConversionJob?.isActive != true) startNextConversion()
    }

    private fun startNextConversion() {
        val next = conversionQueue.poll()
        if (next == null) { _conversionState.value = null; convContext?.let { IsoBuilderService.stop(it) }; return }
        convCancelledFlag.set(false); convAutoMountLive = next.config.autoMount; convContext = next.context; convOnLog = next.onLog
        IsoBuilderService.start(next.context)
        activeConversionJob = scope.launch {
            try { executeConversionJob(next) } finally {
                if (convCancelledFlag.get()) { _conversionState.value = null; IsoBuilderService.stop(next.context) }
                else startNextConversion()
            }
        }
    }

    private suspend fun executeConversionJob(job: QueuedConversionJob) {
        val appCtx = job.context; val config = job.config; val workRootPath = job.workRootPath
        val pkgDir = if (job.pkgDirOverride.isNotBlank()) File(job.pkgDirOverride) else File(workRootPath, "PKGS")
        val isoDir = File(workRootPath, "ISODIR"); val tmpDir = File(workRootPath, "TMP")
        val safeTargetDir = UsbGadgetEngine.resolveDirectPath(config.targetDir).trimEnd('/')
        val defaultSlug = "Windows_${config.build.build}_${config.build.archDisplay}_${config.lang.code}".replace(Regex("[^A-Za-z0-9._-]"), "_")
        val cleanCustom = config.customOutputName.trim().replace(Regex("[^A-Za-z0-9._ -]"), "_").trim()
        val finalFileName = if (config.outputMode == WinBuildOutputMode.DIRECT_NTFS_USB) {
            if (cleanCustom.isNotEmpty()) { if (cleanCustom.endsWith(".img", true)) cleanCustom else "$cleanCustom.img" } else "${defaultSlug}_USB.img"
        } else {
            if (cleanCustom.isNotEmpty()) { if (cleanCustom.endsWith(".iso", true)) cleanCustom else "$cleanCustom.iso" } else "$defaultSlug.iso"
        }
        val finalOutputPath = "$safeTargetDir/$finalFileName"

        val startMs = System.currentTimeMillis(); var currentStep = 1; var stepStartMs = startMs
        val stepDurations = mutableMapOf<Int, Long>(); val consoleLines = mutableListOf<String>()
        var lastMeasuredOutputBytes = 0L; var lastMeasuredMs = startMs; var smoothDiskSpeed = 0L; var activeBin = "init"

        fun addConsole(line: String) { val c = line.trim(); if (c.isNotEmpty()) { consoleLines.add(c.take(120)); if (consoleLines.size > 20) consoleLines.removeAt(0) } }
        fun advanceStep(newStep: Int, binName: String) {
            val now = System.currentTimeMillis()
            if (newStep > currentStep) { stepDurations[currentStep] = ((now - stepStartMs) / 1000L).coerceAtLeast(1L); stepStartMs = now; currentStep = newStep }
            activeBin = binName
        }
        fun publishConv(stage: String, detail: String, pct: Float, outBytes: Long = lastMeasuredOutputBytes) {
            val now = System.currentTimeMillis(); val elapsedSec = ((now - startMs) / 1000L).coerceAtLeast(1L); val dt = (now - lastMeasuredMs).coerceAtLeast(1L)
            if (outBytes > lastMeasuredOutputBytes && dt >= 300L) {
                val inst = ((outBytes - lastMeasuredOutputBytes) * 1000L) / dt
                smoothDiskSpeed = if (smoothDiskSpeed == 0L) inst else ((smoothDiskSpeed * 6 + inst * 4) / 10)
                lastMeasuredOutputBytes = outBytes; lastMeasuredMs = now
            }
            val clampedPct = pct.coerceIn(0f, 1f)
            val etaSec = if (clampedPct >= 0.04f && clampedPct < 0.99f) ((elapsedSec.toFloat() / clampedPct) * (1f - clampedPct)).toLong() else -1L
            val (batPct, _) = readBatteryPercent(appCtx)
            val queued = conversionQueue.toList()
            val st = WindowsConversionJobState(job.jobTitle, finalFileName, config.outputMode, currentStep, stage, detail, clampedPct, smoothDiskSpeed, elapsedSec, etaSec, outBytes, UsbGadgetEngine.getFreeSpaceBytes(config.targetDir), activeBin, batPct, stepDurations.toMap(), consoleLines.toList(), queued.size, queued.map { it.jobTitle }, convAutoMountLive, workRootPath)
            _conversionState.value = st
            NotificationHelper.updateIsoBuilderProgress(appCtx, st)
        }

        try {
            val tools = inspectTools(appCtx)
            val qCab = shellQuote(tools.cabextract!!); val qWim = shellQuote(tools.wimlib!!); val qChntpw = shellQuote(tools.chntpw!!)
            val qPkg = shellQuote(pkgDir.absolutePath); val qIsoDir = shellQuote(isoDir.absolutePath); val qTmp = shellQuote(tmpDir.absolutePath)
            val pathHeader = "PKG=$qPkg\nISODIR=$qIsoDir\nTMP=$qTmp\nexport TMPDIR=\"\$TMP\" HOME=\"\$TMP\""

            publishConv("Step 1/4 · Checking build tools...", "Verifying native ARM64 binaries", 0.02f)
            if (!runIsolatedRootScript(appCtx, "toolcheck", "set -e\n$pathHeader\nrm -rf \"\$ISODIR\" \"\$TMP\" /data/local/tmp/pb_uup_ext /data/local/tmp/pb_uup_hive\nmkdir -p \"\$ISODIR\" \"\$TMP\"\n$qCab --version >/dev/null\n$qWim --version >/dev/null").isSuccess) {
                throw IllegalStateException("Build tool check failed")
            }

            advanceStep(1, "cabextract")
            val allCabPaths = runIsolatedRootScript(appCtx, "listcabs", "$pathHeader\nfor c in \"\$PKG\"/*.cab \"\$PKG\"/*.CAB; do [ -f \"\$c\" ] && echo \"\$c\"; done").out.map { it.trim() }.filter { it.startsWith("/") && it.lowercase().endsWith(".cab") }
            val editionCabs = allCabPaths.filter { !isSkippableNonEditionCab(it.substringAfterLast('/')) }
            addConsole("Step 1/4: ${editionCabs.size} edition CAB(s) (${allCabPaths.size - editionCabs.size} non-edition skipped)")
            editionCabs.forEachIndexed { idx, cabPath ->
                if (convCancelledFlag.get()) return
                val cabName = cabPath.substringAfterLast('/')
                publishConv("Step 1/4 · Converting CAB ${idx + 1}/${editionCabs.size}...", cabName, 0.03f + (idx.toFloat() / editionCabs.size.coerceAtLeast(1).toFloat()) * 0.14f)
                val oneCabRes = runIsolatedRootScript(appCtx, "cab", "set -e\n$pathHeader\nBN=${shellQuote(cabName)}\nEXTDIR=\"/data/local/tmp/pb_uup_ext\"\nrm -rf \"\$EXTDIR\" && mkdir -p \"\$EXTDIR\"\nset +e\n$qCab -q -d \"\$EXTDIR\" ${shellQuote(cabPath)}\nset -e\nif [ -z \"\$(ls -A \"\$EXTDIR\" 2>/dev/null)\" ]; then rm -rf \"\$EXTDIR\"; exit 0; fi\n$qWim capture \"\$EXTDIR\" \"\$TMP/\$BN.esd\" \"Edition Package\" \"Edition Package\" --compress=none --no-acls --norpfix\nrm -rf \"\$EXTDIR\"")
                if (!oneCabRes.isSuccess) throw IllegalStateException("CAB convert failed ($cabName)")
                addConsole("Converted $cabName")
            }

            advanceStep(2, "wimlib-imagex")
            publishConv("Step 2/4 · Building boot.wim & WinRE...", "Applying WinPE & patching SOFTWARE hive", 0.20f)
            val metaList = runIsolatedRootScript(appCtx, "meta", "$pathHeader\nfor f in \"\$PKG\"/*.esd \"\$PKG\"/*.ESD; do [ -f \"\$f\" ] || continue; cnt=\$($qWim info \"\$f\" 2>/dev/null | grep -c '^Index:' || true); [ \"\$cnt\" -ge 3 ] && echo \"\$f\"; done").out.map { it.trim() }.filter { it.startsWith("/") && it.lowercase().endsWith(".esd") }
            if (metaList.isEmpty()) throw IllegalStateException("No Windows edition metadata ESD (3+ indexes) found.")
            val firstMeta = shellQuote(metaList.first())

            val bootWimRes = runIsolatedRootScript(appCtx, "bootwim", """
                set -e
                $pathHeader
                $qWim apply $firstMeta 1 "${'$'}ISODIR" --no-acls --no-attributes
                mkdir -p "${'$'}ISODIR/sources"
                $qWim export $firstMeta 2 "${'$'}TMP/winre.wim" --compress=maximum --boot
                cp -f "${'$'}TMP/winre.wim" "${'$'}ISODIR/sources/boot.wim"
                $qWim info "${'$'}ISODIR/sources/boot.wim" 1 "Microsoft Windows PE" "Microsoft Windows PE"
                $qWim info "${'$'}ISODIR/sources/boot.wim" 1 --image-property FLAGS=9
                HIVEDIR="/data/local/tmp/pb_uup_hive"
                rm -rf "${'$'}HIVEDIR" && mkdir -p "${'$'}HIVEDIR"
                $qWim extract "${'$'}ISODIR/sources/boot.wim" 1 "/Windows/System32/config/SOFTWARE" --dest-dir="${'$'}HIVEDIR" --no-acls --no-attributes >/dev/null 2>&1 || \
                  $qWim extract "${'$'}ISODIR/sources/boot.wim" 1 "/windows/system32/config/software" --dest-dir="${'$'}HIVEDIR" --no-acls --no-attributes >/dev/null 2>&1 || true
                HIVE_FILE=${'$'}(find "${'$'}HIVEDIR" -maxdepth 1 -type f -iname 'software' | head -n 1)
                if [ -n "${'$'}HIVE_FILE" ]; then
                    set +e
                    printf 'cd Microsoft\\Windows NT\\CurrentVersion\nnv 1 SystemRoot\ned SystemRoot\nX:\\${'$'}Windows.~bt\\Windows\ncd WinPE\nnv 1 InstRoot\ned InstRoot\nX:\\${'$'}Windows.~bt\nq\n' | $qChntpw -C -e "${'$'}HIVE_FILE"
                    set -e
                    $qWim update "${'$'}ISODIR/sources/boot.wim" 1 --command="add '${'$'}HIVE_FILE' /Windows/System32/config/SOFTWARE"
                fi
                rm -rf "${'$'}HIVEDIR"
                BG_FILE=""
                for bg in "${'$'}ISODIR/sources/background_cli.bmp" "${'$'}ISODIR/sources/background_svr.bmp" "${'$'}ISODIR/sources/background_cli.png"; do [ -f "${'$'}bg" ] && BG_FILE="${'$'}bg" && break; done
                [ -n "${'$'}BG_FILE" ] && ($qWim update "${'$'}ISODIR/sources/boot.wim" 1 --command="add '${'$'}BG_FILE' /Windows/system32/winre.jpg" || true)
                $qWim update "${'$'}ISODIR/sources/boot.wim" 1 --command="delete --force /Windows/System32/winpeshl.ini" || true
                $qWim export "${'$'}TMP/winre.wim" 1 "${'$'}ISODIR/sources/boot.wim" "Microsoft Windows Setup" "Microsoft Windows Setup"
                $qWim info "${'$'}ISODIR/sources/boot.wim" 2 --image-property FLAGS=2
                $qWim info "${'$'}ISODIR/sources/boot.wim" 2 --boot
                $qWim extract $firstMeta 3 "/Windows/System32/xmllite.dll" --dest-dir="${'$'}ISODIR/sources" --no-acls --no-attributes >/dev/null 2>&1 || true
                UPD_TXT="/data/local/tmp/pb_boot_update.txt"; : > "${'$'}UPD_TXT"
                echo "delete --force /Windows/System32/winpeshl.ini" >> "${'$'}UPD_TXT"
                [ -f "${'$'}ISODIR/setup.exe" ] && echo "add '${'$'}ISODIR/setup.exe' /setup.exe" >> "${'$'}UPD_TXT"
                [ -n "${'$'}BG_FILE" ] && echo "add '${'$'}BG_FILE' /sources/background.bmp" >> "${'$'}UPD_TXT" && echo "add '${'$'}BG_FILE' /Windows/system32/winre.jpg" >> "${'$'}UPD_TXT"
                for sf in "${'$'}ISODIR"/sources/*; do
                    [ -e "${'$'}sf" ] || continue; sbn=${'$'}(basename "${'$'}sf")
                    case "${'$'}sbn" in boot.wim|install.wim|install.esd|sxs|SXS|uup|UUP) continue ;; esac
                    echo "add '${'$'}sf' '/sources/${'$'}sbn'" >> "${'$'}UPD_TXT"
                done
                $qWim update "${'$'}ISODIR/sources/boot.wim" 2 < "${'$'}UPD_TXT"
                $qWim optimize "${'$'}ISODIR/sources/boot.wim"
                rm -f "${'$'}ISODIR/sources/xmllite.dll" "${'$'}UPD_TXT"
            """.trimIndent())
            if (!bootWimRes.isSuccess) throw IllegalStateException("boot.wim assembly failed: ${(bootWimRes.out + bootWimRes.err).takeLast(4).joinToString(" ").take(240)}")
            addConsole("Step 2/4 complete: boot.wim assembled + winre.wim kept")

            advanceStep(3, "wimlib-imagex")
            val detectedOs = WindowsCustomizationEngine.detectWindowsOsKind(config.build.title)
            val effectiveComp = if (!detectedOs.supportsEsd) WinIsoCompression.WIM else config.compression
            val requestedVirtualEds = config.virtualEditions.mapNotNull { VirtualEditionTarget.fromEditionId(it) }
            val useTwoStageSolidEsd = effectiveComp == WinIsoCompression.ESD && (!config.skipWinRe || requestedVirtualEds.isNotEmpty())
            val stageWimPath = if (useTwoStageSolidEsd) "${tmpDir.absolutePath}/install_stage.wim" else "${isoDir.absolutePath}/sources/install.${effectiveComp.ext}"
            val finalInstallPath = "${isoDir.absolutePath}/sources/install.${effectiveComp.ext}"
            val qStageWim = shellQuote(stageWimPath); val qFinalInstall = shellQuote(finalInstallPath)
            val wimProgLog = "/data/local/tmp/pb_wim_prog.log"; val qProgLog = shellQuote(wimProgLog)
            val exportArg = if (useTwoStageSolidEsd) "--compress=LZX:50" else effectiveComp.wimlibArg

            metaList.forEachIndexed { idx, metaPath ->
                if (convCancelledFlag.get()) return
                val spanTotal = if (useTwoStageSolidEsd) 0.30f else 0.48f
                val sliceBase = 0.28f + (idx.toFloat() / metaList.size.toFloat()) * spanTotal
                val sliceSpan = spanTotal / metaList.size.toFloat()
                publishConv("Step 3/4 · Exporting edition ${idx + 1}/${metaList.size}...", "${effectiveComp.label} · 0%", sliceBase)
                val wimPollJob = scope.launch {
                    val pctRegex = Regex("""(\d{1,3})%""")
                    while (isActive && !convCancelledFlag.get()) {
                        delay(700L)
                        val tailLines = Shell.cmd("tail -c 384 $qProgLog 2>/dev/null || true").exec().out
                        val lastPct = pctRegex.findAll(tailLines.joinToString(" ")).lastOrNull()?.groupValues?.get(1)?.toIntOrNull()
                        val curSz = Shell.cmd("stat -c '%s' $qStageWim 2>/dev/null || echo 0").exec().out.firstOrNull()?.trim()?.toLongOrNull() ?: lastMeasuredOutputBytes
                        tailLines.lastOrNull()?.trim()?.takeIf { it.isNotBlank() }?.let { addConsole(it) }
                        if (lastPct != null && lastPct in 0..100) publishConv("Step 3/4 · Exporting edition ${idx + 1}/${metaList.size}...", "${effectiveComp.label} · $lastPct%", sliceBase + (lastPct / 100f) * sliceSpan, curSz)
                    }
                }
                val qMeta = shellQuote(metaPath)
                val expRes = runIsolatedRootScript(appCtx, "wimexp", "set -e\n$pathHeader\n: > $qProgLog\nED_NAME=\$($qWim info $qMeta 3 2>/dev/null | sed -n 's/^Display Name:[[:space:]]*//p; s/^Name:[[:space:]]*//p' | head -n 1)\n[ -z \"\$ED_NAME\" ] && ED_NAME=\"Windows ${config.build.build}\"\nset --\nfor ref in \"\$PKG\"/*.esd \"\$PKG\"/*.ESD \"\$TMP\"/*.esd; do [ -f \"\$ref\" ] && set -- \"\$@\" --ref=\"\$ref\"; done\n$qWim export $qMeta 3 $qStageWim \"\$ED_NAME\" $exportArg \"\$@\" > $qProgLog 2>&1")
                wimPollJob.cancel()
                if (!expRes.isSuccess) {
                    val err = Shell.cmd("tail -n 6 $qProgLog 2>/dev/null || true").exec().out.filter { it.isNotBlank() }.joinToString(" | ").take(260)
                    Shell.cmd("rm -f $qProgLog 2>/dev/null || true").exec()
                    throw IllegalStateException("install.${effectiveComp.ext} export failed: ${err.ifBlank { "code ${expRes.code}" }}")
                }
                Shell.cmd("rm -f $qProgLog 2>/dev/null || true").exec()
            }

            if (requestedVirtualEds.isNotEmpty()) {
                activeBin = "reged+wimlib"
                requestedVirtualEds.forEachIndexed { vIdx, veTarget ->
                    if (convCancelledFlag.get()) return
                    val veBuildMajor = config.build.build.substringBefore('.').toIntOrNull() ?: 22000
                    publishConv("Step 3/4 · Virtual Edition ${vIdx + 1}/${requestedVirtualEds.size}...", veTarget.displayNameForBuild(veBuildMajor), 0.58f + (vIdx.toFloat() / requestedVirtualEds.size.toFloat()) * 0.05f)
                    val ok = VirtualEditionsPlugin.createVirtualEditionInWim(appCtx, tools.wimlib!!, tools.chntpw!!, stageWimPath, veTarget) { msg ->
                        addConsole(msg)
                        emitConvLog("win-ve", msg)
                    }
                    if (!ok) addConsole("Skipped/unchanged Virtual Edition: ${veTarget.editionId}")
                }
            }

            if (!config.skipWinRe) {
                val totalWimIndexes = Shell.cmd("$qWim info $qStageWim 2>/dev/null | grep -c '^Index:' || echo ${metaList.size}").exec().out.firstOrNull()?.trim()?.toIntOrNull()?.coerceAtLeast(metaList.size) ?: metaList.size
                publishConv("Step 3/4 · Injecting Winre.wim (Recovery)...", "Embedding Recovery Environment ($totalWimIndexes edition(s))", 0.63f)
                val winreRes = runIsolatedRootScript(appCtx, "winreinj", "set -e\n$pathHeader\nif [ -f \"\$TMP/winre.wim\" ]; then for idx in \$(seq 1 $totalWimIndexes); do $qWim update $qStageWim \"\$idx\" --command=\"add '\$TMP/winre.wim' /Windows/System32/Recovery/Winre.wim\"; done; fi")
                if (winreRes.isSuccess) { addConsole("Injected Winre.wim ($totalWimIndexes edition(s))"); emitConvLog("win-build", "Injected Winre.wim into /Windows/System32/Recovery/Winre.wim ($totalWimIndexes indexes)") }
            }

            if (useTwoStageSolidEsd) {
                publishConv("Step 3/4 · Compressing to Solid ESD (LZMS)...", "0%", 0.66f)
                val esdPoll = scope.launch {
                    val pctRegex = Regex("""(\d{1,3})%""")
                    while (isActive && !convCancelledFlag.get()) {
                        delay(800L)
                        val p = pctRegex.findAll(Shell.cmd("tail -c 256 $qProgLog 2>/dev/null || true").exec().out.joinToString(" ")).lastOrNull()?.groupValues?.get(1)?.toIntOrNull()
                        val curSz = Shell.cmd("stat -c '%s' $qFinalInstall 2>/dev/null || echo 0").exec().out.firstOrNull()?.trim()?.toLongOrNull() ?: lastMeasuredOutputBytes
                        if (p != null && p in 0..100) publishConv("Step 3/4 · Compressing to Solid ESD (LZMS)...", "$p%", 0.66f + (p / 100f) * 0.14f, curSz)
                    }
                }
                val esdRes = runIsolatedRootScript(appCtx, "esdconv", "set -e\n$pathHeader\n: > $qProgLog\n$qWim export $qStageWim all $qFinalInstall --compress=LZMS --solid --threads=2 > $qProgLog 2>&1\nrm -f $qStageWim $qProgLog")
                esdPoll.cancel()
                if (!esdRes.isSuccess) throw IllegalStateException("Solid ESD compression failed (code ${esdRes.code})")
                addConsole("Converted install.wim + Winre.wim -> Solid install.esd")
            } else {
                runIsolatedRootScript(appCtx, "wimopt", "$pathHeader\n$qWim optimize $qFinalInstall >/dev/null 2>&1 || true")
            }

            advanceStep(4, "oem-inject")
            publishConv("Step 4/4 · Injecting \$OEM\$ packages & tweaks...", "Preparing unplug-safe setup layout", 0.81f)
            val oemUpdates = "${isoDir.absolutePath}/sources/\$OEM\$/\$\$/Setup/Updates"
            val oemApps = "${isoDir.absolutePath}/sources/\$OEM\$/\$\$/Setup/Apps"
            val oemSxs = "${isoDir.absolutePath}/sources/\$OEM\$/\$\$/Setup/sxs"
            val oemScripts = "${isoDir.absolutePath}/sources/\$OEM\$/\$\$/Setup/Scripts"
            val oemCustom = "${isoDir.absolutePath}/sources/\$OEM\$/\$\$/Setup/Custom"
            val oemPanther = "${isoDir.absolutePath}/sources/\$OEM\$/\$\$/Panther"

            if (job.hasUpdates) {
                addConsole("Staging Additional Updates into sources/\$OEM\$/\$\$/Setup/Updates/")
                runIsolatedRootScript(appCtx, "oemupd", "$pathHeader\nmkdir -p ${shellQuote(oemUpdates)}\nfor f in \"\$PKG\"/*.msu \"\$PKG\"/*-KB*.cab \"\$PKG\"/*-kb*.cab \"\$PKG\"/*SSU*.cab \"\$PKG\"/*ssu*.cab \"\$PKG\"/*NDP*.cab; do [ -f \"\$f\" ] && cp -f \"\$f\" ${shellQuote(oemUpdates)}/ 2>/dev/null || true; done")
            }
            if (job.hasApps) {
                addConsole("Staging Inbox Store Apps into sources/\$OEM\$/\$\$/Setup/Apps/")
                runIsolatedRootScript(appCtx, "oemapp", "$pathHeader\nmkdir -p ${shellQuote(oemApps)}\nfor f in \"\$PKG\"/*.msixbundle \"\$PKG\"/*.appxbundle \"\$PKG\"/*.msix \"\$PKG\"/*.appx \"\$PKG\"/*License*.xml \"\$PKG\"/*license*.xml; do [ -f \"\$f\" ] && cp -f \"\$f\" ${shellQuote(oemApps)}/ 2>/dev/null || true; done")
            }
            if (job.hasNetFx3) {
                addConsole("Staging .NET Framework 3.5 into sources/\$OEM\$/\$\$/Setup/sxs/")
                runIsolatedRootScript(appCtx, "oemfx3", "$pathHeader\nmkdir -p ${shellQuote(oemSxs)}\nfor f in \"\$PKG\"/*netfx3-ondemand*.cab \"\$PKG\"/*NetFx3*.cab; do [ -f \"\$f\" ] && cp -f \"\$f\" ${shellQuote(oemSxs)}/ 2>/dev/null || true; done")
            }
            runIsolatedRootScript(appCtx, "w7efi", WindowsCustomizationEngine.applyWin7UefiFixSnippet(qWim, qFinalInstall, qIsoDir))
            if (config.experience.useCa2023Bootloaders) {
                addConsole("Applying Windows CA 2023 signed bootloaders...")
                runIsolatedRootScript(appCtx, "ca2023", WindowsCustomizationEngine.applyCa2023BootloadersSnippet(qWim, qFinalInstall, qIsoDir)).out.filter { it.startsWith("LOG|") }.forEach { addConsole(it.substringAfterLast('|')) }
            }
            if (config.eiCfgUnlock) {
                val tmpEi = File(appCtx.cacheDir, "wb_ei.cfg").apply { writeText("[Channel]\r\nRetail\r\n", Charsets.UTF_8) }
                Shell.cmd("cp -f ${shellQuote(tmpEi.absolutePath)} ${shellQuote("${isoDir.absolutePath}/sources/ei.cfg")}").exec()
                tmpEi.delete(); addConsole("Wrote sources/ei.cfg")
            }

            val needUnattendOrOem = config.experience.hasAnyActive() || config.winUtil.hasAnyActive() || job.hasUpdates || job.hasApps || job.hasNetFx3 || config.runComponentCleanup
            if (needUnattendOrOem) {
                val arch = if (config.build.arch.lowercase() in setOf("x86", "arm64", "amd64")) config.build.arch.lowercase() else "amd64"
                val tmpXml = File(appCtx.cacheDir, "wb_auto.xml").apply { writeText(WindowsCustomizationEngine.generateAutounattendXml(config.experience, config.winUtil, arch, null, true, job.hasUpdates), Charsets.UTF_8) }
                val tmpSpec = File(appCtx.cacheDir, "wb_spec.cmd").apply { writeText(WindowsCustomizationEngine.buildSpecializeScript(config.experience, config.winUtil, job.hasUpdates, job.hasApps, job.hasNetFx3), Charsets.UTF_8) }
                val customPath = config.experience.customPostInstallScriptPath.trim()
                val customName = if (customPath.isNotBlank()) customPath.substringAfterLast('/') else ""
                val tmpComp = File(appCtx.cacheDir, "wb_comp.cmd").apply { writeText(WindowsCustomizationEngine.buildSetupCompleteScript(config.runComponentCleanup, customName.isNotBlank(), customName, hasOemApps = job.hasApps), Charsets.UTF_8) }
                val customCopy = if (customPath.isNotBlank()) "mkdir -p ${shellQuote(oemCustom)} && cp -f ${shellQuote(UsbGadgetEngine.resolveDirectPath(customPath))} ${shellQuote(oemCustom)}/ 2>/dev/null || true" else "true"
                Shell.cmd("mkdir -p ${shellQuote(oemPanther)} ${shellQuote(oemScripts)} && cp -f ${shellQuote(tmpXml.absolutePath)} ${shellQuote("${isoDir.absolutePath}/autounattend.xml")} && cp -f ${shellQuote(tmpXml.absolutePath)} ${shellQuote("$oemPanther/unattend.xml")} && cp -f ${shellQuote(tmpSpec.absolutePath)} ${shellQuote("$oemScripts/PocketBoot_Specialize.cmd")} && cp -f ${shellQuote(tmpComp.absolutePath)} ${shellQuote("$oemScripts/SetupComplete.cmd")} && $customCopy").exec()
                tmpXml.delete(); tmpSpec.delete(); tmpComp.delete()
                addConsole("Injected autounattend.xml + unplug-safe \$OEM\$ scripts")
            }

            if (job.isReadOnlySource) {
                Shell.cmd("rm -rf $qTmp /data/local/tmp/pb_uup_ext /data/local/tmp/pb_uup_hive").exec()
                addConsole("Preserved local UUP source folder untouched (read-only)")
            } else if (config.keepPackagesAfterBuild) {
                saveBuildJobJson(appCtx, workRootPath, config, job.totalDownloadBytes, true)
                Shell.cmd("rm -rf $qTmp /data/local/tmp/pb_uup_ext /data/local/tmp/pb_uup_hive").exec()
                addConsole("Kept base UUP packages for fast rebuilds")
            } else {
                Shell.cmd("rm -rf $qPkg $qTmp /data/local/tmp/pb_uup_ext /data/local/tmp/pb_uup_hive").exec()
            }

            var injectedMbPath: String? = null
            if (config.outputMode == WinBuildOutputMode.DIRECT_NTFS_USB) {
                activeBin = "mkfs.ntfs"
                publishConv("Step 4/4 · Building Direct Bootable USB...", "${config.scheme.label} · NTFS + UEFI/BIOS Boot", 0.85f)
                val usbErr = buildDirectNtfsUsbFromDir(appCtx, isoDir.absolutePath, finalOutputPath, config) { stg, det, f, curB -> publishConv(stg, det, 0.85f + f * 0.12f, curB) }
                if (usbErr != null) throw IllegalStateException(usbErr)
            } else {
                activeBin = "xorriso"
                val qXor = shellQuote(tools.xorriso!!); val qOutIso = shellQuote(finalOutputPath)
                val volId = "WIN_${config.build.build.substringBefore('.')}".take(28).uppercase()
                val estIsoBytes = Shell.cmd("du -sb $qIsoDir 2>/dev/null | awk '{print \$1}'").exec().out.firstOrNull()?.trim()?.toLongOrNull()?.coerceAtLeast(1L) ?: (5L * 1024L * 1024L * 1024L)
                publishConv("Step 4/4 · Packing bootable ISO...", finalFileName, 0.86f)
                val isoPollJob = scope.launch {
                    while (isActive && !convCancelledFlag.get()) {
                        delay(600L)
                        val curSz = Shell.cmd("stat -c '%s' $qOutIso 2>/dev/null || echo 0").exec().out.firstOrNull()?.trim()?.toLongOrNull() ?: 0L
                        if (curSz > 0L) publishConv("Step 4/4 · Packing bootable ISO...", "${UsbGadgetEngine.formatBytes(curSz)} / ~${UsbGadgetEngine.formatBytes(estIsoBytes)}", 0.86f + (curSz.toFloat() / estIsoBytes.toFloat()).coerceIn(0f, 1f) * 0.10f, curSz)
                    }
                }
                val xorRes = runIsolatedRootScript(appCtx, "xorriso", "set -e\n$pathHeader\nmkdir -p ${shellQuote(safeTargetDir)}\nrm -f $qOutIso\nEFI_BOOT=\"\"\nif [ -f \"\$ISODIR/efi/microsoft/boot/efisys.bin\" ]; then EFI_BOOT=\"efi/microsoft/boot/efisys.bin\"; elif [ -f \"\$ISODIR/EFI/Microsoft/Boot/efisys.bin\" ]; then EFI_BOOT=\"EFI/Microsoft/Boot/efisys.bin\"; fi\nBIOS_BOOT=\"\"\n[ -f \"\$ISODIR/boot/etfsboot.com\" ] && BIOS_BOOT=\"boot/etfsboot.com\"\nif [ -n \"\$BIOS_BOOT\" ] && [ -n \"\$EFI_BOOT\" ]; then $qXor -as mkisofs -iso-level 3 -full-iso9660-filenames -volid \"$volId\" -udf -b \"\$BIOS_BOOT\" -no-emul-boot -boot-load-size 8 -boot-info-table -eltorito-alt-boot -e \"\$EFI_BOOT\" -no-emul-boot -o $qOutIso \"\$ISODIR\"\nelif [ -n \"\$EFI_BOOT\" ]; then $qXor -as mkisofs -iso-level 3 -full-iso9660-filenames -volid \"$volId\" -udf -e \"\$EFI_BOOT\" -no-emul-boot -o $qOutIso \"\$ISODIR\"\nelse $qXor -as mkisofs -iso-level 3 -full-iso9660-filenames -volid \"$volId\" -udf -o $qOutIso \"\$ISODIR\"; fi")
                isoPollJob.cancel()
                if (!xorRes.isSuccess) throw IllegalStateException("ISO packing failed: ${(xorRes.out + xorRes.err).takeLast(4).joinToString(" ").take(240)}")
                UsbGadgetEngine.fixUserStoragePermissions(appCtx, safeTargetDir, isDir = true)
                UsbGadgetEngine.fixUserStoragePermissions(appCtx, finalOutputPath, isDir = false)
                if (config.outputMode == WinBuildOutputMode.DIRECT_MULTIBOOT && config.targetMultiBootPath.isNotBlank()) {
                    val mbPath = UsbGadgetEngine.resolveDirectPath(config.targetMultiBootPath)
                    publishConv("Moving ISO into Multi-Boot...", mbPath.substringAfterLast('/'), 0.97f)
                    val addRes = VentoyImageBuilder.addIsoToVentoy(appCtx, mbPath, finalOutputPath, moveFile = true) { p -> publishConv("Moving into Multi-Boot...", p.detail.ifBlank { p.stage }, 0.98f) }
                    if (addRes.success) injectedMbPath = mbPath
                }
            }

            if (!job.isReadOnlySource && config.keepPackagesAfterBuild) Shell.cmd("rm -rf $qIsoDir").exec()
            else UsbGadgetEngine.cleanupTempWorkDir(workRootPath)

            val finalSize = Shell.cmd("stat -c '%s' ${shellQuote(finalOutputPath)} 2>/dev/null || echo 0").exec().out.firstOrNull()?.trim()?.toLongOrNull() ?: 0L
            val totalSec = ((System.currentTimeMillis() - startMs) / 1000L).coerceAtLeast(1L)
            val avgSpeed = if (finalSize > 0L) finalSize / totalSec else smoothDiskSpeed
            val benchStr = "Built in ${formatDurationShort(totalSec)} · Avg ${FastIsoDownloader.formatSpeed(avgSpeed)} · ${UsbGadgetEngine.formatBytes(finalSize)}"
            val bootLabel = if (injectedMbPath == null && finalSize > 0L) {
                val rpt = IsoInspector.inspectBootReadiness(appCtx, finalOutputPath, finalSize)
                when { rpt.hasUefiX64 && rpt.hasLegacyBios -> "✓ UEFI + BIOS Verified"; rpt.hasUefiX64 -> "✓ UEFI x64 Verified"; rpt.hasUefiArm64 -> "✓ UEFI ARM64 Verified"; rpt.hasLegacyBios -> "✓ Legacy BIOS Verified"; else -> null }
            } else null

            emitConvLog("win-build", "Completed $finalFileName ($benchStr${if (bootLabel != null) " · $bootLabel" else ""})")
            val completion = DownloadCompletionEvent(job.jobTitle, finalFileName, finalOutputPath, injectedMbPath, null, null, ChecksumVerifyStatus.NONE, convAutoMountLive, benchStr, bootLabel)
            FastIsoDownloader.publishExternalCompletion(completion)
            NotificationHelper.showBuildComplete(appCtx, job.jobTitle, "$finalFileName · $benchStr")
            job.onComplete(completion)
        } catch (_: CancellationException) {
            Shell.cmd("rm -rf ${shellQuote(isoDir.absolutePath)} ${shellQuote(tmpDir.absolutePath)} 2>/dev/null || true").exec()
        } catch (e: Exception) {
            Shell.cmd("rm -rf ${shellQuote(isoDir.absolutePath)} ${shellQuote(tmpDir.absolutePath)} 2>/dev/null || true").exec()
            val msg = "Windows build failed: ${e.message ?: "Unexpected error"}"
            emitConvLog("win-build", msg, true); job.onError(msg)
        }
    }

    private fun buildDirectNtfsUsbFromDir(context: Context, isoDirPath: String, outImgPath: String, config: WindowsChannelBuildConfig, onSubProgress: (String, String, Float, Long) -> Unit): String? {
        val ntfsFormatter = WindowsImageBuilder.findBundledBinary(context, "libmkfs_ntfs.so", listOf("mkfs.ntfs", "mkntfs")) ?: return "Bundled mkfs.ntfs not found."
        val ntfs3g = WindowsImageBuilder.findBundledBinary(context, "libntfs_3g.so", listOf("ntfs-3g"))
        val msSysBin = WindowsImageBuilder.findBundledBinary(context, "libms_sys.so", listOf("ms-sys"))
        val uefiBytes = runCatching { context.assets.open("uefi-ntfs.img").use { it.readBytes() } }.getOrNull() ?: return "Missing uefi-ntfs.img asset."

        val qSrcDir = shellQuote(isoDirPath); val qOut = shellQuote(outImgPath)
        val dirBytes = Shell.cmd("du -sb $qSrcDir 2>/dev/null | awk '{print \$1}'").exec().out.firstOrNull()?.trim()?.toLongOrNull() ?: (6L * 1024L * 1024L * 1024L)
        val extraDrvBytes = if (config.extraDriversDir.isNotBlank()) {
            val qDrv = shellQuote(UsbGadgetEngine.resolveDirectPath(config.extraDriversDir))
            Shell.cmd("[ -d $qDrv ] && du -sb $qDrv 2>/dev/null | awk '{print \$1}' || echo 0").exec().out.firstOrNull()?.trim()?.toLongOrNull() ?: 0L
        } else 0L

        val layout = DiskPartitioner.calculateLayout(dirBytes + extraDrvBytes, uefiBytes.size.toLong())
        val mntDir = "/data/local/tmp/pb_winbuild_usb_mnt"; var loopDev = ""
        return try {
            val safeDir = outImgPath.substringBeforeLast('/')
            onSubProgress("Step 4/4 · Allocating Direct USB...", UsbGadgetEngine.formatBytes(layout.totalBytes), 0.10f, 0L)
            Shell.cmd("mkdir -p ${shellQuote(safeDir)} && rm -f $qOut && truncate -s ${layout.totalBytes} $qOut").exec()

            val fUefi = File(context.cacheDir, "wb_uefi.bin").apply { writeBytes(uefiBytes) }
            try {
                if (config.scheme == PartitionScheme.MBR) {
                    val mbr = DiskPartitioner.buildMbrSector(layout, part1IsFat32 = false, includeUefiPart = true)
                    val fMbr = File(context.cacheDir, "wb_mbr.bin").apply { writeBytes(mbr) }
                    try {
                        if (!Shell.cmd("dd if=${shellQuote(fMbr.absolutePath)} of=$qOut bs=512 seek=0 count=1 conv=notrunc 2>&1 && dd if=${shellQuote(fUefi.absolutePath)} of=$qOut bs=512 seek=${layout.part2StartLba} conv=notrunc 2>&1").exec().isSuccess) return "MBR partition table write failed"
                    } finally { fMbr.delete() }
                } else {
                    val gpt = DiskPartitioner.buildGptBuffers(layout, includeUefiPart = true)
                    val fPrim = File(context.cacheDir, "wb_gpt_p.bin").apply { writeBytes(gpt.primarySectors) }
                    val fBack = File(context.cacheDir, "wb_gpt_b.bin").apply { writeBytes(gpt.backupSectors) }
                    try {
                        if (!Shell.cmd("dd if=${shellQuote(fPrim.absolutePath)} of=$qOut bs=512 seek=0 count=34 conv=notrunc 2>&1 && dd if=${shellQuote(fBack.absolutePath)} of=$qOut bs=512 seek=${layout.totalSectors - 33L} count=33 conv=notrunc 2>&1 && dd if=${shellQuote(fUefi.absolutePath)} of=$qOut bs=512 seek=${layout.part2StartLba} conv=notrunc 2>&1").exec().isSuccess) return "GPT write failed"
                    } finally { fPrim.delete(); fBack.delete() }
                }
            } finally { fUefi.delete() }

            val loopCmd = """
                OUT_DEV=""
                for idx in ${'$'}(seq 0 63); do
                    CAND="/dev/block/loop${'$'}idx"; [ -b "${'$'}CAND" ] || CAND="/dev/loop${'$'}idx"; [ -b "${'$'}CAND" ] || continue
                    if losetup "${'$'}CAND" 2>/dev/null | grep -q '/'; then continue; fi
                    if losetup -o ${layout.part1OffsetBytes} -S ${layout.part1SizeBytes} "${'$'}CAND" $qOut 2>/dev/null || losetup -o ${layout.part1OffsetBytes} --sizelimit ${layout.part1SizeBytes} "${'$'}CAND" $qOut 2>/dev/null; then OUT_DEV="${'$'}CAND"; break; fi
                done
                [ -z "${'$'}OUT_DEV" ] && OUT_DEV=${'$'}(losetup -f -s -o ${layout.part1OffsetBytes} -S ${layout.part1SizeBytes} $qOut 2>/dev/null || losetup -f --show -o ${layout.part1OffsetBytes} --sizelimit ${layout.part1SizeBytes} $qOut 2>/dev/null || true)
                echo "${'$'}OUT_DEV"
            """.trimIndent()
            loopDev = Shell.cmd(loopCmd).exec().out.lastOrNull().orEmpty().trim()
            if (loopDev.isBlank()) return "Loop attach failed"

            val qLoop = shellQuote(loopDev)
            onSubProgress("Step 4/4 · Formatting NTFS...", config.scheme.label, 0.25f, 0L)
            if (!Shell.cmd("${shellQuote(ntfsFormatter)} -F -Q -L POCKETBOOT -H 255 -S 63 -p ${layout.part1StartLba} $qLoop 2>&1").exec().isSuccess) return "NTFS format failed"
            if (config.scheme == PartitionScheme.MBR && msSysBin != null) Shell.cmd("${shellQuote(msSysBin)} -n -f $qLoop >/dev/null 2>&1 || true").exec()

            val procFs = Shell.cmd("cat /proc/filesystems 2>/dev/null").exec().out.joinToString(" ").lowercase()
            val mntCmd = buildList { if (procFs.contains("ntfs3")) add("mount -t ntfs3 -o rw $qLoop $mntDir 2>&1"); if (ntfs3g != null) add("${shellQuote(ntfs3g)} -o rw,big_writes,noatime $qLoop $mntDir 2>&1") }.joinToString(" || ")
            if (!Shell.cmd("mkdir -p $mntDir && ($mntCmd)").exec().isSuccess) return "NTFS mount failed"

            onSubProgress("Step 4/4 · Copying Windows files to USB...", UsbGadgetEngine.formatBytes(dirBytes), 0.50f, dirBytes / 2L)
            val cpRes = Shell.cmd("cp -rf $qSrcDir/. $mntDir/ 2>&1").exec()
            if (!cpRes.isSuccess) return "File copy into NTFS USB failed: ${cpRes.out.takeLast(3).joinToString(" ").take(180)}"

            if (config.extraDriversDir.isNotBlank()) {
                val drvSrc = UsbGadgetEngine.resolveDirectPath(config.extraDriversDir)
                onSubProgress("Step 4/4 · Copying /Drivers folder...", UsbGadgetEngine.friendlyFolderName(drvSrc), 0.85f, dirBytes)
                Shell.cmd("if [ -d ${shellQuote(drvSrc)} ]; then mkdir -p $mntDir/Drivers && cp -rf ${shellQuote(drvSrc)}/. $mntDir/Drivers/ 2>&1; fi").exec()
            }

            onSubProgress("Step 4/4 · Syncing bootable USB...", outImgPath.substringAfterLast('/'), 0.95f, dirBytes + extraDrvBytes)
            Shell.cmd("sync; umount $mntDir 2>/dev/null || umount -l $mntDir 2>/dev/null; sleep 0.3; [ -n ${shellQuote(loopDev)} ] && losetup -d ${shellQuote(loopDev)} 2>/dev/null; rm -rf $mntDir").exec()
            loopDev = ""

            if (config.scheme == PartitionScheme.MBR && msSysBin != null) Shell.cmd("${shellQuote(msSysBin)} -7 -f $qOut >/dev/null 2>&1 || true").exec()
            UsbGadgetEngine.fixUserStoragePermissions(context, safeDir, isDir = true)
            UsbGadgetEngine.fixUserStoragePermissions(context, outImgPath, isDir = false)
            null
        } finally {
            if (loopDev.isNotBlank()) Shell.cmd("sync; umount $mntDir 2>/dev/null || umount -l $mntDir 2>/dev/null; sleep 0.3; losetup -d ${shellQuote(loopDev)} 2>/dev/null; rm -rf $mntDir").exec()
        }
    }
}
