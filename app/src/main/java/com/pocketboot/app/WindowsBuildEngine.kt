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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

enum class WinFamilyFilter(val label: String, val shortTag: String) {
    WIN11("Windows 11", "Win11"),
    WIN10("Windows 10", "Win10"),
    SERVER("Windows Server", "Server")
}

enum class WinFeatureVersion(
    val family: WinFamilyFilter,
    val label: String,
    val searchTerms: List<String>,
    val buildPrefixes: List<String>,
    val recommended: Boolean = false
) {
    W11_26H2(WinFamilyFilter.WIN11, "26H2", listOf("Windows 11 26H2", "26300", "26320"), listOf("26300", "26320", "26340"), recommended = true),
    W11_26H1(WinFamilyFilter.WIN11, "26H1", listOf("Windows 11 26H1", "28000", "28020"), listOf("28000", "28020", "28120")),
    W11_25H2(WinFamilyFilter.WIN11, "25H2", listOf("Windows 11 25H2", "26200"), listOf("26200")),
    W11_24H2(WinFamilyFilter.WIN11, "24H2", listOf("Windows 11 24H2", "26100"), listOf("26100")),
    W11_23H2(WinFamilyFilter.WIN11, "23H2", listOf("Windows 11 23H2", "22631"), listOf("22631", "22635")),
    W11_INSIDER(WinFamilyFilter.WIN11, "Insider", listOf("Windows 11 Insider", "Canary"), listOf("29", "27", "26340", "28120")),

    W10_22H2(WinFamilyFilter.WIN10, "22H2", listOf("Windows 10 22H2", "19045"), listOf("19045"), recommended = true),
    W10_21H2(WinFamilyFilter.WIN10, "21H2 / LTSC", listOf("Windows 10 21H2", "19044"), listOf("19044")),

    SRV_2025(WinFamilyFilter.SERVER, "2025", listOf("Windows Server 2025", "Server 26100"), listOf("26100", "26300", "28000"), recommended = true),
    SRV_2022(WinFamilyFilter.SERVER, "2022", listOf("Windows Server 2022", "20348"), listOf("20348")),
    SRV_2019(WinFamilyFilter.SERVER, "2019", listOf("Windows Server 2019", "17763"), listOf("17763"));

    companion object {
        fun forFamily(family: WinFamilyFilter): List<WinFeatureVersion> =
            entries.filter { it.family == family }
    }
}

enum class WinBuildTier(val sectionTitle: String, val badgeLabel: String) {
    STABLE("Stable (General Availability)", "Stable"),
    PREVIEW("Preview (Release / Cumulative Preview)", "Preview"),
    INSIDER("Other / Insider Builds", "Insider")
}

data class GroupedWindowsBuilds(
    val stable: List<WindowsChannelBuild> = emptyList(),
    val preview: List<WindowsChannelBuild> = emptyList(),
    val insider: List<WindowsChannelBuild> = emptyList()
) {
    val totalCount: Int get() = stable.size + preview.size + insider.size
    val isEmpty: Boolean get() = totalCount == 0
}

enum class WinIsoCompression(
    val label: String,
    val subtitle: String,
    val ext: String,
    val wimlibArg: String,
    val recommended: Boolean = false
) {
    WIM(
        label = "WIM · Fast",
        subtitle = "~2–4 min · low RAM usage",
        ext = "wim",
        wimlibArg = "--compress=maximum",
        recommended = true
    ),
    ESD(
        label = "ESD · Compact",
        subtitle = "~5–8 min · smallest ISO",
        ext = "esd",
        wimlibArg = "--compress=LZMS --solid --threads=2",
        recommended = false
    )
}

enum class WinBuildOutputMode(
    val label: String,
    val subtitle: String,
    val recommended: Boolean = false
) {
    STANDARD_ISO(
        label = "Bootable ISO (.iso)",
        subtitle = "Standard UEFI + Legacy BIOS installer",
        recommended = true
    ),
    DIRECT_MULTIBOOT(
        label = "Add to Multi-Boot (.img)",
        subtitle = "Builds .iso and moves directly into Multi-Boot"
    ),
    DIRECT_NTFS_USB(
        label = "Direct Bootable USB (.img)",
        subtitle = "Skips ISO packing · faster & saves ~5 GB temp space"
    )
}

data class WindowsChannelBuild(
    val uuid: String,
    val title: String,
    val build: String,
    val arch: String,
    val createdEpoch: Long,
    val tier: WinBuildTier = WinBuildTier.STABLE,
    val channelBadge: String = "Stable"
) {
    val archDisplay: String
        get() = when (arch.lowercase()) {
            "amd64" -> "x64"
            "arm64" -> "ARM64"
            "x86" -> "x86"
            else -> arch.uppercase()
        }

    val dateDisplay: String
        get() = if (createdEpoch > 0L) {
            SimpleDateFormat("dd MMM yyyy", Locale.US).format(Date(createdEpoch * 1000L))
        } else "Recent"
}

data class WinBuildLangOption(
    val code: String,
    val displayName: String
)

data class WinBuildEditionOption(
    val code: String,
    val displayName: String
)

data class WinPackageFileEntry(
    val name: String,
    val url: String,
    val sizeBytes: Long,
    val sha1: String
)

data class WinManifestEstimate(
    val fileCount: Int,
    val totalBytes: Long
)

data class WindowsChannelBuildConfig(
    val build: WindowsChannelBuild,
    val lang: WinBuildLangOption,
    val editions: List<WinBuildEditionOption>,
    val compression: WinIsoCompression,
    val outputMode: WinBuildOutputMode,
    val targetDir: String,
    val customOutputName: String = "",
    val targetMultiBootPath: String = "",
    val connections: Int = 8,
    val autoMount: Boolean = true,
    val bypassRequirements: Boolean = true,
    val bypassMicrosoftAccount: Boolean = true,
    val disableDataCollection: Boolean = true,
    val disableBitLocker: Boolean = true,
    val extraDriversDir: String = ""
)

data class PendingWindowsBuild(
    val workRootPath: String,
    val workPrefix: String,
    val title: String,
    val outputName: String,
    val cachedBytes: Long,
    val totalBytes: Long,
    val config: WindowsChannelBuildConfig
) {
    val progress: Float
        get() = if (totalBytes > 0L) (cachedBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f) else 0f
}

data class WindowsBuildJobState(
    val title: String,
    val stage: String,
    val detail: String,
    val progress: Float = 0f,
    val downloadedBytes: Long = 0L,
    val totalBytes: Long = 0L,
    val speedBytesPerSec: Long = 0L,
    val etaSeconds: Long = -1L,
    val activeThreads: Int = 8,
    val filesDone: Int = 0,
    val filesTotal: Int = 0,
    val isConverting: Boolean = false,
    val isPaused: Boolean = false,
    val autoMount: Boolean = true,
    val workDirPath: String = ""
)

data class WindowsBuildToolsStatus(
    val cabextract: String?,
    val wimlib: String?,
    val chntpw: String?,
    val xorriso: String?
) {
    val canConvertIso: Boolean get() = cabextract != null && wimlib != null && chntpw != null && xorriso != null
    val canBuildDirectUsb: Boolean get() = cabextract != null && wimlib != null && chntpw != null
}

object WindowsBuildEngine {

    private const val API_BASE = "https://api.uupdump.net"
    private const val UA = "Mozilla/5.0 (Linux; Android 15) PocketBoot/4.3.2"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _jobState = MutableStateFlow<WindowsBuildJobState?>(null)
    val jobState: StateFlow<WindowsBuildJobState?> = _jobState.asStateFlow()

    private val langsCache = ConcurrentHashMap<String, List<WinBuildLangOption>>()
    private val editionsCache = ConcurrentHashMap<String, List<WinBuildEditionOption>>()
    private val manifestEstimateCache = ConcurrentHashMap<String, WinManifestEstimate>()

    private var activeJob: Job? = null
    private val cancelledFlag = AtomicBoolean(false)
    private val pausedFlag = AtomicBoolean(false)
    @Volatile private var autoMountLive: Boolean = true
    private var cachedContext: Context? = null
    private var activeConfig: WindowsChannelBuildConfig? = null
    private var activeOnComplete: ((DownloadCompletionEvent) -> Unit)? = null
    private var activeOnError: ((String) -> Unit)? = null
    private var activeOnLog: ((LogEntry) -> Unit)? = null
    private var activeWorkRootPath: String = ""
    private var activeWorkPrefix: String = ""

