package com.pocketboot.app

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.zip.CRC32

enum class PartitionScheme(val label: String, val subtitle: String) {
    GPT("GPT (UEFI)", "Recommended for Windows 11 & modern PCs"),
    MBR("MBR (BIOS / UEFI-CSM)", "Older Legacy BIOS PCs & CSM compatibility")
}

data class DiskLayoutSpec(
    val totalBytes: Long,
    val totalSectors: Long,
    val part1StartLba: Long,
    val part1Sectors: Long,
    val part2StartLba: Long,
    val part2Sectors: Long
) {
    val part1OffsetBytes: Long get() = part1StartLba * 512L
    val part1SizeBytes: Long get() = part1Sectors * 512L
    val part2OffsetBytes: Long get() = part2StartLba * 512L
}

data class GptBuffers(
    val primarySectors: ByteArray, // LBA 0..33 (34 sectors = 17,408 bytes: PMBR + Primary Header + Primary Entries)
    val backupSectors: ByteArray   // LBA (N-33)..(N-1) (33 sectors = 16,896 bytes: Backup Entries + Backup Header)
)

object DiskPartitioner {

    private const val SECTOR_SIZE = 512L
    private const val ALIGN_SECTORS = 2048L // 1 MiB alignment

    // Microsoft Basic Data Partition: EBD0A0A2-B9E5-4433-87C0-68B6B72699C7
    private val GUID_BASIC_DATA = uuidToMixedEndianBytes("EBD0A0A2-B9E5-4433-87C0-68B6B72699C7")

    // EFI System Partition: C12A7328-F81F-11D2-BA4B-00A0C93EC93B
    private val GUID_ESP = uuidToMixedEndianBytes("C12A7328-F81F-11D2-BA4B-00A0C93EC93B")

    // GPT attributes for UEFI:NTFS: 0xD000000000000001 (PLATFORM_REQUIRED | READ_ONLY | HIDDEN | NO_DRIVE_LETTER)
    // Hides the 1 MB boot helper partition from Windows Explorer while UEFI still boots it and Setup ignores it.
    private const val GPT_ATTR_UEFI_NTFS_HIDDEN: Long = -0x2FFFFFFFFFFFFFFFL

    // Standard minimal x86 MBR chainloader (relocates 0x7C00 -> 0x0600, scans partition table for 0x80 active flag,
    // loads VBR via INT 13h AH=42h LBA Extended Read to 0x7C00, and jumps to 0000:7C00)
    private val STANDARD_MBR_BOOTCODE = byteArrayOf(
        0x31.toByte(), 0xC0.toByte(), 0x8E.toByte(), 0xD0.toByte(), 0xBC.toByte(), 0x00.toByte(), 0x7C.toByte(), 0x8E.toByte(),
        0xC0.toByte(), 0x8E.toByte(), 0xD8.toByte(), 0xBE.toByte(), 0x00.toByte(), 0x7C.toByte(), 0xBF.toByte(), 0x00.toByte(),
        0x06.toByte(), 0xB9.toByte(), 0x00.toByte(), 0x01.toByte(), 0xFC.toByte(), 0xF3.toByte(), 0xA5.toByte(), 0xEA.toByte(),
        0x1C.toByte(), 0x06.toByte(), 0x00.toByte(), 0x00.toByte(), 0xBE.toByte(), 0xBE.toByte(), 0x07.toByte(), 0xB3.toByte(),
        0x04.toByte(), 0x80.toByte(), 0x3C.toByte(), 0x80.toByte(), 0x74.toByte(), 0x0E.toByte(), 0x83.toByte(), 0xC6.toByte(),
        0x10.toByte(), 0xFE.toByte(), 0xCB.toByte(), 0x75.toByte(), 0xF4.toByte(), 0xCD.toByte(), 0x18.toByte(), 0xEB.toByte(),
        0xFE.toByte(), 0x8B.toByte(), 0x44.toByte(), 0x08.toByte(), 0x8B.toByte(), 0x5C.toByte(), 0x0A.toByte(), 0x56.toByte(),
        0x66.toByte(), 0x6A.toByte(), 0x00.toByte(), 0x53.toByte(), 0x50.toByte(), 0x68.toByte(), 0x00.toByte(), 0x00.toByte(),
        0x68.toByte(), 0x00.toByte(), 0x7C.toByte(), 0x68.toByte(), 0x01.toByte(), 0x00.toByte(), 0x68.toByte(), 0x10.toByte(),
        0x00.toByte(), 0x89.toByte(), 0xE6.toByte(), 0xB4.toByte(), 0x42.toByte(), 0xCD.toByte(), 0x13.toByte(), 0x83.toByte(),
        0xC4.toByte(), 0x10.toByte(), 0x5E.toByte(), 0x72.toByte(), 0xDA.toByte(), 0x81.toByte(), 0x3E.toByte(), 0xFE.toByte(),
        0x7D.toByte(), 0x55.toByte(), 0xAA.toByte(), 0x75.toByte(), 0xD2.toByte(), 0xEA.toByte(), 0x00.toByte(), 0x7C.toByte(),
        0x00.toByte(), 0x00.toByte()
    )

