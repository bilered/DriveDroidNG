package com.pocketboot.app

import android.annotation.SuppressLint
import android.content.Context
import android.net.http.SslError
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager as WebCookieManager
import android.webkit.JavascriptInterface
import android.webkit.SslErrorHandler
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import java.net.UnknownHostException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

data class NtriverIsoEntry(
    val language: String,
    val arch: String,
    val fileName: String,
    val sha256: String,
    val directStaticUrl: String? = null,
    val ntriverDriveFilename: String? = null,
    val displayLabel: String = ""
) {
    val isMicrosoftStatic: Boolean get() = !directStaticUrl.isNullOrBlank()
    val effectiveLanguageLabel: String get() = displayLabel.ifBlank { language }
}

data class NtriverReleaseSection(
    val id: String,
    val title: String,
    val shortChipLabel: String,
    val majorVersion: String = "",
    val editionLabel: String = "",
    val buildBadge: String = "",
    val note: String = "",
    val isRecommended: Boolean = true,
    val entries: List<NtriverIsoEntry>
) {
    val effectiveMajorVersion: String get() = majorVersion.ifBlank { shortChipLabel }
    val effectiveEditionLabel: String get() = editionLabel.ifBlank { shortChipLabel }
    val hasMicrosoftStaticLinks: Boolean get() = entries.any { it.isMicrosoftStatic }
    val distinctLanguages: List<String> get() = entries.map { it.language }.distinct()
    val distinctArches: List<String> get() = entries.map { it.arch }.distinct()
}

data class NtriverResolveResult(
    val url: String? = null,
    val errorMessage: String? = null,
    val isRateLimited: Boolean = false,
    val needsWebViewFallback: Boolean = false,
    val isMicrosoftStatic: Boolean = false
)

/**
 * Direct Windows RTM ISO archive client for ntriver.org (CC-BY-4.0) with a 4-tier fail-safe pipeline:
 * 1) Direct HTTPS fetch from `ntriver.org` parsed with a zero-backtracking O(N) linear HTML scanner
 * 2) Hardened headless Chromium WebView DOM fetch (passes Cloudflare challenges & hydrates Docusaurus/React)
 * 3) Zero-rate-limit jsDelivr + GitHub Raw source fallback (`ntriver-org/ntriver-org-site`, supporting relative links & MDX)
 * 4) Full-document + loose ISO-link sweep fallback + real tier-by-tier diagnostic trace in UI & Activity Log
 */
object NtriverCatalog {

    private const val SITE_ORIGIN = "https://ntriver.org"
    private const val INDEX_PAGE_URL = "$SITE_ORIGIN/download-windows-office"
    private const val GENERATE_LINK_API = "$SITE_ORIGIN/api/drive/generate-link"
    private const val JSDELIVR_TREE_MAIN = "https://data.jsdelivr.com/v1/packages/gh/ntriver-org/ntriver-org-site@main?structure=flat"
    private const val JSDELIVR_TREE_MASTER = "https://data.jsdelivr.com/v1/packages/gh/ntriver-org/ntriver-org-site@master?structure=flat"
    private const val JSDELIVR_RAW_BASE = "https://cdn.jsdelivr.net/gh/ntriver-org/ntriver-org-site@"
    private const val GITHUB_TREE_MAIN = "https://api.github.com/repos/ntriver-org/ntriver-org-site/git/trees/main?recursive=1"
    private const val GITHUB_TREE_MASTER = "https://api.github.com/repos/ntriver-org/ntriver-org-site/git/trees/master?recursive=1"
    private const val GITHUB_RAW_BASE = "https://raw.githubusercontent.com/ntriver-org/ntriver-org-site/"
    private const val REFRESH_PREFIX = "ntriver:"
    private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/136.0.0.0 Safari/537.36"

    private val SHA256_RE = Regex("\\b([0-9a-fA-F]{64})\\b")
    private val ISO_FILENAME_RE = Regex("""([A-Za-z0-9._()+\-]+\.iso)\b""", RegexOption.IGNORE_CASE)
    private val HTML_TAG_RE = Regex("<[^>]+>")
    private val MULTISPACE_RE = Regex("\\s+")
    // Matches BOTH absolute (https://...) AND relative (/drive?..., drive?..., ../drive?...) Markdown links!
    private val MD_LINK_RE = Regex("""\[([^\]]+)]\(([^)\s]+)\)""")

    private val FALLBACK_SLUGS = mapOf(
        "win11" to "/download-windows-11",
        "win10" to "/download-windows-10",
        "ltsc" to "/download-windows-ltsc",
        "win81" to "/download-windows-8.1",
        "win7" to "/download-windows-7",
        "server2025" to "/download-windows-server-2025",
        "server2022" to "/download-windows-server-2022",
        "server2019" to "/download-windows-server-2019"
    )

    private val FALLBACK_MD_PATHS = mapOf(
        "win11" to listOf("docs/download-windows-11.md", "docs/download-windows-11.mdx", "docs/windows-iso/download-windows-11.md", "docs/windows-iso/download-windows-11.mdx"),
        "win10" to listOf("docs/download-windows-10.md", "docs/download-windows-10.mdx", "docs/windows-iso/download-windows-10.md", "docs/windows-iso/download-windows-10.mdx"),
        "ltsc" to listOf("docs/download-windows-ltsc.md", "docs/download-windows-ltsc.mdx", "docs/windows-iso/download-windows-ltsc.md", "docs/windows-iso/download-windows-ltsc.mdx"),
        "win81" to listOf("docs/download-windows-8.1.md", "docs/download-windows-8.1.mdx", "docs/windows-iso/download-windows-8.1.md", "docs/windows-iso/download-windows-8.1.mdx"),
        "win7" to listOf("docs/download-windows-7.md", "docs/download-windows-7.mdx", "docs/windows-iso/download-windows-7.md", "docs/windows-iso/download-windows-7.mdx"),
        "server2025" to listOf("docs/download-windows-server-2025.md", "docs/download-windows-server-2025.mdx", "docs/windows-server-iso/download-windows-server-2025.md"),
        "server2022" to listOf("docs/download-windows-server-2022.md", "docs/download-windows-server-2022.mdx", "docs/windows-server-iso/download-windows-server-2022.md"),
        "server2019" to listOf("docs/download-windows-server-2019.md", "docs/download-windows-server-2019.mdx", "docs/windows-server-iso/download-windows-server-2019.md")
    )

    private val EXCLUDED_KEYWORDS = listOf(
        "ia-64", "ia64", "itanium", "windows rt", "arm32",
        "software development kit", "(sdk)", " sdk", "driver kit", "(wdk)", " wdk",
        "automated installation kit", "(waik)", " waik",
        "debugging symbols", "symbols debug", "debug-checked", "checked build",
        "language pack", "restore disc", "repair disc", "feature on demand",
        "inbox apps", "multilang", "hpc pack", "storage server", "multipoint server",
        "small business server", "home server"
    )

    private val NICHE_EDITION_KEYWORDS = listOf(
        "azure edition", "evaluation", "embedded", "china", "cmge",
        " n ", "(n)", "editions n", "core / pro n", "pro n", "enterprise n",
        "single language", "foundation", "essentials", "with bing", "pro student"
    )

    private val indexLock = Any()
    private val treeLock = Any()
    private val webViewMutex = Mutex()
    @Volatile private var lastIndexRefreshMs = 0L
    @Volatile private var lastTreeRefreshMs = 0L
    @Volatile private var detectedRepoBranch = "main"
    private val indexLinksCache = ConcurrentHashMap<String, String>()
    private val sectionsCache = ConcurrentHashMap<String, List<NtriverReleaseSection>>()
    private val repoTreeCache = ConcurrentHashMap<String, String>()

    private fun emitLog(onLog: ((LogEntry) -> Unit)?, message: String, isError: Boolean = false) {
        onLog?.invoke(LogEntry(SimpleDateFormat("HH:mm:ss", Locale.US).format(Date()), "ntriver", message, isError))
    }

    fun buildRefreshKey(entry: NtriverIsoEntry): String {
        if (entry.isMicrosoftStatic) return ""
        val drv = entry.ntriverDriveFilename?.trim().orEmpty()
        return if (drv.isNotBlank()) "$REFRESH_PREFIX$drv" else ""
    }

    fun extractDriveFilenameFromRefreshKey(refreshKey: String): String? {
        if (!refreshKey.startsWith(REFRESH_PREFIX)) return null
        val fn = refreshKey.removePrefix(REFRESH_PREFIX).trim()
        return if (fn.endsWith(".iso", ignoreCase = true) && !fn.contains('/') && !fn.contains('\\')) fn else null
    }

    internal fun encodeUrlQueryParam(value: String): String =
        URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    fun normalizeArch(raw: String): String {
        val low = raw.trim().lowercase(Locale.US)
        return when {
            low.contains("arm64") || low.contains("aarch64") || low.contains("a64fre") -> "ARM64"
            low.contains("x64") || low.contains("amd64") || low.contains("64-bit") || low == "64" -> "x64"
            low.contains("x32") || low.contains("x86") || low.contains("i386") || low.contains("32-bit") || low == "32" -> "x86"
            else -> raw.trim().ifBlank { "x64" }
        }
    }

    internal fun isExcludedSectionOrEntry(title: String, archRaw: String = "", fileName: String = ""): Boolean {
        val combined = " ${title.lowercase(Locale.US)} ${archRaw.lowercase(Locale.US)} ${fileName.lowercase(Locale.US)} "
        if (fileName.isNotBlank() && !fileName.trim().endsWith(".iso", ignoreCase = true)) return true
        return EXCLUDED_KEYWORDS.any { combined.contains(it) }
    }

    internal fun isRecommendedSection(rawHeading: String, cleanTitle: String): Boolean {
        if (rawHeading.contains("❤") || rawHeading.contains("\u2764")) return true
        val low = " ${cleanTitle.lowercase(Locale.US)} "
        if (NICHE_EDITION_KEYWORDS.any { low.contains(it) }) return false
        val isStandaloneEdu = low.contains("education") &&
            !low.contains("consumer") && !low.contains("business") && !low.contains("multi") && !low.contains("pro")
        if (isStandaloneEdu) return false
        return true
    }

