package com.pocketboot.app

import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Eject
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.AspectRatio
import androidx.compose.material.icons.rounded.CleaningServices
import androidx.compose.material.icons.rounded.SystemUpdateAlt
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding

enum class VentoyFilesystem(val label: String, val subtitle: String, val badge: String? = null) {
    AUTO("Auto", "Fast exFAT when supported, else universal NTFS", "Recommended"),
    NTFS("NTFS", "Universal via bundled FUSE"),
    EXFAT("exFAT", "Fast native kernel format")
}

private enum class MultiBootSizeUnit(val label: String) {
    MB("MB"),
    GB("GB")
}

data class VentoyAssetInfo(
    val version: String,
    val isUpdatedInApp: Boolean,
    val ready: Boolean
)

data class VentoyBootBundle(
    val bootImg: File,
    val coreImgXz: File,
    val diskImgXz: File,
    val version: String
)

data class VentoyDiskInfo(
    val scheme: PartitionScheme,
    val part1OffsetBytes: Long,
    val part1SizeBytes: Long,
    val part2StartLba: Long,
    val part2Sectors: Long,
    val totalBytes: Long
)

data class VentoyIsoItem(
    val name: String,
    val relativePath: String,
    val sizeBytes: Long
)

data class VentoyDriveContents(
    val info: VentoyDiskInfo,
    val fsType: String,
    val totalBytes: Long,
    val freeBytes: Long,
    val files: List<VentoyIsoItem>,
    val win11BypassCheck: Boolean,
    val win11BypassNro: Boolean,
    val savedExperience: WindowsExperienceConfig = WindowsExperienceConfig(bypassRequirements = win11BypassCheck, bypassMicrosoftAccount = win11BypassNro),
    val savedWinUtil: WinUtilTweaksConfig = WinUtilTweaksConfig(),
    val hasDriversZip: Boolean = false
)

data class VentoyBuildConfig(
    val outputDir: String,
    val outputName: String,
    val sizeMb: Int = 8192,
    val scheme: PartitionScheme = PartitionScheme.GPT,
    val filesystem: VentoyFilesystem = VentoyFilesystem.AUTO,
    val win11BypassCheck: Boolean = true,
    val win11BypassNro: Boolean = true,
    val experience: WindowsExperienceConfig = WindowsExperienceConfig(bypassRequirements = win11BypassCheck, bypassMicrosoftAccount = win11BypassNro),
    val winUtil: WinUtilTweaksConfig = WinUtilTweaksConfig(),
    val driversDir: String = "",
    val initialIsoToAdd: ImageFile? = null,
    val moveInitialIso: Boolean = false,
    val autoMountAfterBuild: Boolean = true
)

object VentoyImageBuilder {

    private const val VENTOY_LATEST_API = "https://api.github.com/repos/ventoy/Ventoy/releases/latest"
    private const val TEMP_VENTOY_MNT = "/data/local/tmp/pb_ventoy_mnt"
    private const val ASSET_DIR = "ventoy"

