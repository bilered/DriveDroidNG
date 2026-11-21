package com.pocketboot.app

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager as WebCookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.net.CookieHandler
import java.net.CookieManager
import java.net.CookiePolicy
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume

enum class WindowsCatalogFamily(
    val id: String,
    val title: String,
    val subtitle: String,
    val badgeLabel: String,
    val sizeHint: String,
    val supportsDirectRetail: Boolean,
    val defaultBuilderFamily: WinFamilyFilter
) {
    WIN11(
        id = "win11",
        title = "Windows 11",
        subtitle = "Latest Retail ISO (26H2 / 25H2) & Channel Builds",
        badgeLabel = "Direct ISO · All Channels",
        sizeHint = "~5.5–7 GB",
        supportsDirectRetail = true,
        defaultBuilderFamily = WinFamilyFilter.WIN11
    ),
    WIN10(
        id = "win10",
        title = "Windows 10",
        subtitle = "Official 22H2 Retail ISO & Channel Builds",
        badgeLabel = "Direct ISO · All Channels",
        sizeHint = "~4.8–6 GB",
        supportsDirectRetail = true,
        defaultBuilderFamily = WinFamilyFilter.WIN10
    ),
    ALL_BUILDS(
        id = "all_builds",
        title = "Windows Builds & Server",
        subtitle = "Windows 11, 10 & Server · Stable, Preview & Insider",
        badgeLabel = "Custom ISO Builder",
        sizeHint = "On-Phone Build",
        supportsDirectRetail = false,
        defaultBuilderFamily = WinFamilyFilter.WIN11
    )
}

data class DirectWindowsRelease(
    val osFamily: String,
    val releaseLabel: String,
    val editionLabel: String,
    val editionId: Int,
    val isArm64Only: Boolean = false
)

/**
 * Groups x64 and ARM64 edition IDs under one clean release pill (e.g. "26H2", "25H2", "24H2").
 */
data class DirectReleaseGroup(
    val osFamily: String,
    val releaseLabel: String,
    val shortChipLabel: String,
    val x64Release: DirectWindowsRelease?,
    val arm64Release: DirectWindowsRelease?
) {
    val defaultRelease: DirectWindowsRelease
        get() = x64Release ?: arm64Release!!
}

data class DirectLanguageSku(
    val skuId: String,
    val language: String,
    val localizedLanguage: String
)

data class DirectIsoLink(
    val arch: String,
    val fileName: String,
    val url: String
)

data class DirectApiResult<T>(
    val data: T? = null,
    val errorMessage: String? = null,
    val isRateLimited: Boolean = false
)

object WindowsDirectCatalog {

    private const val UPSTREAM_CATALOG_URL = "https://raw.githubusercontent.com/pbatard/Fido/master/Fido.ps1"
    private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/136.0.0.0 Safari/537.36"

    @Volatile private var profileId = "606624d44113"
    @Volatile private var mdtInstanceId = "560dc9f3-1aa5-4a2f-b63c-9e18f8d0e175"
    @Volatile private var orgId = "y6jn8c31"
    @Volatile private var mdtCustomerId = "71828191-7845-4120-8833-4a943b6b3b21"

    private val releasesCache = ConcurrentHashMap<String, List<DirectWindowsRelease>>()
    private val skuCache = ConcurrentHashMap<String, Pair<String, List<DirectLanguageSku>>>()

    private val fallbackReleases = listOf(
        DirectWindowsRelease("win11", "26H2 (Latest Retail)", "Windows 11 Home/Pro/Edu", 3813, isArm64Only = false),
        DirectWindowsRelease("win11", "26H2 (Latest Retail)", "Windows 11 Home/Pro/Edu (ARM64)", 3816, isArm64Only = true),
        DirectWindowsRelease("win11", "25H2 (Build 26200)", "Windows 11 Home/Pro/Edu", 3262, isArm64Only = false),
        DirectWindowsRelease("win11", "25H2 (Build 26200)", "Windows 11 Home/Pro/Edu (ARM64)", 3263, isArm64Only = true),
        DirectWindowsRelease("win11", "24H2 (Build 26100)", "Windows 11 Home/Pro/Edu", 3113, isArm64Only = false),
        DirectWindowsRelease("win11", "24H2 (Build 26100)", "Windows 11 Home/Pro/Edu (ARM64)", 3131, isArm64Only = true),
        DirectWindowsRelease("win10", "22H2 v1 (Build 19045)", "Windows 10 Home/Pro/Edu", 2618, isArm64Only = false)
    )