    private fun decodeHtmlEntities(input: String): String = input
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&#x27;", "'")
        .replace("&nbsp;", " ")
        .replace("&bull;", "•")
        .replace("&#8203;", "")
        .replace("\u200B", "")

    private fun stripHtml(input: String): String =
        MULTISPACE_RE.replace(decodeHtmlEntities(HTML_TAG_RE.replace(input, " ")), " ").trim()

    private fun cleanSectionTitle(raw: String): String =
        raw.replace("❤️", "")
            .replace("\u2764\uFE0F", "")
            .replace("\u2764", "")
            .replace("#", "")
            .replace("\u200B", "")
            .replace(MULTISPACE_RE, " ")
            .trim()

    private fun stripLeadingOsPrefix(title: String): String {
        var s = cleanSectionTitle(title)
        listOf(
            "Windows 11 ", "Windows 10 / 11 ", "Windows 10 ", "Windows 8.1 ",
            "Windows 7 ", "Windows Server "
        ).forEach { pref ->
            if (s.startsWith(pref, ignoreCase = true) && s.length > pref.length + 1) {
                s = s.substring(pref.length).trim()
            }
        }
        return s.removePrefix("-").removePrefix("—").removePrefix("–").trim()
    }

    internal fun buildShortChipLabel(fullTitle: String, pagePrefix: String = ""): String {
        val s = stripLeadingOsPrefix(fullTitle)
        val combined = if (pagePrefix.isNotBlank() && !s.contains(pagePrefix, ignoreCase = true)) {
            "$pagePrefix $s"
        } else s
        return if (combined.length > 32) combined.take(30).trimEnd() + "…" else combined.ifBlank { fullTitle.take(28) }
    }

    internal fun deriveVersionAndEdition(
        parentH2: String,
        headingTitle: String,
        pagePrefix: String = ""
    ): Pair<String, String> {
        val cleanHead = cleanSectionTitle(headingTitle)
        val cleanParent = cleanSectionTitle(parentH2)

        if (pagePrefix.isNotBlank()) {
            var ed = cleanHead
                .replace(Regex("""^Windows\s+$pagePrefix\b""", RegexOption.IGNORE_CASE), "")
                .replace(Regex("""^$pagePrefix\b""", RegexOption.IGNORE_CASE), "")
                .trim()
                .removePrefix("-").removePrefix("—").removePrefix("–").trim()
            if (ed.startsWith("(") && ed.endsWith(")") && ed.length > 2) {
                ed = ed.substring(1, ed.length - 1).trim()
            }
            return pagePrefix to ed.ifBlank { "Standard / Datacenter" }
        }

        if (cleanParent.isNotBlank() && !cleanParent.equals(cleanHead, ignoreCase = true)) {
            val major = stripLeadingOsPrefix(cleanParent)
                .replace(Regex("""^with\s+""", RegexOption.IGNORE_CASE), "")
                .trim()
                .ifBlank { cleanParent }
            val ed = cleanHead
                .removePrefix(cleanParent)
                .trim()
                .removePrefix("-").removePrefix("—").removePrefix("–").trim()
                .ifBlank { cleanHead }
            return major.take(28) to ed.take(40)
        }

        val dashIdx = cleanHead.indexOf(" - ").takeIf { it > 0 }
            ?: cleanHead.indexOf(" – ").takeIf { it > 0 }
            ?: cleanHead.indexOf(" — ").takeIf { it > 0 }
        if (dashIdx != null) {
            val left = cleanHead.substring(0, dashIdx).trim()
            val right = cleanHead.substring(dashIdx + 3).trim()
            val major = stripLeadingOsPrefix(left)
                .replace(Regex("""^with\s+""", RegexOption.IGNORE_CASE), "")
                .trim()
                .ifBlank { if (left.contains("8.1")) "RTM" else if (left.contains("Windows 7", true)) "RTM" else left }
            return major.take(28) to right.take(40)
        }

        val low = cleanHead.lowercase(Locale.US)
        if (low.contains("ltsc") || low.contains("ltsb")) {
            val osTag = if (low.contains("windows 11") || low.contains("win 11") || low.contains("2024")) "Win 11" else "Win 10"
            val yr = Regex("""\b(2024|2021|2019|2016|2015)\b""").find(cleanHead)?.value.orEmpty()
            val kind = if (low.contains("ltsb")) "LTSB" else "LTSC"
            val major = listOf(osTag, kind, yr).filter { it.isNotBlank() }.joinToString(" ")
            val ed = stripLeadingOsPrefix(cleanHead).ifBlank { cleanHead }
            return major.take(28) to ed.take(40)
        }

        val verMatch = Regex("""\b(2[0-9]H[12]|2004|1909|1903|1809|1803|1709|1703|1607|1511|1507)\b""", RegexOption.IGNORE_CASE).find(cleanHead)
        if (verMatch != null) {
            val ver = verMatch.value.uppercase(Locale.US)
            val rest = stripLeadingOsPrefix(cleanHead)
                .replace(Regex("""\b${Regex.escape(ver)}\b""", RegexOption.IGNORE_CASE), "")
                .trim()
                .removePrefix("-").removePrefix("—").removePrefix("–").trim()
                .ifBlank { "Standard" }
            return ver to rest.take(40)
        }

        if (low.contains("sp1")) {
            val rest = stripLeadingOsPrefix(cleanHead)
                .replace(Regex("""with\s+sp1|sp1""", RegexOption.IGNORE_CASE), "")
                .trim().removePrefix("-").trim().ifBlank { "Standard" }
            return "SP1" to rest.take(40)
        }
        val short = buildShortChipLabel(cleanHead)
        return short to short
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // ZERO-BACKTRACKING LINEAR HTML SCANNER (O(N), constant stack depth on 2+ MB pages!)
    // ═══════════════════════════════════════════════════════════════════════════

    private fun isTagBoundary(html: String, pos: Int): Boolean {
        if (pos >= html.length) return true
        val c = html[pos]
        return c == '>' || c == '/' || c.isWhitespace()
    }

    private fun findTagOpen(html: String, tag: String, fromIndex: Int): Pair<Int, Int>? {
        val openNeedle = "<$tag"
        var pos = fromIndex
        while (pos < html.length) {
            val idx = html.indexOf(openNeedle, pos, ignoreCase = true)
            if (idx < 0) return null
            val afterTag = idx + openNeedle.length
            if (isTagBoundary(html, afterTag)) {
                val closeGt = html.indexOf('>', afterTag)
                if (closeGt < 0) return null
                return idx to (closeGt + 1)
            }
            pos = afterTag
        }
        return null
    }

    private fun extractTagInners(html: String, tag: String): List<String> {
        val closeNeedle = "</$tag>"
        val out = ArrayList<String>()
        var cursor = 0
        while (cursor < html.length) {
            val open = findTagOpen(html, tag, cursor) ?: break
            val closeIdx = html.indexOf(closeNeedle, open.second, ignoreCase = true)
            if (closeIdx < 0) break
            out.add(html.substring(open.second, closeIdx))
            cursor = closeIdx + closeNeedle.length
        }
        return out
    }

    private fun stripTagBlocksLinear(html: String, tag: String): String {
        val closeNeedle = "</$tag>"
        val sb = StringBuilder(html.length)
        var cursor = 0
        while (cursor < html.length) {
            val open = findTagOpen(html, tag, cursor) ?: break
            sb.append(html, cursor, open.first)
            val closeIdx = html.indexOf(closeNeedle, open.second, ignoreCase = true)
            cursor = if (closeIdx >= 0) closeIdx + closeNeedle.length else html.length
        }
        if (cursor < html.length) sb.append(html, cursor, html.length)
        return sb.toString()
    }

    private fun findTagBlocksLinear(html: String, tag: String): List<Triple<Int, Int, String>> {
        val closeNeedle = "</$tag>"
        val out = ArrayList<Triple<Int, Int, String>>()
        var cursor = 0
        while (cursor < html.length) {
            val open = findTagOpen(html, tag, cursor) ?: break
            val closeIdx = html.indexOf(closeNeedle, open.second, ignoreCase = true)
            if (closeIdx < 0) break
            out.add(Triple(open.first, closeIdx + closeNeedle.length, html.substring(open.second, closeIdx)))
            cursor = closeIdx + closeNeedle.length
        }
        return out
    }

    private data class LinearHeading(val level: Int, val start: Int, val end: Int, val innerHtml: String)

    private fun findHeadingsLinear(html: String): List<LinearHeading> {
        val out = ArrayList<LinearHeading>()
        var cursor = 0
        while (cursor < html.length - 4) {
            val idx = html.indexOf('<', cursor)
            if (idx < 0 || idx + 3 >= html.length) break
            val c1 = html[idx + 1]
            val c2 = html[idx + 2]
            if ((c1 == 'h' || c1 == 'H') && (c2 == '2' || c2 == '3' || c2 == '4') && isTagBoundary(html, idx + 3)) {
                val gt = html.indexOf('>', idx + 3)
                if (gt > idx) {
                    val closeNeedle = "</h$c2>"
                    val closeIdx = html.indexOf(closeNeedle, gt + 1, ignoreCase = true)
                    if (closeIdx > gt) {
                        out.add(LinearHeading(c2 - '0', idx, closeIdx + closeNeedle.length, html.substring(gt + 1, closeIdx)))
                        cursor = closeIdx + closeNeedle.length
                        continue
                    }
                }
            }
            cursor = idx + 1
        }
        return out
    }

    private fun findCellsLinear(trHtml: String): List<String> {
        val out = ArrayList<String>(4)
        var cursor = 0
        while (cursor < trHtml.length - 4) {
            val idx = trHtml.indexOf('<', cursor)
            if (idx < 0 || idx + 3 >= trHtml.length) break
            val c1 = trHtml[idx + 1]
            val c2 = trHtml[idx + 2]
            val isTd = (c1 == 't' || c1 == 'T') && (c2 == 'd' || c2 == 'D')
            val isTh = (c1 == 't' || c1 == 'T') && (c2 == 'h' || c2 == 'H')
            if ((isTd || isTh) && isTagBoundary(trHtml, idx + 3)) {
                val gt = trHtml.indexOf('>', idx + 3)
                if (gt > idx) {
                    val closeNeedle = if (isTd) "</td>" else "</th>"
                    val closeIdx = trHtml.indexOf(closeNeedle, gt + 1, ignoreCase = true)
                    if (closeIdx >= gt + 1) {
                        out.add(trHtml.substring(gt + 1, closeIdx))
                        cursor = closeIdx + closeNeedle.length
                        continue
                    }
                }
            }
            cursor = idx + 1
        }
        return out
    }

    private fun findAnchorsLinear(html: String): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        // Supports double-quoted, single-quoted, AND unquoted minified HTML href=... attributes!
        val hrefRe = Regex("""\bhref\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s"'<>`]+))""", RegexOption.IGNORE_CASE)
        var cursor = 0
        while (cursor < html.length) {
            val open = findTagOpen(html, "a", cursor) ?: break
            val openTag = html.substring(open.first, open.second)
            val closeIdx = html.indexOf("</a>", open.second, ignoreCase = true)
            if (closeIdx < 0) break
            val hrefMatch = hrefRe.find(openTag)
            val href = (hrefMatch?.groupValues?.getOrNull(1)?.ifEmpty {
                hrefMatch.groupValues.getOrNull(2)?.ifEmpty { hrefMatch.groupValues.getOrNull(3) }
            }).orEmpty()
            if (href.isNotBlank()) {
                out.add(href to html.substring(open.second, closeIdx))
            }
            cursor = closeIdx + 4
        }
        return out
    }

