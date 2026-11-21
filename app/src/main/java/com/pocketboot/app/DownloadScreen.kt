package com.pocketboot.app

import android.content.ClipData
import android.content.ClipboardManager
import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.Usb
import androidx.compose.material.icons.rounded.BuildCircle
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.CreateNewFolder
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Source
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material.icons.rounded.Window
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class DistroVersion(
    val label: String,
    val date: String = "",
    val sizeLabel: String,
    val approxBytes: Long,
    val fileName: String,
    val url: String,
    val sha256: String = ""
)

data class CuratedIso(
    val title: String,
    val subtitle: String,
    val category: String,
    val sizeLabel: String,
    val approxBytes: Long,
    val fileName: String,
    val url: String,
    val versions: List<DistroVersion> = emptyList()
) {
    val effectiveVersions: List<DistroVersion>
        get() = versions.ifEmpty {
            listOf(
                DistroVersion(
                    label = "Default Release",
                    date = "",
                    sizeLabel = sizeLabel,
                    approxBytes = approxBytes,
                    fileName = fileName,
                    url = url
                )
            )
        }
}

const val DISTROS_MANIFEST_URL = "https://raw.githubusercontent.com/bilered/DriveDroidNG/main/distros.json"

val fallbackIsos = listOf(
    CuratedIso(
        title = "Ubuntu Desktop",
        subtitle = "Popular Linux desktop installer for 64-bit PCs",
        category = "Linux",
        sizeLabel = "5.8–6.0 GB",
        approxBytes = 6_335_076_761L,
        fileName = "ubuntu-26.04.1-desktop-amd64.iso",
        url = "https://releases.ubuntu.com/resolute/ubuntu-26.04.1-desktop-amd64.iso",
        versions = listOf(
            DistroVersion("26.04.1 LTS (Latest · Linux 7.0 · Resolute)", "Aug 2026", "5.9 GB", 6_335_076_761L, "ubuntu-26.04.1-desktop-amd64.iso", "https://releases.ubuntu.com/resolute/ubuntu-26.04.1-desktop-amd64.iso"),
            DistroVersion("26.10 (Short-Term Interim Release)", "Oct 2026", "5.9 GB", 6_335_076_761L, "ubuntu-26.10-desktop-amd64.iso", "https://releases.ubuntu.com/26.10/ubuntu-26.10-desktop-amd64.iso"),
            DistroVersion("24.04.5 LTS (Mature · GNOME 46 · Noble)", "Aug 2026", "5.8 GB", 6_227_702_579L, "ubuntu-24.04.5.1-desktop-amd64.iso", "https://releases.ubuntu.com/noble/ubuntu-24.04.5.1-desktop-amd64.iso"),
            DistroVersion("22.04.5 LTS (Legacy Support · Jammy)", "Sep 2024", "4.7 GB", 5_046_586_572L, "ubuntu-22.04.5-desktop-amd64.iso", "https://releases.ubuntu.com/jammy/ubuntu-22.04.5-desktop-amd64.iso")
        )
    ),
    CuratedIso(
        title = "Linux Mint",
        subtitle = "Clean, familiar desktop Linux out of the box",
        category = "Linux",
        sizeLabel = "2.8 GB",
        approxBytes = 3_006_477_107L,
        fileName = "linuxmint-22.1-cinnamon-64bit.iso",
        url = "https://mirrors.edge.kernel.org/linuxmint/stable/22.1/linuxmint-22.1-cinnamon-64bit.iso",
        versions = listOf(
            DistroVersion("22.1 Xia (Cinnamon · Recommended)", "Jan 2025", "2.8 GB", 3_006_477_107L, "linuxmint-22.1-cinnamon-64bit.iso", "https://mirrors.edge.kernel.org/linuxmint/stable/22.1/linuxmint-22.1-cinnamon-64bit.iso"),
            DistroVersion("22.1 Xia (Xfce Lightweight)", "Jan 2025", "2.7 GB", 2_899_102_924L, "linuxmint-22.1-xfce-64bit.iso", "https://mirrors.edge.kernel.org/linuxmint/stable/22.1/linuxmint-22.1-xfce-64bit.iso"),
            DistroVersion("22 Wilma (Cinnamon)", "Jul 2024", "2.8 GB", 3_006_477_107L, "linuxmint-22-cinnamon-64bit.iso", "https://mirrors.edge.kernel.org/linuxmint/stable/22/linuxmint-22-cinnamon-64bit.iso"),
            DistroVersion("21.3 Virginia (Cinnamon)", "Jan 2024", "2.9 GB", 3_113_851_289L, "linuxmint-21.3-cinnamon-64bit.iso", "https://mirrors.edge.kernel.org/linuxmint/stable/21.3/linuxmint-21.3-cinnamon-64bit.iso")
        )
    ),
    CuratedIso(
        title = "Fedora Workstation",
        subtitle = "Cutting-edge GNOME desktop with newest Linux kernel",
        category = "Linux",
        sizeLabel = "2.4–2.5 GB",
        approxBytes = 2_684_354_560L,
        fileName = "Fedora-Workstation-Live-45-1.4.x86_64.iso",
        url = "https://download.fedoraproject.org/pub/fedora/linux/releases/45/Workstation/x86_64/iso/Fedora-Workstation-Live-45-1.4.x86_64.iso",
        versions = listOf(
            DistroVersion("Fedora 45 Workstation (Latest)", "Oct 2026", "2.5 GB", 2_684_354_560L, "Fedora-Workstation-Live-45-1.4.x86_64.iso", "https://download.fedoraproject.org/pub/fedora/linux/releases/45/Workstation/x86_64/iso/Fedora-Workstation-Live-45-1.4.x86_64.iso"),
            DistroVersion("Fedora 44 Workstation", "Apr 2026", "2.4 GB", 2_576_980_377L, "Fedora-Workstation-Live-44-1.7.x86_64.iso", "https://download.fedoraproject.org/pub/fedora/linux/releases/44/Workstation/x86_64/iso/Fedora-Workstation-Live-44-1.7.x86_64.iso"),
            DistroVersion("Fedora 43 Workstation", "Oct 2025", "2.3 GB", 2_469_606_195L, "Fedora-Workstation-Live-43-1.6.x86_64.iso", "https://download.fedoraproject.org/pub/fedora/linux/releases/43/Workstation/x86_64/iso/Fedora-Workstation-Live-43-1.6.x86_64.iso")
        )
    ),
    CuratedIso(
        title = "Arch Linux",
        subtitle = "Minimal rolling-release Linux installer for power users",
        category = "Linux",
        sizeLabel = "1.2 GB",
        approxBytes = 1_317_568_512L,
        fileName = "archlinux-x86_64.iso",
        url = "https://geo.mirror.pkgbuild.com/iso/latest/archlinux-x86_64.iso",
        versions = listOf(
            DistroVersion("Latest Monthly Rolling (x86_64)", "Monthly", "1.2 GB", 1_317_568_512L, "archlinux-x86_64.iso", "https://geo.mirror.pkgbuild.com/iso/latest/archlinux-x86_64.iso")
        )
    ),
    CuratedIso(
        title = "Ubuntu Server",
        subtitle = "Headless LTS Linux server & homelab installer",
        category = "Server",
        sizeLabel = "3.8–3.9 GB",
        approxBytes = 4_187_593_113L,
        fileName = "ubuntu-26.04.1-live-server-amd64.iso",
        url = "https://releases.ubuntu.com/resolute/ubuntu-26.04.1-live-server-amd64.iso",
        versions = listOf(
            DistroVersion("26.04.1 LTS Server (Latest · Resolute)", "Aug 2026", "3.9 GB", 4_187_593_113L, "ubuntu-26.04.1-live-server-amd64.iso", "https://releases.ubuntu.com/resolute/ubuntu-26.04.1-live-server-amd64.iso"),
            DistroVersion("24.04.5 LTS Server (Mature · Noble)", "Aug 2026", "3.8 GB", 4_080_218_931L, "ubuntu-24.04.5-live-server-amd64.iso", "https://releases.ubuntu.com/noble/ubuntu-24.04.5-live-server-amd64.iso"),
            DistroVersion("22.04.5 LTS Server (Legacy · Jammy)", "Sep 2024", "2.0 GB", 2_147_483_648L, "ubuntu-22.04.5-live-server-amd64.iso", "https://releases.ubuntu.com/jammy/ubuntu-22.04.5-live-server-amd64.iso")
        )
    ),
    CuratedIso(
        title = "Alpine Linux",
        subtitle = "Security-oriented, ultra-lean server & container OS",
        category = "Server",
        sizeLabel = "250 MB",
        approxBytes = 262_144_000L,
        fileName = "alpine-standard-3.24.4-x86_64.iso",
        url = "https://dl-cdn.alpinelinux.org/alpine/v3.24/releases/x86_64/alpine-standard-3.24.4-x86_64.iso",
        versions = listOf(
            DistroVersion("3.24.4 Standard (x86_64)", "Nov 2026", "250 MB", 262_144_000L, "alpine-standard-3.24.4-x86_64.iso", "https://dl-cdn.alpinelinux.org/alpine/v3.24/releases/x86_64/alpine-standard-3.24.4-x86_64.iso"),
            DistroVersion("3.24.4 Extended (Offline pkgs)", "Nov 2026", "960 MB", 1_006_632_960L, "alpine-extended-3.24.4-x86_64.iso", "https://dl-cdn.alpinelinux.org/alpine/v3.24/releases/x86_64/alpine-extended-3.24.4-x86_64.iso"),
            DistroVersion("3.23.3 Standard (x86_64)", "2026", "240 MB", 251_658_240L, "alpine-standard-3.23.3-x86_64.iso", "https://dl-cdn.alpinelinux.org/alpine/v3.23/releases/x86_64/alpine-standard-3.23.3-x86_64.iso")
        )
    ),
    CuratedIso(
        title = "Kali Linux",
        subtitle = "Penetration testing & security auditing live/installer",
        category = "Security",
        sizeLabel = "4.5 GB",
        approxBytes = 4_831_838_208L,
        fileName = "kali-linux-2026.3-live-amd64.iso",
        url = "https://cdimage.kali.org/kali-2026.3/kali-linux-2026.3-live-amd64.iso",
        versions = listOf(
            DistroVersion("2026.3 Live (amd64 · Recommended)", "Oct 2026", "4.5 GB", 4_831_838_208L, "kali-linux-2026.3-live-amd64.iso", "https://cdimage.kali.org/kali-2026.3/kali-linux-2026.3-live-amd64.iso"),
            DistroVersion("2026.3 Installer (amd64)", "Oct 2026", "4.2 GB", 4_509_715_660L, "kali-linux-2026.3-installer-amd64.iso", "https://cdimage.kali.org/kali-2026.3/kali-linux-2026.3-installer-amd64.iso"),
            DistroVersion("2026.2 Live (amd64)", "Jun 2026", "4.4 GB", 4_724_464_025L, "kali-linux-2026.2-live-amd64.iso", "https://cdimage.kali.org/kali-2026.2/kali-linux-2026.2-live-amd64.iso")
        )
    ),
    CuratedIso(
        title = "Rescuezilla",
        subtitle = "Graphical disk cloning, backup & image restore",
        category = "Rescue",
        sizeLabel = "1.3 GB",
        approxBytes = 1_395_864_371L,
        fileName = "rescuezilla-2.6.2-64bit.resolute.iso",
        url = "https://github.com/rescuezilla/rescuezilla/releases/download/2.6.2/rescuezilla-2.6.2-64bit.resolute.iso",
        versions = listOf(
            DistroVersion("2.6.2 (Ubuntu 26.04 Base · Newest Drivers)", "2026", "1.3 GB", 1_395_864_371L, "rescuezilla-2.6.2-64bit.resolute.iso", "https://github.com/rescuezilla/rescuezilla/releases/download/2.6.2/rescuezilla-2.6.2-64bit.resolute.iso"),
            DistroVersion("2.5.1 (Ubuntu 24.04 Base · Mature)", "2024", "1.2 GB", 1_288_490_188L, "rescuezilla-2.5.1-64bit.noble.iso", "https://github.com/rescuezilla/rescuezilla/releases/download/2.5.1/rescuezilla-2.5.1-64bit.noble.iso")
        )
    ),
    CuratedIso(
        title = "SystemRescue",
        subtitle = "Bootable PC repair, partition & data recovery kit",
        category = "Rescue",
        sizeLabel = "1.37 GB",
        approxBytes = 1_434_451_968L,
        fileName = "systemrescue-13.03-amd64.iso",
        url = "https://fastly-cdn.system-rescue.org/releases/13.03/systemrescue-13.03-amd64.iso",
        versions = listOf(
            DistroVersion("13.03 (Latest amd64)", "2026", "1.37 GB", 1_434_451_968L, "systemrescue-13.03-amd64.iso", "https://fastly-cdn.system-rescue.org/releases/13.03/systemrescue-13.03-amd64.iso"),
            DistroVersion("12.01 (amd64)", "2025", "1.2 GB", 1_288_490_188L, "systemrescue-12.01-amd64.iso", "https://fastly-cdn.system-rescue.org/releases/12.01/systemrescue-12.01-amd64.iso")
        )
    ),
    CuratedIso(
        title = "GParted Live",
        subtitle = "Resize, clone, and repair disk partitions safely",
        category = "Rescue",
        sizeLabel = "560 MB",
        approxBytes = 587_202_560L,
        fileName = "gparted-live-1.8.1-6-amd64.iso",
        url = "https://downloads.sourceforge.net/gparted/gparted-live-1.8.1-6-amd64.iso",
        versions = listOf(
            DistroVersion("1.8.1-6 (amd64 · Latest)", "2026", "560 MB", 587_202_560L, "gparted-live-1.8.1-6-amd64.iso", "https://downloads.sourceforge.net/gparted/gparted-live-1.8.1-6-amd64.iso"),
            DistroVersion("1.7.0-8 (amd64)", "2025", "540 MB", 566_231_040L, "gparted-live-1.7.0-8-amd64.iso", "https://downloads.sourceforge.net/gparted/gparted-live-1.7.0-8-amd64.iso")
        )
    ),
    CuratedIso(
        title = "netboot.xyz",
        subtitle = "Tiny 2 MB network boot menu for dozens of OSes",
        category = "Lightweight",
        sizeLabel = "2 MB",
        approxBytes = 2_500_000L,
        fileName = "netboot.xyz.iso",
        url = "https://boot.netboot.xyz/ipxe/netboot.xyz.iso",
        versions = listOf(
            DistroVersion("Latest x86_64 iPXE ISO", "Rolling", "2 MB", 2_500_000L, "netboot.xyz.iso", "https://boot.netboot.xyz/ipxe/netboot.xyz.iso"),
            DistroVersion("Latest ARM64 iPXE ISO", "Rolling", "2 MB", 2_500_000L, "netboot.xyz-arm64.iso", "https://boot.netboot.xyz/ipxe/netboot.xyz-arm64.iso")
        )
    ),
    CuratedIso(
        title = "Tiny Core Linux",
        subtitle = "Ultra-small live Linux desktop for quick boot tests",
        category = "Lightweight",
        sizeLabel = "23 MB",
        approxBytes = 24_000_000L,
        fileName = "TinyCore-current.iso",
        url = "https://distro.ibiblio.org/tinycorelinux/15.x/x86/release/TinyCore-current.iso",
        versions = listOf(
            DistroVersion("15.x TinyCore (GUI · 23 MB)", "Current", "23 MB", 24_000_000L, "TinyCore-current.iso", "https://distro.ibiblio.org/tinycorelinux/15.x/x86/release/TinyCore-current.iso"),
            DistroVersion("15.x CorePlus (Wi-Fi + Tools)", "Current", "248 MB", 260_046_848L, "CorePlus-current.iso", "https://distro.ibiblio.org/tinycorelinux/15.x/x86/release/CorePlus-current.iso"),
            DistroVersion("15.x Core (CLI Only)", "Current", "17 MB", 17_825_792L, "Core-current.iso", "https://distro.ibiblio.org/tinycorelinux/15.x/x86/release/Core-current.iso")
        )
    )
)

