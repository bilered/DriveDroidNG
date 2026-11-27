package com.pocketboot.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class Wave5OtgRufusTest {

    @Test
    fun fixedDiskLayout_windowsGptUsesFullDriveAndPlacesBackupGptAtPhysicalEnd() {
        val thirtyTwoGiB = 32L * 1024L * 1024L * 1024L
        val sixGiB = 6L * 1024L * 1024L * 1024L
        val oneMiB = 1024L * 1024L

        val layout = DiskPartitioner.calculateFixedDiskLayout(
            totalDiskBytes = thirtyTwoGiB,
            minPart1Bytes = sixGiB,
            part2Bytes = oneMiB,
            scheme = PartitionScheme.GPT
        )
        assertNotNull(layout)
        val l = layout!!
        assertEquals(67_108_864L, l.totalSectors)
        assertEquals(2048L, l.part1StartLba)
        assertEquals(2048L, l.part2Sectors)
        assertEquals(l.part1StartLba + l.part1Sectors, l.part2StartLba)
        assertTrue(l.part2StartLba + l.part2Sectors <= l.totalSectors - 33L)
        assertTrue(l.part1SizeBytes > 31L * 1024L * 1024L * 1024L)

        // Verify Backup GPT header points to exact physical last sector (totalSectors - 1)
        val gpt = DiskPartitioner.buildGptBuffers(l, includeUefiPart = true)
        val backupHdr = ByteBuffer.wrap(gpt.backupSectors.copyOfRange(32 * 512, 33 * 512)).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(l.totalSectors - 1L, backupHdr.getLong(24))
        assertEquals(1L, backupHdr.getLong(32))
        assertEquals(l.totalSectors - 34L, backupHdr.getLong(48))
    }

    @Test
    fun fixedDiskLayout_enforcesTwoTiBMbrGuardAndMinimumCapacity() {
        val threeTiB = 3L * 1024L * 1024L * 1024L * 1024L
        val fourGiB = 4L * 1024L * 1024L * 1024L

        assertTrue(DiskPartitioner.isOverMbrLimit(threeTiB))
        assertEquals(PartitionScheme.GPT, DiskPartitioner.effectiveSchemeForDisk(PartitionScheme.MBR, threeTiB))

        assertNull(DiskPartitioner.calculateFixedDiskLayout(threeTiB, fourGiB, 1024L * 1024L, PartitionScheme.MBR))
        assertNotNull(DiskPartitioner.calculateFixedDiskLayout(threeTiB, fourGiB, 1024L * 1024L, PartitionScheme.GPT))
        assertNull(DiskPartitioner.calculateFixedDiskLayout(fourGiB, 6L * 1024L * 1024L * 1024L, 1024L * 1024L, PartitionScheme.GPT))
    }

    @Test
    fun fixedVentoyLayout_reservesExact32MiBEfiPartitionImmediatelyAfterPart1() {
        val sixtyFourGiB = 64L * 1024L * 1024L * 1024L
        val layout = DiskPartitioner.calculateFixedVentoyLayout(
            totalDiskBytes = sixtyFourGiB,
            minPart1Bytes = 8L * 1024L * 1024L * 1024L,
            scheme = PartitionScheme.GPT
        )
        assertNotNull(layout)
        val l = layout!!
        assertEquals(DiskPartitioner.VENTOY_EFI_SECTORS, l.part2Sectors)
        assertEquals(l.part1StartLba + l.part1Sectors, l.part2StartLba)
        assertTrue(l.part2StartLba + l.part2Sectors <= l.totalSectors - 33L)
    }

    @Test
    fun fixedLinuxFat32Layout_allocatesPersistenceAndRemainingFat32Cleanly() {
        val thirtyTwoGiB = 32L * 1024L * 1024L * 1024L
        val threeGiB = 3L * 1024L * 1024L * 1024L
        val maxPersist = DiskPartitioner.maxLinuxPersistenceGb(thirtyTwoGiB, threeGiB)
        assertTrue(maxPersist in 27..29)

        val layout = DiskPartitioner.calculateFixedLinuxFat32Layout(
            totalDiskBytes = thirtyTwoGiB,
            isoSizeBytes = threeGiB,
            persistenceGb = 12,
            includePersistence = true
        )
        assertNotNull(layout)
        val l = layout!!
        assertEquals(12L * 1024L * 1024L * 1024L, l.part2Sectors * 512L)
        assertEquals(l.part1StartLba + l.part1Sectors, l.part2StartLba)
        assertTrue(l.part1SizeBytes > 19L * 1024L * 1024L * 1024L)

        val mbr = DiskPartitioner.buildLinuxFat32MbrSector(2048L, 4096L, 6144L, 4096L, includePersistence = true)
        assertEquals(3, DiskPartitioner.findPersistenceSlotIndex(mbr))
    }

    @Test
    fun otgSafety_rejects4KnSectorsAndIsolatesSmPublicVolumes() {
        val validUsbSysfs = "/sys/devices/platform/soc/a600000.ssusb/a600000.dwc3/xhci-hcd.0.auto/usb2/2-1/2-1:1.0/host1/target1:0:0/1:0:0:0/block/sdg"
        val sixteenGiB = 16L * 1024L * 1024L * 1024L

        val (safe4k, msg4k) = OtgUsbFlasher.evaluateBlockDeviceSafety("sdg", validUsbSysfs, false, sixteenGiB, emptyList(), logicalBlockSize = 4096)
        assertFalse(safe4k)
        assertTrue(msg4k.contains("512"))

        assertTrue(OtgUsbFlasher.evaluateBlockDeviceSafety("sdg", validUsbSysfs, false, sixteenGiB, emptyList(), logicalBlockSize = 512).first)

        val smOut = listOf(
            "public:8,97 mounted ABCD-1234",
            "public:8,98 unmounted EFGH-5678",
            "public:8,113 mounted SIBLING-USB"
        )
        assertEquals(listOf("public:8,97"), OtgUsbFlasher.parseSmPublicVolumeIds(smOut, setOf("8:96", "8:97", "8:98")))

        assertEquals(OtgFlashMode.DIRECT_WINDOWS, OtgUsbFlasher.recommendModeForSource(ImageFile("/a/Win11_26H2.iso", "Win11_26H2.iso", 5L, "DL")))
        assertEquals(OtgFlashMode.DIRECT_LINUX, OtgUsbFlasher.recommendModeForSource(ImageFile("/a/ubuntu-24.04.iso", "ubuntu-24.04.iso", 5L, "DL")))
        assertEquals(OtgFlashMode.RAW_DD, OtgUsbFlasher.recommendModeForSource(ImageFile("/a/raspios.img.xz", "raspios.img.xz", 5L, "DL")))
    }

    @Test
    fun auditR2Guards_rejectGptProtectiveMbrAndWipeExactFinalSectorOnNonMiBDrives() {
        // Real SanDisk 32 GB stick: 60,062,500 sectors (30,752,000,000 B) — NOT a multiple of 1 MiB (812 trailing sectors)
        val sandiskBytes = 60_062_500L * 512L
        val tailRange = OtgUsbFlasher.computeTailWipeSectorRange(sandiskBytes)
        assertNotNull(tailRange)
        val (seekSec, countSec) = tailRange!!
        assertEquals(4096L, countSec)
        assertEquals(60_062_500L, seekSec + countSec) // Reaches exact physical last sector (totalSectors - 1)!

        // GPT Protective MBR (slot 0 type = 0xEE) must NEVER accept MBR persistence append
        val pmbr = ByteArray(512)
        pmbr[446 + 4] = 0xEE.toByte()
        pmbr[510] = 0x55.toByte()
        pmbr[511] = 0xAA.toByte()
        assertEquals(-1, DiskPartitioner.findPersistenceSlotIndex(pmbr))
        assertNull(DiskPartitioner.appendLinuxPersistenceMbr(pmbr, 4_000_000L, 4_194_304L))
    }
}
