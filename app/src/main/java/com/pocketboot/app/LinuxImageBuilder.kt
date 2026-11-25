package com.pocketboot.app

import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding

enum class LinuxUsbMode(val title: String, val subtitle: String) {
    WITHOUT_PERSISTENCE("Without Persistence", "Standard Live USB (Hybrid ISO or FAT32)"),
    WITH_PERSISTENCE("With Persistence", "Saves files, Wi-Fi & settings across reboots")
}

enum class LinuxWriteMethod(val title: String, val subtitle: String) {
    HYBRID_DD("Hybrid DD / ISO", "Fastest · keeps exact ISO sectors"),
    EXTRACTED_FAT32("Extracted FAT32 (ISO Mode)", "Clean FAT32 USB · best for tools & extra files")
}

enum class LinuxPersistenceStyle(val title: String, val volumeLabel: String, val subtitle: String) {
    UBUNTU_WRITABLE("Ubuntu 20+ / Mint / Rescuezilla", "writable", "ext4 labeled writable (modern Casper)"),
    CASPER_RW("Pop!_OS / Legacy Ubuntu", "casper-rw", "ext4 labeled casper-rw"),
    DEBIAN_KALI("Debian / Kali / GParted / Tails", "persistence", "ext4 labeled persistence + persistence.conf")
}

data class LinuxBuildConfig(
    val isoFile: ImageFile,
    val outputDir: String,
    val outputName: String,
    val usbMode: LinuxUsbMode = LinuxUsbMode.WITH_PERSISTENCE,
    val writeMethod: LinuxWriteMethod = LinuxWriteMethod.HYBRID_DD,
    val scheme: PartitionScheme = PartitionScheme.MBR,
    val persistenceGb: Int = 2,
    val style: LinuxPersistenceStyle = LinuxPersistenceStyle.UBUNTU_WRITABLE,
    val autoMountAfterBuild: Boolean = true,
    val volumeLabel: String = ""
)

object LinuxImageBuilder {
    private const val TEMP_LINUX_MNT = "/data/local/tmp/pb_linux_persist"
    private const val TEMP_FAT_MNT = "/data/local/tmp/pb_linux_fat"
    private const val FAT32_MAX_FILE_BYTES = 4_294_967_295L

    private fun nowTs(): String = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
    private fun shellQuote(value: String): String = "'${value.replace("'", "'\\''")}'"

    suspend fun inspectMaxFileBytesInIso(context: Context, isoPath: String): Long = withContext(Dispatchers.IO) {
        val bundled7z = WindowsImageBuilder.findBundledBinary(context, "lib7zzs.so", listOf("7zz", "7z")) ?: return@withContext 0L
        Shell.cmd("${shellQuote(bundled7z)} l -slt ${shellQuote(UsbGadgetEngine.resolveDirectPath(isoPath))} 2>/dev/null | awk -F' = ' '/^Size = /{if (\$2+0 > m) m=\$2+0} END{print m+0}'")
            .exec().out.lastOrNull()?.trim()?.toLongOrNull() ?: 0L
    }

    fun cancelActiveBuild() {
        BuildThermalHelper.cancelActiveBuildProcess()
    }

    suspend fun buildLinuxPersistentImage(context: Context, config: LinuxBuildConfig, onProgress: (WindowsBuildProgress) -> Unit): ActionResult = withContext(Dispatchers.IO) {
        BuildThermalHelper.resetCancelFlag()
        if (config.writeMethod == LinuxWriteMethod.EXTRACTED_FAT32) buildExtractedFat32Image(context, config, onProgress)
        else buildHybridDdImage(context, config, onProgress)
    }

