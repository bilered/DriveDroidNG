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
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
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
 * Direct Windows RTM ISO archive client for ntriver.org (CC-BY-4.0) with:
 * - Zero-backtracking O(N) linear HTML scanner (safe on 2+ MB Windows 11/10/8.1 pages without ICU regex overflow)
 * - Dynamic index route discovery (`/download-windows-office`) + verified fallback slugs
 * - Fixed GitHub tree raw-Markdown/MDX fallback (`ntriver-org/ntriver-org-site`, excluding `i18n/` and `-esd` pages)
 * - Parent H2 -> Child H3 hierarchy extracting clean `majorVersion` + `editionLabel` sub-categories
 * - Automatic preference for permanent Microsoft Static Links before `/api/drive/generate-link`
 * - Hardened headless WebView fallback if Cloudflare challenges the JSON link endpoint
 * - Strict `.iso`-only & bootable-PC filtering (rejects `.esd`, IA-64, ARM32 RT, SDK/WDK/Symbols)
 */
object NtriverCatalog {

    private const val SITE_ORIGIN = "https://ntriver.org"
    private const val INDEX_PAGE_URL = "$SITE_ORIGIN/download-windows-office"
    private const val GENERATE_LINK_API = "$SITE_ORIGIN/api/drive/generate-link"
    private const val GITHUB_TREE_API = "https://api.github.com/repos/ntriver-org/ntriver-org-site/git/trees/main?recursive=1"
    private const val GITHUB_RAW_BASE = "https://raw.githubusercontent.com/ntriver-org/ntriver-org-site/main/"
    private const val REFRESH_PREFIX = "ntriver:"
    private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/136.0.0.0 Safari/537.36"

    private val SHA256_RE = Regex("\\b([0-9a-fA-F]{64})\\b")
    private val ISO_FILENAME_RE = Regex("""([A-Za-z0-9._()+\-]+\.iso)\b""", RegexOption.IGNORE_CASE)
    private val HTML_TAG_RE = Regex("<[^>]+>")
    private val MULTISPACE_RE = Regex("\\s+")
    private val MD_LINK_RE = Regex("""\[([^\]]+)]\((https?://[^)\s]+)\)""")

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
        "win11" to listOf("docs/download-windows-11.md", "docs/download-windows-11.mdx"),
        "win10" to listOf("docs/download-windows-10.md", "docs/download-windows-10.mdx"),
        "ltsc" to listOf("docs/download-windows-ltsc.md", "docs/download-windows-ltsc.mdx"),
        "win81" to listOf("docs/download-windows-8.1.md", "docs/download-windows-8.1.mdx"),
        "win7" to listOf("docs/download-windows-7.md", "docs/download-windows-7.mdx"),
        "server2025" to listOf("docs/download-windows-server-2025.md", "docs/download-windows-server-2025.mdx"),
        "server2022" to listOf("docs/download-windows-server-2022.md", "docs/download-windows-server-2022.mdx"),
        "server2019" to listOf("docs/download-windows-server-2019.md", "docs/download-windows-server-2019.mdx")
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
    @Volatile private var lastIndexRefreshMs = 0L
    @Volatile private var lastTreeRefreshMs = 0L
    private val indexLinksCache = ConcurrentHashMap<String, String>()
    private val sectionsCache = ConcurrentHashMap<String, List<NtriverReleaseSection>>()
    private val githubTreeCache = ConcurrentHashMap<String, String>()

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
            low.contains("arm64") || low.contains("aarch64") -> "ARM64"
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