    fun calculateLayout(isoSizeBytes: Long, uefiNtfsBytes: Long): DiskLayoutSpec {
        // Allocate ISO size + 12.5% overhead (min 384 MiB) so filesystem metadata + autounattend.xml always fit cleanly
        val overhead = (isoSizeBytes / 8L).coerceAtLeast(384L * 1024L * 1024L)
        val rawMainBytes = (isoSizeBytes + overhead).coerceAtLeast(1024L * 1024L * 1024L)
        val part1Sectors = ((rawMainBytes + (ALIGN_SECTORS * SECTOR_SIZE) - 1) / (ALIGN_SECTORS * SECTOR_SIZE)) * ALIGN_SECTORS

        val uefiSectorsRaw = ((uefiNtfsBytes + SECTOR_SIZE - 1) / SECTOR_SIZE).coerceAtLeast(ALIGN_SECTORS)
        val part2Sectors = ((uefiSectorsRaw + ALIGN_SECTORS - 1) / ALIGN_SECTORS) * ALIGN_SECTORS

        val part1StartLba = ALIGN_SECTORS // 1 MiB
        val part2StartLba = part1StartLba + part1Sectors
        // Leave 1 MiB (2048 sectors) after Partition 2 for Backup GPT header & alignment
        val totalSectors = part2StartLba + part2Sectors + ALIGN_SECTORS
        val totalBytes = totalSectors * SECTOR_SIZE

        return DiskLayoutSpec(
            totalBytes = totalBytes,
            totalSectors = totalSectors,
            part1StartLba = part1StartLba,
            part1Sectors = part1Sectors,
            part2StartLba = part2StartLba,
            part2Sectors = part2Sectors
        )
    }

    fun buildMbrSector(
        layout: DiskLayoutSpec,
        part1IsFat32: Boolean,
        includeUefiPart: Boolean
    ): ByteArray {
        val mbr = ByteArray(512)
        System.arraycopy(STANDARD_MBR_BOOTCODE, 0, mbr, 0, STANDARD_MBR_BOOTCODE.size)

        val buf = ByteBuffer.wrap(mbr).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(440, (System.currentTimeMillis() and 0xFFFFFFFFL).toInt())

        // Partition 1 entry at offset 446 (bootable 0x80)
        val p1Type: Byte = if (part1IsFat32) 0x0C.toByte() else 0x07.toByte()
        writeMbrEntry(buf, 446, bootable = 0x80.toByte(), type = p1Type, startLba = layout.part1StartLba, sectors = layout.part1Sectors)

        // Partition 2 entry at offset 462 (UEFI:NTFS ESP type 0xEF for UEFI-CSM boot)
        if (includeUefiPart) {
            writeMbrEntry(buf, 462, bootable = 0x00, type = 0xEF.toByte(), startLba = layout.part2StartLba, sectors = layout.part2Sectors)
        }

        mbr[510] = 0x55.toByte()
        mbr[511] = 0xAA.toByte()
        return mbr
    }

