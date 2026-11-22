package com.pocketboot.app

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

data class DirectFileUrl(
    val sha1Hex: String,
    val url: String,
    val expireEpochSec: Long
)

data class DirectSyncFileMeta(
    val name: String,
    val sizeBytes: Long,
    val sha1Hex: String
)

data class DirectSyncBuildResult(
    val build: WindowsChannelBuild,
    val revision: Int,
    val ring: String,
    val sku: Int,
    val checkBuild: String,
    val files: List<DirectSyncFileMeta>
)

object MicrosoftUupClient {
    private const val ENDPOINT_CLIENT = "https://fe3.delivery.mp.microsoft.com/ClientWebService/client.asmx"
    private const val ENDPOINT_SECURED = "https://fe3.delivery.mp.microsoft.com/ClientWebService/client.asmx/secured"
    private const val SOAP_CONTENT_TYPE = "application/soap+xml; charset=utf-8"
    private const val WU_UA = "Windows-Update-Agent/10.0.10011.16384 Client-Protocol/1.40"

    private val random = SecureRandom()
    @Volatile private var cachedCookieData: String? = null
    @Volatile private var cachedCookieExpiryMs: Long = 0L

    // Cache file lists discovered during SyncUpdates so offline/direct mode needs zero UUPdump calls
    val syncFilesCache = ConcurrentHashMap<String, List<DirectSyncFileMeta>>()
    val syncMetaCache = ConcurrentHashMap<String, DirectSyncBuildResult>()

    private val LANG_DISPLAY_NAMES = mapOf(
        "en-us" to "English (United States)", "en-gb" to "English (United Kingdom)",
        "ar-sa" to "Arabic (Saudi Arabia)", "bg-bg" to "Bulgarian", "cs-cz" to "Czech",
        "da-dk" to "Danish", "de-de" to "German", "el-gr" to "Greek",
        "es-es" to "Spanish (Spain)", "es-mx" to "Spanish (Mexico)", "et-ee" to "Estonian",
        "fi-fi" to "Finnish", "fr-ca" to "French (Canada)", "fr-fr" to "French (France)",
        "he-il" to "Hebrew", "hr-hr" to "Croatian", "hu-hu" to "Hungarian",
        "it-it" to "Italian", "ja-jp" to "Japanese", "ko-kr" to "Korean",
        "lt-lt" to "Lithuanian", "lv-lv" to "Latvian", "nb-no" to "Norwegian (Bokmal)",
        "nl-nl" to "Dutch", "pl-pl" to "Polish", "pt-br" to "Portuguese (Brazil)",
        "pt-pt" to "Portuguese (Portugal)", "ro-ro" to "Romanian", "ru-ru" to "Russian",
        "sk-sk" to "Slovak", "sl-si" to "Slovenian", "sr-latn-rs" to "Serbian (Latin)",
        "sv-se" to "Swedish", "th-th" to "Thai", "tr-tr" to "Turkish",
        "uk-ua" to "Ukrainian", "zh-cn" to "Chinese (Simplified)", "zh-tw" to "Chinese (Traditional)"
    )

    private val EDITION_DISPLAY_NAMES = mapOf(
        "core" to "Windows Home",
        "coren" to "Windows Home N",
        "coresinglelanguage" to "Windows Home Single Language",
        "corecountryspecific" to "Windows Home China",
        "professional" to "Windows Pro",
        "professionaln" to "Windows Pro N",
        "ppipro" to "Windows Team",
        "serverstandard" to "Windows Server Standard",
        "serverstandardcore" to "Windows Server Standard (Core)",
        "serverdatacenter" to "Windows Server Datacenter",
        "serverdatacentercore" to "Windows Server Datacenter (Core)",
        "serverazurestackhcicor" to "Azure Stack HCI"
    )

    private val EXCLUDED_FILE_PATTERNS = listOf(
        "-express.cab"
    )

    fun isUnusablePackageName(fileName: String): Boolean {
        val low = fileName.lowercase()
        if (low.startsWith("baseless_")) return true
        if (EXCLUDED_FILE_PATTERNS.any { low.contains(it) }) return true
        if (low.endsWith(".psf")) return true
        return false
    }

    fun parseUrlExpiryEpoch(url: String): Long {
        val match = Regex("""[?&]P1=(\d{9,12})""", RegexOption.IGNORE_CASE).find(url)
        return match?.groupValues?.getOrNull(1)?.toLongOrNull() ?: 0L
    }

    private fun utcIso(epochSec: Long): String {
        val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        fmt.timeZone = TimeZone.getTimeZone("UTC")
        return fmt.format(Date(epochSec * 1000L))
    }

    private fun hexToBytes(hex: String): ByteArray {
        val len = hex.length
        val out = ByteArray(len / 2)
        var i = 0
        while (i < len) {
            out[i / 2] = ((Character.digit(hex[i], 16) shl 4) + Character.digit(hex[i + 1], 16)).toByte()
            i += 2
        }
        return out
    }