    private fun nowTs(): String = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())

    private fun shellQuote(value: String): String = "'${value.replace("'", "'\\''")}'"

    private fun userVentoyDir(context: Context): File = File(context.filesDir, "ventoy")

    fun getInstalledVentoyInfo(context: Context): VentoyAssetInfo {
        val uDir = userVentoyDir(context)
        val uBoot = File(uDir, "boot.img")
        val uCore = File(uDir, "core.img.xz")
        val uDisk = File(uDir, "ventoy.disk.img.xz")
        val uVer = File(uDir, "version.txt")
        if (uBoot.exists() && uCore.exists() && uDisk.exists() && uDisk.length() > 100_000L) {
            val ver = runCatching { uVer.readText().trim() }.getOrDefault("").ifBlank { "Updated" }
            return VentoyAssetInfo(version = ver, isUpdatedInApp = true, ready = true)
        }

        val bundledReady = runCatching {
            val list = context.assets.list(ASSET_DIR)?.toSet().orEmpty()
            "boot.img" in list && "core.img.xz" in list && "ventoy.disk.img.xz" in list
        }.getOrDefault(false)

        if (bundledReady) {
            val ver = runCatching {
                context.assets.open("$ASSET_DIR/version.txt").bufferedReader().use { it.readText().trim() }
            }.getOrDefault("Bundled")
            return VentoyAssetInfo(version = ver, isUpdatedInApp = false, ready = true)
        }

        return VentoyAssetInfo(version = "Not installed", isUpdatedInApp = false, ready = false)
    }

    fun resetVentoyToBundled(context: Context): Boolean {
        return runCatching {
            userVentoyDir(context).deleteRecursively()
        }.getOrDefault(false)
    }

    private fun resolveBootBundle(context: Context): VentoyBootBundle? {
        val uDir = userVentoyDir(context)
        val uBoot = File(uDir, "boot.img")
        val uCore = File(uDir, "core.img.xz")
        val uDisk = File(uDir, "ventoy.disk.img.xz")
        val uVer = File(uDir, "version.txt")
        if (uBoot.exists() && uCore.exists() && uDisk.exists() && uDisk.length() > 100_000L) {
            val ver = runCatching { uVer.readText().trim() }.getOrDefault("Updated")
            return VentoyBootBundle(uBoot, uCore, uDisk, ver)
        }

        val cacheDir = File(context.cacheDir, "ventoy_bundled").apply { mkdirs() }
        val cBoot = File(cacheDir, "boot.img")
        val cCore = File(cacheDir, "core.img.xz")
        val cDisk = File(cacheDir, "ventoy.disk.img.xz")

        return runCatching {
            listOf("boot.img" to cBoot, "core.img.xz" to cCore, "ventoy.disk.img.xz" to cDisk).forEach { (assetName, dest) ->
                context.assets.open("$ASSET_DIR/$assetName").use { input ->
                    FileOutputStream(dest).use { output -> input.copyTo(output) }
                }
            }
            val ver = runCatching {
                context.assets.open("$ASSET_DIR/version.txt").bufferedReader().use { it.readText().trim() }
            }.getOrDefault("Bundled")
            if (cBoot.exists() && cCore.exists() && cDisk.length() > 100_000L) {
                VentoyBootBundle(cBoot, cCore, cDisk, ver)
            } else null
        }.getOrNull()
    }

    /**
     * Checks GitHub for the latest official Ventoy Linux release, downloads ventoy-X.Y.Z-linux.tar.gz,
     * extracts boot.img, core.img.xz, and ventoy.disk.img.xz into app-private storage,
     * overriding the bundled APK assets.
     */
    suspend fun updateVentoyFromGitHub(
        context: Context,
        onProgress: (WindowsBuildProgress) -> Unit
    ): ActionResult = withContext(Dispatchers.IO) {
        val ts = nowTs()
        val entries = mutableListOf<LogEntry>()
        val dlFile = File(context.cacheDir, "ventoy_release.tar.gz")
        val extractDir = File(context.cacheDir, "ventoy_extract")

        return@withContext runCatching {
            onProgress(WindowsBuildProgress("Checking for updates...", "Querying latest Multi-Boot engine release", 0.08f, true))

            val apiConn = (URL(VENTOY_LATEST_API).openConnection() as HttpURLConnection).apply {
                connectTimeout = 12000
                readTimeout = 15000
                setRequestProperty("User-Agent", "PocketBoot-Android")
                setRequestProperty("Accept", "application/vnd.github+json")
            }
            if (apiConn.responseCode !in 200..299) {
                return@withContext ActionResult(false, "GitHub API returned HTTP ${apiConn.responseCode}.", entries = entries)
            }
            val jsonText = apiConn.inputStream.bufferedReader().use { it.readText() }
            val root = JSONObject(jsonText)
            val tag = root.optString("tag_name", "").removePrefix("v").trim()
            val assets = root.optJSONArray("assets") ?: JSONArray()
            var downloadUrl = ""
            var assetName = ""
            for (i in 0 until assets.length()) {
                val a = assets.optJSONObject(i) ?: continue
                val n = a.optString("name", "")
                if (n.endsWith("-linux.tar.gz")) {
                    downloadUrl = a.optString("browser_download_url", "")
                    assetName = n
                    break
                }
            }
            if (downloadUrl.isBlank() || tag.isBlank()) {
                return@withContext ActionResult(false, "Could not find Multi-Boot engine archive in latest release.", entries = entries)
            }

            entries.add(LogEntry(ts, "ventoy-upd", "Found Ventoy v$tag ($assetName)"))
            onProgress(WindowsBuildProgress("Downloading Engine v$tag...", assetName, 0.15f, false))

            val dlConn = (URL(downloadUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15000
                readTimeout = 30000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "PocketBoot-Android")
            }
            if (dlConn.responseCode !in 200..299) {
                return@withContext ActionResult(false, "Download failed (HTTP ${dlConn.responseCode}).", entries = entries)
            }
            val totalLen = dlConn.contentLengthLong
            var downloaded = 0L
            dlFile.delete()
            dlConn.inputStream.use { input ->
                FileOutputStream(dlFile).use { output ->
                    val buf = ByteArray(65536)
                    while (true) {
                        val r = input.read(buf)
                        if (r <= 0) break
                        output.write(buf, 0, r)
                        downloaded += r
                        if (totalLen > 0L) {
                            val pct = 0.15f + (downloaded.toFloat() / totalLen.toFloat()) * 0.65f
                            onProgress(
                                WindowsBuildProgress(
                                    "Downloading Engine v$tag...",
                                    "${UsbGadgetEngine.formatBytes(downloaded)} / ${UsbGadgetEngine.formatBytes(totalLen)}",
                                    pct,
                                    false
                                )
                            )
                        }
                    }
                }
            }

            onProgress(WindowsBuildProgress("Extracting boot engine...", "Preparing offline boot files", 0.86f, true))
            extractDir.deleteRecursively()
            extractDir.mkdirs()

            val qTar = shellQuote(dlFile.absolutePath)
            val qExt = shellQuote(extractDir.absolutePath)
            val sevenZip = WindowsImageBuilder.findBundledBinary(context, "lib7zzs.so")

            val extCmd = if (sevenZip != null) {
                val q7z = shellQuote(sevenZip)
                "(tar -xzf $qTar -C $qExt 2>/dev/null) || ($q7z x -y -so $qTar 2>/dev/null | $q7z x -y -si -ttar -o$qExt 2>&1)"
            } else {
                "tar -xzf $qTar -C $qExt 2>&1"
            }
            Shell.cmd(extCmd).exec()

            val foundBoot = extractDir.walkTopDown().firstOrNull { it.isFile && it.name == "boot.img" }
            val foundCore = extractDir.walkTopDown().firstOrNull { it.isFile && it.name == "core.img.xz" }
            val foundDisk = extractDir.walkTopDown().firstOrNull { it.isFile && it.name == "ventoy.disk.img.xz" }

            if (foundBoot == null || foundCore == null || foundDisk == null || foundDisk.length() < 100_000L) {
                return@withContext ActionResult(false, "Downloaded archive did not contain expected boot engine files.", entries = entries)
            }

            val targetDir = userVentoyDir(context).apply { mkdirs() }
            foundBoot.copyTo(File(targetDir, "boot.img"), overwrite = true)
            foundCore.copyTo(File(targetDir, "core.img.xz"), overwrite = true)
            foundDisk.copyTo(File(targetDir, "ventoy.disk.img.xz"), overwrite = true)
            File(targetDir, "version.txt").writeText(tag)

            onProgress(WindowsBuildProgress("Updated to Engine v$tag!", "Ready for offline multi-boot builds", 1f, false))
            entries.add(LogEntry(ts, "ventoy-upd", "Installed Ventoy v$tag boot files in app storage"))
            ActionResult(true, "Updated Multi-Boot engine to v$tag.", entries = entries)
        }.getOrElse { e ->
            entries.add(LogEntry(ts, "ventoy-upd", "Update error: ${e.message}", true))
            ActionResult(false, "Multi-Boot engine update failed: ${e.message ?: "Network error"}", entries = entries)
        }.also {
            dlFile.delete()
            extractDir.deleteRecursively()
        }
    }

    /**
     * Quickly probes an .img file to see if it is a genuine Ventoy Multi-Boot disk image
     * (works for both PocketBoot-created and PC Ventoy2Disk-created GPT/MBR images).
     */
    suspend fun probeVentoyImage(imgPath: String): VentoyDiskInfo? = withContext(Dispatchers.IO) {
        runCatching {
            val resolved = UsbGadgetEngine.resolveDirectPath(imgPath)
            val qPath = shellQuote(resolved)
            val totalBytes = Shell.cmd("stat -c '%s' $qPath 2>/dev/null")
                .exec().out.firstOrNull()?.trim()?.toLongOrNull() ?: return@withContext null
            if (totalBytes < 64L * 1024L * 1024L) return@withContext null

            val hdrTmp = File("/data/local/tmp/pb_vtoy_probe_${System.nanoTime()}.bin")
            val qHdr = shellQuote(hdrTmp.absolutePath)
            try {
                // Read LBA 0..3 (2048 bytes: MBR + GPT Header + first 8 GPT entries)
                val ok = Shell.cmd("dd if=$qPath of=$qHdr bs=512 count=4 2>/dev/null && chmod 0644 $qHdr").exec().isSuccess
                if (!ok || !hdrTmp.exists() || hdrTmp.length() < 2048L) return@withContext null
                val raw = hdrTmp.readBytes()
                val buf = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)

                // Check for GPT signature "EFI PART" at offset 512
                val isGpt = String(raw, 512, 8, Charsets.US_ASCII) == "EFI PART"
                val scheme: PartitionScheme
                val p1Start: Long
                val p1Sectors: Long
                val p2Start: Long
                val p2Sectors: Long

                if (isGpt) {
                    scheme = PartitionScheme.GPT
                    // Entry 0 at 1024, Entry 1 at 1152
                    val e0First = buf.getLong(1024 + 32)
                    val e0Last = buf.getLong(1024 + 40)
                    val e1First = buf.getLong(1152 + 32)
                    val e1Last = buf.getLong(1152 + 40)
                    if (e0First <= 0L || e0Last <= e0First || e1First <= 0L || e1Last <= e1First) return@withContext null
                    p1Start = e0First
                    p1Sectors = e0Last - e0First + 1L
                    p2Start = e1First
                    p2Sectors = e1Last - e1First + 1L
                } else {
                    scheme = PartitionScheme.MBR
                    val p2Type = raw[462 + 4].toInt() and 0xFF
                    if (p2Type != 0xEF) return@withContext null
                    p1Start = buf.getInt(446 + 8).toLong() and 0xFFFFFFFFL
                    p1Sectors = buf.getInt(446 + 12).toLong() and 0xFFFFFFFFL
                    p2Start = buf.getInt(462 + 8).toLong() and 0xFFFFFFFFL
                    p2Sectors = buf.getInt(462 + 12).toLong() and 0xFFFFFFFFL
                }

                // Ventoy Partition 2 is always 32 MiB (65536 sectors) with FAT16 volume label "VTOYEFI" at byte 43
                if (p2Sectors != DiskPartitioner.VENTOY_EFI_SECTORS) return@withContext null
                val label = Shell.cmd(
                    "dd if=$qPath bs=512 skip=$p2Start count=1 2>/dev/null | dd bs=1 skip=43 count=11 2>/dev/null | tr -cd 'A-Za-z0-9'"
                ).exec().out.firstOrNull().orEmpty().uppercase()

                if (!label.contains("VTOYEFI")) return@withContext null

                VentoyDiskInfo(
                    scheme = scheme,
                    part1OffsetBytes = p1Start * 512L,
                    part1SizeBytes = p1Sectors * 512L,
                    part2StartLba = p2Start,
                    part2Sectors = p2Sectors,
                    totalBytes = totalBytes
                )
            } finally {
                Shell.cmd("rm -f $qHdr 2>/dev/null").exec()
            }
        }.getOrNull()
    }

    private fun decompressXzFile(context: Context, xzFile: File, outFile: File): Boolean {
        val sevenZip = WindowsImageBuilder.findBundledBinary(context, "lib7zzs.so")
        val workDir = File(context.cacheDir, "vtoy_xz_${System.nanoTime()}").apply { mkdirs() }
        return try {
            outFile.delete()
            if (sevenZip != null) {
                val res = Shell.cmd(
                    "${shellQuote(sevenZip)} e -y -o${shellQuote(workDir.absolutePath)} ${shellQuote(xzFile.absolutePath)} 2>&1"
                ).exec()
                val extracted = workDir.listFiles()?.firstOrNull { it.isFile && it.length() > 0L }
                if (res.isSuccess && extracted != null) {
                    extracted.copyTo(outFile, overwrite = true)
                    return true
                }
            }
            val fallback = Shell.cmd("xz -dc ${shellQuote(xzFile.absolutePath)} > ${shellQuote(outFile.absolutePath)} 2>/dev/null").exec()
            fallback.isSuccess && outFile.exists() && outFile.length() > 0L
        } finally {
            workDir.deleteRecursively()
        }
    }

    private fun writeVentoyBootStructures(
        context: Context,
        outPath: String,
        scheme: PartitionScheme,
        layout: DiskLayoutSpec,
        bundle: VentoyBootBundle,
        writePartitionTable: Boolean
    ): String? {
        val qOut = shellQuote(outPath)
        val bootBytes = bundle.bootImg.readBytes()
        if (bootBytes.size < 446) return "Invalid Ventoy boot.img"

        val coreRaw = File(context.cacheDir, "vtoy_core.img")
        val efiRaw = File(context.cacheDir, "vtoy_efi.img")
        try {
            if (!decompressXzFile(context, bundle.coreImgXz, coreRaw)) {
                return "Failed to decompress Ventoy core.img.xz"
            }
            if (!decompressXzFile(context, bundle.diskImgXz, efiRaw)) {
                return "Failed to decompress Ventoy ventoy.disk.img.xz"
            }

            if (writePartitionTable) {
                if (scheme == PartitionScheme.GPT) {
                    val gpt = DiskPartitioner.buildVentoyGptBuffers(layout, bootBytes)
                    val fPrim = File(context.cacheDir, "pb_vtoy_prim.bin").apply { writeBytes(gpt.primarySectors) }
                    val fBack = File(context.cacheDir, "pb_vtoy_back.bin").apply { writeBytes(gpt.backupSectors) }
                    try {
                        val res = Shell.cmd(
                            "dd if=${shellQuote(fPrim.absolutePath)} of=$qOut bs=512 seek=0 count=34 conv=notrunc 2>&1 && " +
                                "dd if=${shellQuote(fBack.absolutePath)} of=$qOut bs=512 seek=${layout.totalSectors - 33L} count=33 conv=notrunc 2>&1"
                        ).exec()
                        if (!res.isSuccess) return (res.out + res.err).joinToString(" ").take(200)
                    } finally {
                        fPrim.delete()
                        fBack.delete()
                    }
                } else {
                    val mbr = DiskPartitioner.buildVentoyMbrSector(layout, bootBytes)
                    val fMbr = File(context.cacheDir, "pb_vtoy_mbr.bin").apply { writeBytes(mbr) }
                    try {
                        val res = Shell.cmd("dd if=${shellQuote(fMbr.absolutePath)} of=$qOut bs=512 seek=0 count=1 conv=notrunc 2>&1").exec()
                        if (!res.isSuccess) return (res.out + res.err).joinToString(" ").take(200)
                    } finally {
                        fMbr.delete()
                    }
                }
            } else {
                // Non-destructive bootloader upgrade: patch ONLY bytes 0..445 (+ byte 92=0x22 on GPT), keep partition table intact!
                val boot446 = bootBytes.copyOf(446)
                if (scheme == PartitionScheme.GPT) {
                    boot446[92] = 0x22.toByte()
                }
                val fBoot = File(context.cacheDir, "pb_vtoy_446.bin").apply { writeBytes(boot446) }
                try {
                    val res = Shell.cmd("dd if=${shellQuote(fBoot.absolutePath)} of=$qOut bs=1 seek=0 count=446 conv=notrunc 2>&1").exec()
                    if (!res.isSuccess) return (res.out + res.err).joinToString(" ").take(200)
                } finally {
                    fBoot.delete()
                }
            }

            // Write core.img (LBA 34 + byte 17908=0x23 for GPT; LBA 1 for MBR)
            if (scheme == PartitionScheme.GPT) {
                val coreBytes = coreRaw.readBytes()
                val patchedCore = coreBytes.copyOf(coreBytes.size.coerceAtMost(2014 * 512))
                // Byte 17908 on disk = (34 * 512) + 500 -> offset 500 inside core.img!
                if (patchedCore.size > 500) {
                    patchedCore[500] = 0x23.toByte()
                }
                val fCore = File(context.cacheDir, "pb_vtoy_core_gpt.bin").apply { writeBytes(patchedCore) }
                try {
                    val res = Shell.cmd("dd if=${shellQuote(fCore.absolutePath)} of=$qOut bs=512 seek=34 count=2014 conv=notrunc 2>&1").exec()
                    if (!res.isSuccess) return (res.out + res.err).joinToString(" ").take(200)
                } finally {
                    fCore.delete()
                }
            } else {
                val res = Shell.cmd("dd if=${shellQuote(coreRaw.absolutePath)} of=$qOut bs=512 seek=1 count=2047 conv=notrunc 2>&1").exec()
                if (!res.isSuccess) return (res.out + res.err).joinToString(" ").take(200)
            }

            // Write full 32 MiB VTOYEFI partition at part2StartLba (no pipe short-reads!)
            val efiRes = Shell.cmd(
                "dd if=${shellQuote(efiRaw.absolutePath)} of=$qOut bs=512 seek=${layout.part2StartLba} count=${DiskPartitioner.VENTOY_EFI_SECTORS} conv=notrunc 2>&1"
            ).exec()
            if (!efiRes.isSuccess) return (efiRes.out + efiRes.err).joinToString(" ").take(200)

            return null
        } finally {
            coreRaw.delete()
            efiRaw.delete()
        }
    }

    private fun attachPartition1Loop(imgPath: String, offsetBytes: Long, sizeBytes: Long): String {
        val qImg = shellQuote(imgPath)
        val cmd = """
            L=${'$'}(losetup -f -s -o $offsetBytes -S $sizeBytes $qImg 2>/dev/null)
            if [ -z "${'$'}L" ]; then
                L=${'$'}(losetup -f --show -o $offsetBytes --sizelimit $sizeBytes $qImg 2>/dev/null)
            fi
            if [ -z "${'$'}L" ]; then
                CAND=${'$'}(losetup -f 2>/dev/null)
                if [ -n "${'$'}CAND" ]; then
                    if losetup -o $offsetBytes -S $sizeBytes "${'$'}CAND" $qImg 2>/dev/null || \
                       losetup -o $offsetBytes --sizelimit $sizeBytes "${'$'}CAND" $qImg 2>/dev/null; then
                        L="${'$'}CAND"
                    fi
                fi
            fi
            echo "${'$'}L"
        """.trimIndent()
        return Shell.cmd(cmd).exec().out.lastOrNull().orEmpty().trim()
    }

    private fun mountVentoyPartition1(context: Context, loopDev: String): String? {
        val qLoop = shellQuote(loopDev)
        val procFs = Shell.cmd("cat /proc/filesystems 2>/dev/null").exec().out
            .map { it.trim().substringAfterLast('\t').substringAfterLast(' ').lowercase() }
            .filter { it.isNotEmpty() }
            .toSet()
        val ntfs3gBin = WindowsImageBuilder.findBundledBinary(
            context,
            "libntfs_3g.so",
            listOf("ntfs-3g", "/system/bin/ntfs-3g")
        )
        val hasFuse = ("fuse" in procFs || "fuseblk" in procFs) && Shell.cmd("[ -e /dev/fuse ]").exec().isSuccess

        Shell.cmd("mkdir -p $TEMP_VENTOY_MNT && umount $TEMP_VENTOY_MNT 2>/dev/null || true").exec()

        val candidates = mutableListOf<Pair<String, String>>()
        if ("ntfs3" in procFs) candidates.add("NTFS (ntfs3)" to "mount -t ntfs3 -o rw $qLoop $TEMP_VENTOY_MNT 2>&1")
        if ("tntfs" in procFs) candidates.add("NTFS (tntfs)" to "mount -t tntfs -o rw $qLoop $TEMP_VENTOY_MNT 2>&1")
        if ("exfat" in procFs) candidates.add("exFAT" to "mount -t exfat -o rw $qLoop $TEMP_VENTOY_MNT 2>&1")
        if ("sdfat" in procFs) candidates.add("exFAT (sdfat)" to "mount -t sdfat -o rw $qLoop $TEMP_VENTOY_MNT 2>&1")
        if (ntfs3gBin != null && hasFuse) {
            val qN3g = shellQuote(ntfs3gBin)
            candidates.add("NTFS (ntfs-3g FUSE)" to "$qN3g -o rw,big_writes,noatime $qLoop $TEMP_VENTOY_MNT 2>&1")
            candidates.add("NTFS (ntfs-3g FUSE)" to "$qN3g -o rw $qLoop $TEMP_VENTOY_MNT 2>&1")
        }
        candidates.add("FAT32" to "mount -t vfat -o rw $qLoop $TEMP_VENTOY_MNT 2>&1")
        candidates.add("Auto" to "mount -o rw $qLoop $TEMP_VENTOY_MNT 2>&1")

        for ((label, cmd) in candidates) {
            if (Shell.cmd(cmd).exec().isSuccess) return label
        }
        return null
    }

    private fun unmountVentoyPartition1(loopDev: String) {
        Shell.cmd(
            """
            sync 2>/dev/null
            umount $TEMP_VENTOY_MNT 2>/dev/null || umount -l $TEMP_VENTOY_MNT 2>/dev/null
            sleep 1
            if [ -n ${shellQuote(loopDev)} ]; then
                losetup -d ${shellQuote(loopDev)} 2>/dev/null || (sleep 1 && losetup -d ${shellQuote(loopDev)} 2>/dev/null)
            fi
            rm -rf $TEMP_VENTOY_MNT 2>/dev/null
            """.trimIndent()
        ).exec()
    }

    private fun findWindowsIsoRelPathsOnMounted(): List<String> {
        val out = Shell.cmd("cd $TEMP_VENTOY_MNT 2>/dev/null && find . -maxdepth 2 -type f -iname '*.iso' ! -path './ventoy/*' 2>/dev/null || true").exec().out
        return out.map { it.trim().removePrefix("./") }
            .filter { rel -> rel.isNotBlank() && IsoInspector.quickGuessByName(rel.substringAfterLast('/')) == IsoOsType.WINDOWS }
            .map { "/$it" }.distinct().sorted()
    }

    private fun packDriversInjectionOnMounted(context: Context, driversDir: String): Boolean {
        if (driversDir.isBlank()) return false
        val srcDir = UsbGadgetEngine.resolveDirectPath(driversDir).trimEnd('/')
        val sevenZip = WindowsImageBuilder.findBundledBinary(context, "lib7zzs.so", listOf("7zz", "7z")) ?: return false
        val stageDir = "/data/local/tmp/pb_vtoy_drv_stage"; val tmpZip = "/data/local/tmp/pb_vtoy_drivers.zip"
        val tmpBat = File(context.cacheDir, "VentoyAutoRun.bat").apply {
            writeText("@echo off\r\nif exist \"X:\\Drivers\" (for /r \"X:\\Drivers\" %%F in (*.inf) do drvload \"%%~fF\" >nul 2>&1)\r\nexit /b 0\r\n", Charsets.UTF_8)
        }
        return try {
            Shell.cmd("""
                if [ -d ${shellQuote(srcDir)} ]; then
                    rm -rf $stageDir $tmpZip && mkdir -p $stageDir/Drivers && cp -rf ${shellQuote(srcDir)}/. $stageDir/Drivers/ &&
                    cp -f ${shellQuote(tmpBat.absolutePath)} $stageDir/VentoyAutoRun.bat &&
                    cd $stageDir && ${shellQuote(sevenZip)} a -tzip -y $tmpZip . >/dev/null 2>&1 &&
                    mkdir -p $TEMP_VENTOY_MNT/ventoy && cp -f $tmpZip $TEMP_VENTOY_MNT/ventoy/drivers_inject.zip && rm -rf $stageDir $tmpZip
                else exit 1; fi
            """.trimIndent()).exec().isSuccess
        } finally { tmpBat.delete() }
    }

    private fun writeVentoyJsonOnMounted(context: Context, bypassCheck: Boolean, bypassNro: Boolean, experience: WindowsExperienceConfig? = null, winUtil: WinUtilTweaksConfig? = null, driversDir: String? = null) {
        val existingRaw = Shell.cmd("cat $TEMP_VENTOY_MNT/ventoy/ventoy.json 2>/dev/null").exec().out.joinToString("\n")
        val rootObj = runCatching { if (existingRaw.isNotBlank()) JSONObject(existingRaw) else JSONObject() }.getOrDefault(JSONObject())
        rootObj.put("control", JSONArray().apply {
            put(JSONObject().put("VTOY_WIN11_BYPASS_CHECK", if (bypassCheck) "1" else "0"))
            put(JSONObject().put("VTOY_WIN11_BYPASS_NRO", if (bypassNro) "1" else "0"))
        })
        if (experience != null && winUtil != null) {
            if (experience.hasAnyActive() || winUtil.hasAnyActive()) {
                val tmpXml = File(context.cacheDir, "pocketboot_unattend.xml").apply { writeText(WindowsCustomizationEngine.generateVentoySelfContainedXml(experience, winUtil, "amd64"), Charsets.UTF_8) }
                try { Shell.cmd("mkdir -p $TEMP_VENTOY_MNT/ventoy/scripts && cp -f ${shellQuote(tmpXml.absolutePath)} $TEMP_VENTOY_MNT/ventoy/scripts/pocketboot_unattend.xml").exec() } finally { tmpXml.delete() }
            } else Shell.cmd("rm -f $TEMP_VENTOY_MNT/ventoy/scripts/pocketboot_unattend.xml 2>/dev/null").exec()
            val metaObj = JSONObject().apply {
                put("bypassRequirements", experience.bypassRequirements)
                put("bypassMicrosoftAccount", experience.bypassMicrosoftAccount)
                put("disableDataCollection", experience.disableDataCollection)
                put("disableBitLocker", experience.disableBitLocker)
                put("qolImprovements", experience.qolImprovements)
                put("disableSponsoredApps", experience.disableSponsoredApps)
                put("classicContextMenu", experience.classicContextMenu)
                put("skipWifiSetup", experience.skipWifiSetup)
                put("createLocalUser", experience.createLocalUser)
                put("localUsername", experience.localUsername)
                put("skipOobe", experience.skipOobe)
                put("applySkuSiPolicy", experience.applySkuSiPolicy)
                put("winUtilJson", winUtil.toJsonString())
            }
            val tmpMeta = File(context.cacheDir, "pb_vtoy_meta.json").apply { writeText(metaObj.toString(), Charsets.UTF_8) }
            try { Shell.cmd("mkdir -p $TEMP_VENTOY_MNT/ventoy && cp -f ${shellQuote(tmpMeta.absolutePath)} $TEMP_VENTOY_MNT/ventoy/pocketboot_meta.json").exec() } finally { tmpMeta.delete() }
        }
        if (driversDir != null) {
            if (driversDir.isNotBlank()) packDriversInjectionOnMounted(context, driversDir)
            else Shell.cmd("rm -f $TEMP_VENTOY_MNT/ventoy/drivers_inject.zip 2>/dev/null").exec()
        }
        val hasXml = Shell.cmd("[ -f $TEMP_VENTOY_MNT/ventoy/scripts/pocketboot_unattend.xml ] && echo 1 || echo 0").exec().out.lastOrNull()?.trim() == "1"
        val hasDrvZip = Shell.cmd("[ -f $TEMP_VENTOY_MNT/ventoy/drivers_inject.zip ] && echo 1 || echo 0").exec().out.lastOrNull()?.trim() == "1"
        val winIsos = findWindowsIsoRelPathsOnMounted()
        if (hasXml && winIsos.isNotEmpty()) {
            val autoArr = JSONArray()
            winIsos.forEach { rel -> autoArr.put(JSONObject().apply { put("image", rel); put("template", JSONArray().put("/ventoy/scripts/pocketboot_unattend.xml")); put("autosel", 1); put("timeout", 3) }) }
            rootObj.put("auto_install", autoArr)
        } else rootObj.remove("auto_install")
        if (hasDrvZip && winIsos.isNotEmpty()) {
            val injArr = JSONArray()
            winIsos.forEach { rel -> injArr.put(JSONObject().apply { put("image", rel); put("archive", "/ventoy/drivers_inject.zip") }) }
            rootObj.put("injection", injArr)
        } else rootObj.remove("injection")
        val tmpJson = File(context.cacheDir, "ventoy.json").apply { writeText(rootObj.toString(2), Charsets.UTF_8) }
        try { Shell.cmd("mkdir -p $TEMP_VENTOY_MNT/ventoy && cp -f ${shellQuote(tmpJson.absolutePath)} $TEMP_VENTOY_MNT/ventoy/ventoy.json").exec() } finally { tmpJson.delete() }
    }

    private fun readPocketBootMetaOnMounted(defaultCheck: Boolean, defaultNro: Boolean): Triple<WindowsExperienceConfig, WinUtilTweaksConfig, Boolean> {
        val hasDrv = Shell.cmd("[ -f $TEMP_VENTOY_MNT/ventoy/drivers_inject.zip ] && echo 1 || echo 0").exec().out.lastOrNull()?.trim() == "1"
        val raw = Shell.cmd("cat $TEMP_VENTOY_MNT/ventoy/pocketboot_meta.json 2>/dev/null").exec().out.joinToString("\n")
        if (raw.isBlank()) return Triple(WindowsExperienceConfig(bypassRequirements = defaultCheck, bypassMicrosoftAccount = defaultNro), WinUtilTweaksConfig(), hasDrv)
        return runCatching {
            val o = JSONObject(raw)
            val exp = WindowsExperienceConfig(
                bypassRequirements = o.optBoolean("bypassRequirements", defaultCheck),
                bypassMicrosoftAccount = o.optBoolean("bypassMicrosoftAccount", defaultNro),
                disableDataCollection = o.optBoolean("disableDataCollection", true),
                disableBitLocker = o.optBoolean("disableBitLocker", true),
                qolImprovements = o.optBoolean("qolImprovements", true),
                disableSponsoredApps = o.optBoolean("disableSponsoredApps", false),
                classicContextMenu = o.optBoolean("classicContextMenu", false),
                skipWifiSetup = o.optBoolean("skipWifiSetup", true),
                createLocalUser = o.optBoolean("createLocalUser", false),
                localUsername = o.optString("localUsername", ""),
                skipOobe = o.optBoolean("skipOobe", false),
                applySkuSiPolicy = o.optBoolean("applySkuSiPolicy", false)
            )
            Triple(exp, WinUtilTweaksConfig.fromJsonString(o.optString("winUtilJson", "")), hasDrv)
        }.getOrDefault(Triple(WindowsExperienceConfig(bypassRequirements = defaultCheck, bypassMicrosoftAccount = defaultNro), WinUtilTweaksConfig(), hasDrv))
    }

    private fun readVentoyJsonOnMounted(): Pair<Boolean, Boolean> {
        val txt = Shell.cmd("cat $TEMP_VENTOY_MNT/ventoy/ventoy.json 2>/dev/null").exec().out.joinToString("\n")
        if (txt.isBlank()) return true to true
        return runCatching {
            val obj = JSONObject(txt)
            val arr = obj.optJSONArray("control") ?: return true to true
            var check = true
            var nro = true
            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i) ?: continue
                if (item.has("VTOY_WIN11_BYPASS_CHECK")) check = item.optString("VTOY_WIN11_BYPASS_CHECK") != "0"
                if (item.has("VTOY_WIN11_BYPASS_NRO")) nro = item.optString("VTOY_WIN11_BYPASS_NRO") != "0"
            }
            check to nro
        }.getOrDefault(true to true)
    }

    fun cancelActiveBuild() {
        BuildThermalHelper.cancelActiveBuildProcess()
    }

    suspend fun buildVentoyImage(
        context: Context,
        config: VentoyBuildConfig,
        onProgress: (WindowsBuildProgress) -> Unit
    ): ActionResult = withContext(Dispatchers.IO) {
        BuildThermalHelper.resetCancelFlag()
        val ts = nowTs()
        val entries = mutableListOf<LogEntry>()
        val safeDir = UsbGadgetEngine.resolveDirectPath(config.outputDir).trimEnd('/')
        val safeName = UsbGadgetEngine.sanitizeOutputFileName(config.outputName, ".img", "Pocket Multi Boot")
        val outPath = "$safeDir/$safeName"
        val qOut = shellQuote(outPath)
        val qDir = shellQuote(safeDir)
        var loopDev = ""

        return@withContext runCatching {
            if (!Shell.getShell().isRoot) {
                return@withContext ActionResult(false, "Root access is required.", entries = entries)
            }
            config.initialIsoToAdd?.let { initIso ->
                if (outPath.equals(UsbGadgetEngine.resolveDirectPath(initIso.path), ignoreCase = true)) {
                    return@withContext ActionResult(false, "Output drive cannot overwrite the source ISO.", entries = entries)
                }
            }
            if (UsbGadgetEngine.getStatus().mountedLunFor(outPath) != null) {
                return@withContext ActionResult(false, "$safeName is currently mounted to your PC. Unmount it before overwriting.", entries = entries)
            }

            onProgress(WindowsBuildProgress("Checking Multi-Boot engine...", "Loading offline boot files", 0.06f, true))
            val bundle = resolveBootBundle(context)
                ?: return@withContext ActionResult(
                    false,
                    "Multi-Boot engine files missing. Open Settings → Multi-Boot Engine → Update.",
                    entries = entries
                )

            val layout = DiskPartitioner.calculateVentoyLayoutMb(config.sizeMb, config.scheme)
            val sizeLabel = if (config.sizeMb >= 1024) {
                String.format(Locale.US, "%.1f GB", config.sizeMb / 1024.0).replace(".0 GB", " GB")
            } else {
                "${config.sizeMb} MB"
            }
            val procFs = Shell.cmd("cat /proc/filesystems 2>/dev/null").exec().out
                .map { it.trim().substringAfterLast('\t').substringAfterLast(' ').lowercase() }
                .filter { it.isNotEmpty() }
                .toSet()

            val exfatFormatter = WindowsImageBuilder.findBundledBinary(
                context,
                "libmkfs_exfat.so",
                listOf("mkfs.exfat", "mkfs.exfat_sec", "mkexfatfs")
            )
            val ntfsFormatter = WindowsImageBuilder.findBundledBinary(
                context,
                "libmkfs_ntfs.so",
                listOf("mkfs.ntfs", "mkntfs")
            )
            val hasKernelExfat = "exfat" in procFs || "sdfat" in procFs
            val useExfat = when (config.filesystem) {
                VentoyFilesystem.AUTO -> exfatFormatter != null && hasKernelExfat
                VentoyFilesystem.EXFAT -> exfatFormatter != null && hasKernelExfat
                VentoyFilesystem.NTFS -> false
            }
            if (!useExfat && ntfsFormatter == null) {
                return@withContext ActionResult(false, "No NTFS or exFAT formatter found.", entries = entries)
            }

            // Step 1: Allocate SPARSE .img (only uses ~35 MB on phone until ISOs are copied inside!)
            onProgress(
                WindowsBuildProgress(
                    "Creating $sizeLabel sparse Multi-Boot drive...",
                    "Engine v${bundle.version} (${config.scheme.label} · ${if (useExfat) "exFAT" else "NTFS"})",
                    0.16f,
                    false
                )
            )
            val allocRes = Shell.cmd(
                "mkdir -p $qDir && rm -f $qOut && (truncate -s ${layout.totalBytes} $qOut 2>/dev/null || dd if=/dev/zero of=$qOut bs=1M count=0 seek=${layout.totalBytes / (1024L * 1024L)} 2>/dev/null)"
            ).exec()
            if (!allocRes.isSuccess) {
                return@withContext ActionResult(false, "Failed to create sparse image at $outPath.", entries = entries)
            }

            // Step 2: Write Ventoy GPT/MBR partition table + boot.img + core.img + 32 MB VTOYEFI
            onProgress(WindowsBuildProgress("Writing Multi-Boot bootloader...", "Preparing 32 MB EFI boot partition", 0.34f, false))
            val writeErr = writeVentoyBootStructures(
                context = context,
                outPath = outPath,
                scheme = config.scheme,
                layout = layout,
                bundle = bundle,
                writePartitionTable = true
            )
            if (writeErr != null) {
                Shell.cmd("rm -f $qOut 2>/dev/null").exec()
                return@withContext ActionResult(false, "Multi-Boot bootloader write failed: $writeErr", entries = entries)
            }
            entries.add(LogEntry(ts, "ventoy", "Wrote Ventoy v${bundle.version} ${config.scheme.label} + VTOYEFI at LBA=${layout.part2StartLba}"))

            // Step 3: Attach Partition 1 & format NTFS or exFAT
            onProgress(
                WindowsBuildProgress(
                    "Formatting Partition 1...",
                    if (useExfat) "exFAT" else "NTFS (Universal)",
                    0.56f,
                    false
                )
            )
            loopDev = attachPartition1Loop(outPath, layout.part1OffsetBytes, layout.part1SizeBytes)
            if (loopDev.isBlank() || !loopDev.startsWith("/dev/")) {
                Shell.cmd("rm -f $qOut 2>/dev/null").exec()
                return@withContext ActionResult(false, "Kernel loop setup failed for Multi-Boot Partition 1.", entries = entries)
            }
            val qLoop = shellQuote(loopDev)

            val fmtRes = if (useExfat) {
                val qFmt = shellQuote(exfatFormatter!!)
                Shell.cmd("$qFmt -L POCKETBOOT $qLoop 2>&1 || $qFmt -n POCKETBOOT $qLoop 2>&1 || $qFmt $qLoop 2>&1").exec()
            } else {
                val qFmt = shellQuote(ntfsFormatter!!)
                Shell.cmd("$qFmt -F -Q -L POCKETBOOT -H 255 -S 63 -p ${layout.part1StartLba} $qLoop 2>&1").exec()
            }
            if (!fmtRes.isSuccess) {
                unmountVentoyPartition1(loopDev)
                Shell.cmd("rm -f $qOut 2>/dev/null").exec()
                val err = (fmtRes.out + fmtRes.err).joinToString(" ").take(200)
                return@withContext ActionResult(false, "Multi-Boot format failed: $err", entries = entries)
            }

            // Step 4: Mount Partition 1, write ventoy.json bypasses, optionally copy/move initial ISO
            onProgress(WindowsBuildProgress("Configuring Multi-Boot...", "Writing boot configuration", 0.70f, false))
            val mntType = mountVentoyPartition1(context, loopDev)
            if (mntType != null) {
                config.initialIsoToAdd?.let { iso ->
                    val srcPath = UsbGadgetEngine.resolveDirectPath(iso.path)
                    val destName = iso.name
                    val destPath = "$TEMP_VENTOY_MNT/$destName"
                    val srcSize = iso.sizeBytes.coerceAtLeast(1L)
                    onProgress(
                        WindowsBuildProgress(
                            stage = if (config.moveInitialIso) "Moving ISO into Multi-Boot..." else "Copying ISO into Multi-Boot...",
                            detail = "$destName (${UsbGadgetEngine.formatBytes(srcSize)})",
                            progress = 0.72f,
                            isIndeterminate = false
                        )
                    )
                    val copyJob = launch(Dispatchers.IO) {
                        while (isActive) {
                            delay(600L)
                            val cur = Shell.cmd("stat -c '%s' ${shellQuote(destPath)} 2>/dev/null")
                                .exec().out.firstOrNull()?.trim()?.toLongOrNull() ?: 0L
                            val frac = (cur.toFloat() / srcSize.toFloat()).coerceIn(0f, 1f)
                            onProgress(
                                WindowsBuildProgress(
                                    stage = if (config.moveInitialIso) "Moving ISO into Multi-Boot..." else "Copying ISO into Multi-Boot...",
                                    detail = "$destName • ${UsbGadgetEngine.formatBytes(cur)} / ${UsbGadgetEngine.formatBytes(srcSize)}",
                                    progress = 0.72f + frac * 0.24f,
                                    isIndeterminate = false
                                )
                            )
                        }
                    }
                    val vProf = BuildThermalHelper.currentDefaultThermalProfile()
                    val cpRes = BuildThermalHelper.runIsolatedRootScript(
                        context,
                        "ventoy_cp",
                        "set -e\n${BuildThermalHelper.thermalExecPrefix(vProf)}\n\$THERM_PFX cp -f ${shellQuote(srcPath)} ${shellQuote(destPath)} 2>&1",
                        trackPid = true
                    )
                    copyJob.cancel()
                    if (!isActive || BuildThermalHelper.isBuildCancelled()) {
                        unmountVentoyPartition1(loopDev)
                        loopDev = ""
                        Shell.cmd("rm -f $qOut 2>/dev/null || true").exec()
                        return@withContext ActionResult(false, "Multi-Boot drive build cancelled.", entries = entries)
                    }
                    if (cpRes.isSuccess) {
                        if (config.moveInitialIso) {
                            Shell.cmd("rm -f ${shellQuote(srcPath)} 2>/dev/null").exec()
                        }
                        entries.add(LogEntry(ts, "ventoy", "${if (config.moveInitialIso) "Moved" else "Copied"} $destName into Ventoy drive"))
                    } else {
                        entries.add(LogEntry(ts, "ventoy", "Warning: could not add $destName: ${(cpRes.out + cpRes.err).joinToString(" ").take(140)}", true))
                    }
                }
                writeVentoyJsonOnMounted(context, config.experience.bypassRequirements, config.experience.bypassMicrosoftAccount, config.experience, config.winUtil, config.driversDir)
                entries.add(LogEntry(ts, "ventoy", "Mounted Partition 1 via $mntType and wrote ventoy.json (synced Windows auto_install & drivers)"))
            }

            onProgress(WindowsBuildProgress("Finalizing Multi-Boot drive...", "Syncing & fixing storage permissions", 0.96f, false))
            unmountVentoyPartition1(loopDev)
            loopDev = ""

            UsbGadgetEngine.fixUserStoragePermissions(context, safeDir, isDir = true)
            UsbGadgetEngine.fixUserStoragePermissions(context, outPath, isDir = false)
            val vOutBytes = Shell.cmd("stat -c '%s' $qOut 2>/dev/null || echo 0").exec().out.firstOrNull()?.trim()?.toLongOrNull() ?: 0L
            if (vOutBytes > 0L) UsbGadgetEngine.syncMediaStoreFileSize(context, outPath, vOutBytes)
            IsoInspector.invalidate(outPath)

            onProgress(WindowsBuildProgress("Done!", safeName, 1f, false))
            ActionResult(
                success = true,
                userMessage = "Created Multi-Boot drive: $safeName ($sizeLabel sparse, ${if (useExfat) "exFAT" else "NTFS"}).",
                createdPath = outPath,
                entries = entries
            )
        }.getOrElse { e ->
            if (loopDev.isNotEmpty()) unmountVentoyPartition1(loopDev)
            entries.add(LogEntry(ts, "ventoy", "Error: ${e.message}", true))
            ActionResult(false, "Multi-Boot build failed: ${e.message ?: "Unexpected error"}", entries = entries)
        }
    }

    suspend fun inspectDriveContents(context: Context, imgPath: String): VentoyDriveContents? = withContext(Dispatchers.IO) {
        val resolved = UsbGadgetEngine.resolveDirectPath(imgPath)
        val info = probeVentoyImage(resolved) ?: return@withContext null
        val loopDev = attachPartition1Loop(resolved, info.part1OffsetBytes, info.part1SizeBytes)
        if (loopDev.isBlank() || !loopDev.startsWith("/dev/")) return@withContext null
        return@withContext try {
            val fsLabel = mountVentoyPartition1(context, loopDev) ?: return@withContext null
            val dfLine = Shell.cmd("df -B1 $TEMP_VENTOY_MNT 2>/dev/null | tail -n 1").exec().out.firstOrNull().orEmpty().trim()
            val dfParts = dfLine.split(Regex("\\s+"))
            val totalB = dfParts.getOrNull(1)?.toLongOrNull() ?: info.part1SizeBytes
            val freeB = dfParts.getOrNull(3)?.toLongOrNull() ?: 0L

            val fileLines = Shell.cmd(
                """
                find $TEMP_VENTOY_MNT -maxdepth 3 -type f \( -iname '*.iso' -o -iname '*.img' -o -iname '*.wim' -o -iname '*.vhd' -o -iname '*.vhdx' \) 2>/dev/null | while IFS= read -r f; do
                    sz=${'$'}(stat -c '%s' "${'$'}f" 2>/dev/null)
                    rel=${'$'}{f#$TEMP_VENTOY_MNT/}
                    echo "${'$'}sz|${'$'}rel"
                done
                """.trimIndent()
            ).exec().out

            val items = fileLines.mapNotNull { line ->
                val sep = line.indexOf('|')
                if (sep <= 0) return@mapNotNull null
                val sz = line.substring(0, sep).trim().toLongOrNull() ?: 0L
                val rel = line.substring(sep + 1).trim()
                if (rel.isEmpty() || rel.startsWith("ventoy/")) return@mapNotNull null
                VentoyIsoItem(name = rel.substringAfterLast('/'), relativePath = rel, sizeBytes = sz)
            }.sortedBy { it.name.lowercase() }

            val (bypassCheck, bypassNro) = readVentoyJsonOnMounted()
            val (savedExp, savedWu, hasDrvZip) = readPocketBootMetaOnMounted(bypassCheck, bypassNro)
            VentoyDriveContents(
                info = info,
                fsType = fsLabel,
                totalBytes = totalB,
                freeBytes = freeB,
                files = items,
                win11BypassCheck = bypassCheck,
                win11BypassNro = bypassNro,
                savedExperience = savedExp,
                savedWinUtil = savedWu,
                hasDriversZip = hasDrvZip
            )
        } finally {
            unmountVentoyPartition1(loopDev)
        }
    }

    suspend fun addIsoToVentoy(
        context: Context,
        ventoyImgPath: String,
        sourceIsoPath: String,
        moveFile: Boolean,
        onProgress: (WindowsBuildProgress) -> Unit
    ): ActionResult = withContext(Dispatchers.IO) {
        val ts = nowTs()
        val entries = mutableListOf<LogEntry>()
        val resolvedImg = UsbGadgetEngine.resolveDirectPath(ventoyImgPath)
        val resolvedSrc = UsbGadgetEngine.resolveDirectPath(sourceIsoPath)
        val isoName = resolvedSrc.substringAfterLast('/')
        val info = probeVentoyImage(resolvedImg)
            ?: return@withContext ActionResult(false, "Selected file is not a valid Multi-Boot .img drive.", entries = entries)

        val srcSize = Shell.cmd("stat -c '%s' ${shellQuote(resolvedSrc)} 2>/dev/null")
            .exec().out.firstOrNull()?.trim()?.toLongOrNull() ?: 0L
        if (srcSize <= 0L) {
            return@withContext ActionResult(false, "Source ISO not found or empty.", entries = entries)
        }

        val loopDev = attachPartition1Loop(resolvedImg, info.part1OffsetBytes, info.part1SizeBytes)
        if (loopDev.isBlank() || !loopDev.startsWith("/dev/")) {
            return@withContext ActionResult(false, "Could not attach Multi-Boot partition.", entries = entries)
        }

        return@withContext try {
            onProgress(WindowsBuildProgress("Opening Multi-Boot drive...", "Mounting Partition 1", 0.05f, true))
            val fsLabel = mountVentoyPartition1(context, loopDev)
                ?: return@withContext ActionResult(false, "Could not mount Multi-Boot Partition 1.", entries = entries)

            val dfLine = Shell.cmd("df -B1 $TEMP_VENTOY_MNT 2>/dev/null | tail -n 1").exec().out.firstOrNull().orEmpty().trim()
            val freeB = dfLine.split(Regex("\\s+")).getOrNull(3)?.toLongOrNull() ?: 0L
            if (freeB > 0L && freeB < srcSize + (16L * 1024L * 1024L)) {
                return@withContext ActionResult(
                    false,
                    "Not enough free space inside Multi-Boot drive. Need ${UsbGadgetEngine.formatBytes(srcSize)}, only ${UsbGadgetEngine.formatBytes(freeB)} free.",
                    entries = entries
                )
            }

            val destPath = "$TEMP_VENTOY_MNT/$isoName"
            val verb = if (moveFile) "Moving" else "Copying"
            val progressJob = launch(Dispatchers.IO) {
                while (isActive) {
                    delay(550L)
                    val cur = Shell.cmd("stat -c '%s' ${shellQuote(destPath)} 2>/dev/null")
                        .exec().out.firstOrNull()?.trim()?.toLongOrNull() ?: 0L
                    val frac = (cur.toFloat() / srcSize.toFloat()).coerceIn(0f, 1f)
                    onProgress(
                        WindowsBuildProgress(
                            stage = "$verb $isoName...",
                            detail = "${UsbGadgetEngine.formatBytes(cur)} / ${UsbGadgetEngine.formatBytes(srcSize)} ($fsLabel)",
                            progress = 0.08f + frac * 0.86f,
                            isIndeterminate = false
                        )
                    )
                }
            }

            val cpRes = Shell.cmd("cp -f ${shellQuote(resolvedSrc)} ${shellQuote(destPath)} 2>&1").exec()
            progressJob.cancel()

            if (!cpRes.isSuccess) {
                Shell.cmd("rm -f ${shellQuote(destPath)} 2>/dev/null").exec()
                val err = (cpRes.out + cpRes.err).joinToString(" ").take(200)
                return@withContext ActionResult(false, "Copy failed: $err", entries = entries)
            }

            if (moveFile) {
                Shell.cmd("rm -f ${shellQuote(resolvedSrc)} 2>/dev/null").exec()
            }

            val (curChk, curNro) = readVentoyJsonOnMounted()
            writeVentoyJsonOnMounted(context, curChk, curNro)
            onProgress(WindowsBuildProgress("Syncing Multi-Boot drive...", "Flushing writes safely", 0.97f, false))
            entries.add(LogEntry(ts, "ventoy", "${if (moveFile) "Moved" else "Copied"} $isoName into ${resolvedImg.substringAfterLast('/')}"))
            ActionResult(true, "${if (moveFile) "Moved" else "Added"} $isoName to Multi-Boot drive.", entries = entries)
        } finally {
            unmountVentoyPartition1(loopDev)
        }
    }

    suspend fun removeIsoFromVentoy(context: Context, ventoyImgPath: String, relativePath: String): ActionResult = withContext(Dispatchers.IO) {
        val resolvedImg = UsbGadgetEngine.resolveDirectPath(ventoyImgPath)
        val info = probeVentoyImage(resolvedImg)
            ?: return@withContext ActionResult(false, "Not a valid Multi-Boot image.")
        val loopDev = attachPartition1Loop(resolvedImg, info.part1OffsetBytes, info.part1SizeBytes)
        if (loopDev.isBlank() || !loopDev.startsWith("/dev/")) {
            return@withContext ActionResult(false, "Could not attach Multi-Boot partition.")
        }
        return@withContext try {
            mountVentoyPartition1(context, loopDev)
                ?: return@withContext ActionResult(false, "Could not mount Multi-Boot partition.")
            val target = "$TEMP_VENTOY_MNT/${relativePath.trimStart('/')}"
            val res = Shell.cmd("rm -f ${shellQuote(target)} 2>&1").exec()
            if (res.isSuccess) { val (curChk, curNro) = readVentoyJsonOnMounted(); writeVentoyJsonOnMounted(context, curChk, curNro) }
            ActionResult(
                success = res.isSuccess,
                userMessage = if (res.isSuccess) "Removed ${relativePath.substringAfterLast('/')} from Multi-Boot drive." else "Could not remove file."
            )
        } finally {
            unmountVentoyPartition1(loopDev)
        }
    }

    suspend fun updateVentoyJson(context: Context, ventoyImgPath: String, bypassCheck: Boolean, bypassNro: Boolean): ActionResult = withContext(Dispatchers.IO) {
        val resolvedImg = UsbGadgetEngine.resolveDirectPath(ventoyImgPath)
        val info = probeVentoyImage(resolvedImg)
            ?: return@withContext ActionResult(false, "Not a valid Multi-Boot image.")
        val loopDev = attachPartition1Loop(resolvedImg, info.part1OffsetBytes, info.part1SizeBytes)
        if (loopDev.isBlank() || !loopDev.startsWith("/dev/")) {
            return@withContext ActionResult(false, "Could not attach Multi-Boot partition.")
        }
        return@withContext try {
            mountVentoyPartition1(context, loopDev)
                ?: return@withContext ActionResult(false, "Could not mount Multi-Boot partition.")
            writeVentoyJsonOnMounted(context, bypassCheck, bypassNro)
            ActionResult(true, "Saved Multi-Boot Windows 11 bypass settings.")
        } finally {
            unmountVentoyPartition1(loopDev)
        }
    }

    suspend fun updateVentoyCustomization(context: Context, ventoyImgPath: String, experience: WindowsExperienceConfig, winUtil: WinUtilTweaksConfig, driversDir: String? = null): ActionResult = withContext(Dispatchers.IO) {
        val resolvedImg = UsbGadgetEngine.resolveDirectPath(ventoyImgPath)
        val info = probeVentoyImage(resolvedImg) ?: return@withContext ActionResult(false, "Not a valid Multi-Boot image.")
        val loopDev = attachPartition1Loop(resolvedImg, info.part1OffsetBytes, info.part1SizeBytes)
        if (loopDev.isBlank() || !loopDev.startsWith("/dev/")) return@withContext ActionResult(false, "Could not attach Multi-Boot partition.")
        return@withContext try {
            mountVentoyPartition1(context, loopDev) ?: return@withContext ActionResult(false, "Could not mount Multi-Boot partition.")
            writeVentoyJsonOnMounted(context, experience.bypassRequirements, experience.bypassMicrosoftAccount, experience, winUtil, driversDir)
            ActionResult(true, "Saved Windows Experience, WinUtil tweaks & drivers to Multi-Boot drive.")
        } finally { unmountVentoyPartition1(loopDev) }
    }

    /**
     * Non-destructively updates the Ventoy bootloader (boot.img, core.img, VTOYEFI Partition 2)
     * on an existing Ventoy .img without touching any ISOs on Partition 1.
     */
    suspend fun upgradeVentoyBootloader(context: Context, ventoyImgPath: String): ActionResult = withContext(Dispatchers.IO) {
        val resolvedImg = UsbGadgetEngine.resolveDirectPath(ventoyImgPath)
        val info = probeVentoyImage(resolvedImg)
            ?: return@withContext ActionResult(false, "Not a valid Multi-Boot image.")
        val bundle = resolveBootBundle(context)
            ?: return@withContext ActionResult(false, "Multi-Boot engine files missing. Update in Settings first.")

        val layout = DiskLayoutSpec(
            totalBytes = info.totalBytes,
            totalSectors = info.totalBytes / 512L,
            part1StartLba = info.part1OffsetBytes / 512L,
            part1Sectors = info.part1SizeBytes / 512L,
            part2StartLba = info.part2StartLba,
            part2Sectors = info.part2Sectors
        )
        val err = writeVentoyBootStructures(
            context = context,
            outPath = resolvedImg,
            scheme = info.scheme,
            layout = layout,
            bundle = bundle,
            writePartitionTable = false
        )
        if (err != null) {
            ActionResult(false, "Bootloader upgrade failed: $err")
        } else {
            ActionResult(true, "Updated Multi-Boot bootloader on ${resolvedImg.substringAfterLast('/')} to v${bundle.version} (ISOs preserved).")
        }
    }

    /**
     * Sparse Space Reclaim: zeros deleted/free space inside Partition 1 and punches sparse
     * holes (`fallocate -d` or sparse `cp --sparse=always`) so Android immediately regains
     * gigabytes freed after deleting ISOs from a Multi-Boot drive.
     */
    suspend fun reclaimSparseSpace(
        context: Context,
        ventoyImgPath: String,
        onProgress: (WindowsBuildProgress) -> Unit
    ): ActionResult = withContext(Dispatchers.IO) {
        val resolvedImg = UsbGadgetEngine.resolveDirectPath(ventoyImgPath)
        val info = probeVentoyImage(resolvedImg)
            ?: return@withContext ActionResult(false, "Not a valid Multi-Boot image.")
        val qImg = shellQuote(resolvedImg)
        val beforeBlocks = Shell.cmd("stat -c '%b' $qImg 2>/dev/null").exec().out.firstOrNull()?.trim()?.toLongOrNull() ?: 0L
        val beforeDisk = beforeBlocks * 512L

        val loopDev = attachPartition1Loop(resolvedImg, info.part1OffsetBytes, info.part1SizeBytes)
        if (loopDev.isBlank() || !loopDev.startsWith("/dev/")) {
            return@withContext ActionResult(false, "Could not attach Multi-Boot partition.")
        }

        return@withContext runCatching {
            onProgress(WindowsBuildProgress("Scanning free blocks...", "Mounting Partition 1", 0.15f, true))
            val fsLabel = mountVentoyPartition1(context, loopDev)
                ?: return@withContext ActionResult(false, "Could not mount Multi-Boot partition.")

            // Try fstrim first (instant if supported by kernel loop driver), then zero-fill free space briefly
            onProgress(WindowsBuildProgress("Trimming deleted ISO blocks...", "Preparing sparse hole punch ($fsLabel)", 0.45f, true))
            val trimmed = Shell.cmd("command -v fstrim >/dev/null 2>&1 && fstrim $TEMP_VENTOY_MNT 2>/dev/null").exec().isSuccess
            if (!trimmed) {
                Shell.cmd(
                    "dd if=/dev/zero of=$TEMP_VENTOY_MNT/.pb_zero_reclaim bs=4M 2>/dev/null || true; sync; rm -f $TEMP_VENTOY_MNT/.pb_zero_reclaim; sync"
                ).exec()
            }
            unmountVentoyPartition1(loopDev)

            onProgress(WindowsBuildProgress("Reclaiming Android storage...", "Punching sparse holes in .img", 0.78f, true))
            val digOk = Shell.cmd("command -v fallocate >/dev/null 2>&1 && fallocate -d $qImg 2>/dev/null").exec().isSuccess
            if (!digOk) {
                // Sparse copy fallback inside `<parent>/.pocketboot_tmp`
                val parentDir = resolvedImg.substringBeforeLast('/')
                val tmpWork = UsbGadgetEngine.createSafeTempWorkDir(context, parentDir, "reclaim_${System.currentTimeMillis() % 10000}")
                val qTmpImg = shellQuote("$tmpWork/sparse.img")
                val cpOk = Shell.cmd("cp --sparse=always $qImg $qTmpImg 2>/dev/null && mv -f $qTmpImg $qImg").exec().isSuccess
                UsbGadgetEngine.cleanupTempWorkDir(tmpWork)
                if (!cpOk) {
                    return@withContext ActionResult(false, "Kernel lacks fallocate -d and sparse cp.")
                }
            }

            UsbGadgetEngine.fixUserStoragePermissions(context, resolvedImg, isDir = false); Shell.cmd("stat -c '%s' $qImg 2>/dev/null || echo 0").exec().out.firstOrNull()?.trim()?.toLongOrNull()?.takeIf { it > 0L }?.let { UsbGadgetEngine.syncMediaStoreFileSize(context, resolvedImg, it) }
            val afterBlocks = Shell.cmd("stat -c '%b' $qImg 2>/dev/null").exec().out.firstOrNull()?.trim()?.toLongOrNull() ?: beforeBlocks
            val afterDisk = afterBlocks * 512L
            val saved = (beforeDisk - afterDisk).coerceAtLeast(0L)
            onProgress(WindowsBuildProgress("Done!", "Now ${UsbGadgetEngine.formatBytes(afterDisk)} on disk", 1f, false))
            ActionResult(
                success = true,
                userMessage = if (saved > 1024L * 1024L) {
                    "Reclaimed ${UsbGadgetEngine.formatBytes(saved)} (now ${UsbGadgetEngine.formatBytes(afterDisk)} on disk)."
                } else {
                    "Drive already optimized (${UsbGadgetEngine.formatBytes(afterDisk)} on disk)."
                }
            )
        }.getOrElse { e ->
            unmountVentoyPartition1(loopDev)
            ActionResult(false, "Reclaim failed: ${e.message ?: "Unexpected error"}")
        }
    }

    /**
     * Non-Destructive Multi-Boot Resize: expands an existing Multi-Boot .img to a larger capacity
     * (e.g., 8 GB -> 16/32/64/128 GB) while preserving every ISO and ventoy.json configuration.
     * Uses in-place `libntfsresize.so` for NTFS drives when available, and falls back to a verified
     * copy-and-swap rebuild inside `<parent>/.pocketboot_tmp/` for exFAT drives.
     */
    suspend fun resizeVentoyImage(
        context: Context,
        ventoyImgPath: String,
        newSizeGb: Int,
        onProgress: (WindowsBuildProgress) -> Unit
    ): ActionResult = withContext(Dispatchers.IO) {
        val resolvedImg = UsbGadgetEngine.resolveDirectPath(ventoyImgPath)
        val info = probeVentoyImage(resolvedImg)
            ?: return@withContext ActionResult(false, "Not a valid Multi-Boot image.")
        val bundle = resolveBootBundle(context)
            ?: return@withContext ActionResult(false, "Multi-Boot engine files missing.")

        val newSizeMb = newSizeGb.coerceIn(2, 128) * 1024
        val newLayout = DiskPartitioner.calculateVentoyLayoutMb(newSizeMb, info.scheme)
        if (newLayout.totalBytes <= info.totalBytes) {
            return@withContext ActionResult(false, "Select a size larger than the current drive capacity.")
        }

        val qImg = shellQuote(resolvedImg)
        val p1Magic = Shell.cmd(
            "dd if=$qImg bs=512 skip=${info.part1OffsetBytes / 512L} count=1 2>/dev/null | tr -cd 'A-Za-z0-9 '"
        ).exec().out.joinToString("").uppercase()
        val isNtfs = p1Magic.contains("NTFS")
        val ntfsResizeBin = WindowsImageBuilder.findBundledBinary(context, "libntfsresize.so", listOf("ntfsresize"))

        // Path A: Instant in-place NTFS expansion via bundled static libntfsresize.so
        if (isNtfs && ntfsResizeBin != null) {
            var loopDev = ""
            return@withContext runCatching {
                onProgress(WindowsBuildProgress("Expanding sparse image to $newSizeGb GB...", "Extending disk boundaries", 0.20f, false))
                val truncOk = Shell.cmd("truncate -s ${newLayout.totalBytes} $qImg 2>&1").exec().isSuccess
                if (!truncOk) return@withContext ActionResult(false, "Failed to expand image file.")

                onProgress(WindowsBuildProgress("Updating partition table & EFI...", "Relocating 32 MB VTOYEFI to new end", 0.48f, false))
                val tblErr = writeVentoyBootStructures(
                    context = context,
                    outPath = resolvedImg,
                    scheme = info.scheme,
                    layout = newLayout,
                    bundle = bundle,
                    writePartitionTable = true
                )
                if (tblErr != null) return@withContext ActionResult(false, "Partition table update failed: $tblErr")

                onProgress(WindowsBuildProgress("Expanding NTFS filesystem...", "Running ntfsresize (all ISOs kept)", 0.72f, true))
                loopDev = attachPartition1Loop(resolvedImg, newLayout.part1OffsetBytes, newLayout.part1SizeBytes)
                if (loopDev.isBlank()) return@withContext ActionResult(false, "Could not attach expanded partition.")
                val qLoop = shellQuote(loopDev)
                val qNr = shellQuote(ntfsResizeBin)
                val nrRes = Shell.cmd("printf 'y\\n' | $qNr -f -b $qLoop 2>&1 || printf 'y\\n' | $qNr -f $qLoop 2>&1").exec()
                unmountVentoyPartition1(loopDev)
                loopDev = ""
                if (!nrRes.isSuccess) {
                    return@withContext ActionResult(false, "ntfsresize failed: ${(nrRes.out + nrRes.err).takeLast(3).joinToString(" ").take(160)}")
                }
                UsbGadgetEngine.fixUserStoragePermissions(context, resolvedImg, isDir = false); Shell.cmd("stat -c '%s' $qImg 2>/dev/null || echo 0").exec().out.firstOrNull()?.trim()?.toLongOrNull()?.takeIf { it > 0L }?.let { UsbGadgetEngine.syncMediaStoreFileSize(context, resolvedImg, it) }
                onProgress(WindowsBuildProgress("Done!", "Expanded to $newSizeGb GB", 1f, false))
                ActionResult(true, "Expanded ${resolvedImg.substringAfterLast('/')} to $newSizeGb GB (all ISOs preserved).")
            }.getOrElse { e ->
                if (loopDev.isNotEmpty()) unmountVentoyPartition1(loopDev)
                ActionResult(false, "Resize failed: ${e.message ?: "Unexpected error"}")
            }
        }

        // Path B: Universal safe copy-and-swap (works for exFAT and NTFS without ntfsresize)
        val parentDir = resolvedImg.substringBeforeLast('/')
        val workDir = UsbGadgetEngine.createSafeTempWorkDir(context, parentDir, "resize_${System.currentTimeMillis() % 10000}")
        val newImgName = "expanded.img"
        val newImgPath = "$workDir/$newImgName"
        val oldMnt = "$workDir/old_mnt"
        val newMnt = "$workDir/new_mnt"
        var oldLoop = ""
        var newLoop = ""
        return@withContext runCatching {
            val fsChoice = if (isNtfs) VentoyFilesystem.NTFS else VentoyFilesystem.EXFAT
            onProgress(WindowsBuildProgress("Creating $newSizeGb GB container...", "Safe non-destructive rebuild", 0.15f, false))
            val buildRes = buildVentoyImage(
                context = context,
                config = VentoyBuildConfig(
                    outputDir = workDir,
                    outputName = newImgName,
                    sizeMb = newSizeMb,
                    scheme = info.scheme,
                    filesystem = fsChoice,
                    autoMountAfterBuild = false
                ),
                onProgress = { p -> onProgress(p.copy(progress = 0.15f + p.progress * 0.30f)) }
            )
            if (!buildRes.success) {
                return@withContext ActionResult(false, "Could not create expanded drive: ${buildRes.userMessage}")
            }

            onProgress(WindowsBuildProgress("Copying existing ISOs...", "Preserving all ISOs & boot config", 0.55f, true))
            oldLoop = attachPartition1Loop(resolvedImg, info.part1OffsetBytes, info.part1SizeBytes)
            newLoop = attachPartition1Loop(newImgPath, newLayout.part1OffsetBytes, newLayout.part1SizeBytes)
            if (oldLoop.isBlank() || newLoop.isBlank()) {
                return@withContext ActionResult(false, "Could not attach partitions for migration.")
            }

            val procFs = Shell.cmd("cat /proc/filesystems 2>/dev/null").exec().out.joinToString(" ").lowercase()
            val ntfs3g = WindowsImageBuilder.findBundledBinary(context, "libntfs_3g.so", listOf("ntfs-3g"))
            fun mntExpr(loop: String, dst: String): String {
                val ql = shellQuote(loop)
                val qd = shellQuote(dst)
                return buildList {
                    if (procFs.contains("ntfs3")) add("mount -t ntfs3 -o rw $ql $qd 2>/dev/null")
                    if (procFs.contains("exfat")) add("mount -t exfat -o rw $ql $qd 2>/dev/null")
                    if (procFs.contains("sdfat")) add("mount -t sdfat -o rw $ql $qd 2>/dev/null")
                    if (ntfs3g != null) add("${shellQuote(ntfs3g)} -o rw,big_writes,noatime $ql $qd 2>/dev/null")
                    add("mount -o rw $ql $qd 2>/dev/null")
                }.joinToString(" || ")
            }
            val mntBoth = Shell.cmd(
                "mkdir -p ${shellQuote(oldMnt)} ${shellQuote(newMnt)} && (${mntExpr(oldLoop, oldMnt)}) && (${mntExpr(newLoop, newMnt)})"
            ).exec()
            if (!mntBoth.isSuccess) return@withContext ActionResult(false, "Could not mount partitions for ISO copy.")

            val cpRes = Shell.cmd("cp -af ${shellQuote(oldMnt)}/. ${shellQuote(newMnt)}/ 2>&1").exec()
            Shell.cmd(
                "sync; umount ${shellQuote(oldMnt)} 2>/dev/null || umount -l ${shellQuote(oldMnt)} 2>/dev/null; " +
                    "umount ${shellQuote(newMnt)} 2>/dev/null || umount -l ${shellQuote(newMnt)} 2>/dev/null; " +
                    "sleep 1; losetup -d ${shellQuote(oldLoop)} 2>/dev/null; losetup -d ${shellQuote(newLoop)} 2>/dev/null"
            ).exec()
            oldLoop = ""
            newLoop = ""
            if (!cpRes.isSuccess) return@withContext ActionResult(false, "ISO migration failed during resize.")

            onProgress(WindowsBuildProgress("Replacing with $newSizeGb GB drive...", "Finalizing atomic swap", 0.94f, false))
            val swapRes = Shell.cmd("mv -f ${shellQuote(newImgPath)} $qImg 2>&1").exec()
            if (!swapRes.isSuccess) return@withContext ActionResult(false, "Failed to finalize resized drive.")

            UsbGadgetEngine.fixUserStoragePermissions(context, resolvedImg, isDir = false); Shell.cmd("stat -c '%s' $qImg 2>/dev/null || echo 0").exec().out.firstOrNull()?.trim()?.toLongOrNull()?.takeIf { it > 0L }?.let { UsbGadgetEngine.syncMediaStoreFileSize(context, resolvedImg, it) }
            onProgress(WindowsBuildProgress("Done!", "Expanded to $newSizeGb GB", 1f, false))
            ActionResult(true, "Expanded ${resolvedImg.substringAfterLast('/')} to $newSizeGb GB (all ISOs preserved).")
        }.getOrElse { e ->
            ActionResult(false, "Resize failed: ${e.message ?: "Unexpected error"}")
        }.also {
            if (oldLoop.isNotEmpty()) Shell.cmd("umount ${shellQuote(oldMnt)} 2>/dev/null; losetup -d ${shellQuote(oldLoop)} 2>/dev/null").exec()
            if (newLoop.isNotEmpty()) Shell.cmd("umount ${shellQuote(newMnt)} 2>/dev/null; losetup -d ${shellQuote(newLoop)} 2>/dev/null").exec()
            UsbGadgetEngine.cleanupTempWorkDir(workDir)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VentoyBuilderSheet(
    defaultDir: String,
    initialIso: ImageFile?,
    presets: List<AutounattendPreset> = emptyList(),
    building: Boolean,
    progress: WindowsBuildProgress,
    onDismiss: () -> Unit,
    onCancelBuild: () -> Unit,
    onSavePreset: (AutounattendPreset) -> Unit = {},
    onDeletePreset: (String) -> Unit = {},
    onStartBuild: (VentoyBuildConfig) -> Unit,
    onMessage: (String, Boolean) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var assetInfo by remember { mutableStateOf(VentoyImageBuilder.getInstalledVentoyInfo(context)) }
    var updatingAssets by remember { mutableStateOf(false) }
    var updateProgress by remember { mutableStateOf(WindowsBuildProgress()) }

    var outputName by remember { mutableStateOf("Pocket Multi Boot") }
    var selectedPresetMb by remember { mutableIntStateOf(8192) }
    var isCustomSize by remember { mutableStateOf(false) }
    var customSizeText by remember { mutableStateOf("24") }
    var customUnit by remember { mutableStateOf(MultiBootSizeUnit.GB) }
    var scheme by remember { mutableStateOf(PartitionScheme.GPT) }
    var filesystem by remember { mutableStateOf(VentoyFilesystem.AUTO) }
    var experience by remember { mutableStateOf(WindowsExperienceConfig()) }
    var winUtil by remember { mutableStateOf(WinUtilTweaksConfig()) }
    var driversDir by remember { mutableStateOf("") }
    var showDriversPicker by remember { mutableStateOf(false) }
    var includeInitial by remember(initialIso?.path) { mutableStateOf(initialIso != null) }
    var moveInitial by remember { mutableStateOf(false) }
    var autoMount by remember { mutableStateOf(true) }

    val effectiveSizeMb = if (isCustomSize) {
        val rawNum = customSizeText.toDoubleOrNull() ?: 0.0
        val mb = if (customUnit == MultiBootSizeUnit.GB) (rawNum * 1024.0).roundToInt() else rawNum.roundToInt()
        mb.coerceIn(512, 131072)
    } else {
        selectedPresetMb
    }
    val customValid = !isCustomSize || ((customSizeText.toDoubleOrNull() ?: 0.0) > 0.0)
    val displaySize = if (effectiveSizeMb >= 1024) {
        String.format(Locale.US, "%.2f GB", effectiveSizeMb / 1024.0)
            .replace(".00 GB", " GB")
            .replace("0 GB", " GB")
    } else {
        "$effectiveSizeMb MB"
    }
    val trimmedName = outputName.trim().ifBlank { "Pocket Multi Boot" }
    val finalImgName = if (trimmedName.endsWith(".img", ignoreCase = true)) trimmedName else "$trimmedName.img"

    var targetExists by remember(defaultDir) { mutableStateOf(false) }
    val resolvedOutName = remember(finalImgName) { UsbGadgetEngine.sanitizeOutputFileName(finalImgName, ".img", "Pocket Multi Boot") }
    LaunchedEffect(defaultDir, resolvedOutName) {
        targetExists = UsbGadgetEngine.fileExistsInDir(defaultDir, resolvedOutName)
    }

    ModalBottomSheet(
        onDismissRequest = { if (!building && !updatingAssets) onDismiss() },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = PocketColors.surfaceCard
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .imePadding()
                .padding(horizontal = 20.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(42.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(PocketColors.violetBg),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Rounded.Layers, null, tint = PocketColors.violet, modifier = Modifier.size(22.dp))
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text("Create Multi-Boot Drive", style = MaterialTheme.typography.titleLarge, color = PocketColors.textPrimary)
                    Text(
                        text = "Boot multiple Windows & Linux ISOs over 2.2 GB from one sparse drive",
                        style = MaterialTheme.typography.bodySmall,
                        color = PocketColors.textSecondary
                    )
                }
            }

            // Multi-Boot Engine Version & In-App Update Bar
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = PocketColors.surfaceInput,
                border = BorderStroke(0.5.dp, if (assetInfo.ready) PocketColors.border else PocketColors.warning)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (assetInfo.ready) {
                                "Multi-Boot Engine v${assetInfo.version} (${if (assetInfo.isUpdatedInApp) "Updated" else "Bundled"})"
                            } else {
                                "Multi-Boot Engine Missing"
                            },
                            style = MaterialTheme.typography.labelLarge,
                            color = if (assetInfo.ready) PocketColors.textPrimary else PocketColors.warning
                        )
                        Text(
                            text = if (updatingAssets) updateProgress.detail.ifBlank { updateProgress.stage } else "Offline ready · Tap Update for latest engine",
                            style = MaterialTheme.typography.bodySmall,
                            color = PocketColors.textSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    TextButton(
                        onClick = {
                            scope.launch {
                                updatingAssets = true
                                val res = VentoyImageBuilder.updateVentoyFromGitHub(context) { updateProgress = it }
                                assetInfo = VentoyImageBuilder.getInstalledVentoyInfo(context)
                                updatingAssets = false
                                onMessage(res.userMessage, !res.success)
                            }
                        },
                        enabled = !building && !updatingAssets
                    ) {
                        if (updatingAssets) {
                            CircularProgressIndicator(modifier = Modifier.size(15.dp), strokeWidth = 2.dp, color = PocketColors.accent)
                        } else {
                            Icon(Icons.Rounded.CloudDownload, null, tint = PocketColors.accentSoft, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Update", fontSize = 12.sp, color = PocketColors.accentSoft)
                        }
                    }
                }
            }

            if (building) {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = PocketColors.surfaceInput,
                    border = BorderStroke(1.dp, PocketColors.accent)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(progress.stage, style = MaterialTheme.typography.titleMedium, color = PocketColors.textPrimary)
                        if (progress.detail.isNotBlank()) {
                            Text(progress.detail, style = MaterialTheme.typography.bodySmall, color = PocketColors.textSecondary)
                        }
                        if (progress.isIndeterminate) {
                            LinearProgressIndicator(
                                modifier = Modifier.fillMaxWidth().height(7.dp).clip(RoundedCornerShape(999.dp)),
                                color = PocketColors.accent,
                                trackColor = PocketColors.surfaceSubtle
                            )
                        } else {
                            LinearProgressIndicator(
                                progress = { progress.progress.coerceIn(0f, 1f) },
                                modifier = Modifier.fillMaxWidth().height(7.dp).clip(RoundedCornerShape(999.dp)),
                                color = PocketColors.accent,
                                trackColor = PocketColors.surfaceSubtle
                            )
                        }
                    }
                }

                OutlinedButton(
                    onClick = onCancelBuild,
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, PocketColors.danger)
                ) {
                    Icon(Icons.Rounded.Close, null, tint = PocketColors.danger, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Cancel", color = PocketColors.danger, fontWeight = FontWeight.SemiBold)
                }
            } else {
                // Drive Name (default: "Pocket Multi Boot" + .img suffix like Blank Drive)
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Drive Name", style = MaterialTheme.typography.labelLarge, color = PocketColors.textPrimary)
                    OutlinedTextField(
                        value = outputName,
                        onValueChange = { outputName = it },
                        placeholder = { Text("Pocket Multi Boot") },
                        singleLine = true,
                        suffix = { Text(".img", style = MaterialTheme.typography.labelMedium, color = PocketColors.accentSoft) },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = PocketColors.accent,
                            unfocusedBorderColor = PocketColors.border,
                            focusedContainerColor = PocketColors.surfaceInput,
                            unfocusedContainerColor = PocketColors.surfaceInput,
                            focusedTextColor = PocketColors.textPrimary,
                            unfocusedTextColor = PocketColors.textPrimary
                        )
                    )
                }

                // Drive Capacity Selector (matching Blank USB Drive layout: 2x3 presets + Custom MB/GB switch)
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Drive Capacity (Sparse)", style = MaterialTheme.typography.labelLarge, color = PocketColors.textPrimary)
                        Text(displaySize, style = MaterialTheme.typography.labelLarge, color = PocketColors.accentSoft)
                    }
                    val presets = listOf(
                        "8 GB" to 8192,
                        "16 GB" to 16384,
                        "32 GB" to 32768,
                        "64 GB" to 65536,
                        "128 GB" to 131072,
                        "Custom" to -1
                    )
                    presets.chunked(3).forEach { rowItems ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            rowItems.forEach { (label, mb) ->
                                val selected = if (mb == -1) isCustomSize else (!isCustomSize && selectedPresetMb == mb)
                                Surface(
                                    modifier = Modifier
                                        .weight(1f)
                                        .bounceClick {
                                            if (mb == -1) isCustomSize = true else {
                                                isCustomSize = false
                                                selectedPresetMb = mb
                                            }
                                        },
                                    shape = RoundedCornerShape(10.dp),
                                    color = if (selected) PocketColors.accentBg else PocketColors.surfaceInput,
                                    border = BorderStroke(1.dp, if (selected) PocketColors.accent else PocketColors.border)
                                ) {
                                    Box(
                                        modifier = Modifier.padding(vertical = 10.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = label,
                                            fontSize = 12.sp,
                                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                                            color = if (selected) PocketColors.accentSoft else PocketColors.textPrimary
                                        )
                                    }
                                }
                            }
                        }
                    }

                    AnimatedVisibility(visible = isCustomSize) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedTextField(
                                value = customSizeText,
                                onValueChange = { input ->
                                    customSizeText = if (customUnit == MultiBootSizeUnit.GB) {
                                        input.filter { it.isDigit() || it == '.' }
                                    } else {
                                        input.filter { it.isDigit() }
                                    }
                                },
                                label = { Text("Size (${customUnit.label})") },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(12.dp),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = PocketColors.accent,
                                    unfocusedBorderColor = PocketColors.border,
                                    focusedContainerColor = PocketColors.surfaceInput,
                                    unfocusedContainerColor = PocketColors.surfaceInput,
                                    focusedTextColor = PocketColors.textPrimary,
                                    unfocusedTextColor = PocketColors.textPrimary
                                )
                            )

                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = PocketColors.surfaceInput,
                                border = BorderStroke(0.5.dp, PocketColors.border)
                            ) {
                                Row(modifier = Modifier.padding(4.dp)) {
                                    MultiBootSizeUnit.entries.forEach { unit ->
                                        val active = customUnit == unit
                                        Surface(
                                            shape = RoundedCornerShape(8.dp),
                                            color = if (active) PocketColors.accent else PocketColors.surfaceInput,
                                            modifier = Modifier.clickable {
                                                if (customUnit != unit) {
                                                    val cur = customSizeText.toDoubleOrNull()
                                                    customUnit = unit
                                                    if (cur != null && cur > 0.0) {
                                                        customSizeText = if (unit == MultiBootSizeUnit.GB) {
                                                            String.format(Locale.US, "%.1f", (cur / 1024.0).coerceAtLeast(1.0))
                                                                .replace(".0", "")
                                                        } else {
                                                            (cur * 1024.0).roundToInt().toString()
                                                        }
                                                    }
                                                }
                                            }
                                        ) {
                                            Text(
                                                text = unit.label,
                                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.SemiBold,
                                                color = if (active) PocketColors.onAccent else PocketColors.textSecondary
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // Partition Scheme
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Partition Style", style = MaterialTheme.typography.labelLarge, color = PocketColors.textPrimary)
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PartitionScheme.entries.forEach { s ->
                            val selected = scheme == s
                            Surface(
                                modifier = Modifier
                                    .weight(1f)
                                    .bounceClick { scheme = s },
                                shape = RoundedCornerShape(12.dp),
                                color = if (selected) PocketColors.accentBg else PocketColors.surfaceInput,
                                border = BorderStroke(if (selected) 1.5.dp else 0.5.dp, if (selected) PocketColors.accent else PocketColors.border)
                            ) {
                                Column(modifier = Modifier.padding(11.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text(s.label, style = MaterialTheme.typography.labelLarge, color = if (selected) PocketColors.accentSoft else PocketColors.textPrimary)
                                    Text(if (s == PartitionScheme.GPT) "UEFI + BIOS hybrid" else "Legacy BIOS + UEFI", fontSize = 11.sp, color = PocketColors.textSecondary)
                                }
                            }
                        }
                    }
                }

                // ISO Partition Filesystem: Symmetrical Full-Width Auto (Recommended) + 50/50 NTFS & exFAT
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("ISO Partition Filesystem", style = MaterialTheme.typography.labelLarge, color = PocketColors.textPrimary)

                    val autoSelected = filesystem == VentoyFilesystem.AUTO
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .bounceClick { filesystem = VentoyFilesystem.AUTO },
                        shape = RoundedCornerShape(12.dp),
                        color = if (autoSelected) PocketColors.accentBg else PocketColors.surfaceInput,
                        border = BorderStroke(if (autoSelected) 1.5.dp else 0.5.dp, if (autoSelected) PocketColors.accent else PocketColors.border)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 13.dp, vertical = 11.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text(
                                        text = "Auto Select",
                                        style = MaterialTheme.typography.labelLarge,
                                        color = if (autoSelected) PocketColors.accentSoft else PocketColors.textPrimary
                                    )
                                    Surface(shape = RoundedCornerShape(6.dp), color = PocketColors.successBg) {
                                        Text(
                                            text = "Recommended",
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.5.dp),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = PocketColors.success
                                        )
                                    }
                                }
                                Text(
                                    text = "Uses fast native exFAT when supported, else universal NTFS",
                                    fontSize = 11.sp,
                                    color = PocketColors.textSecondary
                                )
                            }
                        }
                    }

                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(VentoyFilesystem.NTFS, VentoyFilesystem.EXFAT).forEach { fs ->
                            val selected = filesystem == fs
                            Surface(
                                modifier = Modifier
                                    .weight(1f)
                                    .bounceClick { filesystem = fs },
                                shape = RoundedCornerShape(12.dp),
                                color = if (selected) PocketColors.accentBg else PocketColors.surfaceInput,
                                border = BorderStroke(if (selected) 1.5.dp else 0.5.dp, if (selected) PocketColors.accent else PocketColors.border)
                            ) {
                                Column(
                                    modifier = Modifier.padding(11.dp),
                                    verticalArrangement = Arrangement.spacedBy(2.dp)
                                ) {
                                    Text(
                                        text = fs.label,
                                        style = MaterialTheme.typography.labelLarge,
                                        color = if (selected) PocketColors.accentSoft else PocketColors.textPrimary
                                    )
                                    Text(
                                        text = fs.subtitle,
                                        fontSize = 11.sp,
                                        color = PocketColors.textSecondary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }
                }

                WindowsTwoPaneCustomizer(
                    osKind = WindowsOsKind.WIN11, arch = "amd64", experience = experience, winUtil = winUtil, presets = presets,
                    showCa2023Option = false, showCustomScriptOption = false, defaultBrowseDir = defaultDir,
                    onExperienceChange = { experience = it }, onWinUtilChange = { winUtil = it },
                    onApplyPreset = { p ->
                        experience = WindowsExperienceConfig(p.bypassRequirements, p.bypassMicrosoftAccount, p.disableDataCollection, p.disableBitLocker, p.qolImprovements, p.disableSponsoredApps, p.classicContextMenu, p.skipWifiSetup, p.createLocalUser, p.localUsername, "", p.skipOobe, p.useCa2023Bootloaders, p.applySkuSiPolicy)
                        winUtil = WinUtilTweaksConfig.fromJsonString(p.winUtilJson)
                    },
                    onSavePreset = { n -> onSavePreset(AutounattendPreset(n, scheme.name, experience.bypassRequirements, experience.bypassMicrosoftAccount, experience.disableDataCollection, experience.disableBitLocker, experience.disableSponsoredApps, experience.classicContextMenu, experience.skipWifiSetup, experience.createLocalUser, experience.localUsername, experience.skipOobe, experience.qolImprovements, experience.useCa2023Bootloaders, experience.applySkuSiPolicy, winUtil.toJsonString())) },
                    onDeletePreset = onDeletePreset
                )
                Surface(modifier = Modifier.fillMaxWidth().bounceClick { showDriversPicker = true }, shape = RoundedCornerShape(12.dp), color = PocketColors.surfaceInput, border = BorderStroke(0.75.dp, if (driversDir.isNotBlank()) PocketColors.accent else PocketColors.border)) {
                    Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 13.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Icon(Icons.Rounded.FolderOpen, null, tint = PocketColors.accentSoft, modifier = Modifier.size(18.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Inject Windows Drivers Folder (Optional)", style = MaterialTheme.typography.labelMedium, color = PocketColors.textPrimary)
                            Text(if (driversDir.isNotBlank()) driversDir.replace("/data/media/0", "/sdcard") else "Auto-loads .inf storage/Wi-Fi drivers in WinPE via Ventoy injection", fontSize = 11.sp, color = if (driversDir.isNotBlank()) PocketColors.accentSoft else PocketColors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        if (driversDir.isNotBlank()) { IconButton(onClick = { driversDir = "" }, modifier = Modifier.size(28.dp)) { Icon(Icons.Rounded.Close, "Clear", tint = PocketColors.textMuted, modifier = Modifier.size(15.dp)) } }
                        else Text("Browse", style = MaterialTheme.typography.labelSmall, color = PocketColors.accentSoft)
                    }
                }

                // Optional initial ISO
                if (initialIso != null) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = PocketColors.surfaceInput,
                        border = BorderStroke(1.dp, PocketColors.accent)
                    ) {
                        Column {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { includeInitial = !includeInitial }
                                    .padding(horizontal = 14.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("Add ${initialIso.name} now", style = MaterialTheme.typography.labelMedium, color = PocketColors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(UsbGadgetEngine.formatBytes(initialIso.sizeBytes), style = PocketColors.monoStyle, color = PocketColors.textSecondary)
                                }
                                PocketSwitch(checked = includeInitial, onCheckedChange = { includeInitial = it }, activeColor = PocketColors.success)
                            }
                            if (includeInitial) {
                                PocketHairlineDivider()
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { moveInitial = !moveInitial }
                                        .padding(horizontal = 14.dp, vertical = 9.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text("Move instead of Copy (saves phone storage)", style = MaterialTheme.typography.labelMedium, color = PocketColors.textPrimary)
                                        Text("Deletes source ISO after verified copy", fontSize = 11.sp, color = PocketColors.textSecondary)
                                    }
                                    PocketSwitch(checked = moveInitial, onCheckedChange = { moveInitial = it }, activeColor = PocketColors.warning)
                                }
                            }
                        }
                    }
                }

                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = PocketColors.surfaceInput,
                    border = BorderStroke(0.5.dp, PocketColors.border)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { autoMount = !autoMount }
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Mount as USB Drive when done", style = MaterialTheme.typography.labelMedium, color = PocketColors.textPrimary)
                            Text("Auto-mount as Read-Only USB when ready", fontSize = 11.sp, color = PocketColors.textSecondary)
                        }
                        PocketSwitch(checked = autoMount, onCheckedChange = { autoMount = it }, activeColor = PocketColors.success)
                    }
                }

                if (targetExists) {
                    Surface(shape = RoundedCornerShape(10.dp), color = PocketColors.warningBg, border = BorderStroke(0.5.dp, PocketColors.warning)) {
                        Row(modifier = Modifier.fillMaxWidth().padding(10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.WarningAmber, null, tint = PocketColors.warning, modifier = Modifier.size(16.dp))
                            Text("$resolvedOutName already exists in this folder and will be overwritten.", style = MaterialTheme.typography.bodySmall, color = PocketColors.textPrimary)
                        }
                    }
                }

                Button(
                    onClick = {
                        onStartBuild(
                            VentoyBuildConfig(
                                outputDir = defaultDir,
                                outputName = finalImgName,
                                sizeMb = effectiveSizeMb,
                                scheme = scheme,
                                filesystem = filesystem,
                                win11BypassCheck = experience.bypassRequirements,
                                win11BypassNro = experience.bypassMicrosoftAccount,
                                experience = experience,
                                winUtil = winUtil,
                                driversDir = driversDir,
                                initialIsoToAdd = if (includeInitial) initialIso else null,
                                moveInitialIso = moveInitial,
                                autoMountAfterBuild = autoMount
                            )
                        )
                    },
                    enabled = assetInfo.ready && trimmedName.isNotBlank() && customValid && !updatingAssets,
                    modifier = Modifier.fillMaxWidth().height(PocketTokens.PrimaryButtonHeight),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = PocketColors.accent, contentColor = PocketColors.onAccent)
                ) {
                    Text("Create $displaySize Multi-Boot Drive", fontWeight = FontWeight.SemiBold)
                }
            }

            Spacer(modifier = Modifier.height(22.dp))
        }
    }

    if (showDriversPicker) RootFolderPickerSheet("Select Drivers Folder (Ventoy Injection)", "Packed into /ventoy/drivers_inject.zip for Windows ISOs", driversDir.ifBlank { defaultDir }, { showDriversPicker = false }) { driversDir = it; showDriversPicker = false }
}


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VentoyManagerSheet(
    ventoyFile: ImageFile,
    isMountedToPc: Boolean,
    availableIsos: List<ImageFile>,
    presets: List<AutounattendPreset> = emptyList(),
    defaultDir: String = "/data/media/0/Download",
    onSavePreset: (AutounattendPreset) -> Unit = {},
    onDeletePreset: (String) -> Unit = {},
    onDismiss: () -> Unit,
    onUnmountFirst: () -> Unit,
    onLibraryChanged: () -> Unit,
    onMessage: (String, Boolean) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var loading by remember { mutableStateOf(true) }
    var contents by remember { mutableStateOf<VentoyDriveContents?>(null) }
    var opBusy by remember { mutableStateOf(false) }
    var mgrExp by remember { mutableStateOf(WindowsExperienceConfig()) }
    var mgrWinUtil by remember { mutableStateOf(WinUtilTweaksConfig()) }
    var mgrDriversDir by remember { mutableStateOf("") }
    var showMgrDriversPicker by remember { mutableStateOf(false) }
    var showMgrTweaksPanel by remember { mutableStateOf(false) }
    var opProgress by remember { mutableStateOf(WindowsBuildProgress()) }
    var showAddIsoPicker by remember { mutableStateOf(false) }
    var showResizeDialog by remember { mutableStateOf(false) }
    var moveOnAdd by remember { mutableStateOf(false) }
    var assetInfo by remember { mutableStateOf(VentoyImageBuilder.getInstalledVentoyInfo(context)) }

    fun reloadContents() {
        if (isMountedToPc) {
            loading = false
            return
        }
        scope.launch {
            loading = true
            val loaded = VentoyImageBuilder.inspectDriveContents(context, ventoyFile.path)
            contents = loaded
            if (loaded != null) {
                mgrExp = loaded.savedExperience
                mgrWinUtil = loaded.savedWinUtil
            }
            loading = false
        }
    }

    LaunchedEffect(ventoyFile.path, isMountedToPc) {
        reloadContents()
    }

    ModalBottomSheet(
        onDismissRequest = { if (!opBusy) onDismiss() },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = PocketColors.surfaceCard
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .imePadding()
                .padding(horizontal = 20.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(42.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(PocketColors.accentBg),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Rounded.Layers, null, tint = PocketColors.accentSoft, modifier = Modifier.size(22.dp))
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text("Multi-Boot Manager", style = MaterialTheme.typography.titleLarge, color = PocketColors.textPrimary)
                    Text(ventoyFile.name, style = MaterialTheme.typography.bodySmall, color = PocketColors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (!isMountedToPc && !opBusy) {
                    IconButton(onClick = { reloadContents() }) {
                        Icon(Icons.Rounded.Refresh, "Refresh", tint = PocketColors.textSecondary)
                    }
                }
            }

            if (isMountedToPc) {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = PocketColors.warningBg,
                    border = BorderStroke(1.dp, PocketColors.warning)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Rounded.WarningAmber, null, tint = PocketColors.warning, modifier = Modifier.size(20.dp))
                            Text("Drive Currently Mounted to PC", style = MaterialTheme.typography.labelLarge, color = PocketColors.textPrimary)
                        }
                        Text(
                            "Unmount this Multi-Boot drive before adding or removing ISOs on your phone to prevent filesystem corruption.",
                            style = MaterialTheme.typography.bodySmall,
                            color = PocketColors.textSecondary
                        )
                        Button(
                            onClick = onUnmountFirst,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = PocketColors.danger, contentColor = Color.White)
                        ) {
                            Icon(Icons.Rounded.Eject, null, modifier = Modifier.size(17.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Unmount Drive Now", fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            } else if (opBusy) {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = PocketColors.surfaceInput,
                    border = BorderStroke(1.dp, PocketColors.accent)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(opProgress.stage, style = MaterialTheme.typography.titleMedium, color = PocketColors.textPrimary)
                        if (opProgress.detail.isNotBlank()) {
                            Text(opProgress.detail, style = MaterialTheme.typography.bodySmall, color = PocketColors.textSecondary)
                        }
                        if (opProgress.isIndeterminate) {
                            LinearProgressIndicator(
                                modifier = Modifier.fillMaxWidth().height(7.dp).clip(RoundedCornerShape(999.dp)),
                                color = PocketColors.accent,
                                trackColor = PocketColors.surfaceSubtle
                            )
                        } else {
                            LinearProgressIndicator(
                                progress = { opProgress.progress.coerceIn(0f, 1f) },
                                modifier = Modifier.fillMaxWidth().height(7.dp).clip(RoundedCornerShape(999.dp)),
                                color = PocketColors.accent,
                                trackColor = PocketColors.surfaceSubtle
                            )
                        }
                    }
                }
            } else if (loading) {
                Box(modifier = Modifier.fillMaxWidth().padding(vertical = 28.dp), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        CircularProgressIndicator(color = PocketColors.accent)
                        Text("Inspecting Multi-Boot partition...", style = MaterialTheme.typography.bodySmall, color = PocketColors.textSecondary)
                    }
                }
            } else if (contents == null) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = PocketColors.dangerBg,
                    border = BorderStroke(1.dp, PocketColors.danger)
                ) {
                    Text(
                        "Could not mount Multi-Boot Partition 1. Check Root & Filesystems in Pre-Flight Diagnostics.",
                        modifier = Modifier.padding(14.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = PocketColors.textPrimary
                    )
                }
            } else {
                val c = contents!!
                val usedBytes = (c.totalBytes - c.freeBytes).coerceAtLeast(0L)
                val usedRatio = if (c.totalBytes > 0L) (usedBytes.toFloat() / c.totalBytes.toFloat()).coerceIn(0f, 1f) else 0f

                // Storage & Partition Info Card
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = PocketColors.surfaceInput,
                    border = BorderStroke(0.5.dp, PocketColors.border)
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("${c.info.scheme.label} • ${c.fsType}", style = MaterialTheme.typography.labelLarge, color = PocketColors.textPrimary)
                            Text("${UsbGadgetEngine.formatBytes(c.freeBytes)} free", style = MaterialTheme.typography.labelMedium, color = PocketColors.success)
                        }
                        LinearProgressIndicator(
                            progress = { usedRatio },
                            modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(999.dp)),
                            color = PocketColors.accent,
                            trackColor = PocketColors.surfaceSubtle
                        )
                        Text(
                            "Used ${UsbGadgetEngine.formatBytes(usedBytes)} of ${UsbGadgetEngine.formatBytes(c.totalBytes)} • ${c.files.size} bootable file(s)",
                            style = MaterialTheme.typography.bodySmall,
                            color = PocketColors.textSecondary
                        )
                    }
                }

                // Add ISO + Non-destructive Bootloader Upgrade buttons
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { showAddIsoPicker = true },
                        modifier = Modifier.weight(1.2f).height(PocketTokens.PrimaryButtonHeight),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = PocketColors.accent, contentColor = PocketColors.onAccent)
                    ) {
                        Icon(Icons.Rounded.Add, null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Add ISO", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                    }
                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                opBusy = true
                                opProgress = WindowsBuildProgress("Updating Multi-Boot bootloader...", "Writing Engine v${assetInfo.version} (ISOs kept)", 0.5f, true)
                                val res = VentoyImageBuilder.upgradeVentoyBootloader(context, ventoyFile.path)
                                opBusy = false
                                onMessage(res.userMessage, !res.success)
                            }
                        },
                        enabled = assetInfo.ready,
                        modifier = Modifier.weight(1f).height(PocketTokens.PrimaryButtonHeight),
                        shape = RoundedCornerShape(12.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp),
                        border = BorderStroke(0.5.dp, PocketColors.border)
                    ) {
                        Icon(Icons.Rounded.SystemUpdateAlt, null, tint = PocketColors.accentSoft, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Sync v${assetInfo.version}", fontSize = 12.sp, color = PocketColors.textPrimary, maxLines = 1)
                    }
                }

                // Reclaim Sparse Free Space + Non-Destructive Resize row
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                opBusy = true
                                opProgress = WindowsBuildProgress("Reclaiming free space...", "Zeroing deleted blocks & punching sparse holes", 0.3f, true)
                                val res = VentoyImageBuilder.reclaimSparseSpace(context, ventoyFile.path) { opProgress = it }
                                opBusy = false
                                onMessage(res.userMessage, !res.success)
                                if (res.success) onLibraryChanged()
                                reloadContents()
                            }
                        },
                        modifier = Modifier.weight(1f).height(42.dp),
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp),
                        border = BorderStroke(0.5.dp, PocketColors.border)
                    ) {
                        Icon(Icons.Rounded.CleaningServices, null, tint = PocketColors.success, modifier = Modifier.size(15.dp))
                        Spacer(modifier = Modifier.width(5.dp))
                        Text("Reclaim Space", fontSize = 12.sp, color = PocketColors.textPrimary, maxLines = 1)
                    }
                    OutlinedButton(
                        onClick = { showResizeDialog = true },
                        modifier = Modifier.weight(1f).height(42.dp),
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp),
                        border = BorderStroke(0.5.dp, PocketColors.border)
                    ) {
                        Icon(Icons.Rounded.AspectRatio, null, tint = PocketColors.infoBlue, modifier = Modifier.size(15.dp))
                        Spacer(modifier = Modifier.width(5.dp))
                        Text("Resize Drive", fontSize = 12.sp, color = PocketColors.textPrimary, maxLines = 1)
                    }
                }

                // ISOs inside Ventoy drive
                Text("ISOs Inside This Drive", style = MaterialTheme.typography.labelLarge, color = PocketColors.textPrimary)
                if (c.files.isEmpty()) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = PocketColors.surfaceInput,
                        border = BorderStroke(0.5.dp, PocketColors.border)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(Icons.Rounded.Info, null, tint = PocketColors.accentSoft, modifier = Modifier.size(18.dp))
                            Text("No ISOs inside yet. Tap 'Add ISO' above to add Windows 11, Linux, or rescue ISOs.", style = MaterialTheme.typography.bodySmall, color = PocketColors.textSecondary)
                        }
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        c.files.forEach { item ->
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = PocketColors.surfaceInput,
                                border = BorderStroke(0.5.dp, PocketColors.border)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    Icon(Icons.Rounded.Album, null, tint = PocketColors.accentSoft, modifier = Modifier.size(20.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(item.name, style = MaterialTheme.typography.labelLarge, color = PocketColors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text(UsbGadgetEngine.formatBytes(item.sizeBytes), style = MaterialTheme.typography.bodySmall, color = PocketColors.textSecondary)
                                    }
                                    IconButton(
                                        onClick = {
                                            scope.launch {
                                                opBusy = true
                                                opProgress = WindowsBuildProgress("Removing ISO...", item.name, 0.5f, true)
                                                val res = VentoyImageBuilder.removeIsoFromVentoy(context, ventoyFile.path, item.relativePath)
                                                opBusy = false
                                                onMessage(res.userMessage, !res.success)
                                                reloadContents()
                                            }
                                        }
                                    ) {
                                        Icon(Icons.Rounded.DeleteOutline, "Remove", tint = PocketColors.danger, modifier = Modifier.size(20.dp))
                                    }
                                }
                            }
                        }
                    }
                }

                // Win 11 Bypass toggles inside ventoy.json
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = PocketColors.surfaceInput,
                    border = BorderStroke(0.5.dp, PocketColors.border)
                ) {
                    Column {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Win 11 TPM / Secure Boot Bypass", style = MaterialTheme.typography.labelMedium, color = PocketColors.textPrimary)
                                Text("Skips Win 11 TPM, Secure Boot & RAM checks", fontSize = 11.sp, color = PocketColors.textSecondary)
                            }
                            PocketSwitch(
                                checked = c.win11BypassCheck,
                                onCheckedChange = { newVal ->
                                    scope.launch {
                                        opBusy = true
                                        opProgress = WindowsBuildProgress("Updating boot config...", "", 0.5f, true)
                                        val res = VentoyImageBuilder.updateVentoyJson(context, ventoyFile.path, newVal, c.win11BypassNro)
                                        opBusy = false
                                        onMessage(res.userMessage, !res.success)
                                        reloadContents()
                                    }
                                },
                                activeColor = PocketColors.success
                            )
                        }
                        PocketHairlineDivider()
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Win 11 Offline Account Bypass (NRO)", style = MaterialTheme.typography.labelMedium, color = PocketColors.textPrimary)
                                Text("Enables offline local account during Win 11 OOBE", fontSize = 11.sp, color = PocketColors.textSecondary)
                            }
                            PocketSwitch(
                                checked = c.win11BypassNro,
                                onCheckedChange = { newVal ->
                                    scope.launch {
                                        opBusy = true
                                        opProgress = WindowsBuildProgress("Updating boot config...", "", 0.5f, true)
                                        val res = VentoyImageBuilder.updateVentoyJson(context, ventoyFile.path, c.win11BypassCheck, newVal)
                                        opBusy = false
                                        onMessage(res.userMessage, !res.success)
                                        reloadContents()
                                    }
                                },
                                activeColor = PocketColors.success
                            )
                        }
                    }
                }

                Surface(modifier = Modifier.fillMaxWidth().bounceClick { showMgrTweaksPanel = !showMgrTweaksPanel }, shape = RoundedCornerShape(12.dp), color = PocketColors.accentBg, border = BorderStroke(0.75.dp, PocketColors.accent)) {
                    Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 13.dp, vertical = 11.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Windows Experience, WinUtil & Drivers Injection", style = MaterialTheme.typography.labelLarge, color = PocketColors.accentSoft)
                            Text("Applies to all Windows ISOs on this drive (Linux ISOs untouched)", fontSize = 11.sp, color = PocketColors.textSecondary)
                        }
                        Text(if (showMgrTweaksPanel) "Hide" else "Configure", style = MaterialTheme.typography.labelSmall, color = PocketColors.accentSoft)
                    }
                }
                if (showMgrTweaksPanel) {
                    WindowsTwoPaneCustomizer(
                        osKind = WindowsOsKind.WIN11, arch = "amd64", experience = mgrExp, winUtil = mgrWinUtil, presets = presets,
                        showCa2023Option = false, showCustomScriptOption = false, defaultBrowseDir = defaultDir,
                        onExperienceChange = { mgrExp = it }, onWinUtilChange = { mgrWinUtil = it },
                        onApplyPreset = { p ->
                            mgrExp = WindowsExperienceConfig(p.bypassRequirements, p.bypassMicrosoftAccount, p.disableDataCollection, p.disableBitLocker, p.qolImprovements, p.disableSponsoredApps, p.classicContextMenu, p.skipWifiSetup, p.createLocalUser, p.localUsername, "", p.skipOobe, p.useCa2023Bootloaders, p.applySkuSiPolicy)
                            mgrWinUtil = WinUtilTweaksConfig.fromJsonString(p.winUtilJson)
                        },
                        onSavePreset = { n -> onSavePreset(AutounattendPreset(n, PartitionScheme.GPT.name, mgrExp.bypassRequirements, mgrExp.bypassMicrosoftAccount, mgrExp.disableDataCollection, mgrExp.disableBitLocker, mgrExp.disableSponsoredApps, mgrExp.classicContextMenu, mgrExp.skipWifiSetup, mgrExp.createLocalUser, mgrExp.localUsername, mgrExp.skipOobe, mgrExp.qolImprovements, mgrExp.useCa2023Bootloaders, mgrExp.applySkuSiPolicy, mgrWinUtil.toJsonString())) },
                        onDeletePreset = onDeletePreset
                    )
                    Surface(modifier = Modifier.fillMaxWidth().bounceClick { showMgrDriversPicker = true }, shape = RoundedCornerShape(12.dp), color = PocketColors.surfaceInput, border = BorderStroke(0.75.dp, if (mgrDriversDir.isNotBlank()) PocketColors.accent else PocketColors.border)) {
                        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 13.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Icon(Icons.Rounded.FolderOpen, null, tint = PocketColors.accentSoft, modifier = Modifier.size(18.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Inject Drivers Folder (/ventoy/drivers_inject.zip)", style = MaterialTheme.typography.labelMedium, color = PocketColors.textPrimary)
                                Text(when { mgrDriversDir.isNotBlank() -> mgrDriversDir.replace("/data/media/0", "/sdcard"); c.hasDriversZip -> "Active (/ventoy/drivers_inject.zip) · Tap to replace"; else -> "Tap to browse folder" }, fontSize = 11.sp, color = PocketColors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            Text("Browse", style = MaterialTheme.typography.labelSmall, color = PocketColors.accentSoft)
                        }
                    }
                    Button(
                        onClick = {
                            scope.launch {
                                opBusy = true; opProgress = WindowsBuildProgress("Applying Windows tweaks & drivers...", ventoyFile.name, 0.5f, true)
                                val res = VentoyImageBuilder.updateVentoyCustomization(context, ventoyFile.path, mgrExp, mgrWinUtil, mgrDriversDir.ifBlank { null })
                                opBusy = false; onMessage(res.userMessage, !res.success); reloadContents()
                            }
                        },
                        enabled = !opBusy, modifier = Modifier.fillMaxWidth().height(46.dp), shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = PocketColors.accent, contentColor = PocketColors.onAccent)
                    ) { Text("Apply Tweaks & Drivers to Multi-Boot", fontWeight = FontWeight.SemiBold) }
                }
            }

            Spacer(modifier = Modifier.height(22.dp))
        }
    }

    // Pick ISO from PocketBoot Library to Copy or Move into Ventoy
    if (showAddIsoPicker) {
        val isoCandidates = remember(availableIsos) {
            availableIsos.filter { it.name.endsWith(".iso", true) }
        }
        AlertDialog(
            onDismissRequest = { showAddIsoPicker = false },
            containerColor = PocketColors.surfaceCard,
            title = { Text("Add ISO to Multi-Boot Drive", color = PocketColors.textPrimary) },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = PocketColors.surfaceInput,
                        border = BorderStroke(0.5.dp, PocketColors.border)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { moveOnAdd = !moveOnAdd }
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Move ISO (instead of Copy)", style = MaterialTheme.typography.labelMedium, color = PocketColors.textPrimary)
                                Text("Avoids keeping 2 copies on phone", fontSize = 11.sp, color = PocketColors.textSecondary)
                            }
                            PocketSwitch(checked = moveOnAdd, onCheckedChange = { moveOnAdd = it }, activeColor = PocketColors.warning)
                        }
                    }

                    if (isoCandidates.isEmpty()) {
                        Text(
                            "No .iso files found in Your Drive Files. Tap Browse on Home to add an ISO first.",
                            style = MaterialTheme.typography.bodySmall,
                            color = PocketColors.textSecondary
                        )
                    } else {
                        isoCandidates.forEach { iso ->
                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        showAddIsoPicker = false
                                        scope.launch {
                                            opBusy = true
                                            opProgress = WindowsBuildProgress("Preparing...", iso.name, 0.03f, true)
                                            val res = VentoyImageBuilder.addIsoToVentoy(
                                                context = context,
                                                ventoyImgPath = ventoyFile.path,
                                                sourceIsoPath = iso.path,
                                                moveFile = moveOnAdd,
                                                onProgress = { opProgress = it }
                                            )
                                            opBusy = false
                                            onMessage(res.userMessage, !res.success)
                                            if (res.success && moveOnAdd) onLibraryChanged()
                                            reloadContents()
                                        }
                                    },
                                shape = RoundedCornerShape(10.dp),
                                color = PocketColors.surfaceInput,
                                border = BorderStroke(0.5.dp, PocketColors.border)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    Icon(Icons.Rounded.Album, null, tint = PocketColors.accentSoft, modifier = Modifier.size(18.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(iso.name, style = MaterialTheme.typography.labelMedium, color = PocketColors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text(UsbGadgetEngine.formatBytes(iso.sizeBytes), fontSize = 11.sp, color = PocketColors.textSecondary)
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showAddIsoPicker = false }) {
                    Text("Close", color = PocketColors.textSecondary)
                }
            }
        )
    }

    if (showResizeDialog) {
        val currentGb = ((ventoyFile.sizeBytes / (1024L * 1024L * 1024L)).toInt()).coerceAtLeast(1)
        val optionsGb = listOf(16, 32, 64, 128).filter { it > currentGb }
        var targetGb by remember(currentGb) { mutableIntStateOf(optionsGb.firstOrNull() ?: (currentGb + 16).coerceAtMost(128)) }

        AlertDialog(
            onDismissRequest = { showResizeDialog = false },
            containerColor = PocketColors.surfaceCard,
            title = { Text("Resize Multi-Boot Drive", color = PocketColors.textPrimary) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "Non-destructively expand ${ventoyFile.name} (currently ~${currentGb} GB) while keeping all stored ISOs intact.",
                        style = MaterialTheme.typography.bodySmall,
                        color = PocketColors.textSecondary
                    )
                    if (optionsGb.isEmpty()) {
                        Text("This drive is already at maximum capacity (128 GB).", style = MaterialTheme.typography.bodySmall, color = PocketColors.warning)
                    } else {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                            optionsGb.forEach { gb ->
                                val sel = targetGb == gb
                                Surface(
                                    modifier = Modifier.weight(1f).bounceClick { targetGb = gb },
                                    shape = RoundedCornerShape(10.dp),
                                    color = if (sel) PocketColors.accentBg else PocketColors.surfaceInput,
                                    border = BorderStroke(if (sel) 1.5.dp else 0.5.dp, if (sel) PocketColors.accent else PocketColors.border)
                                ) {
                                    Box(modifier = Modifier.padding(vertical = 10.dp), contentAlignment = Alignment.Center) {
                                        Text("${gb} GB", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = if (sel) PocketColors.accentSoft else PocketColors.textPrimary)
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        showResizeDialog = false
                        scope.launch {
                            opBusy = true
                            opProgress = WindowsBuildProgress("Resizing Multi-Boot drive...", "Expanding to $targetGb GB", 0.1f, true)
                            val res = VentoyImageBuilder.resizeVentoyImage(context, ventoyFile.path, targetGb) { opProgress = it }
                            opBusy = false
                            onMessage(res.userMessage, !res.success)
                            if (res.success) onLibraryChanged()
                            reloadContents()
                        }
                    },
                    enabled = optionsGb.isNotEmpty(),
                    colors = ButtonDefaults.buttonColors(containerColor = PocketColors.accent, contentColor = PocketColors.onAccent)
                ) {
                    Text("Expand to $targetGb GB", fontWeight = FontWeight.SemiBold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showResizeDialog = false }) {
                    Text("Cancel", color = PocketColors.textSecondary)
                }
            }
        )
    }

    if (showMgrDriversPicker) RootFolderPickerSheet("Select Drivers Folder (Ventoy Injection)", "Packed into /ventoy/drivers_inject.zip for Windows ISOs", mgrDriversDir.ifBlank { defaultDir }, { showMgrDriversPicker = false }) { mgrDriversDir = it; showMgrDriversPicker = false }
}