    private suspend fun buildHybridDdImage(context: Context, config: LinuxBuildConfig, onProgress: (WindowsBuildProgress) -> Unit): ActionResult = withContext(Dispatchers.IO) {
        val ts = nowTs()
        val entries = mutableListOf<LogEntry>()
        val safeDir = UsbGadgetEngine.resolveDirectPath(config.outputDir).trimEnd('/')
        val safeName = UsbGadgetEngine.sanitizeOutputFileName(config.outputName, ".img", "linux-usb")
        val outPath = "$safeDir/$safeName"
        val srcIsoPath = UsbGadgetEngine.resolveDirectPath(config.isoFile.path)
        val qIso = shellQuote(srcIsoPath)
        val qOut = shellQuote(outPath); val qDir = shellQuote(safeDir)
        var loopDev = ""
        fun cleanup(deletePartial: Boolean) {
            BuildThermalHelper.unmountAndDetachClean(
                mountPoints = listOf(TEMP_LINUX_MNT),
                loopDevices = if (loopDev.isNotBlank()) listOf(loopDev) else emptyList(),
                removeDirs = true
            )
            if (deletePartial) Shell.cmd("rm -f $qOut 2>/dev/null").exec()
        }
        return@withContext runCatching {
            if (!Shell.getShell().isRoot) return@withContext ActionResult(false, "Root access is required.", entries = entries)
            if (outPath.equals(srcIsoPath, ignoreCase = true)) return@withContext ActionResult(false, "Output image cannot overwrite the source ISO.", entries = entries)
            if (UsbGadgetEngine.getStatus().mountedLunFor(outPath) != null) return@withContext ActionResult(false, "$safeName is currently mounted to your PC. Unmount it before overwriting.", entries = entries)
            onProgress(WindowsBuildProgress("Checking ISO image...", config.isoFile.name, 0.05f, true))
            val isoSize = Shell.cmd("stat -c '%s' $qIso 2>/dev/null").exec().out.firstOrNull()?.trim()?.toLongOrNull() ?: config.isoFile.sizeBytes
            if (isoSize <= 0L) return@withContext ActionResult(false, "Source ISO not found or empty.", entries = entries)
            val withPersistence = config.usbMode == LinuxUsbMode.WITH_PERSISTENCE
            val persistBytes = if (withPersistence) config.persistenceGb.coerceIn(1, 32).toLong() * 1024L * 1024L * 1024L else 0L
            val alignBytes = 1024L * 1024L
            val persistOffsetBytes = ((isoSize + alignBytes - 1L) / alignBytes) * alignBytes
            val totalBytes = if (withPersistence) persistOffsetBytes + persistBytes else isoSize
            val freeSpace = UsbGadgetEngine.getFreeSpaceBytes(safeDir)
            if (freeSpace > 0L && freeSpace < totalBytes + (64L * 1024L * 1024L)) return@withContext ActionResult(false, "Not enough free space. Need ${UsbGadgetEngine.formatBytes(totalBytes)}, have ${UsbGadgetEngine.formatBytes(freeSpace)}.", entries = entries)
            onProgress(WindowsBuildProgress("Copying ISO image...", if (withPersistence) "${UsbGadgetEngine.formatBytes(isoSize)} + ${config.persistenceGb} GB ${config.style.volumeLabel}" else UsbGadgetEngine.formatBytes(isoSize), 0.28f, true))
            val linuxProf = BuildThermalHelper.currentDefaultThermalProfile()
            BuildThermalHelper.waitForThermalCooldownIfNeeded(context, linuxProf) { temp, resumeAt ->
                onProgress(WindowsBuildProgress("Cooling down phone...", String.format(Locale.US, "%.1f°C -> resuming at %.1f°C", temp, resumeAt), 0.25f, true))
            }
            if (!isActive || BuildThermalHelper.isBuildCancelled()) { cleanup(true); return@withContext ActionResult(false, "Build cancelled.", entries = entries) }
            val cpRes = BuildThermalHelper.runIsolatedRootScript(
                context,
                "linux_cp",
                "set -e\n${BuildThermalHelper.thermalExecPrefix(linuxProf)}\nmkdir -p $qDir && rm -f $qOut && \$THERM_PFX cp -f $qIso $qOut 2>&1",
                trackPid = true
            )
            if (!isActive || BuildThermalHelper.isBuildCancelled()) { cleanup(true); return@withContext ActionResult(false, "Build cancelled.", entries = entries) }
            if (!cpRes.isSuccess) { cleanup(true); return@withContext ActionResult(false, "Failed to copy ISO: ${(cpRes.out + cpRes.err).joinToString(" ").take(200)}", entries = entries) }

            if (withPersistence) {
                if (!Shell.cmd("truncate -s $totalBytes $qOut 2>&1 || dd if=/dev/zero of=$qOut bs=1M count=0 seek=${totalBytes / (1024L * 1024L)} 2>&1").exec().isSuccess) { cleanup(true); return@withContext ActionResult(false, "Failed to expand image for persistence.", entries = entries) }
                onProgress(WindowsBuildProgress("Adding persistence partition...", "Updating MBR partition table", 0.58f, false))
                val tmpIn = File(context.cacheDir, "pb_linux_mbr_in.bin"); val tmpOut = File(context.cacheDir, "pb_linux_mbr_out.bin")
                try {
                    Shell.cmd("dd if=$qOut of=${shellQuote(tmpIn.absolutePath)} bs=512 count=1 2>/dev/null").exec()
                    val patched = DiskPartitioner.appendLinuxPersistenceMbr(if (tmpIn.exists()) tmpIn.readBytes() else ByteArray(0), persistOffsetBytes / 512L, persistBytes / 512L)
                    if (patched == null) { cleanup(true); return@withContext ActionResult(false, "Could not append persistence partition (try Extracted FAT32 mode).", entries = entries) }
                    tmpOut.writeBytes(patched)
                    Shell.cmd("dd if=${shellQuote(tmpOut.absolutePath)} of=$qOut bs=512 count=1 conv=notrunc 2>/dev/null").exec()
                } finally { tmpIn.delete(); tmpOut.delete() }
                onProgress(WindowsBuildProgress("Formatting ext4 persistence...", "Label: ${config.style.volumeLabel}", 0.74f, false))
                loopDev = attachLoopSlice(qOut, persistOffsetBytes, persistBytes)
                if (loopDev.isBlank() || !loopDev.startsWith("/dev/")) { cleanup(true); return@withContext ActionResult(false, "Could not attach loop device.", entries = entries) }
                formatExt4AndInit(shellQuote(loopDev), config.style, entries, ts)?.let { cleanup(true); return@withContext ActionResult(false, it, entries = entries) }
            }
            onProgress(WindowsBuildProgress("Finalizing USB image...", "Syncing & fixing permissions", 0.95f, false))
            cleanup(false)
            UsbGadgetEngine.fixUserStoragePermissions(context, safeDir, isDir = true)
            UsbGadgetEngine.fixUserStoragePermissions(context, outPath, isDir = false)
            val lOutBytes = Shell.cmd("stat -c '%s' $qOut 2>/dev/null || echo 0").exec().out.firstOrNull()?.trim()?.toLongOrNull() ?: 0L
            if (lOutBytes > 0L) UsbGadgetEngine.syncMediaStoreFileSize(context, outPath, lOutBytes)
            onProgress(WindowsBuildProgress("Done!", safeName, 1f, false))
            val msg = if (withPersistence) "Built $safeName with ${config.persistenceGb} GB ${config.style.volumeLabel}." else "Built standard live USB image $safeName."
            entries.add(LogEntry(ts, "linux-build", msg))
            ActionResult(true, msg, createdPath = outPath, entries = entries)
        }.getOrElse { e -> cleanup(true); ActionResult(false, "Linux/Tool build failed: ${e.message ?: "Unexpected error"}", entries = entries) }
    }