    private fun bytesToHex(bytes: ByteArray): String {
        val chars = "0123456789abcdef"
        val out = CharArray(bytes.size * 2)
        for (i in bytes.indices) {
            val v = bytes[i].toInt() and 0xFF
            out[i * 2] = chars[v ushr 4]
            out[i * 2 + 1] = chars[v and 0x0F]
        }
        return String(out)
    }

    private fun base64DigestToHex(b64: String): String = runCatching {
        bytesToHex(Base64.decode(b64.trim(), Base64.DEFAULT))
    }.getOrDefault("")

    private fun htmlDecode(raw: String): String = raw
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&apos;", "'")
        .replace("&amp;", "&")

    private fun xmlEscapeAttr(raw: String): String = raw
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")

    /** Exact port of UUPdump auths.php uupDevice() — generates anonymous MSA device ticket. */
    fun generateDeviceToken(): String {
        val header = "13003002c377040014d5bcac7a66de0d50beddf9bba16c87edb9e019898000"
        val randBytes = ByteArray(527) // 527 bytes = 1054 hex chars
        random.nextBytes(randBytes)
        val fullHex = header + bytesToHex(randBytes) + "b401"
        val tVal = Base64.encodeToString(hexToBytes(fullHex), Base64.NO_WRAP)
        val payload = "t=$tVal&p="
        val utf16 = ByteArray(payload.length * 2)
        for (i in payload.indices) {
            utf16[i * 2] = payload[i].code.toByte()
            utf16[i * 2 + 1] = 0
        }
        return Base64.encodeToString(utf16, Base64.NO_WRAP)
    }

    /** Exact port of requests.php branchFromBuild(). */
    fun branchFromBuild(build: String): String {
        val parts = build.split('.')
        val major = (if (parts.size >= 3) parts[2] else parts.firstOrNull().orEmpty()).toIntOrNull() ?: 0
        return when (major) {
            15063 -> "rs2_release"
            16299 -> "rs3_release"
            17134 -> "rs4_release"
            17763 -> "rs5_release"
            17784 -> "rs5_release_svc_hci"
            18362, 18363 -> "19h1_release"
            19041, 19042, 19043, 19044, 19045 -> "vb_release"
            20279 -> "fe_release_10x"
            20348, 20349 -> "fe_release"
            22000 -> "co_release"
            22621, 22631 -> "ni_release"
            25398 -> "zn_release"
            26100, 26200, 26300 -> "ge_release"
            28000, 28100 -> "br_release"
            else -> "rs_prerelease"
        }
    }

    private fun isServerSku(sku: Int): Boolean = sku in setOf(7, 8, 12, 13, 79, 80, 145, 146, 406, 407)