    private fun isCloudflareChallenge(code: Int, body: String): Boolean {
        val low = body.take(8_192).lowercase(Locale.US)
        if (code == 403 || code == 503 || code == 429) {
            if (low.contains("just a moment") || low.contains("cf-browser-verification") ||
                low.contains("challenge-platform") || low.contains("cloudflare")
            ) return true
        }
        return low.contains("cf-browser-verification") || low.contains("_cf_chl_opt")
    }

    private fun httpGet(urlStr: String, acceptJson: Boolean = false, referer: String? = null): Pair<Int, String> {
        val url = URL(urlStr)
        require(url.protocol.equals("https", ignoreCase = true)) { "HTTPS is required for catalog requests." }
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", UA)
            setRequestProperty("Accept", if (acceptJson) "application/json, text/plain, */*" else "text/html, text/plain, */*")
            setRequestProperty("Accept-Language", "en-US,en;q=0.9")
            if (!referer.isNullOrBlank()) setRequestProperty("Referer", referer)
        }
        return try {
            val code = conn.responseCode
            require(conn.url.protocol.equals("https", ignoreCase = true)) { "Rejected redirect to non-HTTPS URL." }
            val stream = if (code in 200..399) conn.inputStream else conn.errorStream
            val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            code to body
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Pure-JVM parser extracting OS page routes from `/download-windows-office`.
     */
    fun parseIndexPageLinks(html: String): Map<String, String> {
        val exact = LinkedHashMap<String, String>()
        val fallback = LinkedHashMap<String, String>()
        for ((rawHrefRaw, innerText) in findAnchorsLinear(html)) {
            val rawHref = decodeHtmlEntities(rawHrefRaw).trim()
            if (rawHref.startsWith("#") || rawHref.startsWith("mailto:") || rawHref.startsWith("javascript:")) continue
            val path = when {
                rawHref.startsWith(SITE_ORIGIN, ignoreCase = true) -> rawHref.substring(SITE_ORIGIN.length)
                rawHref.startsWith("/") -> rawHref
                else -> continue
            }.substringBefore('#').substringBefore('?').trimEnd('/')
            if (path.isBlank() || path == "/download-windows-office") continue
            val lowPath = path.lowercase(Locale.US)
            val lowText = stripHtml(innerText).lowercase(Locale.US)
            if (lowPath.contains("office") || lowPath.contains("esd")) continue

            when {
                lowPath.endsWith("/download-windows-11") || lowPath.endsWith("/windows-11") -> exact["win11"] = path
                lowPath.contains("windows-11") && !lowPath.contains("ltsc") && !lowPath.contains("arm") -> fallback.putIfAbsent("win11", path)

                lowPath.endsWith("/download-windows-10") || lowPath.endsWith("/windows-10") -> exact["win10"] = path
                lowPath.contains("windows-10") && !lowPath.contains("ltsc") && !lowPath.contains("cmge") && !lowPath.contains("arm") -> fallback.putIfAbsent("win10", path)

                lowPath.endsWith("/download-windows-ltsc") || lowPath.endsWith("/windows-ltsc") -> exact["ltsc"] = path
                lowPath.contains("ltsc") || lowText.contains("ltsc") -> fallback.putIfAbsent("ltsc", path)

                lowPath.endsWith("/download-windows-8.1") || lowPath.endsWith("/download-windows-8-1") -> exact["win81"] = path
                lowPath.contains("windows-8.1") || lowPath.contains("windows-8-1") -> fallback.putIfAbsent("win81", path)

                lowPath.endsWith("/download-windows-7") || lowPath.endsWith("/windows-7") -> exact["win7"] = path
                (lowPath.contains("windows-7") && !lowPath.contains("embedded")) || lowText.startsWith("windows 7") -> fallback.putIfAbsent("win7", path)

                lowPath.endsWith("/download-windows-server-2025") -> exact["server2025"] = path
                lowPath.contains("server-2025") || lowText.contains("server 2025") -> fallback.putIfAbsent("server2025", path)

                lowPath.endsWith("/download-windows-server-2022") -> exact["server2022"] = path
                lowPath.contains("server-2022") || lowText.contains("server 2022") -> fallback.putIfAbsent("server2022", path)

                lowPath.endsWith("/download-windows-server-2019") -> exact["server2019"] = path
                lowPath.contains("server-2019") || lowText.contains("server 2019") -> fallback.putIfAbsent("server2019", path)
            }
        }
        val merged = LinkedHashMap<String, String>(fallback)
        merged.putAll(exact)
        return merged
    }

    internal fun isHostOrSubdomain(host: String, domain: String): Boolean {
        val h = host.trim().lowercase(Locale.US)
        val d = domain.trim().lowercase(Locale.US)
        return h == d || h.endsWith(".$d")
    }

    private fun normalizeRelativeDriveUrl(href: String): String? {
        val clean = decodeHtmlEntities(href).trim()
        if (clean.isBlank()) return null
        if (clean.startsWith("https://", ignoreCase = true)) return clean
        if (clean.startsWith("http://", ignoreCase = true)) return null
        var rel = clean
        while (rel.startsWith("../") || rel.startsWith("./")) {
            rel = if (rel.startsWith("../")) rel.substring(3) else rel.substring(2)
        }
        val low = rel.lowercase(Locale.US)
        return when {
            low.startsWith("/drive") -> "$SITE_ORIGIN$rel"
            low.startsWith("drive?") || low.startsWith("drive/") || low == "drive" -> "$SITE_ORIGIN/$rel"
            else -> null
        }
    }

    internal fun extractDriveFilenameFromUrl(href: String): String? {
        val fullUrlStr = normalizeRelativeDriveUrl(href) ?: return null
        val parsed = runCatching { URL(fullUrlStr) }.getOrNull() ?: return null
        if (!isHostOrSubdomain(parsed.host.orEmpty(), "ntriver.org")) return null
        val path = parsed.path.orEmpty()
        if (!path.equals("/drive", ignoreCase = true) && !path.startsWith("/drive/", ignoreCase = true)) return null

        val query = parsed.query.orEmpty()
        val candidate = if (query.isNotBlank()) {
            val parts = query.split('&').filter { it.isNotBlank() }
            val named = parts.firstOrNull {
                it.startsWith("filename=", ignoreCase = true) || it.startsWith("file=", ignoreCase = true)
            }?.substringAfter('=')
            named ?: parts.firstOrNull { !it.contains('=') } ?: parts.first().substringAfter('=', parts.first())
        } else {
            path.substringAfter("/drive/", "")
        }
        if (candidate.isBlank()) return null
        val decoded = runCatching { URLDecoder.decode(candidate.replace("+", "%2B"), "UTF-8") }
            .getOrDefault(candidate).trim()
        return if (decoded.endsWith(".iso", ignoreCase = true) && !decoded.contains('/') && !decoded.contains('\\')) decoded else null
    }

    internal fun isDirectMicrosoftIsoUrl(href: String): Boolean {
        val clean = decodeHtmlEntities(href).trim()
        val parsed = runCatching { URL(clean) }.getOrNull() ?: return false
        if (!parsed.protocol.equals("https", ignoreCase = true)) return false
        val host = parsed.host.orEmpty().lowercase(Locale.US)
        if (isHostOrSubdomain(host, "rg-adguard.net") || isHostOrSubdomain(host, "ntriver.org")) return false
        val isMsHost = isHostOrSubdomain(host, "microsoft.com") ||
            isHostOrSubdomain(host, "microsoft.net") ||
            isHostOrSubdomain(host, "msecnd.net") ||
            isHostOrSubdomain(host, "azureedge.net") ||
            isHostOrSubdomain(host, "windowsupdate.com") ||
            isHostOrSubdomain(host, "msedge.net")
        return isMsHost && parsed.path.orEmpty().lowercase(Locale.US).endsWith(".iso")
    }

    internal fun isNtriverDriveHref(href: String): Boolean {
        val full = normalizeRelativeDriveUrl(href) ?: return false
        val parsed = runCatching { URL(full) }.getOrNull() ?: return false
        if (!isHostOrSubdomain(parsed.host.orEmpty(), "ntriver.org")) return false
        val path = parsed.path.orEmpty()
        return path.equals("/drive", ignoreCase = true) || path.startsWith("/drive/", ignoreCase = true)
    }

    private fun guessLanguageFromFileName(fileName: String): String {
        val low = fileName.lowercase(Locale.US)
        return when {
            low.startsWith("en-us") || low.contains("_en-us") || low.contains("_en_us") -> "English (US)"
            low.startsWith("en-gb") || low.contains("_en-gb") || low.contains("englishinternational") -> "English (International)"
            low.startsWith("en_") || low.contains("english") -> "English"
            low.startsWith("hi-in") || low.contains("hindi") -> "Hindi"
            low.startsWith("de-de") || low.contains("german") -> "German"
            low.startsWith("fr-fr") || low.contains("french") -> "French"
            low.startsWith("es-es") || low.startsWith("es-mx") || low.contains("spanish") -> "Spanish"
            low.startsWith("pt-br") || low.startsWith("pt-pt") -> "Portuguese"
            low.startsWith("ru-ru") || low.contains("russian") -> "Russian"
            low.startsWith("ja-jp") || low.contains("japanese") -> "Japanese"
            low.startsWith("ko-kr") || low.contains("korean") -> "Korean"
            low.startsWith("zh-cn") -> "Chinese (Simplified)"
            low.startsWith("zh-tw") -> "Chinese (Traditional)"
            low.startsWith("ar-sa") -> "Arabic"
            low.startsWith("it-it") -> "Italian"
            low.startsWith("nl-nl") -> "Dutch"
            low.startsWith("pl-pl") -> "Polish"
            low.startsWith("tr-tr") -> "Turkish"
            else -> "English"
        }
    }

    internal fun disambiguateEntries(entries: List<NtriverIsoEntry>): List<NtriverIsoEntry> {
        if (entries.isEmpty()) return emptyList()
        val mergedMap = LinkedHashMap<String, NtriverIsoEntry>()
        for (e in entries) {
            val k = "${e.arch.lowercase(Locale.US)}|${e.fileName.lowercase(Locale.US)}"
            val existing = mergedMap[k]
            if (existing == null) {
                mergedMap[k] = e
            } else {
                mergedMap[k] = existing.copy(
                    sha256 = existing.sha256.ifBlank { e.sha256 },
                    directStaticUrl = existing.directStaticUrl ?: e.directStaticUrl,
                    ntriverDriveFilename = existing.ntriverDriveFilename ?: e.ntriverDriveFilename
                )
            }
        }
        val deduped = mergedMap.values.toList()
        if (deduped.size <= 1) return deduped.map { it.copy(displayLabel = it.language) }

        val dupKeys = deduped
            .groupingBy { "${it.arch.lowercase(Locale.US)}|${it.language.lowercase(Locale.US)}" }
            .eachCount()
            .filterValues { it > 1 }
            .keys
        if (dupKeys.isEmpty()) return deduped.map { it.copy(displayLabel = it.language) }

        val usedLabels = HashMap<String, Int>()
        return deduped.map { e ->
            val groupKey = "${e.arch.lowercase(Locale.US)}|${e.language.lowercase(Locale.US)}"
            if (groupKey !in dupKeys) {
                e.copy(displayLabel = e.language)
            } else {
                val low = e.fileName.lowercase(Locale.US)
                val stem = if (low.endsWith(".iso")) e.fileName.dropLast(4) else e.fileName
                val hint = when {
                    low.contains("englishinternational") || low.contains("en-gb") || low.contains("en_gb") -> "International"
                    low.contains("en-us") || low.contains("en_us") -> "US"
                    low.contains("ultimate") -> "Ultimate"
                    low.contains("professional") || low.contains("_pro_") -> "Pro"
                    low.contains("home_premium") || low.contains("homepremium") -> "Home Premium"
                    low.contains("home_basic") || low.contains("homebasic") -> "Home Basic"
                    low.contains("starter") -> "Starter"
                    low.contains("iot_enterprise") || low.contains("iotenterprise") -> "IoT Enterprise"
                    low.contains("enterprise") -> "Enterprise"
                    low.contains("consumer") -> "Consumer"
                    low.contains("business") -> "Business"
                    low.contains("datacenter") -> "Datacenter"
                    low.contains("standard") -> "Standard"
                    low.contains("_vl_") || low.contains("_vol_") -> "VL"
                    else -> stem.takeLast(14).trimStart('_', '-', '.')
                }
                val baseLabel = "${e.language} ($hint)"
                val uniqKey = "${e.arch.lowercase(Locale.US)}|${baseLabel.lowercase(Locale.US)}"
                val count = (usedLabels[uniqKey] ?: 0) + 1
                usedLabels[uniqKey] = count
                val finalLabel = if (count == 1) baseLabel else "$baseLabel #$count"
                e.copy(displayLabel = finalLabel)
            }
        }
    }

    private fun extractMetaFromIntro(introText: String): Pair<String, String> {
        val clean = introText
            .replace("\r", "")
            .replace(MD_LINK_RE, "$1")
            .replace("**", "")
            .replace("__", "")
            .replace("`", "")
        // Supports BOTH `Build: 26300.9974` AND `Build - 26300.9457 (September 2026)`
        val buildMatch = Regex("""Build\s*[:\-–—]\s*([^\n•|]+)""", RegexOption.IGNORE_CASE).find(clean)
        val buildBadge = buildMatch?.groupValues?.getOrNull(1)?.trim()
            ?.replace("[", "")?.replace("]", "")?.take(56).orEmpty()
        val noteMatch = Regex("""Note\s*[:\-–—]\s*([^\n]+)""", RegexOption.IGNORE_CASE).find(clean)
        val note = noteMatch?.groupValues?.getOrNull(1)?.trim()?.take(180).orEmpty()
        return buildBadge to note
    }

    private fun parseHtmlTablesFromBlock(block: String, sectionTitle: String): List<NtriverIsoEntry> {
        val entries = mutableListOf<NtriverIsoEntry>()
        for ((_, _, tblBody) in findTagBlocksLinear(block, "table")) {
            val rows = findTagBlocksLinear(tblBody, "tr")
            if (rows.isEmpty()) continue
            val headerText = stripHtml(rows.first().third).lowercase(Locale.US)
            if (headerText.contains("windows version") && headerText.contains("iso links")) continue

            for (rowIdx in rows.indices) {
                val cells = findCellsLinear(rows[rowIdx].third)
                if (cells.size < 2) continue
                val col0 = stripHtml(cells[0])
                val col1 = stripHtml(cells[1])
                if (rowIdx == 0 && (col0.equals("Language", true) || col1.equals("Arch", true))) continue
                val detailsHtml = if (cells.size >= 3) cells.drop(2).joinToString(" ") else cells[1]
                val detailsText = stripHtml(detailsHtml)
                val textFileName = ISO_FILENAME_RE.find(detailsText)?.groupValues?.get(1)?.trim()
                val sha = SHA256_RE.find(detailsText)?.groupValues?.get(1)?.lowercase(Locale.US).orEmpty()

                var directStatic: String? = null
                var driveFile: String? = null
                var hasGenericDriveLink = false
                for ((rawHref, rawLbl) in findAnchorsLinear(detailsHtml)) {
                    val href = decodeHtmlEntities(rawHref).trim()
                    val label = stripHtml(rawLbl).lowercase(Locale.US)
                    if (href.contains("rg-adguard", true) || label.contains("rg-adguard")) continue
                    if (directStatic == null && isDirectMicrosoftIsoUrl(href)) {
                        directStatic = href
                    }
                    if (driveFile == null) {
                        val extracted = extractDriveFilenameFromUrl(href)
                        if (extracted != null) {
                            driveFile = extracted
                        } else if (isNtriverDriveHref(href)) {
                            hasGenericDriveLink = true
                        }
                    }
                }
                val staticUrlFile = directStatic?.substringBefore('?')?.substringAfterLast('/')
                    ?.takeIf { it.endsWith(".iso", ignoreCase = true) }
                val fileName = textFileName ?: driveFile ?: staticUrlFile ?: continue
                if (driveFile == null && hasGenericDriveLink) driveFile = fileName
                val archHint = if (cells.size >= 3) col1 else WindowsCustomizationEngine.detectProcessorArchitecture(fileName)
                if (isExcludedSectionOrEntry(sectionTitle, archHint, fileName)) continue
                if (directStatic == null && driveFile == null) continue

                entries.add(
                    NtriverIsoEntry(
                        language = col0.ifBlank { guessLanguageFromFileName(fileName) },
                        arch = normalizeArch(archHint),
                        fileName = fileName,
                        sha256 = sha,
                        directStaticUrl = directStatic,
                        ntriverDriveFilename = driveFile
                    )
                )
            }
        }
        return entries
    }

    /**
     * Universal fallback sweep if a page wraps ISO links in non-table elements (`<details>`, React `<Tabs>`, `<li>`, cards).
     */
    internal fun parseLooseIsoLinksFallback(cleanedHtml: String, pagePrefix: String = ""): List<NtriverReleaseSection> {
        val entries = mutableListOf<NtriverIsoEntry>()
        // First try all <tr> rows across the document regardless of heading hierarchy
        val trEntries = parseHtmlTablesFromBlock("<table>$cleanedHtml</table>", pagePrefix)
        entries.addAll(trEntries)

        // If still empty, sweep every anchor on the page for drive/MS static `.iso` links
        if (entries.isEmpty()) {
            for ((rawHref, rawLbl) in findAnchorsLinear(cleanedHtml)) {
                val href = decodeHtmlEntities(rawHref).trim()
                val lbl = stripHtml(rawLbl)
                if (href.contains("rg-adguard", true) || lbl.contains("rg-adguard", true)) continue
                val directStatic = if (isDirectMicrosoftIsoUrl(href)) href else null
                val driveFile = extractDriveFilenameFromUrl(href)
                val staticFile = directStatic?.substringBefore('?')?.substringAfterLast('/')
                    ?.takeIf { it.endsWith(".iso", ignoreCase = true) }
                val lblFile = ISO_FILENAME_RE.find(lbl)?.groupValues?.get(1)?.trim()
                val fileName = driveFile ?: staticFile ?: lblFile ?: continue
                val effectiveDrive = driveFile ?: if (isNtriverDriveHref(href)) fileName else null
                if (directStatic == null && effectiveDrive == null) continue
                val arch = normalizeArch(WindowsCustomizationEngine.detectProcessorArchitecture(fileName))
                if (isExcludedSectionOrEntry(pagePrefix, arch, fileName)) continue
                entries.add(
                    NtriverIsoEntry(
                        language = guessLanguageFromFileName(fileName),
                        arch = arch,
                        fileName = fileName,
                        sha256 = "",
                        directStaticUrl = directStatic,
                        ntriverDriveFilename = effectiveDrive
                    )
                )
            }
        }
        if (entries.isEmpty()) return emptyList()
        val label = pagePrefix.ifBlank { "Available ISOs" }
        return listOf(
            NtriverReleaseSection(
                id = "${pagePrefix}_fallback_0",
                title = label,
                shortChipLabel = label,
                majorVersion = label,
                editionLabel = "All Editions",
                isRecommended = true,
                entries = disambiguateEntries(entries)
            )
        )
    }

    private fun parseHtmlRegion(cleanedHtml: String, pagePrefix: String): List<NtriverReleaseSection> {
        val headings = findHeadingsLinear(cleanedHtml)
        if (headings.isEmpty()) return emptyList()

        val sections = mutableListOf<NtriverReleaseSection>()
        var activeH2Raw = ""
        var activeH2Clean = ""
        var activeH2Excluded = false

        for (i in headings.indices) {
            val h = headings[i]
            val rawHeadingText = stripHtml(h.innerHtml)
            val cleanTitle = cleanSectionTitle(rawHeadingText)
            if (cleanTitle.isBlank()) continue
            val lowTitle = cleanTitle.lowercase(Locale.US)
            val isMetaHeading = lowTitle == "index" || lowTitle == "misc" || lowTitle == "missing files" ||
                lowTitle == "overview" || lowTitle == "installation keys" || lowTitle == "to do"

            if (h.level == 2) {
                activeH2Raw = rawHeadingText
                activeH2Clean = cleanTitle
                activeH2Excluded = isMetaHeading || isExcludedSectionOrEntry(cleanTitle)
            }
            if (isMetaHeading || isExcludedSectionOrEntry(cleanTitle) || (h.level > 2 && activeH2Excluded)) continue

            val blockStart = h.end
            val blockEnd = if (i + 1 < headings.size) headings[i + 1].start else cleanedHtml.length
            if (blockStart >= blockEnd) continue
            val block = cleanedHtml.substring(blockStart, blockEnd)

            val firstTableOpen = findTagOpen(block, "table", 0)
            val introHtml = if (firstTableOpen != null) block.substring(0, firstTableOpen.first) else block
            val introLines = introHtml
                .replace(Regex("""</(?:p|li|div|br)\s*>|<br\s*/?>""", RegexOption.IGNORE_CASE), "\n")
                .let { decodeHtmlEntities(HTML_TAG_RE.replace(it, " ")) }
            val (buildBadge, note) = extractMetaFromIntro(introLines)

            val parentForHeading = if (h.level > 2) activeH2Clean else ""
            val combinedTitleForFilter = if (parentForHeading.isNotBlank() && !cleanTitle.contains(parentForHeading, true)) {
                "$parentForHeading - $cleanTitle"
            } else cleanTitle
            val entries = if (firstTableOpen != null) parseHtmlTablesFromBlock(block, combinedTitleForFilter) else emptyList()

            if (entries.isNotEmpty()) {
                val (majorVersion, editionLabel) = deriveVersionAndEdition(parentForHeading, cleanTitle, pagePrefix)
                val fullTitle = if (pagePrefix.isNotBlank() && !combinedTitleForFilter.contains(pagePrefix, true)) {
                    "$pagePrefix · $combinedTitleForFilter"
                } else combinedTitleForFilter
                val recRaw = if (h.level > 2) "$activeH2Raw $rawHeadingText" else rawHeadingText
                sections.add(
                    NtriverReleaseSection(
                        id = "${pagePrefix}_${majorVersion}_${editionLabel}_${sections.size}",
                        title = fullTitle,
                        shortChipLabel = buildShortChipLabel(combinedTitleForFilter, pagePrefix),
                        majorVersion = majorVersion,
                        editionLabel = editionLabel,
                        buildBadge = buildBadge,
                        note = note,
                        isRecommended = isRecommendedSection(recRaw, combinedTitleForFilter),
                        entries = disambiguateEntries(entries)
                    )
                )
            }
        }
        return sections
    }

    /**
     * Zero-backtracking linear parser for rendered Docusaurus HTML pages on ntriver.org.
     * Tries every `<article>` block, then `<main>`, then full document, then loose ISO sweep.
     */
    fun parseHtmlCatalogPage(html: String, pagePrefix: String = ""): List<NtriverReleaseSection> {
        val cleanFull = stripTagBlocksLinear(stripTagBlocksLinear(html, "script"), "style")

        for (art in extractTagInners(cleanFull, "article")) {
            val res = parseHtmlRegion(art, pagePrefix)
            if (res.isNotEmpty()) return res
        }
        for (main in extractTagInners(cleanFull, "main")) {
            val res = parseHtmlRegion(main, pagePrefix)
            if (res.isNotEmpty()) return res
        }
        val fullRes = parseHtmlRegion(cleanFull, pagePrefix)
        if (fullRes.isNotEmpty()) return fullRes

        return parseLooseIsoLinksFallback(cleanFull, pagePrefix)
    }

    /**
     * Pure-JVM fallback parser for raw GitHub / jsDelivr Markdown & MDX pages from `ntriver-org/ntriver-org-site`.
     * Supports relative links (`/drive?...`, `drive?...`), GFM pipe tables with or without leading `|`,
     * inline HTML `<table>` blocks inside MDX, and a loose link sweep fallback.
     */
    fun parseMarkdownCatalogPage(markdown: String, pagePrefix: String = ""): List<NtriverReleaseSection> {
        val lines = markdown.replace("\r\n", "\n").split('\n')
        val sections = mutableListOf<NtriverReleaseSection>()

        var activeH2Raw = ""
        var activeH2Clean = ""
        var activeH2Excluded = false
        var curLevel = 2
        var curRawHeading = ""
        var curTitle = ""
        val curIntro = StringBuilder()
        val curRawBody = StringBuilder()
        val curEntries = mutableListOf<NtriverIsoEntry>()
        var skipCurrentSection = true

        fun flushSection() {
            if (!skipCurrentSection && curTitle.isNotBlank()) {
                val parentForHeading = if (curLevel > 2) activeH2Clean else ""
                val combinedTitle = if (parentForHeading.isNotBlank() && !curTitle.contains(parentForHeading, true)) {
                    "$parentForHeading - $curTitle"
                } else curTitle
                val rawBodyStr = curRawBody.toString()
                if (rawBodyStr.contains("<table", ignoreCase = true)) {
                    curEntries.addAll(parseHtmlTablesFromBlock(rawBodyStr, combinedTitle))
                }
                if (curEntries.isNotEmpty()) {
                    val (buildBadge, note) = extractMetaFromIntro(curIntro.toString())
                    val (majorVersion, editionLabel) = deriveVersionAndEdition(parentForHeading, curTitle, pagePrefix)
                    val fullTitle = if (pagePrefix.isNotBlank() && !combinedTitle.contains(pagePrefix, true)) {
                        "$pagePrefix · $combinedTitle"
                    } else combinedTitle
                    val recRaw = if (curLevel > 2) "$activeH2Raw $curRawHeading" else curRawHeading
                    sections.add(
                        NtriverReleaseSection(
                            id = "${pagePrefix}_${majorVersion}_${editionLabel}_${sections.size}",
                            title = fullTitle,
                            shortChipLabel = buildShortChipLabel(combinedTitle, pagePrefix),
                            majorVersion = majorVersion,
                            editionLabel = editionLabel,
                            buildBadge = buildBadge,
                            note = note,
                            isRecommended = isRecommendedSection(recRaw, combinedTitle),
                            entries = disambiguateEntries(curEntries.toList())
                        )
                    )
                }
            }
            curIntro.setLength(0)
            curRawBody.setLength(0)
            curEntries.clear()
        }

        val headingLineRe = Regex("""^(#{2,4})\s+(.+)$""")
        val sepRe = Regex("""^:?-{3,}:?$""")
        val splitPipeRe = Regex("""(?<!\\)\|""")

        for (rawLine in lines) {
            val line = rawLine.trim()
            val hMatch = headingLineRe.find(line)
            if (hMatch != null) {
                flushSection()
                curLevel = hMatch.groupValues[1].length
                curRawHeading = hMatch.groupValues[2].trim()
                curTitle = cleanSectionTitle(curRawHeading)
                val low = curTitle.lowercase(Locale.US)
                val isMeta = curTitle.isBlank() ||
                    low == "index" || low == "misc" || low == "missing files" || low == "overview" ||
                    low == "installation keys" || low == "to do"
                if (curLevel == 2) {
                    activeH2Raw = curRawHeading
                    activeH2Clean = curTitle
                    activeH2Excluded = isMeta || isExcludedSectionOrEntry(curTitle)
                }
                skipCurrentSection = isMeta || isExcludedSectionOrEntry(curTitle) || (curLevel > 2 && activeH2Excluded)
                continue
            }
            if (skipCurrentSection) continue

            // Accept GFM pipe table rows BOTH with leading `|` AND without leading `|` (e.g. `English | x64 | ...`)
            val looksLikeTableRow = line.startsWith("|") || (line.contains("|") && (line.contains(".iso", true) || line.contains("drive", true) || line.contains("http", true)))
            if (!looksLikeTableRow) {
                curRawBody.append(rawLine).append('\n')
                if (curEntries.isEmpty() && line.isNotBlank() && !line.startsWith("<")) {
                    curIntro.append(line.removePrefix("*").removePrefix("-").trim()).append('\n')
                }
                continue
            }

            val cols = line.trim('|')
                .split(splitPipeRe)
                .map { it.replace("\\|", "|").trim() }
            if (cols.size < 2) continue
            if (sepRe.matches(cols[0]) ||
                cols[0].equals("Language", true) ||
                cols[0].equals("Windows Version", true) ||
                cols[1].equals("Arch", true)
            ) continue

            val lang = stripHtml(cols[0]).ifBlank { "English" }
            val detailsRaw = if (cols.size >= 3) cols.drop(2).joinToString(" | ") else cols[1]
            val textFileName = ISO_FILENAME_RE.find(stripHtml(detailsRaw))?.groupValues?.get(1)?.trim()
            val sha = SHA256_RE.find(detailsRaw)?.groupValues?.get(1)?.lowercase(Locale.US).orEmpty()

            var directStatic: String? = null
            var driveFile: String? = null
            var hasGenericDriveLink = false
            for (lm in MD_LINK_RE.findAll(detailsRaw)) {
                val lbl = lm.groupValues[1].lowercase(Locale.US)
                val href = lm.groupValues[2].trim()
                if (href.contains("rg-adguard", true) || lbl.contains("rg-adguard")) continue
                if (directStatic == null && isDirectMicrosoftIsoUrl(href)) {
                    directStatic = href
                }
                if (driveFile == null) {
                    val extracted = extractDriveFilenameFromUrl(href)
                    if (extracted != null) {
                        driveFile = extracted
                    } else if (isNtriverDriveHref(href)) {
                        hasGenericDriveLink = true
                    }
                }
            }
            for ((rawHref, rawLbl) in findAnchorsLinear(detailsRaw)) {
                val href = decodeHtmlEntities(rawHref).trim()
                val lbl = stripHtml(rawLbl).lowercase(Locale.US)
                if (href.contains("rg-adguard", true) || lbl.contains("rg-adguard")) continue
                if (directStatic == null && isDirectMicrosoftIsoUrl(href)) directStatic = href
                if (driveFile == null) {
                    val extracted = extractDriveFilenameFromUrl(href)
                    if (extracted != null) {
                        driveFile = extracted
                    } else if (isNtriverDriveHref(href)) {
                        hasGenericDriveLink = true
                    }
                }
            }
            val staticUrlFile = directStatic?.substringBefore('?')?.substringAfterLast('/')
                ?.takeIf { it.endsWith(".iso", ignoreCase = true) }
            val fileName = textFileName ?: driveFile ?: staticUrlFile ?: continue
            if (driveFile == null && hasGenericDriveLink) driveFile = fileName
            val archRaw = if (cols.size >= 3) stripHtml(cols[1]) else WindowsCustomizationEngine.detectProcessorArchitecture(fileName)
            val parentTitle = if (curLevel > 2) "$activeH2Clean $curTitle" else curTitle
            if (isExcludedSectionOrEntry(parentTitle, archRaw, fileName)) continue
            if (directStatic == null && driveFile == null) continue

            curEntries.add(
                NtriverIsoEntry(
                    language = lang,
                    arch = normalizeArch(archRaw),
                    fileName = fileName,
                    sha256 = sha,
                    directStaticUrl = directStatic,
                    ntriverDriveFilename = driveFile
                )
            )
        }
        flushSection()
        if (sections.isNotEmpty()) return sections

        // Universal loose Markdown fallback if page uses non-table bullet lists or JSX components
        val looseEntries = mutableListOf<NtriverIsoEntry>()
        for (lm in MD_LINK_RE.findAll(markdown)) {
            val lbl = lm.groupValues[1]
            val href = lm.groupValues[2].trim()
            if (href.contains("rg-adguard", true) || lbl.contains("rg-adguard", true)) continue
            val directStatic = if (isDirectMicrosoftIsoUrl(href)) href else null
            val driveFile = extractDriveFilenameFromUrl(href)
            val staticFile = directStatic?.substringBefore('?')?.substringAfterLast('/')?.takeIf { it.endsWith(".iso", true) }
            val lblFile = ISO_FILENAME_RE.find(lbl)?.groupValues?.get(1)?.trim()
            val fileName = driveFile ?: staticFile ?: lblFile ?: continue
            val effectiveDrive = driveFile ?: if (isNtriverDriveHref(href)) fileName else null
            if (directStatic == null && effectiveDrive == null) continue
            val arch = normalizeArch(WindowsCustomizationEngine.detectProcessorArchitecture(fileName))
            if (isExcludedSectionOrEntry(pagePrefix, arch, fileName)) continue
            looseEntries.add(
                NtriverIsoEntry(
                    language = guessLanguageFromFileName(fileName),
                    arch = arch,
                    fileName = fileName,
                    sha256 = "",
                    directStaticUrl = directStatic,
                    ntriverDriveFilename = effectiveDrive
                )
            )
        }
        if (looseEntries.isEmpty()) return emptyList()
        val label = pagePrefix.ifBlank { "Available ISOs" }
        return listOf(
            NtriverReleaseSection(
                id = "${pagePrefix}_md_fallback_0",
                title = label,
                shortChipLabel = label,
                majorVersion = label,
                editionLabel = "All Editions",
                isRecommended = true,
                entries = disambiguateEntries(looseEntries)
            )
        )
    }

    /**
     * Pure-JVM parser for `GET https://ntriver.org/api/drive/generate-link?filename=...` JSON response.
     * Strictly enforces HTTPS scheme on the resolved URL.
     */
    fun parseGenerateLinkJson(body: String): NtriverResolveResult {
        val trimmed = body.trim()
        if (trimmed.isBlank()) {
            return NtriverResolveResult(errorMessage = "NTriver Drive returned an empty response. Tap Retry.")
        }
        val low = trimmed.lowercase(Locale.US)
        if (low.startsWith("<!doctype") || low.startsWith("<html") || low.contains("cf-browser-verification")) {
            return NtriverResolveResult(
                errorMessage = "NTriver Drive is currently behind a browser verification challenge. Tap Retry or try another network.",
                needsWebViewFallback = true
            )
        }
        return runCatching {
            val obj = JSONObject(trimmed)
            val errMsg = obj.optString("error", "").ifBlank { obj.optString("message", "") }.trim()
            val rawUrl = obj.optString("url", "")
                .ifBlank { obj.optJSONObject("data")?.optString("url", "").orEmpty() }
                .ifBlank { obj.optString("downloadUrl", "") }
                .trim()
            if (rawUrl.isNotBlank()) {
                val parsed = runCatching { URL(rawUrl) }.getOrNull()
                if (parsed == null || !parsed.protocol.equals("https", ignoreCase = true)) {
                    NtriverResolveResult(errorMessage = "Rejected non-HTTPS download URL returned by archive.")
                } else {
                    NtriverResolveResult(url = rawUrl)
                }
            } else {
                val rateLimited = errMsg.contains("rate", true) || errMsg.contains("limit", true) || errMsg.contains("many", true)
                NtriverResolveResult(
                    errorMessage = errMsg.ifBlank { "Archive did not return a download link for this file." },
                    isRateLimited = rateLimited
                )
            }
        }.getOrElse {
            NtriverResolveResult(errorMessage = "Unexpected response from NTriver Drive. Tap Retry.")
        }
    }

    private fun ensureIndexLinksLoaded(forceRefresh: Boolean) {
        synchronized(indexLock) {
            val now = System.currentTimeMillis()
            if (indexLinksCache.isNotEmpty() && (!forceRefresh || (now - lastIndexRefreshMs) < 5_000L)) return
            runCatching {
                val (code, html) = httpGet(INDEX_PAGE_URL)
                if (code in 200..299 && !isCloudflareChallenge(code, html)) {
                    val parsed = parseIndexPageLinks(html)
                    if (parsed.isNotEmpty()) {
                        indexLinksCache.clear()
                        parsed.forEach { (k, v) -> indexLinksCache[k] = v }
                        lastIndexRefreshMs = System.currentTimeMillis()
                    }
                }
            }
        }
    }

    /**
     * Maps repository tree paths to OS keys, strictly skipping `i18n/` and `-esd` files
     * and prioritizing exact canonical basenames (`download-windows-11.md`/`.mdx`, etc.) over partial matches.
     */
    internal fun mapGithubTreePaths(paths: List<String>): Map<String, String> {
        val exact = LinkedHashMap<String, String>()
        val fallback = LinkedHashMap<String, String>()
        for (rawPath in paths) {
            val path = rawPath.trimStart('/')
            if (!path.endsWith(".md", true) && !path.endsWith(".mdx", true)) continue
            val low = path.lowercase(Locale.US)
            if (low.startsWith("i18n/") || low.contains("/i18n/") ||
                low.contains("-esd") || low.contains("_esd") || low.contains("/esd") ||
                low.contains("office") || low.contains("readme")
            ) continue
            val base = low.substringAfterLast('/').removeSuffix(".mdx").removeSuffix(".md")
            when {
                base == "download-windows-11" || base == "windows-11" -> exact["win11"] = path
                (base.contains("windows-11") || base.contains("windows_11")) && !base.contains("ltsc") && !base.contains("arm") -> fallback.putIfAbsent("win11", path)

                base == "download-windows-10" || base == "windows-10" -> exact["win10"] = path
                (base.contains("windows-10") || base.contains("windows_10")) && !base.contains("ltsc") && !base.contains("cmge") && !base.contains("arm") -> fallback.putIfAbsent("win10", path)

                base == "download-windows-ltsc" || base == "windows-ltsc" -> exact["ltsc"] = path
                base.contains("ltsc") -> fallback.putIfAbsent("ltsc", path)

                base == "download-windows-8.1" || base == "download-windows-8-1" || base == "windows-8.1" -> exact["win81"] = path
                base.contains("windows-8.1") || base.contains("windows_8.1") || base.contains("windows-8-1") -> fallback.putIfAbsent("win81", path)

                base == "download-windows-7" || base == "windows-7" -> exact["win7"] = path
                (base.contains("windows-7") || base.contains("windows_7")) && !base.contains("embedded") -> fallback.putIfAbsent("win7", path)

                base == "download-windows-server-2025" || base == "windows-server-2025" -> exact["server2025"] = path
                base.contains("server-2025") || base.contains("server_2025") -> fallback.putIfAbsent("server2025", path)

                base == "download-windows-server-2022" || base == "windows-server-2022" -> exact["server2022"] = path
                base.contains("server-2022") || base.contains("server_2022") -> fallback.putIfAbsent("server2022", path)

                base == "download-windows-server-2019" || base == "windows-server-2019" -> exact["server2019"] = path
                base.contains("server-2019") || base.contains("server_2019") -> fallback.putIfAbsent("server2019", path)
            }
        }
        val merged = LinkedHashMap<String, String>(fallback)
        merged.putAll(exact)
        return merged
    }

    /**
     * Pure-JVM parser for jsDelivr GitHub package tree JSON (supports BOTH flat `?structure=flat` AND nested directory trees).
     */
    internal fun parseJsDelivrTreeJson(body: String): Map<String, String> {
        val rawPaths = ArrayList<String>()
        fun walk(arr: JSONArray?, prefix: String) {
            if (arr == null) return
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val name = obj.optString("name", "").trim()
                if (name.isBlank()) continue
                val type = obj.optString("type", "")
                val full = if (name.startsWith("/")) name.trimStart('/') else if (prefix.isBlank()) name else "$prefix/$name"
                if (type.equals("directory", ignoreCase = true) || obj.has("files")) {
                    walk(obj.optJSONArray("files"), full)
                } else {
                    rawPaths.add(full)
                }
            }
        }
        runCatching {
            val root = JSONObject(body)
            walk(root.optJSONArray("files"), "")
        }
        return mapGithubTreePaths(rawPaths)
    }