    private val JUNK_TITLE_PATTERNS = listOf(
        "critical oobe",
        "dynamic update",
        "safe os",
        "setup dynamic",
        ".net framework",
        "microsoft edge",
        "security intelligence",
        "defender",
        "windows update stack",
        "servicing stack",
        "language pack",
        "feature on demand",
        "out-of-band",
        "recovery update",
        "compatibility update",
        "windows configuration update",
        "malicious software"
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

    private fun fetchJsonWithRetry(urlStr: String): JSONObject? {
        for (attempt in 0..2) {
            val res = runCatching {
                val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 12_000
                    readTimeout = 20_000
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
            if (res.first == "RATE_LIMIT" && attempt < 2) {
                Thread.sleep(1300L * (attempt + 1))
                continue
            }
            if (attempt < 2) Thread.sleep(500L)
        }
        return null
    }

    private fun isJunkStubTitle(title: String): Boolean {
        val low = title.lowercase()
        return JUNK_TITLE_PATTERNS.any { low.contains(it) }
    }

    private fun cleanNormalizedTitleKey(title: String, build: String, arch: String): String {
        val noDupSuffix = title.replace(Regex("""\s*\(\d+\)\s*$"""), "").trim().lowercase()
        return "$noDupSuffix|$build|${arch.lowercase()}"
    }

    private fun classifyBuildTier(title: String, build: String): Pair<WinBuildTier, String> {
        val low = title.lowercase()
        val major = build.substringBefore('.').toIntOrNull() ?: 0
        return when {
            low.contains("canary") || major >= 29000 || major in 27000..27999 ->
                WinBuildTier.INSIDER to "Canary"
            low.contains("dev") || major in 26340..26999 || major in 28100..28999 ->
                WinBuildTier.INSIDER to "Dev"
            low.contains("beta") || major in 26320..26339 || major in 28020..28099 || major == 22635 ->
                WinBuildTier.INSIDER to "Beta"
            low.contains("insider") ->
                WinBuildTier.INSIDER to "Insider"
            low.contains("release preview") || low.contains("preview update") || low.contains("rp") ->
                WinBuildTier.PREVIEW to "Preview"
            else ->
                WinBuildTier.STABLE to "Stable"
        }
    }

    suspend fun fetchGroupedBuilds(
        family: WinFamilyFilter,
        featureVer: WinFeatureVersion,
        archFilter: String = "amd64",
        customQuery: String = ""
    ): GroupedWindowsBuilds = withContext(Dispatchers.IO) {
        val cleanQuery = customQuery.trim()
        val rawQueries = if (cleanQuery.isNotBlank()) listOf(cleanQuery) else featureVer.searchTerms

        val collected = mutableListOf<WindowsChannelBuild>()
        val seenUuids = mutableSetOf<String>()
        val seenNormKeys = mutableSetOf<String>()

        for (qTerm in rawQueries) {
            val encoded = URLEncoder.encode(qTerm, "UTF-8")
            val root = fetchJsonWithRetry("$API_BASE/listid.php?search=$encoded&sortByDate=1") ?: continue
            val buildsObj = root.optJSONObject("response")?.optJSONObject("builds") ?: continue

            val keys = buildsObj.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                val b = buildsObj.optJSONObject(k) ?: continue
                val uuid = b.optString("uuid", "").trim()
                val title = b.optString("title", "").trim()
                val build = b.optString("build", "").trim()
                val arch = b.optString("arch", "amd64").trim()
                val created = b.optLong("created", 0L)
                if (uuid.isEmpty() || title.isEmpty()) continue
                if (archFilter != "all" && !arch.equals(archFilter, ignoreCase = true)) continue
                if (isJunkStubTitle(title)) continue

                val lowTitle = title.lowercase()
                val isServerTitle = lowTitle.contains("server") || lowTitle.contains("azure stack")
                val matchesFamily = when (family) {
                    WinFamilyFilter.SERVER -> isServerTitle
                    WinFamilyFilter.WIN10 -> !isServerTitle && (lowTitle.contains("windows 10") || build.startsWith("1904"))
                    WinFamilyFilter.WIN11 -> !isServerTitle && !lowTitle.contains("windows 10")
                }
                if (!matchesFamily) continue

                if (cleanQuery.isBlank()) {
                    val matchesVersion = featureVer.buildPrefixes.any { pref -> build.startsWith(pref) } ||
                        lowTitle.contains(featureVer.label.substringBefore(' ').lowercase())
                    if (!matchesVersion) continue
                }

                val normKey = cleanNormalizedTitleKey(title, build, arch)
                if (!seenUuids.add(uuid) || !seenNormKeys.add(normKey)) continue

                val (tier, badge) = classifyBuildTier(title, build)
                collected.add(
                    WindowsChannelBuild(
                        uuid = uuid,
                        title = title.replace(Regex("""\s*\(\d+\)\s*$"""), "").trim(),
                        build = build,
                        arch = arch,
                        createdEpoch = created,
                        tier = tier,
                        channelBadge = badge
                    )
                )
            }
        }

        val sorted = collected.sortedByDescending { it.createdEpoch }
        GroupedWindowsBuilds(
            stable = sorted.filter { it.tier == WinBuildTier.STABLE }.take(15),
            preview = sorted.filter { it.tier == WinBuildTier.PREVIEW }.take(15),
            insider = sorted.filter { it.tier == WinBuildTier.INSIDER }.take(15)
        )
    }

    suspend fun fetchLanguages(updateId: String): List<WinBuildLangOption> = withContext(Dispatchers.IO) {
        langsCache[updateId]?.let { return@withContext it }
        val root = fetchJsonWithRetry("$API_BASE/listlangs.php?id=${URLEncoder.encode(updateId, "UTF-8")}")
            ?: return@withContext emptyList()
        val resp = root.optJSONObject("response") ?: return@withContext emptyList()
        val fancy = resp.optJSONObject("langFancyNames") ?: JSONObject()
        val list = resp.optJSONArray("langList")
        val out = mutableListOf<WinBuildLangOption>()
        if (list != null) {
            for (i in 0 until list.length()) {
                val code = list.optString(i, "").trim()
                if (code.isNotEmpty()) {
                    out.add(WinBuildLangOption(code = code, displayName = fancy.optString(code, code)))
                }
            }
        }
        val sorted = out.sortedWith(
            compareBy<WinBuildLangOption> { !it.code.equals("en-us", true) }
                .thenBy { !it.displayName.startsWith("English", true) }
                .thenBy { it.displayName.lowercase() }
        )
        if (sorted.isNotEmpty()) langsCache[updateId] = sorted
        sorted
    }

    suspend fun fetchEditions(updateId: String, langCode: String): List<WinBuildEditionOption> =
        withContext(Dispatchers.IO) {
            val key = "$updateId|$langCode"
            editionsCache[key]?.let { return@withContext it }
            val url = "$API_BASE/listeditions.php?id=${URLEncoder.encode(updateId, "UTF-8")}&lang=${URLEncoder.encode(langCode, "UTF-8")}"
            val root = fetchJsonWithRetry(url) ?: return@withContext emptyList()
            val resp = root.optJSONObject("response") ?: return@withContext emptyList()
            val fancy = resp.optJSONObject("editionFancyNames") ?: JSONObject()
            val list = resp.optJSONArray("editionList")
            val out = mutableListOf<WinBuildEditionOption>()
            if (list != null) {
                for (i in 0 until list.length()) {
                    val code = list.optString(i, "").trim()
                    if (code.isNotEmpty()) {
                        out.add(WinBuildEditionOption(code = code, displayName = fancy.optString(code, code)))
                    }
                }
            }
            val sorted = out.sortedWith(
                compareBy<WinBuildEditionOption> {
                    when (it.code.uppercase()) {
                        "PROFESSIONAL" -> 0
                        "CORE" -> 1
                        "ENTERPRISE" -> 2
                        else -> 3
                    }
                }.thenBy { it.displayName.lowercase() }
            )
            if (sorted.isNotEmpty()) editionsCache[key] = sorted
            sorted
        }

    private fun isSkippableNonEditionCab(fileName: String): Boolean {
        val low = fileName.lowercase()
        if (!low.endsWith(".cab")) return false
        return low.contains("windows10.0-kb") ||
            low.contains("windows11.0-kb") ||
            low.startsWith("ssu-") ||
            low.contains("-ssu-") ||
            low.contains("desktopdeployment") ||
            low.contains("aggregatedmetadata") ||
            low.contains("baseless") ||
            low.contains("safeos") ||
            low.contains("dynamicupdate")
    }

    private fun filterCorePackageFiles(all: List<WinPackageFileEntry>): List<WinPackageFileEntry> =
        all.filter { f ->
            val low = f.name.lowercase()
            low.endsWith(".esd") || (low.endsWith(".cab") && !isSkippableNonEditionCab(low))
        }.ifEmpty { all }

    suspend fun fetchManifestEstimate(
        updateId: String,
        langCode: String,
        editionCodes: List<String>
    ): WinManifestEstimate? = withContext(Dispatchers.IO) {
        val edParam = editionCodes.sorted().joinToString(";") { it.lowercase() }
        val key = "$updateId|$langCode|$edParam"
        manifestEstimateCache[key]?.let { return@withContext it }

        val url = "$API_BASE/get.php?id=${URLEncoder.encode(updateId, "UTF-8")}" +
            "&lang=${URLEncoder.encode(langCode, "UTF-8")}" +
            "&edition=${URLEncoder.encode(edParam, "UTF-8")}&noLinks=1"
        val root = fetchJsonWithRetry(url) ?: return@withContext null
        val filesObj = root.optJSONObject("response")?.optJSONObject("files") ?: return@withContext null

        val list = mutableListOf<WinPackageFileEntry>()
        val keys = filesObj.keys()
        while (keys.hasNext()) {
            val fileName = keys.next()
            val f = filesObj.optJSONObject(fileName) ?: continue
            val size = f.optString("size", "0").toLongOrNull() ?: f.optLong("size", 0L)
            val sha1 = f.optString("sha1", "").trim()
            list.add(WinPackageFileEntry(name = fileName, url = "", sizeBytes = size, sha1 = sha1))
        }
        val core = filterCorePackageFiles(list)
        if (core.isEmpty()) return@withContext null
        val est = WinManifestEstimate(fileCount = core.size, totalBytes = core.sumOf { it.sizeBytes.coerceAtLeast(0L) })
        manifestEstimateCache[key] = est
        est
    }

    suspend fun fetchFileManifest(
        updateId: String,
        langCode: String,
        editionCodes: List<String>
    ): List<WinPackageFileEntry> = withContext(Dispatchers.IO) {
        val edParam = editionCodes.joinToString(";") { it.lowercase() }
        val url = "$API_BASE/get.php?id=${URLEncoder.encode(updateId, "UTF-8")}" +
            "&lang=${URLEncoder.encode(langCode, "UTF-8")}" +
            "&edition=${URLEncoder.encode(edParam, "UTF-8")}&noLinks=0"
        val root = fetchJsonWithRetry(url) ?: return@withContext emptyList()
        val filesObj = root.optJSONObject("response")?.optJSONObject("files") ?: return@withContext emptyList()

        val out = mutableListOf<WinPackageFileEntry>()
        val keys = filesObj.keys()
        while (keys.hasNext()) {
            val fileName = keys.next()
            val f = filesObj.optJSONObject(fileName) ?: continue
            val fUrl = f.optString("url", "").trim()
            val size = f.optString("size", "0").toLongOrNull() ?: f.optLong("size", 0L)
            val sha1 = f.optString("sha1", "").trim()
            if (fUrl.startsWith("http", true)) {
                out.add(WinPackageFileEntry(name = fileName, url = fUrl, sizeBytes = size, sha1 = sha1))
            }
        }
        out.sortedByDescending { it.sizeBytes }
    }

    fun buildExternalPageLink(build: WindowsChannelBuild, langCode: String): String =
        "https://uupdump.net/selectlang.php?id=${URLEncoder.encode(build.uuid, "UTF-8")}&pack=${URLEncoder.encode(langCode, "UTF-8")}"

    private fun saveBuildJobJson(context: Context, workRootPath: String, config: WindowsChannelBuildConfig, totalBytes: Long) {
        runCatching {
            val edArr = JSONArray()
            config.editions.forEach { ed ->
                edArr.put(JSONObject().put("code", ed.code).put("displayName", ed.displayName))
            }
            val obj = JSONObject().apply {
                put("uuid", config.build.uuid)
                put("title", config.build.title)
                put("build", config.build.build)
                put("arch", config.build.arch)
                put("createdEpoch", config.build.createdEpoch)
                put("langCode", config.lang.code)
                put("langName", config.lang.displayName)
                put("editions", edArr)
                put("compression", config.compression.name)
                put("outputMode", config.outputMode.name)
                put("targetDir", config.targetDir)
                put("customOutputName", config.customOutputName)
                put("targetMultiBootPath", config.targetMultiBootPath)
                put("connections", config.connections)
                put("autoMount", autoMountLive)
                put("bypassRequirements", config.bypassRequirements)
                put("bypassMicrosoftAccount", config.bypassMicrosoftAccount)
                put("disableDataCollection", config.disableDataCollection)
                put("disableBitLocker", config.disableBitLocker)
                put("extraDriversDir", config.extraDriversDir)
                put("totalBytes", totalBytes)
            }
            val tmpFile = File(context.cacheDir, "pb_build_job_${System.nanoTime()}.json")
            tmpFile.writeText(obj.toString(), Charsets.UTF_8)
            Shell.cmd("mv -f ${shellQuote(tmpFile.absolutePath)} ${shellQuote("$workRootPath/build_job.json")} 2>/dev/null").exec()
            tmpFile.delete()
        }
    }

    suspend fun scanPendingBuilds(context: Context, scanDirs: List<String>): List<PendingWindowsBuild> =
        withContext(Dispatchers.IO) {
            val activePath = activeWorkRootPath
            val out = mutableListOf<PendingWindowsBuild>()
            val seenPaths = mutableSetOf<String>()

            val rootsToScan = scanDirs.map { UsbGadgetEngine.resolveDirectPath(it).trimEnd('/') + "/.pocketboot_tmp" } +
                listOf("/data/local/tmp")

            for (parent in rootsToScan.distinct()) {
                val qParent = shellQuote(parent)
                val jobFiles = Shell.cmd(
                    "[ -d $qParent ] && find $qParent -maxdepth 2 -type f -name 'build_job.json' 2>/dev/null || true"
                ).exec().out.map { it.trim() }.filter { it.isNotEmpty() }

                for (jf in jobFiles) {
                    val workRoot = jf.substringBeforeLast('/')
                    if (workRoot == activePath && _jobState.value != null) continue
                    if (!seenPaths.add(workRoot)) continue
                    val raw = Shell.cmd("cat ${shellQuote(jf)} 2>/dev/null").exec().out.joinToString("\n")
                    if (raw.isBlank()) continue
                    runCatching {
                        val o = JSONObject(raw)
                        val edArr = o.optJSONArray("editions") ?: JSONArray()
                        val eds = mutableListOf<WinBuildEditionOption>()
                        for (i in 0 until edArr.length()) {
                            val eo = edArr.optJSONObject(i) ?: continue
                            val c = eo.optString("code", "").trim()
                            if (c.isNotEmpty()) eds.add(WinBuildEditionOption(c, eo.optString("displayName", c)))
                        }
                        if (eds.isEmpty()) return@runCatching
                        val b = WindowsChannelBuild(
                            uuid = o.optString("uuid"),
                            title = o.optString("title"),
                            build = o.optString("build"),
                            arch = o.optString("arch", "amd64"),
                            createdEpoch = o.optLong("createdEpoch", 0L)
                        )
                        if (b.uuid.isBlank()) return@runCatching
                        val cfg = WindowsChannelBuildConfig(
                            build = b,
                            lang = WinBuildLangOption(o.optString("langCode", "en-us"), o.optString("langName", "English")),
                            editions = eds,
                            compression = runCatching { WinIsoCompression.valueOf(o.optString("compression")) }.getOrDefault(WinIsoCompression.WIM),
                            outputMode = runCatching { WinBuildOutputMode.valueOf(o.optString("outputMode")) }.getOrDefault(WinBuildOutputMode.STANDARD_ISO),
                            targetDir = o.optString("targetDir", "/data/media/0/Download"),
                            customOutputName = o.optString("customOutputName", ""),
                            targetMultiBootPath = o.optString("targetMultiBootPath", ""),
                            connections = o.optInt("connections", 8),
                            autoMount = o.optBoolean("autoMount", true),
                            bypassRequirements = o.optBoolean("bypassRequirements", true),
                            bypassMicrosoftAccount = o.optBoolean("bypassMicrosoftAccount", true),
                            disableDataCollection = o.optBoolean("disableDataCollection", true),
                            disableBitLocker = o.optBoolean("disableBitLocker", true),
                            extraDriversDir = o.optString("extraDriversDir", "")
                        )
                        val workPrefix = workRoot.substringAfterLast('/')
                        val pkgBytes = Shell.cmd(
                            "[ -d ${shellQuote("$workRoot/PKGS")} ] && du -sb ${shellQuote("$workRoot/PKGS")} 2>/dev/null | awk '{print \$1}' || echo 0"
                        ).exec().out.firstOrNull()?.trim()?.toLongOrNull() ?: 0L
                        val stageDir = stageCacheDir(context, workPrefix)
                        val partBytes = stageDir.listFiles()?.filter { it.name.endsWith(".part") }?.sumOf { f ->
                            val meta = File(f.absolutePath + ".pbmeta")
                            if (meta.exists()) {
                                runCatching {
                                    val mo = JSONObject(meta.readText(Charsets.UTF_8))
                                    val sliceCnt = mo.optInt("sliceCount", 0)
                                    val doneArr = mo.optJSONArray("doneSlices")
                                    val tot = mo.optLong("totalBytes", 0L)
                                    if (sliceCnt > 0 && doneArr != null && tot > 0L) {
                                        (tot * doneArr.length().toLong()) / sliceCnt.toLong()
                                    } else {
                                        val sA = mo.optJSONArray("chunkStarts")
                                        val cA = mo.optJSONArray("chunkCursors")
                                        if (sA != null && cA != null) {
                                            (0 until minOf(sA.length(), cA.length())).sumOf { i ->
                                                (cA.optLong(i) - sA.optLong(i)).coerceAtLeast(0L)
                                            }
                                        } else 0L
                                    }
                                }.getOrDefault(0L)
                            } else f.length()
                        } ?: 0L

                        out.add(
                            PendingWindowsBuild(
                                workRootPath = workRoot,
                                workPrefix = workPrefix,
                                title = "Win ${b.build} (${b.archDisplay} · ${cfg.lang.code})",
                                outputName = cfg.customOutputName.ifBlank { "Windows_${b.build}.iso" },
                                cachedBytes = pkgBytes + partBytes,
                                totalBytes = o.optLong("totalBytes", 0L),
                                config = cfg
                            )
                        )
                    }
                }
            }
            out.sortedByDescending { it.cachedBytes }
        }

    fun discardPendingBuild(context: Context, pending: PendingWindowsBuild) {
        scope.launch {
            UsbGadgetEngine.cleanupTempWorkDir(pending.workRootPath)
            runCatching { stageCacheDir(context, pending.workPrefix).deleteRecursively() }
        }
    }

    private fun pruneOldWorkDirs(targetDir: String, keepPrefix: String) {
        val resolvedDefault = UsbGadgetEngine.resolveDirectPath(targetDir).trimEnd('/')
        val parentTmp = "$resolvedDefault/.pocketboot_tmp"
        val qParent = shellQuote(parentTmp)
        val qKeep = shellQuote(keepPrefix)
        Shell.cmd(
            "if [ -d $qParent ]; then find $qParent -mindepth 1 -maxdepth 1 -type d -name 'winbuild_*' ! -name $qKeep -mmin +1440 -exec rm -rf {} + 2>/dev/null || true; fi"
        ).exec()
    }

    fun updateAutoMount(value: Boolean) {
        autoMountLive = value
        _jobState.value = _jobState.value?.copy(autoMount = value)
    }

    fun pauseBuild() {
        val cur = _jobState.value ?: return
        if (cur.isConverting || cur.isPaused) return
        pausedFlag.set(true)
        activeJob?.cancel()
        activeJob = null
        val pausedState = cur.copy(
            isPaused = true,
            speedBytesPerSec = 0L,
            etaSeconds = -1L,
            stage = "Paused (${cur.filesDone}/${cur.filesTotal} packages verified)",
            detail = "Byte-level checkpoints saved · Tap Resume anytime"
        )
        _jobState.value = pausedState
        cachedContext?.let { ctx ->
            NotificationHelper.updateDownloadProgress(
                ctx,
                TurboDownloadState(
                    title = pausedState.title,
                    fileName = pausedState.stage,
                    url = "",
                    targetDir = activeConfig?.targetDir.orEmpty(),
                    downloadedBytes = pausedState.downloadedBytes,
                    totalBytes = pausedState.totalBytes,
                    supportsRange = true,
                    isPaused = true,
                    statusText = pausedState.detail
                )
            )
        }
    }

    fun resumeBuild() {
        val ctx = cachedContext ?: return
        val cfg = activeConfig ?: return
        val onDone = activeOnComplete ?: return
        val onErr = activeOnError ?: return
        val onLog = activeOnLog
        val cur = _jobState.value
        if (cur != null && !cur.isPaused) return
        startBuild(ctx, cfg.copy(autoMount = autoMountLive), onDone, onErr, onLog ?: {})
    }

    fun cancelBuild(discardTemp: Boolean = true) {
        cancelledFlag.set(true)
        pausedFlag.set(false)
        activeJob?.cancel()
        activeJob = null
        val pathToClean = activeWorkRootPath
        val prefixToClean = activeWorkPrefix
        _jobState.value = null
        cachedContext?.let { ctx ->
            TurboDownloadService.stop(ctx)
            if (discardTemp && prefixToClean.isNotBlank()) {
                runCatching { stageCacheDir(ctx, prefixToClean).deleteRecursively() }
            }
        }
        if (discardTemp && pathToClean.isNotBlank()) {
            scope.launch { UsbGadgetEngine.cleanupTempWorkDir(pathToClean) }
            activeWorkRootPath = ""
            activeWorkPrefix = ""
        }
    }

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

    fun startBuild(
        context: Context,
        config: WindowsChannelBuildConfig,
        onComplete: (DownloadCompletionEvent) -> Unit,
        onError: (String) -> Unit,
        onLog: (LogEntry) -> Unit = {}
    ) {
        activeJob?.cancel()
        activeJob = null
        cancelledFlag.set(false)
        pausedFlag.set(false)
        autoMountLive = config.autoMount

        val appCtx = context.applicationContext
        cachedContext = appCtx
        activeConfig = config
        activeOnComplete = onComplete
        activeOnError = onError
        activeOnLog = onLog

        val tools = inspectTools(appCtx)
        val needIsoTools = config.outputMode != WinBuildOutputMode.DIRECT_NTFS_USB
        if (needIsoTools && !tools.canConvertIso) {
            val msg = "Windows ISO build tools missing in APK. Check Pre-Flight Diagnostics."
            emitLog("win-iso", msg, true)
            onError(msg)
            return
        }
        if (!needIsoTools && !tools.canBuildDirectUsb) {
            val msg = "Windows build tools (cabextract/wimlib/chntpw) missing in APK."
            emitLog("win-iso", msg, true)
            onError(msg)
            return
        }

        val minNeededBytes = if (config.outputMode == WinBuildOutputMode.DIRECT_NTFS_USB) {
            10L * 1024L * 1024L * 1024L
        } else {
            12L * 1024L * 1024L * 1024L
        }
        val freeBytes = UsbGadgetEngine.getFreeSpaceBytes(config.targetDir)
        if (freeBytes in 1L until minNeededBytes) {
            val msg = "Need at least ${UsbGadgetEngine.formatBytes(minNeededBytes)} free in ${UsbGadgetEngine.friendlyFolderName(config.targetDir)} (have ${UsbGadgetEngine.formatBytes(freeBytes)})."
            emitLog("win-iso", msg, true)
            onError(msg)
            return
        }

        val maxConns = config.connections.coerceIn(1, 16)
        val jobTitle = "Win ${config.build.build} (${config.build.archDisplay} · ${config.lang.code})"
        _jobState.value = WindowsBuildJobState(
            title = jobTitle,
            stage = "Fetching package manifest...",
            detail = "Preparing ${config.editions.size} edition(s)",
            progress = 0.02f,
            activeThreads = maxConns,
            autoMount = autoMountLive
        )
        TurboDownloadService.start(appCtx)
        emitLog("win-iso", "Starting $jobTitle (${config.outputMode.label}, ${config.compression.label}, Turbo ${maxConns}x)")

        activeJob = scope.launch {
            val workPrefix = "winbuild_${config.build.uuid.take(8)}_${config.lang.code}"
            pruneOldWorkDirs(config.targetDir, keepPrefix = workPrefix)
            val workRootPath = UsbGadgetEngine.createSafeTempWorkDir(
                context = appCtx,
                defaultDir = config.targetDir,
                prefix = workPrefix
            )
            activeWorkRootPath = workRootPath
            activeWorkPrefix = workPrefix

            val pkgDir = File(workRootPath, "PKGS")
            val isoDir = File(workRootPath, "ISODIR")
            val tmpDir = File(workRootPath, "TMP")
            val appStageDir = stageCacheDir(appCtx, workPrefix)

            try {
                val manifest = fetchFileManifest(
                    updateId = config.build.uuid,
                    langCode = config.lang.code,
                    editionCodes = config.editions.map { it.code }
                )
                if (manifest.isEmpty()) {
                    throw IllegalStateException("Package server returned an empty file manifest. Try another build or language.")
                }

                val coreFiles = filterCorePackageFiles(manifest)
                val totalBytes = coreFiles.sumOf { it.sizeBytes.coerceAtLeast(0L) }
                Shell.cmd(
                    "mkdir -p ${shellQuote(pkgDir.absolutePath)} ${shellQuote(isoDir.absolutePath)} ${shellQuote(tmpDir.absolutePath)} && " +
                        "chmod -R 0777 ${shellQuote(workRootPath)}"
                ).exec()
                saveBuildJobJson(appCtx, workRootPath, config, totalBytes)

                val alreadyVerified = batchVerifyExistingPackages(pkgDir.absolutePath, coreFiles)
                val initialDoneBytes = coreFiles.filter { it.name in alreadyVerified }.sumOf { it.sizeBytes.coerceAtLeast(0L) }
                val downloadedCounter = AtomicLong(initialDoneBytes)
                val filesDoneCounter = AtomicInteger(alreadyVerified.size)
                val liveThreadsCounter = AtomicInteger(0)
                val effectiveMaxConns = AtomicInteger(maxConns)

                if (alreadyVerified.isNotEmpty()) {
                    emitLog("win-iso", "Resumed ${alreadyVerified.size}/${coreFiles.size} verified packages (${UsbGadgetEngine.formatBytes(initialDoneBytes)})")
                }

                val meterJob = launch {
                    var prevBytes = downloadedCounter.get()
                    var prevTime = System.currentTimeMillis()
                    var smoothedSpeed = 0.0
                    var ticks = 0
                    while (isActive && !cancelledFlag.get() && !pausedFlag.get()) {
                        delay(450L)
                        ticks++
                        val now = System.currentTimeMillis()
                        val cur = downloadedCounter.get().coerceIn(0L, totalBytes.coerceAtLeast(1L))
                        val dt = (now - prevTime).coerceAtLeast(1L)
                        val instant = ((cur - prevBytes).coerceAtLeast(0L) * 1000.0) / dt.toDouble()
                        smoothedSpeed = if (smoothedSpeed == 0.0) instant else (smoothedSpeed * 0.65 + instant * 0.35)
                        prevBytes = cur
                        prevTime = now

                        val spd = smoothedSpeed.toLong()
                        val rem = (totalBytes - cur).coerceAtLeast(0L)
                        val eta = if (spd > 1024L && totalBytes > 0L) rem / spd else -1L
                        val frac = if (totalBytes > 0L) (cur.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f) else 0f
                        val shownThreads = liveThreadsCounter.get().coerceAtLeast(1).coerceAtMost(effectiveMaxConns.get())

                        val st = WindowsBuildJobState(
                            title = jobTitle,
                            stage = "Downloading packages (${filesDoneCounter.get()}/${coreFiles.size})...",
                            detail = "${UsbGadgetEngine.formatBytes(cur)} / ${UsbGadgetEngine.formatBytes(totalBytes)}",
                            progress = 0.04f + frac * 0.50f,
                            downloadedBytes = cur,
                            totalBytes = totalBytes,
                            speedBytesPerSec = spd,
                            etaSeconds = eta,
                            activeThreads = shownThreads,
                            filesDone = filesDoneCounter.get(),
                            filesTotal = coreFiles.size,
                            isConverting = false,
                            isPaused = false,
                            autoMount = autoMountLive,
                            workDirPath = workRootPath
                        )
                        _jobState.value = st
                        if (ticks % 2 == 0) {
                            NotificationHelper.updateDownloadProgress(
                                appCtx,
                                TurboDownloadState(
                                    title = st.title,
                                    fileName = st.stage,
                                    url = "",
                                    targetDir = config.targetDir,
                                    downloadedBytes = cur,
                                    totalBytes = totalBytes,
                                    speedBytesPerSec = spd,
                                    etaSeconds = eta,
                                    activeThreads = shownThreads,
                                    supportsRange = true,
                                    statusText = st.detail
                                )
                            )
                        }
                    }
                }

                try {
                    val pending = coreFiles.filter { it.name !in alreadyVerified }
                    val batchItems = pending.map { entry ->
                        val safeName = entry.name.replace(Regex("[^A-Za-z0-9._-]"), "_")
                        FastIsoDownloader.TurboBatchItem(
                            id = entry.name,
                            url = entry.url,
                            partFile = File(appStageDir, "$safeName.part"),
                            metaFile = File(appStageDir, "$safeName.part.pbmeta"),
                            expectedSize = entry.sizeBytes,
                            expectedSha1 = entry.sha1
                        )
                    }
                    FastIsoDownloader.downloadBatchTurbo(
                        items = batchItems,
                        connections = maxConns,
                        pausedFlag = pausedFlag,
                        cancelledFlag = cancelledFlag,
                        totalCounter = downloadedCounter,
                        activeStreamsOut = liveThreadsCounter,
                        onFileVerified = { doneItem ->
                            val destPath = "${pkgDir.absolutePath}/${doneItem.id}"
                            val mvRes = Shell.cmd("mv -f ${shellQuote(doneItem.partFile.absolutePath)} ${shellQuote(destPath)} 2>&1").exec()
                            if (!mvRes.isSuccess) {
                                throw IllegalStateException("Move failed for ${doneItem.id}: ${mvRes.out.joinToString(" ")}")
                            }
                            filesDoneCounter.incrementAndGet()
                        }
                    )
                } finally {
                    meterJob.cancel()
                }

                if (pausedFlag.get() || cancelledFlag.get()) return@launch
                runCatching { appStageDir.deleteRecursively() }
                emitLog("win-iso", "Downloaded & SHA-1 verified all ${coreFiles.size} packages")

                fun updateConv(stage: String, detail: String, pct: Float) {
                    val st = WindowsBuildJobState(
                        title = jobTitle,
                        stage = stage,
                        detail = detail,
                        progress = pct,
                        downloadedBytes = totalBytes,
                        totalBytes = totalBytes,
                        speedBytesPerSec = 0L,
                        etaSeconds = -1L,
                        activeThreads = maxConns,
                        filesDone = coreFiles.size,
                        filesTotal = coreFiles.size,
                        isConverting = true,
                        isPaused = false,
                        autoMount = autoMountLive,
                        workDirPath = workRootPath
                    )
                    _jobState.value = st
                    NotificationHelper.updateDownloadProgress(
                        appCtx,
                        TurboDownloadState(
                            title = st.title,
                            fileName = stage,
                            url = "",
                            targetDir = config.targetDir,
                            downloadedBytes = (pct * 100).toLong(),
                            totalBytes = 100L,
                            supportsRange = false,
                            statusText = detail
                        )
                    )
                }

                val qCab = shellQuote(tools.cabextract!!)
                val qWim = shellQuote(tools.wimlib!!)
                val qChntpw = shellQuote(tools.chntpw!!)
                val qPkg = shellQuote(pkgDir.absolutePath)
                val qIsoDir = shellQuote(isoDir.absolutePath)
                val qTmp = shellQuote(tmpDir.absolutePath)
                val pathHeader = """
                    PKG=$qPkg
                    ISODIR=$qIsoDir
                    TMP=$qTmp
                    export TMPDIR="${'$'}TMP" HOME="${'$'}TMP"
                """.trimIndent()

                // Pre-flight check bundled binaries in an isolated child shell
                val toolCheckRes = runIsolatedRootScript(
                    appCtx,
                    "toolcheck",
                    """
                    set -e
                    $pathHeader
                    rm -rf "${'$'}ISODIR" "${'$'}TMP" /data/local/tmp/pb_uup_ext /data/local/tmp/pb_uup_hive
                    mkdir -p "${'$'}ISODIR" "${'$'}TMP"
                    $qCab --version >/dev/null
                    $qWim --version >/dev/null
                    """.trimIndent()
                )
                if (!toolCheckRes.isSuccess) {
                    val err = (toolCheckRes.out + toolCheckRes.err).filter { it.isNotBlank() }.takeLast(5).joinToString(" | ").take(280)
                    emitLog("win-iso", "Build tool check failed (code ${toolCheckRes.code}): $err", true)
                    throw IllegalStateException("Build tool check failed: ${err.ifBlank { "exit code ${toolCheckRes.code}" }}")
                }

                // STEP 1/4: Convert genuine edition/language CABs -> uncompressed reference ESDs on native Linux storage
                val allCabPaths = runIsolatedRootScript(
                    appCtx,
                    "listcabs",
                    """
                    $pathHeader
                    for c in "${'$'}PKG"/*.cab "${'$'}PKG"/*.CAB; do
                        [ -f "${'$'}c" ] && echo "${'$'}c"
                    done
                    """.trimIndent()
                ).out.map { it.trim() }.filter { it.startsWith("/") && it.lowercase().endsWith(".cab") }
                val editionCabs = allCabPaths.filter { !isSkippableNonEditionCab(it.substringAfterLast('/')) }

                if (editionCabs.isEmpty()) {
                    emitLog("win-iso", "Step 1/4: No edition .cab packages to convert (pure ESD build)")
                } else {
                    emitLog("win-iso", "Step 1/4: Converting ${editionCabs.size} edition .cab package(s) (skipped ${allCabPaths.size - editionCabs.size} non-edition CABs)")
                }

                var convertedCabs = 0
                editionCabs.forEachIndexed { idx, cabPath ->
                    if (cancelledFlag.get()) return@launch
                    val cabName = cabPath.substringAfterLast('/')
                    val pct = 0.55f + (idx.toFloat() / editionCabs.size.coerceAtLeast(1).toFloat()) * 0.07f
                    updateConv(
                        "Step 1/4 · Converting CAB ${idx + 1}/${editionCabs.size}...",
                        cabName,
                        pct
                    )
                    val qOneCab = shellQuote(cabPath)
                    val qBn = shellQuote(cabName)
                    val oneCabRes = runIsolatedRootScript(
                        appCtx,
                        "cab",
                        """
                        set -e
                        $pathHeader
                        BN=$qBn
                        EXTDIR="/data/local/tmp/pb_uup_ext"
                        rm -rf "${'$'}EXTDIR"
                        mkdir -p "${'$'}EXTDIR"
                        set +e
                        $qCab -q -d "${'$'}EXTDIR" $qOneCab
                        CAB_RC=${'$'}?
                        set -e
                        if [ -z "${'$'}(ls -A "${'$'}EXTDIR" 2>/dev/null)" ]; then
                            echo "PB_SKIP_NON_MSCF (rc=${'$'}CAB_RC)"
                            rm -rf "${'$'}EXTDIR"
                            exit 0
                        fi
                        $qWim capture "${'$'}EXTDIR" "${'$'}TMP/${'$'}BN.esd" "Edition Package" "Edition Package" --compress=none --no-acls --norpfix
                        rm -rf "${'$'}EXTDIR"
                        """.trimIndent()
                    )
                    val joinedOut = (oneCabRes.out + oneCabRes.err).joinToString(" ")
                    if (!oneCabRes.isSuccess) {
                        val err = (oneCabRes.out + oneCabRes.err).filter { it.isNotBlank() }.takeLast(5).joinToString(" | ").take(280)
                        emitLog("win-iso", "CAB convert failed on $cabName (code ${oneCabRes.code}): $err", true)
                        throw IllegalStateException("CAB convert failed ($cabName): ${err.ifBlank { "exit code ${oneCabRes.code}" }}")
                    }
                    if (joinedOut.contains("PB_SKIP_NON_MSCF")) {
                        emitLog("win-iso", "Skipped non-MSCF cabinet: $cabName")
                    } else if (!joinedOut.contains("PB_SKIP_EMPTY")) {
                        convertedCabs++
                    }
                }
                emitLog("win-iso", "Step 1/4 complete: converted $convertedCabs CAB reference ESD(s)")

                // STEP 2/4: Detect edition metadata ESDs & assemble dual-index boot.wim (exact UUPdump convert.sh flow)
                updateConv("Step 2/4 · Building boot.wim...", "Inspecting editions & applying WinPE layout", 0.63f)
                val metaListRes = runIsolatedRootScript(
                    appCtx,
                    "meta",
                    """
                    $pathHeader
                    for f in "${'$'}PKG"/*.esd "${'$'}PKG"/*.ESD; do
                        [ -f "${'$'}f" ] || continue
                        cnt=${'$'}($qWim info "${'$'}f" 2>/dev/null | grep -c '^Index:' || true)
                        if [ "${'$'}cnt" -ge 3 ]; then
                            echo "${'$'}f"
                        fi
                    done
                    """.trimIndent()
                )
                val metaList = metaListRes.out.map { it.trim() }.filter { it.startsWith("/") && it.lowercase().endsWith(".esd") }

                if (metaList.isEmpty()) {
                    throw IllegalStateException("No Windows edition metadata ESD (3+ indexes) found in downloaded packages.")
                }
                val firstMeta = shellQuote(metaList.first())

                val bootWimRes = runIsolatedRootScript(
                    appCtx,
                    "bootwim",
                    """
                    set -e
                    $pathHeader
                    $qWim apply $firstMeta 1 "${'$'}ISODIR" --no-acls --no-attributes
                    mkdir -p "${'$'}ISODIR/sources"
                    $qWim export $firstMeta 2 "${'$'}TMP/winre.wim" --compress=maximum --boot
                    cp -f "${'$'}TMP/winre.wim" "${'$'}ISODIR/sources/boot.wim"
                    $qWim info "${'$'}ISODIR/sources/boot.wim" 1 "Microsoft Windows PE" "Microsoft Windows PE"
                    $qWim info "${'$'}ISODIR/sources/boot.wim" 1 --image-property FLAGS=9

                    # Exact UUPdump WinPE SOFTWARE hive patch on native Linux storage
                    HIVEDIR="/data/local/tmp/pb_uup_hive"
                    rm -rf "${'$'}HIVEDIR"
                    mkdir -p "${'$'}HIVEDIR"
                    $qWim extract "${'$'}ISODIR/sources/boot.wim" 1 "/Windows/System32/config/SOFTWARE" --dest-dir="${'$'}HIVEDIR" --no-acls --no-attributes >/dev/null 2>&1 || \
                      $qWim extract "${'$'}ISODIR/sources/boot.wim" 1 "/windows/system32/config/software" --dest-dir="${'$'}HIVEDIR" --no-acls --no-attributes >/dev/null 2>&1 || true
                    HIVE_FILE=${'$'}(find "${'$'}HIVEDIR" -maxdepth 1 -type f -iname 'software' | head -n 1)
                    if [ -n "${'$'}HIVE_FILE" ]; then
                        set +e
                        printf 'cd Microsoft\\Windows NT\\CurrentVersion\nnv 1 SystemRoot\ned SystemRoot\nX:\\${'$'}Windows.~bt\\Windows\ncd WinPE\nnv 1 InstRoot\ned InstRoot\nX:\\${'$'}Windows.~bt\nq\n' | $qChntpw -C -e "${'$'}HIVE_FILE"
                        REG_RC=${'$'}?
                        set -e
                        if [ "${'$'}REG_RC" != "0" ] && [ "${'$'}REG_RC" != "2" ]; then
                            echo "Warning: reged exited with ${'$'}REG_RC"
                        fi
                        $qWim update "${'$'}ISODIR/sources/boot.wim" 1 --command="add '${'$'}HIVE_FILE' /Windows/System32/config/SOFTWARE"
                    fi
                    rm -rf "${'$'}HIVEDIR"

                    BG_FILE=""
                    for bg in "${'$'}ISODIR/sources/background_cli.bmp" "${'$'}ISODIR/sources/background_svr.bmp" "${'$'}ISODIR/sources/background_cli.png"; do
                        [ -f "${'$'}bg" ] && BG_FILE="${'$'}bg" && break
                    done
                    if [ -n "${'$'}BG_FILE" ]; then
                        $qWim update "${'$'}ISODIR/sources/boot.wim" 1 --command="add '${'$'}BG_FILE' /Windows/system32/winre.jpg" || true
                    fi
                    $qWim update "${'$'}ISODIR/sources/boot.wim" 1 --command="delete --force /Windows/System32/winpeshl.ini" || true

                    # Index 2: Microsoft Windows Setup
                    $qWim export "${'$'}TMP/winre.wim" 1 "${'$'}ISODIR/sources/boot.wim" "Microsoft Windows Setup" "Microsoft Windows Setup"
                    $qWim info "${'$'}ISODIR/sources/boot.wim" 2 --image-property FLAGS=2
                    $qWim info "${'$'}ISODIR/sources/boot.wim" 2 --boot
                    $qWim extract $firstMeta 3 "/Windows/System32/xmllite.dll" --dest-dir="${'$'}ISODIR/sources" --no-acls --no-attributes >/dev/null 2>&1 || true

                    UPD_TXT="/data/local/tmp/pb_boot_update.txt"
                    : > "${'$'}UPD_TXT"
                    echo "delete --force /Windows/System32/winpeshl.ini" >> "${'$'}UPD_TXT"
                    if [ -f "${'$'}ISODIR/setup.exe" ]; then
                        echo "add '${'$'}ISODIR/setup.exe' /setup.exe" >> "${'$'}UPD_TXT"
                    fi
                    if [ -n "${'$'}BG_FILE" ]; then
                        echo "add '${'$'}BG_FILE' /sources/background.bmp" >> "${'$'}UPD_TXT"
                        echo "add '${'$'}BG_FILE' /Windows/system32/winre.jpg" >> "${'$'}UPD_TXT"
                    fi
                    for sf in "${'$'}ISODIR"/sources/*; do
                        [ -e "${'$'}sf" ] || continue
                        sbn=${'$'}(basename "${'$'}sf")
                        case "${'$'}sbn" in
                            boot.wim|install.wim|install.esd|sxs|SXS|uup|UUP) continue ;;
                        esac
                        echo "add '${'$'}sf' '/sources/${'$'}sbn'" >> "${'$'}UPD_TXT"
                    done
                    $qWim update "${'$'}ISODIR/sources/boot.wim" 2 < "${'$'}UPD_TXT"
                    $qWim optimize "${'$'}ISODIR/sources/boot.wim"
                    rm -f "${'$'}ISODIR/sources/xmllite.dll" "${'$'}TMP/winre.wim" "${'$'}UPD_TXT"
                    """.trimIndent()
                )
                if (!bootWimRes.isSuccess) {
                    val err = (bootWimRes.out + bootWimRes.err).filter { it.isNotBlank() }.takeLast(5).joinToString(" | ").take(280)
                    emitLog("win-iso", "boot.wim assembly failed (code ${bootWimRes.code}): $err", true)
                    throw IllegalStateException("boot.wim assembly failed: ${err.ifBlank { "exit code ${bootWimRes.code}" }}")
                }
                emitLog("win-iso", "Step 2/4 complete: Assembled boot.wim (WinPE + Setup)")

                // STEP 3/4: Export Windows editions into install.wim / install.esd with live percentage polling
                val installPath = "${isoDir.absolutePath}/sources/install.${config.compression.ext}"
                val qInstallFile = shellQuote(installPath)
                val wimProgLog = "/data/local/tmp/pb_wim_prog.log"
                val qProgLog = shellQuote(wimProgLog)

                metaList.forEachIndexed { idx, metaPath ->
                    if (cancelledFlag.get()) return@launch
                    val sliceBase = 0.68f + (idx.toFloat() / metaList.size.toFloat()) * 0.20f
                    val sliceSpan = 0.20f / metaList.size.toFloat()
                    updateConv(
                        "Step 3/4 · Compressing install.${config.compression.ext} (${idx + 1}/${metaList.size})...",
                        "${config.compression.label} · 0%",
                        sliceBase
                    )

                    val wimPollJob = launch {
                        val pctRegex = Regex("""(\d{1,3})%""")
                        while (isActive && !cancelledFlag.get()) {
                            delay(800L)
                            val tail = Shell.cmd("tail -c 256 $qProgLog 2>/dev/null || true").exec().out.joinToString(" ")
                            val lastPct = pctRegex.findAll(tail).lastOrNull()?.groupValues?.get(1)?.toIntOrNull()
                            if (lastPct != null && lastPct in 0..100) {
                                updateConv(
                                    "Step 3/4 · Compressing install.${config.compression.ext} (${idx + 1}/${metaList.size})...",
                                    "${config.compression.label} · $lastPct%",
                                    sliceBase + (lastPct / 100f) * sliceSpan
                                )
                            }
                        }
                    }

                    val qMeta = shellQuote(metaPath)
                    val expRes = runIsolatedRootScript(
                        appCtx,
                        "wimexp",
                        """
                        set -e
                        $pathHeader
                        : > $qProgLog
                        ED_NAME=${'$'}($qWim info $qMeta 3 2>/dev/null | sed -n 's/^Display Name:[[:space:]]*//p; s/^Name:[[:space:]]*//p' | head -n 1)
                        [ -z "${'$'}ED_NAME" ] && ED_NAME="Windows ${config.build.build}"
                        set --
                        for ref in "${'$'}PKG"/*.esd "${'$'}PKG"/*.ESD "${'$'}TMP"/*.esd; do
                            [ -f "${'$'}ref" ] && set -- "${'$'}@" --ref="${'$'}ref"
                        done
                        $qWim export $qMeta 3 $qInstallFile "${'$'}ED_NAME" ${config.compression.wimlibArg} "${'$'}@" > $qProgLog 2>&1
                        """.trimIndent()
                    )
                    wimPollJob.cancel()
                    if (!expRes.isSuccess) {
                        val err = Shell.cmd("tail -n 6 $qProgLog 2>/dev/null || true").exec().out
                            .filter { it.isNotBlank() }.joinToString(" | ").take(280)
                        Shell.cmd("rm -f $qProgLog 2>/dev/null || true").exec()
                        emitLog("win-iso", "install.${config.compression.ext} export failed: $err", true)
                        throw IllegalStateException("install.${config.compression.ext} export failed: ${err.ifBlank { "exit code ${expRes.code}" }}")
                    }
                    Shell.cmd("rm -f $qProgLog 2>/dev/null || true").exec()
                }
                runIsolatedRootScript(appCtx, "wimopt", "$pathHeader\n$qWim optimize $qInstallFile >/dev/null 2>&1 || true")
                Shell.cmd("rm -rf $qPkg $qTmp /data/local/tmp/pb_uup_ext /data/local/tmp/pb_uup_hive").exec()
                emitLog("win-iso", "Step 3/4 complete: Built install.${config.compression.ext} (${metaList.size} edition(s))")

                val safeTargetDir = UsbGadgetEngine.resolveDirectPath(config.targetDir).trimEnd('/')
                val defaultSlug = "Windows_${config.build.build}_${config.build.archDisplay}_${config.lang.code}"
                    .replace(Regex("[^A-Za-z0-9._-]"), "_")
                val cleanCustom = config.customOutputName.trim()
                    .replace(Regex("[^A-Za-z0-9._ -]"), "_")
                    .trim()

                var finalOutputPath = ""
                var finalFileName = ""
                var injectedMbPath: String? = null

                if (config.outputMode == WinBuildOutputMode.DIRECT_NTFS_USB) {
                    updateConv("Step 4/4 · Building Direct Bootable USB...", "Formatting GPT NTFS + UEFI Boot & Bypasses", 0.90f)
                    finalFileName = if (cleanCustom.isNotEmpty()) {
                        if (cleanCustom.endsWith(".img", true)) cleanCustom else "$cleanCustom.img"
                    } else {
                        "${defaultSlug}_USB.img"
                    }
                    finalOutputPath = "$safeTargetDir/$finalFileName"
                    val usbErr = buildDirectNtfsUsbFromDir(appCtx, isoDir.absolutePath, finalOutputPath, config)
                    if (usbErr != null) {
                        emitLog("win-iso", "Direct USB build failed: $usbErr", true)
                        throw IllegalStateException(usbErr)
                    }
                } else {
                    val qXor = shellQuote(tools.xorriso!!)
                    finalFileName = if (cleanCustom.isNotEmpty()) {
                        if (cleanCustom.endsWith(".iso", true)) cleanCustom else "$cleanCustom.iso"
                    } else {
                        "$defaultSlug.iso"
                    }
                    finalOutputPath = "$safeTargetDir/$finalFileName"
                    val qOutIso = shellQuote(finalOutputPath)
                    val volId = "WIN_${config.build.build.substringBefore('.')}".take(28).uppercase()

                    val estIsoBytes = Shell.cmd("du -sb $qIsoDir 2>/dev/null | awk '{print \$1}'").exec()
                        .out.firstOrNull()?.trim()?.toLongOrNull()?.coerceAtLeast(1L) ?: (5L * 1024L * 1024L * 1024L)
                    updateConv("Step 4/4 · Packing bootable ISO...", finalFileName, 0.90f)

                    val isoPollJob = launch {
                        while (isActive && !cancelledFlag.get()) {
                            delay(700L)
                            val curSz = Shell.cmd("stat -c '%s' $qOutIso 2>/dev/null || echo 0")
                                .exec().out.firstOrNull()?.trim()?.toLongOrNull() ?: 0L
                            if (curSz > 0L) {
                                val f = (curSz.toFloat() / estIsoBytes.toFloat()).coerceIn(0f, 1f)
                                updateConv(
                                    "Step 4/4 · Packing bootable ISO...",
                                    "${UsbGadgetEngine.formatBytes(curSz)} / ~${UsbGadgetEngine.formatBytes(estIsoBytes)}",
                                    0.90f + f * 0.06f
                                )
                            }
                        }
                    }

                    val xorRes = runIsolatedRootScript(
                        appCtx,
                        "xorriso",
                        """
                        set -e
                        $pathHeader
                        mkdir -p ${shellQuote(safeTargetDir)}
                        rm -f $qOutIso
                        EFI_BOOT=""
                        if [ -f "${'$'}ISODIR/efi/microsoft/boot/efisys.bin" ]; then
                            EFI_BOOT="efi/microsoft/boot/efisys.bin"
                        elif [ -f "${'$'}ISODIR/EFI/Microsoft/Boot/efisys.bin" ]; then
                            EFI_BOOT="EFI/Microsoft/Boot/efisys.bin"
                        fi
                        BIOS_BOOT=""
                        if [ -f "${'$'}ISODIR/boot/etfsboot.com" ]; then
                            BIOS_BOOT="boot/etfsboot.com"
                        fi

                        if [ -n "${'$'}BIOS_BOOT" ] && [ -n "${'$'}EFI_BOOT" ]; then
                            $qXor -as mkisofs -iso-level 3 -full-iso9660-filenames -volid "$volId" -udf \
                              -b "${'$'}BIOS_BOOT" -no-emul-boot -boot-load-size 8 -boot-info-table \
                              -eltorito-alt-boot -e "${'$'}EFI_BOOT" -no-emul-boot \
                              -o $qOutIso "${'$'}ISODIR"
                        elif [ -n "${'$'}EFI_BOOT" ]; then
                            $qXor -as mkisofs -iso-level 3 -full-iso9660-filenames -volid "$volId" -udf \
                              -e "${'$'}EFI_BOOT" -no-emul-boot \
                              -o $qOutIso "${'$'}ISODIR"
                        else
                            $qXor -as mkisofs -iso-level 3 -full-iso9660-filenames -volid "$volId" -udf \
                              -o $qOutIso "${'$'}ISODIR"
                        fi
                        """.trimIndent()
                    )
                    isoPollJob.cancel()
                    if (!xorRes.isSuccess) {
                        val err = (xorRes.out + xorRes.err).filter { it.isNotBlank() }.takeLast(5).joinToString(" | ").take(280)
                        emitLog("win-iso", "ISO packing failed (code ${xorRes.code}): $err", true)
                        throw IllegalStateException("ISO packing failed: ${err.ifBlank { "exit code ${xorRes.code}" }}")
                    }

                    UsbGadgetEngine.fixUserStoragePermissions(appCtx, safeTargetDir, isDir = true)
                    UsbGadgetEngine.fixUserStoragePermissions(appCtx, finalOutputPath, isDir = false)

                    if (config.outputMode == WinBuildOutputMode.DIRECT_MULTIBOOT && config.targetMultiBootPath.isNotBlank()) {
                        val mbPath = UsbGadgetEngine.resolveDirectPath(config.targetMultiBootPath)
                        updateConv("Moving ISO into Multi-Boot...", mbPath.substringAfterLast('/'), 0.97f)
                        val addRes = VentoyImageBuilder.addIsoToVentoy(
                            context = appCtx,
                            ventoyImgPath = mbPath,
                            sourceIsoPath = finalOutputPath,
                            moveFile = true,
                            onProgress = { p -> updateConv("Moving into Multi-Boot...", p.detail.ifBlank { p.stage }, 0.98f) }
                        )
                        if (addRes.success) {
                            injectedMbPath = mbPath
                        }
                    }
                }

                UsbGadgetEngine.cleanupTempWorkDir(workRootPath)
                runCatching { appStageDir.deleteRecursively() }
                activeWorkRootPath = ""
                activeWorkPrefix = ""
                _jobState.value = null
                TurboDownloadService.stop(appCtx)

                emitLog("win-iso", "Completed build: $finalFileName")
                val completion = DownloadCompletionEvent(
                    title = jobTitle,
                    fileName = finalFileName,
                    savedPath = finalOutputPath,
                    injectedMultiBootPath = injectedMbPath,
                    computedSha256 = null,
                    expectedSha256 = null,
                    checksumStatus = ChecksumVerifyStatus.NONE,
                    autoMount = autoMountLive
                )
                FastIsoDownloader.publishExternalCompletion(completion)
                NotificationHelper.showDownloadComplete(appCtx, jobTitle, "Built $finalFileName")
                onComplete(completion)
            } catch (_: CancellationException) {
                if (!pausedFlag.get()) {
                    _jobState.value = null
                    TurboDownloadService.stop(appCtx)
                }
            } catch (e: Exception) {
                Shell.cmd("rm -rf ${shellQuote(isoDir.absolutePath)} ${shellQuote(tmpDir.absolutePath)} 2>/dev/null || true").exec()
                _jobState.value = null
                TurboDownloadService.stop(appCtx)
                val msg = "Windows build failed: ${e.message ?: "Unexpected error"}"
                emitLog("win-iso", msg, true)
                onError(msg)
            }
        }
    }

    private fun buildDirectNtfsUsbFromDir(
        context: Context,
        isoDirPath: String,
        outImgPath: String,
        config: WindowsChannelBuildConfig
    ): String? {
        val ntfsFormatter = WindowsImageBuilder.findBundledBinary(context, "libmkfs_ntfs.so", listOf("mkfs.ntfs", "mkntfs"))
            ?: return "Bundled mkfs.ntfs not found."
        val ntfs3g = WindowsImageBuilder.findBundledBinary(context, "libntfs_3g.so", listOf("ntfs-3g"))
        val uefiBytes = runCatching { context.assets.open("uefi-ntfs.img").use { it.readBytes() } }.getOrNull()
            ?: return "Missing uefi-ntfs.img asset."

        val qSrcDir = shellQuote(isoDirPath)
        val qOut = shellQuote(outImgPath)
        val dirBytes = Shell.cmd("du -sb $qSrcDir 2>/dev/null | awk '{print \$1}'").exec()
            .out.firstOrNull()?.trim()?.toLongOrNull() ?: (6L * 1024L * 1024L * 1024L)
        val extraDrvBytes = if (config.extraDriversDir.isNotBlank()) {
            val qDrv = shellQuote(UsbGadgetEngine.resolveDirectPath(config.extraDriversDir))
            Shell.cmd("[ -d $qDrv ] && du -sb $qDrv 2>/dev/null | awk '{print \$1}' || echo 0")
                .exec().out.firstOrNull()?.trim()?.toLongOrNull() ?: 0L
        } else 0L

        val layout = DiskPartitioner.calculateLayout(dirBytes + extraDrvBytes, uefiBytes.size.toLong())
        val mntDir = "/data/local/tmp/pb_winbuild_usb_mnt"
        var loopDev = ""
        return try {
            val safeDir = outImgPath.substringBeforeLast('/')
            Shell.cmd("mkdir -p ${shellQuote(safeDir)} && rm -f $qOut && truncate -s ${layout.totalBytes} $qOut").exec()

            val gpt = DiskPartitioner.buildGptBuffers(layout, includeUefiPart = true)
            val fPrim = File(context.cacheDir, "wb_gpt_p.bin").apply { writeBytes(gpt.primarySectors) }
            val fBack = File(context.cacheDir, "wb_gpt_b.bin").apply { writeBytes(gpt.backupSectors) }
            val fUefi = File(context.cacheDir, "wb_uefi.bin").apply { writeBytes(uefiBytes) }
            try {
                val pRes = Shell.cmd(
                    "dd if=${shellQuote(fPrim.absolutePath)} of=$qOut bs=512 seek=0 count=34 conv=notrunc 2>&1 && " +
                        "dd if=${shellQuote(fBack.absolutePath)} of=$qOut bs=512 seek=${layout.totalSectors - 33L} count=33 conv=notrunc 2>&1 && " +
                        "dd if=${shellQuote(fUefi.absolutePath)} of=$qOut bs=512 seek=${layout.part2StartLba} conv=notrunc 2>&1"
                ).exec()
                if (!pRes.isSuccess) return "GPT write failed"
            } finally {
                fPrim.delete(); fBack.delete(); fUefi.delete()
            }

            val loopCmd = """
                OUT_DEV=""
                for idx in ${'$'}(seq 0 63); do
                    CAND="/dev/block/loop${'$'}idx"
                    [ -b "${'$'}CAND" ] || CAND="/dev/loop${'$'}idx"
                    [ -b "${'$'}CAND" ] || continue
                    if losetup "${'$'}CAND" 2>/dev/null | grep -q '/'; then continue; fi
                    if losetup -o ${layout.part1OffsetBytes} -S ${layout.part1SizeBytes} "${'$'}CAND" $qOut 2>/dev/null ||                        losetup -o ${layout.part1OffsetBytes} --sizelimit ${layout.part1SizeBytes} "${'$'}CAND" $qOut 2>/dev/null; then
                        OUT_DEV="${'$'}CAND"
                        break
                    fi
                done
                if [ -z "${'$'}OUT_DEV" ]; then
                    OUT_DEV=${'$'}(losetup -f -s -o ${layout.part1OffsetBytes} -S ${layout.part1SizeBytes} $qOut 2>/dev/null ||                       losetup -f --show -o ${layout.part1OffsetBytes} --sizelimit ${layout.part1SizeBytes} $qOut 2>/dev/null || true)
                fi
                echo "${'$'}OUT_DEV"
            """.trimIndent()
            loopDev = Shell.cmd(loopCmd).exec().out.lastOrNull().orEmpty().trim()
            if (loopDev.isBlank()) return "Loop attach failed (all loop devices busy or losetup offset unsupported)"

            val qLoop = shellQuote(loopDev)
            val fmtRes = Shell.cmd("${shellQuote(ntfsFormatter)} -F -Q -L POCKETBOOT -H 255 -S 63 -p ${layout.part1StartLba} $qLoop 2>&1").exec()
            if (!fmtRes.isSuccess) return "NTFS format failed"

            val procFs = Shell.cmd("cat /proc/filesystems 2>/dev/null").exec().out.joinToString(" ").lowercase()
            val mntCmd = buildList {
                if (procFs.contains("ntfs3")) add("mount -t ntfs3 -o rw $qLoop $mntDir 2>&1")
                if (ntfs3g != null) add("${shellQuote(ntfs3g)} -o rw,big_writes,noatime $qLoop $mntDir 2>&1")
            }.joinToString(" || ")
            val mntRes = Shell.cmd("mkdir -p $mntDir && ($mntCmd)").exec()
            if (!mntRes.isSuccess) return "NTFS mount failed"

            val cpRes = Shell.cmd("cp -rf $qSrcDir/. $mntDir/ 2>&1").exec()
            if (!cpRes.isSuccess) return "File copy into NTFS USB failed: ${cpRes.out.takeLast(3).joinToString(" ").take(180)}"

            val anyBypass = config.bypassRequirements || config.bypassMicrosoftAccount ||
                config.disableDataCollection || config.disableBitLocker
            if (anyBypass) {
                val xml = WindowsImageBuilder.generateAutounattendXml(
                    WindowsBuildConfig(
                        isoFile = ImageFile(outImgPath, outImgPath.substringAfterLast('/'), dirBytes, ""),
                        outputDir = safeDir,
                        outputName = outImgPath.substringAfterLast('/'),
                        bypassRequirements = config.bypassRequirements,
                        bypassMicrosoftAccount = config.bypassMicrosoftAccount,
                        disableDataCollection = config.disableDataCollection,
                        disableBitLocker = config.disableBitLocker
                    )
                )
                val tmpXml = File(context.cacheDir, "wb_unattend.xml").apply { writeText(xml, Charsets.UTF_8) }
                val oemPanther = "$mntDir/sources/\$OEM\$/\$\$/Panther"
                Shell.cmd(
                    "cp ${shellQuote(tmpXml.absolutePath)} $mntDir/autounattend.xml && " +
                        "mkdir -p ${shellQuote(oemPanther)} && " +
                        "cp ${shellQuote(tmpXml.absolutePath)} ${shellQuote("$oemPanther/unattend.xml")}"
                ).exec()
                tmpXml.delete()
            }

            if (config.extraDriversDir.isNotBlank()) {
                val drvSrc = UsbGadgetEngine.resolveDirectPath(config.extraDriversDir)
                val qDrv = shellQuote(drvSrc)
                Shell.cmd("if [ -d $qDrv ]; then mkdir -p $mntDir/Drivers && cp -rf $qDrv/. $mntDir/Drivers/ 2>&1; fi").exec()
            }

            UsbGadgetEngine.fixUserStoragePermissions(context, safeDir, isDir = true)
            UsbGadgetEngine.fixUserStoragePermissions(context, outImgPath, isDir = false)
            null
        } finally {
            Shell.cmd("sync; umount $mntDir 2>/dev/null || umount -l $mntDir 2>/dev/null; sleep 1; [ -n ${shellQuote(loopDev)} ] && losetup -d ${shellQuote(loopDev)} 2>/dev/null; rm -rf $mntDir").exec()
        }
    }
}
