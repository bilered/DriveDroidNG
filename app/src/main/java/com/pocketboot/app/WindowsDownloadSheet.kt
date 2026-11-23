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
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.BatteryAlert
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckBox
import androidx.compose.material.icons.rounded.CheckBoxOutlineBlank
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.CloudSync
import androidx.compose.runtime.collectAsState
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
    customUupApiUrl: String = AppSettings.DEFAULT_UUP_API_URL,
    preferDirectMicrosoftUup: Boolean = false,
    cachedUupBuildsJson: String = "",
    defaultThermalProfile: BuildThermalProfile = BuildThermalProfile.BALANCED,
    defaultAutoThermalCooldown: Boolean = true,
    onUpdateCachedUupBuilds: (String) -> Unit = {},
    onSavePreset: (AutounattendPreset) -> Unit = {},
    onDeletePreset: (String) -> Unit = {},
    onDismiss: () -> Unit,
    onStartDirectDownload: (title: String, url: String, fileName: String, expectedSha256: String) -> Unit,
    onStartChannelBuild: (WindowsChannelBuildConfig) -> Unit,
    onStartLocalUupBuild: (String, WindowsChannelBuildConfig) -> Unit = { _, _ -> }
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
    var storeAppLevel by remember { mutableStateOf(WinStoreAppLevel.SECURITY_ONLY) }
    var splitWimForFat32 by remember { mutableStateOf(false) }
    var selectedThermalProfile by remember(defaultThermalProfile) { mutableStateOf(defaultThermalProfile) }
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

    // v4.5.0 Direct Microsoft fallback, Virtual Editions & Local UUP Folder state
    val activeUupSource by WindowsBuildEngine.activeSource.collectAsState()
    var hideCumulativeUpdates by remember { mutableStateOf(true) }
    var hideOlderBuilds by remember { mutableStateOf(true) }
    var groupByYear by remember { mutableStateOf(false) }
    var selectedYearFilter by remember { mutableStateOf<Int?>(null) }
    var directMsFetching by remember { mutableStateOf(false) }
    var selectedVirtualEditions by remember(family.id) { mutableStateOf<Set<String>>(emptySet()) }
    var showLocalUupFolderPicker by remember { mutableStateOf(false) }
    var localUupSelectedFolder by remember { mutableStateOf<String?>(null) }

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
        query: String = customSearchQuery,
        hideCu: Boolean = hideCumulativeUpdates,
        hideOlder: Boolean = hideOlderBuilds
    ) {
        scope.launch {
            WindowsBuildEngine.configureNetwork(customUupApiUrl, preferDirectMicrosoftUup)
            if (groupedBuilds.isEmpty) {
                val quickOffline = WindowsBuildEngine.loadOfflineCatalogBuilds(context, cachedUupBuildsJson)
                val quickGrouped = WindowsBuildEngine.filterBuildsToGroups(quickOffline, fam, ver, arch, query.trim(), hideCu, hideOlder)
                if (!quickGrouped.isEmpty) groupedBuilds = quickGrouped
            }
            buildsLoading = true
            groupedBuilds = WindowsBuildEngine.fetchGroupedBuilds(
                family = fam,
                featureVer = ver,
                archFilter = arch,
                customQuery = query,
                context = context,
                cachedCatalogJson = cachedUupBuildsJson,
                hideCumulativeUpdates = hideCu,
                hideOlderBuilds = hideOlder
            )
            val availYears = groupedBuilds.allBuilds.map { it.createdYear }.toSet()
            if (selectedYearFilter != null && selectedYearFilter !in availYears) {
                selectedYearFilter = null
            }
            buildsLoading = false
        }
    }

    LaunchedEffect(family.id, activeTab) {
        WindowsBuildEngine.configureNetwork(customUupApiUrl, preferDirectMicrosoftUup)
        when (activeTab) {
            WindowsDownloadTab.DIRECT_ISO -> if (directReleaseGroups.isEmpty()) loadDirectReleases(force = false)
            WindowsDownloadTab.ALL_CHANNELS -> {
                if (groupedBuilds.isEmpty && selectedBuild == null) loadGroupedBuilds()
                // Quietly sync daily uup-builds.json from GitHub in the background
                scope.launch {
                    WindowsBuildEngine.syncOfflineCatalogFromGitHub()?.let { freshJson ->
                        onUpdateCachedUupBuilds(freshJson)
                    }
                }
            }
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
        selectedVirtualEditions = emptySet()
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
            detailedManifest = WindowsBuildEngine.fetchDetailedManifest(b.uuid, l.code, selectedBuildEditions.toList(), archHint = b.arch, buildHint = b.build)
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
                                modifier = Modifier.weight(1f).height(40.dp).bounceClick { activeTab = tab },
                                shape = RoundedCornerShape(9.dp),
                                color = segBg,
                                border = if (isSel) BorderStroke(1.dp, PocketColors.accent) else null
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp),
                                    horizontalArrangement = Arrangement.Center,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = tab.label,
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = if (isSel) FontWeight.SemiBold else FontWeight.Medium,
                                        color = if (isSel) PocketColors.accentSoft else PocketColors.textSecondary,
                                        maxLines = 1
                                    )
                                    if (tab.badge != null) {
                                        Spacer(modifier = Modifier.width(5.dp))
                                        Surface(shape = RoundedCornerShape(4.dp), color = PocketColors.successBg) {
                                            Text(
                                                text = tab.badge,
                                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
                                                fontSize = 9.5.sp,
                                                lineHeight = 11.sp,
                                                fontWeight = FontWeight.SemiBold,
                                                color = PocketColors.success,
                                                maxLines = 1
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
                                    modifier = Modifier.weight(1f).height(36.dp).bounceClick {
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
                                    Box(modifier = Modifier.fillMaxSize().padding(horizontal = 4.dp), contentAlignment = Alignment.Center) {
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
                                    modifier = Modifier.height(34.dp).bounceClick {
                                        selectedFeatureVer = fv
                                        customSearchQuery = ""
                                        loadGroupedBuilds(selectedWinFamily, fv, archFilter, "")
                                    },
                                    shape = RoundedCornerShape(999.dp),
                                    color = if (isSel) PocketColors.accentBg else PocketColors.surfaceInput,
                                    border = BorderStroke(if (isSel) 1.dp else 0.5.dp, if (isSel) PocketColors.accent else PocketColors.border)
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 12.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        Text(
                                            text = fv.label,
                                            style = MaterialTheme.typography.labelMedium,
                                            fontWeight = if (isSel) FontWeight.SemiBold else FontWeight.Medium,
                                            color = if (isSel) PocketColors.accentSoft else PocketColors.textSecondary,
                                            maxLines = 1
                                        )
                                        if (fv.recommended) {
                                            Text("Rec", fontSize = 9.5.sp, lineHeight = 12.sp, fontWeight = FontWeight.SemiBold, color = PocketColors.success, maxLines = 1)
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
                                    modifier = Modifier.weight(1f).height(36.dp).bounceClick {
                                        archFilter = code
                                        loadGroupedBuilds(selectedWinFamily, selectedFeatureVer, code, customSearchQuery)
                                    },
                                    shape = RoundedCornerShape(10.dp),
                                    color = if (isSel) PocketColors.infoBlueBg else PocketColors.surfaceInput,
                                    border = BorderStroke(if (isSel) 1.dp else 0.5.dp, if (isSel) PocketColors.infoBlue else PocketColors.border)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxSize().padding(horizontal = 6.dp),
                                        horizontalArrangement = Arrangement.Center,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(lbl, style = MaterialTheme.typography.labelMedium, color = if (isSel) PocketColors.infoBlue else PocketColors.textSecondary, maxLines = 1)
                                        if (isRec) {
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text("Rec", fontSize = 9.5.sp, lineHeight = 12.sp, fontWeight = FontWeight.SemiBold, color = PocketColors.success, maxLines = 1)
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

                    // Source status bar + Direct Microsoft Fetch Latest + Offline Local UUP Folder
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "${selectedWinFamily.label} › ${selectedFeatureVer.label} › ${if (archFilter == "amd64") "x64" else archFilter.uppercase()}",
                            style = PocketColors.monoStyle,
                            color = PocketColors.accentSoft,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        val srcBg = when (activeUupSource) {
                            UupDataSource.UUPDUMP_LIVE -> PocketColors.successBg
                            UupDataSource.DIRECT_MICROSOFT -> PocketColors.infoBlueBg
                            UupDataSource.OFFLINE_CATALOG -> PocketColors.warningBg
                        }
                        val srcTint = when (activeUupSource) {
                            UupDataSource.UUPDUMP_LIVE -> PocketColors.success
                            UupDataSource.DIRECT_MICROSOFT -> PocketColors.infoBlue
                            UupDataSource.OFFLINE_CATALOG -> PocketColors.warning
                        }
                        Surface(shape = RoundedCornerShape(6.dp), color = srcBg) {
                            Text(
                                text = activeUupSource.label,
                                modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
                                style = MaterialTheme.typography.labelSmall,
                                color = srcTint
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(7.dp)
                    ) {
                        Surface(
                            modifier = Modifier.weight(1f).bounceClick(enabled = !directMsFetching && !buildsLoading) {
                                scope.launch {
                                    directMsFetching = true
                                    val freshMs = WindowsBuildEngine.fetchLatestDirectFromMicrosoft(selectedWinFamily, selectedFeatureVer, archFilter)
                                    directMsFetching = false
                                    if (freshMs.isNotEmpty()) {
                                        groupedBuilds = GroupedWindowsBuilds(
                                            stable = freshMs.filter { it.tier == WinBuildTier.STABLE },
                                            preview = freshMs.filter { it.tier == WinBuildTier.PREVIEW },
                                            insider = freshMs.filter { it.tier == WinBuildTier.INSIDER }
                                        )
                                        Toast.makeText(context, "Fetched ${freshMs.size} live build(s) directly from Microsoft.", Toast.LENGTH_SHORT).show()
                                    } else {
                                        Toast.makeText(context, "Microsoft returned no newer build for this branch.", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            },
                            shape = RoundedCornerShape(10.dp),
                            color = PocketColors.infoBlueBg.copy(alpha = 0.7f),
                            border = BorderStroke(0.75.dp, PocketColors.infoBlue.copy(alpha = 0.7f))
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 9.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center
                            ) {
                                if (directMsFetching) {
                                    CircularProgressIndicator(modifier = Modifier.size(13.dp), strokeWidth = 1.8.dp, color = PocketColors.infoBlue)
                                } else {
                                    Icon(Icons.Rounded.CloudSync, null, tint = PocketColors.infoBlue, modifier = Modifier.size(15.dp))
                                }
                                Spacer(modifier = Modifier.width(5.dp))
                                Text("Fetch Direct MS", style = MaterialTheme.typography.labelSmall, color = PocketColors.infoBlue, maxLines = 1)
                            }
                        }

                        Surface(
                            modifier = Modifier.weight(1f).bounceClick { showLocalUupFolderPicker = true },
                            shape = RoundedCornerShape(10.dp),
                            color = PocketColors.surfaceInput,
                            border = BorderStroke(0.75.dp, PocketColors.border)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 9.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center
                            ) {
                                Icon(Icons.Rounded.FolderOpen, null, tint = PocketColors.accentSoft, modifier = Modifier.size(15.dp))
                                Spacer(modifier = Modifier.width(5.dp))
                                Text("Local UUP Folder", style = MaterialTheme.typography.labelSmall, color = PocketColors.textPrimary, maxLines = 1)
                            }
                        }
                    }

                    // Build Filters & Categorization Controls
                    Surface(
                        shape = RoundedCornerShape(11.dp),
                        color = PocketColors.surfaceInput,
                        border = BorderStroke(0.5.dp, PocketColors.border)
                    ) {
                        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 11.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth().clickable {
                                    hideOlderBuilds = !hideOlderBuilds
                                    if (hideOlderBuilds) { groupByYear = false; selectedYearFilter = null }
                                    loadGroupedBuilds(selectedWinFamily, selectedFeatureVer, archFilter, customSearchQuery, hideCumulativeUpdates, hideOlderBuilds)
                                },
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("Hide older builds (>12 months)", fontSize = 11.5.sp, fontWeight = FontWeight.Medium, color = PocketColors.textPrimary)
                                    val olderSub = if (hideOlderBuilds && groupedBuilds.hiddenOlderCount > 0) {
                                        "${groupedBuilds.hiddenOlderCount} older archive build(s) hidden"
                                    } else if (hideOlderBuilds) {
                                        "Showing recent builds from the last 12 months"
                                    } else {
                                        "Showing full multi-year build archive"
                                    }
                                    Text(olderSub, fontSize = 10.sp, color = PocketColors.textSecondary)
                                }
                                PocketSwitch(
                                    checked = hideOlderBuilds,
                                    onCheckedChange = {
                                        hideOlderBuilds = it
                                        if (it) { groupByYear = false; selectedYearFilter = null }
                                        loadGroupedBuilds(selectedWinFamily, selectedFeatureVer, archFilter, customSearchQuery, hideCumulativeUpdates, it)
                                    },
                                    activeColor = PocketColors.accent
                                )
                            }

                            PocketHairlineDivider()

                            Row(
                                modifier = Modifier.fillMaxWidth().clickable {
                                    hideCumulativeUpdates = !hideCumulativeUpdates
                                    loadGroupedBuilds(selectedWinFamily, selectedFeatureVer, archFilter, customSearchQuery, hideCumulativeUpdates, hideOlderBuilds)
                                },
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("Hide standalone Cumulative Updates (non-ISO)", fontSize = 11.5.sp, color = PocketColors.textSecondary, modifier = Modifier.weight(1f))
                                PocketSwitch(
                                    checked = hideCumulativeUpdates,
                                    onCheckedChange = {
                                        hideCumulativeUpdates = it
                                        loadGroupedBuilds(selectedWinFamily, selectedFeatureVer, archFilter, customSearchQuery, it, hideOlderBuilds)
                                    },
                                    activeColor = PocketColors.accent
                                )
                            }

                            AnimatedVisibility(visible = !hideOlderBuilds) {
                                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    PocketHairlineDivider()
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text("Categorize builds", fontSize = 11.5.sp, color = PocketColors.textSecondary)
                                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                            listOf(false to "By Channel", true to "By Year").forEach { (isYearMode, lbl) ->
                                                val isSel = groupByYear == isYearMode
                                                Surface(
                                                    modifier = Modifier.bounceClick { groupByYear = isYearMode },
                                                    shape = RoundedCornerShape(7.dp),
                                                    color = if (isSel) PocketColors.accentBg else PocketColors.surfaceCard,
                                                    border = BorderStroke(if (isSel) 1.dp else 0.5.dp, if (isSel) PocketColors.accent else PocketColors.border)
                                                ) {
                                                    Text(
                                                        text = lbl,
                                                        modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
                                                        fontSize = 11.sp,
                                                        fontWeight = if (isSel) FontWeight.SemiBold else FontWeight.Medium,
                                                        color = if (isSel) PocketColors.accentSoft else PocketColors.textSecondary
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

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
                                "No installable builds matched ${selectedWinFamily.label} ${selectedFeatureVer.label}. Try turning off \"Hide older builds\" or picking another Feature Version.",
                                modifier = Modifier.padding(14.dp),
                                style = MaterialTheme.typography.bodySmall,
                                color = PocketColors.textSecondary
                            )
                        }
                    } else {
                        if (!hideOlderBuilds && groupByYear) {
                            val yearGroups = remember(groupedBuilds) { groupedBuilds.groupedByYear() }
                            if (yearGroups.size > 1) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    val allSel = selectedYearFilter == null
                                    Surface(
                                        modifier = Modifier.bounceClick { selectedYearFilter = null },
                                        shape = RoundedCornerShape(8.dp),
                                        color = if (allSel) PocketColors.accentBg else PocketColors.surfaceInput,
                                        border = BorderStroke(if (allSel) 1.dp else 0.5.dp, if (allSel) PocketColors.accent else PocketColors.border)
                                    ) {
                                        Text(
                                            text = "All Years (${groupedBuilds.totalCount})",
                                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = if (allSel) PocketColors.accentSoft else PocketColors.textSecondary
                                        )
                                    }
                                    yearGroups.forEach { (yr, list) ->
                                        val isSel = selectedYearFilter == yr
                                        Surface(
                                            modifier = Modifier.bounceClick { selectedYearFilter = if (isSel) null else yr },
                                            shape = RoundedCornerShape(8.dp),
                                            color = if (isSel) PocketColors.accentBg else PocketColors.surfaceInput,
                                            border = BorderStroke(if (isSel) 1.dp else 0.5.dp, if (isSel) PocketColors.accent else PocketColors.border)
                                        ) {
                                            Text(
                                                text = "${if (yr > 0) yr.toString() else "Other"} (${list.size})",
                                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                                                style = MaterialTheme.typography.labelSmall,
                                                color = if (isSel) PocketColors.accentSoft else PocketColors.textSecondary
                                            )
                                        }
                                    }
                                }
                            }

                            val shownYearGroups = if (selectedYearFilter == null) yearGroups else yearGroups.filter { it.first == selectedYearFilter }
                            shownYearGroups.forEach { (yr, list) ->
                                BuildYearSection(
                                    year = yr,
                                    builds = list,
                                    onSelectBuild = { selectedBuild = it }
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

                        if (hideOlderBuilds && groupedBuilds.hiddenOlderCount > 0) {
                            Surface(
                                modifier = Modifier.fillMaxWidth().bounceClick {
                                    hideOlderBuilds = false
                                    loadGroupedBuilds(selectedWinFamily, selectedFeatureVer, archFilter, customSearchQuery, hideCumulativeUpdates, false)
                                },
                                shape = RoundedCornerShape(10.dp),
                                color = PocketColors.surfaceSubtle,
                                border = BorderStroke(0.5.dp, PocketColors.border)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 9.dp),
                                    horizontalArrangement = Arrangement.Center,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(Icons.Rounded.CalendarMonth, null, tint = PocketColors.accentSoft, modifier = Modifier.size(15.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = "Show ${groupedBuilds.hiddenOlderCount} older build(s) from previous years",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = PocketColors.accentSoft
                                    )
                                }
                            }
                        }
                    }
                } else {
                    // STEP 2: CONFIGURE SELECTED BUILD
                    val b = selectedBuild!!
                    val selectedLangCode = selectedBuildLang?.code ?: "en-us"
                    val filteredAppsForSel = remember(detailedManifest, storeAppLevel, selectedLangCode, b.arch) {
                        detailedManifest?.let { WindowsBuildEngine.filterStoreApps(it.appFiles, selectedLangCode, b.arch, storeAppLevel) }.orEmpty()
                    }
                    val selAppBytes = remember(filteredAppsForSel) { filteredAppsForSel.sumOf { it.sizeBytes } }
                    val selDlBytes = when (downloadMethod) {
                        WinDownloadMethod.DOWNLOAD_UPDATES_ONLY -> detailedManifest?.updateBytes ?: 0L
                        else -> (detailedManifest?.baseBytes ?: 3_500_000_000L) +
                            (if (includeUpdates) detailedManifest?.updateBytes ?: 0L else 0L) +
                            selAppBytes +
                            (if (integrateNetFx3) detailedManifest?.netFx3Bytes ?: 0L else 0L)
                    }
                    val estBuildOutBytes = if (downloadMethod == WinDownloadMethod.DOWNLOAD_AND_BUILD) {
                        (selDlBytes * 2L) + (if (skipWinRe) 0L else 520L * 1024L * 1024L) + 512L * 1024L * 1024L
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

                    val buildMajorNum = remember(b.build) { b.build.substringBefore('.').toIntOrNull() ?: 22000 }
                    val availableVirtualEds = remember(selectedBuildEditions, buildMajorNum) {
                        VirtualEditionTarget.availableForBaseEditions(selectedBuildEditions, buildMajorNum)
                    }
                    if (downloadMethod == WinDownloadMethod.DOWNLOAD_AND_BUILD && availableVirtualEds.isNotEmpty()) {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                PocketSectionHeader("Virtual Editions (0 MB Extra Download)")
                                Surface(shape = RoundedCornerShape(6.dp), color = PocketColors.accentBg) {
                                    Text("${selectedVirtualEditions.count { id -> availableVirtualEds.any { it.editionId == id } }} added", modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.5.dp), style = MaterialTheme.typography.labelSmall, color = PocketColors.accentSoft)
                                }
                            }
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = PocketColors.surfaceInput,
                                border = BorderStroke(0.5.dp, PocketColors.border)
                            ) {
                                Column(modifier = Modifier.fillMaxWidth()) {
                                    availableVirtualEds.forEachIndexed { idx, ve ->
                                        val isChecked = selectedVirtualEditions.contains(ve.editionId)
                                        if (idx > 0) PocketHairlineDivider()
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clickable {
                                                    selectedVirtualEditions = if (isChecked) selectedVirtualEditions - ve.editionId else selectedVirtualEditions + ve.editionId
                                                }
                                                .padding(horizontal = 12.dp, vertical = 9.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                                        ) {
                                            Icon(
                                                imageVector = if (isChecked) Icons.Rounded.CheckBox else Icons.Rounded.CheckBoxOutlineBlank,
                                                contentDescription = null,
                                                tint = if (isChecked) PocketColors.accent else PocketColors.textMuted,
                                                modifier = Modifier.size(19.dp)
                                            )
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(ve.displayNameForBuild(buildMajorNum), style = MaterialTheme.typography.labelMedium, color = PocketColors.textPrimary)
                                                Text("Cloned from ${ve.requiredBaseEdition} inside WIM", fontSize = 10.sp, color = PocketColors.textSecondary)
                                            }
                                            Icon(Icons.Rounded.Layers, null, tint = if (isChecked) PocketColors.accentSoft else PocketColors.textMuted, modifier = Modifier.size(15.dp))
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
                                            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                                    Text("Inbox Store Apps", style = MaterialTheme.typography.labelMedium, color = PocketColors.textPrimary)
                                                    Text("${filteredAppsForSel.size} pkg(s) · +${UsbGadgetEngine.formatBytes(selAppBytes)}", style = PocketColors.monoStyle, fontSize = 10.5.sp, color = PocketColors.accentSoft)
                                                }
                                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                                    WinStoreAppLevel.entries.forEach { lvl ->
                                                        val sel = storeAppLevel == lvl
                                                        Surface(
                                                            modifier = Modifier.weight(1f).height(34.dp).bounceClick { storeAppLevel = lvl },
                                                            shape = RoundedCornerShape(8.dp),
                                                            color = if (sel) PocketColors.accentBg else PocketColors.surfaceCard,
                                                            border = BorderStroke(if (sel) 1.dp else 0.5.dp, if (sel) PocketColors.accent else PocketColors.border)
                                                        ) {
                                                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                                                Text(lvl.shortLabel, style = MaterialTheme.typography.labelSmall, fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Medium, color = if (sel) PocketColors.accentSoft else PocketColors.textSecondary, maxLines = 1)
                                                            }
                                                        }
                                                    }
                                                }
                                                Text(storeAppLevel.subtitle, fontSize = 10.5.sp, color = PocketColors.textSecondary)
                                            }
                                            PocketHairlineDivider()
                                        }
                                        if (detailedManifest?.hasNetFx3 == true) {
                                            DirectUsbOptionRow("Integrate .NET Framework 3.5 (Offline)", integrateNetFx3) { integrateNetFx3 = it }; PocketHairlineDivider()
                                        }
                                        if (downloadMethod == WinDownloadMethod.DOWNLOAD_AND_BUILD) {
                                            DirectUsbOptionRow("Run component cleanup (/ResetBase)", runComponentCleanup) { runComponentCleanup = it }; PocketHairlineDivider()
                                            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                                    Text("WIM / ESD Compression", style = MaterialTheme.typography.labelMedium, color = PocketColors.textPrimary)
                                                    Text(buildCompression.label, style = PocketColors.monoStyle, fontSize = 10.5.sp, color = PocketColors.accentSoft)
                                                }
                                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                                    WinIsoCompression.entries.forEach { comp ->
                                                        val disabled = comp == WinIsoCompression.ESD && !detOs.supportsEsd
                                                        val sel = buildCompression == comp && !disabled
                                                        Surface(
                                                            modifier = Modifier.weight(1f).height(34.dp).bounceClick(enabled = !disabled) {
                                                                buildCompression = comp
                                                                if (comp == WinIsoCompression.ESD) splitWimForFat32 = false
                                                            },
                                                            shape = RoundedCornerShape(8.dp),
                                                            color = if (sel) PocketColors.accentBg else PocketColors.surfaceCard,
                                                            border = BorderStroke(if (sel) 1.dp else 0.5.dp, if (sel) PocketColors.accent else PocketColors.border)
                                                        ) {
                                                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                                                Text(comp.shortLabel, style = MaterialTheme.typography.labelSmall, fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Medium, color = if (disabled) PocketColors.textMuted else if (sel) PocketColors.accentSoft else PocketColors.textSecondary, maxLines = 1)
                                                            }
                                                        }
                                                    }
                                                }
                                                Text(buildCompression.subtitle, fontSize = 10.5.sp, color = PocketColors.textSecondary)
                                            }
                                            PocketHairlineDivider()
                                            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                                    Text("Thermal Profile (Changeable Live)", style = MaterialTheme.typography.labelMedium, color = PocketColors.textPrimary)
                                                    Text(selectedThermalProfile.label, style = PocketColors.monoStyle, fontSize = 10.5.sp, color = PocketColors.infoBlue)
                                                }
                                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                                    BuildThermalProfile.entries.forEach { prof ->
                                                        val sel = selectedThermalProfile == prof
                                                        Surface(
                                                            modifier = Modifier.weight(1f).height(34.dp).bounceClick { selectedThermalProfile = prof },
                                                            shape = RoundedCornerShape(8.dp),
                                                            color = if (sel) PocketColors.infoBlueBg else PocketColors.surfaceCard,
                                                            border = BorderStroke(if (sel) 1.dp else 0.5.dp, if (sel) PocketColors.infoBlue else PocketColors.border)
                                                        ) {
                                                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                                                Text(prof.shortLabel, style = MaterialTheme.typography.labelSmall, fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Medium, color = if (sel) PocketColors.infoBlue else PocketColors.textSecondary, maxLines = 1)
                                                            }
                                                        }
                                                    }
                                                }
                                                Text(selectedThermalProfile.subtitle, fontSize = 10.5.sp, color = PocketColors.textSecondary)
                                            }
                                            PocketHairlineDivider()
                                            if (buildCompression != WinIsoCompression.ESD && buildOutputMode != WinBuildOutputMode.DIRECT_NTFS_USB) {
                                                DirectUsbOptionRow("Split WIM for FAT32 (install*.swm ≤ 3.5 GB)", splitWimForFat32) { splitWimForFat32 = it }; PocketHairlineDivider()
                                            }
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
                                        includeUpdates = includeUpdates, includeStoreApps = storeAppLevel != WinStoreAppLevel.SECURITY_ONLY, integrateNetFx3 = integrateNetFx3, runComponentCleanup = runComponentCleanup,
                                        skipWinRe = skipWinRe, eiCfgUnlock = eiCfgUnlock, keepPackagesAfterBuild = keepPackagesAfterBuild, scheme = usbScheme, experience = experience, winUtil = winUtil, extraDriversDir = usbExtraDriversDir.trim(), virtualEditions = selectedVirtualEditions.filter { id -> availableVirtualEds.any { it.editionId == id } }, storeAppLevel = storeAppLevel, splitWimForFat32 = splitWimForFat32, thermalProfile = selectedThermalProfile, autoThermalCooldown = defaultAutoThermalCooldown
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
    if (showLocalUupFolderPicker) {
        RootFolderPickerSheet(
            title = "Select Local UUP Folder",
            subtitle = "Pick a folder containing downloaded .esd / .cab files (kept read-only)",
            initialDir = targetDir,
            onDismiss = { showLocalUupFolderPicker = false },
            onSelectFolder = { chosen ->
                showLocalUupFolderPicker = false
                localUupSelectedFolder = chosen
            }
        )
    }
    localUupSelectedFolder?.let { folderPath ->
        var localOutName by remember(folderPath) { mutableStateOf("${folderPath.substringAfterLast('/').replace(Regex("[^A-Za-z0-9._-]"), "_")}.iso") }
        var localMode by remember(folderPath) { mutableStateOf(WinBuildOutputMode.STANDARD_ISO) }
        var localComp by remember(folderPath) { mutableStateOf(WinIsoCompression.WIM) }
        var localThermal by remember(folderPath) { mutableStateOf(selectedThermalProfile) }
        var localIncludeUpdates by remember(folderPath) { mutableStateOf(true) }
        var localNetFx3 by remember(folderPath) { mutableStateOf(true) }
        var localSplitFat32 by remember(folderPath) { mutableStateOf(false) }
        var localSkipWinRe by remember(folderPath) { mutableStateOf(false) }
        var localEiCfg by remember(folderPath) { mutableStateOf(false) }
        var localCleanup by remember(folderPath) { mutableStateOf(false) }
        var localAddEnterprise by remember(folderPath) { mutableStateOf(false) }
        var localAddEducation by remember(folderPath) { mutableStateOf(false) }
        var localAddProWork by remember(folderPath) { mutableStateOf(false) }
        var localAddIotEnt by remember(folderPath) { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { localUupSelectedFolder = null },
            containerColor = PocketColors.surfaceCard,
            title = { Text("Build from Local UUP Folder", color = PocketColors.textPrimary) },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    Text(
                        "Source (read-only, never deleted): ${folderPath.replace("/data/media/0", "/sdcard")}",
                        style = PocketColors.monoStyle,
                        fontSize = 10.5.sp,
                        color = PocketColors.accentSoft
                    )
                    OutlinedTextField(
                        value = localOutName,
                        onValueChange = { localOutName = it },
                        label = { Text("Output Name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp)
                    )
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(WinBuildOutputMode.STANDARD_ISO to "ISO (.iso)", WinBuildOutputMode.DIRECT_NTFS_USB to "Direct USB (.img)").forEach { (m, lbl) ->
                            val sel = localMode == m
                            Surface(
                                modifier = Modifier.weight(1f).bounceClick {
                                    localMode = m
                                    localOutName = localOutName.substringBeforeLast('.') + if (m == WinBuildOutputMode.DIRECT_NTFS_USB) ".img" else ".iso"
                                },
                                shape = RoundedCornerShape(9.dp),
                                color = if (sel) PocketColors.accentBg else PocketColors.surfaceInput,
                                border = BorderStroke(0.75.dp, if (sel) PocketColors.accent else PocketColors.border)
                            ) {
                                Box(modifier = Modifier.padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
                                    Text(lbl, style = MaterialTheme.typography.labelSmall, color = if (sel) PocketColors.accentSoft else PocketColors.textPrimary)
                                }
                            }
                        }
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text("WIM / ESD Compression", style = MaterialTheme.typography.labelMedium, color = PocketColors.textPrimary)
                            Text(localComp.label, style = PocketColors.monoStyle, fontSize = 10.sp, color = PocketColors.accentSoft)
                        }
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            WinIsoCompression.entries.forEach { comp ->
                                val sel = localComp == comp
                                Surface(
                                    modifier = Modifier.weight(1f).height(34.dp).bounceClick {
                                        localComp = comp
                                        if (comp == WinIsoCompression.ESD) localSplitFat32 = false
                                    },
                                    shape = RoundedCornerShape(8.dp),
                                    color = if (sel) PocketColors.accentBg else PocketColors.surfaceInput,
                                    border = BorderStroke(if (sel) 1.dp else 0.5.dp, if (sel) PocketColors.accent else PocketColors.border)
                                ) {
                                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                        Text(comp.shortLabel, style = MaterialTheme.typography.labelSmall, fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Medium, color = if (sel) PocketColors.accentSoft else PocketColors.textSecondary, maxLines = 1)
                                    }
                                }
                            }
                        }
                        Text(localComp.subtitle, fontSize = 10.sp, color = PocketColors.textSecondary)
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text("Thermal Profile", style = MaterialTheme.typography.labelMedium, color = PocketColors.textPrimary)
                            Text(localThermal.label, style = PocketColors.monoStyle, fontSize = 10.sp, color = PocketColors.infoBlue)
                        }
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            BuildThermalProfile.entries.forEach { prof ->
                                val sel = localThermal == prof
                                Surface(
                                    modifier = Modifier.weight(1f).height(32.dp).bounceClick { localThermal = prof },
                                    shape = RoundedCornerShape(8.dp),
                                    color = if (sel) PocketColors.infoBlueBg else PocketColors.surfaceInput,
                                    border = BorderStroke(if (sel) 1.dp else 0.5.dp, if (sel) PocketColors.infoBlue else PocketColors.border)
                                ) {
                                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                        Text(prof.shortLabel, style = MaterialTheme.typography.labelSmall, fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Medium, color = if (sel) PocketColors.infoBlue else PocketColors.textSecondary, maxLines = 1)
                                    }
                                }
                            }
                        }
                    }
                    DirectUsbOptionRow("Include Updates (Setup DU + LCU/SSU)", localIncludeUpdates) { localIncludeUpdates = it }
                    DirectUsbOptionRow("Integrate .NET Framework 3.5", localNetFx3) { localNetFx3 = it }
                    if (localComp != WinIsoCompression.ESD && localMode != WinBuildOutputMode.DIRECT_NTFS_USB) {
                        DirectUsbOptionRow("Split WIM for FAT32 (install*.swm ≤ 3.5 GB)", localSplitFat32) { localSplitFat32 = it }
                    }
                    DirectUsbOptionRow("Run component cleanup (/ResetBase)", localCleanup) { localCleanup = it }
                    DirectUsbOptionRow("Skip WinRE Recovery (~480 MB smaller)", localSkipWinRe) { localSkipWinRe = it }
                    DirectUsbOptionRow("Unlock all editions (ei.cfg)", localEiCfg) { localEiCfg = it }
                    DirectUsbOptionRow("Add Virtual Edition: Enterprise", localAddEnterprise) { localAddEnterprise = it }
                    DirectUsbOptionRow("Add Virtual Edition: Education", localAddEducation) { localAddEducation = it }
                    DirectUsbOptionRow("Add Virtual Edition: Pro for Workstations", localAddProWork) { localAddProWork = it }
                    DirectUsbOptionRow("Add Virtual Edition: IoT Enterprise", localAddIotEnt) { localAddIotEnt = it }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val dummyBuild = WindowsChannelBuild(
                            uuid = "local-${System.currentTimeMillis()}",
                            title = "Local UUP (${folderPath.substringAfterLast('/')})",
                            build = "Local",
                            arch = "amd64",
                            createdEpoch = System.currentTimeMillis() / 1000L
                        )
                        val ves = buildList {
                            if (localAddEnterprise) add("Enterprise")
                            if (localAddEducation) add("Education")
                            if (localAddProWork) add("ProfessionalWorkstation")
                            if (localAddIotEnt) add("IoTEnterprise")
                        }
                        val cfg = WindowsChannelBuildConfig(
                            build = dummyBuild,
                            lang = WinBuildLangOption("en-us", "English (United States)"),
                            editions = listOf(WinBuildEditionOption("LOCAL", "Local UUP")),
                            compression = localComp,
                            outputMode = localMode,
                            targetDir = targetDir,
                            customOutputName = localOutName.trim(),
                            autoMount = true,
                            includeUpdates = localIncludeUpdates,
                            integrateNetFx3 = localNetFx3,
                            runComponentCleanup = localCleanup,
                            skipWinRe = localSkipWinRe,
                            eiCfgUnlock = localEiCfg,
                            scheme = usbScheme,
                            experience = experience,
                            winUtil = winUtil,
                            extraDriversDir = usbExtraDriversDir.trim(),
                            virtualEditions = ves,
                            localUupDirPath = folderPath,
                            splitWimForFat32 = localSplitFat32,
                            thermalProfile = localThermal,
                            autoThermalCooldown = defaultAutoThermalCooldown
                        )
                        localUupSelectedFolder = null
                        onDismiss()
                        onStartLocalUupBuild(folderPath, cfg)
                    },
                    enabled = localOutName.trim().isNotEmpty(),
                    colors = ButtonDefaults.buttonColors(containerColor = PocketColors.accent, contentColor = PocketColors.onAccent)
                ) {
                    Text("Start Offline Build", fontWeight = FontWeight.SemiBold)
                }
            },
            dismissButton = {
                TextButton(onClick = { localUupSelectedFolder = null }) {
                    Text("Cancel", color = PocketColors.textSecondary)
                }
            }
        )
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
private fun BuildYearSection(
    year: Int,
    builds: List<WindowsChannelBuild>,
    onSelectBuild: (WindowsChannelBuild) -> Unit
) {
    val yearTitle = if (year > 0) "$year Builds" else "Undated Builds"
    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            Icon(Icons.Rounded.CalendarMonth, null, tint = PocketColors.accentSoft, modifier = Modifier.size(14.dp))
            Text(
                text = yearTitle.uppercase(),
                fontSize = 11.5.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.8.sp,
                color = PocketColors.accentSoft
            )
            Box(modifier = Modifier.weight(1f).height(0.5.dp).background(PocketColors.border))
            Text("${builds.size}", style = PocketColors.monoStyle, color = PocketColors.textMuted)
        }
        builds.forEach { b ->
            val (badgeTint, badgeBg) = when (b.tier) {
                WinBuildTier.STABLE -> PocketColors.success to PocketColors.successBg
                WinBuildTier.PREVIEW -> PocketColors.infoBlue to PocketColors.infoBlueBg
                WinBuildTier.INSIDER -> PocketColors.violet to PocketColors.violetBg
            }
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
                            Text("Build ${b.build}${if (b.dateDisplay.isNotBlank()) " · ${b.dateDisplay}" else ""}", style = PocketColors.monoStyle, color = PocketColors.textMuted)
                        }
                    }
                    Icon(Icons.Rounded.ChevronRight, null, tint = PocketColors.accentSoft, modifier = Modifier.size(18.dp))
                }
            }
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
