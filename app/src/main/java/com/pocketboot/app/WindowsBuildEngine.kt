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

enum class BuildThermalProfile(
    val label: String,
    val shortLabel: String,
    val subtitle: String,
    val wimThreads: Int,
    val esdThreads: Int,
    val sevenZipThreads: Int,
    val niceLevel: Int,
    val ionicePrio: Int,
    val cooldownTriggerC: Float,
    val cooldownResumeC: Float
) {
    COOL("Cool", "Cool", "2 threads · avoids Prime core · auto-cooldown @ 41.5°C", 2, 1, 2, 15, 7, 41.5f, 38.5f),
    BALANCED("Balanced", "Balanced", "4 threads · low priority · auto-cooldown @ 43.0°C", 4, 2, 4, 10, 5, 43.0f, 39.0f),
    MAX("Max Speed", "Max", "All cores · normal priority · no auto-cooldown", 0, 4, 0, 0, 0, 0f, 0f)
}

enum class WinIsoCompression(val label: String, val shortLabel: String, val subtitle: String, val ext: String, val wimlibArg: String, val recommended: Boolean = false) {
    WIM("Standard (LZX)", "LZX", "Default · retail size · balanced heat", "wim", "--compress=maximum", recommended = true),
    XPRESS("Fast & Cool (XPRESS)", "XPRESS", "~2× faster · lowest heat · larger ISO (~7 GB)", "wim", "--compress=fast", recommended = false),
    ESD("Compact (Solid ESD)", "ESD", "Smallest ISO · slowest & warmest", "esd", "--compress=LZMS --solid", recommended = false)
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
enum class WinStoreAppLevel(val label: String, val shortLabel: String, val subtitle: String) {
    SECURITY_ONLY("Security Only (Default)", "Security", "Auto-stages SecHealthUI on Win 11 22H2+ (~25 MB)"),
    ESSENTIAL("Essential Apps", "Essential", "Store, Security, Terminal, Notepad, Photos, Paint + Frameworks"),
    ALL_INBOX("All Inbox Apps", "All Apps", "Full filtered inbox Store apps + Frameworks")
}
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
    val localUupDirPath: String = "",
    val storeAppLevel: WinStoreAppLevel = if (includeStoreApps) WinStoreAppLevel.ALL_INBOX else WinStoreAppLevel.SECURITY_ONLY,
    val splitWimForFat32: Boolean = false,
    val thermalProfile: BuildThermalProfile = BuildThermalProfile.BALANCED,
    val autoThermalCooldown: Boolean = true
)

data class PendingWindowsBuild(
    val workRootPath: String, val workPrefix: String, val title: String, val outputName: String,
    val cachedBytes: Long, val totalBytes: Long, val isCachedReady: Boolean = false, val config: WindowsChannelBuildConfig,
    val hasStagedIsoDir: Boolean = false
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
    val autoMount: Boolean = true, val workDirPath: String = "",
    val keepPackagesAfterBuild: Boolean = false, val isReadOnlySource: Boolean = false,
    val isFailed: Boolean = false, val errorMessage: String = "",
    val batteryTempC: Float = 0f,
    val thermalProfile: BuildThermalProfile = BuildThermalProfile.BALANCED,
    val autoThermalCooldown: Boolean = true,
    val isCoolingDown: Boolean = false
)

data class WindowsBuildToolsStatus(
    val cabextract: String?,
    val wimlib: String?,
    val chntpw: String?,
    val xorriso: String?,
    val sevenZip: String? = null
) {
    val canConvertIso: Boolean get() = cabextract != null && wimlib != null && chntpw != null && xorriso != null
    val canBuildDirectUsb: Boolean get() = cabextract != null && wimlib != null && chntpw != null
}

private data class QueuedConversionJob(
    val context: Context, val config: WindowsChannelBuildConfig, val workRootPath: String, val workPrefix: String,
    val jobTitle: String, val totalDownloadBytes: Long, val hasUpdates: Boolean, val hasApps: Boolean, val hasNetFx3: Boolean,
    val isReadOnlySource: Boolean = false, val pkgDirOverride: String = "", val resumeFromStep4: Boolean = false,
    val onComplete: (DownloadCompletionEvent) -> Unit, val onError: (String) -> Unit, val onLog: (LogEntry) -> Unit
)

object WindowsBuildEngine {
    private const val DEFAULT_API_BASE = "https://api.uupdump.net"
    private const val GITHUB_RAW_BASE = "https://raw.githubusercontent.com/bilered/PocketBoot/main"
    private const val UA = "Mozilla/5.0 (Linux; Android 15) PocketBoot/4.5.0"
    private const val STAGE_SCHEMA_VERSION = "PB_STAGE_V20"

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
    @Volatile private var convKeepPackagesLive: Boolean = false
    @Volatile private var convThermalProfileLive: BuildThermalProfile = BuildThermalProfile.BALANCED
    @Volatile private var convAutoCooldownLive: Boolean = true
    @Volatile private var convThermalCoolingLive: Boolean = false
    @Volatile private var defaultThermalProfile: BuildThermalProfile = BuildThermalProfile.BALANCED
    @Volatile private var defaultAutoCooldown: Boolean = true
    @Volatile private var lastFailedJob: QueuedConversionJob? = null
    @Volatile private var cachedCpuMasks: Triple<String, String, String>? = null
    private const val ACTIVE_PID_FILE = "/data/local/tmp/pb_active_pid"

    fun configureThermal(profile: BuildThermalProfile, autoCooldown: Boolean) {
        defaultThermalProfile = profile
        defaultAutoCooldown = autoCooldown
    }

    fun currentDefaultThermalProfile(): BuildThermalProfile = defaultThermalProfile

    private fun detectCpuMasks(): Triple<String, String, String> {
        cachedCpuMasks?.let { return it }
        val res = runCatching {
            val out = Shell.cmd(
                "for c in /sys/devices/system/cpu/cpu[0-9]*; do " +
                    "[ -d \"\$c\" ] || continue; " +
                    "id=\${c##*cpu}; " +
                    "f=\$(cat \"\$c/cpufreq/cpuinfo_max_freq\" 2>/dev/null || cat \"\$c/cpufreq/scaling_max_freq\" 2>/dev/null || echo 1000000); " +
                    "echo \"\$id \$f\"; done"
            ).exec().out
            val pairs = out.mapNotNull { line ->
                val p = line.trim().split(Regex("\\s+"))
                val id = p.getOrNull(0)?.toIntOrNull() ?: return@mapNotNull null
                val freq = p.getOrNull(1)?.toLongOrNull() ?: 1_000_000L
                if (id in 0..30) id to freq else null
            }.sortedBy { it.first }
            if (pairs.size < 2) return@runCatching Triple("", "", "")
            var allMask = 0
            pairs.forEach { (id, _) -> allMask = allMask or (1 shl id) }
            val maxFreq = pairs.maxOf { it.second }
            val distinctFreqs = pairs.map { it.second }.distinct().sorted()
            val nonPrime = pairs.filter { it.second < maxFreq }
            var balancedMask = 0
            if (nonPrime.size >= 2) {
                nonPrime.forEach { (id, _) -> balancedMask = balancedMask or (1 shl id) }
            } else {
                val keepCnt = (pairs.size * 3 / 4).coerceAtLeast(2)
                pairs.take(keepCnt).forEach { (id, _) -> balancedMask = balancedMask or (1 shl id) }
            }
            var coolMask = 0
            if (distinctFreqs.size >= 3) {
                val minFreq = distinctFreqs.first()
                val little = pairs.filter { it.second == minFreq }
                val mid = pairs.filter { it.second > minFreq && it.second < maxFreq }
                val chosen = (little + mid.take(2)).ifEmpty { nonPrime }
                chosen.forEach { (id, _) -> coolMask = coolMask or (1 shl id) }
            } else if (nonPrime.size >= 2) {
                nonPrime.take(nonPrime.size.coerceAtMost(4)).forEach { (id, _) -> coolMask = coolMask or (1 shl id) }
            } else {
                pairs.take((pairs.size / 2).coerceAtLeast(2)).forEach { (id, _) -> coolMask = coolMask or (1 shl id) }
            }
            Triple(
                "0x" + coolMask.toString(16),
                "0x" + balancedMask.toString(16),
                "0x" + allMask.toString(16)
            )
        }.getOrDefault(Triple("", "", ""))
        cachedCpuMasks = res
        return res
    }

    internal fun cpuMaskForProfile(profile: BuildThermalProfile): String {
        val (coolMask, balMask, allMask) = detectCpuMasks()
        return when (profile) {
            BuildThermalProfile.COOL -> coolMask
            BuildThermalProfile.BALANCED -> balMask
            BuildThermalProfile.MAX -> allMask
        }
    }

    internal fun thermalExecPrefix(profile: BuildThermalProfile): String {
        val mask = cpuMaskForProfile(profile)
        val parts = mutableListOf("THERM_PFX=\"\"")
        if (profile.ionicePrio > 0) {
            parts += "if ionice -c 2 -n ${profile.ionicePrio} true >/dev/null 2>&1; then THERM_PFX=\"\$THERM_PFX ionice -c 2 -n ${profile.ionicePrio}\"; fi"
        }
        if (profile.niceLevel > 0) {
            parts += "if nice -n ${profile.niceLevel} true >/dev/null 2>&1; then THERM_PFX=\"\$THERM_PFX nice -n ${profile.niceLevel}\"; fi"
        }
        if (profile != BuildThermalProfile.MAX && mask.isNotBlank()) {
            parts += "if taskset $mask true >/dev/null 2>&1; then THERM_PFX=\"\$THERM_PFX taskset $mask\"; fi"
        }
        return parts.joinToString("; ")
    }

    internal fun wimThreadArgFor(profile: BuildThermalProfile, isSolidEsd: Boolean = false): String {
        val t = if (isSolidEsd) profile.esdThreads else profile.wimThreads
        return if (t > 0) "--threads=$t" else ""
    }

    private fun applyLiveThermalTuningToActivePid(profile: BuildThermalProfile) {
        val mask = cpuMaskForProfile(profile)
        val cmd = buildString {
            append("PID=\$(cat $ACTIVE_PID_FILE 2>/dev/null | tr -dc '0-9'); ")
            append("if [ -n \"\$PID\" ] && kill -0 \"\$PID\" 2>/dev/null; then ")
            append("if command -v renice >/dev/null 2>&1; then ")
            append("renice -n ${profile.niceLevel} -p \"\$PID\" >/dev/null 2>&1 || renice ${profile.niceLevel} \"\$PID\" >/dev/null 2>&1 || true; ")
            append("for t in /proc/\$PID/task/*; do tid=\${t##*/}; renice -n ${profile.niceLevel} -p \"\$tid\" >/dev/null 2>&1 || true; done; ")
            append("fi; ")
            append("if command -v ionice >/dev/null 2>&1; then ionice -c 2 -n ${profile.ionicePrio} -p \"\$PID\" >/dev/null 2>&1 || true; fi; ")
            if (mask.isNotBlank()) {
                append("if command -v taskset >/dev/null 2>&1; then ")
                append("taskset -p $mask \"\$PID\" >/dev/null 2>&1 || true; ")
                append("for t in /proc/\$PID/task/*; do tid=\${t##*/}; taskset -p $mask \"\$tid\" >/dev/null 2>&1 || true; done; ")
                append("fi; ")
            }
            append("fi")
        }
        Shell.cmd(cmd).exec()
    }

    private fun signalActivePid(signal: String) {
        Shell.cmd(
            "PID=\$(cat $ACTIVE_PID_FILE 2>/dev/null | tr -dc '0-9'); " +
                "if [ -n \"\$PID\" ] && kill -0 \"\$PID\" 2>/dev/null; then kill -$signal \"\$PID\" 2>/dev/null || true; fi"
        ).exec()
    }
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
        xorriso = WindowsImageBuilder.findBundledBinary(context, "libxorriso.so", listOf("xorriso")),
        sevenZip = WindowsImageBuilder.findBundledBinary(context, "lib7zzs.so", listOf("7zzs", "7zz", "7z"))
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