    private suspend fun buildExtractedFat32Image(context: Context, config: LinuxBuildConfig, onProgress: (WindowsBuildProgress) -> Unit): ActionResult = withContext(Dispatchers.IO) {
        val ts = nowTs()
        val entries = mutableListOf<LogEntry>()
        val safeDir = UsbGadgetEngine.resolveDirectPath(config.outputDir).trimEnd('/')
        val safeName = UsbGadgetEngine.sanitizeOutputFileName(config.outputName, ".img", "linux-usb")
        val outPath = "$safeDir/$safeName"
        val srcIsoPath = UsbGadgetEngine.resolveDirectPath(config.isoFile.path)
        val qIso = shellQuote(srcIsoPath); val qOut = shellQuote(outPath); val qDir = shellQuote(safeDir)
        var fatLoop = ""; var extLoop = ""
        fun cleanup(deletePartial: Boolean) {
            BuildThermalHelper.unmountAndDetachClean(
                mountPoints = listOf(TEMP_FAT_MNT, TEMP_LINUX_MNT),
                loopDevices = listOf(fatLoop, extLoop).filter { it.isNotBlank() },
                removeDirs = true
            )
            if (deletePartial) Shell.cmd("rm -f $qOut 2>/dev/null").exec()
        }
        return@withContext runCatching {
            if (!Shell.getShell().isRoot) return@withContext ActionResult(false, "Root access is required.", entries = entries)
            if (outPath.equals(srcIsoPath, ignoreCase = true)) return@withContext ActionResult(false, "Output image cannot overwrite the source ISO.", entries = entries)
            if (UsbGadgetEngine.getStatus().mountedLunFor(outPath) != null) return@withContext ActionResult(false, "$safeName is currently mounted to your PC. Unmount it before overwriting.", entries = entries)
            val bin7z = WindowsImageBuilder.findBundledBinary(context, "lib7zzs.so", listOf("7zz", "7z")) ?: return@withContext ActionResult(false, "Bundled 7zz binary missing.", entries = entries)
            onProgress(WindowsBuildProgress("Checking ISO contents...", config.isoFile.name, 0.05f, true))
            val maxFile = inspectMaxFileBytesInIso(context, srcIsoPath)
            if (maxFile > FAT32_MAX_FILE_BYTES) return@withContext ActionResult(false, "ISO contains a file over 4 GB (${UsbGadgetEngine.formatBytes(maxFile)}). Use Hybrid DD / ISO mode instead.", entries = entries)
            val isoSize = Shell.cmd("stat -c '%s' $qIso 2>/dev/null").exec().out.firstOrNull()?.trim()?.toLongOrNull() ?: config.isoFile.sizeBytes
            val withPersistence = config.usbMode == LinuxUsbMode.WITH_PERSISTENCE
            val fatBytes = ((isoSize / (1024L * 1024L)) + 256L).coerceAtLeast(512L) * 1024L * 1024L
            val persistBytes = if (withPersistence) config.persistenceGb.coerceIn(1, 32).toLong() * 1024L * 1024L * 1024L else 0L
            val part1OffsetBytes = 1024L * 1024L
            val part2OffsetBytes = part1OffsetBytes + fatBytes
            val totalBytes = part2OffsetBytes + persistBytes + (2L * 1024L * 1024L)
            val freeSpace = UsbGadgetEngine.getFreeSpaceBytes(safeDir)
            if (freeSpace > 0L && freeSpace < totalBytes + (64L * 1024L * 1024L)) return@withContext ActionResult(false, "Not enough free space. Need ${UsbGadgetEngine.formatBytes(totalBytes)}, have ${UsbGadgetEngine.formatBytes(freeSpace)}.", entries = entries)
            onProgress(WindowsBuildProgress("Allocating FAT32 USB image...", UsbGadgetEngine.formatBytes(totalBytes), 0.14f, false))
            if (!Shell.cmd("mkdir -p $qDir && rm -f $qOut && truncate -s $totalBytes $qOut 2>&1").exec().isSuccess) return@withContext ActionResult(false, "Failed to allocate image file.", entries = entries)
            val mbrBytes = DiskPartitioner.buildLinuxFat32MbrSector(
                part1StartLba = part1OffsetBytes / 512L,
                part1Sectors = fatBytes / 512L,
                part2StartLba = part2OffsetBytes / 512L,
                part2Sectors = persistBytes / 512L,
                includePersistence = withPersistence
            )
            val tmpMbr = File(context.cacheDir, "pb_fat_mbr.bin").apply { writeBytes(mbrBytes) }
            try { Shell.cmd("dd if=${shellQuote(tmpMbr.absolutePath)} of=$qOut bs=512 count=1 conv=notrunc 2>/dev/null").exec() } finally { tmpMbr.delete() }
            WindowsImageBuilder.findBundledBinary(context, "libms_sys.so", listOf("ms-sys"))?.let { msSys -> Shell.cmd("${shellQuote(msSys)} -s -f $qOut >/dev/null 2>&1 || ${shellQuote(msSys)} -7 -f $qOut >/dev/null 2>&1 || true").exec() }

            val fatLabel = if (config.volumeLabel.isNotBlank()) {
                IsoInspector.sanitizeFatLabel(config.volumeLabel, fallback = "POCKETBOOT", uppercase = true)
            } else {
                IsoInspector.defaultLinuxFat32Label(srcIsoPath, config.isoFile.name)
            }
            val qFatLbl = shellQuote(fatLabel)
            onProgress(WindowsBuildProgress("Formatting FAT32 partition...", "${fatBytes / (1024L * 1024L)} MB · $fatLabel", 0.28f, false))
            fatLoop = attachLoopSlice(qOut, part1OffsetBytes, fatBytes)
            if (fatLoop.isBlank() || !fatLoop.startsWith("/dev/")) { cleanup(true); return@withContext ActionResult(false, "Could not attach loop device for FAT32.", entries = entries) }
            val qFatLoop = shellQuote(fatLoop)
            val mkFatBin = WindowsImageBuilder.findBundledBinary(context, "libmkfs_fat.so", listOf("mkfs.vfat", "mkfs.fat", "newfs_msdos"))
            val fatFmtCmd = when {
                mkFatBin != null && mkFatBin.contains("newfs_msdos") -> "${shellQuote(mkFatBin)} -F 32 -L $qFatLbl $qFatLoop 2>&1"
                mkFatBin != null -> "${shellQuote(mkFatBin)} -F 32 -n $qFatLbl $qFatLoop 2>&1"
                else -> "mkfs.vfat -F 32 -n $qFatLbl $qFatLoop 2>&1 || newfs_msdos -F 32 -L $qFatLbl $qFatLoop 2>&1"
            }
            val fatFmtRes = Shell.cmd(fatFmtCmd).exec()
            if (!fatFmtRes.isSuccess) { cleanup(true); return@withContext ActionResult(false, "FAT32 format failed: ${(fatFmtRes.out + fatFmtRes.err).joinToString(" ").take(200)}", entries = entries) }

            val linuxProf = BuildThermalHelper.currentDefaultThermalProfile()
            BuildThermalHelper.waitForThermalCooldownIfNeeded(context, linuxProf) { temp, resumeAt ->
                onProgress(WindowsBuildProgress("Cooling down phone...", String.format(Locale.US, "%.1f°C -> resuming at %.1f°C", temp, resumeAt), 0.42f, true))
            }
            if (!isActive || BuildThermalHelper.isBuildCancelled()) { cleanup(true); return@withContext ActionResult(false, "Build cancelled.", entries = entries) }
            onProgress(WindowsBuildProgress("Extracting ISO files...", "${config.isoFile.name} · ${linuxProf.shortLabel} thermal", 0.45f, true))
            if (!Shell.cmd("mkdir -p $TEMP_FAT_MNT && mount -t vfat -o rw $qFatLoop $TEMP_FAT_MNT 2>&1").exec().isSuccess) { cleanup(true); return@withContext ActionResult(false, "Could not mount FAT32 partition.", entries = entries) }
            val mmtArg = BuildThermalHelper.sevenZipThreadArgFor(linuxProf)
            val extRes = BuildThermalHelper.runIsolatedRootScript(
                context,
                "linux_ext",
                "set -e\n${BuildThermalHelper.thermalExecPrefix(linuxProf)}\n\$THERM_PFX ${shellQuote(bin7z)} x -y $mmtArg -o$TEMP_FAT_MNT $qIso 2>&1",
                trackPid = true
            )
            if (!isActive || BuildThermalHelper.isBuildCancelled()) { cleanup(true); return@withContext ActionResult(false, "Build cancelled.", entries = entries) }
            if (!extRes.isSuccess) { cleanup(true); return@withContext ActionResult(false, "ISO extraction failed: ${(extRes.out + extRes.err).takeLast(4).joinToString(" ").take(200)}", entries = entries) }

            // Sync label-dependent distro boot configs (CDLABEL=, root=live:LABEL=, archisolabel=, misolabel=, GRUB search -l/--label)
            val safeSedLbl = fatLabel.replace("/", "").replace("&", "").replace("|", "").replace("#", "")
            Shell.cmd(
                """
                find $TEMP_FAT_MNT -maxdepth 4 -type f \( -iname 'grub.cfg' -o -iname 'isolinux.cfg' -o -iname 'syslinux.cfg' -o -iname 'loopback.cfg' -o -iname 'txt.cfg' \) 2>/dev/null | while IFS= read -r cfg; do
                    sed -i -E "s#(CDLABEL=)[^[:space:]\"']+#\1$safeSedLbl#g; s#(root=live:LABEL=)[^[:space:]\"']+#\1$safeSedLbl#g; s#(archisolabel=)[^[:space:]\"']+#\1$safeSedLbl#g; s#(misolabel=)[^[:space:]\"']+#\1$safeSedLbl#g; s#(search[[:space:]][^;#]*[[:space:]](-l|--label)[[:space:]]+['\"]?)[^[:space:]'\"]+(['\"]?)#\1$safeSedLbl\3#g" "${'$'}cfg" 2>/dev/null || true
                done
                """.trimIndent()
            ).exec()
            entries.add(LogEntry(ts, "linux-build", "Formatted FAT32 ($fatLabel) & synced bootloader label entries"))

            if (withPersistence) {
                val bootParam = if (config.style == LinuxPersistenceStyle.DEBIAN_KALI) "persistence" else "persistent"
                Shell.cmd(
                    """
                    find $TEMP_FAT_MNT -maxdepth 4 -type f \( -iname 'grub.cfg' -o -iname 'isolinux.cfg' -o -iname 'syslinux.cfg' -o -iname 'loopback.cfg' -o -iname 'txt.cfg' \) 2>/dev/null | while IFS= read -r cfg; do
                        sed -i "/$bootParam/!{s/boot=casper/boot=casper $bootParam/g; s/boot=live/boot=live $bootParam/g;}" "${'$'}cfg" 2>/dev/null || true
                    done
                    """.trimIndent()
                ).exec()
                entries.add(LogEntry(ts, "linux-build", "Injected '$bootParam' kernel boot flag"))
                onProgress(WindowsBuildProgress("Formatting ext4 persistence...", "${config.persistenceGb} GB ${config.style.volumeLabel}", 0.78f, false))
                extLoop = attachLoopSlice(qOut, part2OffsetBytes, persistBytes)
                if (extLoop.isBlank() || !extLoop.startsWith("/dev/")) { cleanup(true); return@withContext ActionResult(false, "Could not attach persistence loop.", entries = entries) }
                formatExt4AndInit(shellQuote(extLoop), config.style, entries, ts)?.let { cleanup(true); return@withContext ActionResult(false, it, entries = entries) }
            }

            onProgress(WindowsBuildProgress("Finalizing FAT32 USB...", "Syncing & fixing permissions", 0.95f, false))
            cleanup(false)
            UsbGadgetEngine.fixUserStoragePermissions(context, safeDir, isDir = true)
            UsbGadgetEngine.fixUserStoragePermissions(context, outPath, isDir = false)
            val lOutBytes = Shell.cmd("stat -c '%s' $qOut 2>/dev/null || echo 0").exec().out.firstOrNull()?.trim()?.toLongOrNull() ?: 0L
            if (lOutBytes > 0L) UsbGadgetEngine.syncMediaStoreFileSize(context, outPath, lOutBytes)
            val msg = if (withPersistence) "Built FAT32 USB $safeName + ${config.persistenceGb} GB ${config.style.volumeLabel}." else "Built Extracted FAT32 USB $safeName."
            entries.add(LogEntry(ts, "linux-build", msg))
            onProgress(WindowsBuildProgress("Done!", safeName, 1f, false))
            ActionResult(true, msg, createdPath = outPath, entries = entries)
        }.getOrElse { e -> cleanup(true); ActionResult(false, "Linux/Tool USB build failed: ${e.message ?: "Unexpected error"}", entries = entries) }
    }