private fun parseDistrosJson(rawJson: String): List<CuratedIso> {
    if (rawJson.isBlank()) return emptyList()
    return runCatching {
        val trimmed = rawJson.trim()
        val arr: JSONArray = if (trimmed.startsWith("[")) {
            JSONArray(trimmed)
        } else {
            val obj = JSONObject(trimmed)
            obj.optJSONArray("distros") ?: JSONArray()
        }
        val out = mutableListOf<CuratedIso>()
        for (i in 0 until arr.length()) {
            val item = arr.optJSONObject(i) ?: continue
            val title = item.optString("title").trim()
            val url = item.optString("url").trim()
            val fileName = item.optString("fileName").trim()
            val category = item.optString("category", "Linux")
            if (category.equals("Windows", true) && url.contains("microsoft.com/software-download", true)) continue
            if (title.isEmpty() || url.isEmpty() || fileName.isEmpty()) continue

            val versArr = item.optJSONArray("versions")
            val parsedVersions = mutableListOf<DistroVersion>()
            if (versArr != null) {
                for (j in 0 until versArr.length()) {
                    val v = versArr.optJSONObject(j) ?: continue
                    val vUrl = v.optString("url").trim()
                    val vName = v.optString("fileName").trim()
                    if (vUrl.isEmpty() || vName.isEmpty()) continue
                    parsedVersions.add(
                        DistroVersion(
                            label = v.optString("label", vName),
                            date = v.optString("date", ""),
                            sizeLabel = v.optString("sizeLabel", "ISO"),
                            approxBytes = v.optLong("approxBytes", 0L),
                            fileName = vName,
                            url = vUrl,
                            sha256 = v.optString("sha256", "")
                        )
                    )
                }
            }
            out.add(
                CuratedIso(
                    title = title,
                    subtitle = item.optString("subtitle", "Bootable OS image"),
                    category = category,
                    sizeLabel = item.optString("sizeLabel", "ISO"),
                    approxBytes = item.optLong("approxBytes", 0L),
                    fileName = fileName,
                    url = url,
                    versions = parsedVersions
                )
            )
        }
        out
    }.getOrDefault(emptyList())
}

private fun serializeDistrosJson(list: List<CuratedIso>): String {
    val arr = JSONArray()
    list.forEach { d ->
        val obj = JSONObject().apply {
            put("title", d.title)
            put("subtitle", d.subtitle)
            put("category", d.category)
            put("sizeLabel", d.sizeLabel)
            put("approxBytes", d.approxBytes)
            put("fileName", d.fileName)
            put("url", d.url)
            if (d.versions.isNotEmpty()) {
                val vArr = JSONArray()
                d.versions.forEach { v ->
                    vArr.put(
                        JSONObject().apply {
                            put("label", v.label)
                            put("date", v.date)
                            put("sizeLabel", v.sizeLabel)
                            put("approxBytes", v.approxBytes)
                            put("fileName", v.fileName)
                            put("url", v.url)
                            if (v.sha256.isNotBlank()) put("sha256", v.sha256)
                        }
                    )
                }
                put("versions", vArr)
            }
        }
        arr.put(obj)
    }
    return JSONObject().put("version", 4).put("distros", arr).toString()
}

private suspend fun fetchUrlText(urlStr: String): String? = withContext(Dispatchers.IO) {
    runCatching {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            connectTimeout = 9000
            readTimeout = 12000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "PocketBoot-Android")
        }
        try {
            if (conn.responseCode in 200..299) {
                conn.inputStream.bufferedReader().use { it.readText() }
            } else null
        } finally {
            conn.disconnect()
        }
    }.getOrNull()
}

private suspend fun fetchAllRepos(repoUrls: List<String>): List<CuratedIso> {
    val merged = mutableListOf<CuratedIso>()
    val seenUrls = mutableSetOf<String>()
    for (url in repoUrls) {
        val raw = fetchUrlText(url) ?: continue
        val parsed = parseDistrosJson(raw)
        parsed.forEach { iso ->
            if (seenUrls.add(iso.url.lowercase())) {
                merged.add(iso)
            }
        }
    }
    return merged
}