    fun readBatteryTempC(context: Context): Float {
        return runCatching {
            val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val tenths = intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0
            if (tenths > 0) tenths / 10f else 0f
        }.getOrDefault(0f)
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
        return low.contains("windows10.0-kb") || low.contains("windows11.0-kb") || low.contains("windows12.0-kb") ||
            low.startsWith("ssu-") || low.contains("-ssu-") ||
            low.contains("desktopdeployment") || low.contains("aggregatedmetadata") ||
            low.contains("baseless") || low.contains("safeos") ||
            low.contains("dynamicupdate") || low.contains("netfx3-ondemand") ||
            low.contains("retaildemo") || low.contains("holographic-desktop-fod") ||
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

    private val FRAMEWORK_APP_TOKENS = listOf("vclibs", "ui.xaml", "windowsappruntime", "net.native", "directx")
    private val ESSENTIAL_APP_TOKENS = listOf(
        "sechealthui", "windowsstore", "storepurchaseapp", "desktopappinstaller",
        "startexperiencesapp", "applicationcompatibilityenhancements",
        "windowsterminal", "windowsnotepad", "windows.photos", "paint",
        "screensketch", "windowscalculator", "windowscamera"
    )

    /**
     * Cleans raw UUPdump `edition=app` file lists (which can contain ~2,800 items including foreign
     * `_language-*.msix`, `qps-ploc*` pseudo-locales, mismatched architectures, and ~8 KB stub bundles)
     * down to clean, installable packages matching the chosen architecture, language, and app level.
     */
    fun filterStoreApps(
        rawApps: List<WinPackageFileEntry>,
        langCode: String,
        arch: String,
        level: WinStoreAppLevel = WinStoreAppLevel.ALL_INBOX
    ): List<WinPackageFileEntry> {
        if (rawApps.isEmpty()) return emptyList()
        val langFull = langCode.lowercase()
        val langShort = langFull.substringBefore('-')
        val archLow = arch.lowercase()
        val langSatRegex = Regex("""_language-([a-z0-9-]+)(?:[._]|$)""", RegexOption.IGNORE_CASE)

        val cleaned = rawApps.filter { f ->
            val low = f.name.lowercase()
            // Keep only real installable appx/msix packages (skip loose xml/blockmap/etc.)
            if (!(low.endsWith(".msixbundle") || low.endsWith(".appxbundle") || low.endsWith(".msix") || low.endsWith(".appx"))) return@filter false
            // Drop pseudo-locales, stubs, and LanguageExperiencePack bloat
            if (low.contains("qps-ploc") || low.contains("stub.") || low.contains("stub_") || low.contains("languageexperiencepack")) return@filter false
            // Filter foreign `_language-<xx>` satellite packages
            val satMatch = langSatRegex.find(low)
            if (satMatch != null) {
                val tag = satMatch.groupValues[1]
                if (tag != langFull && tag != langShort && !tag.startsWith("$langShort-")) return@filter false
            }
            // Filter mismatched architecture packages (keep neutral & x86 frameworks on amd64)
            val isFw = FRAMEWORK_APP_TOKENS.any { low.contains(it) }
            when (archLow) {
                "amd64", "x64" -> if (low.contains("_arm64_") || low.contains("_arm_")) return@filter false
                "arm64" -> if (low.contains("_x64_") || low.contains("_amd64_")) return@filter false
                "x86" -> if (low.contains("_x64_") || low.contains("_amd64_") || low.contains("_arm64_") || low.contains("_arm_")) return@filter false
            }
            if (!isFw && archLow in setOf("amd64", "x64") && low.contains("_x86_")) return@filter false
            true
        }

        // Deduplicate ~8 KB stub bundles (e.g., `Microsoft.WindowsStore_8wekyb3d8bbwe.msixbundle`)
        // whenever a larger full versioned package exists for the same app family prefix.
        val byFamily = cleaned.groupBy { f -> f.name.substringBefore('_').lowercase() }
        val deduped = buildList {
            byFamily.values.forEach { group ->
                val hasFullBundle = group.any { it.sizeBytes >= 65_536L }
                group.forEach { item ->
                    if (hasFullBundle && item.sizeBytes in 1L until 65_536L && (item.name.endsWith(".msixbundle", true) || item.name.endsWith(".appxbundle", true))) {
                        // Drop ~8 KB stub wrapper in favor of the full bundle (StubAppsFull=1 parity)
                    } else {
                        add(item)
                    }
                }
            }
        }

        return when (level) {
            WinStoreAppLevel.ALL_INBOX -> deduped
            WinStoreAppLevel.SECURITY_ONLY -> deduped.filter { f ->
                val low = f.name.lowercase()
                low.contains("sechealthui")
            }
            WinStoreAppLevel.ESSENTIAL -> deduped.filter { f ->
                val low = f.name.lowercase()
                FRAMEWORK_APP_TOKENS.any { low.contains(it) } || ESSENTIAL_APP_TOKENS.any { low.contains(it) }
            }
        }.sortedBy { it.name.lowercase() }
    }

    private fun fetchSeparateStoreAppsFromApi(
        updateId: String,
        langCode: String,
        archHint: String,
        requireUrl: Boolean
    ): List<WinPackageFileEntry> {
        val noLinksParam = if (requireUrl) "" else "&noLinks=1"
        val appUrl = "$apiBaseUrl/get.php?id=${URLEncoder.encode(updateId, "UTF-8")}&lang=neutral&edition=app$noLinksParam"
        val appRoot = fetchJsonWithRetry(appUrl, timeoutMs = 12_000) ?: return emptyList()
        val parsedApps = parseDetailedManifest(appRoot, requireUrl).appFiles
        return filterStoreApps(parsedApps, langCode, archHint, WinStoreAppLevel.ALL_INBOX)
    }

    private fun mergeAppsIntoManifest(baseManifest: WinDetailedManifest, extraApps: List<WinPackageFileEntry>): WinDetailedManifest {
        if (extraApps.isEmpty()) return baseManifest
        val mergedApps = (baseManifest.appFiles + extraApps).distinctBy { it.name.lowercase() }.sortedBy { it.name.lowercase() }
        val mergedAll = (baseManifest.baseFiles + baseManifest.updateFiles + mergedApps + baseManifest.netFx3Files)
            .distinctBy { it.name.lowercase() }
            .sortedBy { it.name.lowercase() }
        return baseManifest.copy(appFiles = mergedApps, allFiles = mergedAll)
    }

    suspend fun fetchDetailedManifest(
        updateId: String,
        langCode: String,
        editionCodes: List<String>,
        archHint: String = "amd64",
        buildHint: String = ""
    ): WinDetailedManifest? = withContext(Dispatchers.IO) {
        val edParam = editionCodes.sorted().joinToString(";") { it.lowercase() }
        val key = "$updateId|$langCode|$edParam|$archHint"
        detailedManifestCache[key]?.let { return@withContext it }

        val buildMajor = buildHint.substringBefore('.').toIntOrNull() ?: 0
        val supportsInboxApps = buildMajor == 0 || buildMajor >= 22563

        if (shouldUseUupDumpApi()) {
            val url = "$apiBaseUrl/get.php?id=${URLEncoder.encode(updateId, "UTF-8")}&lang=${URLEncoder.encode(langCode, "UTF-8")}&edition=${URLEncoder.encode(edParam, "UTF-8")}&noLinks=1"
            val root = fetchJsonWithRetry(url)
            if (root != null) {
                var parsed = parseDetailedManifest(root, false)
                if (parsed.allFiles.isNotEmpty()) {
                    // Secondary `edition=app` query (matches UUPdump aria2_download.log) for Win 11 22H2+ Store Apps & SecHealthUI
                    if (supportsInboxApps && parsed.appFiles.isEmpty()) {
                        val extraApps = fetchSeparateStoreAppsFromApi(updateId, langCode, archHint, requireUrl = false)
                        parsed = mergeAppsIntoManifest(parsed, extraApps)
                    } else if (parsed.appFiles.isNotEmpty()) {
                        val filteredApps = filterStoreApps(parsed.appFiles, langCode, archHint, WinStoreAppLevel.ALL_INBOX)
                        parsed = parsed.copy(
                            appFiles = filteredApps,
                            allFiles = (parsed.baseFiles + parsed.updateFiles + filteredApps + parsed.netFx3Files).sortedBy { it.name.lowercase() }
                        )
                    }
                    detailedManifestCache[key] = parsed
                    return@withContext parsed
                }
            }
        }

        // Fallback: Direct Microsoft SyncUpdates cache or GitHub uup-db/<uuid>.json
        val rawFiles = MicrosoftUupClient.syncFilesCache[updateId] ?: fetchGitHubBuildManifest(updateId)?.second ?: return@withContext null
        val filtered = MicrosoftUupClient.filterFilesForSelection(rawFiles, langCode, editionCodes)
        val entries = filtered.map { WinPackageFileEntry(it.name, "", it.sizeBytes, it.sha1Hex, classifyPackageFile(it.name), 0L) }
        var parsed = buildDetailedManifestFromEntries(entries)
        if (parsed.appFiles.isNotEmpty()) {
            val cleanApps = filterStoreApps(parsed.appFiles, langCode, archHint, WinStoreAppLevel.ALL_INBOX)
            parsed = parsed.copy(
                appFiles = cleanApps,
                allFiles = (parsed.baseFiles + parsed.updateFiles + cleanApps + parsed.netFx3Files).sortedBy { it.name.lowercase() }
            )
        }
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
        archHint: String = "amd64",
        buildHint: String = ""
    ): WinDetailedManifest = withContext(Dispatchers.IO) {
        val buildMajor = buildHint.substringBefore('.').toIntOrNull() ?: 0
        val supportsInboxApps = buildMajor == 0 || buildMajor >= 22563

        // Layer 1: UUPdump API if enabled and healthy
        if (shouldUseUupDumpApi()) {
            val edParam = editionCodes.joinToString(";") { it.lowercase() }
            val url = "$apiBaseUrl/get.php?id=${URLEncoder.encode(updateId, "UTF-8")}&lang=${URLEncoder.encode(langCode, "UTF-8")}&edition=${URLEncoder.encode(edParam, "UTF-8")}"
            val root = fetchJsonWithRetry(url)
            if (root != null) {
                var parsed = parseDetailedManifest(root, true)
                if (parsed.allFiles.isNotEmpty()) {
                    if (supportsInboxApps && parsed.appFiles.isEmpty()) {
                        val extraApps = fetchSeparateStoreAppsFromApi(updateId, langCode, archHint, requireUrl = true)
                        parsed = mergeAppsIntoManifest(parsed, extraApps)
                    } else if (parsed.appFiles.isNotEmpty()) {
                        val cleanApps = filterStoreApps(parsed.appFiles, langCode, archHint, WinStoreAppLevel.ALL_INBOX)
                        parsed = parsed.copy(
                            appFiles = cleanApps,
                            allFiles = (parsed.baseFiles + parsed.updateFiles + cleanApps + parsed.netFx3Files).sortedBy { it.name.lowercase() }
                        )
                    }
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
            var built = buildDetailedManifestFromEntries(matched)
            if (built.appFiles.isNotEmpty()) {
                val cleanApps = filterStoreApps(built.appFiles, langCode, archHint, WinStoreAppLevel.ALL_INBOX)
                built = built.copy(
                    appFiles = cleanApps,
                    allFiles = (built.baseFiles + built.updateFiles + cleanApps + built.netFx3Files).sortedBy { it.name.lowercase() }
                )
            }
            return@withContext built
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
                put("storeAppLevel", config.storeAppLevel.name)
                put("splitWimForFat32", config.splitWimForFat32)
                put("thermalProfile", config.thermalProfile.name)
                put("autoThermalCooldown", config.autoThermalCooldown)
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
                    val cfg = WindowsChannelBuildConfig(bld, lang, eds, comp, mode, method, o.optString("targetDir", baseDir), o.optString("customOutputName", ""), o.optString("targetMultiBootPath", ""), o.optInt("connections", 8), o.optBoolean("autoMount", true), o.optBoolean("includeUpdates", false), o.optBoolean("includeStoreApps", false), o.optBoolean("integrateNetFx3", false), o.optBoolean("runComponentCleanup", false), o.optBoolean("skipWinRe", false), o.optBoolean("eiCfgUnlock", false), o.optBoolean("keepPackagesAfterBuild", false), scheme, exp, WinUtilTweaksConfig.fromJsonString(o.optString("winUtilJson", "")), extraDriversDir = o.optString("extraDriversDir", ""), virtualEditions = veList, storeAppLevel = runCatching { WinStoreAppLevel.valueOf(o.optString("storeAppLevel", if (o.optBoolean("includeStoreApps", false)) "ALL_INBOX" else "SECURITY_ONLY")) }.getOrDefault(WinStoreAppLevel.SECURITY_ONLY), splitWimForFat32 = o.optBoolean("splitWimForFat32", false), thermalProfile = runCatching { BuildThermalProfile.valueOf(o.optString("thermalProfile", defaultThermalProfile.name)) }.getOrDefault(defaultThermalProfile), autoThermalCooldown = o.optBoolean("autoThermalCooldown", defaultAutoCooldown))
                    val workPrefix = workRoot.substringAfterLast('/')
                    val pkgBytes = Shell.cmd("du -sb ${shellQuote("$workRoot/PKGS")} 2>/dev/null | awk '{print \$1}'").exec().out.firstOrNull()?.trim()?.toLongOrNull() ?: 0L
                    val stageBytes = stageCacheDir(context, workPrefix).walkTopDown().filter { it.isFile && it.name.endsWith(".part") }.sumOf { it.length() }
                    val totalBytes = o.optLong("totalBytes", 0L).coerceAtLeast(pkgBytes)
                    if (pkgBytes > 0L || stageBytes > 0L) {
                        val stagedReady = isStageMarkerValid(workRoot)
                        results.add(PendingWindowsBuild(workRoot, workPrefix, "Win ${bld.build} (${bld.archDisplay})", cfg.customOutputName.ifBlank { "Win_${bld.build}_${bld.archDisplay}_${lang.code}${if (mode == WinBuildOutputMode.DIRECT_NTFS_USB) "_USB.img" else ".iso"}" }, (pkgBytes + stageBytes).coerceAtMost(totalBytes.coerceAtLeast(pkgBytes + stageBytes)), totalBytes, o.optBoolean("isCachedReady", false), cfg, hasStagedIsoDir = stagedReady))
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
    fun updateConversionKeepPackages(value: Boolean) { convKeepPackagesLive = value; _conversionState.value = _conversionState.value?.copy(keepPackagesAfterBuild = value) }

    fun updateConversionThermalProfile(profile: BuildThermalProfile) {
        convThermalProfileLive = profile
        val cur = _conversionState.value
        val shouldResumeNow = convThermalCoolingLive && (profile.cooldownTriggerC <= 0f || (cur != null && cur.batteryTempC > 0f && cur.batteryTempC <= profile.cooldownResumeC))
        if (shouldResumeNow) convThermalCoolingLive = false
        if (cur != null) {
            val updatedTail = (cur.consoleTail + "Thermal profile -> ${profile.label} (${profile.subtitle})").takeLast(80)
            _conversionState.value = cur.copy(
                thermalProfile = profile,
                isCoolingDown = if (shouldResumeNow) false else cur.isCoolingDown,
                consoleTail = updatedTail
            )
        }
        scope.launch {
            applyLiveThermalTuningToActivePid(profile)
            if (shouldResumeNow) signalActivePid("CONT")
        }
    }

    fun updateConversionAutoCooldown(enabled: Boolean) {
        convAutoCooldownLive = enabled
        val shouldResumeNow = !enabled && convThermalCoolingLive
        if (shouldResumeNow) convThermalCoolingLive = false
        val cur = _conversionState.value
        if (cur != null) {
            _conversionState.value = cur.copy(
                autoThermalCooldown = enabled,
                isCoolingDown = if (shouldResumeNow) false else cur.isCoolingDown
            )
        }
        if (shouldResumeNow) {
            scope.launch { signalActivePid("CONT") }
        }
    }
    fun dismissFailedConversion() {
        if (_conversionState.value?.isFailed == true) {
            lastFailedJob = null
            _conversionState.value = null
        }
    }
    fun retryFailedConversion(): Boolean {
        val failed = lastFailedJob ?: return false
        val failedAtStep4 = _conversionState.value?.currentStep == 4
        lastFailedJob = null
        _conversionState.value = null
        enqueueConversion(
            failed.copy(
                config = failed.config.copy(autoMount = convAutoMountLive, keepPackagesAfterBuild = convKeepPackagesLive, thermalProfile = convThermalProfileLive, autoThermalCooldown = convAutoCooldownLive),
                resumeFromStep4 = failedAtStep4
            )
        )
        return true
    }

    /**
     * 100% offline rebuild from an already-downloaded cached build (zero internet calls, zero 8 GB re-hash).
     * Automatically resumes directly at Step 4 if a finished ISODIR staging marker (.pb_isodir_ready) exists.
     */
    fun rebuildCachedPending(
        context: Context,
        pending: PendingWindowsBuild,
        configOverride: WindowsChannelBuildConfig? = null,
        forceFullRebuild: Boolean = false,
        onComplete: (DownloadCompletionEvent) -> Unit,
        onError: (String) -> Unit,
        onLog: (LogEntry) -> Unit
    ) {
        val appCtx = context.applicationContext
        val prevConfig = pending.config
        val config = configOverride ?: prevConfig
        val workRootPath = pending.workRootPath
        val pkgPath = "$workRootPath/PKGS"
        scope.launch {
            try {
                val tools = inspectTools(appCtx)
                if (config.outputMode == WinBuildOutputMode.DIRECT_NTFS_USB && !tools.canBuildDirectUsb) {
                    throw IllegalStateException("Bundled build tools missing. Reinstall the latest APK.")
                }
                if (config.outputMode != WinBuildOutputMode.DIRECT_NTFS_USB && !tools.canConvertIso) {
                    throw IllegalStateException("Bundled ISO build tools (cabextract/wimlib/chntpw/xorriso) missing.")
                }
                val stagingOptionsChanged = config.compression != prevConfig.compression ||
                    (config.outputMode == WinBuildOutputMode.DIRECT_NTFS_USB) != (prevConfig.outputMode == WinBuildOutputMode.DIRECT_NTFS_USB) ||
                    config.includeUpdates != prevConfig.includeUpdates ||
                    config.integrateNetFx3 != prevConfig.integrateNetFx3 ||
                    config.runComponentCleanup != prevConfig.runComponentCleanup ||
                    config.skipWinRe != prevConfig.skipWinRe ||
                    config.eiCfgUnlock != prevConfig.eiCfgUnlock ||
                    config.splitWimForFat32 != prevConfig.splitWimForFat32 ||
                    config.virtualEditions != prevConfig.virtualEditions ||
                    config.experience != prevConfig.experience ||
                    config.winUtil != prevConfig.winUtil
                val rawReadyIsoDir = isStageMarkerValid(workRootPath)
                val canFastStep4 = rawReadyIsoDir && !forceFullRebuild && !stagingOptionsChanged
                if (rawReadyIsoDir && !canFastStep4) {
                    Shell.cmd("rm -f ${shellQuote("$workRootPath/.pb_isodir_ready")} 2>/dev/null || true").exec()
                }
                val esdCount = Shell.cmd("ls ${shellQuote(pkgPath)}/*.esd ${shellQuote(pkgPath)}/*.ESD 2>/dev/null | wc -l").exec().out.firstOrNull()?.trim()?.toIntOrNull() ?: 0
                if (!canFastStep4 && esdCount <= 0) {
                    throw IllegalStateException("Cached packages are incomplete or missing. Please tap Resume to verify/download.")
                }
                val hasUpd = Shell.cmd("find ${shellQuote(pkgPath)} -maxdepth 1 -type f \\( -iname '*.msu' -o -iname '*-kb*.cab' -o -iname '*ssu*.cab' -o -iname '*ndp*.cab' -o -iname '*dynamicupdate*.cab' \\) 2>/dev/null | head -n 1").exec().out.any { it.isNotBlank() }
                val hasApps = Shell.cmd("find ${shellQuote(pkgPath)} -maxdepth 1 -type f \\( -iname '*.msixbundle' -o -iname '*.appxbundle' -o -iname '*.msix' -o -iname '*.appx' \\) 2>/dev/null | head -n 1").exec().out.any { it.isNotBlank() }
                val hasFx3 = Shell.cmd("find ${shellQuote(pkgPath)} -maxdepth 1 -type f -iname '*netfx3*.cab' 2>/dev/null | head -n 1").exec().out.any { it.isNotBlank() }

                if (configOverride != null) {
                    saveBuildJobJson(appCtx, workRootPath, config, pending.totalBytes.coerceAtLeast(pending.cachedBytes), isCachedReady = true)
                }
                val modeDesc = if (canFastStep4) "Fast Step-4 pack (${config.compression.shortLabel})" else "${config.compression.shortLabel} · $esdCount cached ESDs"
                onLog(LogEntry(nowTs(), "win-build", "Queued 100% offline rebuild for ${pending.title} ($modeDesc)"))
                enqueueConversion(
                    QueuedConversionJob(
                        context = appCtx,
                        config = config,
                        workRootPath = workRootPath,
                        workPrefix = pending.workPrefix,
                        jobTitle = pending.title,
                        totalDownloadBytes = pending.totalBytes.coerceAtLeast(pending.cachedBytes),
                        hasUpdates = config.includeUpdates && hasUpd,
                        hasApps = hasApps,
                        hasNetFx3 = config.integrateNetFx3 && hasFx3,
                        isReadOnlySource = false,
                        pkgDirOverride = "",
                        resumeFromStep4 = canFastStep4,
                        onComplete = onComplete,
                        onError = onError,
                        onLog = onLog
                    )
                )
            } catch (e: Exception) {
                val msg = "Offline rebuild failed: ${e.message ?: "Unexpected error"}"
                onLog(LogEntry(nowTs(), "win-build", msg, true))
                onError(msg)
            }
        }
    }

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
            Shell.cmd("PID=\$(cat $ACTIVE_PID_FILE 2>/dev/null | tr -dc '0-9'); [ -n \"\$PID\" ] && kill -CONT \"\$PID\" 2>/dev/null || true; pkill -CONT -f 'libwimlib_imagex|liblibxorriso|libcabextract' 2>/dev/null || true; [ -n \"\$PID\" ] && kill -TERM \"\$PID\" 2>/dev/null || true; pkill -f 'libwimlib_imagex|liblibxorriso|libcabextract' 2>/dev/null || true; rm -rf $ACTIVE_PID_FILE /data/local/tmp/pb_uup_ext /data/local/tmp/pb_uup_hive /data/local/tmp/pb_wim_prog.log /data/local/tmp/pb_cab_prog.log /data/local/tmp/pb_ca2023 /data/local/tmp/pb_win7_efi 2>/dev/null || true").exec()
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

    private fun savePackageManifest(context: Context, workRootPath: String, files: List<WinPackageFileEntry>) {
        runCatching {
            val arr = JSONArray()
            files.forEach { f ->
                arr.put(JSONObject().apply {
                    put("name", f.name)
                    put("size", f.sizeBytes)
                    put("sha1", f.sha1.trim().lowercase())
                })
            }
            val tmp = File(context.cacheDir, "pb_manifest_tmp.json")
            tmp.writeText(arr.toString(), Charsets.UTF_8)
            Shell.cmd("mkdir -p ${shellQuote(workRootPath)} && cp -f ${shellQuote(tmp.absolutePath)} ${shellQuote("$workRootPath/pb_manifest.json")}").exec()
            tmp.delete()
        }
    }

    private fun loadPackageManifest(workRootPath: String): List<WinPackageFileEntry> {
        val raw = Shell.cmd("cat ${shellQuote("$workRootPath/pb_manifest.json")} 2>/dev/null || true").exec().out.joinToString("
").trim()
        if (raw.isBlank()) return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val name = o.optString("name", "").trim()
                    if (name.isEmpty()) continue
                    add(WinPackageFileEntry(name, "", o.optLong("size", 0L), o.optString("sha1", "").trim().lowercase()))
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun computePkgDirFingerprint(pkgDirPath: String): String {
        val qPkg = shellQuote(pkgDirPath)
        return Shell.cmd(
            "if [ -d $qPkg ]; then find $qPkg -maxdepth 1 -type f -exec stat -c '%n|%s|%Y' {} + 2>/dev/null | sort | sha1sum | awk '{print \$1}'; fi"
        ).exec().out.firstOrNull().orEmpty().trim()
    }

    private fun savePkgVerifiedFingerprint(workRootPath: String, fingerprint: String) {
        if (fingerprint.isBlank()) return
        Shell.cmd("printf '%s\n' ${shellQuote(fingerprint)} > ${shellQuote("$workRootPath/.pb_pkgs_verified")} 2>/dev/null || true").exec()
    }

    private fun readPkgVerifiedFingerprint(workRootPath: String): String {
        return Shell.cmd("cat ${shellQuote("$workRootPath/.pb_pkgs_verified")} 2>/dev/null || true").exec().out.firstOrNull().orEmpty().trim()
    }

    private fun isStageMarkerValid(workRootPath: String): Boolean {
        val qMarker = shellQuote("$workRootPath/.pb_isodir_ready")
        val qBoot = shellQuote("$workRootPath/ISODIR/sources/boot.wim")
        return Shell.cmd("[ -f $qMarker ] && grep -qx '$STAGE_SCHEMA_VERSION' $qMarker 2>/dev/null && [ -f $qBoot ] && echo OK || true")
            .exec().out.any { it.trim() == "OK" }
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

                val manifest = fetchFullDownloadManifest(config.build.uuid, config.lang.code, config.editions.map { it.code }, archHint = config.build.arch, buildHint = config.build.build)
                if (manifest.allFiles.isEmpty()) throw IllegalStateException("Could not load package list from UUPdump or Microsoft servers.")

                val buildMajor = config.build.build.substringBefore('.').toIntOrNull() ?: 0
                val effectiveAppLevel = if (config.includeStoreApps && config.storeAppLevel == WinStoreAppLevel.SECURITY_ONLY) {
                    WinStoreAppLevel.ALL_INBOX
                } else config.storeAppLevel
                val selectedApps = if (manifest.hasApps && (buildMajor == 0 || buildMajor >= 22563)) {
                    filterStoreApps(manifest.appFiles, config.lang.code, config.build.arch, effectiveAppLevel)
                } else emptyList()

                val targetFiles = when (config.downloadMethod) {
                    WinDownloadMethod.DOWNLOAD_UPDATES_ONLY -> manifest.updateFiles
                    else -> buildList {
                        addAll(manifest.baseFiles)
                        if (config.includeUpdates) addAll(manifest.updateFiles)
                        if (selectedApps.isNotEmpty()) addAll(selectedApps)
                        if (config.integrateNetFx3) addAll(manifest.netFx3Files)
                    }
                }.distinctBy { it.name }
                val hasEffectiveApps = selectedApps.isNotEmpty()
                if (selectedApps.isNotEmpty()) {
                    emitLog("win-dl", "Included ${selectedApps.size} Store/Security app package(s) (${effectiveAppLevel.shortLabel}, ${UsbGadgetEngine.formatBytes(selectedApps.sumOf { it.sizeBytes })})")
                }
                if (targetFiles.isEmpty()) throw IllegalStateException("No downloadable packages matched the selected options.")

                val totalBytes = targetFiles.sumOf { it.sizeBytes }.coerceAtLeast(1L)
                val freeNow = UsbGadgetEngine.getFreeSpaceBytes(config.targetDir)
                val estExtraBuildBytes = if (config.downloadMethod == WinDownloadMethod.DOWNLOAD_AND_BUILD) ((totalBytes * 2L) + (if (config.skipWinRe) 0L else 520L * 1024L * 1024L) + 512L * 1024L * 1024L) else 256L * 1024L * 1024L
                val existingPkgBytes = Shell.cmd("du -sb ${shellQuote(pkgDir.absolutePath)} 2>/dev/null | awk '{print \$1}'").exec().out.firstOrNull()?.trim()?.toLongOrNull() ?: 0L
                val neededFree = ((totalBytes - existingPkgBytes).coerceAtLeast(0L)) + estExtraBuildBytes
                if (freeNow > 0L && freeNow < neededFree) throw IllegalStateException("Not enough free space. Need ~${UsbGadgetEngine.formatBytes(neededFree)}, have ${UsbGadgetEngine.formatBytes(freeNow)} free.")

                saveBuildJobJson(appCtx, workRootPath, config, totalBytes, false)
                savePackageManifest(appCtx, workRootPath, targetFiles)
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
                        val freshMf = fetchFullDownloadManifest(config.build.uuid, config.lang.code, config.editions.map { it.code }, archHint = config.build.arch, buildHint = config.build.build)
                        freshMf.allFiles.associate { it.name to (it.url to it.expireEpochSec) }
                    }
                    val pendingMoves = ConcurrentLinkedQueue<Pair<String, String>>()
                    val moveLock = Any()
                    fun flushMoves(force: Boolean) {
                        val batch = mutableListOf<Pair<String, String>>()
                        synchronized(moveLock) {
                            if (!force && pendingMoves.size < 25) return
                            while (true) {
                                val next = pendingMoves.poll() ?: break
                                batch.add(next)
                            }
                        }
                        if (batch.isEmpty()) return
                        val cmdLines = batch.joinToString("\n") { (src, dst) -> "mv -f ${shellQuote(src)} ${shellQuote(dst)}" }
                        val mvRes = runIsolatedRootScript(appCtx, "mvbatch", "set -e\n$cmdLines")
                        if (!mvRes.isSuccess) throw IllegalStateException("Batch move failed for ${batch.size} package(s)")
                    }
                    FastIsoDownloader.downloadBatchTurbo(batchItems, maxConns, dlPausedFlag, dlCancelledFlag, downloadedCounter, liveThreadsCounter, onRefreshUrls = refreshCallback) { doneItem ->
                        pendingMoves.add(doneItem.partFile.absolutePath to "${pkgDir.absolutePath}/${doneItem.id}")
                        if (doneItem.expectedSize >= 32L * 1024L * 1024L || pendingMoves.size >= 25) {
                            flushMoves(force = true)
                        }
                        filesDoneCounter.incrementAndGet()
                    }
                    flushMoves(force = true)
                } finally { meterJob.cancel() }

                if (dlPausedFlag.get() || dlCancelledFlag.get()) return@launch
                // Gate 0A post-move audit: verify every expected package landed in PKGS/ with exact non-zero size
                val landedMap = Shell.cmd(
                    "for f in ${shellQuote(pkgDir.absolutePath)}/*; do if [ ! -f "\$f" ]; then continue; fi; printf '%s|%s\n' "\$(basename "\$f")" "\$(stat -c '%s' "\$f" 2>/dev/null || echo 0)"; done"
                ).exec().out.mapNotNull { line ->
                    val p = line.split("|", limit = 2)
                    if (p.size == 2) p[0] to (p[1].trim().toLongOrNull() ?: 0L) else null
                }.toMap()
                val missingOrBad = targetFiles.filter { entry ->
                    val sz = landedMap[entry.name] ?: -1L
                    sz <= 0L || (entry.sizeBytes > 0L && sz != entry.sizeBytes)
                }
                if (missingOrBad.isNotEmpty()) {
                    val sample = missingOrBad.take(3).joinToString(", ") { it.name }
                    throw IllegalStateException("Download integrity check failed: ${missingOrBad.size} file(s) missing or truncated ($sample). Tap Resume to re-download.")
                }
                savePkgVerifiedFingerprint(workRootPath, computePkgDirFingerprint(pkgDir.absolutePath))
                runCatching { appStageDir.deleteRecursively() }
                emitLog("win-dl", "Gate 0A passed: downloaded & SHA-1 verified all ${targetFiles.size} packages")

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

                enqueueConversion(
                    QueuedConversionJob(
                        context = appCtx,
                        config = config.copy(autoMount = dlAutoMountLive),
                        workRootPath = workRootPath,
                        workPrefix = workPrefix,
                        jobTitle = jobTitle,
                        totalDownloadBytes = totalBytes,
                        hasUpdates = config.includeUpdates && manifest.hasUpdates,
                        hasApps = hasEffectiveApps,
                        hasNetFx3 = config.integrateNetFx3 && manifest.hasNetFx3,
                        isReadOnlySource = false,
                        pkgDirOverride = "",
                        resumeFromStep4 = false,
                        onComplete = onComplete,
                        onError = onError,
                        onLog = onLog
                    )
                )
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
                val hasUpd = Shell.cmd("find ${shellQuote(resolvedFolder)} -maxdepth 1 -type f \\( -iname '*.msu' -o -iname '*-kb*.cab' -o -iname '*ssu*.cab' -o -iname '*ndp*.cab' -o -iname '*dynamicupdate*.cab' \\) 2>/dev/null | head -n 1").exec().out.any { it.isNotBlank() }
                val hasApps = Shell.cmd("find ${shellQuote(resolvedFolder)} -maxdepth 1 -type f \\( -iname '*.msixbundle' -o -iname '*.appxbundle' -o -iname '*.msix' -o -iname '*.appx' \\) 2>/dev/null | head -n 1").exec().out.any { it.isNotBlank() }
                val hasFx3 = Shell.cmd("find ${shellQuote(resolvedFolder)} -maxdepth 1 -type f -iname '*netfx3*.cab' 2>/dev/null | head -n 1").exec().out.any { it.isNotBlank() }

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
        if (next == null) {
            if (_conversionState.value?.isFailed != true) _conversionState.value = null
            convContext?.let { IsoBuilderService.stop(it) }
            return
        }
        lastFailedJob = null
        convCancelledFlag.set(false)
        convAutoMountLive = next.config.autoMount
        convKeepPackagesLive = next.config.keepPackagesAfterBuild
        convThermalProfileLive = next.config.thermalProfile
        convAutoCooldownLive = next.config.autoThermalCooldown
        convThermalCoolingLive = false
        convContext = next.context
        convOnLog = next.onLog
        IsoBuilderService.start(next.context)
        activeConversionJob = scope.launch {
            try { executeConversionJob(next) } finally {
                if (convCancelledFlag.get()) {
                    lastFailedJob = null
                    _conversionState.value = null
                    IsoBuilderService.stop(next.context)
                } else if (_conversionState.value?.isFailed == true && conversionQueue.isEmpty()) {
                    IsoBuilderService.stop(next.context)
                } else {
                    startNextConversion()
                }
            }
        }
    }

    private fun toRegExpandSzHex(value: String): String {
        val sb = StringBuilder()
        val withNull = value + "\u0000"
        withNull.forEachIndexed { idx, ch ->
            if (idx > 0) sb.append(',')
            val lo = ch.code and 0xFF
            val hi = (ch.code ushr 8) and 0xFF
            sb.append(String.format("%02x,%02x", lo, hi))
        }
        return sb.toString()
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
        var cachedFreeBytes = UsbGadgetEngine.getFreeSpaceBytes(config.targetDir)
        var lastFreeCheckMs = startMs
        var lastNotifMs = 0L
        var lastPublishedStage = ""
        var lastPublishedDetail = ""
        var lastPublishedPct = 0f

        fun addConsole(line: String) { val c = line.replace("\r", "").trim(); if (c.isNotEmpty() && consoleLines.lastOrNull() != c.take(160)) { consoleLines.add(c.take(160)); if (consoleLines.size > 250) consoleLines.removeAt(0) } }
        fun advanceStep(newStep: Int, binName: String) {
            val now = System.currentTimeMillis()
            if (newStep > currentStep) { stepDurations[currentStep] = ((now - stepStartMs) / 1000L).coerceAtLeast(1L); stepStartMs = now; currentStep = newStep }
            activeBin = binName
        }
        fun publishConv(stage: String, detail: String, pct: Float, outBytes: Long = lastMeasuredOutputBytes, forceNotif: Boolean = false) {
            val now = System.currentTimeMillis(); val elapsedSec = ((now - startMs) / 1000L).coerceAtLeast(1L); val dt = (now - lastMeasuredMs).coerceAtLeast(1L)
            if (outBytes > lastMeasuredOutputBytes && dt >= 300L) {
                val inst = ((outBytes - lastMeasuredOutputBytes) * 1000L) / dt
                smoothDiskSpeed = if (smoothDiskSpeed == 0L) inst else ((smoothDiskSpeed * 6 + inst * 4) / 10)
                lastMeasuredOutputBytes = outBytes; lastMeasuredMs = now
            }
            if (now - lastFreeCheckMs >= 3000L) {
                cachedFreeBytes = UsbGadgetEngine.getFreeSpaceBytes(config.targetDir)
                lastFreeCheckMs = now
            }
            val clampedPct = pct.coerceIn(0f, 1f)
            val etaSec = if (clampedPct >= 0.04f && clampedPct < 0.99f) ((elapsedSec.toFloat() / clampedPct) * (1f - clampedPct)).toLong() else -1L
            val (batPct, _) = readBatteryPercent(appCtx)
            val batTemp = readBatteryTempC(appCtx)
            val queued = conversionQueue.toList()
            lastPublishedStage = stage; lastPublishedDetail = detail; lastPublishedPct = clampedPct
            val st = WindowsConversionJobState(
                title = job.jobTitle, outputFileName = finalFileName, outputMode = config.outputMode,
                currentStep = currentStep, stageLabel = stage, detailText = detail, overallProgress = clampedPct,
                diskSpeedBytesPerSec = if (convThermalCoolingLive) 0L else smoothDiskSpeed,
                elapsedSeconds = elapsedSec, etaSeconds = etaSec, currentOutputBytes = outBytes,
                freeSpaceBytes = cachedFreeBytes, activeBinary = activeBin, batteryPct = batPct,
                stepDurationsSec = stepDurations.toMap(), consoleTail = consoleLines.toList(),
                queuedCount = queued.size, queuedTitles = queued.map { it.jobTitle },
                autoMount = convAutoMountLive, workDirPath = workRootPath,
                keepPackagesAfterBuild = convKeepPackagesLive, isReadOnlySource = job.isReadOnlySource,
                isFailed = false, errorMessage = "",
                batteryTempC = batTemp, thermalProfile = convThermalProfileLive,
                autoThermalCooldown = convAutoCooldownLive, isCoolingDown = convThermalCoolingLive
            )
            _conversionState.value = st
            if (forceNotif || now - lastNotifMs >= 2500L) {
                lastNotifMs = now
                NotificationHelper.updateIsoBuilderProgress(appCtx, st)
            }
        }

        val thermalWatchdogJob = scope.launch {
            while (isActive && !convCancelledFlag.get()) {
                delay(1500L)
                val tempC = readBatteryTempC(appCtx)
                val prof = convThermalProfileLive
                val triggerC = prof.cooldownTriggerC
                val resumeC = prof.cooldownResumeC
                if (convAutoCooldownLive && triggerC > 0f && tempC >= triggerC && !convThermalCoolingLive) {
                    convThermalCoolingLive = true
                    signalActivePid("STOP")
                    addConsole("Thermal cooldown: paused at ${String.format(Locale.US, "%.1f", tempC)}°C (resuming at ${String.format(Locale.US, "%.1f", resumeC)}°C)")
                    publishConv(lastPublishedStage.ifBlank { "Cooling down..." }, "Paused at ${String.format(Locale.US, "%.1f", tempC)}°C → waiting for ${String.format(Locale.US, "%.1f", resumeC)}°C", lastPublishedPct, forceNotif = true)
                } else if (convThermalCoolingLive && (!convAutoCooldownLive || triggerC <= 0f || tempC <= resumeC)) {
                    convThermalCoolingLive = false
                    signalActivePid("CONT")
                    addConsole("Thermal cooldown finished (${String.format(Locale.US, "%.1f", tempC)}°C) — build resumed")
                    publishConv(lastPublishedStage.ifBlank { "Resuming build..." }, lastPublishedDetail, lastPublishedPct, forceNotif = true)
                } else if (convThermalCoolingLive) {
                    publishConv(lastPublishedStage.ifBlank { "Cooling down..." }, "Cooling ${String.format(Locale.US, "%.1f", tempC)}°C → ${String.format(Locale.US, "%.1f", resumeC)}°C", lastPublishedPct)
                } else {
                    val cur = _conversionState.value
                    if (cur != null && !cur.isFailed) {
                        val (bp, _) = readBatteryPercent(appCtx)
                        val elapsedSec = ((System.currentTimeMillis() - startMs) / 1000L).coerceAtLeast(1L)
                        _conversionState.value = cur.copy(batteryTempC = tempC, batteryPct = bp, elapsedSeconds = elapsedSec, thermalProfile = prof, autoThermalCooldown = convAutoCooldownLive)
                    }
                }
            }
        }

        try {
            val tools = inspectTools(appCtx)
            val qCab = shellQuote(tools.cabextract!!); val qWim = shellQuote(tools.wimlib!!); val qChntpw = shellQuote(tools.chntpw!!)
            val qPkg = shellQuote(pkgDir.absolutePath); val qIsoDir = shellQuote(isoDir.absolutePath); val qTmp = shellQuote(tmpDir.absolutePath)
            val pathHeader = "PKG=$qPkg\nISODIR=$qIsoDir\nTMP=$qTmp\nexport TMPDIR=\"\$TMP\" HOME=\"\$TMP\" WIMLIB_IMAGEX_IGNORE_CASE=1"

            val isoReadyMarker = "$workRootPath/.pb_isodir_ready"
            val qIsoReadyMarker = shellQuote(isoReadyMarker)
            val canSkipToStep4Pack = job.resumeFromStep4 && isStageMarkerValid(workRootPath) && Shell.cmd(
                "[ -f ${shellQuote("${isoDir.absolutePath}/sources/install.wim")} ] || [ -f ${shellQuote("${isoDir.absolutePath}/sources/install.esd")} ] || [ -f ${shellQuote("${isoDir.absolutePath}/sources/install.swm")} ] && echo OK || true"
            ).exec().out.any { it.trim() == "OK" }

            if (canSkipToStep4Pack) {
                addConsole("Fast-Retry: Reusing completed v20 ISODIR staging folder (skipping Steps 1–3)")
                currentStep = 4
                activeBin = if (config.outputMode == WinBuildOutputMode.DIRECT_NTFS_USB) "mkfs.ntfs" else "xorriso"
                publishConv("Step 4/4 · Fast-Retry from staged ISODIR...", finalFileName, 0.84f)
            } else {
            publishConv("Step 1/4 · Checking build tools & source integrity...", "Gate 0B: verifying source UUP packages", 0.02f)
            if (!runIsolatedRootScript(appCtx, "toolcheck", "set -e\n$pathHeader\nrm -rf $qIsoReadyMarker \"\$ISODIR\" \"\$TMP\" /data/local/tmp/pb_uup_ext /data/local/tmp/pb_uup_hive\nmkdir -p \"\$ISODIR\" \"\$TMP\"\n$qCab --version >/dev/null\n$qWim --version >/dev/null").isSuccess) {
                throw IllegalStateException("Build tool check failed")
            }

            // Gate 0B: Pre-build source package integrity audit (SHA-1 via pb_manifest.json or archive header verification)
            val savedManifest = if (!job.isReadOnlySource) loadPackageManifest(workRootPath) else emptyList()
            if (savedManifest.isNotEmpty()) {
                val curFp = computePkgDirFingerprint(pkgDir.absolutePath)
                val prevFp = readPkgVerifiedFingerprint(workRootPath)
                if (curFp.isNotBlank() && curFp == prevFp) {
                    addConsole("Gate 0B passed: ${savedManifest.size} cached packages match verified SHA-1 fingerprint")
                } else {
                    addConsole("Gate 0B: Verifying SHA-1 hashes of ${savedManifest.size} cached packages...")
                    publishConv("Step 1/4 · Verifying source SHA-1...", "${savedManifest.size} cached packages", 0.025f)
                    val verifiedSet = batchVerifyExistingPackages(pkgDir.absolutePath, savedManifest)
                    val missing = savedManifest.filter { it.name !in verifiedSet }
                    if (missing.isNotEmpty()) {
                        val sample = missing.take(3).joinToString(", ") { it.name }
                        throw IllegalStateException("Gate 0B failed: ${missing.size} cached package(s) missing or failed SHA-1 ($sample). Corrupt file(s) removed — tap Resume to re-download.")
                    }
                    savePkgVerifiedFingerprint(workRootPath, computePkgDirFingerprint(pkgDir.absolutePath))
                    addConsole("Gate 0B passed: all ${savedManifest.size} packages SHA-1 verified")
                }
            } else {
                val q7zOpt = tools.sevenZip?.let { shellQuote(it) }.orEmpty()
                val msu7zCheckCmd = if (q7zOpt.isNotBlank()) "$q7zOpt l \"\$f\" >/dev/null 2>&1" else "true"
                val structAudit = runIsolatedRootScript(appCtx, "gate0b_struct", """
                    set -e
                    $pathHeader
                    ESD_C=0; CAB_C=0; MSU_C=0
                    for f in "${'$'}PKG"/*.esd "${'$'}PKG"/*.ESD; do
                        if [ ! -f "${'$'}f" ]; then continue; fi
                        if [ ! -s "${'$'}f" ]; then echo "BAD|0-byte ESD: ${'$'}(basename "${'$'}f")"; exit 1; fi
                        if ! $qWim info "${'$'}f" >/dev/null 2>&1; then echo "BAD|Corrupt ESD header: ${'$'}(basename "${'$'}f")"; exit 1; fi
                        ESD_C=${'$'}((ESD_C + 1))
                    done
                    for f in "${'$'}PKG"/*.cab "${'$'}PKG"/*.CAB; do
                        if [ ! -f "${'$'}f" ]; then continue; fi
                        if [ ! -s "${'$'}f" ]; then echo "BAD|0-byte CAB: ${'$'}(basename "${'$'}f")"; exit 1; fi
                        if ! $qCab -l "${'$'}f" >/dev/null 2>&1; then echo "BAD|Corrupt CAB archive: ${'$'}(basename "${'$'}f")"; exit 1; fi
                        CAB_C=${'$'}((CAB_C + 1))
                    done
                    for f in "${'$'}PKG"/*.msu "${'$'}PKG"/*.MSU; do
                        if [ ! -f "${'$'}f" ]; then continue; fi
                        if [ ! -s "${'$'}f" ]; then echo "BAD|0-byte MSU: ${'$'}(basename "${'$'}f")"; exit 1; fi
                        if ! $msu7zCheckCmd; then echo "BAD|Corrupt MSU archive: ${'$'}(basename "${'$'}f")"; exit 1; fi
                        MSU_C=${'$'}((MSU_C + 1))
                    done
                    echo "OK|${'$'}ESD_C ESD, ${'$'}CAB_C CAB, ${'$'}MSU_C MSU"
                """.trimIndent())
                if (!structAudit.isSuccess) {
                    val bad = (structAudit.out + structAudit.err).firstOrNull { it.startsWith("BAD|") }?.substringAfter('|')
                        ?: (structAudit.out + structAudit.err).takeLast(2).joinToString(" ")
                    throw IllegalStateException("Gate 0B source integrity failed: ${bad.take(220)}")
                }
                val summary = structAudit.out.firstOrNull { it.startsWith("OK|") }?.substringAfter('|') ?: "source archives"
                addConsole("Gate 0B passed: $summary verified (0 corrupt)")
            }

            advanceStep(1, "cabextract")
            val allCabPaths = runIsolatedRootScript(appCtx, "listcabs", "$pathHeader\nfor c in \"\$PKG\"/*.cab \"\$PKG\"/*.CAB; do if [ -f \"\$c\" ]; then echo \"\$c\"; fi; done").out.map { it.trim() }.filter { it.startsWith("/") && it.lowercase().endsWith(".cab") }
            val editionCabs = allCabPaths.filter { !isSkippableNonEditionCab(it.substringAfterLast('/')) }
            addConsole("Step 1/4: ${editionCabs.size} edition CAB(s) (${allCabPaths.size - editionCabs.size} non-edition skipped) · Thermal: ${convThermalProfileLive.label}")
            if (editionCabs.isNotEmpty()) {
                val cabProgLog = "/data/local/tmp/pb_cab_prog.log"
                val qCabProg = shellQuote(cabProgLog)
                val cabListFile = File(appCtx.cacheDir, "pb_cab_list.txt").apply {
                    writeText(editionCabs.joinToString("\n"), Charsets.UTF_8)
                }
                val qCabList = shellQuote(cabListFile.absolutePath)
                val cabCacheDir = if (!job.isReadOnlySource) "$workRootPath/CAB_CACHE" else ""
                val qCabCache = shellQuote(cabCacheDir)
                val thermSetup1 = thermalExecPrefix(convThermalProfileLive)
                val cabPollJob = scope.launch {
                    var lastCount = 0
                    while (isActive && !convCancelledFlag.get()) {
                        delay(900L)
                        val lines = Shell.cmd("tail -n 6 $qCabProg 2>/dev/null || true").exec().out.map { it.trim() }.filter { it.startsWith("CAB_DONE|") }
                        val last = lines.lastOrNull() ?: continue
                        val parts = last.split('|')
                        val idxDone = parts.getOrNull(1)?.toIntOrNull() ?: lastCount
                        val nameDone = parts.getOrNull(3).orEmpty()
                        if (idxDone > lastCount) {
                            lastCount = idxDone
                            if (nameDone.isNotBlank()) addConsole("Converted $nameDone")
                            publishConv(
                                "Step 1/4 · Converting CAB $idxDone/${editionCabs.size}...",
                                nameDone,
                                0.03f + (idxDone.toFloat() / editionCabs.size.coerceAtLeast(1).toFloat()) * 0.14f
                            )
                        }
                    }
                }
                val batchCabScript = """
                    set -e
                    $pathHeader
                    $thermSetup1
                    : > $qCabProg
                    trap 'rm -f $ACTIVE_PID_FILE' EXIT
                    EXTDIR="/data/local/tmp/pb_uup_ext"
                    CABCACHE=$qCabCache
                    if [ -n "${'$'}CABCACHE" ]; then mkdir -p "${'$'}CABCACHE"; fi
                    TOTAL=${editionCabs.size}
                    IDX=0
                    while IFS= read -r CABPATH || [ -n "${'$'}CABPATH" ]; do
                        if [ ! -f "${'$'}CABPATH" ]; then continue; fi
                        IDX=${'$'}((IDX + 1))
                        BN=${'$'}(basename "${'$'}CABPATH")
                        CAB_SZ=${'$'}(stat -c '%s' "${'$'}CABPATH" 2>/dev/null || echo 0)
                        if [ -n "${'$'}CABCACHE" ]; then
                            CACHED_ESD="${'$'}CABCACHE/${'$'}BN.${'$'}CAB_SZ.esd"
                            if [ -s "${'$'}CACHED_ESD" ] && $qWim info "${'$'}CACHED_ESD" >/dev/null 2>&1; then
                                (ln -f "${'$'}CACHED_ESD" "${'$'}TMP/${'$'}BN.esd" 2>/dev/null || cp -f "${'$'}CACHED_ESD" "${'$'}TMP/${'$'}BN.esd")
                                echo "CAB_DONE|${'$'}IDX|${'$'}TOTAL|${'$'}BN (cached)" >> $qCabProg
                                continue
                            fi
                        fi
                        if ! $qCab -l "${'$'}CABPATH" 2>/dev/null | grep -qi 'update\.mum'; then
                            echo "CAB_DONE|${'$'}IDX|${'$'}TOTAL|${'$'}BN (skipped)" >> $qCabProg
                            continue
                        fi
                        rm -rf "${'$'}EXTDIR" && mkdir -p "${'$'}EXTDIR"
                        set +e
                        ${'$'}THERM_PFX $qCab -q -k -d "${'$'}EXTDIR" "${'$'}CABPATH" &
                        CPID=${'$'}!; echo "${'$'}CPID" > $ACTIVE_PID_FILE; wait "${'$'}CPID"
                        set -e
                        if [ -z "${'$'}(ls -A "${'$'}EXTDIR" 2>/dev/null)" ]; then
                            rm -rf "${'$'}EXTDIR"
                            echo "CAB_DONE|${'$'}IDX|${'$'}TOTAL|${'$'}BN" >> $qCabProg
                            continue
                        fi
                        rm -f "${'$'}TMP/${'$'}BN.tmp.esd"
                        ${'$'}THERM_PFX $qWim capture "${'$'}EXTDIR" "${'$'}TMP/${'$'}BN.tmp.esd" "Edition Package" "Edition Package" --compress=none --no-acls --norpfix >/dev/null 2>&1 &
                        WPID=${'$'}!; echo "${'$'}WPID" > $ACTIVE_PID_FILE; wait "${'$'}WPID"
                        mv -f "${'$'}TMP/${'$'}BN.tmp.esd" "${'$'}TMP/${'$'}BN.esd"
                        if [ -n "${'$'}CABCACHE" ]; then
                            (ln -f "${'$'}TMP/${'$'}BN.esd" "${'$'}CABCACHE/${'$'}BN.${'$'}CAB_SZ.esd" 2>/dev/null || cp -f "${'$'}TMP/${'$'}BN.esd" "${'$'}CABCACHE/${'$'}BN.${'$'}CAB_SZ.esd" 2>/dev/null || true)
                        fi
                        rm -rf "${'$'}EXTDIR"
                        echo "CAB_DONE|${'$'}IDX|${'$'}TOTAL|${'$'}BN" >> $qCabProg
                    done < $qCabList
                    rm -f $ACTIVE_PID_FILE
                """.trimIndent()
                val batchCabRes = try {
                    runIsolatedRootScript(appCtx, "cabs_batch", batchCabScript)
                } finally {
                    cabPollJob.cancel()
                    cabListFile.delete()
                    Shell.cmd("rm -f $qCabProg $ACTIVE_PID_FILE 2>/dev/null || true").exec()
                }
                if (!batchCabRes.isSuccess) throw IllegalStateException("CAB batch convert failed: ${(batchCabRes.out + batchCabRes.err).takeLast(3).joinToString(" ").take(200)}")
                addConsole("Step 1/4 complete: converted ${editionCabs.size} edition CAB(s)")
            }

            advanceStep(2, "wimlib-imagex")
            publishConv("Step 2/4 · Building boot.wim & WinRE...", "Applying WinPE & patching SOFTWARE hive", 0.20f)
            val metaList = runIsolatedRootScript(appCtx, "meta", "$pathHeader\nfor f in \"\$PKG\"/*.esd \"\$PKG\"/*.ESD; do [ -f \"\$f\" ] || continue; cnt=\$($qWim info \"\$f\" 2>/dev/null | grep -c '^Index:' || true); [ \"\$cnt\" -ge 3 ] && echo \"\$f\"; done").out.map { it.trim() }.filter { it.startsWith("/") && it.lowercase().endsWith(".esd") }
            if (metaList.isEmpty()) throw IllegalStateException("No Windows edition metadata ESD (3+ indexes) found.")
            val firstMeta = shellQuote(metaList.first())

            val boot1RegFile = File(appCtx.cacheDir, "pb_boot1_soft.reg").apply {
                val bgWinreHex = toRegExpandSzHex("%SystemRoot%\\System32\\winre.jpg")
                writeText(
                    "REGEDIT4\r\n\r\n" +
                        "[HKEY_LOCAL_MACHINE\\SOFTWARE\\Microsoft\\Windows NT\\CurrentVersion]\r\n" +
                        "\"SystemRoot\"=\"X:\\\\${'$'}Windows.~bt\\\\Windows\"\r\n\r\n" +
                        "[HKEY_LOCAL_MACHINE\\SOFTWARE\\Microsoft\\Windows NT\\CurrentVersion\\WinPE]\r\n" +
                        "\"InstRoot\"=\"X:\\\\${'$'}Windows.~bt\"\r\n" +
                        "\"CustomBackground\"=hex(2):$bgWinreHex\r\n" +
                        "\"CustomShell\"=-\r\n\r\n",
                    Charsets.US_ASCII
                )
            }
            val boot2RegFile = File(appCtx.cacheDir, "pb_boot2_soft.reg").apply {
                val bgSetupHex = toRegExpandSzHex("%SystemRoot%\\System32\\setup.bmp")
                writeText(
                    "REGEDIT4\r\n\r\n" +
                        "[HKEY_LOCAL_MACHINE\\SOFTWARE\\Microsoft\\Windows NT\\CurrentVersion\\WinPE]\r\n" +
                        "\"CustomBackground\"=hex(2):$bgSetupHex\r\n" +
                        "\"CustomShell\"=-\r\n\r\n",
                    Charsets.US_ASCII
                )
            }
            val qBoot1Reg = shellQuote(boot1RegFile.absolutePath)
            val qBoot2Reg = shellQuote(boot2RegFile.absolutePath)
            val archTag = config.build.archDisplay.lowercase()
            val bldMajorForFonts = config.build.build.substringBefore('.').toIntOrNull() ?: 0
            val wantBootFonts = bldMajorForFonts == 0 || bldMajorForFonts in 18890..27499

            val bootWimRes = try {
                runIsolatedRootScript(appCtx, "bootwim", """
                    set -e
                    $pathHeader
                    $qWim apply $firstMeta 1 "${'$'}ISODIR" --no-acls --no-attributes
                    rm -f "${'$'}ISODIR/MediaMeta.xml" "${'$'}ISODIR/mediameta.xml" 2>/dev/null || true
                    mkdir -p "${'$'}ISODIR/sources"
                    if [ "${if (job.hasUpdates) "1" else "0"}" = "1" ]; then
                        DU_TMP="/data/local/tmp/pb_step2_du"
                        rm -rf "${'$'}DU_TMP"
                        for f in "${'$'}PKG"/*.cab "${'$'}PKG"/*.CAB; do
                            if [ ! -f "${'$'}f" ]; then continue; fi
                            bn=${'$'}(basename "${'$'}f")
                            low=${'$'}(printf '%s' "${'$'}bn" | tr 'A-Z' 'a-z')
                            case "${'$'}low" in
                                *-kb*.cab|ssu-*.cab|*-ssu-*.cab|*ndp*.cab|*dynamicupdate*.cab) ;;
                                *) continue ;;
                            esac
                            case "${'$'}low" in
                                *desktopdeployment*|*aggregatedmetadata*|*baseless*|*-express.cab|*netfx3-ondemand*|*safeos*) continue ;;
                            esac
                            kb_num=${'$'}(printf '%s' "${'$'}bn" | grep -oiE 'KB[0-9]{6,8}' | head -n 1 || true)
                            if [ -n "${'$'}kb_num" ] && find "${'$'}PKG" -maxdepth 1 -type f -iname "*${'$'}kb_num*.msu" 2>/dev/null | grep -q .; then
                                continue
                            fi
                            if $qCab -l "${'$'}f" 2>/dev/null | grep -qi 'update\.mum'; then
                                continue
                            fi
                            rm -rf "${'$'}DU_TMP" && mkdir -p "${'$'}DU_TMP"
                            set +e
                            $qCab -q -k -d "${'$'}DU_TMP" "${'$'}f" >/dev/null 2>&1
                            set -e
                            if [ -n "${'$'}(ls -A "${'$'}DU_TMP" 2>/dev/null)" ]; then
                                (
                                    set -f
                                    cd "${'$'}DU_TMP"
                                    find . -type f | while IFS= read -r rel; do
                                        rel="${'$'}{rel#./}"
                                        cur="${'$'}ISODIR/sources"
                                        dirpart="${'$'}{rel%/*}"
                                        basepart="${'$'}{rel##*/}"
                                        if [ "${'$'}dirpart" != "${'$'}rel" ]; then
                                            first_seg="${'$'}{dirpart%%/*}"
                                            low_seg=${'$'}(printf '%s' "${'$'}first_seg" | tr 'A-Z' 'a-z')
                                            if printf '%s' "${'$'}low_seg" | grep -qE '^[a-z]{2,3}(-[a-z0-9]{2,4}){1,2}$'; then
                                                if ! find "${'$'}ISODIR/sources" -maxdepth 1 -mindepth 1 -type d -iname "${'$'}low_seg" 2>/dev/null | grep -q .; then
                                                    continue
                                                fi
                                            fi
                                            OLDIFS="${'$'}IFS"; IFS="/"
                                            for seg in ${'$'}dirpart; do
                                                if [ -z "${'$'}seg" ]; then continue; fi
                                                match=${'$'}(find "${'$'}cur" -maxdepth 1 -mindepth 1 -type d -iname "${'$'}seg" 2>/dev/null | head -n 1)
                                                if [ -n "${'$'}match" ]; then cur="${'$'}match"; else cur="${'$'}cur/${'$'}seg"; mkdir -p "${'$'}cur"; fi
                                            done
                                            IFS="${'$'}OLDIFS"
                                        fi
                                        existing=${'$'}(find "${'$'}cur" -maxdepth 1 -mindepth 1 -type f -iname "${'$'}basepart" 2>/dev/null | head -n 1)
                                        if [ -n "${'$'}existing" ]; then
                                            cp -f "${'$'}DU_TMP/${'$'}rel" "${'$'}existing"
                                        else
                                            cp -f "${'$'}DU_TMP/${'$'}rel" "${'$'}cur/${'$'}basepart"
                                        fi
                                    done
                                )
                                echo "DU_LOG|Applied Setup DU to sources/ (synced into boot.wim): ${'$'}bn"
                            fi
                            rm -rf "${'$'}DU_TMP"
                        done
                    fi
                    if [ "${if (wantBootFonts) "1" else "0"}" = "1" ]; then
                        mkdir -p "${'$'}ISODIR/boot/fonts" "${'$'}ISODIR/efi/microsoft/boot/fonts"
                        $qWim extract $firstMeta 3 "/Windows/Boot/Fonts" --dest-dir="${'$'}TMP/bootfonts" --no-acls --no-attributes >/dev/null 2>&1 || true
                        if [ -d "${'$'}TMP/bootfonts/Fonts" ]; then
                            cp -f "${'$'}TMP/bootfonts/Fonts"/* "${'$'}ISODIR/boot/fonts/" 2>/dev/null || true
                            cp -f "${'$'}TMP/bootfonts/Fonts"/* "${'$'}ISODIR/efi/microsoft/boot/fonts/" 2>/dev/null || true
                        elif [ -d "${'$'}TMP/bootfonts" ]; then
                            cp -f "${'$'}TMP/bootfonts"/* "${'$'}ISODIR/boot/fonts/" 2>/dev/null || true
                            cp -f "${'$'}TMP/bootfonts"/* "${'$'}ISODIR/efi/microsoft/boot/fonts/" 2>/dev/null || true
                        fi
                        rm -rf "${'$'}TMP/bootfonts"
                    fi

                    ${thermalExecPrefix(convThermalProfileLive)}
                    trap 'rm -f $ACTIVE_PID_FILE' EXIT
                    ${'$'}THERM_PFX $qWim export $firstMeta 2 "${'$'}TMP/winre.wim" --compress=maximum ${wimThreadArgFor(convThermalProfileLive, false)} --boot &
                    WPID=${'$'}!; echo "${'$'}WPID" > $ACTIVE_PID_FILE; wait "${'$'}WPID"
                    rm -f $ACTIVE_PID_FILE
                    cp -f "${'$'}TMP/winre.wim" "${'$'}ISODIR/sources/boot.wim"
                    $qWim info "${'$'}ISODIR/sources/boot.wim" 1 "Microsoft Windows PE ($archTag)" "Microsoft Windows PE ($archTag)"
                    $qWim info "${'$'}ISODIR/sources/boot.wim" 1 --image-property FLAGS=9

                    HIVEDIR="/data/local/tmp/pb_uup_hive"
                    rm -rf "${'$'}HIVEDIR" && mkdir -p "${'$'}HIVEDIR"
                    $qWim extract "${'$'}ISODIR/sources/boot.wim" 1 "/Windows/System32/config/SOFTWARE" --dest-dir="${'$'}HIVEDIR" --no-acls --no-attributes >/dev/null 2>&1 || \
                      $qWim extract "${'$'}ISODIR/sources/boot.wim" 1 "/windows/system32/config/software" --dest-dir="${'$'}HIVEDIR" --no-acls --no-attributes >/dev/null 2>&1 || true
                    HIVE_FILE=${'$'}(find "${'$'}HIVEDIR" -maxdepth 1 -type f -iname 'software' | head -n 1)
                    if [ -n "${'$'}HIVE_FILE" ]; then
                        set +e
                        printf 'y\n' | $qChntpw -C -I "${'$'}HIVE_FILE" 'HKEY_LOCAL_MACHINE\SOFTWARE' $qBoot1Reg >/dev/null 2>&1
                        printf 'cd Microsoft\\Windows NT\\CurrentVersion\\WinPE\ndv CustomShell\nq\ny\n' | $qChntpw -C -e "${'$'}HIVE_FILE" >/dev/null 2>&1
                        set -e
                        $qWim update "${'$'}ISODIR/sources/boot.wim" 1 --command="add '${'$'}HIVE_FILE' /Windows/System32/config/SOFTWARE"
                    fi
                    rm -rf "${'$'}HIVEDIR"

                    BG_FILE=""
                    for bg in "${'$'}ISODIR/sources/background_cli.bmp" "${'$'}ISODIR/sources/background_svr.bmp" "${'$'}ISODIR/sources/background_cli.png" "${'$'}ISODIR/sources/background_svr.png"; do
                        if [ -f "${'$'}bg" ]; then BG_FILE="${'$'}bg"; break; fi
                    done
                    if [ -n "${'$'}BG_FILE" ]; then
                        $qWim update "${'$'}ISODIR/sources/boot.wim" 1 --command="add '${'$'}BG_FILE' /Windows/system32/winre.jpg" || true
                        $qWim update "${'$'}ISODIR/sources/boot.wim" 1 --command="add '${'$'}BG_FILE' /Windows/system32/winpe.jpg" || true
                    fi
                    $qWim update "${'$'}ISODIR/sources/boot.wim" 1 --command="delete --force /Windows/System32/winpeshl.ini" || true

                    $qWim export "${'$'}TMP/winre.wim" 1 "${'$'}ISODIR/sources/boot.wim" "Microsoft Windows Setup ($archTag)" "Microsoft Windows Setup ($archTag)"
                    $qWim info "${'$'}ISODIR/sources/boot.wim" 2 --image-property FLAGS=2
                    $qWim info "${'$'}ISODIR/sources/boot.wim" 2 --boot

                    rm -rf "${'$'}HIVEDIR" && mkdir -p "${'$'}HIVEDIR"
                    $qWim extract "${'$'}ISODIR/sources/boot.wim" 2 "/Windows/System32/config/SOFTWARE" --dest-dir="${'$'}HIVEDIR" --no-acls --no-attributes >/dev/null 2>&1 || \
                      $qWim extract "${'$'}ISODIR/sources/boot.wim" 2 "/windows/system32/config/software" --dest-dir="${'$'}HIVEDIR" --no-acls --no-attributes >/dev/null 2>&1 || true
                    HIVE2=${'$'}(find "${'$'}HIVEDIR" -maxdepth 1 -type f -iname 'software' | head -n 1)
                    if [ -n "${'$'}HIVE2" ]; then
                        set +e
                        printf 'y\n' | $qChntpw -C -I "${'$'}HIVE2" 'HKEY_LOCAL_MACHINE\SOFTWARE' $qBoot2Reg >/dev/null 2>&1
                        printf 'cd Microsoft\\Windows NT\\CurrentVersion\\WinPE\ndv CustomShell\nq\ny\n' | $qChntpw -C -e "${'$'}HIVE2" >/dev/null 2>&1
                        set -e
                    fi

                    XML_TMP=""
                    if ! find "${'$'}ISODIR/sources" -maxdepth 1 -type f -iname 'xmllite.dll' 2>/dev/null | grep -q .; then
                        $qWim extract $firstMeta 3 "/Windows/System32/xmllite.dll" --dest-dir="${'$'}TMP" --no-acls --no-attributes >/dev/null 2>&1 || true
                        XML_TMP=${'$'}(find "${'$'}TMP" -maxdepth 1 -type f -iname 'xmllite.dll' 2>/dev/null | head -n 1)
                    fi
                    UPD_TXT="/data/local/tmp/pb_boot_update.txt"; : > "${'$'}UPD_TXT"
                    echo "delete --force /Windows/System32/winpeshl.ini" >> "${'$'}UPD_TXT"
                    if [ -n "${'$'}HIVE2" ]; then echo "add '${'$'}HIVE2' /Windows/System32/config/SOFTWARE" >> "${'$'}UPD_TXT"; fi
                    if [ -f "${'$'}ISODIR/setup.exe" ]; then echo "add '${'$'}ISODIR/setup.exe' /setup.exe" >> "${'$'}UPD_TXT"; fi
                    if [ -f "${'$'}ISODIR/sources/inf/setup.cfg" ]; then echo "add '${'$'}ISODIR/sources/inf/setup.cfg' /sources/inf/setup.cfg" >> "${'$'}UPD_TXT"; fi
                    if [ -n "${'$'}XML_TMP" ]; then echo "add '${'$'}XML_TMP' /sources/xmllite.dll" >> "${'$'}UPD_TXT"; fi
                    if [ -n "${'$'}BG_FILE" ]; then
                        echo "add '${'$'}BG_FILE' /sources/background.bmp" >> "${'$'}UPD_TXT"
                        echo "add '${'$'}BG_FILE' /Windows/system32/setup.bmp" >> "${'$'}UPD_TXT"
                        echo "add '${'$'}BG_FILE' /Windows/system32/winpe.jpg" >> "${'$'}UPD_TXT"
                        echo "add '${'$'}BG_FILE' /Windows/system32/winre.jpg" >> "${'$'}UPD_TXT"
                    fi
                    for sf in "${'$'}ISODIR"/sources/*; do
                        if [ ! -e "${'$'}sf" ]; then continue; fi; sbn=${'$'}(basename "${'$'}sf")
                        case "${'$'}sbn" in boot.wim|BOOT.WIM|install.wim|INSTALL.WIM|install.esd|INSTALL.ESD|install*.swm|INSTALL*.SWM|sxs|SXS|uup|UUP|ei.cfg|EI.CFG|pid.txt|PID.TXT|'${'$'}OEM${'$'}') continue ;; esac
                        echo "add '${'$'}sf' '/sources/${'$'}sbn'" >> "${'$'}UPD_TXT"
                    done
                    $qWim update "${'$'}ISODIR/sources/boot.wim" 2 < "${'$'}UPD_TXT"
                    $qWim optimize "${'$'}ISODIR/sources/boot.wim"
                    rm -rf "${'$'}HIVEDIR" "${'$'}XML_TMP" "${'$'}UPD_TXT"
                """.trimIndent())
            } finally {
                boot1RegFile.delete()
                boot2RegFile.delete()
            }
            if (!bootWimRes.isSuccess) throw IllegalStateException("boot.wim assembly failed: ${(bootWimRes.out + bootWimRes.err).takeLast(4).joinToString(" ").take(240)}")
            bootWimRes.out.filter { it.startsWith("DU_LOG|") }.forEach { addConsole(it.substringAfter('|')) }
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
            val baseExportArg = if (useTwoStageSolidEsd) "--compress=fast" else effectiveComp.wimlibArg

            metaList.forEachIndexed { idx, metaPath ->
                if (convCancelledFlag.get()) return
                val spanTotal = if (useTwoStageSolidEsd) 0.30f else 0.48f
                val sliceBase = 0.28f + (idx.toFloat() / metaList.size.toFloat()) * spanTotal
                val sliceSpan = spanTotal / metaList.size.toFloat()
                val curProf = convThermalProfileLive
                val threadArg = wimThreadArgFor(curProf, isSolidEsd = !useTwoStageSolidEsd && effectiveComp == WinIsoCompression.ESD)
                val thermSetup = thermalExecPrefix(curProf)
                addConsole("Step 3/4: Exporting edition ${idx + 1}/${metaList.size} (${effectiveComp.shortLabel} · ${curProf.label}${if (threadArg.isNotBlank()) " $threadArg" else ""})")
                publishConv("Step 3/4 · Exporting edition ${idx + 1}/${metaList.size}...", "${effectiveComp.label} · 0%", sliceBase, forceNotif = true)
                val wimPollJob = scope.launch {
                    val pctRegex = Regex("""(\d{1,3})%""")
                    var lastSeenPct = 0
                    while (isActive && !convCancelledFlag.get()) {
                        delay(1200L)
                        if (convThermalCoolingLive) continue
                        val pollOut = Shell.cmd("stat -c 'SZ|%s' $qStageWim 2>/dev/null || echo 'SZ|0'; tail -c 512 $qProgLog 2>/dev/null | tr '\r' '\n' | tail -n 5 || true").exec().out
                        val curSz = pollOut.firstOrNull { it.startsWith("SZ|") }?.substringAfter('|')?.trim()?.toLongOrNull() ?: lastMeasuredOutputBytes
                        val cleanLines = pollOut.filterNot { it.startsWith("SZ|") }.map { it.trim() }.filter { it.isNotEmpty() }
                        val foundPct = pctRegex.findAll(cleanLines.joinToString(" ")).lastOrNull()?.groupValues?.get(1)?.toIntOrNull()
                        if (foundPct != null && foundPct in 0..100) lastSeenPct = foundPct
                        cleanLines.lastOrNull()?.let { addConsole(it) }
                        publishConv(
                            "Step 3/4 · Exporting edition ${idx + 1}/${metaList.size}...",
                            "${effectiveComp.label} · $lastSeenPct%",
                            sliceBase + (lastSeenPct / 100f) * sliceSpan,
                            curSz
                        )
                    }
                }
                val qMeta = shellQuote(metaPath)
                val expRes = runIsolatedRootScript(appCtx, "wimexp", """
                    set -e
                    $pathHeader
                    $thermSetup
                    : > $qProgLog
                    trap 'rm -f $ACTIVE_PID_FILE' EXIT
                    INFO_TXT=${'$'}($qWim info $qMeta 3 2>/dev/null || true)
                    ED_NAME=${'$'}(printf '%s\n' "${'$'}INFO_TXT" | sed -n 's/^Display Name:[[:space:]]*//p; s/^Name:[[:space:]]*//p' | head -n 1)
                    ED_DESC=${'$'}(printf '%s\n' "${'$'}INFO_TXT" | sed -n 's/^Display Description:[[:space:]]*//p; s/^Description:[[:space:]]*//p' | head -n 1)
                    ED_ID=${'$'}(printf '%s\n' "${'$'}INFO_TXT" | sed -n 's/^Edition ID:[[:space:]]*//p' | head -n 1)
                    if [ -z "${'$'}ED_NAME" ]; then ED_NAME="Windows ${config.build.build}"; fi
                    if [ -z "${'$'}ED_DESC" ]; then ED_DESC="${'$'}ED_NAME"; fi
                    set --
                    for ref in "${'$'}PKG"/*.esd "${'$'}PKG"/*.ESD "${'$'}TMP"/*.esd; do
                        if [ -f "${'$'}ref" ]; then set -- "${'$'}@" --ref="${'$'}ref"; fi
                    done
                    ${'$'}THERM_PFX $qWim export $qMeta 3 $qStageWim "${'$'}ED_NAME" "${'$'}ED_DESC" $baseExportArg $threadArg "${'$'}@" > $qProgLog 2>&1 &
                    WPID=${'$'}!; echo "${'$'}WPID" > $ACTIVE_PID_FILE; wait "${'$'}WPID"
                    rm -f $ACTIVE_PID_FILE
                    NEW_IDX=${'$'}($qWim info $qStageWim 2>/dev/null | grep -c '^Index:' || echo 1)
                    if [ -n "${'$'}ED_ID" ]; then
                        $qWim info $qStageWim "${'$'}NEW_IDX" \
                            --image-property DISPLAYNAME="${'$'}ED_NAME" \
                            --image-property DISPLAYDESCRIPTION="${'$'}ED_DESC" \
                            --image-property FLAGS="${'$'}ED_ID" >/dev/null 2>&1 || true
                    fi
                """.trimIndent())
                wimPollJob.cancel()
                if (!expRes.isSuccess) {
                    val progErr = Shell.cmd("tail -n 6 $qProgLog 2>/dev/null || true").exec().out.filter { it.isNotBlank() }.joinToString(" | ")
                    val err = progErr.ifBlank { (expRes.out + expRes.err).filter { it.isNotBlank() }.takeLast(4).joinToString(" | ") }.take(260)
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
                val esdProf = convThermalProfileLive
                val esdThreadArg = wimThreadArgFor(esdProf, isSolidEsd = true)
                val esdTherm = thermalExecPrefix(esdProf)
                addConsole("Step 3/4: Solid ESD compression (${esdProf.label}${if (esdThreadArg.isNotBlank()) " $esdThreadArg" else ""})")
                publishConv("Step 3/4 · Compressing to Solid ESD (LZMS)...", "0%", 0.66f, forceNotif = true)
                val esdPoll = scope.launch {
                    val pctRegex = Regex("""(\d{1,3})%""")
                    var lastSeenPct = 0
                    while (isActive && !convCancelledFlag.get()) {
                        delay(1200L)
                        if (convThermalCoolingLive) continue
                        val pollOut = Shell.cmd("stat -c 'SZ|%s' $qFinalInstall 2>/dev/null || echo 'SZ|0'; tail -c 512 $qProgLog 2>/dev/null | tr '\r' '\n' | tail -n 5 || true").exec().out
                        val curSz = pollOut.firstOrNull { it.startsWith("SZ|") }?.substringAfter('|')?.trim()?.toLongOrNull() ?: lastMeasuredOutputBytes
                        val cleanLines = pollOut.filterNot { it.startsWith("SZ|") }.map { it.trim() }.filter { it.isNotEmpty() }
                        val foundPct = pctRegex.findAll(cleanLines.joinToString(" ")).lastOrNull()?.groupValues?.get(1)?.toIntOrNull()
                        if (foundPct != null && foundPct in 0..100) lastSeenPct = foundPct
                        cleanLines.lastOrNull()?.let { addConsole(it) }
                        publishConv("Step 3/4 · Compressing to Solid ESD (LZMS)...", "$lastSeenPct%", 0.66f + (lastSeenPct / 100f) * 0.14f, curSz)
                    }
                }
                val esdRes = runIsolatedRootScript(appCtx, "esdconv", "set -e\n$pathHeader\n$esdTherm\n: > $qProgLog\ntrap 'rm -f $ACTIVE_PID_FILE' EXIT\n\$THERM_PFX $qWim export $qStageWim all $qFinalInstall --compress=LZMS --solid $esdThreadArg > $qProgLog 2>&1 &\nEPID=\$!; echo \"\$EPID\" > $ACTIVE_PID_FILE; wait \"\$EPID\"\nrm -f $qStageWim $qProgLog $ACTIVE_PID_FILE")
                esdPoll.cancel()
                if (!esdRes.isSuccess) {
                    val esdProgErr = Shell.cmd("tail -n 6 $qProgLog 2>/dev/null || true").exec().out.filter { it.isNotBlank() }.joinToString(" | ")
                    val esdDetail = esdProgErr.ifBlank { (esdRes.out + esdRes.err).filter { it.isNotBlank() }.takeLast(4).joinToString(" | ") }.take(260)
                    Shell.cmd("rm -f $qProgLog 2>/dev/null || true").exec()
                    throw IllegalStateException("Solid ESD compression failed: ${esdDetail.ifBlank { "code ${esdRes.code}" }}")
                }
                addConsole("Converted install.wim + Winre.wim -> Solid install.esd")
            } else if (effectiveComp != WinIsoCompression.ESD && (!config.skipWinRe || requestedVirtualEds.isNotEmpty())) {
                addConsole("Optimizing install.wim (deduplicating & finalizing WIM header)...")
                publishConv("Step 3/4 · Optimizing install.wim...", "Finalizing WIM integrity & size", 0.77f)
                runIsolatedRootScript(appCtx, "wimopt", "$pathHeader\n${thermalExecPrefix(convThermalProfileLive)}\n\$THERM_PFX $qWim optimize $qFinalInstall >/dev/null 2>&1 || true")
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
                addConsole("Classifying updates: Setup DU -> sources/, OS Updates -> 1_SSU / 2_UPD / 3_LCU")
                val updRes = runIsolatedRootScript(appCtx, "oemupd", """
                    set -e
                    $pathHeader
                    mkdir -p ${shellQuote(oemUpdates)}
                    MUM_TMP="/data/local/tmp/pb_upd_mum"
                    DU_TMP="/data/local/tmp/pb_upd_du"
                    rm -rf "${'$'}MUM_TMP" "${'$'}DU_TMP"

                    # 1. Stage unified .msu cumulative updates as 3_<name>
                    for f in "${'$'}PKG"/*.msu "${'$'}PKG"/*.MSU; do
                        if [ ! -f "${'$'}f" ]; then continue; fi
                        bn=${'$'}(basename "${'$'}f")
                        (ln -f "${'$'}f" ${shellQuote(oemUpdates)}/"3_${'$'}bn" 2>/dev/null || cp -f "${'$'}f" ${shellQuote(oemUpdates)}/"3_${'$'}bn")
                        echo "UPD_LOG|LCU MSU -> 3_${'$'}bn"
                    done

                    # 2. Classify & stage .cab updates (Setup DU already synced in Step 2; skip SafeOS/WinPE-only & MSU-duplicated KBs)
                    for f in "${'$'}PKG"/*.cab "${'$'}PKG"/*.CAB; do
                        if [ ! -f "${'$'}f" ]; then continue; fi
                        bn=${'$'}(basename "${'$'}f")
                        low=${'$'}(printf '%s' "${'$'}bn" | tr 'A-Z' 'a-z')
                        case "${'$'}low" in
                            *-kb*.cab|ssu-*.cab|*-ssu-*.cab|*ndp*.cab|*dynamicupdate*.cab) ;;
                            *) continue ;;
                        esac
                        case "${'$'}low" in
                            *desktopdeployment*|*aggregatedmetadata*|*baseless*|*-express.cab|*netfx3-ondemand*|*safeos*) continue ;;
                        esac
                        # Skip CAB if a combined .msu with the same KB number already exists
                        kb_num=${'$'}(printf '%s' "${'$'}bn" | grep -oiE 'KB[0-9]{6,8}' | head -n 1 || true)
                        if [ -n "${'$'}kb_num" ] && find "${'$'}PKG" -maxdepth 1 -type f -iname "*${'$'}kb_num*.msu" 2>/dev/null | grep -q .; then
                            echo "UPD_LOG|Skipped duplicate CAB (superseded by MSU): ${'$'}bn"
                            continue
                        fi

                        # Setup Dynamic Updates (no update.mum) were already merged into ISODIR/sources/ + boot.wim in Step 2
                        if ! $qCab -l "${'$'}f" 2>/dev/null | grep -qi 'update\.mum'; then
                            echo "UPD_LOG|Setup DU already synced into boot.wim & sources/: ${'$'}bn"
                            continue
                        fi

                        # Extract update.mum to skip SafeOS/WinPE-only CABs and detect SSU accurately
                        rm -rf "${'$'}MUM_TMP" && mkdir -p "${'$'}MUM_TMP"
                        set +e
                        $qCab -q -F 'update.mum' -d "${'$'}MUM_TMP" "${'$'}f" >/dev/null 2>&1
                        set -e
                        MUM_F=${'$'}(find "${'$'}MUM_TMP" -maxdepth 2 -type f -iname 'update.mum' | head -n 1)
                        if [ -n "${'$'}MUM_F" ]; then
                            if grep -qi 'Package_for_SafeOSDU' "${'$'}MUM_F" 2>/dev/null; then
                                echo "UPD_LOG|Skipped SafeOS WinRE-only CAB: ${'$'}bn"
                                rm -rf "${'$'}MUM_TMP"; continue
                            fi
                            if grep -qi 'WinPE' "${'$'}MUM_F" 2>/dev/null && ! grep -qi 'Edition"' "${'$'}MUM_F" 2>/dev/null; then
                                echo "UPD_LOG|Skipped WinPE-only CAB: ${'$'}bn"
                                rm -rf "${'$'}MUM_TMP"; continue
                            fi
                        fi

                        if printf '%s' "${'$'}low" | grep -qiE '^ssu-|-ssu-' || $qCab -l "${'$'}f" 2>/dev/null | grep -qi 'microsoft-windows-servicingstack_'; then
                            (ln -f "${'$'}f" ${shellQuote(oemUpdates)}/"1_${'$'}bn" 2>/dev/null || cp -f "${'$'}f" ${shellQuote(oemUpdates)}/"1_${'$'}bn")
                            echo "UPD_LOG|SSU -> 1_${'$'}bn"
                        else
                            (ln -f "${'$'}f" ${shellQuote(oemUpdates)}/"2_${'$'}bn" 2>/dev/null || cp -f "${'$'}f" ${shellQuote(oemUpdates)}/"2_${'$'}bn")
                            echo "UPD_LOG|Update CAB -> 2_${'$'}bn"
                        fi
                        rm -rf "${'$'}MUM_TMP"
                    done
                    # Remove Updates dir if everything was Setup DU / skipped
                    if [ -z "${'$'}(ls -A ${shellQuote(oemUpdates)} 2>/dev/null)" ]; then
                        rmdir ${shellQuote(oemUpdates)} 2>/dev/null || true
                    fi
                """.trimIndent())
                updRes.out.filter { it.startsWith("UPD_LOG|") }.forEach { addConsole(it.substringAfter('|')) }
            }
            if (job.hasApps) {
                addConsole("Staging Inbox Store Apps into sources/\$OEM\$/\$\$/Setup/Apps/")
                val appRes = runIsolatedRootScript(appCtx, "oemapp", "set -e\n$pathHeader\nmkdir -p ${shellQuote(oemApps)}\nfor f in \"\$PKG\"/*.msixbundle \"\$PKG\"/*.appxbundle \"\$PKG\"/*.msix \"\$PKG\"/*.appx \"\$PKG\"/*License*.xml \"\$PKG\"/*license*.xml; do if [ ! -f \"\$f\" ]; then continue; fi; bn=\$(basename \"\$f\"); ln -f \"\$f\" ${shellQuote(oemApps)}/\"\$bn\" 2>/dev/null || cp -f \"\$f\" ${shellQuote(oemApps)}/\"\$bn\"; done")
                if (!appRes.isSuccess) throw IllegalStateException("Failed staging Store Apps into \$OEM\$: ${(appRes.out + appRes.err).takeLast(2).joinToString(" ").take(180)}")
            }
            if (job.hasNetFx3) {
                addConsole("Staging .NET Framework 3.5 into sources/\$OEM\$/\$\$/Setup/sxs/")
                val fxRes = runIsolatedRootScript(appCtx, "oemfx3", "set -e\n$pathHeader\nmkdir -p ${shellQuote(oemSxs)}\nfor f in \"\$PKG\"/*netfx3-ondemand*.cab \"\$PKG\"/*NetFx3*.cab; do if [ ! -f \"\$f\" ]; then continue; fi; bn=\$(basename \"\$f\"); ln -f \"\$f\" ${shellQuote(oemSxs)}/\"\$bn\" 2>/dev/null || cp -f \"\$f\" ${shellQuote(oemSxs)}/\"\$bn\"; done")
                if (!fxRes.isSuccess) throw IllegalStateException("Failed staging .NET 3.5 into \$OEM\$: ${(fxRes.out + fxRes.err).takeLast(2).joinToString(" ").take(180)}")
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

            val hasStagedOemUpdates = job.hasUpdates && Shell.cmd("[ -d ${shellQuote(oemUpdates)} ] && [ -n \"\$(ls -A ${shellQuote(oemUpdates)} 2>/dev/null)\" ] && echo OK || true").exec().out.any { it.trim() == "OK" }
            val hasStagedOemApps = job.hasApps && Shell.cmd("[ -d ${shellQuote(oemApps)} ] && [ -n \"\$(ls -A ${shellQuote(oemApps)} 2>/dev/null)\" ] && echo OK || true").exec().out.any { it.trim() == "OK" }
            val hasStagedOemNetFx3 = job.hasNetFx3 && Shell.cmd("[ -d ${shellQuote(oemSxs)} ] && [ -n \"\$(ls -A ${shellQuote(oemSxs)} 2>/dev/null)\" ] && echo OK || true").exec().out.any { it.trim() == "OK" }
            val needUnattendOrOem = config.experience.hasAnyActive() || config.winUtil.hasAnyActive() || hasStagedOemUpdates || hasStagedOemApps || hasStagedOemNetFx3 || config.runComponentCleanup
            if (!needUnattendOrOem) {
                Shell.cmd("rm -rf ${shellQuote("${isoDir.absolutePath}/sources/\$OEM\$")} 2>/dev/null || true").exec()
            } else {
                val arch = if (config.build.arch.lowercase() in setOf("x86", "arm64", "amd64")) config.build.arch.lowercase() else "amd64"
                val detectedLang = Shell.cmd("grep -iE '^[a-z]{2,3}(-[a-z0-9]{2,4}){1,2}[[:space:]]*=' ${shellQuote("${isoDir.absolutePath}/sources/lang.ini")} 2>/dev/null | head -n 1 | cut -d= -f1 | tr -d ' \r\n'").exec().out.firstOrNull().orEmpty().trim().ifBlank { config.lang.code }
                val tmpXml = File(appCtx.cacheDir, "wb_auto.xml").apply { writeText(WindowsCustomizationEngine.generateAutounattendXml(config.experience, config.winUtil, arch, null, true, hasStagedOemUpdates, includeWindowsPePass = true, uiLang = detectedLang), Charsets.UTF_8) }
                val tmpPantherXml = File(appCtx.cacheDir, "wb_panther.xml").apply { writeText(WindowsCustomizationEngine.generateAutounattendXml(config.experience, config.winUtil, arch, null, true, hasStagedOemUpdates, includeWindowsPePass = false), Charsets.UTF_8) }
                val tmpSpec = File(appCtx.cacheDir, "wb_spec.cmd").apply { writeText(WindowsCustomizationEngine.buildSpecializeScript(config.experience, config.winUtil, hasStagedOemUpdates, hasStagedOemApps, hasStagedOemNetFx3), Charsets.UTF_8) }
                val customPath = config.experience.customPostInstallScriptPath.trim()
                val customName = if (customPath.isNotBlank()) customPath.substringAfterLast('/') else ""
                val tmpComp = File(appCtx.cacheDir, "wb_comp.cmd").apply { writeText(WindowsCustomizationEngine.buildSetupCompleteScript(config.runComponentCleanup, customName.isNotBlank(), customName, hasOemApps = hasStagedOemApps), Charsets.UTF_8) }
                val customCopy = if (customPath.isNotBlank()) "mkdir -p ${shellQuote(oemCustom)} && cp -f ${shellQuote(UsbGadgetEngine.resolveDirectPath(customPath))} ${shellQuote(oemCustom)}/ 2>/dev/null || true" else "true"
                Shell.cmd("mkdir -p ${shellQuote(oemPanther)} ${shellQuote(oemScripts)} && cp -f ${shellQuote(tmpXml.absolutePath)} ${shellQuote("${isoDir.absolutePath}/autounattend.xml")} && cp -f ${shellQuote(tmpPantherXml.absolutePath)} ${shellQuote("$oemPanther/unattend.xml")} && cp -f ${shellQuote(tmpSpec.absolutePath)} ${shellQuote("$oemScripts/PocketBoot_Specialize.cmd")} && cp -f ${shellQuote(tmpComp.absolutePath)} ${shellQuote("$oemScripts/SetupComplete.cmd")} && $customCopy").exec()
                tmpXml.delete(); tmpPantherXml.delete(); tmpSpec.delete(); tmpComp.delete()
                addConsole("Injected autounattend.xml + unplug-safe \$OEM\$ scripts")
            }

            // In ISO mode (xorriso ISO-9660 + Joliet), WinPE's cdfs.sys requires every single file <= 4 GiB.
            // Automatically split any >3.95 GiB install.wim -> install*.swm AND any >3.95 GiB .msu -> .pbpart01/.pbpart02!
            if (config.outputMode != WinBuildOutputMode.DIRECT_NTFS_USB) {
                val wimCheckPath = "${isoDir.absolutePath}/sources/install.wim"
                val wimCheckSz = Shell.cmd("stat -c '%s' ${shellQuote(wimCheckPath)} 2>/dev/null || echo 0").exec().out.firstOrNull()?.trim()?.toLongOrNull() ?: 0L
                if (wimCheckSz > 3_950_000_000L) {
                    activeBin = "wimlib-imagex"
                    addConsole("Auto-splitting install.wim (${UsbGadgetEngine.formatBytes(wimCheckSz)}) into 3,500 MB install*.swm chunks (cdfs.sys + FAT32 safe)...")
                    publishConv("Step 4/4 · Splitting >4GB install.wim...", "3,500 MB chunks (install*.swm)", 0.83f, wimCheckSz)
                    val swmPath = "${isoDir.absolutePath}/sources/install.swm"
                    val swmRes = runIsolatedRootScript(appCtx, "wimsplit", """
                        set -e
                        $pathHeader
                        ${thermalExecPrefix(convThermalProfileLive)}
                        ${'$'}THERM_PFX $qWim split ${shellQuote(wimCheckPath)} ${shellQuote(swmPath)} 3500 >/dev/null 2>&1
                        if [ ! -s ${shellQuote(swmPath)} ] || [ ! -s ${shellQuote("${isoDir.absolutePath}/sources/install2.swm")} ]; then
                            echo "WIM split output missing" >&2; exit 1
                        fi
                        rm -f ${shellQuote(wimCheckPath)}
                    """.trimIndent())
                    if (!swmRes.isSuccess) throw IllegalStateException("Failed to split >4 GiB install.wim: ${(swmRes.out + swmRes.err).takeLast(3).joinToString(" ").take(200)}")
                    addConsole("Split complete: sources/install*.swm ready (all chunks <= 3.5 GB)")
                }

                // Split any >3.95 GiB .msu cumulative update in $OEM$/$$/Setup/Updates into 3,500 MiB .pbpart01/.pbpart02 chunks
                val bigMsus = Shell.cmd("if [ -d ${shellQuote(oemUpdates)} ]; then for f in ${shellQuote(oemUpdates)}/*.msu ${shellQuote(oemUpdates)}/*.MSU; do if [ ! -f "\$f" ]; then continue; fi; sz=\$(stat -c '%s' "\$f" 2>/dev/null || echo 0); if [ "\$sz" -gt 3950000000 ]; then printf '%s|%s\n' "\$f" "\$sz"; fi; done; fi").exec().out
                bigMsus.forEach { line ->
                    val parts = line.split("|", limit = 2)
                    if (parts.size == 2) {
                        val msuPath = parts[0].trim()
                        val msuSz = parts[1].trim().toLongOrNull() ?: 0L
                        val msuName = msuPath.substringAfterLast('/')
                        val freeBeforeSplit = UsbGadgetEngine.getFreeSpaceBytes(config.targetDir)
                        if (freeBeforeSplit > 0L && freeBeforeSplit < msuSz + 256L * 1024L * 1024L) {
                            throw IllegalStateException("Not enough free space to split $msuName (${UsbGadgetEngine.formatBytes(msuSz)}). Free: ${UsbGadgetEngine.formatBytes(freeBeforeSplit)}")
                        }
                        addConsole("Splitting >4 GiB update $msuName (${UsbGadgetEngine.formatBytes(msuSz)}) into 3,500 MiB .pbpart chunks (auto-rejoined during Windows Specialize)...")
                        publishConv("Step 4/4 · Splitting >4GB update...", "$msuName (.pbpart01/.pbpart02)", 0.84f, msuSz)
                        val msuSplitRes = runIsolatedRootScript(appCtx, "msusplit", """
                            set -e
                            $pathHeader
                            SRC=${shellQuote(msuPath)}
                            ORIG_SZ=${'$'}(stat -c '%s' "${'$'}SRC")
                            BS=4194304
                            CHUNK_BLKS=875
                            SKIP=0
                            IDX=1
                            SUM=0
                            rm -f "${'$'}SRC".pbpart*
                            while [ "${'$'}SUM" -lt "${'$'}ORIG_SZ" ]; do
                                TAG=${'$'}(printf '%02d' "${'$'}IDX")
                                OUTP="${'$'}SRC.pbpart${'$'}TAG"
                                dd if="${'$'}SRC" of="${'$'}OUTP" bs="${'$'}BS" count="${'$'}CHUNK_BLKS" skip="${'$'}SKIP" 2>/dev/null
                                PSZ=${'$'}(stat -c '%s' "${'$'}OUTP" 2>/dev/null || echo 0)
                                if [ "${'$'}PSZ" -le 0 ]; then rm -f "${'$'}OUTP"; break; fi
                                SUM=${'$'}((SUM + PSZ))
                                SKIP=${'$'}((SKIP + CHUNK_BLKS))
                                IDX=${'$'}((IDX + 1))
                            done
                            if [ "${'$'}SUM" -ne "${'$'}ORIG_SZ" ]; then
                                rm -f "${'$'}SRC".pbpart*
                                echo "MSU split byte mismatch (${'$'}SUM != ${'$'}ORIG_SZ)" >&2
                                exit 1
                            fi
                            rm -f "${'$'}SRC"
                            echo "MSU_SPLIT_OK|${'$'}((IDX - 1)) parts|${'$'}SUM bytes"
                        """.trimIndent())
                        if (!msuSplitRes.isSuccess) {
                            throw IllegalStateException("Failed splitting >4 GiB update $msuName: ${(msuSplitRes.out + msuSplitRes.err).takeLast(2).joinToString(" ").take(200)}")
                        }
                        val okInfo = msuSplitRes.out.firstOrNull { it.startsWith("MSU_SPLIT_OK|") }?.substringAfter('|') ?: "split"
                        addConsole("Split complete: $msuName -> $okInfo (verified byte-exact)")
                    }
                }
            }

            // Free temporary CAB/WIM staging files and stamp PB_STAGE_V20 marker for safe Fast Step-4 retry!
            Shell.cmd("rm -rf $qTmp /data/local/tmp/pb_uup_ext /data/local/tmp/pb_uup_hive && printf '%s\n' '$STAGE_SCHEMA_VERSION' > $qIsoReadyMarker").exec()
            }

            // Gate 1: Pre-Pack Full Staging Integrity Audit (checks ALL files in ISODIR before packing ISO or USB)
            publishConv("Step 4/4 · Gate 1: Auditing all staged files...", "Zero-byte, WIM, SetupHost & size checks", 0.85f)
            val isIsoOutput = config.outputMode != WinBuildOutputMode.DIRECT_NTFS_USB
            val gate1Res = runIsolatedRootScript(appCtx, "gate1_audit", """
                set -e
                $pathHeader
                # 1. Scan ALL files in ISODIR for 0-byte critical files + count total & 0-byte files
                TOTAL_FILES=0
                ZERO_ALL=0
                BAD_ZERO=""
                BAD_BIG=""
                G1_LIST="/data/local/tmp/pb_g1_files.txt"
                find "${'$'}ISODIR" -type f > "${'$'}G1_LIST"
                while IFS= read -r f; do
                    if [ ! -f "${'$'}f" ]; then continue; fi
                    TOTAL_FILES=${'$'}((TOTAL_FILES + 1))
                    sz=${'$'}(stat -c '%s' "${'$'}f" 2>/dev/null || echo 0)
                    bn=${'$'}(basename "${'$'}f")
                    low=${'$'}(printf '%s' "${'$'}bn" | tr 'A-Z' 'a-z')
                    if [ "${'$'}sz" -eq 0 ]; then
                        ZERO_ALL=${'$'}((ZERO_ALL + 1))
                        case "${'$'}low" in
                            *.wim|*.swm|*.esd|*.msu|*.cab|*.efi|*.dll|*.exe|*.mui|*.xml|*.cmd|*.bat|*.ini|*.cfg|*.sys|*.man|*.vbs|*.com|*.bin|*.pbpart*|bootmgr)
                                BAD_ZERO="${'$'}f"
                                break
                                ;;
                        esac
                    fi
                    if [ "${if (isIsoOutput) "1" else "0"}" = "1" ] && [ "${'$'}sz" -gt 3950000000 ]; then
                        BAD_BIG="${'$'}f (${'$'}sz bytes)"
                        break
                    fi
                done < "${'$'}G1_LIST"
                rm -f "${'$'}G1_LIST"
                if [ -n "${'$'}BAD_ZERO" ]; then echo "FAIL|0-byte critical file in ISODIR: ${'$'}BAD_ZERO"; exit 1; fi
                if [ -n "${'$'}BAD_BIG" ]; then echo "FAIL|File exceeds 3.95 GiB ISO limit: ${'$'}BAD_BIG"; exit 1; fi
                if [ "${'$'}TOTAL_FILES" -lt 20 ]; then echo "FAIL|ISODIR incomplete (only ${'$'}TOTAL_FILES files)"; exit 1; fi

                # 2. Verify boot.wim (2+ indexes, index 2 bootable) and install.* WIM header
                BOOT_INFO=${'$'}($qWim info "${'$'}ISODIR/sources/boot.wim" 2>/dev/null || true)
                BOOT_CNT=${'$'}(printf '%s\n' "${'$'}BOOT_INFO" | grep -c '^Index:' || true)
                if [ "${'$'}BOOT_CNT" -lt 2 ]; then echo "FAIL|boot.wim invalid (found ${'$'}BOOT_CNT index(es), need 2)"; exit 1; fi
                INST_FILE=""
                for cand in "${'$'}ISODIR/sources/install.wim" "${'$'}ISODIR/sources/install.swm" "${'$'}ISODIR/sources/install.esd"; do
                    if [ -s "${'$'}cand" ]; then INST_FILE="${'$'}cand"; break; fi
                done
                if [ -z "${'$'}INST_FILE" ]; then echo "FAIL|Missing install.wim / install.swm / install.esd in ISODIR/sources/"; exit 1; fi
                INST_CNT=${'$'}($qWim info "${'$'}INST_FILE" 2>/dev/null | grep -c '^Index:' || true)
                if [ "${'$'}INST_CNT" -lt 1 ]; then echo "FAIL|Unreadable WIM header in ${'$'}(basename "${'$'}INST_FILE")"; exit 1; fi

                # 3. Verify SetupHost.exe SHA-1 sync between ISODIR/sources/ and boot.wim Index 2 (prevents "Install driver to show hardware")
                ISO_SH=${'$'}(find "${'$'}ISODIR/sources" -maxdepth 1 -type f -iname 'setuphost.exe' 2>/dev/null | head -n 1)
                SH_SYNC="n/a"
                if [ -n "${'$'}ISO_SH" ]; then
                    SH_TMP="/data/local/tmp/pb_gate1_sh"
                    rm -rf "${'$'}SH_TMP" && mkdir -p "${'$'}SH_TMP"
                    $qWim extract "${'$'}ISODIR/sources/boot.wim" 2 "/sources/SetupHost.exe" --dest-dir="${'$'}SH_TMP" --no-acls --no-attributes >/dev/null 2>&1 ||                       $qWim extract "${'$'}ISODIR/sources/boot.wim" 2 "/sources/setuphost.exe" --dest-dir="${'$'}SH_TMP" --no-acls --no-attributes >/dev/null 2>&1 || true
                    BOOT_SH=${'$'}(find "${'$'}SH_TMP" -maxdepth 1 -type f -iname 'setuphost.exe' 2>/dev/null | head -n 1)
                    if [ -z "${'$'}BOOT_SH" ]; then
                        rm -rf "${'$'}SH_TMP"; echo "FAIL|SetupHost.exe missing inside boot.wim Index 2"; exit 1
                    fi
                    H_ISO=${'$'}(sha1sum "${'$'}ISO_SH" | awk '{print ${'$'}1}')
                    H_BOOT=${'$'}(sha1sum "${'$'}BOOT_SH" | awk '{print ${'$'}1}')
                    rm -rf "${'$'}SH_TMP"
                    if [ "${'$'}H_ISO" != "${'$'}H_BOOT" ]; then
                        echo "FAIL|SetupHost.exe SHA-1 mismatch (ISODIR=${'$'}H_ISO vs boot.wim=${'$'}H_BOOT)"
                        exit 1
                    fi
                    SH_SYNC="SHA-1 synced"
                fi

                # 4. Verify at least one bootloader exists
                HAS_BL=${'$'}(find "${'$'}ISODIR" -maxdepth 4 -type f \( -iname 'etfsboot.com' -o -iname 'efisys*.bin' -o -iname 'bootx64.efi' -o -iname 'bootaa64.efi' \) 2>/dev/null | head -n 1)
                if [ -z "${'$'}HAS_BL" ]; then echo "FAIL|No BIOS/UEFI bootloaders found in ISODIR"; exit 1; fi
                echo "GATE1_OK|${'$'}TOTAL_FILES|${'$'}ZERO_ALL|${'$'}INST_CNT|${'$'}SH_SYNC"
            """.trimIndent())
            if (!gate1Res.isSuccess) {
                val g1Err = (gate1Res.out + gate1Res.err).firstOrNull { it.startsWith("FAIL|") }?.substringAfter('|')
                    ?: (gate1Res.out + gate1Res.err).takeLast(3).joinToString(" ")
                Shell.cmd("rm -f $qIsoReadyMarker 2>/dev/null || true").exec()
                throw IllegalStateException("Gate 1 pre-pack integrity audit failed: ${g1Err.take(240)}")
            }
            val g1Parts = gate1Res.out.firstOrNull { it.startsWith("GATE1_OK|") }?.split('|').orEmpty()
            val isoDirFileCount = g1Parts.getOrNull(1)?.toIntOrNull() ?: 0
            val isoDirZeroCount = g1Parts.getOrNull(2)?.toIntOrNull() ?: 0
            val g1EdCnt = g1Parts.getOrNull(3)?.toIntOrNull() ?: 1
            val g1ShSync = g1Parts.getOrNull(4).orEmpty().ifBlank { "OK" }
            addConsole("Gate 1 passed: $isoDirFileCount staged files verified (0 corrupt, $g1EdCnt edition(s), SetupHost $g1ShSync)")

            var injectedMbPath: String? = null
            if (config.outputMode == WinBuildOutputMode.DIRECT_NTFS_USB) {
                activeBin = "mkfs.ntfs"
                addConsole("Starting Direct NTFS USB creation (${config.scheme.label})...")
                publishConv("Step 4/4 · Building Direct Bootable USB...", "${config.scheme.label} · NTFS + UEFI/BIOS Boot", 0.85f)
                val usbErr = buildDirectNtfsUsbFromDir(appCtx, isoDir.absolutePath, finalOutputPath, config) { stg, det, f, curB ->
                    addConsole("$stg · $det")
                    publishConv(stg, det, 0.85f + f * 0.12f, curB)
                }
                if (usbErr != null) throw IllegalStateException(usbErr)
            } else {
                activeBin = "xorriso"
                val qOutIso = shellQuote(finalOutputPath)
                val volId = "WIN_${config.build.build.substringBefore('.')}".take(28).uppercase()
                val estIsoBytes = Shell.cmd("du -sb $qIsoDir 2>/dev/null | awk '{print \$1}'").exec().out.firstOrNull()?.trim()?.toLongOrNull()?.coerceAtLeast(1L) ?: (5L * 1024L * 1024L * 1024L)
                val freeForIso = UsbGadgetEngine.getFreeSpaceBytes(config.targetDir)
                if (freeForIso > 0L && freeForIso < estIsoBytes + 256L * 1024L * 1024L) {
                    throw IllegalStateException("Not enough free space to pack ISO. Need ~${UsbGadgetEngine.formatBytes(estIsoBytes + 256L * 1024L * 1024L)}, have ${UsbGadgetEngine.formatBytes(freeForIso)} free.")
                }

                val xorLog = "/data/local/tmp/pb_xorriso_prog.log"
                val qXorLog = shellQuote(xorLog)
                val preferNoPrompt = config.experience.hasAnyActive() || config.winUtil.hasAnyActive()
                addConsole("xorriso: packing ~${UsbGadgetEngine.formatBytes(estIsoBytes)} (-iso-level 3 -J -joliet-long -c boot/boot.cat --stdio_sync 16m)")
                publishConv("Step 4/4 · Packing bootable ISO (xorriso)...", finalFileName, 0.86f)
                val xorTherm = thermalExecPrefix(convThermalProfileLive)
                val isoPollJob = scope.launch {
                    while (isActive && !convCancelledFlag.get()) {
                        delay(1200L)
                        if (convThermalCoolingLive) continue
                        val pollOut = Shell.cmd("stat -c 'SZ|%s' $qOutIso 2>/dev/null || echo 'SZ|0'; tail -c 512 $qXorLog 2>/dev/null | tr '\r' '\n' | tail -n 5 || true").exec().out
                        val curSz = pollOut.firstOrNull { it.startsWith("SZ|") }?.substringAfter('|')?.trim()?.toLongOrNull() ?: lastMeasuredOutputBytes
                        pollOut.filterNot { it.startsWith("SZ|") }.map { it.trim() }.filter { it.isNotEmpty() }.lastOrNull()?.let { addConsole(it) }
                        publishConv(
                            "Step 4/4 · Packing bootable ISO (xorriso)...",
                            "${UsbGadgetEngine.formatBytes(curSz)} / ~${UsbGadgetEngine.formatBytes(estIsoBytes)}",
                            0.86f + (curSz.toFloat() / estIsoBytes.toFloat()).coerceIn(0f, 1f) * 0.09f,
                            curSz
                        )
                    }
                }
                val qXor = shellQuote(tools.xorriso!!)
                val isoPackScript = """
                    set -e
                    $pathHeader
                    $xorTherm
                    mkdir -p ${shellQuote(safeTargetDir)}
                    rm -f $qOutIso
                    : > $qXorLog
                    trap 'rm -f $ACTIVE_PID_FILE' EXIT
                    cd "${'$'}ISODIR"
                    find . -exec touch {} + 2>/dev/null || true
                    EFI_BOOT=""
                    if [ "${if (preferNoPrompt) "1" else "0"}" = "1" ]; then
                        EFI_BOOT=${'$'}(find . -maxdepth 4 -type f -iname 'efisys_noprompt.bin' 2>/dev/null | head -n 1 | sed 's|^\./||')
                    fi
                    if [ -z "${'$'}EFI_BOOT" ]; then
                        EFI_BOOT=${'$'}(find . -maxdepth 4 -type f -iname 'efisys.bin' 2>/dev/null | head -n 1 | sed 's|^\./||')
                    fi
                    BIOS_BOOT=${'$'}(find . -maxdepth 4 -type f -iname 'etfsboot.com' 2>/dev/null | head -n 1 | sed 's|^\./||')
                    if [ -n "${'$'}BIOS_BOOT" ] || [ -n "${'$'}EFI_BOOT" ]; then mkdir -p boot; fi
                    echo "ISO-9660/Joliet Bootloaders: BIOS=${'$'}{BIOS_BOOT:-none} | EFI=${'$'}{EFI_BOOT:-none}" >> $qXorLog
                    if [ -n "${'$'}BIOS_BOOT" ] && [ -n "${'$'}EFI_BOOT" ]; then
                        ${'$'}THERM_PFX $qXor -as mkisofs --stdio_sync 16m -iso-level 3 -full-iso9660-filenames -J -joliet-long -c boot/boot.cat -volid "$volId" -b "${'$'}BIOS_BOOT" -no-emul-boot -boot-load-size 8 -eltorito-alt-boot -e "${'$'}EFI_BOOT" -no-emul-boot -o $qOutIso . >> $qXorLog 2>&1 &
                    elif [ -n "${'$'}EFI_BOOT" ]; then
                        ${'$'}THERM_PFX $qXor -as mkisofs --stdio_sync 16m -iso-level 3 -full-iso9660-filenames -J -joliet-long -c boot/boot.cat -volid "$volId" -e "${'$'}EFI_BOOT" -no-emul-boot -o $qOutIso . >> $qXorLog 2>&1 &
                    else
                        ${'$'}THERM_PFX $qXor -as mkisofs --stdio_sync 16m -iso-level 3 -full-iso9660-filenames -J -joliet-long -volid "$volId" -o $qOutIso . >> $qXorLog 2>&1 &
                    fi
                    XPID=${'$'}!; echo "${'$'}XPID" > $ACTIVE_PID_FILE; wait "${'$'}XPID"
                    rm -f $ACTIVE_PID_FILE
                """.trimIndent()
                val xorRes = runIsolatedRootScript(appCtx, "xorriso", isoPackScript)
                isoPollJob.cancel()
                val xorLastLines = Shell.cmd("tail -n 8 $qXorLog 2>/dev/null | tr '\r' '\n' | tail -n 6 || true").exec().out.map { it.trim() }.filter { it.isNotEmpty() }
                xorLastLines.forEach { addConsole(it) }
                Shell.cmd("rm -f $qXorLog 2>/dev/null || true").exec()
                if (!xorRes.isSuccess) {
                    Shell.cmd("rm -f $qOutIso 2>/dev/null || true").exec()
                    val errDetail = xorLastLines.takeLast(3).joinToString(" | ").ifBlank { (xorRes.out + xorRes.err).takeLast(3).joinToString(" ") }
                    throw IllegalStateException("xorriso failed (exit ${xorRes.code}): ${errDetail.take(260)}")
                }

                // Gate 2: Post-Pack Full ISO Audit via bundled 7zzs (catches any 0-byte truncation or dropped files!)
                val q7zBin = tools.sevenZip?.let { shellQuote(it) }
                if (q7zBin != null) {
                    activeBin = "7zzs"
                    publishConv("Step 4/4 · Gate 2: Verifying final ISO...", "Scanning all ISO entries for corruption", 0.96f)
                    val gate2Res = runIsolatedRootScript(appCtx, "gate2_iso", """
                        set -e
                        $q7zBin l -slt $qOutIso 2>/dev/null | awk '
                            BEGIN { path=""; folder=""; sz=-1; files=0; zeros=0; bad=""; seen=0 }
                            function flush_rec() {
                                if (!seen) return
                                if (folder != "+") {
                                    lp = tolower(path)
                                    if (lp !~ /(^|[\/\\])boot\.cat(alog)?$/ && lp !~ /^\[boot\]/) {
                                        files++
                                        if (sz == 0) {
                                            zeros++
                                            if (bad == "" && lp ~ /\.(wim|swm|esd|msu|cab|efi|dll|exe|mui|xml|cmd|bat|ini|cfg|sys|man|vbs|com|bin|pbpart[0-9]+)$|(^|[\/\\])bootmgr$/) {
                                                bad = path
                                            }
                                        }
                                    }
                                }
                                path=""; folder=""; sz=-1; seen=0
                            }
                            /^Path = / { flush_rec(); path=substr(${'$'}0, 8); seen=1; next }
                            /^Folder = / { folder=substr(${'$'}0, 10); next }
                            /^Size = / { sz=substr(${'$'}0, 8)+0; next }
                            /^$/ { flush_rec(); next }
                            END { flush_rec(); printf "G2|%d|%d|%s\n", files, zeros, bad }
                        '
                    """.trimIndent())
                    val g2Line = gate2Res.out.firstOrNull { it.startsWith("G2|") }
                    if (!gate2Res.isSuccess || g2Line == null) {
                        Shell.cmd("rm -f $qOutIso 2>/dev/null || true").exec()
                        throw IllegalStateException("Gate 2 ISO audit failed: 7zzs could not read the finished ISO.")
                    }
                    val g2 = g2Line.split('|', limit = 4)
                    val isoFiles = g2.getOrNull(1)?.toIntOrNull() ?: 0
                    val isoZeros = g2.getOrNull(2)?.toIntOrNull() ?: 0
                    val isoBad = g2.getOrNull(3).orEmpty().trim()
                    if (isoBad.isNotEmpty() || isoZeros > isoDirZeroCount || (isoDirFileCount > 0 && isoFiles < isoDirFileCount)) {
                        Shell.cmd("rm -f $qOutIso 2>/dev/null || true").exec()
                        throw IllegalStateException("Gate 2 ISO integrity failed (files=$isoFiles/$isoDirFileCount, zeros=$isoZeros/$isoDirZeroCount${if (isoBad.isNotEmpty()) ", corrupt=$isoBad" else ""}). Staged ISODIR preserved for retry.")
                    }
                    addConsole("Gate 2 passed: $isoFiles/$isoDirFileCount ISO files verified via 7zzs (0 corrupt)")
                }
                UsbGadgetEngine.fixUserStoragePermissions(appCtx, safeTargetDir, isDir = true)
                UsbGadgetEngine.fixUserStoragePermissions(appCtx, finalOutputPath, isDir = false)
                if (config.outputMode == WinBuildOutputMode.DIRECT_MULTIBOOT && config.targetMultiBootPath.isNotBlank()) {
                    val mbPath = UsbGadgetEngine.resolveDirectPath(config.targetMultiBootPath)
                    publishConv("Moving ISO into Multi-Boot...", mbPath.substringAfterLast('/'), 0.97f)
                    val addRes = VentoyImageBuilder.addIsoToVentoy(appCtx, mbPath, finalOutputPath, moveFile = true) { p -> publishConv("Moving into Multi-Boot...", p.detail.ifBlank { p.stage }, 0.98f) }
                    if (addRes.success) injectedMbPath = mbPath
                }
            }

            // Post-build verification BEFORE cleaning up PKGS or workRootPath!
            val finalSize = if (injectedMbPath != null) {
                Shell.cmd("stat -c '%s' ${shellQuote(injectedMbPath)} 2>/dev/null || echo 0").exec().out.firstOrNull()?.trim()?.toLongOrNull() ?: 0L
            } else {
                Shell.cmd("stat -c '%s' ${shellQuote(finalOutputPath)} 2>/dev/null || echo 0").exec().out.firstOrNull()?.trim()?.toLongOrNull() ?: 0L
            }
            if (injectedMbPath == null && finalSize < 50L * 1024L * 1024L) {
                Shell.cmd("rm -f ${shellQuote(finalOutputPath)} 2>/dev/null || true").exec()
                throw IllegalStateException("Output file missing or truncated (${UsbGadgetEngine.formatBytes(finalSize)}). Downloaded UUP packages were preserved for retry.")
            }

            val totalSec = ((System.currentTimeMillis() - startMs) / 1000L).coerceAtLeast(1L)
            val avgSpeed = if (finalSize > 0L) finalSize / totalSec else smoothDiskSpeed
            val benchStr = "Built in ${formatDurationShort(totalSec)} · Avg ${FastIsoDownloader.formatSpeed(avgSpeed)} · ${UsbGadgetEngine.formatBytes(finalSize)}"
            val bootLabel = if (injectedMbPath == null && finalSize > 0L) {
                val rpt = IsoInspector.inspectBootReadiness(appCtx, finalOutputPath, finalSize)
                when { rpt.hasUefiX64 && rpt.hasLegacyBios -> "✓ UEFI + BIOS Verified"; rpt.hasUefiX64 -> "✓ UEFI x64 Verified"; rpt.hasUefiArm64 -> "✓ UEFI ARM64 Verified"; rpt.hasLegacyBios -> "✓ Legacy BIOS Verified"; else -> null }
            } else null

            // Output verified — now clean up according to read-only / keepPackagesAfterBuild
            if (job.isReadOnlySource) {
                addConsole("Preserved local UUP source folder untouched (read-only)")
                UsbGadgetEngine.cleanupTempWorkDir(workRootPath)
            } else if (convKeepPackagesLive) {
                saveBuildJobJson(appCtx, workRootPath, config.copy(keepPackagesAfterBuild = true), job.totalDownloadBytes, true)
                Shell.cmd("rm -rf $qIsoDir $qTmp $qIsoReadyMarker").exec()
                addConsole("Kept base UUP packages for fast rebuilds")
            } else {
                UsbGadgetEngine.cleanupTempWorkDir(workRootPath)
            }

            emitConvLog("win-build", "Completed $finalFileName ($benchStr${if (bootLabel != null) " · $bootLabel" else ""})")
            val completion = DownloadCompletionEvent(job.jobTitle, finalFileName, finalOutputPath, injectedMbPath, null, null, ChecksumVerifyStatus.NONE, convAutoMountLive, benchStr, bootLabel)
            FastIsoDownloader.publishExternalCompletion(completion)
            NotificationHelper.showBuildComplete(appCtx, job.jobTitle, "$finalFileName · $benchStr")
            job.onComplete(completion)
        } catch (_: CancellationException) {
            Shell.cmd("rm -rf ${shellQuote(isoDir.absolutePath)} ${shellQuote(tmpDir.absolutePath)} 2>/dev/null || true").exec()
        } catch (e: Exception) {
            // Preserve PKGS & (if Step 4 already staged) preserve ISODIR so Retry jumps straight to Step 4 packing!
            val keepIsoDir = currentStep == 4 && isStageMarkerValid(workRootPath)
            if (keepIsoDir) {
                Shell.cmd("rm -rf ${shellQuote(tmpDir.absolutePath)} 2>/dev/null || true").exec()
            } else {
                Shell.cmd("rm -rf ${shellQuote(isoDir.absolutePath)} ${shellQuote(tmpDir.absolutePath)} ${shellQuote("$workRootPath/.pb_isodir_ready")} 2>/dev/null || true").exec()
            }
            if (!job.isReadOnlySource) {
                saveBuildJobJson(appCtx, workRootPath, config.copy(keepPackagesAfterBuild = true), job.totalDownloadBytes, true)
            }
            val msg = e.message ?: "Unexpected build error"
            addConsole("ERROR: $msg")
            runCatching {
                val failLogTmp = File(appCtx.cacheDir, "pb_failed_build.log")
                failLogTmp.writeText("PocketBoot v4.5.0 Build Failure Log (${nowTs()})\nBuild: ${job.jobTitle}\nOutput: $finalFileName\nStep: $currentStep/4\nError: $msg\n\n--- Console Log ---\n${consoleLines.joinToString("\n")}\n", Charsets.UTF_8)
                val failDst = "$safeTargetDir/PocketBoot_FailedBuild.log"
                Shell.cmd("mkdir -p ${shellQuote(safeTargetDir)} && cp -f ${shellQuote(failLogTmp.absolutePath)} ${shellQuote(failDst)}").exec()
                UsbGadgetEngine.fixUserStoragePermissions(appCtx, failDst, isDir = false)
                failLogTmp.delete()
            }
            if (keepIsoDir) addConsole("Preserved staged ISODIR + packages — Retry will jump straight to Step 4 packing!")
            else if (!job.isReadOnlySource) addConsole("Saved downloaded UUP packages for instant Retry.")
            lastFailedJob = job
            val elapsedSec = ((System.currentTimeMillis() - startMs) / 1000L).coerceAtLeast(1L)
            val (batPct, _) = readBatteryPercent(appCtx)
            _conversionState.value = WindowsConversionJobState(
                title = job.jobTitle,
                outputFileName = finalFileName,
                outputMode = config.outputMode,
                currentStep = currentStep,
                stageLabel = "Build Failed at Step $currentStep/4",
                detailText = msg,
                overallProgress = _conversionState.value?.overallProgress ?: 0f,
                diskSpeedBytesPerSec = 0L,
                elapsedSeconds = elapsedSec,
                etaSeconds = -1L,
                currentOutputBytes = lastMeasuredOutputBytes,
                freeSpaceBytes = UsbGadgetEngine.getFreeSpaceBytes(config.targetDir),
                activeBinary = activeBin,
                batteryPct = batPct,
                stepDurationsSec = stepDurations.toMap(),
                consoleTail = consoleLines.toList(),
                queuedCount = conversionQueue.size,
                queuedTitles = conversionQueue.map { it.jobTitle },
                autoMount = convAutoMountLive,
                workDirPath = workRootPath,
                keepPackagesAfterBuild = true,
                isReadOnlySource = job.isReadOnlySource,
                isFailed = true,
                errorMessage = msg,
                batteryTempC = readBatteryTempC(appCtx),
                thermalProfile = convThermalProfileLive,
                autoThermalCooldown = convAutoCooldownLive,
                isCoolingDown = false
            )
            NotificationHelper.showBuildFailed(appCtx, job.jobTitle, msg)
            emitConvLog("win-build", "Windows build failed: $msg", true)
            job.onError("Windows build failed: $msg")
        } finally {
            thermalWatchdogJob.cancel()
            if (convThermalCoolingLive) {
                convThermalCoolingLive = false
                signalActivePid("CONT")
            }
            Shell.cmd("rm -f $ACTIVE_PID_FILE 2>/dev/null || true").exec()
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