    private fun attachLoopSlice(qOut: String, offsetBytes: Long, sizeBytes: Long): String {
        val loopCmd = """
            L=${'$'}(losetup -f -s -o $offsetBytes -S $sizeBytes $qOut 2>/dev/null)
            if [ -z "${'$'}L" ]; then L=${'$'}(losetup -f --show -o $offsetBytes --sizelimit $sizeBytes $qOut 2>/dev/null); fi
            if [ -z "${'$'}L" ]; then
                CAND=${'$'}(losetup -f 2>/dev/null)
                if [ -n "${'$'}CAND" ] && (losetup -o $offsetBytes -S $sizeBytes "${'$'}CAND" $qOut 2>/dev/null || losetup -o $offsetBytes --sizelimit $sizeBytes "${'$'}CAND" $qOut 2>/dev/null); then L="${'$'}CAND"; fi
            fi
            echo "${'$'}L"
        """.trimIndent()
        return Shell.cmd(loopCmd).exec().out.lastOrNull().orEmpty().trim()
    }

    private fun formatExt4AndInit(qLoop: String, style: LinuxPersistenceStyle, entries: MutableList<LogEntry>, ts: String): String? {
        val label = style.volumeLabel
        val ext4Res = Shell.cmd("""
            if command -v mke2fs >/dev/null 2>&1; then mke2fs -t ext4 -F -L $label $qLoop 2>&1
            elif command -v mkfs.ext4 >/dev/null 2>&1; then mkfs.ext4 -F -L $label $qLoop 2>&1
            elif [ -x /system/bin/mke2fs ]; then /system/bin/mke2fs -t ext4 -F -L $label $qLoop 2>&1
            else echo "No mke2fs/mkfs.ext4 binary found on device"; exit 1; fi
        """.trimIndent()).exec()
        if (!ext4Res.isSuccess) return "ext4 format failed: ${(ext4Res.out + ext4Res.err).joinToString(" ").take(200)}"
        if (style == LinuxPersistenceStyle.DEBIAN_KALI) {
            if (Shell.cmd("mkdir -p $TEMP_LINUX_MNT && mount -t ext4 -o rw $qLoop $TEMP_LINUX_MNT 2>&1 && printf '/ union\\n' > $TEMP_LINUX_MNT/persistence.conf").exec().isSuccess) {
                entries.add(LogEntry(ts, "linux-build", "Created /persistence.conf (/ union)"))
            }
        }
        return null
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LinuxBuilderSheet(
    isoFile: ImageFile, defaultDir: String, building: Boolean, progress: WindowsBuildProgress,
    onDismiss: () -> Unit, onCancelBuild: () -> Unit, onStartBuild: (LinuxBuildConfig) -> Unit
) {
    val context = LocalContext.current
    val defaultStyle = remember(isoFile.name) {
        val low = isoFile.name.lowercase()
        when {
            low.contains("kali") || low.contains("debian") || low.contains("tails") || low.contains("gparted") || low.contains("clonezilla") || low.contains("parrot") -> LinuxPersistenceStyle.DEBIAN_KALI
            low.contains("pop-os") || low.contains("pop_os") -> LinuxPersistenceStyle.CASPER_RW
            else -> LinuxPersistenceStyle.UBUNTU_WRITABLE
        }
    }
    var usbMode by remember(isoFile.path) { mutableStateOf(LinuxUsbMode.WITH_PERSISTENCE) }
    var writeMethod by remember(isoFile.path) { mutableStateOf(LinuxWriteMethod.HYBRID_DD) }
    var style by remember(isoFile.path) { mutableStateOf(defaultStyle) }
    var persistGb by remember { mutableIntStateOf(2) }
    var autoMount by remember { mutableStateOf(true) }
    var maxIsoFileBytes by remember(isoFile.path) { mutableStateOf(0L) }
    var detectedFatLabel by remember(isoFile.path) { mutableStateOf(IsoInspector.sanitizeFatLabel(isoFile.name.substringBeforeLast('.'), "POCKETBOOT", uppercase = true)) }
    var volumeLabel by remember(isoFile.path) { mutableStateOf(detectedFatLabel) }
    var volumeLabelEdited by remember(isoFile.path) { mutableStateOf(false) }
    LaunchedEffect(isoFile.path) {
        maxIsoFileBytes = LinuxImageBuilder.inspectMaxFileBytesInIso(context, isoFile.path)
        val probed = withContext(Dispatchers.IO) { IsoInspector.defaultLinuxFat32Label(isoFile.path, isoFile.name) }
        detectedFatLabel = probed
        if (!volumeLabelEdited) volumeLabel = probed
    }

    val defaultOutName = remember(isoFile.name, usbMode) {
        val stem = isoFile.name.substringBeforeLast('.').take(32)
        if (usbMode == LinuxUsbMode.WITH_PERSISTENCE) "$stem-Persist.img" else "$stem-USB.img"
    }
    var outputName by remember(defaultOutName) { mutableStateOf(defaultOutName) }
    val fat32Blocked = maxIsoFileBytes > 4_294_967_295L
    val estNeeded = isoFile.sizeBytes + (if (usbMode == LinuxUsbMode.WITH_PERSISTENCE) persistGb.toLong() * 1024L * 1024L * 1024L else 0L) + (if (writeMethod == LinuxWriteMethod.EXTRACTED_FAT32) 256L * 1024L * 1024L else 16L * 1024L * 1024L)
    val freeSpace = remember(defaultDir) { UsbGadgetEngine.getFreeSpaceBytes(defaultDir) }
    val lowSpace = freeSpace in 1 until estNeeded

    var targetExists by remember(isoFile.path) { mutableStateOf(false) }
    val resolvedOutName = remember(outputName, defaultOutName) { UsbGadgetEngine.sanitizeOutputFileName(outputName.ifBlank { defaultOutName }, ".img", "linux-usb") }
    LaunchedEffect(defaultDir, resolvedOutName) {
        targetExists = UsbGadgetEngine.fileExistsInDir(defaultDir, resolvedOutName)
    }

    ModalBottomSheet(onDismissRequest = { if (!building) onDismiss() }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = PocketColors.surfaceCard) {
        Column(modifier = Modifier.fillMaxWidth().navigationBarsPadding().imePadding().padding(horizontal = 20.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(13.dp)) {
            PocketSheetHeader(icon = Icons.Rounded.Terminal, tint = PocketColors.accentSoft, iconBg = PocketColors.accentBg, title = "Linux & Tool USB Builder", subtitle = "${isoFile.name} · ${UsbGadgetEngine.formatBytes(isoFile.sizeBytes)}", subtitleMono = true)
            if (building) {
                Surface(shape = RoundedCornerShape(14.dp), color = PocketColors.surfaceInput, border = BorderStroke(1.dp, PocketColors.accent)) {
                    Column(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(progress.stage, style = MaterialTheme.typography.titleMedium, color = PocketColors.textPrimary)
                        if (progress.detail.isNotBlank()) Text(progress.detail, style = MaterialTheme.typography.bodySmall, color = PocketColors.textSecondary)
                        if (progress.isIndeterminate) LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(7.dp).clip(RoundedCornerShape(999.dp)), color = PocketColors.accent, trackColor = PocketColors.surfaceSubtle)
                        else LinearProgressIndicator(progress = { progress.progress.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().height(7.dp).clip(RoundedCornerShape(999.dp)), color = PocketColors.accent, trackColor = PocketColors.surfaceSubtle)
                    }
                }
                OutlinedButton(onClick = onCancelBuild, modifier = Modifier.fillMaxWidth().height(48.dp), shape = RoundedCornerShape(12.dp), border = BorderStroke(1.dp, PocketColors.danger)) {
                    Icon(Icons.Rounded.Close, null, tint = PocketColors.danger, modifier = Modifier.size(18.dp)); Spacer(modifier = Modifier.width(6.dp)); Text("Cancel Build", color = PocketColors.danger, fontWeight = FontWeight.SemiBold)
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("USB Mode", style = MaterialTheme.typography.labelLarge, color = PocketColors.textPrimary)
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        LinuxUsbMode.entries.forEach { mode ->
                            val selected = usbMode == mode
                            Surface(modifier = Modifier.weight(1f).bounceClick { usbMode = mode }, shape = RoundedCornerShape(12.dp), color = if (selected) PocketColors.accentBg else PocketColors.surfaceInput, border = BorderStroke(if (selected) 1.5.dp else 0.75.dp, if (selected) PocketColors.accent else PocketColors.border)) {
                                Column(modifier = Modifier.padding(11.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text(mode.title, style = MaterialTheme.typography.labelLarge, color = if (selected) PocketColors.accentSoft else PocketColors.textPrimary)
                                    Text(mode.subtitle, fontSize = 10.5.sp, color = PocketColors.textSecondary)
                                }
                            }
                        }
                    }
                }
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Write Method", style = MaterialTheme.typography.labelLarge, color = PocketColors.textPrimary)
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        LinuxWriteMethod.entries.forEach { m ->
                            val selected = writeMethod == m; val blocked = m == LinuxWriteMethod.EXTRACTED_FAT32 && fat32Blocked
                            Surface(modifier = Modifier.weight(1f).bounceClick(enabled = !blocked) { writeMethod = m }, shape = RoundedCornerShape(12.dp), color = if (selected) PocketColors.surfaceSubtle else PocketColors.surfaceInput, border = BorderStroke(if (selected) 1.dp else 0.5.dp, if (selected) PocketColors.accent else PocketColors.border)) {
                                Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text(m.title, style = MaterialTheme.typography.labelMedium, color = if (blocked) PocketColors.textMuted else PocketColors.textPrimary)
                                    Text(if (blocked) "Blocked (>4 GB file in ISO)" else m.subtitle, fontSize = 10.sp, color = PocketColors.textSecondary)
                                }
                            }
                        }
                    }
                }
                AnimatedVisibility(visible = usbMode == LinuxUsbMode.WITH_PERSISTENCE) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("Persistence Partition Format", style = MaterialTheme.typography.labelLarge, color = PocketColors.textPrimary)
                            LinuxPersistenceStyle.entries.forEach { s ->
                                val selected = style == s
                                Surface(modifier = Modifier.fillMaxWidth().bounceClick { style = s }, shape = RoundedCornerShape(11.dp), color = if (selected) PocketColors.accentBg else PocketColors.surfaceInput, border = BorderStroke(if (selected) 1.dp else 0.5.dp, if (selected) PocketColors.accent else PocketColors.border)) {
                                    Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 9.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(s.title, style = MaterialTheme.typography.labelMedium, color = if (selected) PocketColors.accentSoft else PocketColors.textPrimary)
                                            Text(s.subtitle, fontSize = 10.5.sp, color = PocketColors.textSecondary)
                                        }
                                        Text(s.volumeLabel, style = PocketColors.monoStyle, color = PocketColors.accentSoft)
                                    }
                                }
                            }
                        }
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("Persistent Storage Size (ext4)", style = MaterialTheme.typography.labelLarge, color = PocketColors.textPrimary)
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                listOf(1, 2, 4, 8, 16).forEach { gb ->
                                    val selected = persistGb == gb
                                    Surface(modifier = Modifier.weight(1f).bounceClick { persistGb = gb }, shape = RoundedCornerShape(10.dp), color = if (selected) PocketColors.accentBg else PocketColors.surfaceInput, border = BorderStroke(if (selected) 1.25.dp else 0.75.dp, if (selected) PocketColors.accent else PocketColors.border)) {
                                        Box(modifier = Modifier.padding(vertical = 9.dp), contentAlignment = Alignment.Center) { Text("${gb}G", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = if (selected) PocketColors.accentSoft else PocketColors.textPrimary) }
                                    }
                                }
                            }
                        }
                    }
                }
                OutlinedTextField(value = outputName, onValueChange = { outputName = it }, label = { Text("Output USB Image Name") }, singleLine = true, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = PocketColors.accent, unfocusedBorderColor = PocketColors.border, focusedTextColor = PocketColors.textPrimary, unfocusedTextColor = PocketColors.textPrimary))
                AnimatedVisibility(visible = writeMethod == LinuxWriteMethod.EXTRACTED_FAT32) {
                    OutlinedTextField(
                        value = volumeLabel,
                        onValueChange = { volumeLabelEdited = true; volumeLabel = it.filter { ch -> ch.code in 0x20..0x7E && ch !in "\\/:*?\"<>|.,;+=[]'`" }.uppercase(Locale.US).take(11) },
                        label = { Text("FAT32 Volume Label (max 11 chars)") },
                        placeholder = { Text(detectedFatLabel) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = PocketColors.accent, unfocusedBorderColor = PocketColors.border, focusedTextColor = PocketColors.textPrimary, unfocusedTextColor = PocketColors.textPrimary)
                    )
                }
                Surface(shape = RoundedCornerShape(12.dp), color = PocketColors.surfaceInput, border = BorderStroke(0.5.dp, PocketColors.border)) {
                    Row(modifier = Modifier.fillMaxWidth().clickable { autoMount = !autoMount }.padding(horizontal = 13.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Auto-mount when finished", style = MaterialTheme.typography.labelMedium, color = PocketColors.textPrimary)
                            Text(if (usbMode == LinuxUsbMode.WITH_PERSISTENCE) "Mounts as Writable USB (RW) for persistence" else "Mounts as Read-Only USB (RO)", fontSize = 11.sp, color = PocketColors.textSecondary)
                        }
                        PocketSwitch(checked = autoMount, onCheckedChange = { autoMount = it }, activeColor = PocketColors.accent)
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
                if (lowSpace) {
                    Surface(shape = RoundedCornerShape(10.dp), color = PocketColors.warningBg, border = BorderStroke(0.5.dp, PocketColors.warning)) {
                        Row(modifier = Modifier.fillMaxWidth().padding(10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.WarningAmber, null, tint = PocketColors.warning, modifier = Modifier.size(16.dp))
                            Text("Low storage: need ~${UsbGadgetEngine.formatBytes(estNeeded)}, have ${UsbGadgetEngine.formatBytes(freeSpace)}.", style = MaterialTheme.typography.bodySmall, color = PocketColors.textPrimary)
                        }
                    }
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Icon(Icons.Rounded.Info, null, tint = PocketColors.textMuted, modifier = Modifier.size(14.dp))
                        Text(if (writeMethod == LinuxWriteMethod.EXTRACTED_FAT32 && usbMode == LinuxUsbMode.WITH_PERSISTENCE) "Auto-injects persistence boot flag into GRUB/Syslinux." else "Estimated image size: ~${UsbGadgetEngine.formatBytes(estNeeded)}", fontSize = 11.sp, color = PocketColors.textSecondary)
                    }
                }
                Button(onClick = { onStartBuild(LinuxBuildConfig(isoFile, defaultDir, outputName.trim().ifBlank { defaultOutName }, usbMode, writeMethod, PartitionScheme.MBR, persistGb, style, autoMount, volumeLabel = volumeLabel.trim().ifBlank { detectedFatLabel })) }, enabled = outputName.trim().isNotEmpty() && !lowSpace && !(writeMethod == LinuxWriteMethod.EXTRACTED_FAT32 && fat32Blocked), modifier = Modifier.fillMaxWidth().height(48.dp), shape = RoundedCornerShape(12.dp), colors = ButtonDefaults.buttonColors(containerColor = PocketColors.accent, contentColor = PocketColors.onAccent)) {
                    Text(if (usbMode == LinuxUsbMode.WITH_PERSISTENCE) "Build Persistent USB (.img)" else "Build Live USB (.img)", fontWeight = FontWeight.SemiBold)
                }
            }
            Spacer(modifier = Modifier.height(22.dp))
        }
    }
}
