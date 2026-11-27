package com.pocketboot.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Wave6NtriverTest {

    @Test
    fun parseIndexPageLinks_discoversAllWindowsAndServerRoutes() {
        val sampleIndex = """
            <article>
              <a href="/download-windows-11">Windows 11</a>
              <a href="/download-windows-10">Windows 10</a>
              <a href="/download-windows-ltsc">Windows 10 / 11 LTSC</a>
              <a href="/download-windows-8.1">Windows 8.1</a>
              <a href="/download-windows-7">Windows 7</a>
              <a href="https://ntriver.org/download-windows-server-2025">Windows Server 2025</a>
              <a href="/download-windows-server-2022">Windows Server 2022</a>
              <a href="/download-windows-server-2019">Windows Server 2019</a>
            </article>
        """.trimIndent()

        val links = NtriverCatalog.parseIndexPageLinks(sampleIndex)
        assertEquals("/download-windows-11", links["win11"])
        assertEquals("/download-windows-10", links["win10"])
        assertEquals("/download-windows-ltsc", links["ltsc"])
        assertEquals("/download-windows-8.1", links["win81"])
        assertEquals("/download-windows-7", links["win7"])
        assertEquals("/download-windows-server-2025", links["server2025"])
        assertEquals("/download-windows-server-2022", links["server2022"])
        assertEquals("/download-windows-server-2019", links["server2019"])
    }

    @Test
    fun parseHtmlCatalogPage_extractsValidIsosSkipsIndexClutterAndEsds() {
        val sampleHtml = """
            <article>
              <h2>Index</h2>
              <table>
                <tr><th>Windows Version</th><th>ISO Links</th></tr>
                <tr><td>Windows Server 2022</td><td>Server 2022 (November 2026)</td></tr>
              </table>

              <h2>Windows Server 2022 (November 2026) ❤️</h2>
              <ul>
                <li>Build: [20348.5873] (Updated November 2026)</li>
                <li>Note: Only EN and RU language files have been added so far; the remaining languages will be added later.</li>
              </ul>
              <table>
                <tr><th>Language</th><th>Arch</th><th>File Details</th></tr>
                <tr>
                  <td>English</td>
                  <td>x64</td>
                  <td>en-us_windows_server_2022_updated_nov_2026_x64_dvd_2fd3d7d1.iso<br/>
                      SHA-256: 3c667fa417f5559f65df66731e91fe1a982410e8877a6655797bb4a7ed061d6e<br/>
                      Download: <a href="https://ntriver.org/drive?en-us_windows_server_2022_updated_nov_2026_x64_dvd_2fd3d7d1.iso">NTriver</a> •
                      <a href="https://files.rg-adguard.net/file/abc">rg-adguard</a></td>
                </tr>
                <tr>
                  <td>English</td>
                  <td>x32</td>
                  <td>26100.1742.240906-0331.ge_release_svc_refresh_CLIENTCONSUMER_RET_x86FRE_en-us.esd<br/>
                      SHA-256: 1111111111111111111111111111111111111111111111111111111111111111<br/>
                      Download: <a href="https://ntriver.org/drive?file.esd">NTriver</a></td>
                </tr>
              </table>

              <h2>Windows Server 2022 - Datacenter Azure Edition</h2>
              <table>
                <tr><th>Language</th><th>Arch</th><th>File Details</th></tr>
                <tr>
                  <td>English</td>
                  <td>x64</td>
                  <td>20348.1006.220908-1954.fe_release_svc_refresh_SERVERDCAZURE_VOL_x64FRE_en-us.iso<br/>
                      SHA-256: f97d1ea3d5817cc31d74766ef30ee8d17f1fcdbcde691d169f837ed29f101f88<br/>
                      Download: <a href="https://software-static.download.prss.microsoft.com/sg/download/20348.1006.iso">Microsoft</a> •
                      <a href="https://files.rg-adguard.net/file/xyz">rg-adguard</a></td>
                </tr>
              </table>

              <h2>Windows Driver Kit (WDK) 7.1.0</h2>
              <table>
                <tr><th>Language</th><th>Arch</th><th>File Details</th></tr>
                <tr><td>English</td><td>IA-64</td><td>wdk_ia64.iso <a href="https://ntriver.org/drive?wdk_ia64.iso">NTriver</a></td></tr>
              </table>
            </article>
        """.trimIndent()

        val sections = NtriverCatalog.parseHtmlCatalogPage(sampleHtml, pagePrefix = "Server 2022")
        assertEquals(2, sections.size)

        val nov = sections[0]
        assertTrue(nov.isRecommended)
        assertTrue(nov.buildBadge.contains("20348.5873"))
        assertTrue(nov.note.contains("Only EN and RU"))
        assertEquals(1, nov.entries.size)
        val e1 = nov.entries[0]
        assertEquals("English", e1.language)
        assertEquals("x64", e1.arch)
        assertEquals("en-us_windows_server_2022_updated_nov_2026_x64_dvd_2fd3d7d1.iso", e1.fileName)
        assertEquals("3c667fa417f5559f65df66731e91fe1a982410e8877a6655797bb4a7ed061d6e", e1.sha256)
        assertNull(e1.directStaticUrl)
        assertEquals(e1.fileName, e1.ntriverDriveFilename)
        assertEquals("ntriver:${e1.fileName}", NtriverCatalog.buildRefreshKey(e1))

        val azure = sections[1]
        assertFalse(azure.isRecommended)
        assertEquals(1, azure.entries.size)
        val e2 = azure.entries[0]
        assertTrue(e2.isMicrosoftStatic)
        assertEquals("https://software-static.download.prss.microsoft.com/sg/download/20348.1006.iso", e2.directStaticUrl)
        assertEquals("", NtriverCatalog.buildRefreshKey(e2))
    }

    @Test
    fun parseMarkdownCatalogPage_handlesPipesAndNormalizesX32ToX86() {
        val md = """
            ## Windows 8.1 Update 3 ❤️
            * Build: 9600.17415
            | Language | Arch | File Details |
            | --- | --- | --- |
            | English | x32 | Win8.1_English_x32.iso SHA-256: aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa [NTriver](https://ntriver.org/drive?Win8.1_English_x32.iso) |
            | English | ARM32 | WinRT_arm32.iso [NTriver](https://ntriver.org/drive?WinRT_arm32.iso) |
        """.trimIndent()

        val sections = NtriverCatalog.parseMarkdownCatalogPage(md)
        assertEquals(1, sections.size)
        assertEquals(1, sections[0].entries.size)
        assertEquals("x86", sections[0].entries[0].arch)
        assertEquals("Win8.1_English_x32.iso", sections[0].entries[0].fileName)
    }

    @Test
    fun parseGenerateLinkJson_validatesHttpsAndRejectsHttpOrHtml() {
        val ok = NtriverCatalog.parseGenerateLinkJson("""{"url":"https://cdn.example.com/win11.iso?sig=abc"}""")
        assertEquals("https://cdn.example.com/win11.iso?sig=abc", ok.url)
        assertNull(ok.errorMessage)

        val nested = NtriverCatalog.parseGenerateLinkJson("""{"data":{"url":"https://cdn2.example.com/win11.iso"}}""")
        assertEquals("https://cdn2.example.com/win11.iso", nested.url)

        val insecure = NtriverCatalog.parseGenerateLinkJson("""{"url":"http://insecure.example.com/win11.iso"}""")
        assertNull(insecure.url)
        assertNotNull(insecure.errorMessage)

        val htmlChallenge = NtriverCatalog.parseGenerateLinkJson("<!DOCTYPE html><html><title>Just a moment...</title></html>")
        assertNull(htmlChallenge.url)
        assertTrue(htmlChallenge.needsWebViewFallback)
        assertFalse(htmlChallenge.isRateLimited)
    }

    @Test
    fun downloaderCookiesAndFilenameDetection_isolateHostsAndDetectRtmNames() {
        val jar = mapOf(
            "ntriver.org" to mapOf("cf_clearance" to "secret123"),
            "storage.example.net" to mapOf("dl_tok" to "abc")
        )
        assertEquals("cf_clearance=secret123", FastIsoDownloader.selectCookiesForHost(jar, "ntriver.org"))
        assertEquals("dl_tok=abc", FastIsoDownloader.selectCookiesForHost(jar, "storage.example.net"))
        assertEquals("", FastIsoDownloader.selectCookiesForHost(jar, "s3.amazonaws.com"))

        assertTrue(WindowsCatalogFamily.WIN11.supportsMicrosoftCdn)
        assertTrue(WindowsCatalogFamily.WIN10.supportsMicrosoftCdn)
        assertTrue(WindowsCatalogFamily.WIN81.supportsMicrosoftCdn)
        assertFalse(WindowsCatalogFamily.WIN_LTSC.supportsMicrosoftCdn)
        assertFalse(WindowsCatalogFamily.WIN7.supportsMicrosoftCdn)
        assertFalse(WindowsCatalogFamily.WIN_SERVER.supportsMicrosoftCdn)

        assertEquals(WindowsOsKind.WIN7, WindowsCustomizationEngine.detectWindowsOsKind("en_windows_7_ultimate_with_sp1_x64_dvd_u_677332.iso"))
        assertEquals(WindowsOsKind.WIN81, WindowsCustomizationEngine.detectWindowsOsKind("en_windows_8.1_with_update_x64_dvd.iso"))
        assertEquals("x86", WindowsCustomizationEngine.detectProcessorArchitecture("Win8.1_SingleLang_English_x32.iso"))
        assertEquals(IsoOsType.WINDOWS, IsoInspector.quickGuessByName("20348.1006.220908-1954.fe_release_svc_refresh_SERVERDCAZURE_VOL_x64FRE_en-us.iso"))

        // Audit fixes: Consumer Home/Pro/Education stays recommended, standalone Education is niche, h4 headings & azureedge.net parse cleanly
        assertTrue(NtriverCatalog.isRecommendedSection("Windows 11 26H2 Consumer (Home/Pro/Education)", "Windows 11 26H2 Consumer (Home/Pro/Education)"))
        assertFalse(NtriverCatalog.isRecommendedSection("Windows 11 Education", "Windows 11 Education"))
        val h4Html = """
            <article>
              <h4>Windows 11 26H2 Consumer</h4>
              <table>
                <tr><th>Language</th><th>Arch</th><th>File Details</th></tr>
                <tr><td>English</td><td>x64</td><td>Win11_26H2_English_x64.iso <a href="https://msdl.azureedge.net/Win11_26H2_English_x64.iso?sig=1">Microsoft</a></td></tr>
              </table>
            </article>
        """.trimIndent()
        val h4Secs = NtriverCatalog.parseHtmlCatalogPage(h4Html)
        assertEquals(1, h4Secs.size)
        assertTrue(h4Secs[0].entries[0].isMicrosoftStatic)

        // Audit R2 fixes: GitHub tree LTSC vs Win11 isolation, bold Markdown Build/Note extraction, href-only filename fallback
        val treeMapped = NtriverCatalog.mapGithubTreePaths(
            listOf(
                "docs/download-windows-11-ltsc.md",
                "docs/download-windows-11.md",
                "docs/download-windows-7-embedded.md",
                "docs/download-windows-7.md"
            )
        )
        assertEquals("docs/download-windows-11-ltsc.md", treeMapped["ltsc"])
        assertEquals("docs/download-windows-11.md", treeMapped["win11"])
        assertEquals("docs/download-windows-7.md", treeMapped["win7"])

        val boldMd = """
            ## Windows 11 26H2 Consumer ❤️
            - **Build**: [26300.1234](https://example.com)
            - **Note:** Only EN language uploaded so far.
            | Language | Arch | File Details |
            | --- | --- | --- |
            | English | x64 | [NTriver](https://ntriver.org/drive?Win11_26H2_English_x64.iso) <a href="https://software-static.download.prss.microsoft.com/Win11_26H2_English_x64.iso">Microsoft</a> |
        """.trimIndent()
        val boldSecs = NtriverCatalog.parseMarkdownCatalogPage(boldMd)
        assertEquals(1, boldSecs.size)
        assertEquals("26300.1234", boldSecs[0].buildBadge)
        assertTrue(boldSecs[0].note.contains("Only EN"))
        assertEquals(1, boldSecs[0].entries.size)
        assertEquals("Win11_26H2_English_x64.iso", boldSecs[0].entries[0].fileName)
        assertTrue(boldSecs[0].entries[0].isMicrosoftStatic)

        // Audit R3 fixes: exact slug priority over -arm64/-office, plus 4-column and 2-column HTML tables
        val trickyIndex = """
            <a href="/download-windows-11-arm64">Windows 11 ARM64</a>
            <a href="/download-windows-11">Windows 11</a>
            <a href="/download-windows-office">Overview</a>
        """.trimIndent()
        assertEquals("/download-windows-11", NtriverCatalog.parseIndexPageLinks(trickyIndex)["win11"])

        val fourColHtml = """
            <article>
              <h2>Windows 7 SP1 Ultimate ❤️</h2>
              <table>
                <tr><th>Language</th><th>Arch</th><th>File Name</th><th>Download &amp; Checksum</th></tr>
                <tr>
                  <td>English</td>
                  <td>x64</td>
                  <td>en_windows_7_ultimate_with_sp1_x64_dvd_u_677332.iso</td>
                  <td>SHA-256: bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb <a href="https://ntriver.org/drive?en_windows_7_ultimate_with_sp1_x64_dvd_u_677332.iso">NTriver</a></td>
                </tr>
              </table>
              <h2>Windows 7 Compact Table</h2>
              <table>
                <tr><th>Language</th><th>File Details</th></tr>
                <tr>
                  <td>English</td>
                  <td>en_windows_7_pro_x86_dvd.iso <a href="https://ntriver.org/drive?en_windows_7_pro_x86_dvd.iso">NTriver</a></td>
                </tr>
              </table>
            </article>
        """.trimIndent()
        val parsedTables = NtriverCatalog.parseHtmlCatalogPage(fourColHtml)
        assertEquals(2, parsedTables.size)
        assertEquals("bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb", parsedTables[0].entries[0].sha256)
        assertEquals("x86", parsedTables[1].entries[0].arch)

        // Audit R4 fixes:
        // 1) Duplicate (Language, Arch) disambiguation preserves both US & International English ISOs
        val dupHtml = """
            <article>
              <h2>Windows 11 25H2 Consumer ❤️</h2>
              <table>
                <tr><th>Language</th><th>Arch</th><th>File Details</th></tr>
                <tr><td>English</td><td>x64</td><td>en-us_windows_11_consumer_x64.iso <a href="https://ntriver.org/drive?en-us_windows_11_consumer_x64.iso">NTriver</a></td></tr>
                <tr><td>English</td><td>x64</td><td>en-gb_windows_11_consumer_x64.iso <a href="https://ntriver.org/drive?en-gb_windows_11_consumer_x64.iso">NTriver</a></td></tr>
              </table>
            </article>
        """.trimIndent()
        val dupSecs = NtriverCatalog.parseHtmlCatalogPage(dupHtml)
        assertEquals(1, dupSecs.size)
        assertEquals(2, dupSecs[0].entries.size)
        assertEquals("English (US)", dupSecs[0].entries[0].effectiveLanguageLabel)
        assertEquals("English (International)", dupSecs[0].entries[1].effectiveLanguageLabel)

        // 2) Strict domain suffix security (reject evilmicrosoft.com & evilntriver.org) + multi-param & `+` filename preservation
        assertFalse(NtriverCatalog.isDirectMicrosoftIsoUrl("https://evilmicrosoft.com/win11.iso"))
        assertTrue(NtriverCatalog.isDirectMicrosoftIsoUrl("https://software-static.download.prss.microsoft.com/win11.iso?sig=1#dl"))
        assertNull(NtriverCatalog.extractDriveFilenameFromUrl("https://evilntriver.org/drive?win11.iso"))
        assertEquals("Win11_26H2+Nov.iso", NtriverCatalog.extractDriveFilenameFromUrl("https://ntriver.org/drive?ref=web&filename=Win11_26H2+Nov.iso"))

        // 3) 2-column Markdown table + `:---:` alignment separator + escaped pipe `\|` + Win7 SP1 slug discovery
        val twoColMd = """
            ## Windows 7 SP1 ❤️
            | Language | File Details |
            | :---: | :--- |
            | English | en_windows_7_sp1_x64_dvd.iso \| Retail [NTriver](https://ntriver.org/drive?en_windows_7_sp1_x64_dvd.iso) |
        """.trimIndent()
        val twoColSecs = NtriverCatalog.parseMarkdownCatalogPage(twoColMd)
        assertEquals(1, twoColSecs.size)
        assertEquals("x64", twoColSecs[0].entries[0].arch)
        assertEquals("en_windows_7_sp1_x64_dvd.iso", twoColSecs[0].entries[0].fileName)
        assertEquals("/download-windows-7-sp1", NtriverCatalog.parseIndexPageLinks("""<a href="/download-windows-7-sp1">Windows 7 SP1</a>""")["win7"])

        // 4) Duplicate identical filename row merges links & SHA-256 without doubling picker entries; non-drive NTriver link ignored
        val mergeHtml = """
            <article>
              <h2>Windows 10 22H2 Consumer ❤️</h2>
              <table>
                <tr><th>Language</th><th>Arch</th><th>File Details</th></tr>
                <tr><td>English</td><td>x64</td><td>WIN10_22H2.ISO <a href="https://ntriver.org/drive?WIN10_22H2.ISO">NTriver</a></td></tr>
                <tr><td>English</td><td>x64</td><td>WIN10_22H2.ISO SHA-256: cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc <a href="https://software-static.download.prss.microsoft.com/WIN10_22H2.ISO">Microsoft</a></td></tr>
                <tr><td>English</td><td>x64</td><td>guide.iso <a href="https://ntriver.org/about">NTriver Guide</a></td></tr>
              </table>
            </article>
        """.trimIndent()
        val mergedSecs = NtriverCatalog.parseHtmlCatalogPage(mergeHtml)
        assertEquals(1, mergedSecs.size)
        assertEquals(1, mergedSecs[0].entries.size)
        assertTrue(mergedSecs[0].entries[0].isMicrosoftStatic)
        assertEquals("WIN10_22H2.ISO", mergedSecs[0].entries[0].ntriverDriveFilename)
        assertEquals("cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc", mergedSecs[0].entries[0].sha256)

        // 5) RFC 3986 `%20` URL encoding (spaces -> %20, literal `+` -> %2B) & small JSON/XML error payload guard
        assertEquals("Win%2011%2BNov.iso", NtriverCatalog.encodeUrlQueryParam("Win 11+Nov.iso"))
        assertTrue(FastIsoDownloader.isUnexpectedSmallErrorPayload("application/json; charset=utf-8", 128L))
        assertTrue(FastIsoDownloader.isUnexpectedSmallErrorPayload("application/xml", 512L))
        assertFalse(FastIsoDownloader.isUnexpectedSmallErrorPayload("application/octet-stream", 512L))
        assertFalse(FastIsoDownloader.isUnexpectedSmallErrorPayload("application/json", 5_000_000_000L))

        // 6) Root-cause fix: GitHub tree MUST skip `-esd` and `i18n/` files that sort alphabetically before canonical Win 11/10/8.1 docs!
        val esdPoisonedTree = listOf(
            "i18n/zh/docusaurus-plugin-content-docs/current/download-windows-11.md",
            "docs/download-windows-10-1507-esd.md",
            "docs/download-windows-10.md",
            "docs/download-windows-11-21h2-esd.md",
            "docs/download-windows-11-26h2-esd.md",
            "docs/download-windows-11.md",
            "docs/download-windows-8.1-esd.md",
            "docs/download-windows-8.1.md"
        )
        val cleanMapped = NtriverCatalog.mapGithubTreePaths(esdPoisonedTree)
        assertEquals("docs/download-windows-11.md", cleanMapped["win11"])
        assertEquals("docs/download-windows-10.md", cleanMapped["win10"])
        assertEquals("docs/download-windows-8.1.md", cleanMapped["win81"])

        // 7) Parent H2 -> Child H3 hierarchy + 2-level majorVersion / editionLabel grouping (Win 11 26H2 / 25H2)
        val nestedWin11Html = """
            <article>
              <h2>Windows 11 26H2</h2>
              <h3>Consumer (November 2026) ❤️</h3>
              <p>Build: [26300.9974]</p>
              <table>
                <tr><th>Language</th><th>Arch</th><th>File Details</th></tr>
                <tr><td>English</td><td>ARM64</td><td>en-us_windows_11_consumer_editions_version_26h2_updated_nov_2026_arm64_dvd_699230be.iso <a href="https://ntriver.org/drive?en-us_windows_11_consumer_editions_version_26h2_updated_nov_2026_arm64_dvd_699230be.iso">NTriver</a></td></tr>
              </table>
              <h2>Windows 11 25H2</h2>
              <h3>Business (November 2026)</h3>
              <table>
                <tr><th>Language</th><th>Arch</th><th>File Details</th></tr>
                <tr><td>English</td><td>x64</td><td>en-us_windows_11_business_editions_version_25h2_updated_nov_2026_x64_dvd_11111111.iso <a href="https://ntriver.org/drive?en-us_windows_11_business_editions_version_25h2_updated_nov_2026_x64_dvd_11111111.iso">NTriver</a></td></tr>
              </table>
            </article>
        """.trimIndent()
        val win11Secs = NtriverCatalog.parseHtmlCatalogPage(nestedWin11Html)
        assertEquals(2, win11Secs.size)
        assertEquals("26H2", win11Secs[0].effectiveMajorVersion)
        assertEquals("Consumer (November 2026)", win11Secs[0].effectiveEditionLabel)
        assertEquals("25H2", win11Secs[1].effectiveMajorVersion)
        assertEquals("Business (November 2026)", win11Secs[1].effectiveEditionLabel)

        // 8) MDX with inline HTML <table> + Large 1MB HTML stress test (proves zero regex StackOverflowError!)
        val mdxWithHtmlTable = """
            ## Windows 10 22H2
            ### Consumer ❤️
            <table>
              <tr><th>Language</th><th>Arch</th><th>File Details</th></tr>
              <tr><td>English</td><td>x64</td><td>Win10_22H2_English_x64.iso <a href="https://ntriver.org/drive?Win10_22H2_English_x64.iso">NTriver</a></td></tr>
            </table>
        """.trimIndent()
        val mdxSecs = NtriverCatalog.parseMarkdownCatalogPage(mdxWithHtmlTable)
        assertEquals(1, mdxSecs.size)
        assertEquals("22H2", mdxSecs[0].effectiveMajorVersion)
        assertEquals("Consumer", mdxSecs[0].effectiveEditionLabel)

        val hugeHtml = buildString(1_100_000) {
            append("<html><body><article><h2>Windows 11 25H2</h2><h3>Consumer ❤️</h3><table><tr><th>Language</th><th>Arch</th><th>File Details</th></tr>")
            repeat(2500) { idx ->
                append("<tr><td>Lang$idx</td><td>x64</td><td>win11_lang_$idx.iso SHA-256: aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa <a href=\"https://ntriver.org/drive?win11_lang_$idx.iso\">NTriver</a></td></tr>")
            }
            append("</table></article></body></html>")
        }
        val hugeSecs = NtriverCatalog.parseHtmlCatalogPage(hugeHtml)
        assertEquals(1, hugeSecs.size)
        assertEquals(2500, hugeSecs[0].entries.size)

        // 9) Primary vs Sub-Category WindowsCatalogFamily declutter check
        assertTrue(WindowsCatalogFamily.WIN11.isPrimaryFamily)
        assertTrue(WindowsCatalogFamily.WIN10.isPrimaryFamily)
        assertFalse(WindowsCatalogFamily.WIN_LTSC.isPrimaryFamily)
        assertFalse(WindowsCatalogFamily.WIN81.isPrimaryFamily)
        assertFalse(WindowsCatalogFamily.WIN7.isPrimaryFamily)
        assertFalse(WindowsCatalogFamily.WIN_SERVER.isPrimaryFamily)

        // 10) R7 fixes: Relative Markdown links (`/drive?...`, `drive?...`), GFM table without leading `|`, hyphen `Build - ...`
        val relMd = """
            ## Windows 11 26H2 Consumer ❤️
            Build - 26300.9457 (September 2026)
            Language | Arch | Link
            --- | --- | ---
            English | x64 | [en-us_windows_11_consumer_editions_version_26h2_x64_dvd_e4fa21a2.iso](/drive?en-us_windows_11_consumer_editions_version_26h2_x64_dvd_e4fa21a2.iso)
            German | x64 | [NTriver](drive?de-de_windows_11_consumer_editions_version_26h2_x64_dvd_11111111.iso)
        """.trimIndent()
        val relSecs = NtriverCatalog.parseMarkdownCatalogPage(relMd)
        assertEquals(1, relSecs.size)
        assertTrue(relSecs[0].buildBadge.contains("26300.9457"))
        assertEquals(2, relSecs[0].entries.size)
        assertEquals("en-us_windows_11_consumer_editions_version_26h2_x64_dvd_e4fa21a2.iso", relSecs[0].entries[0].ntriverDriveFilename)
        assertEquals("de-de_windows_11_consumer_editions_version_26h2_x64_dvd_11111111.iso", relSecs[0].entries[1].ntriverDriveFilename)

        // 11) Wrapper `<article>` + unquoted `href=/drive?...` + loose ISO fallback (no heading/table)
        val trickyHtml = """
            <article class="banner"><p>Notice banner</p></article>
            <article class="doc">
              <h2>Windows 10 22H2 Consumer ❤️</h2>
              <table>
                <tr><th>Language</th><th>Arch</th><th>Link</th></tr>
                <tr><td>English</td><td>x64</td><td><a href=/drive?Win10_22H2_English_x64.iso>NTriver</a></td></tr>
              </table>
            </article>
        """.trimIndent()
        val trickySecs = NtriverCatalog.parseHtmlCatalogPage(trickyHtml)
        assertEquals(1, trickySecs.size)
        assertEquals("Win10_22H2_English_x64.iso", trickySecs[0].entries[0].fileName)

        val looseHtml = """<div class="cards"><a href="https://ntriver.org/drive?en-us_windows_11_26h2_x64.iso">Download Win11</a></div>"""
        val looseSecs = NtriverCatalog.parseHtmlCatalogPage(looseHtml, pagePrefix = "Windows 11")
        assertEquals(1, looseSecs.size)
        assertEquals("en-us_windows_11_26h2_x64.iso", looseSecs[0].entries[0].fileName)

        // 12) jsDelivr flat & nested JSON tree parsing + windowsupdate.com static URL support
        val flatJsd = """{"files":[{"name":"/i18n/zh/download-windows-11.md"},{"name":"/docs/download-windows-11-esd.md"},{"name":"/docs/windows-iso/download-windows-11.md"},{"name":"/docs/windows-iso/download-windows-10.mdx"}]}"""
        val flatMap = NtriverCatalog.parseJsDelivrTreeJson(flatJsd)
        assertEquals("docs/windows-iso/download-windows-11.md", flatMap["win11"])
        assertEquals("docs/windows-iso/download-windows-10.mdx", flatMap["win10"])

        val nestedJsd = """{"files":[{"type":"directory","name":"docs","files":[{"type":"file","name":"download-windows-8.1.md"}]}]}"""
        assertEquals("docs/download-windows-8.1.md", NtriverCatalog.parseJsDelivrTreeJson(nestedJsd)["win81"])

        assertTrue(NtriverCatalog.isDirectMicrosoftIsoUrl("https://dl.delivery.mp.microsoft.com/filestreamingservice/files/win11.iso"))
        assertTrue(NtriverCatalog.isDirectMicrosoftIsoUrl("https://download.windowsupdate.com/d/msdownload/win10.iso"))
        assertFalse(NtriverCatalog.isDirectMicrosoftIsoUrl("https://evilwindowsupdate.com/win10.iso"))
    }
}