    private fun ensureRepoTreeLoaded(forceRefresh: Boolean, trace: MutableList<String>) {
        synchronized(treeLock) {
            val now = System.currentTimeMillis()
            if (repoTreeCache.isNotEmpty() && (!forceRefresh || (now - lastTreeRefreshMs) < 5_000L)) return

            // 1. Zero-rate-limit jsDelivr CDN tree (tries main then master)
            for ((branch, treeUrl) in listOf("main" to JSDELIVR_TREE_MAIN, "master" to JSDELIVR_TREE_MASTER)) {
                val mapped = runCatching {
                    val (code, body) = httpGet(treeUrl, acceptJson = true)
                    if (code in 200..299 && body.isNotBlank()) parseJsDelivrTreeJson(body) else emptyMap()
                }.getOrDefault(emptyMap())
                if (mapped.isNotEmpty()) {
                    repoTreeCache.clear()
                    mapped.forEach { (k, v) -> repoTreeCache[k] = v }
                    detectedRepoBranch = branch
                    lastTreeRefreshMs = System.currentTimeMillis()
                    return
                }
            }

            // 2. Fallback: GitHub Git Trees API (tries main then master)
            for ((branch, treeUrl) in listOf("main" to GITHUB_TREE_MAIN, "master" to GITHUB_TREE_MASTER)) {
                val mapped = runCatching {
                    val (code, body) = httpGet(treeUrl, acceptJson = true)
                    if (code !in 200..299 || body.isBlank()) {
                        trace.add("ghTree($branch): HTTP $code")
                        emptyMap()
                    } else {
                        val tree = JSONObject(body).optJSONArray("tree") ?: return@runCatching emptyMap()
                        val rawPaths = ArrayList<String>(tree.length())
                        for (i in 0 until tree.length()) {
                            val p = tree.optJSONObject(i)?.optString("path", "").orEmpty()
                            if (p.isNotBlank()) rawPaths.add(p)
                        }
                        mapGithubTreePaths(rawPaths)
                    }
                }.getOrElse { e ->
                    trace.add("ghTree($branch): ${e.javaClass.simpleName}")
                    emptyMap()
                }
                if (mapped.isNotEmpty()) {
                    repoTreeCache.clear()
                    mapped.forEach { (k, v) -> repoTreeCache[k] = v }
                    detectedRepoBranch = branch
                    lastTreeRefreshMs = System.currentTimeMillis()
                    return
                }
            }
        }
    }