    private fun ensureCookieManager() {
        if (CookieHandler.getDefault() == null) {
            CookieHandler.setDefault(CookieManager(null, CookiePolicy.ACCEPT_ALL))
        }
    }

    private fun httpGet(
        urlStr: String,
        referer: String? = null,
        acceptJson: Boolean = false,
        followRedirects: Boolean = true
    ): Pair<Int, String> {
        ensureCookieManager()
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 12_000
            readTimeout = 18_000
            instanceFollowRedirects = followRedirects
            setRequestProperty("User-Agent", UA)
            setRequestProperty("Accept-Language", "en-US,en;q=0.9")
            setRequestProperty("Accept", if (acceptJson) "application/json, text/plain, */*" else "*/*")
            if (!referer.isNullOrBlank()) {
                setRequestProperty("Referer", referer)
                setRequestProperty("Origin", "https://www.microsoft.com")
            }
        }
        return try {
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            code to body
        } finally {
            conn.disconnect()
        }
    }

    suspend fun fetchReleases(family: WindowsCatalogFamily, forceRefresh: Boolean = false): List<DirectWindowsRelease> =
        withContext(Dispatchers.IO) {
            val famKey = if (family == WindowsCatalogFamily.WIN11) "win11" else "win10"
            if (!forceRefresh) {
                releasesCache[famKey]?.takeIf { it.isNotEmpty() }?.let { return@withContext it }
            }

            val parsed = runCatching {
                val (code, script) = httpGet(UPSTREAM_CATALOG_URL)
                if (code !in 200..299 || script.isBlank()) emptyList() else {
                    extractProtocolConstants(script)
                    parseReleaseTable(script)
                }
            }.getOrDefault(emptyList())

            val forFamily = parsed.filter { it.osFamily == famKey }
                .ifEmpty { fallbackReleases.filter { it.osFamily == famKey } }
                .distinctBy { it.editionId }
                .sortedWith(compareBy<DirectWindowsRelease> { it.isArm64Only }.thenByDescending { it.editionId })

            if (forFamily.isNotEmpty()) {
                releasesCache[famKey] = forFamily
            }
            forFamily
        }

    fun groupReleases(releases: List<DirectWindowsRelease>): List<DirectReleaseGroup> {
        val orderedKeys = LinkedHashMap<String, Pair<DirectWindowsRelease?, DirectWindowsRelease?>>()
        releases.sortedByDescending { it.editionId }.forEach { rel ->
            val key = extractShortReleaseKey(rel.releaseLabel)
            val existing = orderedKeys[key]
            if (existing == null) {
                orderedKeys[key] = if (rel.isArm64Only) (null to rel) else (rel to null)
            } else {
                val x64 = existing.first ?: if (!rel.isArm64Only) rel else null
                val arm = existing.second ?: if (rel.isArm64Only) rel else null
                orderedKeys[key] = x64 to arm
            }
        }
        return orderedKeys.entries.map { (shortKey, pair) ->
            val primary = pair.first ?: pair.second!!
            DirectReleaseGroup(
                osFamily = primary.osFamily,
                releaseLabel = primary.releaseLabel,
                shortChipLabel = shortKey,
                x64Release = pair.first,
                arm64Release = pair.second
            )
        }
    }

    private fun extractShortReleaseKey(releaseLabel: String): String {
        val m = Regex("""\b(2[0-9]H[12](?:\s+v\d+)?)\b""", RegexOption.IGNORE_CASE).find(releaseLabel)
        if (m != null) return m.groupValues[1].uppercase()
        return releaseLabel.substringBefore('(').trim().take(18).ifBlank { "Latest" }
    }

    private fun extractProtocolConstants(script: String) {
        Regex("""\${'$'}ProfileId\s*=\s*"([0-9a-fA-F-]{8,40})"""").find(script)?.groupValues?.get(1)?.let {
            if (it.isNotBlank()) profileId = it
        }
        Regex("""instanceId=([0-9a-fA-F-]{16,40})""").find(script)?.groupValues?.get(1)?.let {
            if (it.isNotBlank()) mdtInstanceId = it
        }
        Regex("""org_id=([a-zA-Z0-9]{4,16})""").find(script)?.groupValues?.get(1)?.let {
            if (it.isNotBlank()) orgId = it
        }
        Regex("""CustomerId=([0-9a-fA-F-]{16,40})""").find(script)?.groupValues?.get(1)?.let {
            if (it.isNotBlank()) mdtCustomerId = it
        }
    }

    /**
     * Parses upstream `$WindowsVersions` PowerShell table accurately:
     * - Detects Windows 11 vs Windows 10 headers even with `windows10ISO` or short tags
     * - Supports BOTH array-tuple edition rows `@("Windows 11 Home/Pro/Edu", @(3813, 3816))`
     *   (where element 0 = x64 and element 1 = ARM64) AND single-ID rows `@("Windows 10 Home/Pro/Edu", 2618)`
     * - Prevents Win 10 edition IDs from ever leaking into Win 11.
     */
    private fun parseReleaseTable(script: String): List<DirectWindowsRelease> {
        val out = mutableListOf<DirectWindowsRelease>()
        val startIdx = script.indexOf("\$WindowsVersions")
        if (startIdx < 0) return emptyList()
        val body = script.substring(startIdx).substringBefore("\nfunction ").take(60_000)

        var currentOs = ""
        var currentRelease = ""

        val osWin11Regex = Regex("""@\(\s*"Windows\s+11"\s*,""", RegexOption.IGNORE_CASE)
        val osWin10Regex = Regex("""@\(\s*"Windows\s+10"\s*,""", RegexOption.IGNORE_CASE)
        val osOtherRegex = Regex("""@\(\s*"(?:Windows\s+8|Windows\s+7|UEFI\s+Shell)[^"]*"\s*,""", RegexOption.IGNORE_CASE)

        val releaseRegex = Regex("""@\(\s*"([^"]*(?:2[0-9]H[12]|v\d|Build\s+\d+)[^"]*)"\s*,""", RegexOption.IGNORE_CASE)
        // Tuple form: @("Windows 11 Home/Pro/Edu", @(3813, 3816))
        val tupleEditionRegex = Regex("""@\(\s*"([^"]+)"\s*,\s*@\(\s*(\d{3,5})\s*(?:,\s*(\d{3,5}))?\s*\)\s*\)""")
        // Single form: @("Windows 10 Home/Pro/Edu", 2618)
        val singleEditionRegex = Regex("""@\(\s*"([^"]+)"\s*,\s*(\d{3,5})\s*\)""")

        body.lineSequence().forEach { rawLine ->
            val line = rawLine.trim()
            if (line.startsWith("#")) return@forEach

            if (osWin11Regex.containsMatchIn(line)) {
                currentOs = "win11"
                currentRelease = ""
                return@forEach
            }
            if (osWin10Regex.containsMatchIn(line)) {
                currentOs = "win10"
                currentRelease = ""
                return@forEach
            }
            if (osOtherRegex.containsMatchIn(line)) {
                currentOs = ""
                currentRelease = ""
                return@forEach
            }
            if (currentOs.isEmpty()) return@forEach

            // Check tuple edition format FIRST (Windows 11 x64 + ARM64 pairs)
            tupleEditionRegex.find(line)?.let { m ->
                val edLabel = m.groupValues[1].trim()
                if (!edLabel.contains("China", true)) {
                    val x64Id = m.groupValues[2].toIntOrNull()
                    val armId = m.groupValues[3].toIntOrNull()
                    val rel = currentRelease.ifBlank { "Latest Retail" }
                    if (x64Id != null) {
                        out.add(
                            DirectWindowsRelease(
                                osFamily = currentOs,
                                releaseLabel = rel,
                                editionLabel = edLabel,
                                editionId = x64Id,
                                isArm64Only = false
                            )
                        )
                    }
                    if (armId != null && armId != x64Id) {
                        out.add(
                            DirectWindowsRelease(
                                osFamily = currentOs,
                                releaseLabel = rel,
                                editionLabel = "$edLabel (ARM64)",
                                editionId = armId,
                                isArm64Only = true
                            )
                        )
                    }
                }
                return@forEach
            }

            // Check single-integer edition format (Windows 10 or single-arch Win 11)
            singleEditionRegex.find(line)?.let { m ->
                val edLabel = m.groupValues[1].trim()
                val edId = m.groupValues[2].toIntOrNull() ?: return@forEach
                if (!edLabel.contains("China", true)) {
                    // Sanity guard: never allow known Windows 10 IDs (<= 2700) inside win11
                    if (currentOs == "win11" && edId < 2700) return@forEach
                    val isArm = edLabel.contains("arm64", true) || currentRelease.contains("arm64", true)
                    val rel = currentRelease.ifBlank { "Latest Retail" }
                    out.add(
                        DirectWindowsRelease(
                            osFamily = currentOs,
                            releaseLabel = rel,
                            editionLabel = edLabel,
                            editionId = edId,
                            isArm64Only = isArm
                        )
                    )
                }
                return@forEach
            }

            releaseRegex.find(line)?.let { m ->
                val lbl = m.groupValues[1].trim()
                if (!lbl.startsWith("Windows ", true)) {
                    currentRelease = lbl
                }
            }
        }
        return out
    }

    private fun initializeSession(sessionId: String, osFamily: String) {
        val pageSlug = if (osFamily == "win11") "windows11" else "windows10ISO"
        val pageUrl = "https://www.microsoft.com/en-us/software-download/$pageSlug"

        runCatching { httpGet(pageUrl) }
        runCatching {
            httpGet(
                "https://vlscppe.microsoft.com/tags?org_id=$orgId&session_id=$sessionId",
                referer = pageUrl,
                followRedirects = false
            )
        }
        runCatching {
            val mdtUrl = "https://ov-df.microsoft.com/mdt.js?instanceId=$mdtInstanceId&PageId=si&session_id=$sessionId"
            val (_, jsBody) = httpGet(mdtUrl, referer = pageUrl)
            val wVal = Regex("""[?&]w=([^&'"\s]+)""").find(jsBody)?.groupValues?.get(1)
            val custVal = Regex("""CustomerId=([0-9a-fA-F-]{16,40})""").find(jsBody)?.groupValues?.get(1) ?: mdtCustomerId
            val rticks = Regex("""rticks['":=\s]+(\d+)""").find(jsBody)?.groupValues?.get(1)
            val nowMs = System.currentTimeMillis()
            if (!wVal.isNullOrBlank()) {
                val replyUrl = buildString {
                    append("https://ov-df.microsoft.com/?session_id=$sessionId")
                    append("&CustomerId=$custVal&PageId=si")
                    append("&w=${URLEncoder.encode(wVal, "UTF-8")}")
                    append("&mdt=$nowMs")
                    if (!rticks.isNullOrBlank()) append("&rticks=$rticks")
                }
                httpGet(replyUrl, referer = pageUrl)
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun executeViaChromiumSession(
        context: Context,
        osFamily: String,
        apiUrl: String
    ): String? = withTimeoutOrNull(18_000L) {
        suspendCancellableCoroutine { cont ->
            val mainHandler = Handler(Looper.getMainLooper())
            mainHandler.post {
                val pageSlug = if (osFamily == "win11") "windows11" else "windows10ISO"
                val originUrl = "https://www.microsoft.com/en-us/software-download/$pageSlug"
                var finished = false
                var wv: WebView? = null

                fun finish(result: String?) {
                    if (finished) return
                    finished = true
                    mainHandler.post {
                        runCatching {
                            wv?.stopLoading()
                            wv?.destroy()
                        }
                        wv = null
                    }
                    if (cont.isActive) cont.resume(result)
                }

                try {
                    val webView = WebView(context.applicationContext)
                    wv = webView
                    webView.settings.javaScriptEnabled = true
                    webView.settings.domStorageEnabled = true
                    webView.settings.userAgentString = UA
                    WebCookieManager.getInstance().setAcceptCookie(true)
                    WebCookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)

                    webView.addJavascriptInterface(object {
                        @JavascriptInterface
                        fun onApiResult(json: String) {
                            finish(json)
                        }

                        @JavascriptInterface
                        fun onApiError(err: String) {
                            finish(null)
                        }
                    }, "PocketBootBridge")

                    var triggered = false
                    webView.webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView?, url: String?) {
                            if (triggered || view == null) return
                            triggered = true
                            val safeUrl = apiUrl.replace("\\", "\\\\").replace("'", "\\'")
                            val js = """
                                (function(){
                                  setTimeout(function(){
                                    fetch('$safeUrl', { credentials: 'include', headers: { 'Accept': 'application/json' } })
                                      .then(function(r){ return r.text(); })
                                      .then(function(t){ PocketBootBridge.onApiResult(t); })
                                      .catch(function(e){ PocketBootBridge.onApiError(String(e)); });
                                  }, 900);
                                })();
                            """.trimIndent()
                            view.evaluateJavascript(js, null)
                        }
                    }

                    cont.invokeOnCancellation { finish(null) }
                    webView.loadUrl(originUrl)
                } catch (_: Exception) {
                    finish(null)
                }
            }
        }
    }

    private fun parseSkusFromBody(body: String): DirectApiResult<List<DirectLanguageSku>> {
        val root = runCatching { JSONObject(body) }.getOrNull()
            ?: return DirectApiResult(errorMessage = "Invalid response from Microsoft servers.")
        val errors = root.optJSONArray("Errors")
        if (errors != null && errors.length() > 0) {
            val errVal = errors.optJSONObject(0)?.optString("Value", "").orEmpty()
            val blocked = errVal.contains("715-123130") || errVal.contains("automated", true) || errVal.contains("banned", true)
            return DirectApiResult(
                errorMessage = if (blocked) {
                    "Microsoft rate-limited direct retail downloads on this connection. Switch to 'All Channels' for unblocked downloads."
                } else errVal.ifBlank { "Microsoft rejected the language lookup." },
                isRateLimited = blocked
            )
        }
        val arr = root.optJSONArray("Skus") ?: JSONArray()
        val skus = mutableListOf<DirectLanguageSku>()
        for (i in 0 until arr.length()) {
            val s = arr.optJSONObject(i) ?: continue
            val id = s.optString("Id", "").trim()
            val lang = s.optString("Language", "").trim()
            val loc = s.optString("LocalizedLanguage", lang).trim().ifBlank { lang }
            if (id.isNotEmpty() && loc.isNotEmpty()) {
                skus.add(DirectLanguageSku(skuId = id, language = lang, localizedLanguage = loc))
            }
        }
        val sorted = skus.sortedWith(
            compareBy<DirectLanguageSku> { !it.localizedLanguage.contains("English (United States)", true) }
                .thenBy { !it.localizedLanguage.startsWith("English", true) }
                .thenBy { it.localizedLanguage.lowercase() }
        )
        return if (sorted.isEmpty()) {
            DirectApiResult(errorMessage = "No languages returned for this release.", isRateLimited = true)
        } else {
            DirectApiResult(data = sorted)
        }
    }

    private fun parseLinksFromBody(body: String, release: DirectWindowsRelease): DirectApiResult<List<DirectIsoLink>> {
        val root = runCatching { JSONObject(body) }.getOrNull()
            ?: return DirectApiResult(errorMessage = "Invalid download response from Microsoft.")
        val errors = root.optJSONArray("Errors")
        if (errors != null && errors.length() > 0) {
            val errVal = errors.optJSONObject(0)?.optString("Value", "").orEmpty()
            val blocked = errVal.contains("715-123130") || errVal.contains("automated", true) || errVal.contains("validate", true)
            return DirectApiResult(
                errorMessage = if (blocked) {
                    "Microsoft rate-limited direct link signing on this IP. Switch to 'All Channels' below to build directly from Microsoft servers."
                } else errVal.ifBlank { "Could not sign download link." },
                isRateLimited = blocked
            )
        }
        val arr = root.optJSONArray("ProductDownloadOptions") ?: JSONArray()
        val links = mutableListOf<DirectIsoLink>()
        for (i in 0 until arr.length()) {
            val item = arr.optJSONObject(i) ?: continue
            val uri = item.optString("Uri", "").trim()
            if (!uri.startsWith("http", true)) continue
            val fileName = uri.substringAfterLast('/').substringBefore('?').ifBlank { "Windows.iso" }
            val dlType = item.optInt("DownloadType", -1)
            val arch = when {
                release.isArm64Only || fileName.contains("arm64", true) || fileName.contains("a64", true) -> "ARM64"
                dlType == 0 || fileName.contains("x32", true) || fileName.contains("x86", true) -> "x86"
                else -> "x64"
            }
            links.add(DirectIsoLink(arch = arch, fileName = fileName, url = uri))
        }
        val sorted = links.sortedBy {
            when (it.arch) {
                "x64" -> 0
                "ARM64" -> 1
                else -> 2
            }
        }
        return if (sorted.isEmpty()) {
            DirectApiResult(errorMessage = "Microsoft returned no direct links for this language. Try 'All Channels' instead.", isRateLimited = true)
        } else {
            DirectApiResult(data = sorted)
        }
    }

    suspend fun fetchLanguages(
        context: Context,
        release: DirectWindowsRelease,
        forceRefresh: Boolean = false
    ): DirectApiResult<Pair<String, List<DirectLanguageSku>>> = withContext(Dispatchers.IO) {
        val cacheKey = "${release.osFamily}_${release.editionId}"
        if (!forceRefresh) {
            skuCache[cacheKey]?.let { return@withContext DirectApiResult(data = it) }
        }

        val sessionId = UUID.randomUUID().toString()
        val pageSlug = if (release.osFamily == "win11") "windows11" else "windows10ISO"
        val referer = "https://www.microsoft.com/en-us/software-download/$pageSlug"
        val apiUrl = "https://www.microsoft.com/software-download-connector/api/getskuinformationbyproductedition" +
            "?profile=$profileId&productEditionId=${release.editionId}" +
            "&SKU=undefined&friendlyFileName=undefined&Locale=en-US&sessionID=$sessionId"

        runCatching {
            initializeSession(sessionId, release.osFamily)
            val (code, body) = httpGet(apiUrl, referer = referer, acceptJson = true)
            if (code in 200..299 && body.isNotBlank()) {
                val parsed = parseSkusFromBody(body)
                if (parsed.data != null) {
                    val pair = sessionId to parsed.data
                    skuCache[cacheKey] = pair
                    return@withContext DirectApiResult(data = pair)
                }
            }
        }

        val wvBody = executeViaChromiumSession(context, release.osFamily, apiUrl)
        if (!wvBody.isNullOrBlank()) {
            val parsed = parseSkusFromBody(wvBody)
            if (parsed.data != null) {
                val pair = sessionId to parsed.data
                skuCache[cacheKey] = pair
                return@withContext DirectApiResult(data = pair)
            }
            return@withContext DirectApiResult(errorMessage = parsed.errorMessage, isRateLimited = parsed.isRateLimited)
        }

        DirectApiResult(
            errorMessage = "Microsoft blocked direct retail queries on this IP/VPN. Switch to 'All Channels' for unblocked Microsoft builds.",
            isRateLimited = true
        )
    }

    suspend fun fetchDownloadLinks(
        context: Context,
        release: DirectWindowsRelease,
        sessionId: String,
        skuId: String
    ): DirectApiResult<List<DirectIsoLink>> = withContext(Dispatchers.IO) {
        val pageSlug = if (release.osFamily == "win11") "windows11" else "windows10ISO"
        val referer = "https://www.microsoft.com/en-us/software-download/$pageSlug"
        val apiUrl = "https://www.microsoft.com/software-download-connector/api/GetProductDownloadLinksBySku" +
            "?profile=$profileId&productEditionId=undefined&SKU=$skuId" +
            "&friendlyFileName=undefined&Locale=en-US&sessionID=$sessionId"

        runCatching {
            val (code, body) = httpGet(apiUrl, referer = referer, acceptJson = true)
            if (code in 200..299 && body.isNotBlank()) {
                val parsed = parseLinksFromBody(body, release)
                if (parsed.data != null) return@withContext parsed
            }
        }

        val wvBody = executeViaChromiumSession(context, release.osFamily, apiUrl)
        if (!wvBody.isNullOrBlank()) {
            return@withContext parseLinksFromBody(wvBody, release)
        }

        DirectApiResult(
            errorMessage = "Microsoft rate-limited direct link signing. Switch to 'All Channels' to download directly from Windows Update servers.",
            isRateLimited = true
        )
    }
}