    fun buildLinuxFat32MbrSector(
        part1StartLba: Long,
        part1Sectors: Long,
        part2StartLba: Long = 0L,
        part2Sectors: Long = 0L,
        includePersistence: Boolean = false
    ): ByteArray {
        val mbr = ByteArray(512)
        System.arraycopy(STANDARD_MBR_BOOTCODE, 0, mbr, 0, STANDARD_MBR_BOOTCODE.size)
        val buf = ByteBuffer.wrap(mbr).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(440, (System.currentTimeMillis() and 0xFFFFFFFFL).toInt())
        writeMbrEntry(buf, 446, bootable = 0x80.toByte(), type = 0x0C.toByte(), startLba = part1StartLba, sectors = part1Sectors)
        if (includePersistence && part2Sectors > 0L) {
            writeMbrEntry(buf, 462, bootable = 0x00, type = 0x83.toByte(), startLba = part2StartLba, sectors = part2Sectors)
        }
        mbr[510] = 0x55.toByte()
        mbr[511] = 0xAA.toByte()
        return mbr
    }

    const val VENTOY_EFI_SECTORS = 65536L // Exact 32 MiB VTOYEFI partition

    fun calculateVentoyLayoutMb(dataSizeMb: Int, scheme: PartitionScheme): DiskLayoutSpec {
        val part1StartLba = ALIGN_SECTORS // 2048 (1 MiB)
        val clampedMb = dataSizeMb.coerceIn(512, 131072).toLong()
        val part1Sectors = (clampedMb * 1024L * 1024L) / SECTOR_SIZE
        val part2StartLba = part1StartLba + part1Sectors
        val part2Sectors = VENTOY_EFI_SECTORS
        // GPT needs 33 trailing sectors (32 backup entry sectors + 1 backup header sector)
        val totalSectors = if (scheme == PartitionScheme.GPT) {
            part2StartLba + part2Sectors + 33L
        } else {
            part2StartLba + part2Sectors
        }
        return DiskLayoutSpec(
            totalBytes = totalSectors * SECTOR_SIZE,
            totalSectors = totalSectors,
            part1StartLba = part1StartLba,
            part1Sectors = part1Sectors,
            part2StartLba = part2StartLba,
            part2Sectors = part2Sectors
        )
    }

    fun buildVentoyMbrSector(layout: DiskLayoutSpec, bootImgBytes: ByteArray): ByteArray {
        val mbr = buildMbrSector(
            layout = layout,
            part1IsFat32 = false,
            includeUefiPart = true
        )
        val copyLen = bootImgBytes.size.coerceAtMost(446)
        System.arraycopy(bootImgBytes, 0, mbr, 0, copyLen)
        return mbr
    }

    fun buildVentoyGptBuffers(layout: DiskLayoutSpec, bootImgBytes: ByteArray): GptBuffers {
        val gpt = buildGptBuffers(
            layout = layout,
            includeUefiPart = true,
            part1Name = "PocketBoot",
            part2Name = "VTOYEFI",
            part2IsEsp = true,
            part2Attributes = 0L
        )
        // Patch Ventoy boot.img (446 bytes) into LBA 0 Protective MBR + GPT core.img start LBA marker (0x22 = LBA 34) at offset 92
        val copyLen = bootImgBytes.size.coerceAtMost(446)
        System.arraycopy(bootImgBytes, 0, gpt.primarySectors, 0, copyLen)
        gpt.primarySectors[92] = 0x22.toByte()
        return gpt
    }