    /**
     * Tier 2: Hardened headless Chromium WebView DOM fetcher.
     * Solves Cloudflare JS challenges, hydrates React/Docusaurus tables, and returns rendered DOM HTML.
     */
    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun fetchHtmlViaHeadlessWebView(context: Context, pageUrl: String): Pair<String?, String> {
        return webViewMutex.withLock {
            var errDetail = "timeout"
            val html = withTimeoutOrNull(18_000L) {
                suspendCancellableCoroutine<String?> { cont ->
                    val mainHandler = Handler(Looper.getMainLooper())
                    val done = AtomicBoolean(false)
                    var wv: WebView? = null

                    fun finish(res: String?, detail: String = "ok") {
                        if (!done.compareAndSet(false, true)) return
                        errDetail = detail
                        mainHandler.post {
                            runCatching {
                                wv?.stopLoading()
                                wv?.loadUrl("about:blank")
                                wv?.removeJavascriptInterface("PBPage")
                                wv?.destroy()
                            }
                            wv = null
                        }
                        if (cont.isActive) cont.resume(res)
                    }

                    cont.invokeOnCancellation { finish(null, "cancelled") }

                    mainHandler.post {
                        if (!cont.isActive || done.get()) return@post
                        runCatching {
                            val webView = WebView(context.applicationContext)
                            wv = webView
                            webView.settings.apply {
                                javaScriptEnabled = true
                                domStorageEnabled = true
                                allowFileAccess = false
                                allowContentAccess = false
                                loadsImagesAutomatically = false
                                blockNetworkImage = true
                                userAgentString = UA
                            }
                            WebCookieManager.getInstance().setAcceptCookie(true)
                            WebCookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)
                            val bridge = object {
                                @JavascriptInterface
                                fun onHtml(dom: String) = finish(dom, "ok(${dom.length}B)")

                                @JavascriptInterface
                                fun onFail(reason: String) = finish(null, reason)
                            }
                            webView.addJavascriptInterface(bridge, "PBPage")
                            val fired = AtomicBoolean(false)
                            webView.webViewClient = object : WebViewClient() {
                                override fun onPageFinished(view: WebView?, url: String?) {
                                    val u = url.orEmpty()
                                    if (!u.startsWith(SITE_ORIGIN, true) || fired.get()) return
                                    val titleLow = view?.title.orEmpty().lowercase(Locale.US)
                                    if (titleLow.contains("just a moment") || titleLow.contains("attention required")) return
                                    if (!fired.compareAndSet(false, true)) return
                                    val js = """
                                        (function(){
                                          function grab(attempt){
                                            var t = (document.title || '').toLowerCase();
                                            var cf = !!(document.getElementById('challenge-running') || document.getElementById('cf-challenge-running') || document.getElementById('challenge-form'));
                                            var hasContent = document.querySelectorAll('table, a[href*=".iso"]').length > 0;
                                            if ((cf || t.indexOf('just a moment') >= 0 || !hasContent) && attempt < 5) {
                                              setTimeout(function(){ grab(attempt + 1); }, 1200);
                                              return;
                                            }
                                            var root = document.querySelector('article') || document.querySelector('main') || document.body;
                                            if (root && root.innerHTML) {
                                              PBPage.onHtml(root.innerHTML);
                                            } else {
                                              PBPage.onFail('empty-dom');
                                            }
                                          }
                                          grab(0);
                                        })();
                                    """.trimIndent()
                                    view?.evaluateJavascript(js, null)
                                }

                                override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                                    if (request?.isForMainFrame == true) {
                                        finish(null, "err:${error?.errorCode ?: -1}")
                                    }
                                }

                                override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: SslError?) {
                                    handler?.cancel()
                                    finish(null, "ssl-error")
                                }
                            }
                            webView.loadUrl(pageUrl)
                        }.onFailure { e -> finish(null, e.javaClass.simpleName) }
                    }
                }
            }
            html to errDetail
        }
    }

    private suspend fun fetchSinglePageSections(
        context: Context,
        key: String,
        pagePrefix: String,
        forceRefresh: Boolean,
        trace: MutableList<String>
    ): Pair<List<NtriverReleaseSection>, Boolean> {
        val slug = indexLinksCache[key] ?: FALLBACK_SLUGS[key] ?: return emptyList<NtriverReleaseSection>() to false
        val pageUrl = if (slug.startsWith("https://", true)) slug else "$SITE_ORIGIN/${slug.trimStart('/')}"
        var dnsBlocked = false

        // ── Tier 1: Direct HTTPS ntriver.org ──────────────────────────────────
        var tier1FailedForWebView = false
        val tier1Sections = try {
            val (code, html) = httpGet(pageUrl, referer = INDEX_PAGE_URL)
            if (code in 200..299 && !isCloudflareChallenge(code, html)) {
                val parsed = parseHtmlCatalogPage(html, pagePrefix = pagePrefix)
                if (parsed.isNotEmpty()) {
                    trace.add("$key direct: HTTP $code (${parsed.size} sec)")
                    parsed
                } else {
                    trace.add("$key direct: HTTP $code (0 sec, ${html.length}B)")
                    tier1FailedForWebView = true
                    emptyList()
                }
            } else {
                trace.add("$key direct: HTTP $code${if (isCloudflareChallenge(code, html)) " CF" else ""}")
                tier1FailedForWebView = true
                emptyList()
            }
        } catch (e: UnknownHostException) {
            dnsBlocked = true
            trace.add("$key direct: DNS blocked")
            emptyList()
        } catch (e: Exception) {
            trace.add("$key direct: ${e.javaClass.simpleName}")
            tier1FailedForWebView = true
            emptyList()
        }
        if (tier1Sections.isNotEmpty()) return tier1Sections to false

        // ── Tier 2: Headless Chromium WebView DOM Fetch (skip if DNS blocked) ─
        if (tier1FailedForWebView && !dnsBlocked) {
            val (domHtml, wvDetail) = fetchHtmlViaHeadlessWebView(context, pageUrl)
            if (!domHtml.isNullOrBlank()) {
                val wvSections = parseHtmlCatalogPage(domHtml, pagePrefix = pagePrefix)
                if (wvSections.isNotEmpty()) {
                    trace.add("$key webview: ${wvSections.size} sec")
                    return wvSections to false
                }
                trace.add("$key webview: 0 sec ($wvDetail)")
            } else {
                trace.add("$key webview: $wvDetail")
            }
        }

        // ── Tier 3: Zero-Rate-Limit jsDelivr CDN + GitHub Raw Markdown/MDX ───
        ensureRepoTreeLoaded(forceRefresh, trace)
        val candidatePaths = buildList {
            repoTreeCache[key]?.let { add(it) }
            FALLBACK_MD_PATHS[key]?.forEach { p -> if (p !in this) add(p) }
        }
        val branch = detectedRepoBranch
        for (mdPath in candidatePaths) {
            val cleanPath = mdPath.trimStart('/')
            // 3a. jsDelivr CDN raw mirror (fast, zero GitHub API limits, bypasses ISP blocks on ntriver.org)
            val jsdSections = runCatching {
                val (code, md) = httpGet("$JSDELIVR_RAW_BASE$branch/$cleanPath")
                if (code in 200..299 && md.isNotBlank()) {
                    parseMarkdownCatalogPage(md, pagePrefix = pagePrefix)
                } else emptyList()
            }.getOrDefault(emptyList())
            if (jsdSections.isNotEmpty()) {
                trace.add("$key jsDelivr: ${jsdSections.size} sec ($cleanPath)")
                return jsdSections to dnsBlocked
            }

            // 3b. raw.githubusercontent.com mirror
            val ghSections = runCatching {
                val (code, md) = httpGet("$GITHUB_RAW_BASE$branch/$cleanPath")
                if (code in 200..299 && md.isNotBlank()) {
                    parseMarkdownCatalogPage(md, pagePrefix = pagePrefix)
                } else emptyList()
            }.getOrDefault(emptyList())
            if (ghSections.isNotEmpty()) {
                trace.add("$key ghRaw: ${ghSections.size} sec ($cleanPath)")
                return ghSections to dnsBlocked
            }
        }
        trace.add("$key mirrors: 0/${candidatePaths.size} matched")
        return emptyList<NtriverReleaseSection>() to dnsBlocked
    }

    suspend fun fetchSections(
        context: Context,
        family: WindowsCatalogFamily,
        forceRefresh: Boolean = false,
        onLog: ((LogEntry) -> Unit)? = null
    ): DirectApiResult<List<NtriverReleaseSection>> = withContext(Dispatchers.IO) {
        if (!forceRefresh) {
            sectionsCache[family.id]?.takeIf { it.isNotEmpty() }?.let {
                return@withContext DirectApiResult(data = it)
            }
        }

        ensureIndexLinksLoaded(forceRefresh)

        val trace = java.util.Collections.synchronizedList(ArrayList<String>())
        var anyDnsBlocked = false
        val sections = if (family == WindowsCatalogFamily.WIN_SERVER) {
            coroutineScope {
                listOf(
                    "server2025" to "Server 2025",
                    "server2022" to "Server 2022",
                    "server2019" to "Server 2019"
                ).map { (key, prefix) ->
                    async(Dispatchers.IO) { fetchSinglePageSections(context, key, prefix, forceRefresh, trace) }
                }.awaitAll().flatMap { (list, dns) ->
                    if (dns) anyDnsBlocked = true
                    list
                }
            }
        } else {
            val (list, dns) = fetchSinglePageSections(context, family.ntriverKey, "", forceRefresh, trace)
            anyDnsBlocked = dns
            list
        }

        val traceStr = trace.joinToString(" -> ")
        if (sections.isNotEmpty()) {
            sectionsCache[family.id] = sections
            emitLog(onLog, "Loaded ${family.title}: ${sections.size} section(s) [$traceStr]")
            DirectApiResult(data = sections)
        } else {
            emitLog(onLog, "Failed ${family.title} catalog: $traceStr", isError = true)
            val msg = if (anyDnsBlocked) {
                "Your ISP/DNS could not resolve ntriver.org and mirror fallbacks failed. Enable Android Private DNS (1.1.1.1 or dns.google) or switch to Microsoft CDN.\n($traceStr)"
            } else {
                "Could not load ${family.title} catalog from NTriver Archive.\n($traceStr)"
            }
            DirectApiResult(errorMessage = msg)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun resolveViaHeadlessWebView(context: Context, fileName: String): NtriverResolveResult {
        return webViewMutex.withLock {
            val enc = encodeUrlQueryParam(fileName)
            val apiPath = "/api/drive/generate-link?filename=$enc"
            val body = withTimeoutOrNull(18_000L) {
                suspendCancellableCoroutine<String?> { cont ->
                    val mainHandler = Handler(Looper.getMainLooper())
                    val done = AtomicBoolean(false)
                    var wv: WebView? = null

                    fun finish(res: String?) {
                        if (!done.compareAndSet(false, true)) return
                        mainHandler.post {
                            runCatching {
                                wv?.stopLoading()
                                wv?.loadUrl("about:blank")
                                wv?.removeJavascriptInterface("PBDrive")
                                wv?.destroy()
                            }
                            wv = null
                        }
                        if (cont.isActive) cont.resume(res)
                    }

                    cont.invokeOnCancellation { finish(null) }

                    mainHandler.post {
                        if (!cont.isActive || done.get()) return@post
                        runCatching {
                            val webView = WebView(context.applicationContext)
                            wv = webView
                            webView.settings.apply {
                                javaScriptEnabled = true
                                domStorageEnabled = true
                                allowFileAccess = false
                                allowContentAccess = false
                                loadsImagesAutomatically = false
                                blockNetworkImage = true
                                userAgentString = UA
                            }
                            WebCookieManager.getInstance().setAcceptCookie(true)
                            WebCookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)
                            val bridge = object {
                                @JavascriptInterface
                                fun onResult(json: String) = finish(json)

                                @JavascriptInterface
                                fun onError(err: String) = finish("{\"error\":${JSONObject.quote(err)}}")
                            }
                            webView.addJavascriptInterface(bridge, "PBDrive")
                            val fired = AtomicBoolean(false)
                            webView.webViewClient = object : WebViewClient() {
                                override fun onPageFinished(view: WebView?, url: String?) {
                                    val u = url.orEmpty()
                                    if (!u.startsWith(SITE_ORIGIN, true) || fired.get()) return
                                    val pageTitle = view?.title.orEmpty().lowercase(Locale.US)
                                    if (pageTitle.contains("just a moment") || pageTitle.contains("attention required")) {
                                        return
                                    }
                                    if (!fired.compareAndSet(false, true)) return
                                    val js = """
                                        (function(){
                                          function run(attempt){
                                            var titleLow = (document.title || '').toLowerCase();
                                            var hasCfDom = !!(document.getElementById('challenge-running') || document.getElementById('cf-challenge-running') || document.getElementById('challenge-form'));
                                            if ((hasCfDom || titleLow.indexOf('just a moment') >= 0 || titleLow.indexOf('attention required') >= 0) && attempt < 4) {
                                              setTimeout(function(){ run(attempt + 1); }, 1500);
                                              return;
                                            }
                                            fetch(${JSONObject.quote(apiPath)}, {credentials:'include', headers:{'Accept':'application/json'}})
                                              .then(function(r){ return r.text(); })
                                              .then(function(t){
                                                var s = (t || '').trim();
                                                if (!s.startsWith('{') && attempt < 4) {
                                                  setTimeout(function(){ run(attempt + 1); }, 1500);
                                                } else {
                                                  PBDrive.onResult(t);
                                                }
                                              })
                                              .catch(function(e){ PBDrive.onError(String(e)); });
                                          }
                                          run(0);
                                        })();
                                    """.trimIndent()
                                    view?.evaluateJavascript(js, null)
                                }

                                override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                                    if (request?.isForMainFrame == true) finish(null)
                                }

                                override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: SslError?) {
                                    handler?.cancel()
                                    finish(null)
                                }
                            }
                            webView.loadUrl("$SITE_ORIGIN/drive?$enc")
                        }.onFailure { finish(null) }
                    }
                }
            } ?: return@withLock NtriverResolveResult(errorMessage = "Timed out generating direct link from NTriver Drive. Tap Retry.", isRateLimited = true)

            parseGenerateLinkJson(body)
        }
    }

    suspend fun resolveDownloadUrl(context: Context, entry: NtriverIsoEntry): NtriverResolveResult =
        withContext(Dispatchers.IO) {
            entry.directStaticUrl?.takeIf { it.startsWith("https://", true) }?.let { staticUrl ->
                return@withContext NtriverResolveResult(url = staticUrl, isMicrosoftStatic = true)
            }
            val driveFile = entry.ntriverDriveFilename?.takeIf { it.isNotBlank() }
                ?: return@withContext NtriverResolveResult(errorMessage = "No downloadable link available for ${entry.fileName}.")
            resolveDriveFilename(context, driveFile)
        }

    suspend fun resolveByRefreshKey(context: Context, refreshKey: String): NtriverResolveResult =
        withContext(Dispatchers.IO) {
            val driveFile = extractDriveFilenameFromRefreshKey(refreshKey)
                ?: return@withContext NtriverResolveResult(errorMessage = "Unsupported link refresh key.")
            resolveDriveFilename(context, driveFile)
        }

    private suspend fun resolveDriveFilename(context: Context, driveFile: String): NtriverResolveResult {
        val enc = encodeUrlQueryParam(driveFile)
        val apiUrl = "$GENERATE_LINK_API?filename=$enc"
        val directRes = try {
            val (code, body) = httpGet(apiUrl, acceptJson = true, referer = "$SITE_ORIGIN/drive?$enc")
            when {
                code == 429 -> NtriverResolveResult(
                    isRateLimited = true,
                    errorMessage = "NTriver Drive is temporarily rate-limiting requests (HTTP 429). Wait a moment or switch networks and retry."
                )
                code == 403 || code == 503 || isCloudflareChallenge(code, body) -> NtriverResolveResult(
                    needsWebViewFallback = true,
                    errorMessage = "NTriver Drive blocked the direct request (HTTP $code). Tap Retry or try switching networks."
                )
                else -> parseGenerateLinkJson(body)
            }
        } catch (_: UnknownHostException) {
            NtriverResolveResult(
                errorMessage = "Your ISP/DNS is blocking ntriver.org. Enable Android Private DNS (one.one.one.one or dns.google) or a VPN, or pick an MS Static / Microsoft CDN ISO."
            )
        } catch (e: Exception) {
            NtriverResolveResult(errorMessage = "Network error contacting NTriver Drive: ${e.message ?: "connection failed"}")
        }

        if (directRes.url != null) return directRes
        if (directRes.needsWebViewFallback) {
            val webRes = resolveViaHeadlessWebView(context, driveFile)
            if (webRes.url != null) return webRes
            if (!webRes.errorMessage.isNullOrBlank()) return webRes
        }
        return directRes
    }
}
