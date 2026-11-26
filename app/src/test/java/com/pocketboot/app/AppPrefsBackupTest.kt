package com.pocketboot.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class AppPrefsBackupTest {

    @Test
    fun exportAndImportBackupJson_roundTripsAllPersistedFields() {
        val original = AppSettings(
            hasCompletedSetup = true,
            defaultDir = "/data/media/0/ISOs",
            downloadTargetMultiBootPath = "/data/media/0/ISOs/MultiBoot.img",
            favoriteQuickMountPath = "/data/media/0/ISOs/Win11.img",
            keepAdbActive = true,
            autoPatchSelinux = false,
            surfaceMode = SurfaceMode.LIGHT,
            useMonet = true,
            accentHex = 0xFF3B82F6L,
            scanDirs = listOf("/data/media/0/ISOs", "/data/media/0/Download"),
            fileModes = mapOf("/data/media/0/ISOs/Win11.img" to MountMode.USB_RW.name),
            useDefaultDistroRepo = false,
            customRepoUrls = listOf("https://example.com/distros.json"),
            autounattendPresets = listOf(AutounattendPreset(name = "Workstation", localUsername = "Shadab")),
            systemTweaksPresets = listOf(SystemTweaksPreset(name = "CleanGaming", tweaksJson = WinUtilTweaksConfig.desktopPreset().toJsonString())),
            systemTweaksSearchHistory = listOf("telemetry", "edge"),
            downloadConnections = 12,
            autoVerifySha256 = false,
            buildThermalProfile = BuildThermalProfile.COOL,
            autoThermalCooldown = false,
            keepScreenAwakeDuringBuilds = true
        )

        val json = AppPrefs.exportBackupJson(original)
        val restored = AppPrefs.importBackupJson(json, AppSettings())
        assertNotNull(restored)
        assertEquals(original.defaultDir, restored!!.defaultDir)
        assertEquals(original.downloadTargetMultiBootPath, restored.downloadTargetMultiBootPath)
        assertEquals(original.favoriteQuickMountPath, restored.favoriteQuickMountPath)
        assertEquals(original.keepAdbActive, restored.keepAdbActive)
        assertEquals(original.autoPatchSelinux, restored.autoPatchSelinux)
        assertEquals(original.surfaceMode, restored.surfaceMode)
        assertEquals(original.downloadConnections, restored.downloadConnections)
        assertEquals(original.buildThermalProfile, restored.buildThermalProfile)
        assertEquals(original.keepScreenAwakeDuringBuilds, restored.keepScreenAwakeDuringBuilds)
        assertEquals(1, restored.autounattendPresets.size)
        assertEquals("Workstation", restored.autounattendPresets.first().name)
        assertEquals(1, restored.systemTweaksPresets.size)
        assertEquals(listOf("telemetry", "edge"), restored.systemTweaksSearchHistory)
    }

    @Test
    fun importBackupJson_rejectsInvalidOrUnversionedPayload() {
        assertNull(AppPrefs.importBackupJson("not json", AppSettings()))
        assertNull(AppPrefs.importBackupJson("""{"defaultDir": "/sdcard/ISOs"}""", AppSettings()))
    }
}