    fun buildGptBuffers(
        layout: DiskLayoutSpec,
        includeUefiPart: Boolean,
        part1Name: String = "Main Data Partition",
        part2Name: String = "UEFI:NTFS",
        part2IsEsp: Boolean = false,
        part2Attributes: Long = GPT_ATTR_UEFI_NTFS_HIDDEN
    ): GptBuffers {
        val totalSectors = layout.totalSectors
        val firstUsableLba = 34L
        val lastUsableLba = totalSectors - 34L
        val backupArrayLba = totalSectors - 33L
        val backupHeaderLba = totalSectors - 1L

        // 1. Protective MBR at LBA 0
        val pmbr = ByteArray(512)
        val pBuf = ByteBuffer.wrap(pmbr).order(ByteOrder.LITTLE_ENDIAN)
        writeMbrEntry(
            buf = pBuf,
            offset = 446,
            bootable = 0x00,
            type = 0xEE.toByte(),
            startLba = 1L,
            sectors = (totalSectors - 1L).coerceAtMost(0xFFFFFFFFL)
        )
        pmbr[510] = 0x55.toByte()
        pmbr[511] = 0xAA.toByte()

        // 2. Partition Entry Array (128 entries * 128 bytes = 16,384 bytes = 32 sectors)
        val entriesBytes = ByteArray(128 * 128)
        // Entry 0: Main Windows Data Partition
        writeGptEntry(
            target = entriesBytes,
            index = 0,
            typeGuid = GUID_BASIC_DATA,
            uniqueGuid = randomMixedEndianGuid(),
            firstLba = layout.part1StartLba,
            lastLba = layout.part1StartLba + layout.part1Sectors - 1L,
            name = part1Name
        )
        // Entry 1: UEFI:NTFS (Microsoft Basic Data + 0x9000000000000000 like official Rufus so Windows Setup
        // never mistakes the 1 MB USB partition for a target EFI System Partition; Ventoy explicitly sets part2IsEsp = true)
        if (includeUefiPart) {
            writeGptEntry(
                target = entriesBytes,
                index = 1,
                typeGuid = if (part2IsEsp) GUID_ESP else GUID_BASIC_DATA,
                uniqueGuid = randomMixedEndianGuid(),
                firstLba = layout.part2StartLba,
                lastLba = layout.part2StartLba + layout.part2Sectors - 1L,
                name = part2Name,
                attributes = part2Attributes
            )
        }

        val entriesCrc = crc32(entriesBytes)
        val diskGuid = randomMixedEndianGuid()

        // 3. Primary GPT Header at LBA 1
        val primaryHeader = buildGptHeader(
            currentLba = 1L,
            backupLba = backupHeaderLba,
            firstUsableLba = firstUsableLba,
            lastUsableLba = lastUsableLba,
            diskGuid = diskGuid,
            partEntryLba = 2L,
            entriesCrc = entriesCrc
        )

        // 4. Backup GPT Header at last LBA
        val backupHeader = buildGptHeader(
            currentLba = backupHeaderLba,
            backupLba = 1L,
            firstUsableLba = firstUsableLba,
            lastUsableLba = lastUsableLba,
            diskGuid = diskGuid,
            partEntryLba = backupArrayLba,
            entriesCrc = entriesCrc
        )

        // Assemble primary buffer: LBA 0 (PMBR) + LBA 1 (Primary Header) + LBA 2..33 (Primary Entries)
        val primarySectors = ByteArray(34 * 512)
        System.arraycopy(pmbr, 0, primarySectors, 0, 512)
        System.arraycopy(primaryHeader, 0, primarySectors, 512, 512)
        System.arraycopy(entriesBytes, 0, primarySectors, 2 * 512, entriesBytes.size)

        // Assemble backup buffer: LBA N-33..N-2 (Backup Entries) + LBA N-1 (Backup Header)
        val backupSectors = ByteArray(33 * 512)
        System.arraycopy(entriesBytes, 0, backupSectors, 0, entriesBytes.size)
        System.arraycopy(backupHeader, 0, backupSectors, entriesBytes.size, 512)

        return GptBuffers(primarySectors = primarySectors, backupSectors = backupSectors)
    }

    private fun writeMbrEntry(
        buf: ByteBuffer,
        offset: Int,
        bootable: Byte,
        type: Byte,
        startLba: Long,
        sectors: Long
    ) {
        buf.put(offset, bootable)
        // Dummy LBA-forced CHS values (FE FF FF)
        buf.put(offset + 1, 0xFE.toByte())
        buf.put(offset + 2, 0xFF.toByte())
        buf.put(offset + 3, 0xFF.toByte())
        buf.put(offset + 4, type)
        buf.put(offset + 5, 0xFE.toByte())
        buf.put(offset + 6, 0xFF.toByte())
        buf.put(offset + 7, 0xFF.toByte())
        buf.putInt(offset + 8, startLba.toInt())
        buf.putInt(offset + 12, sectors.coerceAtMost(0xFFFFFFFFL).toInt())
    }

    private fun writeGptEntry(
        target: ByteArray,
        index: Int,
        typeGuid: ByteArray,
        uniqueGuid: ByteArray,
        firstLba: Long,
        lastLba: Long,
        name: String,
        attributes: Long = 0L
    ) {
        val base = index * 128
        System.arraycopy(typeGuid, 0, target, base, 16)
        System.arraycopy(uniqueGuid, 0, target, base + 16, 16)
        // Always index from base so Entry 1+ never overwrites Entry 0
        val buf = ByteBuffer.wrap(target).order(ByteOrder.LITTLE_ENDIAN)
        buf.putLong(base + 32, firstLba)
        buf.putLong(base + 40, lastLba)
        buf.putLong(base + 48, attributes)
        val chars = name.take(35)
        for (i in chars.indices) {
            buf.putShort(base + 56 + i * 2, chars[i].code.toShort())
        }
    }