    /**
     * Derives clean 2-level hierarchy: `(majorVersion, editionLabel)` for decluttered UI sub-categories.
     * Examples:
     * - Parent H2 "Windows 11 25H2" + H3 "Consumer (November 2026)" -> ("25H2", "Consumer (November 2026)")
     * - H2 "Windows 8.1 with Update 3 - Core / Pro" -> ("Update 3", "Core / Pro")
     * - H2 "Windows Server 2022 (November 2026)" with prefix "Server 2022" -> ("Server 2022", "November 2026")
     * - H2 "Windows 11 Enterprise LTSC 2024" -> ("Win 11 LTSC 2024", "Enterprise LTSC 2024")
     */
    internal fun deriveVersionAndEdition(
        parentH2: String,
        headingTitle: String,
        pagePrefix: String = ""
    ): Pair<String, String> {
        val cleanHead = cleanSectionTitle(headingTitle)
        val cleanParent = cleanSectionTitle(parentH2)

        // 1. Server multi-page merge uses pagePrefix ("Server 2025" / "Server 2022" / "Server 2019") as Main Version
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

        // 2. Explicit H2 -> H3 hierarchy (e.g., H2 = "Windows 11 25H2", H3 = "Consumer (November 2026)")
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

        // 3. Single H2 with " - " separator (e.g. "Windows 8.1 with Update 3 - Core / Pro", "Windows 11 26H2 - Consumer")
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

        // 4. LTSC / LTSB versions
        val low = cleanHead.lowercase(Locale.US)
        if (low.contains("ltsc") || low.contains("ltsb")) {
            val osTag = if (low.contains("windows 11") || low.contains("win 11") || low.contains("2024")) "Win 11" else "Win 10"
            val yr = Regex("""\b(2024|2021|2019|2016|2015)\b""").find(cleanHead)?.value.orEmpty()
            val kind = if (low.contains("ltsb")) "LTSB" else "LTSC"
            val major = listOf(osTag, kind, yr).filter { it.isNotBlank() }.joinToString(" ")
            val ed = stripLeadingOsPrefix(cleanHead).ifBlank { cleanHead }
            return major.take(28) to ed.take(40)
        }

        // 5. Windows 11/10 Feature Update token (26H2, 25H2, 24H2, 22H2, 2004, 1909, 1809, etc.)
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

        // 6. Windows 7 / 8.1 SP1 / Update fallback
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

    private fun extractFirstTagInner(html: String, tag: String): String? {
        val open = findTagOpen(html, tag, 0) ?: return null
        val closeNeedle = "</$tag>"
        val closeIdx = html.indexOf(closeNeedle, open.second, ignoreCase = true)
        return if (closeIdx > open.second) html.substring(open.second, closeIdx) else null
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
        val hrefRe = Regex("""\bhref\s*=\s*(?:"([^"]*)"|'([^']*)')""", RegexOption.IGNORE_CASE)
        var cursor = 0
        while (cursor < html.length) {
            val open = findTagOpen(html, "a", cursor) ?: break
            val openTag = html.substring(open.first, open.second)
            val closeIdx = html.indexOf("</a>", open.second, ignoreCase = true)
            if (closeIdx < 0) break
            val hrefMatch = hrefRe.find(openTag)
            val href = (hrefMatch?.groupValues?.getOrNull(1)?.ifEmpty { hrefMatch.groupValues.getOrNull(2) }).orEmpty()
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

    internal fun extractDriveFilenameFromUrl(href: String): String? {
        val clean = decodeHtmlEntities(href).trim()
        val fullUrlStr = when {
            clean.startsWith("/drive", ignoreCase = true) -> "$SITE_ORIGIN$clean"
            clean.startsWith("https://", ignoreCase = true) -> clean
            else -> return null
        }
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
            isHostOrSubdomain(host, "azureedge.net")
        return isMsHost && parsed.path.orEmpty().lowercase(Locale.US).endsWith(".iso")
    }

    internal fun isNtriverDriveHref(href: String): Boolean {
        val clean = decodeHtmlEntities(href).trim()
        val full = when {
            clean.startsWith("/drive", ignoreCase = true) -> "$SITE_ORIGIN$clean"
            clean.startsWith("https://", ignoreCase = true) -> clean
            else -> return false
        }
        val parsed = runCatching { URL(full) }.getOrNull() ?: return false
        if (!isHostOrSubdomain(parsed.host.orEmpty(), "ntriver.org")) return false
        val path = parsed.path.orEmpty()
        return path.equals("/drive", ignoreCase = true) || path.startsWith("/drive/", ignoreCase = true)
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
        val buildMatch = Regex("""Build\s*:\s*([^\n•|]+)""", RegexOption.IGNORE_CASE).find(clean)
        val buildBadge = buildMatch?.groupValues?.getOrNull(1)?.trim()
            ?.replace("[", "")?.replace("]", "")?.take(56).orEmpty()
        val noteMatch = Regex("""Note\s*:\s*([^\n]+)""", RegexOption.IGNORE_CASE).find(clean)
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
                        language = col0.ifBlank { "English" },
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
     * Zero-backtracking linear parser for rendered Docusaurus HTML pages on ntriver.org.
     * Preserves H2 -> H3 hierarchy (e.g., `Windows 11 25H2` -> `Consumer (November 2026)`) and never overflows Android's regex stack.
     */
    fun parseHtmlCatalogPage(html: String, pagePrefix: String = ""): List<NtriverReleaseSection> {
        val scoped = extractFirstTagInner(html, "article")
            ?: extractFirstTagInner(html, "main")
            ?: html
        val cleanedHtml = stripTagBlocksLinear(stripTagBlocksLinear(scoped, "script"), "style")

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

            val firstTableOpen = findTagOpen(block, "table", 0) ?: continue
            val introHtml = block.substring(0, firstTableOpen.first)
            val introLines = introHtml
                .replace(Regex("""</(?:p|li|div|br)\s*>|<br\s*/?>""", RegexOption.IGNORE_CASE), "\n")
                .let { decodeHtmlEntities(HTML_TAG_RE.replace(it, " ")) }
            val (buildBadge, note) = extractMetaFromIntro(introLines)

            val parentForHeading = if (h.level > 2) activeH2Clean else ""
            val combinedTitleForFilter = if (parentForHeading.isNotBlank() && !cleanTitle.contains(parentForHeading, true)) {
                "$parentForHeading - $cleanTitle"
            } else cleanTitle
            val entries = parseHtmlTablesFromBlock(block, combinedTitleForFilter)

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
     * Pure-JVM fallback parser for raw GitHub Markdown / MDX pages from `ntriver-org/ntriver-org-site`.
     * Supports H2 -> H3 hierarchy, pipe tables, AND inline HTML `<table>` blocks inside MDX docs.
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
                // Also support MDX files that render inline HTML <table> blocks!
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

            if (!line.startsWith("|")) {
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
        return sections
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
     * Maps GitHub repository tree paths to OS keys, strictly skipping `i18n/` and `-esd` files
     * and prioritizing exact canonical basenames (`download-windows-11.md`/`.mdx`, etc.) over partial matches.
     */
    internal fun mapGithubTreePaths(paths: List<String>): Map<String, String> {
        val exact = LinkedHashMap<String, String>()
        val fallback = LinkedHashMap<String, String>()
        for (path in paths) {
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

    private fun ensureGithubTreeLoaded(forceRefresh: Boolean) {
        synchronized(treeLock) {
            val now = System.currentTimeMillis()
            if (githubTreeCache.isNotEmpty() && (!forceRefresh || (now - lastTreeRefreshMs) < 5_000L)) return
            runCatching {
                val (code, body) = httpGet(GITHUB_TREE_API, acceptJson = true)
                if (code !in 200..299 || body.isBlank()) return
                val tree = JSONObject(body).optJSONArray("tree") ?: return
                val rawPaths = ArrayList<String>(tree.length())
                for (i in 0 until tree.length()) {
                    val p = tree.optJSONObject(i)?.optString("path", "").orEmpty()
                    if (p.isNotBlank()) rawPaths.add(p)
                }
                val mapped = mapGithubTreePaths(rawPaths)
                if (mapped.isNotEmpty()) {
                    githubTreeCache.clear()
                    mapped.forEach { (k, v) -> githubTreeCache[k] = v }
                    lastTreeRefreshMs = System.currentTimeMillis()
                }
            }
        }
    }

    private fun fetchSinglePageSections(key: String, pagePrefix: String, forceRefresh: Boolean): List<NtriverReleaseSection> {
        val slug = indexLinksCache[key] ?: FALLBACK_SLUGS[key] ?: return emptyList()
        val pageUrl = if (slug.startsWith("https://", true)) slug else "$SITE_ORIGIN/${slug.trimStart('/')}"
        val htmlSections = runCatching {
            val (code, html) = httpGet(pageUrl, referer = INDEX_PAGE_URL)
            if (code in 200..299 && !isCloudflareChallenge(code, html)) {
                parseHtmlCatalogPage(html, pagePrefix = pagePrefix)
            } else emptyList()
        }.getOrDefault(emptyList())
        if (htmlSections.isNotEmpty()) return htmlSections

        // Fallback: dynamically locate Markdown/MDX file in ntriver-org/ntriver-org-site via GitHub tree API (+ static .md/.mdx backups)
        ensureGithubTreeLoaded(forceRefresh)
        val candidatePaths = buildList {
            githubTreeCache[key]?.let { add(it) }
            FALLBACK_MD_PATHS[key]?.forEach { p -> if (p !in this) add(p) }
        }
        for (mdPath in candidatePaths) {
            val mdSections = runCatching {
                val (code, md) = httpGet("$GITHUB_RAW_BASE${mdPath.trimStart('/')}")
                if (code in 200..299 && md.isNotBlank()) {
                    parseMarkdownCatalogPage(md, pagePrefix = pagePrefix)
                } else emptyList()
            }.getOrDefault(emptyList())
            if (mdSections.isNotEmpty()) return mdSections
        }
        return emptyList()
    }

    suspend fun fetchSections(
        family: WindowsCatalogFamily,
        forceRefresh: Boolean = false
    ): DirectApiResult<List<NtriverReleaseSection>> = withContext(Dispatchers.IO) {
        if (!forceRefresh) {
            sectionsCache[family.id]?.takeIf { it.isNotEmpty() }?.let {
                return@withContext DirectApiResult(data = it)
            }
        }

        ensureIndexLinksLoaded(forceRefresh)

        val sections = if (family == WindowsCatalogFamily.WIN_SERVER) {
            coroutineScope {
                listOf(
                    "server2025" to "Server 2025",
                    "server2022" to "Server 2022",
                    "server2019" to "Server 2019"
                ).map { (key, prefix) ->
                    async(Dispatchers.IO) { fetchSinglePageSections(key, prefix, forceRefresh) }
                }.awaitAll().flatten()
            }
        } else {
            fetchSinglePageSections(family.ntriverKey, "", forceRefresh)
        }

        if (sections.isNotEmpty()) {
            sectionsCache[family.id] = sections
            DirectApiResult(data = sections)
        } else {
            DirectApiResult(
                errorMessage = "Could not load ${family.title} catalog from NTriver Archive. Check your connection or switch networks and tap Retry."
            )
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun resolveViaHeadlessWebView(context: Context, fileName: String): NtriverResolveResult {
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
        } ?: return NtriverResolveResult(errorMessage = "Timed out generating direct link from NTriver Drive. Tap Retry.", isRateLimited = true)

        return parseGenerateLinkJson(body)
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
        val directRes = runCatching {
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
        }.getOrElse { e ->
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
