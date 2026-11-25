package com.pocketboot.app

import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BookmarkAdd
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Preview
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONObject
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding

enum class WindowsOsKind(val label: String, val isModernWin11: Boolean, val supportsEsd: Boolean) {
    WIN11("Windows 11", true, true), WIN10("Windows 10", false, true),
    WIN81("Windows 8.1", false, false), WIN7("Windows 7", false, false),
    SERVER("Windows Server", false, true), UNKNOWN("Windows", true, true)
}

data class WindowsExperienceConfig(
    val bypassRequirements: Boolean = true, val bypassMicrosoftAccount: Boolean = true,
    val disableDataCollection: Boolean = true, val disableBitLocker: Boolean = true,
    val qolImprovements: Boolean = false, val disableSponsoredApps: Boolean = false,
    val classicContextMenu: Boolean = false, val skipWifiSetup: Boolean = true,
    val createLocalUser: Boolean = false, val localUsername: String = "", val localPassword: String = "",
    val skipOobe: Boolean = false, val useCa2023Bootloaders: Boolean = false,
    val applySkuSiPolicy: Boolean = false, val customPostInstallScriptPath: String = ""
) {
    val sanitizedLocalUsername: String
        get() = if (createLocalUser) WindowsCustomizationEngine.sanitizeWindowsUsername(localUsername) else ""
    /** Post-WinPE tweaks applied via Panther\unattend.xml (specialize/oobeSystem). */
    fun hasPostSetupTweaks(): Boolean = bypassMicrosoftAccount || disableDataCollection || disableBitLocker ||
        qolImprovements || disableSponsoredApps || classicContextMenu || skipWifiSetup || skipOobe ||
        sanitizedLocalUsername.isNotEmpty() || applySkuSiPolicy || customPostInstallScriptPath.isNotBlank()
    /** Tweaks that require staging PocketBoot_Specialize.cmd / SetupComplete.cmd beyond pure XML. */
    fun needsSpecializeScript(): Boolean = qolImprovements || disableSponsoredApps || classicContextMenu ||
        applySkuSiPolicy || customPostInstallScriptPath.isNotBlank()
    fun hasUnattendTweaks(): Boolean = bypassRequirements || hasPostSetupTweaks()
    fun hasAnyActive(): Boolean = hasUnattendTweaks() || useCa2023Bootloaders
    fun activeCount(): Int = listOf(bypassRequirements, bypassMicrosoftAccount, disableDataCollection, disableBitLocker,
        qolImprovements, disableSponsoredApps, classicContextMenu, skipWifiSetup, skipOobe,
        sanitizedLocalUsername.isNotEmpty(), useCa2023Bootloaders, applySkuSiPolicy, customPostInstallScriptPath.isNotBlank()).count { it }
    companion object {
        fun stock(): WindowsExperienceConfig = WindowsExperienceConfig(
            bypassRequirements = false, bypassMicrosoftAccount = false, disableDataCollection = false,
            disableBitLocker = false, qolImprovements = false, skipWifiSetup = false
        )
        fun defaultForOs(osKind: WindowsOsKind): WindowsExperienceConfig = when (osKind) {
            WindowsOsKind.WIN11, WindowsOsKind.UNKNOWN -> WindowsExperienceConfig()
            WindowsOsKind.WIN10 -> WindowsExperienceConfig(bypassRequirements = false, bypassMicrosoftAccount = true, disableDataCollection = true, disableBitLocker = true, qolImprovements = false, skipWifiSetup = true)
            WindowsOsKind.SERVER -> WindowsExperienceConfig(bypassRequirements = false, bypassMicrosoftAccount = false, disableDataCollection = true, disableBitLocker = false, qolImprovements = false, skipWifiSetup = false)
            WindowsOsKind.WIN81, WindowsOsKind.WIN7 -> stock()
        }
    }
}

data class WinUtilTweaksConfig(
    val disableActivityHistory: Boolean = false, val disableDeliveryOptimization: Boolean = false,
    val endTaskWithRightClick: Boolean = false, val disableAutoFolderDiscovery: Boolean = false,
    val disableHibernation: Boolean = false, val disableLocationTracking: Boolean = false,
    val preventDeviceCompanionApps: Boolean = false, val disableWpbt: Boolean = false,
    val servicesToManual: Boolean = false, val revertStartMenuLayout: Boolean = false,
    val darkThemeDefault: Boolean = false, val enableLongPaths: Boolean = false,
    val showHiddenFiles: Boolean = false, val disableMouseAcceleration: Boolean = false,
    val numLockOnStartup: Boolean = false, val disableStickyKeys: Boolean = false,
    val taskbarAlignLeft: Boolean = false, val hideTaskbarSearch: Boolean = false,
    val hideTaskView: Boolean = false, val showBatteryPercentage: Boolean = false,
    val disableLockScreen: Boolean = false, val disableLogonAcrylicBlur: Boolean = false,
    val verboseBsodAndLogon: Boolean = false, val alwaysShowScrollbars: Boolean = false,
    val hideSettingsHome: Boolean = false, val disableGameMode: Boolean = false,
    val disableWindowSnapping: Boolean = false, val disableS0Network: Boolean = false,
    val disableMpo: Boolean = false, val disableAndRemoveWindowsAi: Boolean = false,
    val removeOneDrive: Boolean = false, val debloatEdge: Boolean = false,
    val removeEdge: Boolean = false, val removeExplorerHomeGallery: Boolean = false,
    val setHardwareClockUtc: Boolean = false, val disableReservedStorage: Boolean = false,
    val disableBackgroundApps: Boolean = false, val disableStorageSense: Boolean = false,
    val disableSearchIndexing: Boolean = false, val blockRazerLogitechAutoInstall: Boolean = false,
    val preferIpv4OverIpv6: Boolean = false, val disableIpv6: Boolean = false,
    val disableTeredo: Boolean = false, val disableSvchostSplitting: Boolean = false,
    val visualEffectsBestPerformance: Boolean = false, val disableNotificationCenter: Boolean = false,
    val forceS3Sleep: Boolean = false, val ultimatePerformancePlan: Boolean = false
) {
    fun activeCount(): Int = listOf(disableActivityHistory, disableDeliveryOptimization, endTaskWithRightClick, disableAutoFolderDiscovery, disableHibernation, disableLocationTracking, preventDeviceCompanionApps, disableWpbt, servicesToManual, revertStartMenuLayout, darkThemeDefault, enableLongPaths, showHiddenFiles, disableMouseAcceleration, numLockOnStartup, disableStickyKeys, taskbarAlignLeft, hideTaskbarSearch, hideTaskView, showBatteryPercentage, disableLockScreen, disableLogonAcrylicBlur, verboseBsodAndLogon, alwaysShowScrollbars, hideSettingsHome, disableGameMode, disableWindowSnapping, disableS0Network, disableMpo, disableAndRemoveWindowsAi, removeOneDrive, debloatEdge, removeEdge, removeExplorerHomeGallery, setHardwareClockUtc, disableReservedStorage, disableBackgroundApps, disableStorageSense, disableSearchIndexing, blockRazerLogitechAutoInstall, preferIpv4OverIpv6, disableIpv6, disableTeredo, disableSvchostSplitting, visualEffectsBestPerformance, disableNotificationCenter, forceS3Sleep, ultimatePerformancePlan).count { it }
    fun hasAnyActive(): Boolean = activeCount() > 0
    fun toJsonString(): String {
        if (!hasAnyActive()) return ""
        return JSONObject().apply {
            if (disableActivityHistory) put("disableActivityHistory", true); if (disableDeliveryOptimization) put("disableDeliveryOptimization", true)
            if (endTaskWithRightClick) put("endTaskWithRightClick", true); if (disableAutoFolderDiscovery) put("disableAutoFolderDiscovery", true)
            if (disableHibernation) put("disableHibernation", true); if (disableLocationTracking) put("disableLocationTracking", true)
            if (preventDeviceCompanionApps) put("preventDeviceCompanionApps", true); if (disableWpbt) put("disableWpbt", true)
            if (servicesToManual) put("servicesToManual", true); if (revertStartMenuLayout) put("revertStartMenuLayout", true)
            if (darkThemeDefault) put("darkThemeDefault", true); if (enableLongPaths) put("enableLongPaths", true)
            if (showHiddenFiles) put("showHiddenFiles", true); if (disableMouseAcceleration) put("disableMouseAcceleration", true)
            if (numLockOnStartup) put("numLockOnStartup", true); if (disableStickyKeys) put("disableStickyKeys", true)
            if (taskbarAlignLeft) put("taskbarAlignLeft", true); if (hideTaskbarSearch) put("hideTaskbarSearch", true)
            if (hideTaskView) put("hideTaskView", true); if (showBatteryPercentage) put("showBatteryPercentage", true)
            if (disableLockScreen) put("disableLockScreen", true); if (disableLogonAcrylicBlur) put("disableLogonAcrylicBlur", true)
            if (verboseBsodAndLogon) put("verboseBsodAndLogon", true); if (alwaysShowScrollbars) put("alwaysShowScrollbars", true)
            if (hideSettingsHome) put("hideSettingsHome", true); if (disableGameMode) put("disableGameMode", true)
            if (disableWindowSnapping) put("disableWindowSnapping", true); if (disableS0Network) put("disableS0Network", true)
            if (disableMpo) put("disableMpo", true); if (disableAndRemoveWindowsAi) put("disableAndRemoveWindowsAi", true)
            if (removeOneDrive) put("removeOneDrive", true); if (debloatEdge) put("debloatEdge", true)
            if (removeEdge) put("removeEdge", true); if (removeExplorerHomeGallery) put("removeExplorerHomeGallery", true)
            if (setHardwareClockUtc) put("setHardwareClockUtc", true); if (disableReservedStorage) put("disableReservedStorage", true)
            if (disableBackgroundApps) put("disableBackgroundApps", true); if (disableStorageSense) put("disableStorageSense", true)
            if (disableSearchIndexing) put("disableSearchIndexing", true); if (blockRazerLogitechAutoInstall) put("blockRazerLogitechAutoInstall", true)
            if (preferIpv4OverIpv6) put("preferIpv4OverIpv6", true); if (disableIpv6) put("disableIpv6", true)
            if (disableTeredo) put("disableTeredo", true); if (disableSvchostSplitting) put("disableSvchostSplitting", true)
            if (visualEffectsBestPerformance) put("visualEffectsBestPerformance", true); if (disableNotificationCenter) put("disableNotificationCenter", true)
            if (forceS3Sleep) put("forceS3Sleep", true); if (ultimatePerformancePlan) put("ultimatePerformancePlan", true)
        }.toString()
    }
    companion object {
        fun fromJsonString(raw: String): WinUtilTweaksConfig {
            if (raw.isBlank()) return WinUtilTweaksConfig()
            return runCatching {
                val o = JSONObject(raw); val disIpv6 = o.optBoolean("disableIpv6", false); val fS3 = o.optBoolean("forceS3Sleep", false); val remEdge = o.optBoolean("removeEdge", false)
                WinUtilTweaksConfig(o.optBoolean("disableActivityHistory", false), o.optBoolean("disableDeliveryOptimization", false), o.optBoolean("endTaskWithRightClick", false), o.optBoolean("disableAutoFolderDiscovery", false), o.optBoolean("disableHibernation", false), o.optBoolean("disableLocationTracking", false), o.optBoolean("preventDeviceCompanionApps", false), o.optBoolean("disableWpbt", false), o.optBoolean("servicesToManual", false), o.optBoolean("revertStartMenuLayout", false), o.optBoolean("darkThemeDefault", false), o.optBoolean("enableLongPaths", false), o.optBoolean("showHiddenFiles", false), o.optBoolean("disableMouseAcceleration", false), o.optBoolean("numLockOnStartup", false), o.optBoolean("disableStickyKeys", false), o.optBoolean("taskbarAlignLeft", false), o.optBoolean("hideTaskbarSearch", false), o.optBoolean("hideTaskView", false), o.optBoolean("showBatteryPercentage", false), o.optBoolean("disableLockScreen", false), o.optBoolean("disableLogonAcrylicBlur", false), o.optBoolean("verboseBsodAndLogon", false), o.optBoolean("alwaysShowScrollbars", false), o.optBoolean("hideSettingsHome", false), o.optBoolean("disableGameMode", false), o.optBoolean("disableWindowSnapping", false), !fS3 && o.optBoolean("disableS0Network", false), o.optBoolean("disableMpo", false), o.optBoolean("disableAndRemoveWindowsAi", false), o.optBoolean("removeOneDrive", false), !remEdge && o.optBoolean("debloatEdge", false), remEdge, o.optBoolean("removeExplorerHomeGallery", false), o.optBoolean("setHardwareClockUtc", false), o.optBoolean("disableReservedStorage", false), o.optBoolean("disableBackgroundApps", false), o.optBoolean("disableStorageSense", false), o.optBoolean("disableSearchIndexing", false), o.optBoolean("blockRazerLogitechAutoInstall", false), !disIpv6 && o.optBoolean("preferIpv4OverIpv6", false), disIpv6, o.optBoolean("disableTeredo", false), o.optBoolean("disableSvchostSplitting", false), o.optBoolean("visualEffectsBestPerformance", false), o.optBoolean("disableNotificationCenter", false), fS3, o.optBoolean("ultimatePerformancePlan", false))
            }.getOrDefault(WinUtilTweaksConfig())
        }
        fun desktopPreset() = WinUtilTweaksConfig(disableActivityHistory = true, disableDeliveryOptimization = true, endTaskWithRightClick = true, disableAutoFolderDiscovery = true, disableHibernation = true, disableLocationTracking = true, preventDeviceCompanionApps = true, disableWpbt = true, servicesToManual = true, darkThemeDefault = true, enableLongPaths = true, disableMouseAcceleration = true, numLockOnStartup = true, disableStickyKeys = true, hideSettingsHome = true, ultimatePerformancePlan = true)
        fun laptopSafePreset() = WinUtilTweaksConfig(disableActivityHistory = true, disableDeliveryOptimization = true, endTaskWithRightClick = true, disableAutoFolderDiscovery = true, disableLocationTracking = true, preventDeviceCompanionApps = true, disableWpbt = true, servicesToManual = true, darkThemeDefault = true, enableLongPaths = true, disableStickyKeys = true, showBatteryPercentage = true, hideSettingsHome = true, disableS0Network = true)
    }
}