    private fun buildGptHeader(
        currentLba: Long,
        backupLba: Long,
        firstUsableLba: Long,
        lastUsableLba: Long,
        diskGuid: ByteArray,
        partEntryLba: Long,
        entriesCrc: Int
    ): ByteArray {
        val hdr = ByteArray(512)
        val buf = ByteBuffer.wrap(hdr).order(ByteOrder.LITTLE_ENDIAN)
        "EFI PART".toByteArray(Charsets.US_ASCII).copyInto(hdr, 0)
        buf.putInt(8, 0x00010000) // Revision 1.0
        buf.putInt(12, 92)        // Header size
        buf.putInt(16, 0)         // Header CRC32 placeholder
        buf.putInt(20, 0)         // Reserved
        buf.putLong(24, currentLba)
        buf.putLong(32, backupLba)
        buf.putLong(40, firstUsableLba)
        buf.putLong(48, lastUsableLba)
        System.arraycopy(diskGuid, 0, hdr, 56, 16)
        buf.putLong(72, partEntryLba)
        buf.putInt(80, 128)       // Number of partition entries
        buf.putInt(84, 128)       // Size of each partition entry
        buf.putInt(88, entriesCrc)

        val headerCrc = crc32(hdr, 0, 92)
        buf.putInt(16, headerCrc)
        return hdr
    }

    private fun crc32(data: ByteArray, offset: Int = 0, length: Int = data.size): Int {
        val crc = CRC32()
        crc.update(data, offset, length)
        return crc.value.toInt()
    }

    private fun randomMixedEndianGuid(): ByteArray = uuidToMixedEndianBytes(UUID.randomUUID().toString())

    private fun uuidToMixedEndianBytes(uuidStr: String): ByteArray {
        val uuid = UUID.fromString(uuidStr)
        val msb = uuid.mostSignificantBits
        val lsb = uuid.leastSignificantBits
        val out = ByteArray(16)
        val d1 = (msb ushr 32).toInt()
        out[0] = (d1 and 0xFF).toByte()
        out[1] = ((d1 ushr 8) and 0xFF).toByte()
        out[2] = ((d1 ushr 16) and 0xFF).toByte()
        out[3] = ((d1 ushr 24) and 0xFF).toByte()
        val d2 = ((msb ushr 16) and 0xFFFF).toInt()
        out[4] = (d2 and 0xFF).toByte()
        out[5] = ((d2 ushr 8) and 0xFF).toByte()
        val d3 = (msb and 0xFFFF).toInt()
        out[6] = (d3 and 0xFF).toByte()
        out[7] = ((d3 ushr 8) and 0xFF).toByte()
        for (i in 0 until 8) {
            out[8 + i] = ((lsb ushr (56 - i * 8)) and 0xFF).toByte()
        }
        return out
    }

    /**
     * Appends a Linux ext4 persistence partition entry (type 0x83) into the first unused
     * MBR slot (entries 1..3, 0-indexed) of an isohybrid .img file's LBA 0 sector.
     */
    fun appendLinuxPersistenceMbr(
        originalMbrSector: ByteArray,
        startLba: Long,
        sectors: Long
    ): ByteArray? {
        if (originalMbrSector.size < 512) return null
        val mbr = originalMbrSector.copyOf(512)
        val buf = ByteBuffer.wrap(mbr).order(ByteOrder.LITTLE_ENDIAN)

        var targetOffset = -1
        for (slot in 1..3) {
            val off = 446 + slot * 16
            val pType = mbr[off + 4].toInt() and 0xFF
            val pSectors = buf.getInt(off + 12)
            if (pType == 0 && pSectors == 0) {
                targetOffset = off
                break
            }
        }
        if (targetOffset < 0) return null

        writeMbrEntry(
            buf = buf,
            offset = targetOffset,
            bootable = 0x00,
            type = 0x83.toByte(),
            startLba = startLba,
            sectors = sectors
        )
        mbr[510] = 0x55.toByte()
        mbr[511] = 0xAA.toByte()
        return mbr
    }
}
