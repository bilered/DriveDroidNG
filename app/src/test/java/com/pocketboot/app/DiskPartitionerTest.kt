package com.pocketboot.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32

class DiskPartitionerTest {

    @Test
    fun calculateLayout_alignsPartitionsAndFitsLargeWindowsIso() {
        val isoBytes = 6L * 1024L * 1024L * 1024L
        val uefiBytes = 1024L * 1024L
        val layout = DiskPartitioner.calculateLayout(isoBytes, uefiBytes)

        assertEquals(2048L, layout.part1StartLba)
        assertEquals(0L, layout.part1Sectors % 2048L)
        assertTrue(layout.part1SizeBytes > isoBytes)
        assertEquals(layout.part1StartLba + layout.part1Sectors, layout.part2StartLba)
        assertTrue(layout.part2Sectors * 512L >= uefiBytes)
        assertTrue(layout.totalSectors > layout.part2StartLba + layout.part2Sectors)
        assertEquals(layout.totalSectors * 512L, layout.totalBytes)
    }

    @Test
    fun buildMbrSector_setsBootSignatureAndExpectedPartitionTypes() {
        val layout = DiskPartitioner.calculateLayout(5L * 1024L * 1024L * 1024L, 1024L * 1024L)
        val mbr = DiskPartitioner.buildMbrSector(layout, part1IsFat32 = false, includeUefiPart = true)

        assertEquals(512, mbr.size)
        assertEquals(0x55.toByte(), mbr[510])
        assertEquals(0xAA.toByte(), mbr[511])
        assertEquals(0x80.toByte(), mbr[446])
        assertEquals(0x07.toByte(), mbr[450])
        assertEquals(0xEF.toByte(), mbr[466])
    }

    @Test
    fun buildGptBuffers_writesValidHeadersCrcAndHiddenUefiNtfsAttributes() {
        val layout = DiskPartitioner.calculateLayout(5L * 1024L * 1024L * 1024L, 1024L * 1024L)
        val gpt = DiskPartitioner.buildGptBuffers(layout, includeUefiPart = true, part2IsEsp = false)

        assertEquals(34 * 512, gpt.primarySectors.size)
        assertEquals(33 * 512, gpt.backupSectors.size)

        // Protective MBR signature
        assertEquals(0x55.toByte(), gpt.primarySectors[510])
        assertEquals(0xAA.toByte(), gpt.primarySectors[511])
        assertEquals(0xEE.toByte(), gpt.primarySectors[450])

        // Primary GPT header at LBA 1 (offset 512)
        val primarySig = String(gpt.primarySectors, 512, 8, Charsets.US_ASCII)
        assertEquals("EFI PART", primarySig)

        val headerCopy = gpt.primarySectors.copyOfRange(512, 512 + 92)
        val storedCrc = ByteBuffer.wrap(headerCopy).order(ByteOrder.LITTLE_ENDIAN).getInt(16)
        headerCopy[16] = 0; headerCopy[17] = 0; headerCopy[18] = 0; headerCopy[19] = 0
        val crc = CRC32().apply { update(headerCopy) }.value.toInt()
        assertEquals(storedCrc, crc)

        // Partition 2 entry (index 1 -> LBA 2 + 128 bytes) must carry hidden UEFI:NTFS attributes
        val p2AttrOffset = 2 * 512 + 128 + 48
        val p2Attr = ByteBuffer.wrap(gpt.primarySectors.copyOfRange(p2AttrOffset, p2AttrOffset + 8)).order(ByteOrder.LITTLE_ENDIAN).long
        assertEquals(-0x2FFFFFFFFFFFFFFFL, p2Attr)

        // Backup GPT header at end of backupSectors (offset 32 * 512)
        val backupSig = String(gpt.backupSectors, 32 * 512, 8, Charsets.US_ASCII)
        assertEquals("EFI PART", backupSig)
    }

    @Test
    fun appendLinuxPersistenceMbr_appendsIntoFirstFreeSlotAndFailsWhenFull() {
        val baseMbr = DiskPartitioner.buildLinuxFat32MbrSector(2048L, 2_097_152L, includePersistence = false)
        val patched = DiskPartitioner.appendLinuxPersistenceMbr(baseMbr, 2_099_200L, 4_194_304L)
        assertNotNull(patched)
        assertEquals(0x83.toByte(), patched!![462 + 4])

        // Fill all 4 slots -> should return null cleanly
        val fullMbr = baseMbr.copyOf()
        for (slot in 0 until 4) {
            fullMbr[446 + slot * 16 + 4] = 0x83.toByte()
        }
        assertNull(DiskPartitioner.appendLinuxPersistenceMbr(fullMbr, 8_000_000L, 2_097_152L))
    }
}
