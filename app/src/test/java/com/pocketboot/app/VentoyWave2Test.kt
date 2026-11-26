package com.pocketboot.app

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VentoyWave2Test {

    @Test
    fun ventoyThemeCatalog_preservesSiblingSectionsAcrossMenuAndBypassEdits() {
        val root = JSONObject().apply {
            put("control", JSONArray()
                .put(JSONObject().put("VTOY_WIN11_BYPASS_CHECK", "1"))
                .put(JSONObject().put("VTOY_WIN11_BYPASS_NRO", "1"))
                .put(JSONObject().put("VTOY_DEFAULT_KBD_LAYOUT", "US")))
            put("auto_install", JSONArray().put(JSONObject().put("image", "/Win11.iso")))
            put("injection", JSONArray().put(JSONObject().put("image", "/Win11.iso").put("archive", "/ventoy/drivers_inject.zip")))
            put("persistence", JSONArray().put(JSONObject().put("image", "/ubuntu.iso").put("backend", "/ventoy/persistence/ubuntu_casper-rw.dat")))
        }

        val menuCfg = MultiBootMenuConfig(
            themeId = MultiBootThemePreset.POCKETBOOT_DARK.id,
            menuTimeoutSec = 15,
            menuAliases = mapOf(
                "/Win11.iso" to "Windows 11 Pro (Clean)",
                "/ubuntu.iso" to "Ubuntu 26.04 LTS"
            )
        )
        VentoyThemeCatalog.applyMenuConfigPreservingAll(root, menuCfg, setOf("/Win11.iso", "/ubuntu.iso"))

        val readBack = VentoyThemeCatalog.readMenuConfig(root)
        assertEquals(MultiBootThemePreset.POCKETBOOT_DARK.id, readBack.themeId)
        assertEquals(15, readBack.menuTimeoutSec)
        assertEquals("Windows 11 Pro (Clean)", readBack.menuAliases["/Win11.iso"])
        assertEquals("Ubuntu 26.04 LTS", readBack.menuAliases["/ubuntu.iso"])

        assertNotNull(root.optJSONArray("auto_install"))
        assertNotNull(root.optJSONArray("injection"))
        assertEquals(1, VentoyThemeCatalog.readPersistenceEntries(root).size)

        VentoyThemeCatalog.mergeBypassControlPreservingTimeout(root, bypassCheck = false, bypassNro = true)
        assertEquals(15, VentoyThemeCatalog.readMenuConfig(root).menuTimeoutSec)
        assertTrue(root.getJSONArray("control").toString().contains("VTOY_DEFAULT_KBD_LAYOUT"))

        VentoyThemeCatalog.applyMenuConfigPreservingAll(root, readBack.copy(themeId = MultiBootThemePreset.STOCK.id, menuTimeoutSec = 0))
        assertNull(root.optJSONObject("theme"))
        assertEquals(0, VentoyThemeCatalog.readMenuConfig(root).menuTimeoutSec)
    }

    @Test
    fun ventoyThemeCatalog_upsertsAndRemovesPersistenceEntriesCleanly() {
        val root = JSONObject()
        VentoyThemeCatalog.upsertPersistenceEntry(root, "ubuntu.iso", "/ventoy/persistence/ubuntu_casper-rw.dat")
        VentoyThemeCatalog.upsertPersistenceEntry(root, "/kali.iso", "/ventoy/persistence/kali_persistence.dat")
        val entries = VentoyThemeCatalog.readPersistenceEntries(root)
        assertEquals(2, entries.size)

        VentoyThemeCatalog.upsertPersistenceEntry(root, "/ubuntu.iso", "/ventoy/persistence/ubuntu_new.dat")
        assertEquals(2, VentoyThemeCatalog.readPersistenceEntries(root).size)

        val (_, removed) = VentoyThemeCatalog.removePersistenceEntry(root, "/kali.iso")
        assertEquals("/ventoy/persistence/kali_persistence.dat", removed)
        assertEquals(1, VentoyThemeCatalog.readPersistenceEntries(root).size)
    }

    @Test
    fun ventoyPersistenceHelper_sanitizesBackendPathsAndEligibility() {
        val ubPath = VentoyPersistenceHelper.buildPersistenceBackendPath("/ISO/Ubuntu 26.04 (LTS).iso", LinuxPersistenceStyle.UBUNTU_WRITABLE)
        assertTrue(ubPath.startsWith("/ventoy/persistence/"))
        assertTrue(ubPath.endsWith("_writable.dat"))
        assertFalse(ubPath.contains(" "))

        val popPath = VentoyPersistenceHelper.buildPersistenceBackendPath("pop-os_24.04.iso", LinuxPersistenceStyle.CASPER_RW)
        assertTrue(popPath.endsWith("_casper-rw.dat"))

        val kaliPath = VentoyPersistenceHelper.buildPersistenceBackendPath("../evil/kali-linux-2026.iso", LinuxPersistenceStyle.DEBIAN_KALI)
        assertTrue(kaliPath.endsWith("_persistence.dat"))
        assertFalse(kaliPath.contains(".."))

        assertEquals(LinuxPersistenceStyle.DEBIAN_KALI, VentoyPersistenceHelper.guessStyleForIso("kali-linux-2026.1-live-amd64.iso"))
        assertEquals(LinuxPersistenceStyle.CASPER_RW, VentoyPersistenceHelper.guessStyleForIso("pop-os_24.04_amd64.iso"))
        assertEquals(LinuxPersistenceStyle.UBUNTU_WRITABLE, VentoyPersistenceHelper.guessStyleForIso("ubuntu-26.04.1-desktop-amd64.iso"))

        assertTrue(VentoyPersistenceHelper.isLinuxPersistenceEligible("ubuntu-26.04.iso"))
        assertFalse(VentoyPersistenceHelper.isLinuxPersistenceEligible("Win11_26H2_x64.iso"))
        assertFalse(VentoyPersistenceHelper.isLinuxPersistenceEligible("disk.img"))
    }

    @Test
    fun ventoyImageBuilder_resolvesAndSerializesPerIsoMultiTemplateProfiles() {
        val driveDefault = WinUtilTweaksConfig(darkThemeDefault = true)
        val customJson = WinUtilTweaksConfig.desktopPreset().copy(removeEdge = true).toJsonString()
        val customMap = mapOf("custom:CleanGaming" to customJson)

        assertEquals(driveDefault, VentoyImageBuilder.resolveProfileWinUtil(VentoyImageBuilder.PROFILE_ID_DEFAULT, driveDefault, customMap))
        assertTrue(VentoyImageBuilder.resolveProfileWinUtil(VentoyImageBuilder.PROFILE_ID_MINIMAL, driveDefault, customMap).hasAnyActive())
        assertTrue(VentoyImageBuilder.resolveProfileWinUtil(VentoyImageBuilder.PROFILE_ID_LAPTOP, driveDefault, customMap).hasAnyActive())
        assertTrue(VentoyImageBuilder.resolveProfileWinUtil("custom:CleanGaming", driveDefault, customMap).removeEdge)
        assertEquals("custom_cleangaming", VentoyImageBuilder.profileSlug("custom:CleanGaming"))

        val encoded = VentoyImageBuilder.encodeIsoProfileIdsJson(
            mapOf("Win11.iso" to listOf(VentoyImageBuilder.PROFILE_ID_DEFAULT, VentoyImageBuilder.PROFILE_ID_MINIMAL, VentoyImageBuilder.PROFILE_ID_DEFAULT))
        )
        val decoded = VentoyImageBuilder.parseIsoProfileIdsJson(encoded)
        assertEquals(listOf(VentoyImageBuilder.PROFILE_ID_DEFAULT, VentoyImageBuilder.PROFILE_ID_MINIMAL), decoded["/Win11.iso"])
    }

    @Test
    fun imageLoopExplorer_parsesGptMbrRawSlicesAndSanitizesFolderNames() {
        val layout = DiskPartitioner.calculateLayout(5L * 1024L * 1024L * 1024L, 1024L * 1024L)
        val gpt = DiskPartitioner.buildGptBuffers(layout, includeUefiPart = true, part2IsEsp = false)
        val gptSlice = ImageLoopExplorer.parsePartition1Slice(gpt.primarySectors, layout.totalBytes)
        assertEquals("GPT Part 1", gptSlice.schemeLabel)
        assertEquals(layout.part1OffsetBytes, gptSlice.offsetBytes)
        assertEquals(layout.part1SizeBytes, gptSlice.sizeBytes)

        val mbr = DiskPartitioner.buildMbrSector(layout, part1IsFat32 = false, includeUefiPart = true)
        val mbrSlice = ImageLoopExplorer.parsePartition1Slice(mbr, layout.totalBytes)
        assertEquals("MBR Part 1", mbrSlice.schemeLabel)
        assertEquals(layout.part1OffsetBytes, mbrSlice.offsetBytes)
        assertEquals(layout.part1SizeBytes, mbrSlice.sizeBytes)

        val rawSlice = ImageLoopExplorer.parsePartition1Slice(ByteArray(1024), 512L * 1024L * 1024L)
        assertEquals("Raw Image", rawSlice.schemeLabel)
        assertEquals(0L, rawSlice.offsetBytes)
        assertEquals(512L * 1024L * 1024L, rawSlice.sizeBytes)

        assertEquals("Drivers", ImageLoopExplorer.sanitizeFolderName("../Drivers*?:"))
        assertEquals("", ImageLoopExplorer.sanitizeFolderName("...***"))
    }
}