    /** Exact port of requests.php composeDeviceAttributes() (with Win 11 26H2/25H2 & Win 10 ESU). */
    fun composeDeviceAttributes(
        ringIn: String,
        build: String,
        arch: String,
        sku: Int = 48,
        type: String = "Production",
        branchIn: String = "auto",
        targetRelease: Int = -1
    ): String {
        val branch = if (branchIn == "auto" || branchIn.isBlank()) branchFromBuild(build) else branchIn
        var blockUpgrades = if (sku == 125 || sku == 126) 1 else 0
        var flightEnabled = 1
        var isRetail = 0

        var dvcFamily = "Windows.Desktop"
        var insType = "Client"
        var prodType = "WinNT"
        if (sku == 119) dvcFamily = "Windows.Team"
        if (isServerSku(sku)) {
            dvcFamily = "Windows.Server"
            insType = "Server"
            prodType = "ServerNT"
            blockUpgrades = 1
        }

        var ring = ringIn.uppercase()
        var fltBranch = ""
        var fltRing = "External"
        when (ring) {
            "RETAIL" -> { fltRing = "Retail"; flightEnabled = 0; isRetail = 1 }
            "WIF", "DEV" -> { fltBranch = "Dev"; ring = "WIF" }
            "WIS", "BETA" -> { fltBranch = "Beta"; ring = "WIS" }
            "RP", "RELEASEPREVIEW" -> { fltBranch = "ReleasePreview"; ring = "RP" }
            "CANARY" -> { fltBranch = "CanaryChannel"; ring = "WIF" }
        }

        val parts = build.split('.')
        val bldNum = (if (parts.size >= 3) parts[2] else parts.firstOrNull().orEmpty()).toIntOrNull() ?: 19041
        if (bldNum < 17763) {
            fltBranch = "external"
            fltRing = ring
        }

        val now = System.currentTimeMillis() / 1000L
        val exp = now + 82800L
        val past = now - 3600L

        val attrs = mutableListOf(
            "App=WU_OS",
            "AppVer=$build",
            "AttrDataVer=370",
            "AllowInPlaceUpgrade=1",
            "AllowOptionalContent=1",
            "AllowUpgradesWithUnsupportedTPMOrCPU=1",
            "AllowWindowsServerFeatureUpdate=1",
            "BlockFeatureUpdates=$blockUpgrades",
            "BranchReadinessLevel=CB",
            "CIOptin=1",
            "CurrentBranch=$branch",
            "DataExpDateEpoch_GE26H2=$exp",
            "DataExpDateEpoch_GE25H2=$exp",
            "DataExpDateEpoch_GE24H2=$exp",
            "DataExpDateEpoch_GE24H2Setup=$exp",
            "DataExpDateEpoch_NI23H2=$exp",
            "DataExpDateEpoch_NI22H2=$exp",
            "DataExpDateEpoch_NI22H2Setup=$exp",
            "DataExpDateEpoch_CO21H2=$exp",
            "DataExpDateEpoch_CO21H2Setup=$exp",
            "DataExpDateEpoch_22H2=$exp",
            "DataExpDateEpoch_21H2=$exp",
            "DataExpDateEpoch_21H1=$exp",
            "DataExpDateEpoch_20H1=$exp",
            "DataExpDateEpoch_20H1Setup=$exp",
            "DataExpDateEpoch_19H1=$exp",
            "DataExpDateEpoch_19H1Setup=$exp",
            "DataVer_RS5=2000000000",
            "DefaultUserRegion=191",
            "DeviceFamily=$dvcFamily",
            "DeviceInfoGatherSuccessful=1",
            "EKB19H2InstallCount=1",
            "EKB19H2InstallTimeEpoch=1255000000",
            "FlightingBranchName=$fltBranch",
            "FlightRing=$fltRing",
            "Free=gt64",
            "GStatus_GE26H2=2",
            "GStatus_GE25H2=2",
            "GStatus_GE24H2=2",
            "GStatus_GE24H2Setup=2",
            "GStatus_NI23H2=2",
            "GStatus_NI22H2=2",
            "GStatus_NI22H2Setup=2",
            "GStatus_CO21H2=2",
            "GStatus_CO21H2Setup=2",
            "GStatus_22H2=2",
            "GStatus_21H2=2",
            "GStatus_21H1=2",
            "GStatus_20H1=2",
            "GStatus_20H1Setup=2",
            "GStatus_19H1=2",
            "GStatus_19H1Setup=2",
            "GStatus_RS5=2",
            "GenTelRunTimestamp_19H1=$past",
            "InstallDate=1438196400",
            "InstallLanguage=en-US",
            "InstallationType=$insType",
            "IsDeviceRetailDemo=0",
            "IsFlightingEnabled=$flightEnabled",
            "IsRetailOS=$isRetail",
            "LaunchUserOOBE=1",
            "LCUVer=0.0.0.0",
            "MediaBranch=",
            "MediaVersion=$build",
            "CloudPBR=1",
            "DUScan=1",
            "OEMModel=21F6CTO1WW",
            "OEMModelBaseBoard=21F6CTO1WW",
            "OEMName_Uncleaned=LENOVO",
            "OSArchitecture=$arch",
            "OSSkuId=$sku",
            "OSUILocale=en-US",
            "OSVersion=$build",
            "ProcessorIdentifier=Intel64 Family 6 Model 186 Stepping 3",
            "ProcessorManufacturer=GenuineIntel",
            "ProcessorModel=13th Gen Intel(R) Core(TM) i7-1355U",
            "ProductType=$prodType",
            "ReleaseType=$type",
            "SdbVer_GE24H2=2000000000",
            "SdbVer_NI22H2=2000000000",
            "SdbVer_CO21H2=2000000000",
            "SdbVer_20H1=2000000000",
            "SdbVer_19H1=2000000000",
            "SecureBootCapable=1",
            "TelemetryLevel=3",
            "TimestampEpochString_GE26H2=$past",
            "TimestampEpochString_GE25H2=$past",
            "TimestampEpochString_GE24H2=$past",
            "TimestampEpochString_GE24H2Setup=$past",
            "TimestampEpochString_NI23H2=$past",
            "TimestampEpochString_NI22H2=$past",
            "TimestampEpochString_NI22H2Setup=$past",
            "TimestampEpochString_CO21H2=$past",
            "TimestampEpochString_CO21H2Setup=$past",
            "TimestampEpochString_22H2=$past",
            "TimestampEpochString_21H2=$past",
            "TimestampEpochString_21H1=$past",
            "TimestampEpochString_20H1=$past",
            "TimestampEpochString_20H1Setup=$past",
            "TimestampEpochString_19H1=$past",
            "TimestampEpochString_19H1Setup=$past",
            "TPMVersion=2",
            "UpdateManagementGroup=2",
            "UpdateOfferedDays=0",
            "UpgEx_GE26H2=Green",
            "UpgEx_GE25H2=Green",
            "UpgEx_GE24H2Setup=Green",
            "UpgEx_GE24H2=Green",
            "UpgEx_NI23H2=Green",
            "UpgEx_NI22H2=Green",
            "UpgEx_NI22H2Setup=Green",
            "UpgEx_CO21H2=Green",
            "UpgEx_CO21H2Setup=Green",
            "UpgEx_23H2=Green",
            "UpgEx_22H2=Green",
            "UpgEx_21H2=Green",
            "UpgEx_21H1=Green",
            "UpgEx_20H1=Green",
            "UpgEx_19H1=Green",
            "UpgEx_RS5=Green",
            "UpgradeAccepted=1",
            "UpgradeEligible=1",
            "UserInPlaceUpgrade=1",
            "VBSState=2",
            "Version_RS5=2000000000",
            "Win10CommercialAzureESUEligible=1",
            "Win10CommercialKeybasedESUEligible=3",
            "Win10CommercialW365ESUEligible=1",
            "Win10ConsumerESUStatus=3",
            "Win10ConsumerESUAY=10",
            "WuClientVer=$build"
        )
        if (bldNum >= 26100) {
            attrs.add("FlightUpgradeTarget=$targetRelease")
            attrs.add("IsFutureKeyEnabled=1")
        }
        return xmlEscapeAttr("E:" + attrs.joinToString("&"))
    }

