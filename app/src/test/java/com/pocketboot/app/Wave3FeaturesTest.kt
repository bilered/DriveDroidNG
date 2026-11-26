package com.pocketboot.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Wave3FeaturesTest {

    @Test
    fun driverPackManager_sanitizesSlugsAndCategorizesDriversAccurately() {
        assertEquals("Intel_VMD_Gen13", DriverPackManager.sanitizePackSlug("../../Intel VMD Gen13*?"))
        assertEquals("driver_pack", DriverPackManager.sanitizePackSlug("...///"))

        assertEquals(
            DriverPackCategory.INTEL_STORAGE,
            DriverPackManager.detectCategory("Storage", listOf("iaStorVD.inf", "iaStorAC.inf"))
        )
        assertEquals(
            DriverPackCategory.AMD_STORAGE,
            DriverPackManager.detectCategory("RAID", listOf("rcraid.inf", "rcbottom.inf"))
        )
        assertEquals(
            DriverPackCategory.NETWORK,
            DriverPackManager.detectCategory("Laptop_Net", listOf("Netwtw6e.inf", "rtwlane.inf"))
        )
        assertEquals(
            DriverPackCategory.CUSTOM,
            DriverPackManager.detectCategory("Audio_Pack", listOf("hdxrt.inf"))
        )
    }

    @Test
    fun appPrefs_roundTripsDualLunCombosInBackupJson() {
        val combo = DualLunCombo(
            id = "combo_1",
            name = "Win11 + Drivers",
            lun0Path = "/data/media/0/ISOs/Win11.iso",
            lun0Mode = MountMode.CDROM.name,
            lun1Path = "/data/media/0/ISOs/Drivers.img",
            lun1Mode = MountMode.USB_RW.name
        )
        val original = AppSettings(dualLunCombos = listOf(combo))
        val json = AppPrefs.exportBackupJson(original)
        val restored = AppPrefs.importBackupJson(json, AppSettings())

        assertNotNull(restored)
        assertEquals(1, restored!!.dualLunCombos.size)
        assertEquals("Win11 + Drivers", restored.dualLunCombos.first().name)
        assertEquals("/data/media/0/ISOs/Win11.iso", restored.dualLunCombos.first().lun0Path)
        assertEquals(MountMode.CDROM.name, restored.dualLunCombos.first().lun0Mode)
        assertEquals("/data/media/0/ISOs/Drivers.img", restored.dualLunCombos.first().lun1Path)
        assertEquals(MountMode.USB_RW.name, restored.dualLunCombos.first().lun1Mode)
    }

    @Test
    fun usbGadgetEngine_validatesDualLunComboPaths() {
        assertNull(
            UsbGadgetEngine.validateDualLunComboPaths(
                "/data/media/0/ISOs/Win11.iso",
                "/data/media/0/ISOs/Drivers.img"
            )
        )
        assertNotNull(UsbGadgetEngine.validateDualLunComboPaths("", "/data/media/0/ISOs/Drivers.img"))
        val sameErr = UsbGadgetEngine.validateDualLunComboPaths(
            "/sdcard/ISOs/Win11.iso",
            "/data/media/0/ISOs/Win11.iso"
        )
        assertNotNull(sameErr)
        assertTrue(sameErr!!.contains("different", ignoreCase = true))
    }

    @Test
    fun auditGuards_preventRecursiveDriverImportSelfImageImportAndFat32Overflow() {
        assertNotNull(
            DriverPackManager.validateImportPaths(
                "/data/media/0/Download",
                "/data/media/0/Download/PocketBoot_Drivers/Download"
            )
        )
        assertNull(
            DriverPackManager.validateImportPaths(
                "/data/media/0/Drivers/IntelVMD",
                "/data/media/0/Download/PocketBoot_Drivers/IntelVMD"
            )
        )

        assertTrue(
            ImageLoopExplorer.isSelfImportPath(
                "/sdcard/Download/MultiBoot.img",
                "/data/media/0/Download/MultiBoot.img"
            )
        )
        assertFalse(
            ImageLoopExplorer.isSelfImportPath(
                "/sdcard/Download/drivers.zip",
                "/data/media/0/Download/MultiBoot.img"
            )
        )

        val fourGb = 4L * 1024L * 1024L * 1024L
        val twoGb = 2L * 1024L * 1024L * 1024L
        assertTrue(VentoyPersistenceHelper.isFat32PersistenceTooLarge("FAT32", fourGb))
        assertFalse(VentoyPersistenceHelper.isFat32PersistenceTooLarge("FAT32", twoGb))
        assertFalse(VentoyPersistenceHelper.isFat32PersistenceTooLarge("exFAT", fourGb))
        assertFalse(VentoyPersistenceHelper.isFat32PersistenceTooLarge("NTFS (ntfs3)", fourGb))
    }
}