object WindowsCustomizationEngine {

    private val RESERVED_WINDOWS_USERNAMES = setOf(
        "administrator", "guest", "defaultaccount", "wdagutilityaccount",
        "system", "localservice", "networkservice", "none", "users", "everyone",
        "con", "prn", "aux", "nul",
        "com1", "com2", "com3", "com4", "com5", "com6", "com7", "com8", "com9",
        "lpt1", "lpt2", "lpt3", "lpt4", "lpt5", "lpt6", "lpt7", "lpt8", "lpt9"
    )

    /**
     * Sanitizes a Windows local account username (matches official Rufus #2991 rules):
     * strips illegal characters including '&', trims leading/trailing dots & spaces, clamps to 20 chars,
     * and rejects reserved Windows built-in / DOS device names so oobeSystem never fails.
     */
    private const val ILLEGAL_USERNAME_CHARS = "/\\[]:;|=,+*?<>\"&@"

    fun filterUsernameInput(raw: String): String = raw
        .filter { ch -> ch.code >= 0x20 && ch !in ILLEGAL_USERNAME_CHARS }
        .take(20)

    fun sanitizeWindowsUsername(raw: String): String {
        val cleaned = filterUsernameInput(raw)
            .trim()
            .trim('.')
            .trim()
            .take(20)
            .trimEnd('.', ' ')
        if (cleaned.isEmpty()) return ""
        if (cleaned.lowercase() in RESERVED_WINDOWS_USERNAMES) return ""
        return cleaned
    }
    fun xmlEscape(value: String): String = value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;")
    fun detectWindowsOsKind(nameOrLabel: String): WindowsOsKind {
        val low = nameOrLabel.lowercase()
        return when {
            low.contains("server") -> WindowsOsKind.SERVER
            low.contains("win11") || low.contains("win 11") || low.contains("windows 11") || low.contains("windows_11") || low.contains("26h2") || low.contains("26h1") || low.contains("25h2") || low.contains("24h2") || low.contains("23h2") || low.contains("22621") || low.contains("22631") || low.contains("26100") || low.contains("26200") || low.contains("26300") || low.contains("28000") || low.contains("tiny11") -> WindowsOsKind.WIN11
            low.contains("win10") || low.contains("win 10") || low.contains("windows 10") || low.contains("windows_10") || low.contains("19045") || low.contains("19044") || low.contains("19043") || low.contains("18363") || low.contains("tiny10") -> WindowsOsKind.WIN10
            low.contains("win8") || low.contains("win 8") || low.contains("windows 8") || low.contains("8.1") || low.contains("9600") -> WindowsOsKind.WIN81
            low.contains("win7") || low.contains("win 7") || low.contains("windows 7") || low.contains("7601") || low.contains("7600") -> WindowsOsKind.WIN7
            else -> WindowsOsKind.UNKNOWN
        }
    }
    fun detectProcessorArchitecture(nameOrLabel: String): String {
        val low = nameOrLabel.lowercase()
        return when {
            low.contains("arm64") || low.contains("aarch64") -> "arm64"
            (low.contains("x86") && !low.contains("x86_64") && !low.contains("x86-64")) || low.contains("32bit") || low.contains("32-bit") || low.contains("i386") || low.contains("i686") -> "x86"
            else -> "amd64"
        }
    }