    private fun postSoap(urlStr: String, body: String): String? {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 15_000
            readTimeout = 30_000
            setRequestProperty("Content-Type", SOAP_CONTENT_TYPE)
            setRequestProperty("User-Agent", WU_UA)
            setRequestProperty("Accept", "*/*")
        }
        return try {
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (code in 200..299) text else null
        } catch (_: Exception) {
            null
        } finally {
            conn.disconnect()
        }
    }

    private fun getCookieEncryptedData(forceRefresh: Boolean = false): String? {
        val nowMs = System.currentTimeMillis()
        if (!forceRefresh && !cachedCookieData.isNullOrBlank() && nowMs < cachedCookieExpiryMs) {
            return cachedCookieData
        }
        val nowSec = nowMs / 1000L
        val created = utcIso(nowSec)
        val expires = utcIso(nowSec + 120L)
        val uuid = UUID.randomUUID().toString()
        val device = generateDeviceToken()
        val xml = """
<s:Envelope xmlns:a="http://www.w3.org/2005/08/addressing" xmlns:s="http://www.w3.org/2003/05/soap-envelope">
    <s:Header>
        <a:Action s:mustUnderstand="1">http://www.microsoft.com/SoftwareDistribution/Server/ClientWebService/GetCookie</a:Action>
        <a:MessageID>urn:uuid:$uuid</a:MessageID>
        <a:To s:mustUnderstand="1">$ENDPOINT_CLIENT</a:To>
        <o:Security s:mustUnderstand="1" xmlns:o="http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-secext-1.0.xsd">
            <Timestamp xmlns="http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-utility-1.0.xsd">
                <Created>$created</Created>
                <Expires>$expires</Expires>
            </Timestamp>
            <wuws:WindowsUpdateTicketsToken wsu:id="ClientMSA" xmlns:wsu="http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-utility-1.0.xsd" xmlns:wuws="http://schemas.microsoft.com/msus/2014/10/WindowsUpdateAuthorization">
                <TicketType Name="MSA" Version="1.0" Policy="MBI_SSL">
                    <Device>$device</Device>
                </TicketType>
            </wuws:WindowsUpdateTicketsToken>
        </o:Security>
    </s:Header>
    <s:Body>
        <GetCookie xmlns="http://www.microsoft.com/SoftwareDistribution/Server/ClientWebService">
            <oldCookie><Expiration>$created</Expiration></oldCookie>
            <lastChange>$created</lastChange>
            <currentTime>$created</currentTime>
            <protocolVersion>2.0</protocolVersion>
        </GetCookie>
    </s:Body>
</s:Envelope>
        """.trimIndent()

        val resp = postSoap(ENDPOINT_CLIENT, xml) ?: return null
        val enc = Regex("""<EncryptedData>([^<]+)</EncryptedData>""").find(resp)?.groupValues?.getOrNull(1)?.trim()
        if (enc.isNullOrBlank()) return null
        cachedCookieData = enc
        cachedCookieExpiryMs = nowMs + 8L * 60L * 1000L // valid ~10m, refresh after 8m
        return enc
    }

    /** Calls Microsoft's GetExtendedUpdateInfo2 directly to obtain fresh signed CDN URLs for any UpdateID. */
    suspend fun fetchFileUrlsForUpdate(
        updateId: String,
        revision: Int = 1,
        arch: String = "amd64",
        ring: String = "RETAIL",
        checkBuild: String = "10.0.19041.1",
        sku: Int = 48
    ): Map<String, DirectFileUrl> = withContext(Dispatchers.IO) {
        val nowSec = System.currentTimeMillis() / 1000L
        val created = utcIso(nowSec)
        val expires = utcIso(nowSec + 120L)
        val uuid = UUID.randomUUID().toString()
        val device = generateDeviceToken()
        val devAttrs = composeDeviceAttributes(ring, checkBuild, arch, sku)

        val cleanId = updateId.substringBefore("_rev.")
        val effectiveRev = if (updateId.contains("_rev.")) {
            updateId.substringAfter("_rev.").toIntOrNull() ?: revision
        } else revision

        val xml = """
<s:Envelope xmlns:a="http://www.w3.org/2005/08/addressing" xmlns:s="http://www.w3.org/2003/05/soap-envelope">
    <s:Header>
        <a:Action s:mustUnderstand="1">http://www.microsoft.com/SoftwareDistribution/Server/ClientWebService/GetExtendedUpdateInfo2</a:Action>
        <a:MessageID>urn:uuid:$uuid</a:MessageID>
        <a:To s:mustUnderstand="1">$ENDPOINT_SECURED</a:To>
        <o:Security s:mustUnderstand="1" xmlns:o="http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-secext-1.0.xsd">
            <Timestamp xmlns="http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-utility-1.0.xsd">
                <Created>$created</Created>
                <Expires>$expires</Expires>
            </Timestamp>
            <wuws:WindowsUpdateTicketsToken wsu:id="ClientMSA" xmlns:wsu="http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-utility-1.0.xsd" xmlns:wuws="http://schemas.microsoft.com/msus/2014/10/WindowsUpdateAuthorization">
                <TicketType Name="MSA" Version="1.0" Policy="MBI_SSL">
                    <Device>$device</Device>
                </TicketType>
            </wuws:WindowsUpdateTicketsToken>
        </o:Security>
    </s:Header>
    <s:Body>
        <GetExtendedUpdateInfo2 xmlns="http://www.microsoft.com/SoftwareDistribution/Server/ClientWebService">
            <updateIDs>
                <UpdateIdentity>
                    <UpdateID>$cleanId</UpdateID>
                    <RevisionNumber>$effectiveRev</RevisionNumber>
                </UpdateIdentity>
            </updateIDs>
            <infoTypes>
                <XmlUpdateFragmentType>Extended</XmlUpdateFragmentType>
                <XmlUpdateFragmentType>FileUrl</XmlUpdateFragmentType>
                <XmlUpdateFragmentType>FileDecryption</XmlUpdateFragmentType>
                <XmlUpdateFragmentType>EsrpDecryptionInformation</XmlUpdateFragmentType>
                <XmlUpdateFragmentType>PiecesHashUrl</XmlUpdateFragmentType>
                <XmlUpdateFragmentType>BlockMapUrl</XmlUpdateFragmentType>
            </infoTypes>
            <deviceAttributes>$devAttrs</deviceAttributes>
        </GetExtendedUpdateInfo2>
    </s:Body>
</s:Envelope>
        """.trimIndent()

        val resp = postSoap(ENDPOINT_SECURED, xml) ?: return@withContext emptyMap()
        val out = LinkedHashMap<String, DirectFileUrl>()
        val locRegex = Regex("""<FileLocation>(.*?)</FileLocation>""", RegexOption.DOT_MATCHES_ALL)
        val digestRegex = Regex("""<FileDigest>([^<]+)</FileDigest>""")
        val urlRegex = Regex("""<Url>([^<]+)</Url>""")

        locRegex.findAll(resp).forEach { m ->
            val block = m.groupValues[1]
            val digB64 = digestRegex.find(block)?.groupValues?.getOrNull(1)?.trim() ?: return@forEach
            val rawUrl = urlRegex.find(block)?.groupValues?.getOrNull(1)?.trim() ?: return@forEach
            val cleanUrl = htmlDecode(rawUrl)
            val sha1Hex = base64DigestToHex(digB64)
            if (sha1Hex.length == 40 && cleanUrl.startsWith("http")) {
                out[sha1Hex] = DirectFileUrl(sha1Hex, cleanUrl, parseUrlExpiryEpoch(cleanUrl))
            }
        }

        // Also extract embedded <Files> metadata if returned in Extended fragment
        if (syncFilesCache[cleanId].isNullOrEmpty() && resp.contains("FileName=")) {
            val dec = htmlDecode(resp)
            val fRegex = Regex("""<File\s+([^>]+?)(?:/>|>(.*?)</File>)""", RegexOption.DOT_MATCHES_ALL)
            val aName = Regex("""FileName="([^"]+)"""")
            val aSize = Regex("""Size="(\d+)"""")
            val aDig = Regex("""Digest="([^"]+)"""")
            val aSha1 = Regex("""<AdditionalDigest\s+Algorithm="SHA1">([^<]+)</AdditionalDigest>""")
            val extracted = mutableListOf<DirectSyncFileMeta>()
            fRegex.findAll(dec).forEach { fm ->
                val attrs = fm.groupValues[1]
                val inner = fm.groupValues.getOrNull(2).orEmpty()
                val fn = aName.find(attrs)?.groupValues?.getOrNull(1)?.trim() ?: return@forEach
                val sz = aSize.find(attrs)?.groupValues?.getOrNull(1)?.toLongOrNull() ?: 0L
                val dig = aSha1.find(inner)?.groupValues?.getOrNull(1) ?: aDig.find(attrs)?.groupValues?.getOrNull(1) ?: return@forEach
                val hex = base64DigestToHex(dig)
                if (hex.length == 40 && !isUnusablePackageName(fn)) extracted.add(DirectSyncFileMeta(fn, sz, hex))
            }
            if (extracted.isNotEmpty()) syncFilesCache[cleanId] = extracted.distinctBy { it.name.lowercase() }
        }
        out
    }

    /** Calls Microsoft SyncUpdates directly to discover the latest build & its full file metadata. */
    suspend fun syncLatestBuildsDirect(
        arch: String = "amd64",
        ring: String = "RETAIL",
        baselineBuild: String = "10.0.26100.1",
        sku: Int = 48,
        targetRelease: Int = -1
    ): List<DirectSyncBuildResult> = withContext(Dispatchers.IO) {
        val encData = getCookieEncryptedData() ?: return@withContext emptyList()
        val nowSec = System.currentTimeMillis() / 1000L
        val created = utcIso(nowSec)
        val expires = utcIso(nowSec + 120L)
        val cookieExp = utcIso(nowSec + 604800L)
        val uuid = UUID.randomUUID().toString()
        val device = generateDeviceToken()
        val branch = branchFromBuild(baselineBuild)
        val mainProduct = if (isServerSku(sku)) "Server.OS" else "Client.OS.rs2"

        val productsRaw = listOf(
            "PN=$mainProduct.$arch&Branch=$branch&PrimaryOSProduct=1&Repairable=1&V=$baselineBuild&ReofferUpdate=1",
            "PN=Microsoft.NETFX.$arch&V=0.0.0.0",
            "PN=Windows.FeatureExperiencePack.$arch&Repairable=1&V=0.0.0.0",
            "PN=Windows.UpdateStackPackage.$arch&Name=Update Stack Package&Repairable=1&V=$baselineBuild"
        ).joinToString(";")
        val products = xmlEscapeAttr(productsRaw)
        val callerAttrs = xmlEscapeAttr("E:Profile=AUv2&Acquisition=1&Interactive=1&IsSeeker=1&SheddingAware=1&Id=MoUpdateOrchestrator")
        val devAttrs = composeDeviceAttributes(ring, baselineBuild, arch, sku, branchIn = branch, targetRelease = targetRelease)

        val installedIds = listOf(
            1, 10, 105939029, 105995585, 106017178, 107825194, 10809856, 11, 117765322,
            129905029, 130040030, 130040031, 130040032, 130040033, 133399034, 138372035,
            138372036, 139536037, 139536038, 139536039, 139536040, 142045136, 158941041,
            158941042, 158941043, 158941044, 159776047, 160733048, 160733049, 160733050,
            160733051, 160733055, 160733056, 161870057, 161870058, 161870059, 17, 19, 2,
            23110993, 23110994, 23110995, 23110996, 23110999, 23111000, 23111001, 23111002,
            23111003, 23111004, 2359974, 2359977, 24513870, 28880263, 296374060, 3,
            30077688, 30486944, 316003061, 326686062, 326686063, 327065581, 327072300,
            327072305, 333703234, 5143990, 5169043, 5169044, 5169047, 59830006, 59830007,
            59830008, 60484010, 62450018, 62450019, 62450020, 69801474, 8788830, 8806526,
            9125350, 9154769, 98959022, 98959023, 98959024, 98959025, 98959026
        ).joinToString("") { "<int>$it</int>" }

        val xml = """
<s:Envelope xmlns:a="http://www.w3.org/2005/08/addressing" xmlns:s="http://www.w3.org/2003/05/soap-envelope">
    <s:Header>
        <a:Action s:mustUnderstand="1">http://www.microsoft.com/SoftwareDistribution/Server/ClientWebService/SyncUpdates</a:Action>
        <a:MessageID>urn:uuid:$uuid</a:MessageID>
        <a:To s:mustUnderstand="1">$ENDPOINT_CLIENT</a:To>
        <o:Security s:mustUnderstand="1" xmlns:o="http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-secext-1.0.xsd">
            <Timestamp xmlns="http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-utility-1.0.xsd">
                <Created>$created</Created>
                <Expires>$expires</Expires>
            </Timestamp>
            <wuws:WindowsUpdateTicketsToken wsu:id="ClientMSA" xmlns:wsu="http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-utility-1.0.xsd" xmlns:wuws="http://schemas.microsoft.com/msus/2014/10/WindowsUpdateAuthorization">
                <TicketType Name="MSA" Version="1.0" Policy="MBI_SSL">
                    <Device>$device</Device>
                </TicketType>
            </wuws:WindowsUpdateTicketsToken>
        </o:Security>
    </s:Header>
    <s:Body>
        <SyncUpdates xmlns="http://www.microsoft.com/SoftwareDistribution/Server/ClientWebService">
            <cookie>
                <Expiration>$cookieExp</Expiration>
                <EncryptedData>$encData</EncryptedData>
            </cookie>
            <parameters>
                <ExpressQuery>false</ExpressQuery>
                <InstalledNonLeafUpdateIDs>$installedIds</InstalledNonLeafUpdateIDs>
                <OtherCachedUpdateIDs/>
                <SkipSoftwareSync>false</SkipSoftwareSync>
                <NeedTwoGroupOutOfScopeUpdates>true</NeedTwoGroupOutOfScopeUpdates>
                <AlsoPerformRegularSync>true</AlsoPerformRegularSync>
                <ComputerSpec/>
                <ExtendedUpdateInfoParameters>
                    <XmlUpdateFragmentTypes>
                        <XmlUpdateFragmentType>Extended</XmlUpdateFragmentType>
                        <XmlUpdateFragmentType>LocalizedProperties</XmlUpdateFragmentType>
                    </XmlUpdateFragmentTypes>
                    <Locales><string>en-US</string></Locales>
                </ExtendedUpdateInfoParameters>
                <ClientPreferredLanguages/>
                <ProductsParameters>
                    <SyncCurrentVersionOnly>false</SyncCurrentVersionOnly>
                    <DeviceAttributes>$devAttrs</DeviceAttributes>
                    <CallerAttributes>$callerAttrs</CallerAttributes>
                    <Products>$products</Products>
                </ProductsParameters>
            </parameters>
        </SyncUpdates>
    </s:Body>
</s:Envelope>
        """.trimIndent()

        val resp = postSoap(ENDPOINT_CLIENT, xml) ?: return@withContext emptyList()
        parseSyncUpdatesResponse(resp, arch, ring, sku, baselineBuild)
    }

    private fun parseSyncUpdatesResponse(
        rawResp: String,
        arch: String,
        ring: String,
        sku: Int,
        baselineBuild: String
    ): List<DirectSyncBuildResult> {
        val decoded = htmlDecode(rawResp)
        // Map numeric Update ID -> UpdateIdentity (UpdateID UUID, RevisionNumber) & Title & Files
        val idToUuid = HashMap<String, Pair<String, Int>>()
        val idToTitle = HashMap<String, String>()
        val idToFiles = HashMap<String, List<DirectSyncFileMeta>>()

        val updateInfoRegex = Regex("""<UpdateInfo>(.*?)</UpdateInfo>""", RegexOption.DOT_MATCHES_ALL)
        val numIdRegex = Regex("""<ID>(\d+)</ID>""")
        val uuidRegex = Regex("""UpdateID="([0-9a-fA-F-]{36})"[^>]*(?:RevisionNumber="(\d+)")?""")
        val titleRegex = Regex("""<Title>([^<]+)</Title>""")
        val attrFileName = Regex("""FileName="([^"]+)"""")
        val attrSize = Regex("""Size="(\d+)"""")
        val attrDigest = Regex("""Digest="([^"]+)"""")
        val attrAdditionalSha1 = Regex("""<AdditionalDigest\s+Algorithm="SHA1">([^<]+)</AdditionalDigest>""")

        // Parse NewUpdates section (contains UpdateInfo with Deployment + Xml containing UpdateIdentity)
        updateInfoRegex.findAll(decoded).forEach { m ->
            val blk = m.groupValues[1]
            val numId = numIdRegex.find(blk)?.groupValues?.getOrNull(1) ?: return@forEach
            uuidRegex.find(blk)?.let { um ->
                val uid = um.groupValues[1].lowercase()
                val rev = um.groupValues.getOrNull(2)?.toIntOrNull() ?: 1
                idToUuid[numId] = uid to rev
            }
        }

        // Parse ExtendedUpdateInfo -> Updates -> Update (contains LocalizedProperties + Files)
        val extUpdateRegex = Regex("""<Update>(.*?)</Update>""", RegexOption.DOT_MATCHES_ALL)
        extUpdateRegex.findAll(decoded).forEach { m ->
            val blk = m.groupValues[1]
            val numId = numIdRegex.find(blk)?.groupValues?.getOrNull(1) ?: return@forEach
            titleRegex.find(blk)?.groupValues?.getOrNull(1)?.trim()?.let { idToTitle[numId] = it }
            if (blk.contains("<Files>")) {
                val filesList = mutableListOf<DirectSyncFileMeta>()
                val filesBlock = blk.substringAfter("<Files>").substringBefore("</Files>")
                // Handle both self-closing <File .../> and <File ...>...</File>
                val fullFileRegex = Regex("""<File\s+([^>]+?)(?:/>|>(.*?)</File>)""", RegexOption.DOT_MATCHES_ALL)
                fullFileRegex.findAll(filesBlock).forEach { fm ->
                    val attrs = fm.groupValues[1]
                    val inner = fm.groupValues.getOrNull(2).orEmpty()
                    val fname = attrFileName.find(attrs)?.groupValues?.getOrNull(1)?.trim() ?: return@forEach
                    val sz = attrSize.find(attrs)?.groupValues?.getOrNull(1)?.toLongOrNull() ?: 0L
                    val digB64 = attrAdditionalSha1.find(inner)?.groupValues?.getOrNull(1)
                        ?: attrDigest.find(attrs)?.groupValues?.getOrNull(1)
                        ?: return@forEach
                    val sha1 = base64DigestToHex(digB64)
                    if (sha1.length == 40 && !isUnusablePackageName(fname)) {
                        filesList.add(DirectSyncFileMeta(fname, sz, sha1))
                    }
                }
                if (filesList.isNotEmpty()) idToFiles[numId] = filesList
            }
        }

        val results = mutableListOf<DirectSyncBuildResult>()
        val nowSec = System.currentTimeMillis() / 1000L
        val buildNumRegex = Regex("""(\d{5}\.\d+)""")
        val seenUuids = HashSet<String>()

        idToFiles.forEach { (numId, files) ->
            val (uid, rev) = idToUuid[numId] ?: return@forEach
            if (!seenUuids.add(uid)) return@forEach
            val hasEditionEsd = files.any { f ->
                val low = f.name.lowercase()
                low.endsWith(".esd") && (low.contains("core_") || low.contains("professional_") || low.contains("serverstandard") || low.contains("serverdatacenter"))
            }
            if (!hasEditionEsd) return@forEach

            val title = idToTitle[numId] ?: "Windows Update ($arch)"
            val detectedBuild = buildNumRegex.find(title)?.groupValues?.getOrNull(1)
                ?: files.firstNotNullOfOrNull { buildNumRegex.find(it.name)?.groupValues?.getOrNull(1) }
                ?: baselineBuild.removePrefix("10.0.")

            val (tier, badge) = when (ring.uppercase()) {
                "CANARY" -> WinBuildTier.INSIDER to "Canary"
                "DEV", "WIF" -> WinBuildTier.INSIDER to "Dev"
                "BETA", "WIS" -> WinBuildTier.INSIDER to "Beta"
                "RP", "RELEASEPREVIEW" -> WinBuildTier.PREVIEW to "Preview"
                else -> WinBuildTier.STABLE to "Stable"
            }
            val chBuild = WindowsChannelBuild(
                uuid = uid,
                title = title,
                build = detectedBuild,
                arch = arch,
                createdEpoch = nowSec,
                tier = tier,
                channelBadge = badge
            )
            val entry = DirectSyncBuildResult(chBuild, rev, ring, sku, baselineBuild, files)
            syncFilesCache[uid] = files
            syncMetaCache[uid] = entry
            results.add(entry)
        }
        return results
    }

    /** Derives available language list directly from cached fileinfo names when UUPdump API is offline. */
    fun deriveLangsFromFileNames(fileNames: Collection<String>): List<WinBuildLangOption> {
        val langRegex = Regex("""^(?:core|coren|professional|professionaln|serverstandard|serverdatacenter)_([a-z]{2}-[a-z]{2,4}(?:-[a-z]{2})?)\.esd$""", RegexOption.IGNORE_CASE)
        val found = LinkedHashSet<String>()
        fileNames.forEach { name ->
            langRegex.find(name)?.groupValues?.getOrNull(1)?.lowercase()?.let { found.add(it) }
        }
        if (found.isEmpty()) found.add("en-us")
        return found.sorted().map { code ->
            WinBuildLangOption(code, LANG_DISPLAY_NAMES[code] ?: code.uppercase())
        }
    }

    /** Derives available base editions for a given language directly from cached fileinfo names. */
    fun deriveEditionsFromFileNames(fileNames: Collection<String>, langCode: String): List<WinBuildEditionOption> {
        val suffix = "_${langCode.lowercase()}.esd"
        val found = LinkedHashSet<String>()
        fileNames.forEach { raw ->
            val low = raw.lowercase()
            if (low.endsWith(suffix) && !low.contains("-")) {
                val ed = low.removeSuffix(suffix)
                if (ed in EDITION_DISPLAY_NAMES) found.add(ed)
            }
        }
        if (found.isEmpty()) {
            found.add("core")
            found.add("professional")
        }
        return found.map { code ->
            WinBuildEditionOption(code.uppercase(), EDITION_DISPLAY_NAMES[code] ?: code.replaceFirstChar { it.uppercase() })
        }
    }

    /** Filters a raw full-build file list down to neutral core + chosen language + chosen editions (excluding unusable files). */
    fun filterFilesForSelection(
        allFiles: List<DirectSyncFileMeta>,
        langCode: String,
        editionCodes: List<String>
    ): List<DirectSyncFileMeta> {
        val targetLang = langCode.lowercase()
        val chosenEds = editionCodes.map { it.lowercase() }.toSet()
        val anyLangTagRegex = Regex("""[._-]([a-z]{2}-[a-z]{2,4}(?:-[a-z]{2})?)(?=[._-]|$)""", RegexOption.IGNORE_CASE)
        val editionEsdRegex = Regex("""^([a-z0-9]+)_([a-z]{2}-[a-z]{2,4}(?:-[a-z]{2})?)\.esd$""", RegexOption.IGNORE_CASE)

        return allFiles.filter { f ->
            val low = f.name.lowercase()
            if (isUnusablePackageName(low)) return@filter false

            // Check if it's a root edition metadata ESD (e.g. professional_en-us.esd)
            val edMatch = editionEsdRegex.find(low)
            if (edMatch != null) {
                val ed = edMatch.groupValues[1]
                val lg = edMatch.groupValues[2]
                return@filter ed in chosenEds && lg == targetLang
            }

            // Skip unrelated language-tagged files
            val langMatches = anyLangTagRegex.findAll(low).map { it.groupValues[1] }.filter { it in LANG_DISPLAY_NAMES }.toList()
            if (langMatches.isNotEmpty() && targetLang !in langMatches) return@filter false

            // Keep neutral edition-specific packages only for chosen editions
            val isProOnly = low.contains("editionpack-professional-") || low.contains("editionpack-professional_")
            val isCoreOnly = low.contains("editionpack-core-") || low.contains("editionpack-core_")
            if (isProOnly && "professional" !in chosenEds && "professionaln" !in chosenEds) return@filter false
            if (isCoreOnly && "core" !in chosenEds && "coren" !in chosenEds) return@filter false

            true
        }
    }
}
