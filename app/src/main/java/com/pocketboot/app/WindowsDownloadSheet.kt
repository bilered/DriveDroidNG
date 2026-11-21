package com.pocketboot.app

import android.content.ClipData
import android.content.ClipboardManager
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.BatteryAlert
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckBox
import androidx.compose.material.icons.rounded.CheckBoxOutlineBlank
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.SystemUpdateAlt
import androidx.compose.material.icons.rounded.ListAlt
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.FolderCopy
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Tag
import androidx.compose.material.icons.rounded.Tune
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

enum class WindowsDownloadTab(val label: String, val badge: String?) {
    DIRECT_ISO("Direct ISO", "Fast"),
    ALL_CHANNELS("All Channels", null)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WindowsDownloadSheet(
    family: WindowsCatalogFamily,
    targetDir: String,
    multiBootDrives: List<ImageFile>,
    activeMultiBootTarget: ImageFile?,
    turboConnections: Int,
    presets: List<AutounattendPreset> = emptyList(),
    onSavePreset: (AutounattendPreset) -> Unit = {},
    onDeletePreset: (String) -> Unit = {},
    onDismiss: () -> Unit,
    onStartDirectDownload: (title: String, url: String, fileName: String, expectedSha256: String) -> Unit,
    onStartChannelBuild: (WindowsChannelBuildConfig) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val availableTabs = remember(family.id) {
        if (family.supportsDirectRetail) {
            listOf(WindowsDownloadTab.DIRECT_ISO, WindowsDownloadTab.ALL_CHANNELS)
        } else {
            listOf(WindowsDownloadTab.ALL_CHANNELS)
        }
    }
    var activeTab by remember(family.id) {
        mutableStateOf(
            if (family.supportsDirectRetail) WindowsDownloadTab.DIRECT_ISO else WindowsDownloadTab.ALL_CHANNELS
        )
    }

    // ==================== DIRECT RETAIL ISO STATE ====================
    var directLoadingReleases by remember(family.id) { mutableStateOf(false) }
    var directReleaseGroups by remember(family.id) { mutableStateOf<List<DirectReleaseGroup>>(emptyList()) }
    var selectedDirectGroup by remember(family.id) { mutableStateOf<DirectReleaseGroup?>(null) }
    var directWantArm64 by remember(family.id) { mutableStateOf(false) }
    var directWin10Arch by remember(family.id) { mutableStateOf("x64") }
    var directSessionId by remember(family.id) { mutableStateOf("") }
    var directSkus by remember(family.id) { mutableStateOf<List<DirectLanguageSku>>(emptyList()) }
    var directSkuLoading by remember(family.id) { mutableStateOf(false) }
    var selectedDirectSku by remember(family.id) { mutableStateOf<DirectLanguageSku?>(null) }
    var directLinks by remember(family.id) { mutableStateOf<List<DirectIsoLink>>(emptyList()) }
    var directLinkLoading by remember(family.id) { mutableStateOf(false) }
    var selectedDirectLink by remember(family.id) { mutableStateOf<DirectIsoLink?>(null) }
    var directError by remember(family.id) { mutableStateOf<String?>(null) }

    val activeDirectRelease: DirectWindowsRelease? = remember(selectedDirectGroup, directWantArm64) {
        val grp = selectedDirectGroup ?: return@remember null
        if (directWantArm64 && grp.arm64Release != null) grp.arm64Release else grp.defaultRelease
    }

    // ==================== HIERARCHICAL ALL CHANNELS STATE ====================
    var selectedWinFamily by remember(family.id) { mutableStateOf(family.defaultBuilderFamily) }
    val featureVersionsForFamily = remember(selectedWinFamily) { WinFeatureVersion.forFamily(selectedWinFamily) }
    var selectedFeatureVer by remember(family.id, selectedWinFamily) {
        mutableStateOf(featureVersionsForFamily.first())
    }
    var archFilter by remember(family.id) { mutableStateOf("amd64") }
    var customSearchQuery by remember(family.id) { mutableStateOf("") }
    var buildsLoading by remember(family.id) { mutableStateOf(false) }
    var groupedBuilds by remember(family.id) { mutableStateOf(GroupedWindowsBuilds()) }
    var selectedBuild by remember(family.id) { mutableStateOf<WindowsChannelBuild?>(null) }

    // Step 2: Build Config State
    var buildLangs by remember(family.id) { mutableStateOf<List<WinBuildLangOption>>(emptyList()) }
    var buildLangLoading by remember(family.id) { mutableStateOf(false) }
    var selectedBuildLang by remember(family.id) { mutableStateOf<WinBuildLangOption?>(null) }
    var buildEditions by remember(family.id) { mutableStateOf<List<WinBuildEditionOption>>(emptyList()) }
    var buildEdLoading by remember(family.id) { mutableStateOf(false) }
    val selectedBuildEditions = remember(family.id) { mutableStateListOf<String>() }
    var detailedManifest by remember(family.id) { mutableStateOf<WinDetailedManifest?>(null) }
    var estimateLoading by remember(family.id) { mutableStateOf(false) }
    var showUpdatesInspector by remember { mutableStateOf(false) }
    var showFullFilesInspector by remember { mutableStateOf(false) }

    var downloadMethod by remember { mutableStateOf(WinDownloadMethod.DOWNLOAD_AND_BUILD) }
    var buildCompression by remember { mutableStateOf(WinIsoCompression.WIM) }
    var buildOutputMode by remember(activeMultiBootTarget?.path) {
        mutableStateOf(if (activeMultiBootTarget != null) WinBuildOutputMode.DIRECT_MULTIBOOT else WinBuildOutputMode.STANDARD_ISO)
    }
    var customOutputName by remember { mutableStateOf("") }

    var includeUpdates by remember { mutableStateOf(false) }
    var includeStoreApps by remember { mutableStateOf(false) }
    var integrateNetFx3 by remember { mutableStateOf(false) }
    var runComponentCleanup by remember { mutableStateOf(false) }
    var skipWinRe by remember { mutableStateOf(false) }
    var eiCfgUnlock by remember { mutableStateOf(false) }
    var keepPackagesAfterBuild by remember { mutableStateOf(false) }
    var convOptionsExpanded by remember { mutableStateOf(true) }

    var usbScheme by remember { mutableStateOf(PartitionScheme.GPT) }
    var experience by remember { mutableStateOf(WindowsExperienceConfig()) }
    var winUtil by remember { mutableStateOf(WinUtilTweaksConfig()) }
    var usbExtraDriversDir by remember { mutableStateOf("") }
    var showUsbDriversDialog by remember { mutableStateOf(false) }

    val buildTools = remember { WindowsBuildEngine.inspectTools(context) }
    val freeBytes = remember(targetDir) { UsbGadgetEngine.getFreeSpaceBytes(targetDir) }
    val (batteryPct, isCharging) = remember { WindowsBuildEngine.readBatteryPercent(context) }

    // Shared Language Dropdown state
    var langMenuOpen by remember { mutableStateOf(false) }
    var langSearch by remember { mutableStateOf("") }

    fun loadDirectReleases(force: Boolean) {
        scope.launch {
            directLoadingReleases = true
            directError = null
            val raw = WindowsDirectCatalog.fetchReleases(family, forceRefresh = force)
            val groups = WindowsDirectCatalog.groupReleases(raw)
            directReleaseGroups = groups
            selectedDirectGroup = groups.firstOrNull()
            directWantArm64 = false
            directLoadingReleases = false
        }
    }

    fun loadGroupedBuilds(
        fam: WinFamilyFilter = selectedWinFamily,
        ver: WinFeatureVersion = selectedFeatureVer,
        arch: String = archFilter,
        query: String = customSearchQuery
    ) {
        scope.launch {
            buildsLoading = true
            groupedBuilds = WindowsBuildEngine.fetchGroupedBuilds(fam, ver, arch, query)
            buildsLoading = false
        }
    }

    LaunchedEffect(family.id, activeTab) {
        when (activeTab) {
            WindowsDownloadTab.DIRECT_ISO -> if (directReleaseGroups.isEmpty()) loadDirectReleases(force = false)
            WindowsDownloadTab.ALL_CHANNELS -> if (groupedBuilds.isEmpty && selectedBuild == null) loadGroupedBuilds()
        }
    }

    // Direct ISO: fetch languages when active Edition ID changes
    LaunchedEffect(activeDirectRelease?.editionId) {
        val rel = activeDirectRelease ?: return@LaunchedEffect
        directSkuLoading = true
        directError = null
        directLinks = emptyList()
        selectedDirectLink = null
        val res = WindowsDirectCatalog.fetchLanguages(context, rel)
        if (res.data != null) {
            directSessionId = res.data.first
            directSkus = res.data.second
            selectedDirectSku = directSkus.firstOrNull { it.localizedLanguage.contains("English (United States)", true) }
                ?: directSkus.firstOrNull()
        } else {
            directSkus = emptyList()
            selectedDirectSku = null
            directError = res.errorMessage
        }
        directSkuLoading = false
    }

    // Direct ISO: fetch signed CDN links when language changes
    LaunchedEffect(activeDirectRelease?.editionId, selectedDirectSku?.skuId) {
        val rel = activeDirectRelease ?: return@LaunchedEffect
        val sku = selectedDirectSku ?: return@LaunchedEffect
        if (directSessionId.isBlank()) return@LaunchedEffect
        directLinkLoading = true
        directError = null
        val res = WindowsDirectCatalog.fetchDownloadLinks(context, rel, directSessionId, sku.skuId)
        if (res.data != null) {
            directLinks = res.data
            selectedDirectLink = res.data.firstOrNull { it.arch.equals(directWin10Arch, true) } ?: res.data.firstOrNull()
        } else {
            directLinks = emptyList()
            selectedDirectLink = null
            directError = res.errorMessage
        }
        directLinkLoading = false
    }

    LaunchedEffect(directWin10Arch, directLinks) {
        if (directLinks.size > 1) {
            selectedDirectLink = directLinks.firstOrNull { it.arch.equals(directWin10Arch, true) } ?: directLinks.firstOrNull()
        }
    }

    // All Channels Step 2: fetch languages when a build is selected
    LaunchedEffect(selectedBuild?.uuid) {
        val b = selectedBuild ?: return@LaunchedEffect
        buildLangLoading = true
        detailedManifest = null
        val detOs = WindowsCustomizationEngine.detectWindowsOsKind(b.title)
        experience = WindowsExperienceConfig.defaultForOs(detOs)
        usbScheme = if (detOs == WindowsOsKind.WIN7 || b.arch.equals("x86", true)) PartitionScheme.MBR else PartitionScheme.GPT
        buildLangs = WindowsBuildEngine.fetchLanguages(b.uuid)
        selectedBuildLang = buildLangs.firstOrNull { it.code.equals("en-us", true) } ?: buildLangs.firstOrNull()
        buildLangLoading = false
    }

    // All Channels Step 2: fetch editions when language changes
    LaunchedEffect(selectedBuild?.uuid, selectedBuildLang?.code) {
        val b = selectedBuild ?: return@LaunchedEffect
        val l = selectedBuildLang ?: return@LaunchedEffect
        buildEdLoading = true
        buildEditions = WindowsBuildEngine.fetchEditions(b.uuid, l.code)
        selectedBuildEditions.clear()
        val defEd = buildEditions.firstOrNull { it.code.equals("PROFESSIONAL", true) }
            ?: buildEditions.firstOrNull()
        if (defEd != null) selectedBuildEditions.add(defEd.code)
        buildEdLoading = false
    }

    // All Channels Step 2: update default output filename & fetch estimated package size
    val edSelectionKey = selectedBuildEditions.sorted().joinToString(",")
    LaunchedEffect(selectedBuild?.uuid, selectedBuildLang?.code, edSelectionKey, buildOutputMode) {
        val b = selectedBuild ?: return@LaunchedEffect
        val l = selectedBuildLang ?: return@LaunchedEffect
        customOutputName = WindowsBuildEngine.defaultOutputFileName(
            family = selectedWinFamily,
            featureVer = selectedFeatureVer,
            build = b,
            langCode = l.code,
            outputMode = buildOutputMode
        )
        if (selectedBuildEditions.isNotEmpty()) {
            estimateLoading = true
            detailedManifest = WindowsBuildEngine.fetchDetailedManifest(b.uuid, l.code, selectedBuildEditions.toList())
            estimateLoading = false
        } else {
            detailedManifest = null
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = PocketColors.surfaceCard
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(13.dp)
        ) {
            PocketSheetHeader(
                icon = if (activeTab == WindowsDownloadTab.ALL_CHANNELS) Icons.Rounded.Build else Icons.Rounded.Window,
                tint = PocketColors.infoBlue,
                iconBg = PocketColors.infoBlueBg,
                title = family.title,
                subtitle = if (activeTab == WindowsDownloadTab.DIRECT_ISO) {
                    "Direct Official Retail ISO from Microsoft CDN"
                } else {
                    "Windows 11, 10 & Server · On-Phone ISO Builder"
                },
                trailing = {
                    IconButton(
                        onClick = {
                            if (activeTab == WindowsDownloadTab.DIRECT_ISO) loadDirectReleases(force = true)
                            else loadGroupedBuilds()
                        }
                    ) {
                        Icon(Icons.Rounded.Refresh, "Refresh", tint = PocketColors.textSecondary)
                    }
                }
            )

            // 2-Mode Switcher (Direct ISO [Fast] / All Channels)
            if (availableTabs.size > 1) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = PocketColors.surfaceInput,
                    border = BorderStroke(0.5.dp, PocketColors.border)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(4.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        availableTabs.forEach { tab ->
                            val isSel = activeTab == tab
                            val segBg by animateColorAsState(if (isSel) PocketColors.accentBg else Color.Transparent, tween(180), label = "winTabBg")
                            Surface(
                                modifier = Modifier.weight(1f).bounceClick { activeTab = tab },
                                shape = RoundedCornerShape(9.dp),
                                color = segBg,
                                border = if (isSel) BorderStroke(1.dp, PocketColors.accent) else null
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 9.dp),
                                    horizontalArrangement = Arrangement.Center,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = tab.label,
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = if (isSel) FontWeight.SemiBold else FontWeight.Medium,
                                        color = if (isSel) PocketColors.accentSoft else PocketColors.textSecondary
                                    )
                                    if (tab.badge != null) {
                                        Spacer(modifier = Modifier.width(5.dp))
                                        Surface(shape = RoundedCornerShape(4.dp), color = PocketColors.successBg) {
                                            Text(
                                                text = tab.badge,
                                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
                                                fontSize = 9.5.sp,
                                                fontWeight = FontWeight.SemiBold,
                                                color = PocketColors.success
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // =================================================================
            // TAB 1: DIRECT RETAIL ISO (Clean Release + Architecture Picker)
            // =================================================================
            if (activeTab == WindowsDownloadTab.DIRECT_ISO) {
                if (directLoadingReleases) {
                    Box(modifier = Modifier.fillMaxWidth().padding(vertical = 28.dp), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            CircularProgressIndicator(color = PocketColors.accent)
                            Text("Loading official Windows retail releases...", style = MaterialTheme.typography.bodySmall, color = PocketColors.textSecondary)
                        }
                    }
                } else {
                    if (directReleaseGroups.isNotEmpty()) {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            PocketSectionHeader("Release Version")
                            Row(
                                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(7.dp)
                            ) {
                                directReleaseGroups.forEachIndexed { idx, grp ->
                                    val isSel = grp.shortChipLabel == selectedDirectGroup?.shortChipLabel
                                    Surface(
                                        modifier = Modifier.bounceClick {
                                            selectedDirectGroup = grp
                                            if (directWantArm64 && grp.arm64Release == null) directWantArm64 = false
                                        },
                                        shape = RoundedCornerShape(10.dp),
                                        color = if (isSel) PocketColors.accentBg else PocketColors.surfaceInput,
                                        border = BorderStroke(if (isSel) 1.dp else 0.5.dp, if (isSel) PocketColors.accent else PocketColors.border)
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(5.dp)
                                        ) {
                                            Text(
                                                text = grp.shortChipLabel,
                                                style = MaterialTheme.typography.labelMedium,
                                                fontWeight = if (isSel) FontWeight.SemiBold else FontWeight.Medium,
                                                color = if (isSel) PocketColors.accentSoft else PocketColors.textSecondary
                                            )
                                            if (idx == 0) {
                                                Text("Latest", fontSize = 9.5.sp, fontWeight = FontWeight.SemiBold, color = PocketColors.success)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // Unified Architecture Picker (Release -> Architecture -> Language for BOTH Win 11 & Win 10)
                    selectedDirectGroup?.let { grp ->
                        val hasWin11Pair = grp.x64Release != null && grp.arm64Release != null
                        val isWin10Dual = grp.osFamily == "win10" || grp.osFamily == "win81"
                        if (hasWin11Pair || isWin10Dual) {
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                PocketSectionHeader("Architecture")
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    if (hasWin11Pair) {
                                        listOf(false to "x64 (PC / Laptop)", true to "ARM64 (Snapdragon)").forEach { (isArm, lbl) ->
                                            val isSel = directWantArm64 == isArm
                                            Surface(
                                                modifier = Modifier.weight(1f).bounceClick { directWantArm64 = isArm },
                                                shape = RoundedCornerShape(PocketTokens.CompactButtonRadius),
                                                color = if (isSel) PocketColors.infoBlueBg else PocketColors.surfaceInput,
                                                border = BorderStroke(if (isSel) 1.dp else 0.5.dp, if (isSel) PocketColors.infoBlue else PocketColors.border)
                                            ) {
                                                Row(
                                                    modifier = Modifier.padding(vertical = 9.dp, horizontal = 8.dp),
                                                    horizontalArrangement = Arrangement.Center,
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    Icon(Icons.Rounded.Memory, null, tint = if (isSel) PocketColors.infoBlue else PocketColors.textMuted, modifier = Modifier.size(15.dp))
                                                    Spacer(modifier = Modifier.width(5.dp))
                                                    Text(lbl, style = MaterialTheme.typography.labelMedium, color = if (isSel) PocketColors.infoBlue else PocketColors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                                }
                                            }
                                        }
                                    } else {
                                        listOf("x64" to "x64 (64-bit)", "x86" to "x86 (32-bit)").forEach { (archCode, lbl) ->
                                            val isSel = directWin10Arch.equals(archCode, true)
                                            Surface(
                                                modifier = Modifier.weight(1f).bounceClick { directWin10Arch = archCode },
                                                shape = RoundedCornerShape(PocketTokens.CompactButtonRadius),
                                                color = if (isSel) PocketColors.infoBlueBg else PocketColors.surfaceInput,
                                                border = BorderStroke(if (isSel) 1.dp else 0.5.dp, if (isSel) PocketColors.infoBlue else PocketColors.border)
                                            ) {
                                                Row(
                                                    modifier = Modifier.padding(vertical = 9.dp, horizontal = 8.dp),
                                                    horizontalArrangement = Arrangement.Center,
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    Icon(Icons.Rounded.Memory, null, tint = if (isSel) PocketColors.infoBlue else PocketColors.textMuted, modifier = Modifier.size(15.dp))
                                                    Spacer(modifier = Modifier.width(5.dp))
                                                    Text(lbl, style = MaterialTheme.typography.labelMedium, color = if (isSel) PocketColors.infoBlue else PocketColors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    activeDirectRelease?.let { rel ->
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Surface(
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(12.dp),
                                color = PocketColors.surfaceInput,
                                border = BorderStroke(0.5.dp, PocketColors.border)
                            ) {
                                Row(modifier = Modifier.padding(11.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Icon(Icons.Rounded.Tag, null, tint = PocketColors.infoBlue, modifier = Modifier.size(18.dp))
                                    Column {
                                        Text("EDITION ID", fontSize = 10.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.7.sp, color = PocketColors.textMuted)
                                        val badgeArch = if (rel.osFamily == "win10" || rel.osFamily == "win81") directWin10Arch else if (rel.isArm64Only) "ARM64" else "x64"
                                        Text("#${rel.editionId} · $badgeArch", style = PocketColors.monoStyle, color = PocketColors.textPrimary)
                                    }
                                }
                            }
                            Surface(
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(12.dp),
                                color = PocketColors.surfaceInput,
                                border = BorderStroke(0.5.dp, PocketColors.border)
                            ) {
                                Row(modifier = Modifier.padding(11.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Icon(Icons.Rounded.CalendarMonth, null, tint = PocketColors.success, modifier = Modifier.size(18.dp))
                                    Column {
                                        Text("RELEASE", fontSize = 10.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.7.sp, color = PocketColors.textMuted)
                                        Text(rel.releaseLabel.take(24), style = PocketColors.monoStyle, color = PocketColors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                }
                            }
                        }
                    }

                    if (directError != null) {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = PocketColors.warningBg,
                            border = BorderStroke(0.75.dp, PocketColors.warning)
                        ) {
                            Column(modifier = Modifier.fillMaxWidth().padding(13.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Icon(Icons.Rounded.ErrorOutline, null, tint = PocketColors.warning, modifier = Modifier.size(19.dp))
                                    Text(directError!!, style = MaterialTheme.typography.bodySmall, color = PocketColors.textPrimary)
                                }
                                Button(
                                    onClick = { activeTab = WindowsDownloadTab.ALL_CHANNELS },
                                    shape = RoundedCornerShape(9.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = PocketColors.accent, contentColor = PocketColors.onAccent)
                                ) {
                                    Text("Switch to All Channels", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                }
                            }
                        }
                    }

                    if (directSkuLoading || directSkus.isNotEmpty()) {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            PocketSectionHeader("Language", trailing = if (directSkuLoading) "Connecting..." else "${directSkus.size} available")
                            Box(modifier = Modifier.fillMaxWidth()) {
                                Surface(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .bounceClick(enabled = !directSkuLoading && directSkus.isNotEmpty()) {
                                            langSearch = ""
                                            langMenuOpen = true
                                        },
                                    shape = RoundedCornerShape(12.dp),
                                    color = PocketColors.surfaceInput,
                                    border = BorderStroke(0.75.dp, PocketColors.accent.copy(alpha = 0.7f))
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth().padding(horizontal = 13.dp, vertical = 12.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                                    ) {
                                        if (directSkuLoading) {
                                            CircularProgressIndicator(modifier = Modifier.size(17.dp), strokeWidth = 2.dp, color = PocketColors.accent)
                                            Text("Connecting to Microsoft servers...", style = MaterialTheme.typography.bodySmall, color = PocketColors.textSecondary)
                                        } else {
                                            Icon(Icons.Rounded.Language, null, tint = PocketColors.accentSoft, modifier = Modifier.size(19.dp))
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(selectedDirectSku?.localizedLanguage ?: "Select language", style = MaterialTheme.typography.labelLarge, color = PocketColors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                                Text(
                                                    text = if (directLinkLoading) "Generating official 24h CDN URL..." else (selectedDirectLink?.fileName ?: "Official Microsoft CDN"),
                                                    style = PocketColors.monoStyle,
                                                    color = PocketColors.textMuted,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                            }
                                            Icon(Icons.Rounded.ExpandMore, null, tint = PocketColors.textSecondary, modifier = Modifier.size(19.dp))
                                        }
                                    }
                                }

                                DropdownMenu(
                                    expanded = langMenuOpen,
                                    onDismissRequest = { langMenuOpen = false },
                                    modifier = Modifier.fillMaxWidth(0.86f).background(PocketColors.surfaceCard)
                                ) {
                                    if (directSkus.size > 6) {
                                        OutlinedTextField(
                                            value = langSearch,
                                            onValueChange = { langSearch = it },
                                            placeholder = { Text("Search language...", fontSize = 12.sp) },
                                            leadingIcon = { Icon(Icons.Rounded.Search, null, modifier = Modifier.size(16.dp)) },
                                            singleLine = true,
                                            modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp),
                                            shape = RoundedCornerShape(10.dp)
                                        )
                                    }
                                    directSkus.filter {
                                        langSearch.isBlank() || it.localizedLanguage.contains(langSearch.trim(), true) || it.language.contains(langSearch.trim(), true)
                                    }.forEach { sku ->
                                        val isSel = sku.skuId == selectedDirectSku?.skuId
                                        DropdownMenuItem(
                                            text = { Text(sku.localizedLanguage, style = MaterialTheme.typography.labelLarge, color = if (isSel) PocketColors.accentSoft else PocketColors.textPrimary) },
                                            leadingIcon = if (isSel) {
                                                { Icon(Icons.Rounded.Check, null, tint = PocketColors.accentSoft, modifier = Modifier.size(16.dp)) }
                                            } else null,
                                            onClick = {
                                                selectedDirectSku = sku
                                                langMenuOpen = false
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }

                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                        OutlinedButton(
                            onClick = {
                                val link = selectedDirectLink ?: return@OutlinedButton
                                val cm = context.getSystemService(ClipboardManager::class.java)
                                cm?.setPrimaryClip(ClipData.newPlainText("Windows ISO Link", link.url))
                                Toast.makeText(context, "Copied direct link for external download managers.", Toast.LENGTH_SHORT).show()
                            },
                            enabled = selectedDirectLink != null && !directLinkLoading,
                            modifier = Modifier.weight(0.95f).height(48.dp),
                            shape = RoundedCornerShape(12.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp),
                            border = BorderStroke(0.75.dp, PocketColors.border)
                        ) {
                            Icon(Icons.Rounded.ContentCopy, null, tint = PocketColors.accentSoft, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(5.dp))
                            Text("Copy Link", fontSize = 12.5.sp, color = PocketColors.textPrimary)
                        }

                        Button(
                            onClick = {
                                val link = selectedDirectLink ?: return@Button
                                val relName = activeDirectRelease?.releaseLabel ?: family.title
                                onDismiss()
                                onStartDirectDownload("$relName (${selectedDirectSku?.localizedLanguage ?: link.arch})", link.url, link.fileName, "")
                            },
                            enabled = selectedDirectLink != null && !directLinkLoading,
                            modifier = Modifier.weight(1.35f).height(48.dp),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = PocketColors.accent, contentColor = PocketColors.onAccent)
                        ) {
                            if (directLinkLoading) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = PocketColors.onAccent)
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Signing...")
                            } else {
                                Icon(Icons.Rounded.Download, null, modifier = Modifier.size(17.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Turbo (${turboConnections}x)", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                            }
                        }
                    }
                }
            }

            // =================================================================
            // TAB 2: HIERARCHICAL ALL CHANNELS BUILDER
            // Step 1: Windows Version -> Feature Version -> Architecture -> Grouped Builds (Stable / Preview / Insider)
            // Step 2: Language, Editions, Estimated Size, Compression, Output Mode, Direct USB Bypasses & Filename
            // =================================================================
            else {
                if (selectedBuild == null) {
                    // STEP 1: Windows Family (Windows 11 / Windows 10 / Windows Server)
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        PocketSectionHeader("1. Windows Version")
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                            WinFamilyFilter.entries.forEach { wf ->
                                val isSel = selectedWinFamily == wf
                                Surface(
                                    modifier = Modifier.weight(1f).bounceClick {
                                        if (selectedWinFamily != wf) {
                                            selectedWinFamily = wf
                                            val nextVer = WinFeatureVersion.forFamily(wf).first()
                                            selectedFeatureVer = nextVer
                                            if (archFilter == "x86" && wf != WinFamilyFilter.WIN10) archFilter = "amd64"
                                            customSearchQuery = ""
                                            loadGroupedBuilds(wf, nextVer, archFilter, "")
                                        }
                                    },
                                    shape = RoundedCornerShape(10.dp),
                                    color = if (isSel) PocketColors.accentBg else PocketColors.surfaceInput,
                                    border = BorderStroke(if (isSel) 1.dp else 0.5.dp, if (isSel) PocketColors.accent else PocketColors.border)
                                ) {
                                    Box(modifier = Modifier.padding(vertical = 9.dp, horizontal = 4.dp), contentAlignment = Alignment.Center) {
                                        Text(
                                            text = wf.label.replace("Windows ", "Win "),
                                            style = MaterialTheme.typography.labelMedium,
                                            fontWeight = if (isSel) FontWeight.SemiBold else FontWeight.Medium,
                                            color = if (isSel) PocketColors.accentSoft else PocketColors.textSecondary,
                                            maxLines = 1
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // STEP 2: Feature Version (e.g. 26H2 / 26H1 / 25H2 / 24H2 / 23H2 / Insider)
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        PocketSectionHeader("2. Feature Version")
                        Row(
                            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(7.dp)
                        ) {
                            featureVersionsForFamily.forEach { fv ->
                                val isSel = selectedFeatureVer == fv
                                Surface(
                                    modifier = Modifier.bounceClick {
                                        selectedFeatureVer = fv
                                        customSearchQuery = ""
                                        loadGroupedBuilds(selectedWinFamily, fv, archFilter, "")
                                    },
                                    shape = RoundedCornerShape(999.dp),
                                    color = if (isSel) PocketColors.accentBg else PocketColors.surfaceInput,
                                    border = BorderStroke(if (isSel) 1.dp else 0.5.dp, if (isSel) PocketColors.accent else PocketColors.border)
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        Text(
                                            text = fv.label,
                                            style = MaterialTheme.typography.labelMedium,
                                            fontWeight = if (isSel) FontWeight.SemiBold else FontWeight.Medium,
                                            color = if (isSel) PocketColors.accentSoft else PocketColors.textSecondary
                                        )
                                        if (fv.recommended) {
                                            Text("Rec", fontSize = 9.5.sp, fontWeight = FontWeight.SemiBold, color = PocketColors.success)
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // STEP 3: Architecture (x64 Rec / ARM64 / [x86 for Win 10] / All)
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        PocketSectionHeader("3. Architecture")
                        val archOptions = buildList {
                            add(Triple("amd64", "x64", true))
                            add(Triple("arm64", "ARM64", false))
                            if (selectedWinFamily == WinFamilyFilter.WIN10) add(Triple("x86", "x86", false))
                            add(Triple("all", "All", false))
                        }
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                            archOptions.forEach { (code, lbl, isRec) ->
                                val isSel = archFilter == code
                                Surface(
                                    modifier = Modifier.weight(1f).bounceClick {
                                        archFilter = code
                                        loadGroupedBuilds(selectedWinFamily, selectedFeatureVer, code, customSearchQuery)
                                    },
                                    shape = RoundedCornerShape(10.dp),
                                    color = if (isSel) PocketColors.infoBlueBg else PocketColors.surfaceInput,
                                    border = BorderStroke(if (isSel) 1.dp else 0.5.dp, if (isSel) PocketColors.infoBlue else PocketColors.border)
                                ) {
                                    Row(
                                        modifier = Modifier.padding(vertical = 8.dp),
                                        horizontalArrangement = Arrangement.Center,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(lbl, style = MaterialTheme.typography.labelMedium, color = if (isSel) PocketColors.infoBlue else PocketColors.textSecondary)
                                        if (isRec) {
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text("Rec", fontSize = 9.5.sp, fontWeight = FontWeight.SemiBold, color = PocketColors.success)
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // Compact Search Bar (single-line placeholder + ImeAction.Search + aligned 52.dp Go button)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = customSearchQuery,
                            onValueChange = { customSearchQuery = it },
                            placeholder = {
                                Text(
                                    "Search build # (e.g. 26300)...",
                                    fontSize = 12.5.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            },
                            leadingIcon = { Icon(Icons.Rounded.Search, null, tint = PocketColors.accentSoft, modifier = Modifier.size(17.dp)) },
                            trailingIcon = if (customSearchQuery.isNotEmpty()) {
                                {
                                    IconButton(
                                        onClick = {
                                            customSearchQuery = ""
                                            loadGroupedBuilds(selectedWinFamily, selectedFeatureVer, archFilter, "")
                                        }
                                    ) {
                                        Icon(Icons.Rounded.Close, "Clear", tint = PocketColors.textSecondary, modifier = Modifier.size(16.dp))
                                    }
                                }
                            } else null,
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(
                                onSearch = { loadGroupedBuilds(selectedWinFamily, selectedFeatureVer, archFilter, customSearchQuery) }
                            ),
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(PocketTokens.ButtonRadius),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = PocketColors.accent,
                                unfocusedBorderColor = PocketColors.border,
                                focusedContainerColor = PocketColors.surfaceInput,
                                unfocusedContainerColor = PocketColors.surfaceInput
                            )
                        )
                        Button(
                            onClick = { loadGroupedBuilds(selectedWinFamily, selectedFeatureVer, archFilter, customSearchQuery) },
                            enabled = !buildsLoading,
                            modifier = Modifier.height(PocketTokens.InputHeight),
                            shape = RoundedCornerShape(PocketTokens.ButtonRadius),
                            contentPadding = PaddingValues(horizontal = 14.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = PocketColors.accent, contentColor = PocketColors.onAccent)
                        ) {
                            Text("Go", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                        }
                    }

                    // Breadcrumb summary
                    Text(
                        text = "${selectedWinFamily.label} › ${selectedFeatureVer.label} › ${if (archFilter == "amd64") "x64" else archFilter.uppercase()}",
                        style = PocketColors.monoStyle,
                        color = PocketColors.accentSoft
                    )

                    if (buildsLoading) {
                        Box(modifier = Modifier.fillMaxWidth().padding(vertical = 26.dp), contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                CircularProgressIndicator(color = PocketColors.accent)
                                Text("Loading ${selectedWinFamily.label} ${selectedFeatureVer.label} builds...", style = MaterialTheme.typography.bodySmall, color = PocketColors.textSecondary)
                            }
                        }
                    } else if (groupedBuilds.isEmpty) {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = PocketColors.surfaceInput,
                            border = BorderStroke(0.5.dp, PocketColors.border)
                        ) {
                            Text(
                                "No installable builds matched ${selectedWinFamily.label} ${selectedFeatureVer.label}. Try another Feature Version or Architecture.",
                                modifier = Modifier.padding(14.dp),
                                style = MaterialTheme.typography.bodySmall,
                                color = PocketColors.textSecondary
                            )
                        }
                    } else {
                        // GROUP 1: STABLE (General Availability)
                        if (groupedBuilds.stable.isNotEmpty()) {
                            BuildTierSection(
                                title = WinBuildTier.STABLE.sectionTitle,
                                count = groupedBuilds.stable.size,
                                badgeTint = PocketColors.success,
                                badgeBg = PocketColors.successBg,
                                builds = groupedBuilds.stable,
                                onSelectBuild = { selectedBuild = it }
                            )
                        }

                        // GROUP 2: PREVIEW (Release / Cumulative Preview)
                        if (groupedBuilds.preview.isNotEmpty()) {
                            BuildTierSection(
                                title = WinBuildTier.PREVIEW.sectionTitle,
                                count = groupedBuilds.preview.size,
                                badgeTint = PocketColors.infoBlue,
                                badgeBg = PocketColors.infoBlueBg,
                                builds = groupedBuilds.preview,
                                onSelectBuild = { selectedBuild = it }
                            )
                        }

                        // GROUP 3: OTHER / INSIDER BUILDS
                        if (groupedBuilds.insider.isNotEmpty()) {
                            BuildTierSection(
                                title = WinBuildTier.INSIDER.sectionTitle,
                                count = groupedBuilds.insider.size,
                                badgeTint = PocketColors.violet,
                                badgeBg = PocketColors.violetBg,
                                builds = groupedBuilds.insider,
                                onSelectBuild = { selectedBuild = it }
                            )
                        }
                    }
                } else {
                    // STEP 2: CONFIGURE SELECTED BUILD
                    val b = selectedBuild!!
                    val selDlBytes = when (downloadMethod) {
                        WinDownloadMethod.DOWNLOAD_UPDATES_ONLY -> detailedManifest?.updateBytes ?: 0L
                        else -> (detailedManifest?.baseBytes ?: 3_500_000_000L) +
                            (if (includeUpdates) detailedManifest?.updateBytes ?: 0L else 0L) +
                            (if (includeStoreApps) detailedManifest?.appBytes ?: 0L else 0L) +
                            (if (integrateNetFx3) detailedManifest?.netFx3Bytes ?: 0L else 0L)
                    }
                    val estBuildOutBytes = if (downloadMethod == WinDownloadMethod.DOWNLOAD_AND_BUILD) {
                        selDlBytes + (if (skipWinRe) 0L else 520L * 1024L * 1024L) + 512L * 1024L * 1024L
                    } else 256L * 1024L * 1024L
                    val minNeededBytes = selDlBytes + estBuildOutBytes
                    val lowSpace = freeBytes in 1L until minNeededBytes
                    val lowBattery = batteryPct < 25 && !isCharging

                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = PocketColors.surfaceInput,
                        border = BorderStroke(0.75.dp, PocketColors.accent)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            IconButton(onClick = { selectedBuild = null }, modifier = Modifier.size(34.dp)) {
                                Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back to builds", tint = PocketColors.textPrimary, modifier = Modifier.size(18.dp))
                            }
                            Column(modifier = Modifier.weight(1f)) {
                                Text(b.title, style = MaterialTheme.typography.labelLarge, color = PocketColors.textPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text("${b.channelBadge} · Build ${b.build} · ${b.archDisplay} · ${b.dateDisplay}", style = PocketColors.monoStyle, color = PocketColors.accentSoft)
                            }
                        }
                    }

                    // Language Picker
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        PocketSectionHeader("Language", trailing = if (buildLangLoading) "Loading..." else "${buildLangs.size} available")
                        Box(modifier = Modifier.fillMaxWidth()) {
                            Surface(
                                modifier = Modifier.fillMaxWidth().bounceClick(enabled = !buildLangLoading && buildLangs.isNotEmpty()) {
                                    langSearch = ""
                                    langMenuOpen = true
                                },
                                shape = RoundedCornerShape(12.dp),
                                color = PocketColors.surfaceInput,
                                border = BorderStroke(0.75.dp, PocketColors.border)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 13.dp, vertical = 11.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    if (buildLangLoading) {
                                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = PocketColors.accent)
                                        Text("Fetching languages...", style = MaterialTheme.typography.bodySmall, color = PocketColors.textSecondary)
                                    } else {
                                        Icon(Icons.Rounded.Language, null, tint = PocketColors.accentSoft, modifier = Modifier.size(18.dp))
                                        Text(selectedBuildLang?.displayName ?: "Select language", style = MaterialTheme.typography.labelLarge, color = PocketColors.textPrimary, modifier = Modifier.weight(1f))
                                        Icon(Icons.Rounded.ExpandMore, null, tint = PocketColors.textSecondary, modifier = Modifier.size(18.dp))
                                    }
                                }
                            }
                            DropdownMenu(
                                expanded = langMenuOpen,
                                onDismissRequest = { langMenuOpen = false },
                                modifier = Modifier.fillMaxWidth(0.86f).background(PocketColors.surfaceCard)
                            ) {
                                if (buildLangs.size > 6) {
                                    OutlinedTextField(
                                        value = langSearch,
                                        onValueChange = { langSearch = it },
                                        placeholder = { Text("Search language...", fontSize = 12.sp) },
                                        leadingIcon = { Icon(Icons.Rounded.Search, null, modifier = Modifier.size(16.dp)) },
                                        singleLine = true,
                                        modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp),
                                        shape = RoundedCornerShape(10.dp)
                                    )
                                }
                                buildLangs.filter {
                                    langSearch.isBlank() || it.displayName.contains(langSearch.trim(), true) || it.code.contains(langSearch.trim(), true)
                                }.forEach { opt ->
                                    val isSel = opt.code == selectedBuildLang?.code
                                    DropdownMenuItem(
                                        text = { Text(opt.displayName, style = MaterialTheme.typography.labelLarge, color = if (isSel) PocketColors.accentSoft else PocketColors.textPrimary) },
                                        leadingIcon = if (isSel) {
                                            { Icon(Icons.Rounded.Check, null, tint = PocketColors.accentSoft, modifier = Modifier.size(16.dp)) }
                                        } else null,
                                        onClick = {
                                            selectedBuildLang = opt
                                            langMenuOpen = false
                                        }
                                    )
                                }
                            }
                        }
                    }

                    // Editions Checklist
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        PocketSectionHeader("Editions", trailing = if (buildEdLoading) "Loading..." else "${selectedBuildEditions.size} selected")
                        if (buildEdLoading) {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(4.dp), color = PocketColors.accent, trackColor = PocketColors.surfaceSubtle)
                        } else {
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = PocketColors.surfaceInput,
                                border = BorderStroke(0.5.dp, PocketColors.border)
                            ) {
                                Column {
                                    buildEditions.forEachIndexed { idx, ed ->
                                        if (idx > 0) PocketHairlineDivider()
                                        val checked = selectedBuildEditions.contains(ed.code)
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clickable {
                                                    if (checked && selectedBuildEditions.size > 1) selectedBuildEditions.remove(ed.code)
                                                    else if (!checked) selectedBuildEditions.add(ed.code)
                                                }
                                                .padding(horizontal = 12.dp, vertical = 10.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                                        ) {
                                            Icon(
                                                imageVector = if (checked) Icons.Rounded.CheckBox else Icons.Rounded.CheckBoxOutlineBlank,
                                                contentDescription = null,
                                                tint = if (checked) PocketColors.accent else PocketColors.textMuted,
                                                modifier = Modifier.size(20.dp)
                                            )
                                            Text(ed.displayName, style = MaterialTheme.typography.labelMedium, color = PocketColors.textPrimary)
                                        }
                                    }
                                }
                            }
                        }
                    }

                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        PocketSectionHeader("Download Method")
                        WinDownloadMethod.entries.forEach { method ->
                            val isSel = downloadMethod == method
                            val disabled = method == WinDownloadMethod.DOWNLOAD_UPDATES_ONLY && detailedManifest?.hasUpdates == false
                            Surface(modifier = Modifier.fillMaxWidth().bounceClick(enabled = !disabled) { downloadMethod = method }, shape = RoundedCornerShape(11.dp), color = if (isSel) PocketColors.accentBg else PocketColors.surfaceInput, border = BorderStroke(if (isSel) 1.25.dp else 0.5.dp, if (isSel) PocketColors.accent else PocketColors.border)) {
                                Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(method.label, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = if (disabled) PocketColors.textMuted else if (isSel) PocketColors.accentSoft else PocketColors.textPrimary)
                                        Text(if (disabled) "No additional updates in this build" else method.subtitle, fontSize = 10.5.sp, color = PocketColors.textSecondary)
                                    }
                                    if (isSel) Icon(Icons.Rounded.Check, null, tint = PocketColors.accentSoft, modifier = Modifier.size(17.dp))
                                }
                            }
                        }
                    }

                    Surface(shape = RoundedCornerShape(12.dp), color = PocketColors.infoBlueBg.copy(alpha = 0.45f), border = BorderStroke(0.75.dp, PocketColors.infoBlue.copy(alpha = 0.6f))) {
                        Column(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Icon(Icons.Rounded.SystemUpdateAlt, null, tint = PocketColors.infoBlue, modifier = Modifier.size(18.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    val dm = detailedManifest
                                    Text(when { estimateLoading -> "Inspecting package manifest..."; dm != null -> "Base OS: ${UsbGadgetEngine.formatBytes(dm.baseBytes)} (${dm.baseFiles.size} files)"; else -> "Packages are SHA-1 verified & resumable" }, style = MaterialTheme.typography.labelMedium, color = PocketColors.textPrimary)
                                    if (dm != null) {
                                        val updTxt = if (dm.hasUpdates) "${dm.updateFiles.size} additional updates (+${UsbGadgetEngine.formatBytes(dm.updateBytes)})" else "No additional updates"
                                        val appTxt = if (dm.hasApps) " · ${dm.appFiles.size} Store apps (+${UsbGadgetEngine.formatBytes(dm.appBytes)})" else ""
                                        Text("$updTxt$appTxt", fontSize = 11.sp, color = PocketColors.textSecondary)
                                    }
                                }
                            }
                            if (detailedManifest != null) {
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                                    if (detailedManifest!!.hasUpdates) {
                                        OutlinedButton(onClick = { showUpdatesInspector = true }, modifier = Modifier.weight(1f).height(36.dp), shape = RoundedCornerShape(9.dp), contentPadding = PaddingValues(horizontal = 8.dp), border = BorderStroke(0.75.dp, PocketColors.infoBlue.copy(alpha = 0.7f))) {
                                            Text("Updates List (${detailedManifest!!.updateFiles.size})", fontSize = 11.sp, color = PocketColors.textPrimary, maxLines = 1)
                                        }
                                    }
                                    OutlinedButton(onClick = { showFullFilesInspector = true }, modifier = Modifier.weight(1f).height(36.dp), shape = RoundedCornerShape(9.dp), contentPadding = PaddingValues(horizontal = 8.dp), border = BorderStroke(0.75.dp, PocketColors.border)) {
                                        Icon(Icons.Rounded.ListAlt, null, tint = PocketColors.accentSoft, modifier = Modifier.size(14.dp)); Spacer(modifier = Modifier.width(4.dp)); Text("All Files (${detailedManifest!!.allFiles.size})", fontSize = 11.sp, color = PocketColors.textPrimary, maxLines = 1)
                                    }
                                }
                            }
                        }
                    }

                    if (downloadMethod != WinDownloadMethod.DOWNLOAD_UPDATES_ONLY) {
                        val detOs = remember(b.title) { WindowsCustomizationEngine.detectWindowsOsKind(b.title) }
                        Surface(shape = RoundedCornerShape(14.dp), color = PocketColors.surfaceInput, border = BorderStroke(0.75.dp, PocketColors.border)) {
                            Column(modifier = Modifier.fillMaxWidth()) {
                                Row(modifier = Modifier.fillMaxWidth().clickable { convOptionsExpanded = !convOptionsExpanded }.padding(horizontal = 13.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text("Conversion & Integration Options", style = MaterialTheme.typography.labelLarge, color = PocketColors.textPrimary)
                                        Text("100% on-phone · Unplug-safe at first Windows restart", fontSize = 10.5.sp, color = PocketColors.textSecondary)
                                    }
                                    Icon(if (convOptionsExpanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null, tint = PocketColors.textSecondary, modifier = Modifier.size(19.dp))
                                }
                                AnimatedVisibility(visible = convOptionsExpanded) {
                                    Column(modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                        PocketHairlineDivider()
                                        if (detailedManifest?.hasUpdates != false) {
                                            val updSz = detailedManifest?.updateBytes?.takeIf { it > 0L }?.let { " (+${UsbGadgetEngine.formatBytes(it)})" }.orEmpty()
                                            DirectUsbOptionRow("Include additional updates$updSz", includeUpdates) { includeUpdates = it }; PocketHairlineDivider()
                                        }
                                        if (detailedManifest?.hasApps == true) {
                                            DirectUsbOptionRow("Include Inbox Store Apps (+${UsbGadgetEngine.formatBytes(detailedManifest!!.appBytes)})", includeStoreApps) { includeStoreApps = it }; PocketHairlineDivider()
                                        }
                                        if (detailedManifest?.hasNetFx3 == true) {
                                            DirectUsbOptionRow("Integrate .NET Framework 3.5 (Offline)", integrateNetFx3) { integrateNetFx3 = it }; PocketHairlineDivider()
                                        }
                                        if (downloadMethod == WinDownloadMethod.DOWNLOAD_AND_BUILD) {
                                            DirectUsbOptionRow("Run component cleanup (/ResetBase)", runComponentCleanup) { runComponentCleanup = it }; PocketHairlineDivider()
                                            DirectUsbOptionRow("Use solid (ESD) compression${if (!detOs.supportsEsd) " (N/A Win 7/8)" else ""}", buildCompression == WinIsoCompression.ESD && detOs.supportsEsd) { if (detOs.supportsEsd) buildCompression = if (it) WinIsoCompression.ESD else WinIsoCompression.WIM }; PocketHairlineDivider()
                                            DirectUsbOptionRow("Skip WinRE Recovery (~480 MB smaller)", skipWinRe) { skipWinRe = it }; PocketHairlineDivider()
                                            DirectUsbOptionRow("Unlock all editions (ei.cfg)", eiCfgUnlock) { eiCfgUnlock = it }; PocketHairlineDivider()
                                            DirectUsbOptionRow("Keep base UUP packages after build", keepPackagesAfterBuild) { keepPackagesAfterBuild = it }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    if (downloadMethod == WinDownloadMethod.DOWNLOAD_AND_BUILD) {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            PocketSectionHeader("Output Target")
                            val modes = buildList { add(WinBuildOutputMode.STANDARD_ISO); if (multiBootDrives.isNotEmpty()) add(WinBuildOutputMode.DIRECT_MULTIBOOT); add(WinBuildOutputMode.DIRECT_NTFS_USB) }
                            modes.forEach { mode ->
                                val isSel = buildOutputMode == mode
                                Surface(modifier = Modifier.fillMaxWidth().bounceClick { buildOutputMode = mode }, shape = RoundedCornerShape(11.dp), color = if (isSel) PocketColors.accentBg else PocketColors.surfaceInput, border = BorderStroke(if (isSel) 1.5.dp else 0.5.dp, if (isSel) PocketColors.accent else PocketColors.border)) {
                                    Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                                        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                                Text(mode.label, style = MaterialTheme.typography.labelLarge, color = if (isSel) PocketColors.accentSoft else PocketColors.textPrimary)
                                                if (mode.recommended) Surface(shape = RoundedCornerShape(5.dp), color = PocketColors.successBg) { Text("Rec", modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp), style = MaterialTheme.typography.labelSmall, color = PocketColors.success) }
                                            }
                                            Text(mode.subtitle, fontSize = 11.sp, color = PocketColors.textSecondary)
                                        }
                                        if (isSel) Icon(Icons.Rounded.Check, null, tint = PocketColors.accentSoft, modifier = Modifier.size(18.dp))
                                    }
                                }
                            }
                        }

                        AnimatedVisibility(visible = buildOutputMode == WinBuildOutputMode.DIRECT_NTFS_USB) {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                PocketSectionHeader("USB Partition Scheme & Drivers")
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    PartitionScheme.entries.forEach { s ->
                                        val sel = usbScheme == s
                                        Surface(modifier = Modifier.weight(1f).bounceClick { usbScheme = s }, shape = RoundedCornerShape(11.dp), color = if (sel) PocketColors.accentBg else PocketColors.surfaceInput, border = BorderStroke(if (sel) 1.25.dp else 0.5.dp, if (sel) PocketColors.accent else PocketColors.border)) {
                                            Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                                Text(s.label, style = MaterialTheme.typography.labelMedium, color = if (sel) PocketColors.accentSoft else PocketColors.textPrimary)
                                                Text(s.subtitle, fontSize = 10.sp, color = PocketColors.textSecondary)
                                            }
                                        }
                                    }
                                }
                                Surface(modifier = Modifier.fillMaxWidth().bounceClick { showUsbDriversDialog = true }, shape = RoundedCornerShape(11.dp), color = PocketColors.surfaceInput, border = BorderStroke(0.75.dp, if (usbExtraDriversDir.isNotBlank()) PocketColors.accent else PocketColors.border)) {
                                    Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                        Icon(Icons.Rounded.FolderOpen, null, tint = PocketColors.accentSoft, modifier = Modifier.size(18.dp))
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text("Include Driver / Apps Folder (/Drivers)", style = MaterialTheme.typography.labelMedium, color = PocketColors.textPrimary)
                                            Text(if (usbExtraDriversDir.isNotBlank()) usbExtraDriversDir.replace("/data/media/0", "/sdcard") else "Tap to browse folder (Intel RST, Wi-Fi, etc.)", fontSize = 11.sp, color = if (usbExtraDriversDir.isNotBlank()) PocketColors.accentSoft else PocketColors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        }
                                        if (usbExtraDriversDir.isNotBlank()) Icon(Icons.Rounded.Close, "Clear", tint = PocketColors.textMuted, modifier = Modifier.size(17.dp).clickable { usbExtraDriversDir = "" })
                                        else Text("Browse", style = MaterialTheme.typography.labelSmall, color = PocketColors.accentSoft)
                                    }
                                }
                            }
                        }

                        val detOsKind = remember(b.title) { WindowsCustomizationEngine.detectWindowsOsKind(b.title) }
                        WindowsTwoPaneCustomizer(
                            osKind = detOsKind, arch = b.arch, experience = experience, winUtil = winUtil, presets = presets,
                            showCa2023Option = true, showCustomScriptOption = true, defaultBrowseDir = targetDir,
                            onExperienceChange = { experience = it }, onWinUtilChange = { winUtil = it },
                            onApplyPreset = { p ->
                                usbScheme = runCatching { PartitionScheme.valueOf(p.schemeName) }.getOrDefault(usbScheme)
                                experience = WindowsExperienceConfig(p.bypassRequirements, p.bypassMicrosoftAccount, p.disableDataCollection, p.disableBitLocker, p.qolImprovements, p.disableSponsoredApps, p.classicContextMenu, p.skipWifiSetup, p.createLocalUser, p.localUsername, "", p.skipOobe, p.useCa2023Bootloaders, p.applySkuSiPolicy)
                                winUtil = WinUtilTweaksConfig.fromJsonString(p.winUtilJson)
                            },
                            onSavePreset = { n -> onSavePreset(AutounattendPreset(n, usbScheme.name, experience.bypassRequirements, experience.bypassMicrosoftAccount, experience.disableDataCollection, experience.disableBitLocker, experience.disableSponsoredApps, experience.classicContextMenu, experience.skipWifiSetup, experience.createLocalUser, experience.localUsername, experience.skipOobe, experience.qolImprovements, experience.useCa2023Bootloaders, experience.applySkuSiPolicy, winUtil.toJsonString())) },
                            onDeletePreset = onDeletePreset
                        )
                    }

                    OutlinedTextField(
                        value = customOutputName, onValueChange = { customOutputName = it },
                        label = { Text(if (downloadMethod == WinDownloadMethod.DOWNLOAD_AND_BUILD) "Output Filename" else "Output Folder Name") },
                        leadingIcon = { Icon(Icons.Rounded.Tune, null, tint = PocketColors.accentSoft, modifier = Modifier.size(17.dp)) },
                        singleLine = true, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp),
                        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = PocketColors.accent, unfocusedBorderColor = PocketColors.border, focusedContainerColor = PocketColors.surfaceInput, unfocusedContainerColor = PocketColors.surfaceInput)
                    )

                    val freeAfter = (freeBytes - minNeededBytes).coerceAtLeast(0L)
                    Surface(shape = RoundedCornerShape(11.dp), color = when { lowSpace -> PocketColors.dangerBg; lowBattery -> PocketColors.warningBg; else -> PocketColors.surfaceInput }, border = BorderStroke(0.5.dp, when { lowSpace -> PocketColors.danger; lowBattery -> PocketColors.warning; else -> PocketColors.border })) {
                        Column(modifier = Modifier.fillMaxWidth().padding(11.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Icon(when { lowSpace -> Icons.Rounded.WarningAmber; lowBattery -> Icons.Rounded.BatteryAlert; else -> Icons.Rounded.Info }, null, tint = when { lowSpace -> PocketColors.danger; lowBattery -> PocketColors.warning; else -> PocketColors.accentSoft }, modifier = Modifier.size(16.dp))
                                Text(when { lowSpace -> "Need ~${UsbGadgetEngine.formatBytes(minNeededBytes)}, only ${UsbGadgetEngine.formatBytes(freeBytes)} free"; lowBattery -> "Battery at $batteryPct% · plug in charger for long builds"; else -> "DL ${UsbGadgetEngine.formatBytes(selDlBytes)} · Peak ${UsbGadgetEngine.formatBytes(minNeededBytes)} · Free After ~${UsbGadgetEngine.formatBytes(freeAfter)}" }, style = PocketColors.monoStyle, color = PocketColors.textPrimary)
                            }
                            if (freeBytes > 0L) LinearProgressIndicator(progress = { (minNeededBytes.toFloat() / freeBytes.toFloat()).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(999.dp)), color = if (lowSpace) PocketColors.danger else PocketColors.accent, trackColor = PocketColors.surfaceSubtle)
                        }
                    }

                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                        OutlinedButton(
                            onClick = {
                                val langCode = selectedBuildLang?.code ?: "en-us"
                                context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("Windows Build Link", WindowsBuildEngine.buildExternalPageLink(b, langCode)))
                                Toast.makeText(context, "Copied build link.", Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier.weight(0.9f).height(48.dp), shape = RoundedCornerShape(12.dp), contentPadding = PaddingValues(horizontal = 10.dp), border = BorderStroke(0.75.dp, PocketColors.border)
                        ) { Icon(Icons.Rounded.ContentCopy, null, tint = PocketColors.accentSoft, modifier = Modifier.size(16.dp)); Spacer(modifier = Modifier.width(5.dp)); Text("Copy Link", fontSize = 12.5.sp, color = PocketColors.textPrimary) }
                        Button(
                            onClick = {
                                val lang = selectedBuildLang ?: return@Button
                                val chosenEds = buildEditions.filter { selectedBuildEditions.contains(it.code) }
                                if (chosenEds.isEmpty()) return@Button
                                val mbPath = activeMultiBootTarget?.path ?: multiBootDrives.firstOrNull()?.path.orEmpty()
                                onDismiss()
                                onStartChannelBuild(
                                    WindowsChannelBuildConfig(
                                        build = b, lang = lang, editions = chosenEds, compression = buildCompression, outputMode = buildOutputMode, downloadMethod = downloadMethod,
                                        targetDir = targetDir, customOutputName = customOutputName.trim(), targetMultiBootPath = mbPath, connections = turboConnections, autoMount = true,
                                        includeUpdates = includeUpdates, includeStoreApps = includeStoreApps, integrateNetFx3 = integrateNetFx3, runComponentCleanup = runComponentCleanup,
                                        skipWinRe = skipWinRe, eiCfgUnlock = eiCfgUnlock, keepPackagesAfterBuild = keepPackagesAfterBuild, scheme = usbScheme, experience = experience, winUtil = winUtil, extraDriversDir = usbExtraDriversDir.trim()
                                    )
                                )
                            },
                            enabled = !lowSpace && selectedBuildLang != null && selectedBuildEditions.isNotEmpty() && customOutputName.isNotBlank(),
                            modifier = Modifier.weight(1.4f).height(48.dp), shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = PocketColors.accent, contentColor = PocketColors.onAccent)
                        ) {
                            Icon(Icons.Rounded.Build, null, modifier = Modifier.size(17.dp)); Spacer(modifier = Modifier.width(6.dp))
                            Text(when (downloadMethod) { WinDownloadMethod.DOWNLOAD_AND_BUILD -> "Download & Build"; WinDownloadMethod.DOWNLOAD_PACKAGE_SET -> "Download Packages"; WinDownloadMethod.DOWNLOAD_UPDATES_ONLY -> "Download Updates" }, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(22.dp))
        }
    }

    if (showUsbDriversDialog) {
        RootFolderPickerSheet("Select Drivers / Apps Folder", "Files inside will be copied into /Drivers on the Direct USB", usbExtraDriversDir.ifBlank { targetDir }, { showUsbDriversDialog = false }) { usbExtraDriversDir = it; showUsbDriversDialog = false }
    }
    if (showUpdatesInspector && detailedManifest != null) {
        WinManifestInspectorSheet("Additional Updates (${detailedManifest!!.updateFiles.size})", "Total: ${UsbGadgetEngine.formatBytes(detailedManifest!!.updateBytes)} · Embedded unplug-safe via \$OEM\$", detailedManifest!!.updateFiles) { showUpdatesInspector = false }
    }
    if (showFullFilesInspector && detailedManifest != null) {
        WinManifestInspectorSheet("Full Build File List (${detailedManifest!!.allFiles.size})", "Total manifest: ${UsbGadgetEngine.formatBytes(detailedManifest!!.allFiles.sumOf { it.sizeBytes })}", detailedManifest!!.allFiles) { showFullFilesInspector = false }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WinManifestInspectorSheet(title: String, subtitle: String, files: List<WinPackageFileEntry>, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = PocketColors.surfaceCard) {
        Column(modifier = Modifier.fillMaxWidth().fillMaxHeight(0.82f).padding(horizontal = 18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            PocketSheetHeader(icon = Icons.Rounded.ListAlt, tint = PocketColors.infoBlue, iconBg = PocketColors.infoBlueBg, title = title, subtitle = subtitle)
            LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(files, key = { it.name }) { f ->
                    Surface(shape = RoundedCornerShape(10.dp), color = PocketColors.surfaceInput, border = BorderStroke(0.5.dp, PocketColors.border)) {
                        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 11.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Text(f.name, style = MaterialTheme.typography.labelMedium, color = PocketColors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                Spacer(modifier = Modifier.width(6.dp))
                                Surface(shape = RoundedCornerShape(5.dp), color = PocketColors.accentBg) { Text(f.kind.badge, modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp), fontSize = 9.5.sp, color = PocketColors.accentSoft) }
                            }
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("SHA-1: ${f.sha1.take(18)}...", style = PocketColors.monoStyle, fontSize = 10.sp, color = PocketColors.textMuted)
                                Text(UsbGadgetEngine.formatBytes(f.sizeBytes), style = PocketColors.monoStyle, fontSize = 10.5.sp, color = PocketColors.accentSoft)
                            }
                        }
                    }
                }
            }
            OutlinedButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth().padding(bottom = 18.dp).height(46.dp), shape = RoundedCornerShape(12.dp), border = BorderStroke(0.75.dp, PocketColors.border)) { Text("Close", color = PocketColors.textPrimary) }
        }
    }
}

@Composable
private fun BuildTierSection(
    title: String,
    count: Int,
    badgeTint: Color,
    badgeBg: Color,
    builds: List<WindowsChannelBuild>,
    onSelectBuild: (WindowsChannelBuild) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = title.uppercase(),
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.8.sp,
                color = badgeTint
            )
            Box(modifier = Modifier.weight(1f).height(0.5.dp).background(PocketColors.border))
            Text("$count", style = PocketColors.monoStyle, color = PocketColors.textMuted)
        }
        builds.forEach { b ->
            Surface(
                modifier = Modifier.fillMaxWidth().bounceClick { onSelectBuild(b) },
                shape = RoundedCornerShape(12.dp),
                color = PocketColors.surfaceInput,
                border = BorderStroke(0.75.dp, PocketColors.border)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(b.title, style = MaterialTheme.typography.labelLarge, color = PocketColors.textPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Surface(shape = RoundedCornerShape(5.dp), color = badgeBg) {
                                Text(b.channelBadge, modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp), style = MaterialTheme.typography.labelSmall, color = badgeTint)
                            }
                            Surface(shape = RoundedCornerShape(5.dp), color = PocketColors.surfaceSubtle) {
                                Text(b.archDisplay, modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp), style = MaterialTheme.typography.labelSmall, color = PocketColors.textPrimary)
                            }
                            Text("Build ${b.build} · ${b.dateDisplay}", style = PocketColors.monoStyle, color = PocketColors.textMuted)
                        }
                    }
                    Icon(Icons.Rounded.ChevronRight, null, tint = PocketColors.accentSoft, modifier = Modifier.size(18.dp))
                }
            }
        }
    }
}

@Composable
private fun DirectUsbOptionRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(title, style = MaterialTheme.typography.labelMedium, color = PocketColors.textPrimary, modifier = Modifier.weight(1f))
        Spacer(modifier = Modifier.width(8.dp))
        PocketSwitch(checked = checked, onCheckedChange = onCheckedChange, activeColor = PocketColors.success)
    }
}