    fun buildSpecializeScript(experience: WindowsExperienceConfig, winUtil: WinUtilTweaksConfig): String {
        val sb = StringBuilder()
        sb.appendLine("@echo off")
        sb.appendLine("setlocal EnableExtensions EnableDelayedExpansion")
        sb.appendLine("cd /d C:\\Windows\\System32 >nul 2>&1")
        sb.appendLine("mkdir \"C:\\Windows\\Logs\" >nul 2>&1")
        sb.appendLine("set \"PB_LOG=C:\\Windows\\Logs\\PocketBoot_Setup.log\"")
        sb.appendLine("set \"PB_FLAG=C:\\Windows\\Setup\\Scripts\\.pb_spec_done\"")
        sb.appendLine("if exist \"%PB_FLAG%\" exit /b 0")
        sb.appendLine("mkdir \"C:\\Windows\\Setup\\Scripts\" >nul 2>&1")
        sb.appendLine("echo [PocketBoot] Specialize configuration started...")
        sb.appendLine("echo [PocketBoot v4.6.6] Specialize started %DATE% %TIME% >> \"%PB_LOG%\"")
        sb.appendLine("set \"PB_BUILD=0\"")
        sb.appendLine("for /f \"tokens=3\" %%B in ('reg query \"HKLM\\SOFTWARE\\Microsoft\\Windows NT\\CurrentVersion\" /v CurrentBuildNumber 2^>nul ^| findstr /i CurrentBuildNumber') do set \"PB_BUILD=%%B\"")
        if (experience.bypassMicrosoftAccount) sb.appendLine("reg add \"HKLM\\SOFTWARE\\Microsoft\\Windows\\CurrentVersion\\OOBE\" /v BypassNRO /t REG_DWORD /d 1 /f >> \"%PB_LOG%\" 2>&1")
        if (experience.disableBitLocker) sb.appendLine("reg add \"HKLM\\SYSTEM\\CurrentControlSet\\Control\\BitLocker\" /v PreventDeviceEncryption /t REG_DWORD /d 1 /f >> \"%PB_LOG%\" 2>&1")
        if (experience.disableDataCollection) {
            sb.appendLine("reg add \"HKLM\\SOFTWARE\\Policies\\Microsoft\\Windows\\DataCollection\" /v AllowTelemetry /t REG_DWORD /d 0 /f >> \"%PB_LOG%\" 2>&1")
            sb.appendLine("reg add \"HKLM\\SOFTWARE\\Microsoft\\Windows\\CurrentVersion\\Policies\\DataCollection\" /v AllowTelemetry /t REG_DWORD /d 0 /f >> \"%PB_LOG%\" 2>&1")
        }
        if (experience.disableSponsoredApps || experience.qolImprovements) {
            sb.appendLine("reg add \"HKLM\\SOFTWARE\\Policies\\Microsoft\\Windows\\CloudContent\" /v DisableWindowsConsumerFeatures /t REG_DWORD /d 1 /f >> \"%PB_LOG%\" 2>&1")
            sb.appendLine("reg add \"HKLM\\SOFTWARE\\Policies\\Microsoft\\Windows\\CloudContent\" /v DisableSoftLanding /t REG_DWORD /d 1 /f >> \"%PB_LOG%\" 2>&1")
        }
        if (experience.qolImprovements) {
            sb.appendLine("reg add \"HKLM\\SYSTEM\\CurrentControlSet\\Control\\Session Manager\\Power\" /v HiberbootEnabled /t REG_DWORD /d 0 /f >> \"%PB_LOG%\" 2>&1")
            sb.appendLine("reg add \"HKLM\\SOFTWARE\\Policies\\Microsoft\\Windows\\WindowsCopilot\" /v TurnOffWindowsCopilot /t REG_DWORD /d 1 /f >> \"%PB_LOG%\" 2>&1")
            sb.appendLine("reg add \"HKLM\\SOFTWARE\\Policies\\Microsoft\\Windows\\OneDrive\" /v DisableFileSyncNGSC /t REG_DWORD /d 1 /f >> \"%PB_LOG%\" 2>&1")
            sb.appendLine("reg add \"HKLM\\SOFTWARE\\Microsoft\\Windows\\CurrentVersion\\Communications\" /v ConfigureChatAutoInstall /t REG_DWORD /d 0 /f >> \"%PB_LOG%\" 2>&1")
            sb.appendLine("reg add \"HKLM\\SOFTWARE\\Microsoft\\WindowsUpdate\\Orchestrator\\UScheduler_Oobe\\OutlookUpdate\" /v workCompleted /t REG_DWORD /d 1 /f >> \"%PB_LOG%\" 2>&1")
            sb.appendLine("reg add \"HKLM\\SOFTWARE\\Policies\\Microsoft\\Dsh\" /v AllowNewsAndInterests /t REG_DWORD /d 0 /f >> \"%PB_LOG%\" 2>&1")
            sb.appendLine("reg add \"HKLM\\SOFTWARE\\Policies\\Microsoft\\Windows\\Windows Search\" /v EnableDynamicContentInWSB /t REG_DWORD /d 0 /f >> \"%PB_LOG%\" 2>&1")
            sb.appendLine("reg add \"HKLM\\SOFTWARE\\Policies\\Microsoft\\Windows\\WindowsUpdate\\AU\" /v NoAutoRebootWithLoggedOnUsers /t REG_DWORD /d 1 /f >> \"%PB_LOG%\" 2>&1")
        }
        if (experience.applySkuSiPolicy) sb.appendLine("if exist \"C:\\Windows\\System32\\SecureBootUpdates\\SkuSiPolicy.p7b\" (mountvol S: /S >> \"%PB_LOG%\" 2>&1 & if exist \"S:\\EFI\\Microsoft\\Boot\" copy /y \"C:\\Windows\\System32\\SecureBootUpdates\\SkuSiPolicy.p7b\" \"S:\\EFI\\Microsoft\\Boot\\SkuSiPolicy.p7b\" >> \"%PB_LOG%\" 2>&1 & mountvol S: /D >> \"%PB_LOG%\" 2>&1)")
        if (winUtil.disableActivityHistory) {
            sb.appendLine("reg add \"HKLM\\SOFTWARE\\Policies\\Microsoft\\Windows\\System\" /v EnableActivityFeed /t REG_DWORD /d 0 /f >> \"%PB_LOG%\" 2>&1")
            sb.appendLine("reg add \"HKLM\\SOFTWARE\\Policies\\Microsoft\\Windows\\System\" /v PublishUserActivities /t REG_DWORD /d 0 /f >> \"%PB_LOG%\" 2>&1")
            sb.appendLine("reg add \"HKLM\\SOFTWARE\\Policies\\Microsoft\\Windows\\System\" /v UploadUserActivities /t REG_DWORD /d 0 /f >> \"%PB_LOG%\" 2>&1")
        }
        if (winUtil.disableDeliveryOptimization) sb.appendLine("reg add \"HKLM\\SOFTWARE\\Policies\\Microsoft\\Windows\\DeliveryOptimization\" /v DODownloadMode /t REG_DWORD /d 0 /f >> \"%PB_LOG%\" 2>&1")
        if (winUtil.disableHibernation) sb.appendLine("powercfg /h off >> \"%PB_LOG%\" 2>&1")
        if (winUtil.disableLocationTracking) {
            sb.appendLine("reg add \"HKLM\\SOFTWARE\\Microsoft\\Windows\\CurrentVersion\\CapabilityAccessManager\\ConsentStore\\location\" /v Value /t REG_SZ /d Deny /f >> \"%PB_LOG%\" 2>&1")
            sb.appendLine("sc config lfsvc start= disabled >> \"%PB_LOG%\" 2>&1")
        }
        if (winUtil.preventDeviceCompanionApps) sb.appendLine("reg add \"HKLM\\SOFTWARE\\Microsoft\\Windows\\CurrentVersion\\Device Metadata\" /v PreventDeviceMetadataFromNetwork /t REG_DWORD /d 1 /f >> \"%PB_LOG%\" 2>&1")
        if (winUtil.disableWpbt) sb.appendLine("reg add \"HKLM\\SYSTEM\\CurrentControlSet\\Control\\Session Manager\" /v DisableWpbtExecution /t REG_DWORD /d 1 /f >> \"%PB_LOG%\" 2>&1")
        if (winUtil.servicesToManual) sb.appendLine("for %%S in (DiagTrack dmwappushservice MapsBroker WerSvc Fax RemoteRegistry RetailDemo TrkWks WMPNetworkSvc) do sc config %%S start= demand >> \"%PB_LOG%\" 2>&1")
        if (winUtil.enableLongPaths) sb.appendLine("reg add \"HKLM\\SYSTEM\\CurrentControlSet\\Control\\FileSystem\" /v LongPathsEnabled /t REG_DWORD /d 1 /f >> \"%PB_LOG%\" 2>&1")
        if (winUtil.disableLockScreen) sb.appendLine("reg add \"HKLM\\SOFTWARE\\Policies\\Microsoft\\Windows\\Personalization\" /v NoLockScreen /t REG_DWORD /d 1 /f >> \"%PB_LOG%\" 2>&1")
        if (winUtil.disableLogonAcrylicBlur) sb.appendLine("reg add \"HKLM\\SOFTWARE\\Policies\\Microsoft\\Windows\\System\" /v DisableAcrylicBackgroundOnLogon /t REG_DWORD /d 1 /f >> \"%PB_LOG%\" 2>&1")
        if (winUtil.verboseBsodAndLogon) {
            sb.appendLine("reg add \"HKLM\\SOFTWARE\\Microsoft\\Windows\\CurrentVersion\\Policies\\System\" /v VerboseStatus /t REG_DWORD /d 1 /f >> \"%PB_LOG%\" 2>&1")
            sb.appendLine("reg add \"HKLM\\SYSTEM\\CurrentControlSet\\Control\\CrashControl\" /v DisplayParameters /t REG_DWORD /d 1 /f >> \"%PB_LOG%\" 2>&1")
        }
        if (winUtil.hideSettingsHome) sb.appendLine("reg add \"HKLM\\SOFTWARE\\Microsoft\\Windows\\CurrentVersion\\Policies\\Explorer\" /v SettingsPageVisibility /t REG_SZ /d \"hide:home\" /f >> \"%PB_LOG%\" 2>&1")
        if (winUtil.disableS0Network && !winUtil.forceS3Sleep) {
            sb.appendLine("reg add \"HKLM\\SOFTWARE\\Policies\\Microsoft\\Power\\PowerSettings\\f15576e8-98b7-4186-b944-eafa664402d9\" /v ACSettingIndex /t REG_DWORD /d 0 /f >> \"%PB_LOG%\" 2>&1")
            sb.appendLine("reg add \"HKLM\\SOFTWARE\\Policies\\Microsoft\\Power\\PowerSettings\\f15576e8-98b7-4186-b944-eafa664402d9\" /v DCSettingIndex /t REG_DWORD /d 0 /f >> \"%PB_LOG%\" 2>&1")
        }
        if (winUtil.forceS3Sleep) sb.appendLine("reg add \"HKLM\\SYSTEM\\CurrentControlSet\\Control\\Power\" /v PlatformAoAcOverride /t REG_DWORD /d 0 /f >> \"%PB_LOG%\" 2>&1")
        if (winUtil.disableMpo) sb.appendLine("reg add \"HKLM\\SOFTWARE\\Microsoft\\Windows\\Dwm\" /v OverlayTestMode /t REG_DWORD /d 5 /f >> \"%PB_LOG%\" 2>&1")
        if (winUtil.disableAndRemoveWindowsAi) {
            sb.appendLine("reg add \"HKLM\\SOFTWARE\\Policies\\Microsoft\\Windows\\WindowsAI\" /v DisableAIDataAnalysis /t REG_DWORD /d 1 /f >> \"%PB_LOG%\" 2>&1")
            sb.appendLine("reg add \"HKLM\\SOFTWARE\\Policies\\Microsoft\\Windows\\WindowsAI\" /v TurnOffSavingSnapshots /t REG_DWORD /d 1 /f >> \"%PB_LOG%\" 2>&1")
            sb.appendLine("reg add \"HKLM\\SOFTWARE\\Policies\\Microsoft\\Windows\\WindowsCopilot\" /v TurnOffWindowsCopilot /t REG_DWORD /d 1 /f >> \"%PB_LOG%\" 2>&1")
            sb.appendLine("if !PB_BUILD! GEQ 22000 (")
            sb.appendLine("  dism /Online /Disable-Feature /FeatureName:Recall /NoRestart >> \"%PB_LOG%\" 2>&1")
            sb.appendLine("  powershell -NoProfile -ExecutionPolicy Bypass -Command \"Get-AppxProvisionedPackage -Online | Where-Object { \$_.DisplayName -match 'Copilot|Recall' } | ForEach-Object { Remove-AppxProvisionedPackage -Online -PackageName \$_.PackageName -ErrorAction SilentlyContinue }\" >> \"%PB_LOG%\" 2>&1")
            sb.appendLine(")")
        }
        if (winUtil.debloatEdge && !winUtil.removeEdge) {
            sb.appendLine("reg add \"HKLM\\SOFTWARE\\Policies\\Microsoft\\Edge\" /v HubsSidebarEnabled /t REG_DWORD /d 0 /f >> \"%PB_LOG%\" 2>&1")
            sb.appendLine("reg add \"HKLM\\SOFTWARE\\Policies\\Microsoft\\Edge\" /v EdgeShoppingAssistantEnabled /t REG_DWORD /d 0 /f >> \"%PB_LOG%\" 2>&1")
            sb.appendLine("reg add \"HKLM\\SOFTWARE\\Policies\\Microsoft\\Edge\" /v StartupBoostEnabled /t REG_DWORD /d 0 /f >> \"%PB_LOG%\" 2>&1")
            sb.appendLine("reg add \"HKLM\\SOFTWARE\\Policies\\Microsoft\\Edge\" /v BackgroundModeEnabled /t REG_DWORD /d 0 /f >> \"%PB_LOG%\" 2>&1")
            sb.appendLine("reg add \"HKLM\\SOFTWARE\\Policies\\Microsoft\\Edge\" /v ShowMicrosoftRewards /t REG_DWORD /d 0 /f >> \"%PB_LOG%\" 2>&1")
        }
        if (winUtil.removeEdge) {
            sb.appendLine("reg add \"HKLM\\SOFTWARE\\Microsoft\\EdgeUpdate\" /v DoNotUpdateToEdgeWithChromium /t REG_DWORD /d 1 /f >> \"%PB_LOG%\" 2>&1")
            sb.appendLine("for /d %%D in (\"C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\*\") do if exist \"%%D\\Installer\\setup.exe\" \"%%D\\Installer\\setup.exe\" --uninstall --system-level --verbose-logging --force-uninstall >> \"%PB_LOG%\" 2>&1")
        }
        if (winUtil.removeExplorerHomeGallery) sb.appendLine("if !PB_BUILD! GEQ 22000 (reg delete \"HKLM\\SOFTWARE\\Microsoft\\Windows\\CurrentVersion\\Explorer\\Desktop\\NameSpace\\{e88865ea-0e1c-4e20-9aa6-edcd0212c87c}\" /f >> \"%PB_LOG%\" 2>&1 & reg delete \"HKLM\\SOFTWARE\\Microsoft\\Windows\\CurrentVersion\\Explorer\\Desktop\\NameSpace\\{f874310e-b6b7-47dc-bc84-b9e6b38f5903}\" /f >> \"%PB_LOG%\" 2>&1)")
        if (winUtil.setHardwareClockUtc) sb.appendLine("reg add \"HKLM\\SYSTEM\\CurrentControlSet\\Control\\TimeZoneInformation\" /v RealTimeIsUniversal /t REG_DWORD /d 1 /f >> \"%PB_LOG%\" 2>&1")
        if (winUtil.disableReservedStorage) sb.appendLine("dism /Online /Set-ReservedStorageState /State:Disabled >> \"%PB_LOG%\" 2>&1")
        if (winUtil.disableBackgroundApps) sb.appendLine("reg add \"HKLM\\SOFTWARE\\Policies\\Microsoft\\Windows\\AppPrivacy\" /v LetAppsRunInBackground /t REG_DWORD /d 2 /f >> \"%PB_LOG%\" 2>&1")
        if (winUtil.disableStorageSense) sb.appendLine("reg add \"HKLM\\SOFTWARE\\Policies\\Microsoft\\Windows\\StorageSense\" /v AllowStorageSenseGlobal /t REG_DWORD /d 0 /f >> \"%PB_LOG%\" 2>&1")
        if (winUtil.disableSearchIndexing) sb.appendLine("sc config WSearch start= disabled >> \"%PB_LOG%\" 2>&1")
        if (winUtil.blockRazerLogitechAutoInstall) {
            sb.appendLine("reg add \"HKLM\\SOFTWARE\\Policies\\Microsoft\\Windows\\DriverSearching\" /v SearchOrderConfig /t REG_DWORD /d 0 /f >> \"%PB_LOG%\" 2>&1")
            sb.appendLine("reg add \"HKLM\\SOFTWARE\\Microsoft\\Windows\\CurrentVersion\\Device Metadata\" /v PreventDeviceMetadataFromNetwork /t REG_DWORD /d 1 /f >> \"%PB_LOG%\" 2>&1")
        }
        if (winUtil.disableIpv6) sb.appendLine("reg add \"HKLM\\SYSTEM\\CurrentControlSet\\Services\\Tcpip6\\Parameters\" /v DisabledComponents /t REG_DWORD /d 255 /f >> \"%PB_LOG%\" 2>&1")
        else if (winUtil.preferIpv4OverIpv6) sb.appendLine("reg add \"HKLM\\SYSTEM\\CurrentControlSet\\Services\\Tcpip6\\Parameters\" /v DisabledComponents /t REG_DWORD /d 32 /f >> \"%PB_LOG%\" 2>&1")
        if (winUtil.disableTeredo) sb.appendLine("netsh interface teredo set state disabled >> \"%PB_LOG%\" 2>&1")
        if (winUtil.disableSvchostSplitting) sb.appendLine("reg add \"HKLM\\SYSTEM\\CurrentControlSet\\Control\" /v SvcHostSplitThresholdInKB /t REG_DWORD /d 67108864 /f >> \"%PB_LOG%\" 2>&1")
        if (winUtil.ultimatePerformancePlan) {
            sb.appendLine("powercfg -duplicatescheme e9a42b02-d5df-448d-aa00-03f14749eb61 >> \"%PB_LOG%\" 2>&1")
            sb.appendLine("for /f \"tokens=4\" %%G in ('powercfg /list ^| findstr /i \"Ultimate Performance\"') do powercfg /setactive %%G >> \"%PB_LOG%\" 2>&1")
        }
        if (winUtil.removeOneDrive) {
            sb.appendLine("if exist \"C:\\Windows\\SysWOW64\\OneDriveSetup.exe\" \"C:\\Windows\\SysWOW64\\OneDriveSetup.exe\" /uninstall >> \"%PB_LOG%\" 2>&1")
            sb.appendLine("if exist \"C:\\Windows\\System32\\OneDriveSetup.exe\" \"C:\\Windows\\System32\\OneDriveSetup.exe\" /uninstall >> \"%PB_LOG%\" 2>&1")
        }

        val hasDefaultUserTweaks = experience.qolImprovements || experience.disableSponsoredApps || experience.classicContextMenu ||
            winUtil.endTaskWithRightClick || winUtil.disableAutoFolderDiscovery || winUtil.revertStartMenuLayout || winUtil.darkThemeDefault ||
            winUtil.showHiddenFiles || winUtil.disableMouseAcceleration || winUtil.numLockOnStartup || winUtil.disableStickyKeys ||
            winUtil.taskbarAlignLeft || winUtil.hideTaskbarSearch || winUtil.hideTaskView || winUtil.showBatteryPercentage ||
            winUtil.alwaysShowScrollbars || winUtil.disableGameMode || winUtil.disableWindowSnapping ||
            winUtil.visualEffectsBestPerformance || winUtil.disableNotificationCenter || winUtil.removeOneDrive
        if (hasDefaultUserTweaks) {
            sb.appendLine("reg load \"HKU\\PB_DEF\" \"C:\\Users\\Default\\NTUSER.DAT\" >> \"%PB_LOG%\" 2>&1")
            sb.appendLine("if !ERRORLEVEL! EQU 0 (")
            if (experience.qolImprovements || winUtil.removeOneDrive) sb.appendLine("  reg delete \"HKU\\PB_DEF\\Software\\Microsoft\\Windows\\CurrentVersion\\Run\" /v OneDriveSetup /f >> \"%PB_LOG%\" 2>&1")
            if (experience.qolImprovements || experience.disableSponsoredApps) {
                sb.appendLine("  reg add \"HKU\\PB_DEF\\Software\\Microsoft\\Windows\\CurrentVersion\\ContentDeliveryManager\" /v SilentInstalledAppsEnabled /t REG_DWORD /d 0 /f >> \"%PB_LOG%\" 2>&1")
                sb.appendLine("  reg add \"HKU\\PB_DEF\\Software\\Microsoft\\Windows\\CurrentVersion\\ContentDeliveryManager\" /v SubscribedContent-338388Enabled /t REG_DWORD /d 0 /f >> \"%PB_LOG%\" 2>&1")
                sb.appendLine("  reg add \"HKU\\PB_DEF\\Software\\Microsoft\\Windows\\CurrentVersion\\ContentDeliveryManager\" /v SubscribedContent-338389Enabled /t REG_DWORD /d 0 /f >> \"%PB_LOG%\" 2>&1")
                sb.appendLine("  reg add \"HKU\\PB_DEF\\Software\\Microsoft\\Windows\\CurrentVersion\\UserProfileEngagement\" /v ScoobeSystemSettingEnabled /t REG_DWORD /d 0 /f >> \"%PB_LOG%\" 2>&1")
            }
            if (experience.qolImprovements) {
                sb.appendLine("  reg add \"HKU\\PB_DEF\\Software\\Microsoft\\Windows\\CurrentVersion\\Explorer\\Advanced\" /v HideFileExt /t REG_DWORD /d 0 /f >> \"%PB_LOG%\" 2>&1")
                sb.appendLine("  reg add \"HKU\\PB_DEF\\Software\\Microsoft\\Windows\\CurrentVersion\\Explorer\\Advanced\" /v LaunchTo /t REG_DWORD /d 1 /f >> \"%PB_LOG%\" 2>&1")
                sb.appendLine("  reg add \"HKU\\PB_DEF\\Software\\Microsoft\\Windows\\CurrentVersion\\Explorer\\Advanced\" /v ShowCopilotButton /t REG_DWORD /d 0 /f >> \"%PB_LOG%\" 2>&1")
                sb.appendLine("  reg add \"HKU\\PB_DEF\\Software\\Microsoft\\Windows\\CurrentVersion\\Explorer\\Advanced\" /v TaskbarMn /t REG_DWORD /d 0 /f >> \"%PB_LOG%\" 2>&1")
                sb.appendLine("  reg add \"HKU\\PB_DEF\\Software\\Microsoft\\Windows\\CurrentVersion\\Explorer\\Advanced\" /v TaskbarDa /t REG_DWORD /d 0 /f >> \"%PB_LOG%\" 2>&1")
                sb.appendLine("  reg add \"HKU\\PB_DEF\\Software\\Microsoft\\Windows\\CurrentVersion\\SearchSettings\" /v IsDynamicSearchBoxEnabled /t REG_DWORD /d 0 /f >> \"%PB_LOG%\" 2>&1")
                sb.appendLine("  reg add \"HKU\\PB_DEF\\Software\\Policies\\Microsoft\\Windows\\Explorer\" /v DisableSearchBoxSuggestions /t REG_DWORD /d 1 /f >> \"%PB_LOG%\" 2>&1")
            }
            if (experience.classicContextMenu) sb.appendLine("  if !PB_BUILD! GEQ 22000 reg add \"HKU\\PB_DEF\\Software\\Classes\\CLSID\\{86ca1aa0-34aa-4e8b-a509-50c905bae2a2}\\InprocServer32\" /f /ve >> \"%PB_LOG%\" 2>&1")
            if (winUtil.endTaskWithRightClick) sb.appendLine("  if !PB_BUILD! GEQ 22000 reg add \"HKU\\PB_DEF\\Software\\Microsoft\\Windows\\CurrentVersion\\Explorer\\Advanced\\TaskbarDeveloperSettings\" /v TaskbarEndTask /t REG_DWORD /d 1 /f >> \"%PB_LOG%\" 2>&1")
            if (winUtil.disableAutoFolderDiscovery) sb.appendLine("  reg add \"HKU\\PB_DEF\\Software\\Classes\\Local Settings\\Software\\Microsoft\\Windows\\Shell\\Bags\\AllFolders\\Shell\" /v FolderType /t REG_SZ /d NotSpecified /f >> \"%PB_LOG%\" 2>&1")
            if (winUtil.revertStartMenuLayout) sb.appendLine("  if !PB_BUILD! GEQ 22000 reg add \"HKU\\PB_DEF\\Software\\Microsoft\\Windows\\CurrentVersion\\Explorer\\Advanced\" /v Start_Layout /t REG_DWORD /d 1 /f >> \"%PB_LOG%\" 2>&1")
            if (winUtil.darkThemeDefault) {
                sb.appendLine("  reg add \"HKU\\PB_DEF\\Software\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize\" /v AppsUseLightTheme /t REG_DWORD /d 0 /f >> \"%PB_LOG%\" 2>&1")
                sb.appendLine("  reg add \"HKU\\PB_DEF\\Software\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize\" /v SystemUsesLightTheme /t REG_DWORD /d 0 /f >> \"%PB_LOG%\" 2>&1")
            }
            if (winUtil.showHiddenFiles) sb.appendLine("  reg add \"HKU\\PB_DEF\\Software\\Microsoft\\Windows\\CurrentVersion\\Explorer\\Advanced\" /v Hidden /t REG_DWORD /d 1 /f >> \"%PB_LOG%\" 2>&1")
            if (winUtil.disableMouseAcceleration) {
                sb.appendLine("  reg add \"HKU\\PB_DEF\\Control Panel\\Mouse\" /v MouseSpeed /t REG_SZ /d 0 /f >> \"%PB_LOG%\" 2>&1")
                sb.appendLine("  reg add \"HKU\\PB_DEF\\Control Panel\\Mouse\" /v MouseThreshold1 /t REG_SZ /d 0 /f >> \"%PB_LOG%\" 2>&1")
                sb.appendLine("  reg add \"HKU\\PB_DEF\\Control Panel\\Mouse\" /v MouseThreshold2 /t REG_SZ /d 0 /f >> \"%PB_LOG%\" 2>&1")
            }
            if (winUtil.numLockOnStartup) {
                sb.appendLine("  reg add \"HKU\\PB_DEF\\Control Panel\\Keyboard\" /v InitialKeyboardIndicators /t REG_SZ /d 2 /f >> \"%PB_LOG%\" 2>&1")
                sb.appendLine("  reg add \"HKU\\.DEFAULT\\Control Panel\\Keyboard\" /v InitialKeyboardIndicators /t REG_SZ /d 2147483650 /f >> \"%PB_LOG%\" 2>&1")
            }
            if (winUtil.disableStickyKeys) sb.appendLine("  reg add \"HKU\\PB_DEF\\Control Panel\\Accessibility\\StickyKeys\" /v Flags /t REG_SZ /d 506 /f >> \"%PB_LOG%\" 2>&1")
            if (winUtil.taskbarAlignLeft) sb.appendLine("  if !PB_BUILD! GEQ 22000 reg add \"HKU\\PB_DEF\\Software\\Microsoft\\Windows\\CurrentVersion\\Explorer\\Advanced\" /v TaskbarAl /t REG_DWORD /d 0 /f >> \"%PB_LOG%\" 2>&1")
            if (winUtil.hideTaskbarSearch) sb.appendLine("  reg add \"HKU\\PB_DEF\\Software\\Microsoft\\Windows\\CurrentVersion\\Search\" /v SearchboxTaskbarMode /t REG_DWORD /d 0 /f >> \"%PB_LOG%\" 2>&1")
            if (winUtil.hideTaskView) sb.appendLine("  reg add \"HKU\\PB_DEF\\Software\\Microsoft\\Windows\\CurrentVersion\\Explorer\\Advanced\" /v ShowTaskViewButton /t REG_DWORD /d 0 /f >> \"%PB_LOG%\" 2>&1")
            if (winUtil.showBatteryPercentage) sb.appendLine("  if !PB_BUILD! GEQ 22000 reg add \"HKU\\PB_DEF\\Software\\Microsoft\\Windows\\CurrentVersion\\Explorer\\Advanced\" /v ShowBatteryPercentage /t REG_DWORD /d 1 /f >> \"%PB_LOG%\" 2>&1")
            if (winUtil.alwaysShowScrollbars) sb.appendLine("  reg add \"HKU\\PB_DEF\\Control Panel\\Accessibility\" /v DynamicScrollbars /t REG_DWORD /d 0 /f >> \"%PB_LOG%\" 2>&1")
            if (winUtil.disableGameMode) sb.appendLine("  reg add \"HKU\\PB_DEF\\Software\\Microsoft\\GameBar\" /v AutoGameModeEnabled /t REG_DWORD /d 0 /f >> \"%PB_LOG%\" 2>&1")
            if (winUtil.disableWindowSnapping) sb.appendLine("  reg add \"HKU\\PB_DEF\\Control Panel\\Desktop\" /v WindowArrangementActive /t REG_SZ /d 0 /f >> \"%PB_LOG%\" 2>&1")
            if (winUtil.visualEffectsBestPerformance) sb.appendLine("  reg add \"HKU\\PB_DEF\\Software\\Microsoft\\Windows\\CurrentVersion\\Explorer\\VisualEffects\" /v VisualFXSetting /t REG_DWORD /d 2 /f >> \"%PB_LOG%\" 2>&1")
            if (winUtil.disableNotificationCenter) sb.appendLine("  reg add \"HKU\\PB_DEF\\Software\\Policies\\Microsoft\\Windows\\Explorer\" /v DisableNotificationCenter /t REG_DWORD /d 1 /f >> \"%PB_LOG%\" 2>&1")
            sb.appendLine("  reg unload \"HKU\\PB_DEF\" >> \"%PB_LOG%\" 2>&1")
            sb.appendLine(")")
        }
        // OEM BIOS-keyed laptop safeguard: Windows skips SetupComplete.cmd when an OEM product key is detected in ACPI MSDM.
        // Register SetupComplete.cmd under FirstBoot\PostOobe so it executes right after OOBE on every machine (guarded by .pb_setupcomplete_done so it runs strictly once).
        sb.appendLine("if exist \"C:\\Windows\\Setup\\Scripts\\SetupComplete.cmd\" reg add \"HKLM\\SYSTEM\\Setup\\FirstBoot\\PostOobe\" /v \"00\" /t REG_SZ /d \"cmd.exe /c C:\\Windows\\Setup\\Scripts\\SetupComplete.cmd & exit /b 0\" /f >> \"%PB_LOG%\" 2>&1")
        sb.appendLine("echo done > \"%PB_FLAG%\"")
        sb.appendLine("echo [PocketBoot v4.6.6] Specialize finished %DATE% %TIME% >> \"%PB_LOG%\"")
        sb.appendLine("exit /b 0")
        return sb.toString().replace("\r\n", "\n").replace("\n", "\r\n")
    }