@Composable
private fun CatalogGroupHeader(
    icon: ImageVector,
    title: String,
    count: Int,
    tint: Color,
    bg: Color
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 6.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Box(
            modifier = Modifier
                .size(24.dp)
                .clip(RoundedCornerShape(7.dp))
                .background(bg),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(14.dp))
        }
        Text(
            text = title.uppercase(),
            fontSize = 11.5.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.9.sp,
            color = tint
        )
        Box(
            modifier = Modifier
                .weight(1f)
                .height(0.5.dp)
                .background(PocketColors.border)
        )
        Text(
            text = "$count",
            style = PocketColors.monoStyle,
            color = PocketColors.textMuted
        )
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun DownloadScreen(
    settings: AppSettings,
    existingFileNames: Set<String>,
    multiBootDrives: List<ImageFile>,
    onUpdateSettings: (AppSettings) -> Unit,
    onDownloadCompleted: (DownloadCompletionEvent) -> Unit,
    onQuickMountCompleted: (String) -> Unit,
    onOpenWindowsBuilderForPath: (String) -> Unit,
    onAddCompletedToMultiBoot: (String) -> Unit,
    onAppendLog: (LogEntry) -> Unit,
    onMessage: (String, Boolean) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val defaultDir = settings.defaultDir
    val turboState by FastIsoDownloader.state.collectAsState()
    val winBuildState by WindowsBuildEngine.jobState.collectAsState()
    val lastCompletion by FastIsoDownloader.lastCompletion.collectAsState()

    val activeMultiBootTarget = remember(settings.downloadTargetMultiBootPath, multiBootDrives) {
        val target = settings.downloadTargetMultiBootPath
        if (target.isBlank()) null else multiBootDrives.firstOrNull {
            UsbGadgetEngine.resolveDirectPath(it.path) == UsbGadgetEngine.resolveDirectPath(target)
        }
    }

    var pendingResumables by remember { mutableStateOf<List<PendingResumableDownload>>(emptyList()) }
    var pendingWinBuilds by remember { mutableStateOf<List<PendingWindowsBuild>>(emptyList()) }
    LaunchedEffect(turboState, winBuildState, settings.defaultDir) {
        pendingResumables = FastIsoDownloader.scanPendingResumableDownloads(context)
        pendingWinBuilds = WindowsBuildEngine.scanPendingBuilds(context, settings.scanDirs + settings.defaultDir)
    }

    val initialList = remember(settings.cachedDistrosJson) {
        val cached = parseDistrosJson(settings.cachedDistrosJson)
        if (cached.any { it.versions.size > 1 }) cached else fallbackIsos
    }
    var distroList by remember { mutableStateOf(initialList) }
    var syncing by remember { mutableStateOf(false) }
    var showReposDialog by remember { mutableStateOf(false) }
    var showSpeedSettingsDialog by remember { mutableStateOf(false) }
    var folderMenuExpanded by remember { mutableStateOf(false) }
    var showAddFolderDialog by remember { mutableStateOf(false) }

    var selectedWindowsFamily by remember { mutableStateOf<WindowsCatalogFamily?>(null) }
    var selectedDistroForVersion by remember { mutableStateOf<CuratedIso?>(null) }
    var longPressedDistro by remember { mutableStateOf<CuratedIso?>(null) }
    var longPressedWindows by remember { mutableStateOf<WindowsCatalogFamily?>(null) }
    var catalogSearch by remember { mutableStateOf("") }
    var showCancelWinBuildDialog by remember { mutableStateOf(false) }

    var customUrl by remember { mutableStateOf("") }
    var selectedCategory by remember { mutableStateOf("All") }
    var autoMount by remember { mutableStateOf(true) }

    val anyDownloadActive = turboState != null || winBuildState != null
    val freeBytes = remember(defaultDir, turboState) { UsbGadgetEngine.getFreeSpaceBytes(defaultDir) }

    val activeRepoUrls = remember(settings.useDefaultDistroRepo, settings.customRepoUrls) {
        buildList {
            if (settings.useDefaultDistroRepo) add(DISTROS_MANIFEST_URL)
            addAll(settings.customRepoUrls)
        }.distinct()
    }

    fun copyLinkToClipboard(label: String, url: String) {
        val cm = context.getSystemService(ClipboardManager::class.java)
        cm?.setPrimaryClip(ClipData.newPlainText(label, url))
        Toast.makeText(context, "Copied direct link for external download managers.", Toast.LENGTH_SHORT).show()
    }

    fun syncCatalogs(showToast: Boolean) {
        if (activeRepoUrls.isEmpty()) {
            distroList = fallbackIsos
            if (showToast) onMessage("No repositories enabled. Showing built-in catalog.", false)
            return
        }
        scope.launch {
            syncing = true
            val fetched = fetchAllRepos(activeRepoUrls)
            if (fetched.isNotEmpty() && fetched.any { it.versions.isNotEmpty() }) {
                distroList = fetched
                onUpdateSettings(settings.copy(cachedDistrosJson = serializeDistrosJson(fetched)))
                if (showToast) onMessage("Synced ${fetched.size} distros from ${activeRepoUrls.size} repo(s).", false)
            } else if (fetched.isNotEmpty()) {
                val knownTitles = fallbackIsos.map { it.title.lowercase() }.toSet()
                val extras = fetched.filter { it.title.lowercase() !in knownTitles }
                distroList = fallbackIsos + extras
                if (showToast) onMessage("Synced catalog (${distroList.size} distros).", false)
            } else {
                if (showToast) onMessage("Could not reach remote repo. Using built-in catalog.", true)
            }
            syncing = false
        }
    }

    LaunchedEffect(activeRepoUrls) {
        if (activeRepoUrls.isNotEmpty()) {
            syncCatalogs(showToast = false)
        }
    }

    fun startTurboDownload(
        title: String,
        url: String,
        fileName: String,
        approxBytes: Long = 0L,
        expectedSha256: String = ""
    ) {
        val cleanUrl = url.trim()
        if (!cleanUrl.startsWith("http://") && !cleanUrl.startsWith("https://")) {
            onMessage("Please enter a valid http:// or https:// download link.", true)
            return
        }
        val cleanName = fileName.trim().ifBlank { "downloaded-image.iso" }
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
        val mbTargetPath = activeMultiBootTarget?.path.orEmpty()

        FastIsoDownloader.start(
            context = context,
            title = title,
            url = cleanUrl,
            fileName = cleanName,
            targetDir = defaultDir,
            targetMultiBootPath = mbTargetPath,
            approxBytes = approxBytes,
            expectedSha256 = expectedSha256,
            connections = settings.downloadConnections,
            autoMount = autoMount,
            autoVerifySha256 = settings.autoVerifySha256,
            onComplete = { evt ->
                scope.launch { onDownloadCompleted(evt) }
            },
            onError = { err ->
                scope.launch {
                    pendingResumables = FastIsoDownloader.scanPendingResumableDownloads(context)
                    onMessage(err, true)
                }
            }
        )
        val destLabel = if (activeMultiBootTarget != null) " → ${activeMultiBootTarget.name}" else ""
        onMessage("Started Turbo (${settings.downloadConnections}x): $cleanName$destLabel", false)
    }

    val dynamicCategories = remember(distroList) {
        val cats = distroList.map { it.category }.filter { it.isNotBlank() && !it.equals("Windows", true) }.distinct()
        listOf("All", "Windows") + cats
    }
    val nonWinDistros = remember(distroList) {
        distroList.filterNot { it.category.equals("Windows", true) }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = PocketColors.surfaceCard,
            border = BorderStroke(1.dp, PocketColors.border)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(PocketColors.accentBg.copy(alpha = 0.45f), PocketColors.surfaceCard)
                        )
                    )
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = customUrl,
                        onValueChange = { customUrl = it },
                        placeholder = { Text("Paste .iso or .img URL...", fontSize = 13.sp, color = PocketColors.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        leadingIcon = { Icon(Icons.Rounded.Link, null, tint = PocketColors.accentSoft, modifier = Modifier.size(18.dp)) },
                        trailingIcon = {
                            IconButton(
                                onClick = {
                                    val cm = context.getSystemService(ClipboardManager::class.java)
                                    val clipText = cm?.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
                                    if (clipText.isNotBlank()) customUrl = clipText.trim() else onMessage("Clipboard is empty.", false)
                                }
                            ) {
                                Icon(Icons.Rounded.ContentPaste, "Paste URL", tint = PocketColors.accentSoft, modifier = Modifier.size(17.dp))
                            }
                        },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                        keyboardActions = KeyboardActions(
                            onGo = {
                                if (!anyDownloadActive) {
                                    val u = customUrl.trim()
                                    if (u.startsWith("http://", true) || u.startsWith("https://", true)) {
                                        val name = Uri.parse(u).lastPathSegment
                                            ?.takeIf { it.contains('.') } ?: "custom-image.iso"
                                        startTurboDownload(name, u, name)
                                    }
                                }
                            }
                        ),
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(PocketTokens.ButtonRadius),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = PocketColors.accent,
                            unfocusedBorderColor = PocketColors.border,
                            focusedContainerColor = PocketColors.surfaceInput,
                            unfocusedContainerColor = PocketColors.surfaceInput,
                            focusedTextColor = PocketColors.textPrimary,
                            unfocusedTextColor = PocketColors.textPrimary
                        )
                    )
                    Box(
                        modifier = Modifier
                            .size(52.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(if (customUrl.isNotBlank() && !anyDownloadActive) PocketColors.accent else PocketColors.surfaceSubtle)
                            .bounceClick(enabled = customUrl.isNotBlank() && !anyDownloadActive) {
                                val guessedName = Uri.parse(customUrl.trim()).lastPathSegment
                                    ?.takeIf { it.contains('.') } ?: "custom-image.iso"
                                startTurboDownload(guessedName, customUrl, guessedName)
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Download,
                            contentDescription = "Download URL",
                            tint = if (customUrl.isNotBlank() && !anyDownloadActive) PocketColors.onAccent else PocketColors.textMuted,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Box(modifier = Modifier.weight(1f)) {
                        val isDirectMb = activeMultiBootTarget != null
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .bounceClick { folderMenuExpanded = true },
                            shape = RoundedCornerShape(10.dp),
                            color = if (isDirectMb) PocketColors.violetBg else PocketColors.surfaceInput,
                            border = BorderStroke(
                                0.75.dp,
                                when {
                                    folderMenuExpanded -> PocketColors.accent
                                    isDirectMb -> PocketColors.violet.copy(alpha = 0.7f)
                                    else -> PocketColors.border
                                }
                            )
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 9.dp, vertical = 9.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Icon(
                                    imageVector = if (isDirectMb) Icons.Rounded.Layers else Icons.Rounded.Storage,
                                    contentDescription = null,
                                    tint = if (isDirectMb) PocketColors.violet else PocketColors.accentSoft,
                                    modifier = Modifier.size(14.dp)
                                )
                                Text(
                                    text = activeMultiBootTarget?.name ?: UsbGadgetEngine.friendlyFolderName(defaultDir),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = PocketColors.textPrimary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f, fill = false)
                                )
                                if (!isDirectMb && freeBytes > 0L) {
                                    Text(
                                        text = "· ${UsbGadgetEngine.formatBytes(freeBytes)}",
                                        style = PocketColors.monoStyle,
                                        color = PocketColors.success,
                                        maxLines = 1
                                    )
                                }
                                Icon(Icons.Rounded.ExpandMore, "Select destination", tint = PocketColors.textSecondary, modifier = Modifier.size(15.dp))
                            }
                        }

                        DropdownMenu(
                            expanded = folderMenuExpanded,
                            onDismissRequest = { folderMenuExpanded = false },
                            modifier = Modifier.background(PocketColors.surfaceCard)
                        ) {
                            settings.scanDirs.forEach { dir ->
                                val isSelected = activeMultiBootTarget == null && dir == defaultDir
                                val dirFree = remember(dir, folderMenuExpanded) { UsbGadgetEngine.getFreeSpaceBytes(dir) }
                                DropdownMenuItem(
                                    text = {
                                        Column {
                                            Text(
                                                text = UsbGadgetEngine.friendlyFolderName(dir),
                                                style = MaterialTheme.typography.labelLarge,
                                                color = if (isSelected) PocketColors.accentSoft else PocketColors.textPrimary
                                            )
                                            Text(
                                                text = if (dirFree > 0L) {
                                                    "${dir.replaceFirst("/data/media/0", "Internal")} · ${UsbGadgetEngine.formatBytes(dirFree)} free"
                                                } else {
                                                    dir.replaceFirst("/data/media/0", "Internal")
                                                },
                                                style = PocketColors.monoStyle,
                                                color = PocketColors.textMuted,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                    },
                                    leadingIcon = {
                                        Icon(
                                            imageVector = if (isSelected) Icons.Rounded.Check else Icons.Rounded.Folder,
                                            contentDescription = null,
                                            tint = if (isSelected) PocketColors.accentSoft else PocketColors.textSecondary,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    },
                                    onClick = {
                                        folderMenuExpanded = false
                                        val reordered = (listOf(dir) + settings.scanDirs.filter { it != dir }).distinct()
                                        onUpdateSettings(settings.copy(defaultDir = dir, scanDirs = reordered, downloadTargetMultiBootPath = ""))
                                        onMessage("Save folder set to ${UsbGadgetEngine.friendlyFolderName(dir)}.", false)
                                    }
                                )
                            }

                            if (multiBootDrives.isNotEmpty()) {
                                PocketHairlineDivider()
                                multiBootDrives.forEach { mb ->
                                    val isSelMb = activeMultiBootTarget?.path == mb.path
                                    DropdownMenuItem(
                                        text = {
                                            Column {
                                                Text(
                                                    text = "Directly into ${mb.name}",
                                                    style = MaterialTheme.typography.labelLarge,
                                                    color = if (isSelMb) PocketColors.violet else PocketColors.textPrimary,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                                Text(
                                                    text = "Auto-moves downloaded ISO into Multi-Boot",
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = PocketColors.textMuted
                                                )
                                            }
                                        },
                                        leadingIcon = {
                                            Icon(
                                                imageVector = if (isSelMb) Icons.Rounded.Check else Icons.Rounded.Layers,
                                                contentDescription = null,
                                                tint = PocketColors.violet,
                                                modifier = Modifier.size(18.dp)
                                            )
                                        },
                                        onClick = {
                                            folderMenuExpanded = false
                                            onUpdateSettings(settings.copy(downloadTargetMultiBootPath = mb.path))
                                            onMessage("Downloads will move directly into ${mb.name}.", false)
                                        }
                                    )
                                }
                            }

                            PocketHairlineDivider()
                            DropdownMenuItem(
                                text = { Text("Add New Folder...", style = MaterialTheme.typography.labelLarge, color = PocketColors.accentSoft) },
                                leadingIcon = { Icon(Icons.Rounded.CreateNewFolder, null, tint = PocketColors.accentSoft, modifier = Modifier.size(18.dp)) },
                                onClick = {
                                    folderMenuExpanded = false
                                    showAddFolderDialog = true
                                }
                            )
                        }
                    }

                    Surface(
                        modifier = Modifier.bounceClick { showSpeedSettingsDialog = true },
                        shape = RoundedCornerShape(10.dp),
                        color = PocketColors.warningBg,
                        border = BorderStroke(0.75.dp, PocketColors.warning.copy(alpha = 0.7f))
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 9.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(3.dp)
                        ) {
                            Icon(Icons.Rounded.Bolt, "Turbo Speed", tint = PocketColors.warning, modifier = Modifier.size(15.dp))
                            Text("${settings.downloadConnections}x", style = PocketColors.monoStyle, color = PocketColors.warning)
                        }
                    }

                    Surface(
                        modifier = Modifier.bounceClick(enabled = !syncing) { syncCatalogs(showToast = true) },
                        shape = RoundedCornerShape(10.dp),
                        color = PocketColors.surfaceInput,
                        border = BorderStroke(0.5.dp, PocketColors.border)
                    ) {
                        Box(modifier = Modifier.padding(horizontal = 9.dp, vertical = 9.dp), contentAlignment = Alignment.Center) {
                            if (syncing) {
                                CircularProgressIndicator(modifier = Modifier.size(15.dp), strokeWidth = 2.dp, color = PocketColors.accent)
                            } else {
                                Icon(Icons.Rounded.Sync, "Sync Catalog", tint = PocketColors.accentSoft, modifier = Modifier.size(16.dp))
                            }
                        }
                    }

                    Surface(
                        modifier = Modifier.bounceClick { showReposDialog = true },
                        shape = RoundedCornerShape(10.dp),
                        color = PocketColors.surfaceInput,
                        border = BorderStroke(0.5.dp, PocketColors.border)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 9.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(3.dp)
                        ) {
                            Icon(Icons.Rounded.Source, "Repos", tint = PocketColors.accentSoft, modifier = Modifier.size(14.dp))
                            Text("${activeRepoUrls.size}", style = PocketColors.monoStyle, color = PocketColors.textPrimary)
                        }
                    }
                }
            }
        }

        AnimatedVisibility(
            visible = pendingWinBuilds.isNotEmpty() && winBuildState == null,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically()
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                PocketSectionHeader("Unfinished Windows Builds · Resumable", trailing = "${pendingWinBuilds.size}")
                pendingWinBuilds.forEach { pb ->
                    Surface(
                        shape = RoundedCornerShape(PocketTokens.CardRadius),
                        color = PocketColors.surfaceCard,
                        border = BorderStroke(1.dp, PocketColors.infoBlue.copy(alpha = 0.7f))
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 13.dp, vertical = 11.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Box(
                                modifier = Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(PocketColors.infoBlueBg),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Rounded.Build, null, tint = PocketColors.infoBlue, modifier = Modifier.size(18.dp))
                            }
                            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(pb.title, style = MaterialTheme.typography.labelLarge, color = PocketColors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    text = if (pb.totalBytes > 0L) "${(pb.progress * 100).toInt()}% · ${UsbGadgetEngine.formatBytes(pb.cachedBytes)} / ${UsbGadgetEngine.formatBytes(pb.totalBytes)}" else "${UsbGadgetEngine.formatBytes(pb.cachedBytes)} cached",
                                    style = PocketColors.monoStyle,
                                    color = PocketColors.textSecondary
                                )
                            }
                            Button(
                                onClick = {
                                    WindowsBuildEngine.startBuild(
                                        context = context,
                                        config = pb.config.copy(connections = settings.downloadConnections),
                                        onComplete = { evt -> scope.launch { onDownloadCompleted(evt) } },
                                        onError = { err -> scope.launch { onMessage(err, true) } },
                                        onLog = { entry -> scope.launch { onAppendLog(entry) } }
                                    )
                                },
                                contentPadding = PaddingValues(horizontal = 11.dp, vertical = 6.dp),
                                shape = RoundedCornerShape(PocketTokens.CompactButtonRadius),
                                colors = ButtonDefaults.buttonColors(containerColor = PocketColors.infoBlue, contentColor = Color.White)
                            ) {
                                Text("Resume", fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold)
                            }
                            IconButton(
                                onClick = {
                                    WindowsBuildEngine.discardPendingBuild(context, pb)
                                    pendingWinBuilds = pendingWinBuilds.filterNot { it.workRootPath == pb.workRootPath }
                                    onMessage("Discarded unfinished build ${pb.title}.", false)
                                },
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(Icons.Rounded.Close, "Discard", tint = PocketColors.textMuted, modifier = Modifier.size(16.dp))
                            }
                        }
                    }
                }
            }
        }

        AnimatedVisibility(
            visible = lastCompletion != null && turboState == null && winBuildState == null,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically()
        ) {
            lastCompletion?.let { done ->
                val isMismatch = done.checksumStatus == ChecksumVerifyStatus.MISMATCH
                val isVerified = done.checksumStatus == ChecksumVerifyStatus.VERIFIED_MATCH
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = when {
                        isMismatch -> PocketColors.dangerBg
                        isVerified -> PocketColors.successBg
                        else -> PocketColors.surfaceCard
                    },
                    border = BorderStroke(
                        1.dp,
                        when {
                            isMismatch -> PocketColors.danger
                            isVerified -> PocketColors.success
                            else -> PocketColors.accent
                        }
                    )
                ) {
                    Column(modifier = Modifier.fillMaxWidth().padding(13.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(
                                imageVector = if (isMismatch) Icons.Rounded.WarningAmber else Icons.Rounded.VerifiedUser,
                                contentDescription = null,
                                tint = when {
                                    isMismatch -> PocketColors.danger
                                    isVerified -> PocketColors.success
                                    else -> PocketColors.accentSoft
                                },
                                modifier = Modifier.size(19.dp)
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = when {
                                        isMismatch -> "SHA-256 Checksum Mismatch!"
                                        isVerified -> "Verified Genuine ISO"
                                        done.injectedMultiBootPath != null -> "Added to ${done.injectedMultiBootPath.substringAfterLast('/')}"
                                        else -> "Download Complete"
                                    },
                                    style = MaterialTheme.typography.labelLarge,
                                    color = PocketColors.textPrimary
                                )
                                Text(done.fileName, style = PocketColors.monoStyle, color = PocketColors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            if (!done.computedSha256.isNullOrBlank()) {
                                IconButton(
                                    onClick = {
                                        val cm = context.getSystemService(ClipboardManager::class.java)
                                        cm?.setPrimaryClip(ClipData.newPlainText("SHA-256", done.computedSha256))
                                        Toast.makeText(context, "SHA-256 copied.", Toast.LENGTH_SHORT).show()
                                    },
                                    modifier = Modifier.size(30.dp)
                                ) {
                                    Icon(Icons.Rounded.ContentCopy, "Copy SHA-256", tint = PocketColors.accentSoft, modifier = Modifier.size(16.dp))
                                }
                            }
                            IconButton(
                                onClick = { FastIsoDownloader.dismissLastCompletion() },
                                modifier = Modifier.size(30.dp)
                            ) {
                                Icon(Icons.Rounded.Close, "Dismiss", tint = PocketColors.textMuted, modifier = Modifier.size(16.dp))
                            }
                        }
                        if (!done.computedSha256.isNullOrBlank()) {
                            Text(
                                text = "SHA-256: ${done.computedSha256}",
                                style = PocketColors.monoStyle,
                                color = PocketColors.textMuted,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        if (!isMismatch && done.injectedMultiBootPath == null) {
                            val isWinIso = done.fileName.endsWith(".iso", true) &&
                                (IsoInspector.quickGuessByName(done.fileName) == IsoOsType.WINDOWS || done.title.contains("Win", true))
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                                horizontalArrangement = Arrangement.spacedBy(7.dp)
                            ) {
                                Surface(
                                    modifier = Modifier.weight(1f).bounceClick { onQuickMountCompleted(done.savedPath) },
                                    shape = RoundedCornerShape(PocketTokens.CompactButtonRadius),
                                    color = PocketColors.accentBg,
                                    border = BorderStroke(1.dp, PocketColors.accent)
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
                                        horizontalArrangement = Arrangement.Center,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(Icons.Rounded.Usb, null, tint = PocketColors.accentSoft, modifier = Modifier.size(14.dp))
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("Mount", style = MaterialTheme.typography.labelSmall, color = PocketColors.accentSoft, maxLines = 1)
                                    }
                                }
                                if (isWinIso) {
                                    Surface(
                                        modifier = Modifier.weight(1.25f).bounceClick { onOpenWindowsBuilderForPath(done.savedPath) },
                                        shape = RoundedCornerShape(PocketTokens.CompactButtonRadius),
                                        color = PocketColors.infoBlueBg,
                                        border = BorderStroke(1.dp, PocketColors.infoBlue)
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
                                            horizontalArrangement = Arrangement.Center,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Icon(Icons.Rounded.Build, null, tint = PocketColors.infoBlue, modifier = Modifier.size(14.dp))
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text("Win USB", style = MaterialTheme.typography.labelSmall, color = PocketColors.infoBlue, maxLines = 1)
                                        }
                                    }
                                }
                                if (done.fileName.endsWith(".iso", true) && multiBootDrives.isNotEmpty()) {
                                    Surface(
                                        modifier = Modifier.weight(1.25f).bounceClick { onAddCompletedToMultiBoot(done.savedPath) },
                                        shape = RoundedCornerShape(PocketTokens.CompactButtonRadius),
                                        color = PocketColors.violetBg,
                                        border = BorderStroke(1.dp, PocketColors.violet)
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
                                            horizontalArrangement = Arrangement.Center,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Icon(Icons.Rounded.Layers, null, tint = PocketColors.violet, modifier = Modifier.size(14.dp))
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text("Multi-Boot", style = MaterialTheme.typography.labelSmall, color = PocketColors.violet, maxLines = 1)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        AnimatedVisibility(
            visible = pendingResumables.isNotEmpty() && turboState == null,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically()
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                PocketSectionHeader("Unfinished Downloads · Resumable", trailing = "${pendingResumables.size}")
                pendingResumables.forEach { item ->
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = PocketColors.surfaceCard,
                        border = BorderStroke(1.dp, PocketColors.warning.copy(alpha = 0.7f))
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 13.dp, vertical = 11.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Box(
                                modifier = Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(PocketColors.warningBg),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Rounded.History, null, tint = PocketColors.warning, modifier = Modifier.size(19.dp))
                            }
                            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(item.title, style = MaterialTheme.typography.labelLarge, color = PocketColors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    text = "${(item.progress * 100).toInt()}% · ${UsbGadgetEngine.formatBytes(item.downloadedBytes)} / ${UsbGadgetEngine.formatBytes(item.totalBytes)}",
                                    style = PocketColors.monoStyle,
                                    color = PocketColors.warning
                                )
                            }
                            IconButton(
                                onClick = {
                                    FastIsoDownloader.discardPendingDownload(item)
                                    pendingResumables = FastIsoDownloader.scanPendingResumableDownloads(context)
                                },
                                modifier = Modifier.size(34.dp)
                            ) {
                                Icon(Icons.Rounded.DeleteOutline, "Discard", tint = PocketColors.danger, modifier = Modifier.size(18.dp))
                            }
                            Surface(
                                modifier = Modifier.bounceClick {
                                    FastIsoDownloader.resumeFromSidecar(
                                        context = context,
                                        pending = item,
                                        autoVerifySha256 = settings.autoVerifySha256,
                                        onComplete = { evt -> scope.launch { onDownloadCompleted(evt) } },
                                        onError = { err ->
                                            scope.launch {
                                                pendingResumables = FastIsoDownloader.scanPendingResumableDownloads(context)
                                                onMessage(err, true)
                                            }
                                        }
                                    )
                                },
                                shape = RoundedCornerShape(999.dp),
                                color = PocketColors.accent
                            ) {
                                Text("Resume", modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = PocketColors.onAccent)
                            }
                        }
                    }
                }
            }
        }

        // Active Windows Channel Build Card
        AnimatedVisibility(
            visible = winBuildState != null,
            enter = fadeIn(tween(220)) + expandVertically(spring(stiffness = Spring.StiffnessMediumLow)),
            exit = fadeOut(tween(180)) + shrinkVertically()
        ) {
            winBuildState?.let { job ->
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = PocketColors.surfaceCard,
                    border = BorderStroke(1.5.dp, PocketColors.infoBlue)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                Brush.verticalGradient(
                                    colors = listOf(PocketColors.infoBlueBg.copy(alpha = 0.55f), PocketColors.surfaceCard)
                                )
                            )
                            .padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text(job.title, style = MaterialTheme.typography.titleMedium, color = PocketColors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                                    Surface(
                                        shape = RoundedCornerShape(5.dp),
                                        color = when {
                                            job.isPaused -> PocketColors.warningBg
                                            job.isConverting -> PocketColors.successBg
                                            else -> PocketColors.warningBg
                                        }
                                    ) {
                                        Text(
                                            text = when {
                                                job.isPaused -> "PAUSED"
                                                job.isConverting -> "BUILD"
                                                else -> "⚡ ${job.activeThreads}x"
                                            },
                                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = when {
                                                job.isPaused -> PocketColors.warning
                                                job.isConverting -> PocketColors.success
                                                else -> PocketColors.warning
                                            }
                                        )
                                    }
                                }
                                Text(job.stage, style = MaterialTheme.typography.bodySmall, color = PocketColors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (!job.isConverting) {
                                    IconButton(
                                        onClick = {
                                            if (job.isPaused) WindowsBuildEngine.resumeBuild()
                                            else WindowsBuildEngine.pauseBuild()
                                        },
                                        modifier = Modifier.size(34.dp)
                                    ) {
                                        Icon(
                                            imageVector = if (job.isPaused) Icons.Rounded.PlayArrow else Icons.Rounded.Pause,
                                            contentDescription = if (job.isPaused) "Resume build" else "Pause build",
                                            tint = PocketColors.accentSoft,
                                            modifier = Modifier.size(19.dp)
                                        )
                                    }
                                }
                                IconButton(
                                    onClick = { showCancelWinBuildDialog = true },
                                    modifier = Modifier.size(34.dp)
                                ) {
                                    Icon(Icons.Rounded.Close, "Cancel", tint = PocketColors.danger, modifier = Modifier.size(18.dp))
                                }
                            }
                        }

                        LinearProgressIndicator(
                            progress = { job.progress.coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(999.dp)),
                            color = if (job.isConverting) PocketColors.success else PocketColors.infoBlue,
                            trackColor = PocketColors.surfaceSubtle
                        )
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(
                                text = "${(job.progress * 100).toInt()}% · ${job.detail}",
                                style = PocketColors.monoStyle,
                                color = if (job.isConverting) PocketColors.success else PocketColors.infoBlue,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = when {
                                    job.isPaused -> "Paused · Resumable"
                                    job.isConverting -> "Assembling"
                                    else -> "${FastIsoDownloader.formatSpeed(job.speedBytesPerSec)} · ${FastIsoDownloader.formatEta(job.etaSeconds)}"
                                },
                                style = PocketColors.monoStyle,
                                color = if (job.isPaused) PocketColors.warning else PocketColors.success,
                                maxLines = 1
                            )
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Auto-mount when finished", style = MaterialTheme.typography.bodySmall, color = PocketColors.textSecondary)
                            PocketSwitch(
                                checked = job.autoMount,
                                onCheckedChange = { WindowsBuildEngine.updateAutoMount(it) }
                            )
                        }
                    }
                }
            }
        }

        AnimatedVisibility(
            visible = turboState != null,
            enter = fadeIn(tween(220)) + expandVertically(spring(stiffness = Spring.StiffnessMediumLow)),
            exit = fadeOut(tween(180)) + shrinkVertically()
        ) {
            turboState?.let { dl ->
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = PocketColors.surfaceCard,
                    border = BorderStroke(1.5.dp, PocketColors.accent)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                Brush.verticalGradient(
                                    colors = listOf(PocketColors.accentBg.copy(alpha = 0.55f), PocketColors.surfaceCard)
                                )
                            )
                            .padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text(dl.title, style = MaterialTheme.typography.titleMedium, color = PocketColors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                                    Surface(shape = RoundedCornerShape(5.dp), color = PocketColors.warningBg) {
                                        Text("⚡ ${dl.activeThreads}x", modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp), style = MaterialTheme.typography.labelSmall, color = PocketColors.warning)
                                    }
                                }
                                Text(
                                    text = if (dl.targetMultiBootPath.isNotBlank()) "${dl.fileName} → ${dl.targetMultiBootPath.substringAfterLast('/')}" else dl.fileName,
                                    style = PocketColors.monoStyle,
                                    color = PocketColors.textSecondary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (dl.supportsRange && !dl.isVerifying && !dl.isInjectingMultiBoot) {
                                    IconButton(
                                        onClick = { if (dl.isPaused) FastIsoDownloader.resume() else FastIsoDownloader.pause() },
                                        modifier = Modifier.size(34.dp)
                                    ) {
                                        Icon(
                                            imageVector = if (dl.isPaused) Icons.Rounded.PlayArrow else Icons.Rounded.Pause,
                                            contentDescription = if (dl.isPaused) "Resume" else "Pause",
                                            tint = PocketColors.accentSoft,
                                            modifier = Modifier.size(19.dp)
                                        )
                                    }
                                }
                                IconButton(
                                    onClick = {
                                        FastIsoDownloader.cancel(deleteTemp = true)
                                        pendingResumables = FastIsoDownloader.scanPendingResumableDownloads(context)
                                        onMessage("Download cancelled.", false)
                                    },
                                    modifier = Modifier.size(34.dp)
                                ) {
                                    Icon(Icons.Rounded.Close, "Cancel", tint = PocketColors.danger, modifier = Modifier.size(18.dp))
                                }
                            }
                        }

                        if (dl.isVerifying || dl.isInjectingMultiBoot) {
                            LinearProgressIndicator(
                                modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(999.dp)),
                                color = if (dl.isVerifying) PocketColors.success else PocketColors.violet,
                                trackColor = PocketColors.surfaceSubtle
                            )
                            Text(dl.statusText, style = PocketColors.monoStyle, color = if (dl.isVerifying) PocketColors.success else PocketColors.violet)
                        } else if (dl.totalBytes > 0L) {
                            LinearProgressIndicator(
                                progress = { dl.progress },
                                modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(999.dp)),
                                color = PocketColors.accent,
                                trackColor = PocketColors.surfaceSubtle
                            )
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(
                                    text = "${(dl.progress * 100).toInt()}% · ${UsbGadgetEngine.formatBytes(dl.downloadedBytes)} / ${UsbGadgetEngine.formatBytes(dl.totalBytes)}",
                                    style = PocketColors.monoStyle,
                                    color = PocketColors.accentSoft
                                )
                                Text(
                                    text = if (dl.isPaused) "Paused · Resumable" else "${FastIsoDownloader.formatSpeed(dl.speedBytesPerSec)} · ${FastIsoDownloader.formatEta(dl.etaSeconds)}",
                                    style = PocketColors.monoStyle,
                                    color = PocketColors.success
                                )
                            }
                        } else {
                            LinearProgressIndicator(
                                modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(999.dp)),
                                color = PocketColors.accent,
                                trackColor = PocketColors.surfaceSubtle
                            )
                            Text("${UsbGadgetEngine.formatBytes(dl.downloadedBytes)} · ${FastIsoDownloader.formatSpeed(dl.speedBytesPerSec)}", style = PocketColors.monoStyle, color = PocketColors.accentSoft)
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Auto-mount when finished", style = MaterialTheme.typography.bodySmall, color = PocketColors.textSecondary)
                            PocketSwitch(
                                checked = autoMount,
                                onCheckedChange = {
                                    autoMount = it
                                    FastIsoDownloader.updateAutoMount(it)
                                }
                            )
                        }
                    }
                }
            }
        }

        Column(
            modifier = Modifier.animateContentSize(spring(stiffness = Spring.StiffnessMediumLow)),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            PocketSectionHeader(title = "OS Catalog", trailing = "Long-press to copy link")

            OutlinedTextField(
                value = catalogSearch,
                onValueChange = { catalogSearch = it },
                placeholder = { Text("Search OS catalog (Windows, Ubuntu, Kali, Rescue)...", fontSize = 12.5.sp, color = PocketColors.textMuted) },
                leadingIcon = { Icon(Icons.Rounded.Search, null, tint = PocketColors.accentSoft, modifier = Modifier.size(18.dp)) },
                trailingIcon = if (catalogSearch.isNotEmpty()) {
                    {
                        IconButton(onClick = { catalogSearch = "" }) {
                            Icon(Icons.Rounded.Close, "Clear search", tint = PocketColors.textSecondary, modifier = Modifier.size(16.dp))
                        }
                    }
                } else null,
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = PocketColors.accent,
                    unfocusedBorderColor = PocketColors.border,
                    focusedContainerColor = PocketColors.surfaceCard,
                    unfocusedContainerColor = PocketColors.surfaceCard,
                    focusedTextColor = PocketColors.textPrimary,
                    unfocusedTextColor = PocketColors.textPrimary
                )
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(7.dp)
            ) {
                dynamicCategories.forEach { cat ->
                    val selected = selectedCategory.equals(cat, ignoreCase = true)
                    val chipBg by animateColorAsState(if (selected) PocketColors.accentBg else PocketColors.surfaceCard, tween(180), label = "catBg")
                    val chipBorder by animateColorAsState(if (selected) PocketColors.accent else PocketColors.border, tween(180), label = "catBorder")
                    val chipText by animateColorAsState(if (selected) PocketColors.accentSoft else PocketColors.textSecondary, tween(180), label = "catText")
                    Surface(
                        modifier = Modifier.bounceClick { selectedCategory = cat },
                        shape = RoundedCornerShape(999.dp),
                        color = chipBg,
                        border = BorderStroke(if (selected) 1.dp else 0.75.dp, chipBorder)
                    ) {
                        Text(
                            text = cat,
                            modifier = Modifier.padding(horizontal = 13.dp, vertical = 6.dp),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                            color = chipText
                        )
                    }
                }
            }

            val q = catalogSearch.trim().lowercase()
            val matchedWinFamilies = remember(q) {
                if (q.isEmpty()) WindowsCatalogFamily.entries.toList()
                else WindowsCatalogFamily.entries.filter {
                    it.title.lowercase().contains(q) || it.subtitle.lowercase().contains(q) || "windows".contains(q)
                }
            }
            val matchedNonWin = remember(nonWinDistros, q) {
                if (q.isEmpty()) nonWinDistros
                else nonWinDistros.filter { iso ->
                    iso.title.lowercase().contains(q) ||
                        iso.subtitle.lowercase().contains(q) ||
                        iso.category.lowercase().contains(q) ||
                        iso.effectiveVersions.any { it.label.lowercase().contains(q) }
                }
            }

            if (selectedCategory == "All") {
                if (matchedWinFamilies.isEmpty() && matchedNonWin.isEmpty()) {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                        color = PocketColors.surfaceCard,
                        border = BorderStroke(0.5.dp, PocketColors.border)
                    ) {
                        Text(
                            text = "No OS images matched \"$catalogSearch\".",
                            modifier = Modifier.padding(18.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = PocketColors.textSecondary
                        )
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
                        if (matchedWinFamilies.isNotEmpty()) {
                            Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                                CatalogGroupHeader(
                                    icon = Icons.Rounded.Window,
                                    title = "Windows",
                                    count = matchedWinFamilies.size,
                                    tint = PocketColors.infoBlue,
                                    bg = PocketColors.infoBlueBg
                                )
                                matchedWinFamilies.forEach { fam ->
                                    WindowsFamilyCard(
                                        fam = fam,
                                        onClick = { selectedWindowsFamily = fam },
                                        onLongClick = { longPressedWindows = fam }
                                    )
                                }
                            }
                        }

                        val grouped = matchedNonWin.groupBy { it.category }
                        grouped.forEach { (catName, itemsInCat) ->
                            val (secIcon, secTint, secBg, secLabel) = when (catName.lowercase()) {
                                "linux" -> listOf(Icons.Rounded.Terminal, PocketColors.success, PocketColors.successBg, "Linux Desktop")
                                "server" -> listOf(Icons.Rounded.Dns, PocketColors.violet, PocketColors.violetBg, "Server & Homelab")
                                "security" -> listOf(Icons.Rounded.Security, PocketColors.danger, PocketColors.dangerBg, "Security & Pentest")
                                "rescue" -> listOf(Icons.Rounded.BuildCircle, PocketColors.warning, PocketColors.warningBg, "Rescue & Repair")
                                "lightweight" -> listOf(Icons.Rounded.Memory, PocketColors.infoBlue, PocketColors.infoBlueBg, "Lightweight & Netboot")
                                else -> listOf(Icons.Rounded.Album, PocketColors.accentSoft, PocketColors.accentBg, catName)
                            }
                            Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                                CatalogGroupHeader(
                                    icon = secIcon as ImageVector,
                                    title = secLabel as String,
                                    count = itemsInCat.size,
                                    tint = secTint as Color,
                                    bg = secBg as Color
                                )
                                itemsInCat.forEach { iso ->
                                    DistroCatalogCard(
                                        iso = iso,
                                        existingFileNames = existingFileNames,
                                        onClick = { selectedDistroForVersion = iso },
                                        onLongClick = { longPressedDistro = iso }
                                    )
                                }
                            }
                        }
                    }
                }
            } else if (selectedCategory.equals("Windows", true)) {
                Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    matchedWinFamilies.forEach { fam ->
                        WindowsFamilyCard(
                            fam = fam,
                            onClick = { selectedWindowsFamily = fam },
                            onLongClick = { longPressedWindows = fam }
                        )
                    }
                }
            } else {
                val filtered = matchedNonWin.filter { it.category.equals(selectedCategory, true) }
                Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    filtered.forEach { iso ->
                        DistroCatalogCard(
                            iso = iso,
                            existingFileNames = existingFileNames,
                            onClick = { selectedDistroForVersion = iso },
                            onLongClick = { longPressedDistro = iso }
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
    }

    selectedWindowsFamily?.let { fam ->
        WindowsDownloadSheet(
            family = fam,
            targetDir = defaultDir,
            multiBootDrives = multiBootDrives,
            activeMultiBootTarget = activeMultiBootTarget,
            turboConnections = settings.downloadConnections,
            onDismiss = { selectedWindowsFamily = null },
            onStartDirectDownload = { title, url, fileName, sha ->
                startTurboDownload(title, url, fileName, expectedSha256 = sha)
            },
            onStartChannelBuild = { cfg ->
                WindowsBuildEngine.startBuild(
                    context = context,
                    config = cfg,
                    onComplete = { evt -> scope.launch { onDownloadCompleted(evt) } },
                    onError = { err -> scope.launch { onMessage(err, true) } },
                    onLog = { entry -> scope.launch { onAppendLog(entry) } }
                )
            }
        )
    }

    selectedDistroForVersion?.let { iso ->
        ModalBottomSheet(
            onDismissRequest = { selectedDistroForVersion = null },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = PocketColors.surfaceCard
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(iso.title, style = MaterialTheme.typography.titleLarge, color = PocketColors.textPrimary)
                    Text("Choose a version to download or copy the direct link", style = MaterialTheme.typography.bodySmall, color = PocketColors.textSecondary)
                }

                iso.effectiveVersions.forEach { ver ->
                    val saved = existingFileNames.contains(ver.fileName.lowercase())
                    Surface(
                        shape = RoundedCornerShape(13.dp),
                        color = PocketColors.surfaceInput,
                        border = BorderStroke(0.75.dp, if (saved) PocketColors.success else PocketColors.border)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 13.dp, vertical = 11.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(ver.label, style = MaterialTheme.typography.labelLarge, color = PocketColors.textPrimary)
                                val meta = buildList {
                                    if (ver.date.isNotBlank()) add(ver.date)
                                    add(ver.sizeLabel)
                                }.joinToString(" · ")
                                Text(meta, style = PocketColors.monoStyle, color = PocketColors.textSecondary)
                                Text(ver.fileName, style = PocketColors.monoStyle, color = PocketColors.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            IconButton(
                                onClick = { copyLinkToClipboard("${iso.title} ${ver.label}", ver.url) },
                                modifier = Modifier.size(36.dp)
                            ) {
                                Icon(Icons.Rounded.ContentCopy, "Copy link", tint = PocketColors.accentSoft, modifier = Modifier.size(18.dp))
                            }
                            if (saved) {
                                Surface(shape = RoundedCornerShape(8.dp), color = PocketColors.successBg) {
                                    Text("Saved", modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp), style = MaterialTheme.typography.labelSmall, color = PocketColors.success)
                                }
                            } else {
                                Surface(
                                    modifier = Modifier.bounceClick(enabled = !anyDownloadActive) {
                                        selectedDistroForVersion = null
                                        startTurboDownload("${iso.title} (${ver.label})", ver.url, ver.fileName, ver.approxBytes, ver.sha256)
                                    },
                                    shape = RoundedCornerShape(9.dp),
                                    color = if (!anyDownloadActive) PocketColors.accent else PocketColors.surfaceSubtle
                                ) {
                                    Text(
                                        text = "⚡ Get",
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        color = if (!anyDownloadActive) PocketColors.onAccent else PocketColors.textMuted
                                    )
                                }
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(22.dp))
            }
        }
    }

    longPressedDistro?.let { iso ->
        val primary = iso.effectiveVersions.first()
        AlertDialog(
            onDismissRequest = { longPressedDistro = null },
            containerColor = PocketColors.surfaceCard,
            title = { Text(iso.title, color = PocketColors.textPrimary) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(primary.label, style = MaterialTheme.typography.labelMedium, color = PocketColors.accentSoft)
                    Text(primary.url, style = PocketColors.monoStyle, color = PocketColors.textMuted, maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        copyLinkToClipboard(iso.title, primary.url)
                        longPressedDistro = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = PocketColors.accent, contentColor = PocketColors.onAccent)
                ) {
                    Icon(Icons.Rounded.ContentCopy, null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Copy Link", fontWeight = FontWeight.SemiBold)
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    longPressedDistro = null
                    selectedDistroForVersion = iso
                }) {
                    Text("All Versions", color = PocketColors.textSecondary)
                }
            }
        )
    }

    longPressedWindows?.let { fam ->
        var fetchingLink by remember(fam.id) { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { if (!fetchingLink) longPressedWindows = null },
            containerColor = PocketColors.surfaceCard,
            title = { Text(fam.title, color = PocketColors.textPrimary) },
            text = {
                Text(
                    text = if (fam.supportsDirectRetail) {
                        "Copy the latest English (x64) official Microsoft CDN link for external download managers, or open the selector to choose another version, language, or channel."
                    } else {
                        "Open All Channels to browse Public, Release Preview, Beta, Dev, Canary, and Server builds."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = PocketColors.textSecondary
                )
            },
            confirmButton = {
                if (fam.supportsDirectRetail) {
                    Button(
                        onClick = {
                            scope.launch {
                                fetchingLink = true
                                var copiedUrl: String? = null
                                val rel = WindowsDirectCatalog.fetchReleases(fam).let { list -> list.firstOrNull { !it.isArm64Only } ?: list.firstOrNull() }
                                if (rel != null) {
                                    val skuRes = WindowsDirectCatalog.fetchLanguages(context, rel)
                                    val data = skuRes.data
                                    if (data != null) {
                                        val enSku = data.second.firstOrNull { it.localizedLanguage.contains("English (United States)", true) }
                                            ?: data.second.firstOrNull()
                                        if (enSku != null) {
                                            val dlRes = WindowsDirectCatalog.fetchDownloadLinks(context, rel, data.first, enSku.skuId)
                                            copiedUrl = (dlRes.data?.firstOrNull { it.arch.equals("x64", true) } ?: dlRes.data?.firstOrNull())?.url
                                        }
                                    }
                                }
                                fetchingLink = false
                                longPressedWindows = null
                                if (copiedUrl != null) {
                                    copyLinkToClipboard("${fam.title} (English x64)", copiedUrl)
                                } else {
                                    onMessage("Direct link unavailable on this IP. Open selector and switch to All Channels.", true)
                                }
                            }
                        },
                        enabled = !fetchingLink,
                        colors = ButtonDefaults.buttonColors(containerColor = PocketColors.accent, contentColor = PocketColors.onAccent)
                    ) {
                        if (fetchingLink) {
                            CircularProgressIndicator(modifier = Modifier.size(15.dp), strokeWidth = 2.dp, color = PocketColors.onAccent)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Signing...")
                        } else {
                            Icon(Icons.Rounded.ContentCopy, null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Copy Direct Link", fontWeight = FontWeight.SemiBold)
                        }
                    }
                } else {
                    Button(
                        onClick = {
                            longPressedWindows = null
                            selectedWindowsFamily = fam
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = PocketColors.accent, contentColor = PocketColors.onAccent)
                    ) {
                        Text("Browse Channels", fontWeight = FontWeight.SemiBold)
                    }
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        longPressedWindows = null
                        selectedWindowsFamily = fam
                    },
                    enabled = !fetchingLink
                ) {
                    Text("Open Selector", color = PocketColors.textSecondary)
                }
            }
        )
    }

    if (showCancelWinBuildDialog) {
        AlertDialog(
            onDismissRequest = { showCancelWinBuildDialog = false },
            containerColor = PocketColors.surfaceCard,
            title = { Text("Cancel Windows Build?", color = PocketColors.textPrimary) },
            text = {
                Text(
                    "Keep downloaded packages in .pocketboot_tmp so you can resume this build later (auto-cleaned after 24h), or discard all temporary files immediately.",
                    style = MaterialTheme.typography.bodySmall,
                    color = PocketColors.textSecondary
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showCancelWinBuildDialog = false
                        WindowsBuildEngine.cancelBuild(discardTemp = true)
                        onMessage("Cancelled build and cleaned temporary files.", false)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = PocketColors.danger, contentColor = Color.White)
                ) {
                    Text("Discard Temp", fontWeight = FontWeight.SemiBold)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showCancelWinBuildDialog = false
                        WindowsBuildEngine.cancelBuild(discardTemp = false)
                        onMessage("Cancelled build (verified packages kept for 24h resume).", false)
                    }
                ) {
                    Text("Keep for Resume", color = PocketColors.accentSoft)
                }
            }
        )
    }

    // Turbo Speed & Auto-Verify Settings Dialog
    if (showSpeedSettingsDialog) {
        AlertDialog(
            onDismissRequest = { showSpeedSettingsDialog = false },
            containerColor = PocketColors.surfaceCard,
            title = { Text("Turbo Downloader Settings", color = PocketColors.textPrimary) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = "Runs in a dedicated background Foreground Service with automatic pause/resume across restarts and single-stream fallback.",
                        style = MaterialTheme.typography.bodySmall,
                        color = PocketColors.textSecondary
                    )

                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Parallel Connections", style = MaterialTheme.typography.labelLarge, color = PocketColors.textPrimary)
                            Text(if (settings.downloadConnections == 8) "8x (Recommended)" else "${settings.downloadConnections}x", style = PocketColors.monoStyle, color = PocketColors.accentSoft)
                        }
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                            listOf(4, 8, 12, 16).forEach { n ->
                                val isSel = settings.downloadConnections == n
                                Surface(
                                    modifier = Modifier.weight(1f).bounceClick { onUpdateSettings(settings.copy(downloadConnections = n)) },
                                    shape = RoundedCornerShape(9.dp),
                                    color = if (isSel) PocketColors.accentBg else PocketColors.surfaceInput,
                                    border = BorderStroke(if (isSel) 1.5.dp else 0.5.dp, if (isSel) PocketColors.accent else PocketColors.border)
                                ) {
                                    Column(modifier = Modifier.padding(vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                        Text("${n}x", fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = if (isSel) PocketColors.accentSoft else PocketColors.textPrimary)
                                        if (n == 8) Text("Rec", fontSize = 9.5.sp, color = PocketColors.success)
                                    }
                                }
                            }
                        }
                    }

                    PocketHairlineDivider()

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Auto-Verify SHA-256", style = MaterialTheme.typography.labelLarge, color = PocketColors.textPrimary)
                            Text("Check integrity right after download finishes", style = MaterialTheme.typography.bodySmall, color = PocketColors.textSecondary)
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        PocketSwitch(
                            checked = settings.autoVerifySha256,
                            onCheckedChange = { onUpdateSettings(settings.copy(autoVerifySha256 = it)) },
                            activeColor = PocketColors.success
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showSpeedSettingsDialog = false }) {
                    Text("Done", color = PocketColors.accentSoft)
                }
            }
        )
    }

    if (showAddFolderDialog) {
        var newPath by remember { mutableStateOf("/sdcard/ISOs") }
        AlertDialog(
            onDismissRequest = { showAddFolderDialog = false },
            containerColor = PocketColors.surfaceCard,
            title = { Text("Add Download Folder", color = PocketColors.textPrimary) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Enter a storage path. PocketBoot will create it if needed and set it as your active save folder.", style = MaterialTheme.typography.bodySmall, color = PocketColors.textSecondary)
                    OutlinedTextField(value = newPath, onValueChange = { newPath = it }, label = { Text("Folder path") }, placeholder = { Text("/sdcard/ISOs") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val resolved = UsbGadgetEngine.resolveDirectPath(newPath)
                        if (resolved.isNotBlank()) {
                            scope.launch {
                                UsbGadgetEngine.ensureDirectoryExists(resolved, context)
                                val updatedDirs = (listOf(resolved) + settings.scanDirs.filter { it != resolved }).distinct()
                                onUpdateSettings(settings.copy(defaultDir = resolved, scanDirs = updatedDirs, downloadTargetMultiBootPath = ""))
                                onMessage("Added and selected ${UsbGadgetEngine.friendlyFolderName(resolved)}.", false)
                            }
                            showAddFolderDialog = false
                        }
                    },
                    enabled = newPath.trim().isNotEmpty(),
                    colors = ButtonDefaults.buttonColors(containerColor = PocketColors.accent, contentColor = PocketColors.onAccent)
                ) {
                    Text("Add & Select", fontWeight = FontWeight.SemiBold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddFolderDialog = false }) { Text("Cancel", color = PocketColors.textSecondary) }
            }
        )
    }

    if (showReposDialog) {
        var newRepoUrl by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showReposDialog = false },
            containerColor = PocketColors.surfaceCard,
            title = { Text("Catalog Repositories", color = PocketColors.textPrimary) },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Official PocketBoot Catalog", style = MaterialTheme.typography.labelLarge, color = PocketColors.textPrimary)
                            Text("Curated multi-version Linux & rescue ISOs", style = MaterialTheme.typography.bodySmall, color = PocketColors.textSecondary)
                        }
                        PocketSwitch(checked = settings.useDefaultDistroRepo, onCheckedChange = { onUpdateSettings(settings.copy(useDefaultDistroRepo = it)) })
                    }

                    PocketHairlineDivider()

                    if (settings.customRepoUrls.isNotEmpty()) {
                        PocketSectionHeader("Custom Repos")
                        settings.customRepoUrls.forEach { repo ->
                            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(repo, style = PocketColors.monoStyle, color = PocketColors.accentSoft, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                IconButton(onClick = { onUpdateSettings(settings.copy(customRepoUrls = settings.customRepoUrls - repo)) }) {
                                    Icon(Icons.Rounded.DeleteOutline, "Remove repo", tint = PocketColors.danger, modifier = Modifier.size(18.dp))
                                }
                            }
                        }
                        PocketHairlineDivider()
                    }

                    Text("Add JSON Repo URL", style = MaterialTheme.typography.labelLarge, color = PocketColors.textPrimary)
                    OutlinedTextField(value = newRepoUrl, onValueChange = { newRepoUrl = it }, placeholder = { Text("https://.../distros.json") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Button(
                        onClick = {
                            val clean = newRepoUrl.trim()
                            if (clean.startsWith("http://") || clean.startsWith("https://")) {
                                onUpdateSettings(settings.copy(customRepoUrls = (settings.customRepoUrls + clean).distinct()))
                                newRepoUrl = ""
                            } else {
                                onMessage("Repo URL must start with http:// or https://", true)
                            }
                        },
                        enabled = newRepoUrl.isNotBlank(),
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = PocketColors.accent, contentColor = PocketColors.onAccent)
                    ) {
                        Icon(Icons.Rounded.Add, null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Add Repository", fontWeight = FontWeight.SemiBold)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showReposDialog = false }) { Text("Done", color = PocketColors.accentSoft) }
            }
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun WindowsFamilyCard(
    fam: WindowsCatalogFamily,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val isCustomCard = fam == WindowsCatalogFamily.ALL_BUILDS
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        shape = RoundedCornerShape(14.dp),
        color = PocketColors.surfaceCard,
        border = BorderStroke(1.dp, PocketColors.border)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier.size(42.dp).clip(RoundedCornerShape(11.dp)).background(if (isCustomCard) PocketColors.violetBg else PocketColors.infoBlueBg),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (isCustomCard) Icons.Rounded.Build else Icons.Rounded.Window,
                    contentDescription = null,
                    tint = if (isCustomCard) PocketColors.violet else PocketColors.infoBlue,
                    modifier = Modifier.size(21.dp)
                )
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(fam.title, style = MaterialTheme.typography.labelLarge, color = PocketColors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(fam.subtitle, style = MaterialTheme.typography.bodySmall, color = PocketColors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.padding(top = 2.dp)
                ) {
                    Surface(shape = RoundedCornerShape(5.dp), color = if (isCustomCard) PocketColors.violetBg else PocketColors.infoBlueBg) {
                        Text(
                            text = fam.badgeLabel,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = if (isCustomCard) PocketColors.violet else PocketColors.infoBlue
                        )
                    }
                    Text(fam.sizeHint, style = PocketColors.monoStyle, color = PocketColors.textMuted)
                }
            }
            Surface(
                shape = RoundedCornerShape(999.dp),
                color = PocketColors.infoBlueBg,
                border = BorderStroke(0.75.dp, PocketColors.infoBlue)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 11.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    Text("Select", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = PocketColors.infoBlue)
                    Icon(Icons.Rounded.ChevronRight, null, tint = PocketColors.infoBlue, modifier = Modifier.size(14.dp))
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DistroCatalogCard(
    iso: CuratedIso,
    existingFileNames: Set<String>,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val versions = iso.effectiveVersions
    val anyInLibrary = versions.any { existingFileNames.contains(it.fileName.lowercase()) }
    val (icon, tint, iconBg) = when (iso.category.lowercase()) {
        "linux" -> Triple(Icons.Rounded.Terminal, PocketColors.success, PocketColors.successBg)
        "server" -> Triple(Icons.Rounded.Dns, PocketColors.violet, PocketColors.violetBg)
        "security" -> Triple(Icons.Rounded.Security, PocketColors.danger, PocketColors.dangerBg)
        "rescue" -> Triple(Icons.Rounded.BuildCircle, PocketColors.warning, PocketColors.warningBg)
        "lightweight", "utility" -> Triple(Icons.Rounded.Memory, PocketColors.infoBlue, PocketColors.infoBlueBg)
        else -> Triple(Icons.Rounded.Album, PocketColors.accentSoft, PocketColors.accentBg)
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        shape = RoundedCornerShape(14.dp),
        color = PocketColors.surfaceCard,
        border = BorderStroke(1.dp, if (anyInLibrary) PocketColors.success.copy(alpha = 0.6f) else PocketColors.border)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier.size(42.dp).clip(RoundedCornerShape(11.dp)).background(if (anyInLibrary) PocketColors.successBg else iconBg),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, contentDescription = null, tint = if (anyInLibrary) PocketColors.success else tint, modifier = Modifier.size(21.dp))
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(iso.title, style = MaterialTheme.typography.labelLarge, color = PocketColors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(iso.subtitle, style = MaterialTheme.typography.bodySmall, color = PocketColors.textSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.padding(top = 2.dp)
                ) {
                    Surface(shape = RoundedCornerShape(5.dp), color = iconBg) {
                        Text(iso.category, modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp), style = MaterialTheme.typography.labelSmall, color = tint)
                    }
                    val verNote = if (versions.size > 1) "${versions.size} versions · ${iso.sizeLabel}" else iso.sizeLabel
                    Text(verNote, style = PocketColors.monoStyle, color = PocketColors.textMuted)
                }
            }
            if (anyInLibrary && versions.size == 1) {
                Surface(
                    shape = RoundedCornerShape(999.dp),
                    color = PocketColors.successBg,
                    border = BorderStroke(0.75.dp, PocketColors.success)
                ) {
                    Row(modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Icon(Icons.Rounded.CheckCircle, null, tint = PocketColors.success, modifier = Modifier.size(13.dp))
                        Text("Saved", style = MaterialTheme.typography.labelSmall, color = PocketColors.success)
                    }
                }
            } else {
                Surface(
                    modifier = Modifier.bounceClick(onClick = onClick),
                    shape = RoundedCornerShape(999.dp),
                    color = PocketColors.accent
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(3.dp)
                    ) {
                        Text(
                            text = if (versions.size > 1) "Versions" else "Get",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = PocketColors.onAccent
                        )
                        if (versions.size > 1) {
                            Icon(Icons.Rounded.ChevronRight, null, tint = PocketColors.onAccent, modifier = Modifier.size(14.dp))
                        }
                    }
                }
            }
        }
    }
}
