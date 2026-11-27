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
    val buildBadge: String = "",
    val note: String = "",
    val isRecommended: Boolean = true,
    val entries: List<NtriverIsoEntry>
) {
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
 * - Dynamic index page route discovery (`/download-windows-office`) + verified fallback slugs
 * - Primary HTML table parser + GitHub tree raw-Markdown fallback (`ntriver-org/ntriver-org-site`)
 * - Automatic preference for permanent Microsoft Static Links before `/api/drive/generate-link`
 * - Short-lived headless WebView fallback if Cloudflare challenges the JSON link endpoint
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
        " n ", "(n)", "editions n", "single language", "foundation", "essentials"
    )

    private val FALLBACK_MD_PATHS = mapOf(
        "win11" to "docs/download-windows-11.md",
        "win10" to "docs/download-windows-10.md",
        "ltsc" to "docs/download-windows-ltsc.md",
        "win81" to "docs/download-windows-8.1.md",
        "win7" to "docs/download-windows-7.md",
        "server2025" to "docs/download-windows-server-2025.md",
        "server2022" to "docs/download-windows-server-2022.md",
        "server2019" to "docs/download-windows-server-2019.md"
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
            .replace(MULTISPACE_RE, " ")
            .trim()

    internal fun buildShortChipLabel(fullTitle: String, pagePrefix: String = ""): String {
        var s = cleanSectionTitle(fullTitle)
        listOf(
            "Windows 11 ", "Windows 10 / 11 ", "Windows 10 ", "Windows 8.1 ",
            "Windows 7 ", "Windows Server "
        ).forEach { pref ->
            if (s.startsWith(pref, ignoreCase = true) && s.length > pref.length + 2) {
                s = s.substring(pref.length).trim()
            }
        }
        s = s.removePrefix("-").removePrefix("—").removePrefix("–").trim()
        val combined = if (pagePrefix.isNotBlank() && !s.contains(pagePrefix, ignoreCase = true)) {
            "$pagePrefix $s"
        } else s
        return if (combined.length > 32) combined.take(30).trimEnd() + "…" else combined.ifBlank { fullTitle.take(28) }
    }

    private fun isCloudflareChallenge(code: Int, body: String): Boolean {
        val low = body.lowercase(Locale.US)
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
            connectTimeout = 12_000
            readTimeout = 18_000
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
        val aRe = Regex("""<a\b[^>]*href=["']([^"']+)["'][^>]*>([\s\S]*?)</a>""", RegexOption.IGNORE_CASE)
        for (m in aRe.findAll(html)) {
            val rawHref = decodeHtmlEntities(m.groupValues[1]).trim()
            if (rawHref.startsWith("#") || rawHref.startsWith("mailto:") || rawHref.startsWith("javascript:")) continue
            val path = when {
                rawHref.startsWith(SITE_ORIGIN, ignoreCase = true) -> rawHref.substring(SITE_ORIGIN.length)
                rawHref.startsWith("/") -> rawHref
                else -> continue
            }.substringBefore('#').substringBefore('?').trimEnd('/')
            if (path.isBlank() || path == "/download-windows-office") continue
            val lowPath = path.lowercase(Locale.US)
            val lowText = stripHtml(m.groupValues[2]).lowercase(Locale.US)
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
        // Preserve literal `+` in ISO filenames while decoding `%20` / `%XX` percent-escapes cleanly
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
        // Merge accidental duplicate (arch, fileName) rows so each ISO appears once with best available metadata/links
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
            .replace(Regex("""\[([^\]]+)]\(https?://[^)\s]+\)"""), "$1")
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

    /**
     * Pure-JVM parser for rendered Docusaurus HTML pages on ntriver.org.
     * Skips top `Index` / `Misc` tables, filters non-bootable/non-ISO rows, and extracts SHA-256 & links.
     */
    fun parseHtmlCatalogPage(html: String, pagePrefix: String = ""): List<NtriverReleaseSection> {
        // Isolate <article> or <main> if present to avoid sidebar/nav false matches
        val scoped = Regex("""<article\b[^>]*>([\s\S]*?)</article>""", RegexOption.IGNORE_CASE).find(html)?.groupValues?.get(1)
            ?: Regex("""<main\b[^>]*>([\s\S]*?)</main>""", RegexOption.IGNORE_CASE).find(html)?.groupValues?.get(1)
            ?: html
        val cleanedHtml = scoped
            .replace(Regex("""<script\b[^>]*>[\s\S]*?</script>""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""<style\b[^>]*>[\s\S]*?</style>""", RegexOption.IGNORE_CASE), "")

        val headingRe = Regex("""<h([2-4])\b[^>]*>([\s\S]*?)</h\1>""", RegexOption.IGNORE_CASE)
        val headings = headingRe.findAll(cleanedHtml).toList()
        if (headings.isEmpty()) return emptyList()

        val tableRe = Regex("""<table\b[^>]*>([\s\S]*?)</table>""", RegexOption.IGNORE_CASE)
        val trRe = Regex("""<tr\b[^>]*>([\s\S]*?)</tr>""", RegexOption.IGNORE_CASE)
        val cellRe = Regex("""<t[dh]\b[^>]*>([\s\S]*?)</t[dh]>""", RegexOption.IGNORE_CASE)
        val anchorRe = Regex("""<a\b[^>]*href=["']([^"']+)["'][^>]*>([\s\S]*?)</a>""", RegexOption.IGNORE_CASE)

        val sections = mutableListOf<NtriverReleaseSection>()
        for (i in headings.indices) {
            val hMatch = headings[i]
            val rawHeadingText = stripHtml(hMatch.groupValues[2])
            val cleanTitle = cleanSectionTitle(rawHeadingText)
            if (cleanTitle.isBlank()) continue
            val lowTitle = cleanTitle.lowercase(Locale.US)
            if (lowTitle == "index" || lowTitle == "misc" || lowTitle == "missing files" || lowTitle == "overview") continue
            if (isExcludedSectionOrEntry(cleanTitle)) continue

            val blockStart = hMatch.range.last + 1
            val blockEnd = if (i + 1 < headings.size) headings[i + 1].range.first else cleanedHtml.length
            val block = cleanedHtml.substring(blockStart, blockEnd)

            val firstTable = tableRe.find(block) ?: continue
            val introHtml = block.substring(0, firstTable.range.first)
            val introLines = introHtml
                .replace(Regex("""</(?:p|li|div|br)\s*>|<br\s*/?>""", RegexOption.IGNORE_CASE), "\n")
                .let { decodeHtmlEntities(HTML_TAG_RE.replace(it, " ")) }
            val (buildBadge, note) = extractMetaFromIntro(introLines)

            val entries = mutableListOf<NtriverIsoEntry>()
            for (tblMatch in tableRe.findAll(block)) {
                val tblBody = tblMatch.groupValues[1]
                val rows = trRe.findAll(tblBody).toList()
                if (rows.isEmpty()) continue
                val headerText = stripHtml(rows.first().groupValues[1]).lowercase(Locale.US)
                if (headerText.contains("windows version") && headerText.contains("iso links")) continue

                for (rowIdx in rows.indices) {
                    val cells = cellRe.findAll(rows[rowIdx].groupValues[1]).map { it.groupValues[1] }.toList()
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
                    for (am in anchorRe.findAll(detailsHtml)) {
                        val href = decodeHtmlEntities(am.groupValues[1]).trim()
                        val label = stripHtml(am.groupValues[2]).lowercase(Locale.US)
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
                    if (isExcludedSectionOrEntry(cleanTitle, archHint, fileName)) continue
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

            if (entries.isNotEmpty()) {
                sections.add(
                    NtriverReleaseSection(
                        id = "${pagePrefix}_${cleanTitle}_${sections.size}",
                        title = if (pagePrefix.isNotBlank() && !cleanTitle.contains(pagePrefix, true)) "$pagePrefix · $cleanTitle" else cleanTitle,
                        shortChipLabel = buildShortChipLabel(cleanTitle, pagePrefix),
                        buildBadge = buildBadge,
                        note = note,
                        isRecommended = isRecommendedSection(rawHeadingText, cleanTitle),
                        entries = disambiguateEntries(entries)
                    )
                )
            }
        }
        return sections
    }

    /**
     * Pure-JVM fallback parser for raw GitHub Markdown pages from `ntriver-org/ntriver-org-site`.
     */
    fun parseMarkdownCatalogPage(markdown: String, pagePrefix: String = ""): List<NtriverReleaseSection> {
        val lines = markdown.replace("\r\n", "\n").split('\n')
        val sections = mutableListOf<NtriverReleaseSection>()
        val mdLinkRe = Regex("""\[([^\]]+)]\((https?://[^)\s]+)\)""")

        var curRawHeading = ""
        var curTitle = ""
        val curIntro = StringBuilder()
        val curEntries = mutableListOf<NtriverIsoEntry>()
        var skipCurrentSection = true

        fun flushSection() {
            if (!skipCurrentSection && curTitle.isNotBlank() && curEntries.isNotEmpty()) {
                val (buildBadge, note) = extractMetaFromIntro(curIntro.toString())
                sections.add(
                    NtriverReleaseSection(
                        id = "${pagePrefix}_${curTitle}_${sections.size}",
                        title = if (pagePrefix.isNotBlank() && !curTitle.contains(pagePrefix, true)) "$pagePrefix · $curTitle" else curTitle,
                        shortChipLabel = buildShortChipLabel(curTitle, pagePrefix),
                        buildBadge = buildBadge,
                        note = note,
                        isRecommended = isRecommendedSection(curRawHeading, curTitle),
                        entries = disambiguateEntries(curEntries.toList())
                    )
                )
            }
            curIntro.setLength(0)
            curEntries.clear()
        }

        for (rawLine in lines) {
            val line = rawLine.trim()
            val hMatch = Regex("""^#{2,4}\s+(.+)$""").find(line)
            if (hMatch != null) {
                flushSection()
                curRawHeading = hMatch.groupValues[1].trim()
                curTitle = cleanSectionTitle(curRawHeading)
                val low = curTitle.lowercase(Locale.US)
                skipCurrentSection = curTitle.isBlank() ||
                    low == "index" || low == "misc" || low == "missing files" || low == "overview" ||
                    isExcludedSectionOrEntry(curTitle)
                continue
            }
            if (skipCurrentSection) continue

            if (!line.startsWith("|")) {
                if (curEntries.isEmpty() && line.isNotBlank()) {
                    curIntro.append(line.removePrefix("*").removePrefix("-").trim()).append('\n')
                }
                continue
            }

            val cols = line.trim('|')
                .split(Regex("""(?<!\\)\|"""))
                .map { it.replace("\\|", "|").trim() }
            if (cols.size < 2) continue
            if (Regex("""^:?-{3,}:?$""").matches(cols[0]) ||
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
            for (lm in mdLinkRe.findAll(detailsRaw)) {
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
            // Also scan inline HTML <a> inside MDX tables unconditionally so mixed Markdown+HTML rows find MS Static links
            val aRe = Regex("""<a\b[^>]*href=["']([^"']+)["'][^>]*>([\s\S]*?)</a>""", RegexOption.IGNORE_CASE)
            for (am in aRe.findAll(detailsRaw)) {
                val href = decodeHtmlEntities(am.groupValues[1]).trim()
                val lbl = stripHtml(am.groupValues[2]).lowercase(Locale.US)
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
            if (isExcludedSectionOrEntry(curTitle, archRaw, fileName)) continue
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

    internal fun mapGithubTreePaths(paths: List<String>): Map<String, String> {
        val mapped = LinkedHashMap<String, String>()
        for (path in paths) {
            if (!path.endsWith(".md", true) && !path.endsWith(".mdx", true)) continue
            val low = path.lowercase(Locale.US)
            when {
                low.contains("ltsc") -> mapped.putIfAbsent("ltsc", path)
                (low.contains("windows-11") || low.contains("windows_11")) && !low.contains("ltsc") -> mapped.putIfAbsent("win11", path)
                (low.contains("windows-10") || low.contains("windows_10")) && !low.contains("ltsc") && !low.contains("cmge") -> mapped.putIfAbsent("win10", path)
                low.contains("windows-8.1") || low.contains("windows_8.1") || low.contains("windows-8-1") -> mapped.putIfAbsent("win81", path)
                (low.contains("windows-7") || low.contains("windows_7")) && !low.contains("embedded") -> mapped.putIfAbsent("win7", path)
                low.contains("server-2025") || low.contains("server_2025") -> mapped.putIfAbsent("server2025", path)
                low.contains("server-2022") || low.contains("server_2022") -> mapped.putIfAbsent("server2022", path)
                low.contains("server-2019") || low.contains("server_2019") -> mapped.putIfAbsent("server2019", path)
            }
        }
        return mapped
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

        // Fallback: dynamically locate Markdown file in ntriver-org/ntriver-org-site via GitHub tree API (+ static backup if GitHub API is rate-limited)
        ensureGithubTreeLoaded(forceRefresh)
        val mdPath = githubTreeCache[key] ?: FALLBACK_MD_PATHS[key] ?: return emptyList()
        return runCatching {
            val (code, md) = httpGet("$GITHUB_RAW_BASE${mdPath.trimStart('/')}")
            if (code in 200..299 && md.isNotBlank()) {
                parseMarkdownCatalogPage(md, pagePrefix = pagePrefix)
            } else emptyList()
        }.getOrDefault(emptyList())
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