    fun buildSetupCompleteScript(
        runComponentCleanup: Boolean = false,
        hasCustomScript: Boolean = false,
        customScriptFileName: String = ""
    ): String {
        val sb = StringBuilder()
        sb.appendLine("@echo off")
        sb.appendLine("setlocal EnableExtensions EnableDelayedExpansion")
        sb.appendLine("cd /d C:\\Windows\\System32 >nul 2>&1")
        sb.appendLine("mkdir \"C:\\Windows\\Logs\" >nul 2>&1")
        sb.appendLine("set \"PB_LOG=C:\\Windows\\Logs\\PocketBoot_Setup.log\"")
        sb.appendLine("set \"PB_SC_FLAG=C:\\Windows\\Setup\\Scripts\\.pb_setupcomplete_done\"")
        sb.appendLine("if exist \"%PB_SC_FLAG%\" exit /b 0")
        sb.appendLine("mkdir \"C:\\Windows\\Setup\\Scripts\" >nul 2>&1")
        sb.appendLine("echo done > \"%PB_SC_FLAG%\"")
        sb.appendLine("reg delete \"HKLM\\SYSTEM\\Setup\\FirstBoot\\PostOobe\" /v \"00\" /f >nul 2>&1")
        sb.appendLine("if not exist \"C:\\Windows\\Setup\\Scripts\\.pb_spec_done\" if exist \"C:\\Windows\\Setup\\Scripts\\PocketBoot_Specialize.cmd\" call \"C:\\Windows\\Setup\\Scripts\\PocketBoot_Specialize.cmd\"")
        if (hasCustomScript && customScriptFileName.isNotBlank()) {
            if (customScriptFileName.lowercase().endsWith(".ps1")) {
                sb.appendLine("if exist \"C:\\Windows\\Setup\\Custom\\$customScriptFileName\" powershell -NoProfile -ExecutionPolicy Bypass -File \"C:\\Windows\\Setup\\Custom\\$customScriptFileName\" >> \"%PB_LOG%\" 2>&1")
            } else {
                sb.appendLine("if exist \"C:\\Windows\\Setup\\Custom\\$customScriptFileName\" call \"C:\\Windows\\Setup\\Custom\\$customScriptFileName\" >> \"%PB_LOG%\" 2>&1")
            }
        }
        if (runComponentCleanup) {
            sb.appendLine("dism /Online /Cleanup-Image /StartComponentCleanup /ResetBase >> \"%PB_LOG%\" 2>&1")
        }
        sb.appendLine("if exist \"C:\\Windows\\Setup\\Custom\" rmdir /s /q \"C:\\Windows\\Setup\\Custom\" >nul 2>&1")
        sb.appendLine("echo [PocketBoot v4.6.6] SetupComplete finished %DATE% %TIME% >> \"%PB_LOG%\"")
        sb.appendLine("start \"\" /b cmd /c \"ping -n 4 127.0.0.1 >nul & rmdir /s /q C:\\Windows\\Setup\\Scripts >nul 2>&1\"")
        sb.appendLine("exit /b 0")
        return sb.toString().replace("\r\n", "\n").replace("\n", "\r\n")
    }

