package com.pocketboot.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Wave4FeaturesTest {

    @Test
    fun compressedImageEngine_detectsFormatsDerivesNamesAndParsesSizes() {
        assertEquals(CompressedFormat.XZ, CompressedImageEngine.detectFormat("raspios-bookworm.img.xz"))
        assertEquals(CompressedFormat.GZ, CompressedImageEngine.detectFormat("openwrt-24.10.img.gz"))
        assertEquals(CompressedFormat.ZST, CompressedImageEngine.detectFormat("archlinux-2026.iso.zst"))
        assertNull(CompressedImageEngine.detectFormat("Win11_26H2.iso"))
        assertNull(CompressedImageEngine.detectFormat("archive.tar.gz"))

        assertEquals("raspios-bookworm.img", CompressedImageEngine.deriveOutputFileName("raspios-bookworm.img.xz"))
        assertEquals("archlinux-2026.iso", CompressedImageEngine.deriveOutputFileName("archlinux-2026.iso.zst"))
        assertEquals(".img", CompressedImageEngine.targetExtensionFor("openwrt.img.gz"))
        assertEquals(".iso", CompressedImageEngine.targetExtensionFor("fedora.iso.xz"))

        assertTrue(IsoInspector.isCompressedImageName("kali-pi.img.xz"))
        assertEquals(IsoOsType.COMPRESSED_IMAGE, IsoInspector.quickGuessByName("kali-pi.img.xz"))
        assertTrue(IsoInspector.isSupportedDriveFileName("ubuntu.iso.zst"))

        // 7-Zip -slt parser (ignores pre-separator header block, reads post-separator entry size)
        assertEquals(
            4_294_967_296L,
            CompressedImageEngine.parse7zUncompressedSize(
                listOf(
                    "Path = disk.img.xz",
                    "Type = xz",
                    "Physical Size = 812345678",
                    "----------",
                    "Path = disk.img",
                    "Size = 4294967296",
                    "Packed Size = 812345678"
                )
            )
        )

        // zstd -lv parser (known content size vs streaming unknown frame)
        assertEquals(
            4_294_967_296L,
            CompressedImageEngine.parseZstdUncompressedSize(
                listOf("Frames  Skips  Compressed  Uncompressed", "Decompressed Size: 4.00 GiB (4294967296 B)")
            )
        )
        assertEquals(
            -1L,
            CompressedImageEngine.parseZstdUncompressedSize(
                listOf("Decompressed Size: unknown")
            )
        )

        // GZip 32-bit ISIZE wrap detection (>4 GiB file wrapped below compressed size)
        assertTrue(CompressedImageEngine.isGzipWrapSuspected(compressedBytes = 1_600_000_000L, reportedUncompressedBytes = 400_000_000L))
        assertFalse(CompressedImageEngine.isGzipWrapSuspected(compressedBytes = 600_000_000L, reportedUncompressedBytes = 3_200_000_000L))
    }

    @Test
    fun dualLunComboValidation_rejectsCompressedImages() {
        val err = UsbGadgetEngine.validateDualLunComboPaths(
            "/data/media/0/Download/raspios.img.xz",
            "/data/media/0/Download/tools.img"
        )
        assertNotNull(err)
        assertTrue(err!!.contains("Compressed", ignoreCase = true))
    }

    @Test
    fun otgUsbFlasher_sevenLayerAntiBrickSafetyMatrix() {
        val normalMounts = listOf(
            "/dev/block/sda14 /data f2fs rw 0 0",
            "/dev/block/sda1 /boot ext4 ro 0 0",
            "/dev/block/dm-0 /system ext4 ro 0 0",
            "/dev/block/sdg1 /mnt/media_rw/ABCD-1234 vfat rw 0 0"
        )
        val validUsbSysfs = "/sys/devices/platform/soc/a600000.ssusb/a600000.dwc3/xhci-hcd.0.auto/usb2/2-1/2-1:1.0/host1/target1:0:0/1:0:0:0/block/sdg"
        val sixteenGb = 16L * 1024L * 1024L * 1024L

        // 1. Valid external USB-C OTG pen drive -> ALLOWED
        assertTrue(OtgUsbFlasher.evaluateBlockDeviceSafety("sdg", validUsbSysfs, false, sixteenGb, normalMounts).first)

        // 2. Samsung/Snapdragon internal UFS phone storage (/dev/block/sda) -> REJECTED
        val ufsSysfs = "/sys/devices/platform/soc/1d84000.ufshc/host0/target0:0:0/0:0:0:0/block/sda"
        assertFalse(OtgUsbFlasher.evaluateBlockDeviceSafety("sda", ufsSysfs, false, 256L * 1024 * 1024 * 1024, normalMounts).first)

        // 3. Internal eMMC / SD card slot -> REJECTED
        assertFalse(OtgUsbFlasher.evaluateBlockDeviceSafety("mmcblk0", "/sys/devices/platform/soc/mmc0/block/mmcblk0", false, sixteenGb, normalMounts).first)

        // 4. Loop device -> REJECTED
        assertFalse(OtgUsbFlasher.evaluateBlockDeviceSafety("loop0", "/sys/devices/virtual/block/loop0", false, sixteenGb, normalMounts).first)

        // 5. Device-mapper / zram -> REJECTED
        assertFalse(OtgUsbFlasher.evaluateBlockDeviceSafety("dm-0", "/sys/devices/virtual/block/dm-0", false, sixteenGb, normalMounts).first)
        assertFalse(OtgUsbFlasher.evaluateBlockDeviceSafety("zram0", "/sys/devices/virtual/block/zram0", false, sixteenGb, normalMounts).first)

        // 6. Partition node instead of whole disk -> REJECTED
        assertFalse(OtgUsbFlasher.evaluateBlockDeviceSafety("sdg1", validUsbSysfs, false, sixteenGb, normalMounts).first)

        // 7. Hardware write-protected (read-only) USB drive -> REJECTED
        assertFalse(OtgUsbFlasher.evaluateBlockDeviceSafety("sdg", validUsbSysfs, true, sixteenGb, normalMounts).first)

        // 8. Undersized bogus device (<64 MB) -> REJECTED
        assertFalse(OtgUsbFlasher.evaluateBlockDeviceSafety("sdg", validUsbSysfs, false, 16L * 1024 * 1024, normalMounts).first)

        // 9. Even a fake /usb1/ sysfs path mounted on protected /data -> REJECTED
        val poisonedMounts = normalMounts + "/dev/block/sdg2 /data/adb ext4 rw 0 0"
        assertFalse(OtgUsbFlasher.evaluateBlockDeviceSafety("sdg", validUsbSysfs, false, sixteenGb, poisonedMounts).first)

        // 10. Source fit & /sys/block/<dev>/stat parser
        assertNull(OtgUsbFlasher.validateSourceFitsDevice(4L * 1024 * 1024 * 1024, sixteenGb))
        assertNotNull(OtgUsbFlasher.validateSourceFitsDevice(32L * 1024 * 1024 * 1024, sixteenGb))
        assertEquals(20480L, OtgUsbFlasher.parseSectorsWrittenFromStat("120 0 960 40 300 0 20480 150 0 90 190"))
    }

    @Test
    fun otgUsbFlasher_extractTargetMountPointsIsolatesExactVoldMinorsAndDecodesSpaces() {
        val mounts = listOf(
            "/dev/block/sda14 /data f2fs rw 0 0",
            "/dev/block/vold/public:8,97 /mnt/media_rw/MY\\040USB vfat rw 0 0",
            "/dev/block/vold/public:8,113 /mnt/media_rw/OTHER_HUB_USB vfat rw 0 0",
            "/dev/block/sdg1 /storage/ABCD-1234 fuse rw 0 0",
            "/dev/block/sdh1 /storage/OTHER-9999 fuse rw 0 0"
        )
        // Target is sdg (8:96) and its partition sdg1 (8:97) ONLY — must NOT touch sdh (8:113) or sda!
        val matched = OtgUsbFlasher.extractTargetMountPoints("sdg", setOf("8:96", "8:97"), mounts)
        assertEquals(listOf("/mnt/media_rw/MY USB", "/storage/ABCD-1234"), matched)
    }

    @Test
    fun compressedSizeValidator_acceptsExactGzip32BitWrapsAndRejectsTruncation() {
        val fiveGiB = 5L * 1024L * 1024L * 1024L
        val oneGiB = 1L * 1024L * 1024L * 1024L
        // 5 GiB mod 4 GiB = 1 GiB -> valid GZip wrap!
        assertTrue(CompressedImageEngine.isExtractedSizeValid(CompressedFormat.GZ, expectedBytes = oneGiB, actualBytes = fiveGiB))
        // Truncated GZip (5 GiB - 100 bytes) -> rejected!
        assertFalse(CompressedImageEngine.isExtractedSizeValid(CompressedFormat.GZ, expectedBytes = oneGiB, actualBytes = fiveGiB - 100L))
        // Non-GZip (XZ) mismatch -> strictly rejected!
        assertFalse(CompressedImageEngine.isExtractedSizeValid(CompressedFormat.XZ, expectedBytes = oneGiB, actualBytes = fiveGiB))
        // Exact match -> accepted
        assertTrue(CompressedImageEngine.isExtractedSizeValid(CompressedFormat.XZ, expectedBytes = oneGiB, actualBytes = oneGiB))
    }

    @Test
    fun otgSha256RangeCommand_usesSectorAlignedDdForIsoSizesWithoutOneByteReads() {
        // 4,700,372,992 bytes = 4482 MiB + 1296 sectors (typical Windows ISO, 2048-byte aligned)
        val cmd = OtgUsbFlasher.buildSha256RangeCommand("/dev/block/sdg", 4_700_372_992L)
        assertTrue(cmd.contains("bs=1048576 count=4482"))
        assertTrue(cmd.contains("bs=512 skip=9179136 count=1296"))
        assertFalse(cmd.contains("bs=1 "))
    }
}