    fun generateAutounattendXml(experience: WindowsExperienceConfig, winUtil: WinUtilTweaksConfig, arch: String = "amd64", selectedEditionIndex: Int? = null, hasSpecializeScript: Boolean = false, hasOemUpdates: Boolean = false, includeWindowsPePass: Boolean = true, uiLang: String = "", autoLogonCount: Int = 1, includeLabConfigInPe: Boolean = true): String {
        val safeArch = if (arch.lowercase() in setOf("x86", "arm64", "amd64")) arch.lowercase() else "amd64"
        val peCommands = mutableListOf<Pair<String, String>>()
        if (includeLabConfigInPe && experience.bypassRequirements) {
            peCommands.add("Bypass TPM Check" to """reg add "HKLM\SYSTEM\Setup\LabConfig" /v BypassTPMCheck /t REG_DWORD /d 1 /f""")
            peCommands.add("Bypass Secure Boot Check" to """reg add "HKLM\SYSTEM\Setup\LabConfig" /v BypassSecureBootCheck /t REG_DWORD /d 1 /f""")
            peCommands.add("Bypass RAM Check" to """reg add "HKLM\SYSTEM\Setup\LabConfig" /v BypassRAMCheck /t REG_DWORD /d 1 /f""")
            peCommands.add("Bypass CPU Check" to """reg add "HKLM\SYSTEM\Setup\LabConfig" /v BypassCPUCheck /t REG_DWORD /d 1 /f""")
            peCommands.add("Bypass Storage Check" to """reg add "HKLM\SYSTEM\Setup\LabConfig" /v BypassStorageCheck /t REG_DWORD /d 1 /f""")
        }
        val specCommands = mutableListOf<Triple<String, String, Boolean>>()
        if (hasSpecializeScript) {
            specCommands.add(Triple("PocketBoot Specialize Tweaks & Updates", """cmd /c "C:\Windows\Setup\Scripts\PocketBoot_Specialize.cmd"""", hasOemUpdates))
        } else {
            if (experience.bypassMicrosoftAccount) specCommands.add(Triple("Bypass Microsoft Account", """reg add "HKLM\SOFTWARE\Microsoft\Windows\CurrentVersion\OOBE" /v BypassNRO /t REG_DWORD /d 1 /f""", false))
            if (experience.disableBitLocker) specCommands.add(Triple("Disable Automatic BitLocker", """reg add "HKLM\SYSTEM\CurrentControlSet\Control\BitLocker" /v PreventDeviceEncryption /t REG_DWORD /d 1 /f""", false))
            if (experience.disableDataCollection) specCommands.add(Triple("Disable Telemetry", """reg add "HKLM\SOFTWARE\Policies\Microsoft\Windows\DataCollection" /v AllowTelemetry /t REG_DWORD /d 0 /f""", false))
            if (experience.disableSponsoredApps || experience.qolImprovements) specCommands.add(Triple("Disable Consumer Features", """reg add "HKLM\SOFTWARE\Policies\Microsoft\Windows\CloudContent" /v DisableWindowsConsumerFeatures /t REG_DWORD /d 1 /f""", false))
        }
        val cleanUser = experience.sanitizedLocalUsername
        val needOobePass = experience.skipOobe || experience.disableDataCollection || experience.skipWifiSetup || cleanUser.isNotEmpty()
        val sb = StringBuilder()
        sb.appendLine("""<?xml version="1.0" encoding="utf-8"?>""")
        sb.appendLine("""<unattend xmlns="urn:schemas-microsoft-com:unattend">""")
        val cleanUiLang = uiLang.trim().takeIf { it.matches(Regex("^[a-zA-Z]{2,3}(-[a-zA-Z0-9]{2,4}){1,2}$")) }.orEmpty()
        val addIntlPe = experience.skipOobe && cleanUiLang.isNotEmpty()
        if (includeWindowsPePass && (peCommands.isNotEmpty() || selectedEditionIndex != null || addIntlPe)) {
            sb.appendLine("""  <settings pass="windowsPE">""")
            if (addIntlPe) {
                sb.appendLine("""    <component name="Microsoft-Windows-International-Core-WinPE" processorArchitecture="$safeArch" publicKeyToken="31bf3856ad364e35" language="neutral" versionScope="nonSxS" xmlns:wcm="http://schemas.microsoft.com/WMIConfig/2002/State">""")
                sb.appendLine("""      <SetupUILanguage><UILanguage>$cleanUiLang</UILanguage></SetupUILanguage>""")
                sb.appendLine("""      <UILanguage>$cleanUiLang</UILanguage>""")
                sb.appendLine("""    </component>""")
            }
            if (peCommands.isNotEmpty() || selectedEditionIndex != null) {
                sb.appendLine("""    <component name="Microsoft-Windows-Setup" processorArchitecture="$safeArch" publicKeyToken="31bf3856ad364e35" language="neutral" versionScope="nonSxS" xmlns:wcm="http://schemas.microsoft.com/WMIConfig/2002/State">""")
                if (selectedEditionIndex != null) {
                    sb.appendLine("""      <ImageInstall><OSImage><InstallFrom><MetaData wcm:action="add"><Key>/IMAGE/INDEX</Key><Value>$selectedEditionIndex</Value></MetaData></InstallFrom></OSImage></ImageInstall>""")
                    sb.appendLine("""      <UserData><AcceptEula>true</AcceptEula><ProductKey><Key></Key><WillShowUI>Never</WillShowUI></ProductKey></UserData>""")
                }
                if (peCommands.isNotEmpty()) {
                    sb.appendLine("""      <RunSynchronous>""")
                    peCommands.forEachIndexed { idx, (desc, cmd) -> sb.appendLine("""        <RunSynchronousCommand wcm:action="add"><Order>${idx + 1}</Order><Description>${xmlEscape(desc)}</Description><Path>${xmlEscape(cmd)}</Path></RunSynchronousCommand>""") }
                    sb.appendLine("""      </RunSynchronous>""")
                }
                sb.appendLine("""    </component>""")
            }
            sb.appendLine("""  </settings>""")
        }
        if (specCommands.isNotEmpty()) {
            sb.appendLine("""  <settings pass="specialize">""")
            sb.appendLine("""    <component name="Microsoft-Windows-Deployment" processorArchitecture="$safeArch" publicKeyToken="31bf3856ad364e35" language="neutral" versionScope="nonSxS" xmlns:wcm="http://schemas.microsoft.com/WMIConfig/2002/State">""")
            sb.appendLine("""      <RunSynchronous>""")
            specCommands.forEachIndexed { idx, (desc, cmd, mayReboot) ->
                val reb = if (mayReboot) "<WillReboot>OnRequest</WillReboot>" else ""
                sb.appendLine("""        <RunSynchronousCommand wcm:action="add"><Order>${idx + 1}</Order><Description>${xmlEscape(desc)}</Description><Path>${xmlEscape(cmd)}</Path>$reb</RunSynchronousCommand>""")
            }
            sb.appendLine("""      </RunSynchronous>""")
            sb.appendLine("""    </component>""")
            sb.appendLine("""  </settings>""")
        }
        if (needOobePass) {
            sb.appendLine("""  <settings pass="oobeSystem">""")
            sb.appendLine("""    <component name="Microsoft-Windows-Shell-Setup" processorArchitecture="$safeArch" publicKeyToken="31bf3856ad364e35" language="neutral" versionScope="nonSxS" xmlns:wcm="http://schemas.microsoft.com/WMIConfig/2002/State">""")
            val safeUser = if (cleanUser.isNotEmpty()) xmlEscape(cleanUser) else ""
            val safePass = if (cleanUser.isNotEmpty()) xmlEscape(experience.localPassword.take(32)) else ""
            if (cleanUser.isNotEmpty()) {
                sb.appendLine("""      <AutoLogon><Enabled>true</Enabled><LogonCount>${autoLogonCount.coerceIn(1, 10)}</LogonCount><Password><Value>$safePass</Value><PlainText>true</PlainText></Password><Username>$safeUser</Username></AutoLogon>""")
                sb.appendLine("""      <FirstLogonCommands><SynchronousCommand wcm:action="add"><CommandLine>net accounts /maxpwage:unlimited</CommandLine><Description>Disable local account password expiration</Description><Order>1</Order></SynchronousCommand></FirstLogonCommands>""")
            }
            sb.appendLine("""      <OOBE>""")
            if (experience.skipOobe) sb.appendLine("""        <HideEULAPage>true</HideEULAPage>""")
            if (cleanUser.isNotEmpty()) sb.appendLine("""        <HideLocalAccountScreen>true</HideLocalAccountScreen>""")
            if (experience.disableDataCollection || experience.skipOobe) sb.appendLine("""        <HideOEMRegistrationScreen>true</HideOEMRegistrationScreen>""")
            if (cleanUser.isNotEmpty()) sb.appendLine("""        <HideOnlineAccountScreens>true</HideOnlineAccountScreens>""")
            if (experience.skipWifiSetup || experience.skipOobe) sb.appendLine("""        <HideWirelessSetupInOOBE>true</HideWirelessSetupInOOBE>""")
            if (experience.disableDataCollection || experience.skipOobe) sb.appendLine("""        <ProtectYourPC>3</ProtectYourPC>""")
            if (experience.skipOobe && cleanUser.isNotEmpty()) sb.appendLine("""        <SkipMachineOOBE>true</SkipMachineOOBE><SkipUserOOBE>true</SkipUserOOBE>""")
            sb.appendLine("""      </OOBE>""")
            if (cleanUser.isNotEmpty()) {
                sb.appendLine("""      <UserAccounts><LocalAccounts><LocalAccount wcm:action="add"><DisplayName>$safeUser</DisplayName><Group>Administrators</Group><Name>$safeUser</Name><Password><Value>$safePass</Value><PlainText>true</PlainText></Password></LocalAccount></LocalAccounts></UserAccounts>""")
            }
            sb.appendLine("""    </component>""")
            sb.appendLine("""  </settings>""")
        }
        sb.appendLine("""</unattend>""")
        return sb.toString()
    }

    fun generateVentoySelfContainedXml(experience: WindowsExperienceConfig, winUtil: WinUtilTweaksConfig, arch: String = "amd64"): String {
        val safeArch = if (arch.lowercase() in setOf("amd64", "x86", "arm64")) arch.lowercase() else "amd64"
        val needScript = experience.needsSpecializeScript() || winUtil.hasAnyActive()
        val baseXml = generateAutounattendXml(experience, winUtil, safeArch, null, hasSpecializeScript = needScript, hasOemUpdates = false)
        if (!needScript) return baseXml
        val scriptText = buildSpecializeScript(experience, winUtil)
        val b64 = android.util.Base64.encodeToString(scriptText.toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP)
        val inlineCmd = """powershell -NoProfile -ExecutionPolicy Bypass -Command "New-Item -ItemType Directory -Force 'C:\Windows\Setup\Scripts' | Out-Null; [IO.File]::WriteAllBytes('C:\Windows\Setup\Scripts\PocketBoot_Specialize.cmd',[Convert]::FromBase64String('$b64')); & cmd.exe /c 'C:\Windows\Setup\Scripts\PocketBoot_Specialize.cmd'" """.trim()
        return baseXml.replace(xmlEscape("""cmd /c "C:\Windows\Setup\Scripts\PocketBoot_Specialize.cmd""""), xmlEscape(inlineCmd))
    }

    /**
     * Official Rufus-parity LabConfig patching: writes BypassTPMCheck, BypassSecureBootCheck, BypassRAMCheck,
     * BypassCPUCheck, and BypassStorageCheck directly into sources/boot.wim Index 2 offline SYSTEM hive so
     * no USB-root autounattend.xml is needed (preserving "Previous version of Setup", Product Key, Edition & EULA).
     */
    fun buildLabConfigRegContent(): String =
        "REGEDIT4\r\n\r\n" +
            "[HKEY_LOCAL_MACHINE\\SYSTEM\\Setup\\LabConfig]\r\n" +
            "\"BypassTPMCheck\"=dword:00000001\r\n" +
            "\"BypassSecureBootCheck\"=dword:00000001\r\n" +
            "\"BypassRAMCheck\"=dword:00000001\r\n" +
            "\"BypassCPUCheck\"=dword:00000001\r\n" +
            "\"BypassStorageCheck\"=dword:00000001\r\n\r\n"

    /**
     * Unified single-pass boot.wim Index 2 updater (exact official Rufus parity):
     * patches LabConfig into /Windows/System32/config/SYSTEM (when regFile + chntpw provided)
     * and/or injects /Autounattend.xml (when xmlFile provided) in ONE wimlib-imagex update call.
     */
    fun patchBootWimSnippet(
        qWimBin: String,
        qSourcesDir: String,
        qChntpwBin: String? = null,
        qRegFile: String? = null,
        qXmlFile: String? = null
    ): String {
        val wantLab = !qChntpwBin.isNullOrBlank() && !qRegFile.isNullOrBlank()
        val wantAu = !qXmlFile.isNullOrBlank()
        val labBlock = if (wantLab) {
            """
            $qWimBin extract "${'$'}BOOT_WIM" "${'$'}BOOT_IDX" "/Windows/System32/config/SYSTEM" --dest-dir="${'$'}BOOT_TMP" --no-acls --no-attributes >/dev/null 2>&1 || \
                $qWimBin extract "${'$'}BOOT_WIM" "${'$'}BOOT_IDX" "/windows/system32/config/system" --dest-dir="${'$'}BOOT_TMP" --no-acls --no-attributes >/dev/null 2>&1 || true
            SYS_HIVE=${'$'}(find "${'$'}BOOT_TMP" -maxdepth 1 -type f -iname 'system' | head -n 1)
            HIVE_SZ=${'$'}(stat -c '%s' "${'$'}SYS_HIVE" 2>/dev/null || echo 0)
            if [ -n "${'$'}SYS_HIVE" ] && [ "${'$'}{HIVE_SZ:-0}" -ge 4096 ]; then
                set +e
                printf 'y\n' | $qChntpwBin -C -I "${'$'}SYS_HIVE" 'HKEY_LOCAL_MACHINE\SYSTEM' $qRegFile >/dev/null 2>&1
                REG_RC=${'$'}?
                set -e
                AFTER_SZ=${'$'}(stat -c '%s' "${'$'}SYS_HIVE" 2>/dev/null || echo 0)
                if [ "${'$'}{AFTER_SZ:-0}" -ge 4096 ]; then
                    echo "add '${'$'}SYS_HIVE' /Windows/System32/config/SYSTEM" >> "${'$'}UPD_CMD"
                    LAB_READY=1
                else
                    echo "LOG|labconfig|reged hive patch failed (rc=${'$'}REG_RC, size=${'$'}AFTER_SZ)"
                fi
            else
                echo "LOG|labconfig|Could not extract valid SYSTEM hive from boot.wim index ${'$'}BOOT_IDX"
            fi
            """.trimIndent()
        } else ""
        val auBlock = if (wantAu) {
            """
            echo "delete --force /Autounattend.xml" >> "${'$'}UPD_CMD"
            echo "add $qXmlFile /Autounattend.xml" >> "${'$'}UPD_CMD"
            AU_READY=1
            """.trimIndent()
        } else ""
        return """
        set -e
        BOOT_TMP="/data/local/tmp/pb_boot_patch"
        rm -rf "${'$'}BOOT_TMP" && mkdir -p "${'$'}BOOT_TMP"
        export TMPDIR="${'$'}BOOT_TMP" HOME="${'$'}BOOT_TMP" WIMLIB_IMAGEX_IGNORE_CASE=1
        BOOT_WIM=${'$'}(find $qSourcesDir -maxdepth 1 -type f -iname 'boot.wim' 2>/dev/null | head -n 1)
        if [ -z "${'$'}BOOT_WIM" ] || [ ! -s "${'$'}BOOT_WIM" ]; then
            rm -rf "${'$'}BOOT_TMP"
            echo "LOG|labconfig|boot.wim not found in sources/"
            exit 1
        fi
        IDX_CNT=${'$'}($qWimBin info "${'$'}BOOT_WIM" 2>/dev/null | grep -c '^Index:' || echo 0)
        BOOT_IDX=2
        if [ "${'$'}{IDX_CNT:-0}" -lt 2 ]; then BOOT_IDX=1; fi
        UPD_CMD="${'$'}BOOT_TMP/update_cmds.txt"
        : > "${'$'}UPD_CMD"
        LAB_READY=0
        AU_READY=0
        $labBlock
        $auBlock
        if [ ! -s "${'$'}UPD_CMD" ]; then
            rm -rf "${'$'}BOOT_TMP"
            exit 1
        fi
        $qWimBin update "${'$'}BOOT_WIM" "${'$'}BOOT_IDX" < "${'$'}UPD_CMD" >/dev/null 2>&1
        rm -rf "${'$'}BOOT_TMP"
        if [ "${'$'}LAB_READY" = "1" ]; then
            echo "LOG|labconfig|Patched LabConfig hardware bypasses directly into boot.wim Index ${'$'}BOOT_IDX (Rufus-style)"
            echo "PATCHED_LAB_OK"
        fi
        if [ "${'$'}AU_READY" = "1" ]; then
            echo "LOG|win-build|Injected /Autounattend.xml into boot.wim Index ${'$'}BOOT_IDX (Rufus-style)"
            echo "PATCHED_AU_OK"
        fi
        """.trimIndent()
    }

    fun applyCa2023BootloadersSnippet(qWimBin: String, qInstallFile: String, qIsoRoot: String, qBootWim: String = ""): String = """
        CA_TMP="/data/local/tmp/pb_ca2023"
        rm -rf "${'$'}CA_TMP" && mkdir -p "${'$'}CA_TMP"
        export TMPDIR="${'$'}CA_TMP" HOME="${'$'}CA_TMP" WIMLIB_IMAGEX_IGNORE_CASE=1
        CA_APPLIED=""
        INST_SRC=$qInstallFile
        BOOT_WIM_SRC=${qBootWim.ifBlank { "''" }}
        ISO_ROOT=$qIsoRoot
        extract_wim_file() {
            local rel_path="${'$'}1"
            local out_name=${'$'}(basename "${'$'}rel_path")
            rm -f "${'$'}CA_TMP/${'$'}out_name"
            case "${'$'}INST_SRC" in
                *.swm|*.SWM)
                    local wdir=${'$'}(dirname "${'$'}INST_SRC")
                    local wbase=${'$'}(basename "${'$'}INST_SRC")
                    local wpre="${'$'}{wbase%.*}"
                    local wext="${'$'}{wbase##*.}"
                    $qWimBin extract "${'$'}INST_SRC" 1 "${'$'}rel_path" --ref="${'$'}{wdir}/${'$'}{wpre}*.${'$'}{wext}" --dest-dir="${'$'}CA_TMP" --no-acls --no-attributes 2>/dev/null || true
                    ;;
                *)
                    if [ -n "${'$'}INST_SRC" ] && [ -s "${'$'}INST_SRC" ]; then
                        $qWimBin extract "${'$'}INST_SRC" 1 "${'$'}rel_path" --dest-dir="${'$'}CA_TMP" --no-acls --no-attributes 2>/dev/null || true
                    fi
                    ;;
            esac
            if [ ! -s "${'$'}CA_TMP/${'$'}out_name" ] && [ -n "${'$'}BOOT_WIM_SRC" ] && [ -s "${'$'}BOOT_WIM_SRC" ]; then
                $qWimBin extract "${'$'}BOOT_WIM_SRC" 2 "${'$'}rel_path" --dest-dir="${'$'}CA_TMP" --no-acls --no-attributes 2>/dev/null || \
                    $qWimBin extract "${'$'}BOOT_WIM_SRC" 1 "${'$'}rel_path" --dest-dir="${'$'}CA_TMP" --no-acls --no-attributes 2>/dev/null || true
            fi
            [ -s "${'$'}CA_TMP/${'$'}out_name" ]
        }
        resolve_ci_dir() {
            local base="${'$'}1"; local child="${'$'}2"
            local hit=${'$'}(find "${'$'}base" -maxdepth 1 -mindepth 1 -type d -iname "${'$'}child" 2>/dev/null | head -n 1)
            if [ -n "${'$'}hit" ]; then printf '%s' "${'$'}hit"; else mkdir -p "${'$'}base/${'$'}child"; printf '%s' "${'$'}base/${'$'}child"; fi
        }
        copy_ci_file() {
            local src="${'$'}1"; local dir="${'$'}2"; local name="${'$'}3"
            local existing=${'$'}(find "${'$'}dir" -maxdepth 1 -mindepth 1 -type f -iname "${'$'}name" 2>/dev/null | head -n 1)
            if [ -n "${'$'}existing" ]; then cp -f "${'$'}src" "${'$'}existing"; else cp -f "${'$'}src" "${'$'}dir/${'$'}name"; fi
        }
        EFI_DIR=${'$'}(resolve_ci_dir "${'$'}ISO_ROOT" "efi")
        EFI_BOOT_DIR=${'$'}(resolve_ci_dir "${'$'}EFI_DIR" "boot")
        EFI_MS_DIR=${'$'}(resolve_ci_dir "${'$'}EFI_DIR" "microsoft")
        EFI_MS_BOOT_DIR=${'$'}(resolve_ci_dir "${'$'}EFI_MS_DIR" "boot")
        if extract_wim_file /Windows/Boot/EFI_EX/bootmgfw_EX.efi && [ -s "${'$'}CA_TMP/bootmgfw_EX.efi" ]; then
            if find "${'$'}EFI_BOOT_DIR" -maxdepth 1 -type f -iname 'bootaa64.efi' 2>/dev/null | grep -q . && ! find "${'$'}EFI_BOOT_DIR" -maxdepth 1 -type f -iname 'bootx64.efi' 2>/dev/null | grep -q .; then
                copy_ci_file "${'$'}CA_TMP/bootmgfw_EX.efi" "${'$'}EFI_BOOT_DIR" "bootaa64.efi"
            else
                copy_ci_file "${'$'}CA_TMP/bootmgfw_EX.efi" "${'$'}EFI_BOOT_DIR" "bootx64.efi"
            fi
            if find "${'$'}EFI_MS_BOOT_DIR" -maxdepth 1 -type f -iname 'bootmgfw.efi' 2>/dev/null | grep -q .; then
                copy_ci_file "${'$'}CA_TMP/bootmgfw_EX.efi" "${'$'}EFI_MS_BOOT_DIR" "bootmgfw.efi"
            fi
            CA_APPLIED="bootmgfw_EX.efi"
        fi
        if extract_wim_file /Windows/Boot/EFI_EX/bootmgr_EX.efi && [ -s "${'$'}CA_TMP/bootmgr_EX.efi" ]; then
            copy_ci_file "${'$'}CA_TMP/bootmgr_EX.efi" "${'$'}ISO_ROOT" "bootmgr.efi"
            CA_APPLIED="${'$'}{CA_APPLIED:+${'$'}CA_APPLIED + }bootmgr_EX.efi"
        fi
        if extract_wim_file /Windows/Boot/DVD/EFI/efisys_ex.bin && [ -s "${'$'}CA_TMP/efisys_ex.bin" ]; then
            copy_ci_file "${'$'}CA_TMP/efisys_ex.bin" "${'$'}EFI_MS_BOOT_DIR" "efisys.bin"
            CA_APPLIED="${'$'}{CA_APPLIED:+${'$'}CA_APPLIED + }efisys_ex.bin"
        fi
        if extract_wim_file /Windows/Boot/DVD/EFI/efisys_noprompt_ex.bin && [ -s "${'$'}CA_TMP/efisys_noprompt_ex.bin" ]; then
            copy_ci_file "${'$'}CA_TMP/efisys_noprompt_ex.bin" "${'$'}EFI_MS_BOOT_DIR" "efisys_noprompt.bin"
            CA_APPLIED="${'$'}{CA_APPLIED:+${'$'}CA_APPLIED + }efisys_noprompt_ex.bin"
        fi
        if [ -n "${'$'}CA_APPLIED" ]; then
            echo "LOG|ca2023|Applied Windows CA 2023 bootloaders (${'$'}CA_APPLIED)"
        else
            echo "LOG|ca2023|Source image lacks EFI_EX CA 2023 bootloaders; kept standard bootloaders"
        fi
        rm -rf "${'$'}CA_TMP"
    """.trimIndent()

    fun applyWin7UefiFixSnippet(qWimBin: String, qInstallFile: String, qIsoRoot: String): String = """
        ISO_ROOT=$qIsoRoot
        INST_SRC=$qInstallFile
        if ! find "${'$'}ISO_ROOT" -maxdepth 3 -type f \( -iname 'bootx64.efi' -o -iname 'bootaa64.efi' \) 2>/dev/null | grep -q .; then
            W7_TMP="/data/local/tmp/pb_win7_efi"
            rm -rf "${'$'}W7_TMP" && mkdir -p "${'$'}W7_TMP"
            export TMPDIR="${'$'}W7_TMP" HOME="${'$'}W7_TMP" WIMLIB_IMAGEX_IGNORE_CASE=1
            W7_OK=0
            case "${'$'}INST_SRC" in
                *.swm|*.SWM)
                    WDIR=${'$'}(dirname "${'$'}INST_SRC"); WBASE=${'$'}(basename "${'$'}INST_SRC"); WPRE="${'$'}{WBASE%.*}"; WEXT="${'$'}{WBASE##*.}"
                    if $qWimBin extract "${'$'}INST_SRC" 1 /Windows/Boot/EFI/bootmgfw.efi --ref="${'$'}{WDIR}/${'$'}{WPRE}*.${'$'}{WEXT}" --dest-dir="${'$'}W7_TMP" --no-acls --no-attributes 2>/dev/null; then W7_OK=1; fi
                    ;;
                *)
                    if $qWimBin extract "${'$'}INST_SRC" 1 /Windows/Boot/EFI/bootmgfw.efi --dest-dir="${'$'}W7_TMP" --no-acls --no-attributes 2>/dev/null; then W7_OK=1; fi
                    ;;
            esac
            if [ "${'$'}W7_OK" = "1" ] && [ -s "${'$'}W7_TMP/bootmgfw.efi" ]; then
                EFI_DIR=${'$'}(find "${'$'}ISO_ROOT" -maxdepth 1 -mindepth 1 -type d -iname 'efi' 2>/dev/null | head -n 1)
                [ -z "${'$'}EFI_DIR" ] && EFI_DIR="${'$'}ISO_ROOT/efi"
                EFI_BOOT_DIR=${'$'}(find "${'$'}EFI_DIR" -maxdepth 1 -mindepth 1 -type d -iname 'boot' 2>/dev/null | head -n 1)
                [ -z "${'$'}EFI_BOOT_DIR" ] && EFI_BOOT_DIR="${'$'}EFI_DIR/boot"
                mkdir -p "${'$'}EFI_BOOT_DIR"
                cp -f "${'$'}W7_TMP/bootmgfw.efi" "${'$'}EFI_BOOT_DIR/bootx64.efi"
                echo "LOG|win7|Extracted Windows 7 UEFI bootx64.efi from install image"
            fi
            rm -rf "${'$'}W7_TMP"
        fi
    """.trimIndent()
}

@Composable
fun WindowsTwoPaneCustomizer(
    osKind: WindowsOsKind, arch: String = "amd64", experience: WindowsExperienceConfig, winUtil: WinUtilTweaksConfig,
    presets: List<AutounattendPreset>, showCa2023Option: Boolean = true, showCustomScriptOption: Boolean = true,
    defaultBrowseDir: String = "/data/media/0/Download", selectedEditionIndex: Int? = null,
    onExperienceChange: (WindowsExperienceConfig) -> Unit, onWinUtilChange: (WinUtilTweaksConfig) -> Unit,
    onApplyPreset: (AutounattendPreset) -> Unit, onSavePreset: (String) -> Unit, onDeletePreset: (String) -> Unit
) {
    var pane1Expanded by remember { mutableStateOf(false) }
    var showWinUtilSheet by remember { mutableStateOf(false) }
    var showSavePresetDialog by remember { mutableStateOf(false) }
    var newPresetName by remember { mutableStateOf("") }
    var showPreviewDialog by remember { mutableStateOf(false) }
    var showScriptPicker by remember { mutableStateOf(false) }
    val expCount = experience.activeCount(); val winUtilCount = winUtil.activeCount()

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(modifier = Modifier.bounceClick { showSavePresetDialog = true }, shape = RoundedCornerShape(9.dp), color = PocketColors.accentBg, border = BorderStroke(0.75.dp, PocketColors.accent)) {
                Row(modifier = Modifier.padding(horizontal = 9.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Icon(Icons.Rounded.BookmarkAdd, null, tint = PocketColors.accentSoft, modifier = Modifier.size(14.dp)); Text("Save Preset", style = MaterialTheme.typography.labelSmall, color = PocketColors.accentSoft)
                }
            }
            Surface(modifier = Modifier.bounceClick { showPreviewDialog = true }, shape = RoundedCornerShape(9.dp), color = PocketColors.surfaceInput, border = BorderStroke(0.75.dp, PocketColors.border)) {
                Row(modifier = Modifier.padding(horizontal = 9.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Icon(Icons.Rounded.Preview, null, tint = PocketColors.textSecondary, modifier = Modifier.size(14.dp)); Text("Preview XML", style = MaterialTheme.typography.labelSmall, color = PocketColors.textPrimary)
                }
            }
            val isStock = expCount == 0 && winUtilCount == 0
            Surface(
                modifier = Modifier.bounceClick { onExperienceChange(WindowsExperienceConfig.stock()); onWinUtilChange(WinUtilTweaksConfig()) },
                shape = RoundedCornerShape(9.dp),
                color = if (isStock) PocketColors.successBg else PocketColors.surfaceInput,
                border = BorderStroke(0.75.dp, if (isStock) PocketColors.success else PocketColors.border)
            ) {
                Text(
                    text = "Stock (No Tweaks)",
                    modifier = Modifier.padding(horizontal = 9.dp, vertical = 6.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isStock) PocketColors.success else PocketColors.textSecondary
                )
            }
            presets.forEach { preset ->
                Surface(shape = RoundedCornerShape(9.dp), color = PocketColors.surfaceSubtle, border = BorderStroke(0.75.dp, PocketColors.border)) {
                    Row(modifier = Modifier.padding(start = 9.dp, end = 4.dp, top = 5.dp, bottom = 5.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(preset.name, style = MaterialTheme.typography.labelSmall, color = PocketColors.textPrimary, modifier = Modifier.clickable { onApplyPreset(preset) })
                        Icon(Icons.Rounded.Close, "Delete preset", tint = PocketColors.textMuted, modifier = Modifier.size(14.dp).clickable { onDeletePreset(preset.name) })
                    }
                }
            }
        }

        // PANE 1: Windows Experience & Bypasses
        Surface(shape = RoundedCornerShape(14.dp), color = PocketColors.surfaceInput, border = BorderStroke(0.75.dp, if (expCount > 0) PocketColors.accent.copy(alpha = 0.55f) else PocketColors.border)) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(modifier = Modifier.fillMaxWidth().clickable { pane1Expanded = !pane1Expanded }.padding(horizontal = 13.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(modifier = Modifier.size(34.dp).clip(RoundedCornerShape(9.dp)).background(PocketColors.accentBg), contentAlignment = Alignment.Center) { Icon(Icons.Rounded.Security, null, tint = PocketColors.accentSoft, modifier = Modifier.size(18.dp)) }
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Windows Experience & Bypasses", style = MaterialTheme.typography.labelLarge, color = PocketColors.textPrimary)
                        Text(if (expCount > 0) "$expCount enabled · Rufus-style install rules" else "Stock Windows install (tap to customize)", fontSize = 11.sp, color = PocketColors.textSecondary)
                    }
                    Surface(shape = RoundedCornerShape(6.dp), color = if (expCount > 0) PocketColors.accentBg else PocketColors.surfaceSubtle) { Text("$expCount", modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp), style = MaterialTheme.typography.labelSmall, color = if (expCount > 0) PocketColors.accentSoft else PocketColors.textMuted) }
                    Icon(if (pane1Expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null, tint = PocketColors.textSecondary, modifier = Modifier.size(19.dp))
                }
                AnimatedVisibility(visible = pane1Expanded, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
                    Column(modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        PocketHairlineDivider()
                        if (osKind == WindowsOsKind.WIN11 || osKind == WindowsOsKind.UNKNOWN) CustomSwitchRow("Bypass 4GB+ RAM, Secure Boot & TPM 2.0", "LabConfig hardware checks in WinPE", experience.bypassRequirements) { onExperienceChange(experience.copy(bypassRequirements = it)) }
                        if (osKind != WindowsOsKind.WIN7 && osKind != WindowsOsKind.WIN81 && osKind != WindowsOsKind.SERVER) CustomSwitchRow("Bypass Online Microsoft Account", "Offline local account (unplug Ethernet in OOBE, or create Local Admin below)", experience.bypassMicrosoftAccount) { onExperienceChange(experience.copy(bypassMicrosoftAccount = it)) }
                        CustomSwitchRow("Disable Data Collection", "Skip privacy questions & telemetry prompts", experience.disableDataCollection) { onExperienceChange(experience.copy(disableDataCollection = it)) }
                        CustomSwitchRow("Disable Automatic BitLocker Encryption", "PreventDeviceEncryption = 1", experience.disableBitLocker) { onExperienceChange(experience.copy(disableBitLocker = it)) }
                        CustomSwitchRow("QoL Improvements Bundle", "No Fast Startup, Copilot, OneDrive nag, Outlook, Widgets/Bing · show file extensions", experience.qolImprovements) { onExperienceChange(experience.copy(qolImprovements = it)) }
                        CustomSwitchRow("Remove Sponsored Bloat Apps", "Block silent promoted Start Menu installs", experience.disableSponsoredApps) { onExperienceChange(experience.copy(disableSponsoredApps = it)) }
                        if (osKind == WindowsOsKind.WIN11 || osKind == WindowsOsKind.UNKNOWN) CustomSwitchRow("Classic Windows 10 Right-Click Menu", "Restores full context menu immediately", experience.classicContextMenu) { onExperienceChange(experience.copy(classicContextMenu = it)) }
                        CustomSwitchRow("Skip Wi-Fi / Network Setup Screen", "HideWirelessSetupInOOBE", experience.skipWifiSetup) { onExperienceChange(experience.copy(skipWifiSetup = it)) }
                        CustomSwitchRow("Create Local Admin Account", "Pre-create account & auto-logon once", experience.createLocalUser) { onExperienceChange(experience.copy(createLocalUser = it)) }
                        AnimatedVisibility(visible = experience.createLocalUser) {
                            val userInvalid = experience.localUsername.isNotBlank() && experience.sanitizedLocalUsername.isEmpty()
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Row(modifier = Modifier.fillMaxWidth().padding(top = 2.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedTextField(value = experience.localUsername, onValueChange = { onExperienceChange(experience.copy(localUsername = WindowsCustomizationEngine.filterUsernameInput(it))) }, label = { Text("Username") }, singleLine = true, isError = userInvalid, modifier = Modifier.weight(1f), shape = RoundedCornerShape(10.dp), colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = PocketColors.accent, unfocusedBorderColor = PocketColors.border, focusedTextColor = PocketColors.textPrimary, unfocusedTextColor = PocketColors.textPrimary))
                                    OutlinedTextField(value = experience.localPassword, onValueChange = { onExperienceChange(experience.copy(localPassword = it.take(32))) }, label = { Text("Password (opt)") }, singleLine = true, modifier = Modifier.weight(1f), shape = RoundedCornerShape(10.dp), colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = PocketColors.accent, unfocusedBorderColor = PocketColors.border, focusedTextColor = PocketColors.textPrimary, unfocusedTextColor = PocketColors.textPrimary))
                                }
                                if (userInvalid) {
                                    Text("Reserved or invalid Windows username (avoid Administrator, Guest, CON, NUL, or symbols).", fontSize = 10.5.sp, color = PocketColors.warning)
                                }
                            }
                        }
                        CustomSwitchRow("Full Unattended OOBE Skip", "SkipMachineOOBE + SkipUserOOBE", experience.skipOobe) { onExperienceChange(experience.copy(skipOobe = it)) }
                        if (experience.skipOobe && experience.sanitizedLocalUsername.isEmpty()) {
                            Text(
                                text = "Tip: Enable \"Create Local Admin Account\" above to skip account screens completely; otherwise OOBE will still ask for a username.",
                                fontSize = 10.5.sp,
                                color = PocketColors.warning
                            )
                        }
                        if (showCa2023Option) CustomSwitchRow("Use 'Windows CA 2023' Signed Bootloaders", "Caution: target PC BIOS must support UEFI CA 2023", experience.useCa2023Bootloaders, warningAccent = true) { onExperienceChange(experience.copy(useCa2023Bootloaders = it, applySkuSiPolicy = if (!it) false else experience.applySkuSiPolicy)) }
                        CustomSwitchRow("Apply SkuSiPolicy.p7b (KB5042562)", "Auto-enables CA 2023 bootloaders · revokes pre-2023 boot media on target PC", experience.applySkuSiPolicy, warningAccent = true) { onExperienceChange(experience.copy(applySkuSiPolicy = it, useCa2023Bootloaders = if (it) true else experience.useCa2023Bootloaders)) }
                        if (showCustomScriptOption) {
                            Surface(modifier = Modifier.fillMaxWidth().bounceClick { showScriptPicker = true }, shape = RoundedCornerShape(10.dp), color = PocketColors.surfaceCard, border = BorderStroke(0.75.dp, if (experience.customPostInstallScriptPath.isNotBlank()) PocketColors.accent else PocketColors.border)) {
                                Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 11.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Icon(Icons.Rounded.Code, null, tint = PocketColors.accentSoft, modifier = Modifier.size(17.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text("Custom Post-Install Script (Optional)", style = MaterialTheme.typography.labelMedium, color = PocketColors.textPrimary)
                                        Text(if (experience.customPostInstallScriptPath.isNotBlank()) experience.customPostInstallScriptPath.substringAfterLast('/') else "Pick a .cmd, .bat, or .ps1 script to run after setup", fontSize = 11.sp, color = if (experience.customPostInstallScriptPath.isNotBlank()) PocketColors.accentSoft else PocketColors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                    if (experience.customPostInstallScriptPath.isNotBlank()) { IconButton(onClick = { onExperienceChange(experience.copy(customPostInstallScriptPath = "")) }, modifier = Modifier.size(28.dp)) { Icon(Icons.Rounded.Close, "Clear script", tint = PocketColors.textMuted, modifier = Modifier.size(15.dp)) } }
                                    else Icon(Icons.Rounded.ChevronRight, null, tint = PocketColors.textMuted, modifier = Modifier.size(17.dp))
                                }
                            }
                        }
                    }
                }
            }
        }

        // PANE 2: Dedicated System Tweaks (WinUtil) Pane
        Surface(modifier = Modifier.fillMaxWidth().bounceClick { showWinUtilSheet = true }, shape = RoundedCornerShape(14.dp), color = PocketColors.surfaceInput, border = BorderStroke(0.75.dp, if (winUtilCount > 0) PocketColors.accent.copy(alpha = 0.65f) else PocketColors.border)) {
            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 13.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(modifier = Modifier.size(34.dp).clip(RoundedCornerShape(9.dp)).background(if (winUtilCount > 0) PocketColors.accentBg else PocketColors.violetBg), contentAlignment = Alignment.Center) { Icon(Icons.Rounded.Tune, null, tint = if (winUtilCount > 0) PocketColors.accentSoft else PocketColors.violet, modifier = Modifier.size(18.dp)) }
                Column(modifier = Modifier.weight(1f)) {
                    Text("System Tweaks (WinUtil)", style = MaterialTheme.typography.labelLarge, color = PocketColors.textPrimary)
                    Text(if (winUtilCount > 0) "$winUtilCount tweak(s) selected · Essential, Preferences & Advanced" else "Optional: Dark mode, WPBT block, Services, AI removal, Power plan", fontSize = 11.sp, color = PocketColors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Surface(shape = RoundedCornerShape(6.dp), color = if (winUtilCount > 0) PocketColors.accentBg else PocketColors.surfaceSubtle) { Text(if (winUtilCount > 0) "$winUtilCount active" else "Configure", modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.5.dp), style = MaterialTheme.typography.labelSmall, color = if (winUtilCount > 0) PocketColors.accentSoft else PocketColors.textSecondary) }
                Icon(Icons.Rounded.ChevronRight, null, tint = PocketColors.textMuted, modifier = Modifier.size(18.dp))
            }
        }
    }

    if (showWinUtilSheet) WinUtilTweaksSheet(config = winUtil, onDismiss = { showWinUtilSheet = false }, onChange = onWinUtilChange)
    if (showScriptPicker) RootSingleFilePickerSheet("Select Post-Install Script", "Runs automatically at end of SetupComplete.cmd", setOf("cmd", "bat", "ps1"), defaultBrowseDir, { showScriptPicker = false }) { onExperienceChange(experience.copy(customPostInstallScriptPath = it)); showScriptPicker = false }
    if (showSavePresetDialog) {
        AlertDialog(
            onDismissRequest = { showSavePresetDialog = false }, containerColor = PocketColors.surfaceCard,
            title = { Text("Save Unified Preset", color = PocketColors.textPrimary) },
            text = { Column(verticalArrangement = Arrangement.spacedBy(6.dp)) { Text("Saves both Pane 1 (Windows Experience) and Pane 2 (WinUtil Tweaks) together.", style = MaterialTheme.typography.bodySmall, color = PocketColors.textSecondary); OutlinedTextField(value = newPresetName, onValueChange = { newPresetName = it.take(26) }, label = { Text("Preset name") }, placeholder = { Text("e.g., Gaming PC or Clean Laptop") }, singleLine = true, modifier = Modifier.fillMaxWidth()) } },
            confirmButton = { Button(onClick = { val n = newPresetName.trim(); if (n.isNotEmpty()) { onSavePreset(n); newPresetName = ""; showSavePresetDialog = false } }, enabled = newPresetName.trim().isNotEmpty(), colors = ButtonDefaults.buttonColors(containerColor = PocketColors.accent, contentColor = PocketColors.onAccent)) { Text("Save", fontWeight = FontWeight.SemiBold) } },
            dismissButton = { TextButton(onClick = { showSavePresetDialog = false }) { Text("Cancel", color = PocketColors.textSecondary) } }
        )
    }
    if (showPreviewDialog) {
        var previewTab by remember { mutableIntStateOf(0) }
        val previewUnattended = selectedEditionIndex != null || experience.skipOobe
        val previewHasXml = previewUnattended || experience.hasPostSetupTweaks() || winUtil.hasAnyActive()
        val previewNeedsScript = experience.needsSpecializeScript() || winUtil.hasAnyActive()
        val xmlText = remember(experience, winUtil, arch, selectedEditionIndex) {
            if (!previewHasXml) {
                if (experience.bypassRequirements) {
                    "<!-- boot.wim LabConfig patch only — no unattend.xml or scripts written (100% native Windows Setup & OOBE) -->"
                } else {
                    "<!-- Pure Stock ISO — no unattend.xml, boot.wim patch, or scripts written (100% untouched Microsoft Setup & OOBE) -->"
                }
            } else {
                WindowsCustomizationEngine.generateAutounattendXml(
                    experience, winUtil, arch,
                    selectedEditionIndex = selectedEditionIndex,
                    hasSpecializeScript = previewNeedsScript,
                    includeWindowsPePass = previewUnattended,
                    includeLabConfigInPe = false
                )
            }
        }
        val scriptText = remember(experience, winUtil) {
            when {
                previewNeedsScript -> WindowsCustomizationEngine.buildSpecializeScript(experience, winUtil)
                !previewHasXml && !experience.bypassRequirements -> "; No Specialize.cmd needed (Pure Stock ISO — zero scripts injected)"
                else -> "; No Specialize.cmd needed (pure Rufus-style Panther\\unattend.xml + boot.wim LabConfig patch)"
            }
        }
        AlertDialog(
            onDismissRequest = { showPreviewDialog = false }, containerColor = PocketColors.surfaceCard,
            title = { Text("Generated Setup Preview", color = PocketColors.textPrimary) },
            text = {
                Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(if (previewUnattended) "boot.wim\\Autounattend.xml" else "Panther\\unattend.xml", "Specialize.cmd").forEachIndexed { idx, lbl ->
                            val sel = previewTab == idx
                            Surface(modifier = Modifier.weight(1f).bounceClick { previewTab = idx }, shape = RoundedCornerShape(8.dp), color = if (sel) PocketColors.accentBg else PocketColors.surfaceInput, border = BorderStroke(0.75.dp, if (sel) PocketColors.accent else PocketColors.border)) {
                                Box(modifier = Modifier.padding(vertical = 6.dp), contentAlignment = Alignment.Center) { Text(lbl, style = MaterialTheme.typography.labelSmall, color = if (sel) PocketColors.accentSoft else PocketColors.textSecondary) }
                            }
                        }
                    }
                    Surface(modifier = Modifier.fillMaxWidth().height(280.dp), shape = RoundedCornerShape(10.dp), color = PocketColors.bgCanvas, border = BorderStroke(0.5.dp, PocketColors.border)) {
                        Text(if (previewTab == 0) xmlText else scriptText, style = PocketColors.monoStyle, fontSize = 10.sp, color = PocketColors.textSecondary, modifier = Modifier.padding(10.dp).verticalScroll(rememberScrollState()))
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showPreviewDialog = false }) { Text("Close", color = PocketColors.accentSoft) } }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WinUtilTweaksSheet(config: WinUtilTweaksConfig, onDismiss: () -> Unit, onChange: (WinUtilTweaksConfig) -> Unit) {
    var activeTab by remember { mutableIntStateOf(0) }
    val count = config.activeCount()
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = PocketColors.surfaceCard) {
        Column(modifier = Modifier.fillMaxWidth().fillMaxHeight(0.86f).navigationBarsPadding().imePadding().padding(horizontal = 18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            PocketSheetHeader(icon = Icons.Rounded.Tune, tint = PocketColors.accentSoft, iconBg = PocketColors.accentBg, title = "System Tweaks (WinUtil)", subtitle = "$count active · Applied offline-safe on first boot")
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                Surface(modifier = Modifier.weight(1f).bounceClick { onChange(WinUtilTweaksConfig.desktopPreset()) }, shape = RoundedCornerShape(9.dp), color = PocketColors.accentBg, border = BorderStroke(0.75.dp, PocketColors.accent)) { Box(modifier = Modifier.padding(vertical = 7.dp), contentAlignment = Alignment.Center) { Text("Desktop Preset", style = MaterialTheme.typography.labelSmall, color = PocketColors.accentSoft) } }
                Surface(modifier = Modifier.weight(1f).bounceClick { onChange(WinUtilTweaksConfig.laptopSafePreset()) }, shape = RoundedCornerShape(9.dp), color = PocketColors.infoBlueBg, border = BorderStroke(0.75.dp, PocketColors.infoBlue)) { Box(modifier = Modifier.padding(vertical = 7.dp), contentAlignment = Alignment.Center) { Text("Laptop Safe", style = MaterialTheme.typography.labelSmall, color = PocketColors.infoBlue) } }
                Surface(modifier = Modifier.weight(0.75f).bounceClick { onChange(WinUtilTweaksConfig()) }, shape = RoundedCornerShape(9.dp), color = PocketColors.surfaceInput, border = BorderStroke(0.75.dp, PocketColors.border)) { Box(modifier = Modifier.padding(vertical = 7.dp), contentAlignment = Alignment.Center) { Text("Clear All", style = MaterialTheme.typography.labelSmall, color = PocketColors.textSecondary) } }
            }
            Surface(shape = RoundedCornerShape(10.dp), color = PocketColors.surfaceInput, border = BorderStroke(0.5.dp, PocketColors.border)) {
                Row(modifier = Modifier.fillMaxWidth().padding(3.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    listOf("Essential", "Preferences", "Advanced").forEachIndexed { idx, lbl ->
                        val sel = activeTab == idx
                        Box(modifier = Modifier.weight(1f).clip(RoundedCornerShape(8.dp)).background(if (sel) PocketColors.surfaceSubtle else Color.Transparent).clickable { activeTab = idx }.padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
                            Text(lbl, style = MaterialTheme.typography.labelMedium, fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal, color = when { sel && idx == 2 -> PocketColors.warning; sel -> PocketColors.accentSoft; else -> PocketColors.textSecondary })
                        }
                    }
                }
            }
            Column(modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                when (activeTab) {
                    0 -> {
                        CustomSwitchRow("Disable Activity History", "Turns off Windows Timeline & activity feed", config.disableActivityHistory) { onChange(config.copy(disableActivityHistory = it)) }
                        CustomSwitchRow("Disable Delivery Optimization", "Stops P2P Windows Update uploads", config.disableDeliveryOptimization) { onChange(config.copy(disableDeliveryOptimization = it)) }
                        CustomSwitchRow("End Task With Right Click", "Adds End Task to Windows 11 taskbar menu", config.endTaskWithRightClick) { onChange(config.copy(endTaskWithRightClick = it)) }
                        CustomSwitchRow("Disable Auto Folder Discovery", "Stops Explorer switching folder view templates", config.disableAutoFolderDiscovery) { onChange(config.copy(disableAutoFolderDiscovery = it)) }
                        CustomSwitchRow("Disable Hibernation", "Frees hiberfil.sys SSD space (best for desktops)", config.disableHibernation) { onChange(config.copy(disableHibernation = it)) }
                        CustomSwitchRow("Disable Location Tracking", "Disables system geolocation & lfsvc", config.disableLocationTracking) { onChange(config.copy(disableLocationTracking = it)) }
                        CustomSwitchRow("Prevent Device Companion Apps", "Stops vendor bloatware auto-downloads on plug-in", config.preventDeviceCompanionApps) { onChange(config.copy(preventDeviceCompanionApps = it)) }
                        CustomSwitchRow("Disable WPBT (BIOS Bloatware)", "Blocks motherboard UEFI from injecting vendor installers", config.disableWpbt) { onChange(config.copy(disableWpbt = it)) }
                        CustomSwitchRow("Set Non-Essential Services to Manual", "DiagTrack, MapsBroker, WerSvc, Fax, RetailDemo", config.servicesToManual) { onChange(config.copy(servicesToManual = it)) }
                        CustomSwitchRow("Revert Start Menu Layout", "Restores prior Win 11 Start Menu layout", config.revertStartMenuLayout) { onChange(config.copy(revertStartMenuLayout = it)) }
                    }
                    1 -> {
                        CustomSwitchRow("Dark Theme by Default", "Dark Mode for Windows UI & Apps before first login", config.darkThemeDefault) { onChange(config.copy(darkThemeDefault = it)) }
                        CustomSwitchRow("Enable Long Paths (>260 chars)", "Removes legacy Win32 path length cap", config.enableLongPaths) { onChange(config.copy(enableLongPaths = it)) }
                        CustomSwitchRow("Show Hidden Files & Folders", "Visible immediately in File Explorer", config.showHiddenFiles) { onChange(config.copy(showHiddenFiles = it)) }
                        CustomSwitchRow("Disable Mouse Acceleration", "Raw 1:1 pointer precision", config.disableMouseAcceleration) { onChange(config.copy(disableMouseAcceleration = it)) }
                        CustomSwitchRow("Num Lock on Startup", "Enables Num Lock at login screen", config.numLockOnStartup) { onChange(config.copy(numLockOnStartup = it)) }
                        CustomSwitchRow("Disable Sticky Keys Popup", "Stops 5x Shift Sticky Keys beep/dialog", config.disableStickyKeys) { onChange(config.copy(disableStickyKeys = it)) }
                        CustomSwitchRow("Taskbar Alignment: Left", "Aligns Windows 11 taskbar icons left", config.taskbarAlignLeft) { onChange(config.copy(taskbarAlignLeft = it)) }
                        CustomSwitchRow("Hide Taskbar Search Icon", "Removes taskbar Search box", config.hideTaskbarSearch) { onChange(config.copy(hideTaskbarSearch = it)) }
                        CustomSwitchRow("Hide Task View Icon", "Removes taskbar Task View button", config.hideTaskView) { onChange(config.copy(hideTaskView = it)) }
                        CustomSwitchRow("System Tray Battery Percentage", "Shows exact % on Windows 11 24H2+", config.showBatteryPercentage) { onChange(config.copy(showBatteryPercentage = it)) }
                        CustomSwitchRow("Disable Lock Screen", "Skips swipe-up screen straight to login", config.disableLockScreen) { onChange(config.copy(disableLockScreen = it)) }
                        CustomSwitchRow("Disable Logon Acrylic Blur", "Crisp wallpaper without blur on sign-in", config.disableLogonAcrylicBlur) { onChange(config.copy(disableLogonAcrylicBlur = it)) }
                        CustomSwitchRow("Verbose BSoD & Logon Status", "Detailed boot/shutdown & crash diagnostics", config.verboseBsodAndLogon) { onChange(config.copy(verboseBsodAndLogon = it)) }
                        CustomSwitchRow("Always Show Scrollbars", "Disables auto-hiding UWP scrollbars", config.alwaysShowScrollbars) { onChange(config.copy(alwaysShowScrollbars = it)) }
                        CustomSwitchRow("Hide Settings Home Page", "Removes ad-heavy Settings Home screen", config.hideSettingsHome) { onChange(config.copy(hideSettingsHome = it)) }
                        CustomSwitchRow("Disable Game Mode", "Turns off AutoGameModeEnabled", config.disableGameMode) { onChange(config.copy(disableGameMode = it)) }
                        CustomSwitchRow("Disable Window Snapping", "Turns off edge drag window snap", config.disableWindowSnapping) { onChange(config.copy(disableWindowSnapping = it)) }
                        CustomSwitchRow("Disable S0 Standby Network", "Prevents Modern Standby Wi-Fi drain in bag (laptops)", config.disableS0Network) { onChange(config.copy(disableS0Network = it, forceS3Sleep = if (it) false else config.forceS3Sleep)) }
                        CustomSwitchRow("Disable Multiplane Overlay (MPO)", "Fixes NVIDIA/AMD flicker & browser stutter", config.disableMpo) { onChange(config.copy(disableMpo = it)) }
                    }
                    else -> {
                        Surface(shape = RoundedCornerShape(10.dp), color = PocketColors.warningBg, border = BorderStroke(0.5.dp, PocketColors.warning)) {
                            Row(modifier = Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Icon(Icons.Rounded.WarningAmber, null, tint = PocketColors.warning, modifier = Modifier.size(16.dp))
                                Text("Advanced tweaks modify core services, browsers, or power plans. Enable only what you need.", style = MaterialTheme.typography.bodySmall, color = PocketColors.textPrimary)
                            }
                        }
                        CustomSwitchRow("Disable & Remove Windows AI", "Blocks Recall, Copilot & AI snapshots", config.disableAndRemoveWindowsAi, warningAccent = true) { onChange(config.copy(disableAndRemoveWindowsAi = it)) }
                        CustomSwitchRow("Remove OneDrive Completely", "Full OneDrive uninstall (beyond QoL block)", config.removeOneDrive, warningAccent = true) { onChange(config.copy(removeOneDrive = it)) }
                        CustomSwitchRow("Debloat Microsoft Edge", "Disables Sidebar, Shopping, Rewards & Startup Boost", config.debloatEdge, warningAccent = true) { onChange(config.copy(debloatEdge = it, removeEdge = if (it) false else config.removeEdge)) }
                        CustomSwitchRow("Remove Microsoft Edge", "Uninstalls Edge (keeps WebView2 intact)", config.removeEdge, warningAccent = true) { onChange(config.copy(removeEdge = it, debloatEdge = if (it) false else config.debloatEdge)) }
                        CustomSwitchRow("Remove Explorer Home & Gallery", "Removes Home & Gallery sidebar nodes (Win 11)", config.removeExplorerHomeGallery, warningAccent = true) { onChange(config.copy(removeExplorerHomeGallery = it)) }
                        CustomSwitchRow("Set Hardware Clock to UTC", "Fixes 5.5h clock jump when dual-booting Linux", config.setHardwareClockUtc, warningAccent = true) { onChange(config.copy(setHardwareClockUtc = it)) }
                        CustomSwitchRow("Disable Reserved Storage", "Frees ~7 GB Windows Update reserve on C:", config.disableReservedStorage, warningAccent = true) { onChange(config.copy(disableReservedStorage = it)) }
                        CustomSwitchRow("Disable Background Apps", "Stops UWP apps running in background", config.disableBackgroundApps, warningAccent = true) { onChange(config.copy(disableBackgroundApps = it)) }
                        CustomSwitchRow("Disable Storage Sense", "Turns off automatic temp file purging", config.disableStorageSense, warningAccent = true) { onChange(config.copy(disableStorageSense = it)) }
                        CustomSwitchRow("Disable Windows Search Indexing", "Disables WSearch service (slower file search)", config.disableSearchIndexing, warningAccent = true) { onChange(config.copy(disableSearchIndexing = it)) }
                        CustomSwitchRow("Block Razer / Logitech Auto-Install", "Stops vendor software popups via Windows Update", config.blockRazerLogitechAutoInstall, warningAccent = true) { onChange(config.copy(blockRazerLogitechAutoInstall = it)) }
                        CustomSwitchRow("Prefer IPv4 Over IPv6", "Safer than disabling IPv6 completely", config.preferIpv4OverIpv6, warningAccent = true) { onChange(config.copy(preferIpv4OverIpv6 = it, disableIpv6 = if (it) false else config.disableIpv6)) }
                        CustomSwitchRow("Disable IPv6 Completely", "DisabledComponents = 0xFF (mutually exclusive w/ IPv4 pref)", config.disableIpv6, warningAccent = true) { onChange(config.copy(disableIpv6 = it, preferIpv4OverIpv6 = if (it) false else config.preferIpv4OverIpv6)) }
                        CustomSwitchRow("Disable Teredo Tunneling", "Disables Teredo IPv6 relay (affects some Xbox P2P)", config.disableTeredo, warningAccent = true) { onChange(config.copy(disableTeredo = it)) }
                        CustomSwitchRow("Disable svchost.exe Splitting", "Consolidates services (16 GB+ RAM desktops)", config.disableSvchostSplitting, warningAccent = true) { onChange(config.copy(disableSvchostSplitting = it)) }
                        CustomSwitchRow("Visual Effects: Best Performance", "Disables UI animations & shadows", config.visualEffectsBestPerformance, warningAccent = true) { onChange(config.copy(visualEffectsBestPerformance = it)) }
                        CustomSwitchRow("Disable Notification Center", "Hides Windows Action/Notification Center", config.disableNotificationCenter, warningAccent = true) { onChange(config.copy(disableNotificationCenter = it)) }
                        CustomSwitchRow("Force Legacy S3 Sleep", "Caution: breaks sleep on Modern-Standby-only laptops", config.forceS3Sleep, warningAccent = true) { onChange(config.copy(forceS3Sleep = it, disableS0Network = if (it) false else config.disableS0Network)) }
                        CustomSwitchRow("Ultimate Performance Power Plan", "Desktops only · unlocks hidden max-perf scheme", config.ultimatePerformancePlan, warningAccent = true) { onChange(config.copy(ultimatePerformancePlan = it)) }
                    }
                }
            }
            Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth().padding(bottom = 18.dp).height(48.dp), shape = RoundedCornerShape(12.dp), colors = ButtonDefaults.buttonColors(containerColor = PocketColors.accent, contentColor = PocketColors.onAccent)) { Text("Done ($count tweaks selected)", fontWeight = FontWeight.SemiBold) }
        }
    }
}

@Composable
private fun CustomSwitchRow(title: String, subtitle: String, checked: Boolean, warningAccent: Boolean = false, onChange: (Boolean) -> Unit) {
    Surface(shape = RoundedCornerShape(10.dp), color = PocketColors.surfaceCard, border = BorderStroke(0.5.dp, if (checked) (if (warningAccent) PocketColors.warning.copy(alpha = 0.6f) else PocketColors.accent.copy(alpha = 0.55f)) else PocketColors.border)) {
        Row(modifier = Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(horizontal = 11.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Text(title, style = MaterialTheme.typography.labelMedium, color = PocketColors.textPrimary)
                Text(subtitle, fontSize = 10.5.sp, color = if (warningAccent && checked) PocketColors.warning else PocketColors.textSecondary)
            }
            Spacer(modifier = Modifier.width(8.dp))
            PocketSwitch(checked = checked, onCheckedChange = onChange, activeColor = if (warningAccent) PocketColors.warning else PocketColors.accent)
        }
    }
}
