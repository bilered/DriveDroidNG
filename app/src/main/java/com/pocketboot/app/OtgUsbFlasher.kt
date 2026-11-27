package com.pocketboot.app

import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Eject
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.Usb
import androidx.compose.material.icons.rounded.VerifiedUser
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class OtgUsbDevice(
    val devName: String,
    val devPath: String,
    val vendor: String,
    val model: String,
    val sizeBytes: Long,
    val sysfsRealPath: String,
    val readOnly: Boolean,
    val mountedPoints: List<String> = emptyList()
) {
    val displayTitle: String
        get() = listOf(vendor, model).map { it.trim() }.filter { it.isNotEmpty() }
            .joinToString(" ").ifBlank { "USB Storage ($devName)" }
}

data class OtgFlashConfig(
    val sourceFile: ImageFile,
    val targetDevice: OtgUsbDevice,
    val verifyAfterFlash: Boolean = true,
    val fullSha256Verify: Boolean = false
)

/**
 * Physical USB-C OTG pen drive flasher with a 7-layer anti-brick safety lock:
 * 1. Whole-disk regex (`^sd[a-z]+$`), never partitions or mmcblk/loop/dm/zram.
 * 2. Strict canonical sysfs USB bus proof (`/usb\d+/`) + internal bus denylist (`ufshc`, `mmc`, `nvme`, `virtual`).
 * 3. `/proc/mounts` system-mount cross-check (disqualifies any disk touching `/`, `/system`, `/data`, `/vendor`, etc.).
 * 4. Writable (`ro == 0`) & minimum capacity >= 64 MB check.
 * 5. Source size <= target disk capacity fail-closed check.
 * 6. USB-C port mutual-exclusion guard (cannot run while ConfigFS LUN is mounted to PC).
 * 7. Clean Android `vold` unmount gate + explicit `FLASH` typed confirmation.
 */
object OtgUsbFlasher {

    private const val MIN_OTG_BYTES = 64L * 1024L * 1024L
    private const val VERIFY_CHUNK_MB = 16L
    private const val VERIFY_CHUNK_BYTES = VERIFY_CHUNK_MB * 1024L * 1024L

    private val WHOLE_SD_DISK_RE = Regex("^sd[a-z]+$")
    private val USB_BUS_RE = Regex("/usb\\d+/")
    private val FORBIDDEN_BUS_TOKENS = listOf(
        "ufshc", "/ufs", "mmc", "nvme", "/virtual/", "/loop", "/zram", "/dm-", "/ram", "platform/soc/1d84000"
    )
    private val SYSTEM_MOUNT_PREFIXES = listOf(
        "/", "/system", "/vendor", "/data", "/metadata", "/apex", "/odm",
        "/product", "/efs", "/persist", "/mnt/vendor", "/cache", "/boot", "/system_ext"
    )

    private fun nowTs(): String = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())

    private fun shellQuote(value: String): String = "'${value.replace("'", "'\\''")}'"

    fun cancelActiveFlash() {
        BuildThermalHelper.cancelActiveBuildProcess()
    }

    /**
     * Pure-JVM testable 7-layer hardware safety validator.
     * Returns `(true, "OK")` ONLY if the block device is proven to be a writable external USB pen drive.
     */
    fun evaluateBlockDeviceSafety(
        devName: String,
        sysfsRealPath: String,
        readOnly: Boolean,
        sizeBytes: Long,
        procMountsLines: List<String>
    ): Pair<Boolean, String> {
        val cleanName = devName.trim()
        if (!WHOLE_SD_DISK_RE.matches(cleanName)) {
            return false to "Rejected $cleanName: not a whole SCSI/USB disk (sd*)."
        }
        val lowSys = sysfsRealPath.trim().lowercase(Locale.US)
        if (lowSys.isEmpty() || !USB_BUS_RE.containsMatchIn(lowSys)) {
            return false to "Rejected $cleanName: sysfs path is not on an external USB host bus."
        }
        FORBIDDEN_BUS_TOKENS.firstOrNull { lowSys.contains(it) }?.let { bad ->
            return false to "Rejected $cleanName: internal/virtual bus token '$bad' detected."
        }
        if (readOnly) {
            return false to "Rejected $cleanName: drive is hardware write-protected (read-only)."
        }
        if (sizeBytes < MIN_OTG_BYTES) {
            return false to "Rejected $cleanName: capacity too small (${UsbGadgetEngine.formatBytes(sizeBytes)})."
        }
        val devToken1 = "/dev/block/$cleanName"
        val devToken2 = "/dev/$cleanName"
        for (line in procMountsLines) {
            val parts = line.trim().split(Regex("\\s+"))
            if (parts.size < 2) continue
            val src = parts[0]
            val mnt = parts[1]
            val matchesThisDisk =
                src == devToken1 || src == devToken2 ||
                    (src.startsWith(devToken1) && src.length > devToken1.length && src[devToken1.length].isDigit()) ||
                    (src.startsWith(devToken2) && src.length > devToken2.length && src[devToken2.length].isDigit())
            if (matchesThisDisk) {
                val isSystemMnt = SYSTEM_MOUNT_PREFIXES.any { pref ->
                    if (pref == "/") mnt == "/" else (mnt == pref || mnt.startsWith("$pref/"))
                }
                if (isSystemMnt) {
                    return false to "Rejected $cleanName: mounted on protected Android system path ($mnt)."
                }
            }
        }
        return true to "OK"
    }

    fun validateSourceFitsDevice(sourceBytes: Long, targetDiskBytes: Long): String? {
        if (sourceBytes <= 0L) return "Source image is empty or missing."
        if (targetDiskBytes < MIN_OTG_BYTES) return "Target USB drive size could not be determined."
        if (sourceBytes > targetDiskBytes) {
            return "Image (${UsbGadgetEngine.formatBytes(sourceBytes)}) is larger than target USB drive (${UsbGadgetEngine.formatBytes(targetDiskBytes)})."
        }
        return null
    }

    fun parseSectorsWrittenFromStat(statLine: String): Long {
        val fields = statLine.trim().split(Regex("\\s+"))
        // Linux /sys/block/<dev>/stat field index 6 (7th field) = sectors written (512-byte units)
        return fields.getOrNull(6)?.toLongOrNull() ?: 0L
    }

    /**
     * Pure-JVM testable parser returning ONLY mount points belonging to [devName], its numeric partitions,
     * or the exact `(major:minor)` pairs in [targetDevNumbers] (never unmounting sibling `sd*` drives on major 8!).
     * Decodes `/proc/mounts` octal space escapes (`\040`).
     */
    fun extractTargetMountPoints(
        devName: String,
        targetDevNumbers: Set<String>,
        procMountsLines: List<String>
    ): List<String> {
        val cleanDev = devName.trim()
        if (!WHOLE_SD_DISK_RE.matches(cleanDev)) return emptyList()
        val exactVoldTokens = targetDevNumbers
            .map { it.trim().replace(':', ',') }
            .filter { Regex("^\\d+,\\d+$").matches(it) }
            .map { "/dev/block/vold/public:$it" }
            .toSet()

        val p1 = "/dev/block/$cleanDev"
        val p2 = "/dev/$cleanDev"
        val result = linkedSetOf<String>()
        for (line in procMountsLines) {
            val parts = line.trim().split(Regex("\\s+"))
            if (parts.size < 2) continue
            val src = parts[0]
            val rawMp = parts[1]
            val matches =
                src == p1 || src == p2 ||
                    (src.startsWith(p1) && src.length > p1.length && src[p1.length].isDigit()) ||
                    (src.startsWith(p2) && src.length > p2.length && src[p2.length].isDigit()) ||
                    src in exactVoldTokens
            if (matches && rawMp.isNotBlank()) {
                val decodedMp = rawMp
                    .replace("\\040", " ")
                    .replace("\\011", "\t")
                    .replace("\\134", "\\")
                result.add(decodedMp)
            }
        }
        return result.toList()
    }

    suspend fun scanUsbOtgDevices(): List<OtgUsbDevice> = withContext(Dispatchers.IO) {
        if (!Shell.getShell().isRoot) return@withContext emptyList()
        val procMounts = Shell.cmd("cat /proc/mounts 2>/dev/null").exec().out
        val rawOut = Shell.cmd(
            """
            for d in /sys/block/sd*; do
                [ -d "${'$'}d" ] || continue
                name=${'$'}{d##*/}
                real=${'$'}(readlink -f "${'$'}d" 2>/dev/null || echo "")
                sectors=${'$'}(cat "${'$'}d/size" 2>/dev/null || echo 0)
                ro=${'$'}(cat "${'$'}d/ro" 2>/dev/null || echo 1)
                vendor=${'$'}(cat "${'$'}d/device/vendor" 2>/dev/null | tr '\n\r|' '   ' || echo "")
                model=${'$'}(cat "${'$'}d/device/model" 2>/dev/null | tr '\n\r|' '   ' || echo "")
                printf '%s|%s|%s|%s|%s|%s\n' "${'$'}name" "${'$'}real" "${'$'}sectors" "${'$'}ro" "${'$'}vendor" "${'$'}model"
            done
            """.trimIndent()
        ).exec().out

        val devices = mutableListOf<OtgUsbDevice>()
        for (line in rawOut) {
            val p = line.split("|", limit = 6)
            if (p.size < 6) continue
            val name = p[0].trim()
            val real = p[1].trim()
            val sectors = p[2].trim().toLongOrNull() ?: 0L
            val ro = p[3].trim() != "0"
            val vendor = p[4].trim()
            val model = p[5].trim()
            val sizeBytes = sectors * 512L

            val (safe, _) = evaluateBlockDeviceSafety(name, real, ro, sizeBytes, procMounts)
            if (!safe) continue

            val devPath = if (Shell.cmd("[ -b ${shellQuote("/dev/block/$name")} ]").exec().isSuccess) {
                "/dev/block/$name"
            } else if (Shell.cmd("[ -b ${shellQuote("/dev/$name")} ]").exec().isSuccess) {
                "/dev/$name"
            } else {
                continue
            }

            val mntPoints = procMounts.mapNotNull { mLine ->
                val mp = mLine.trim().split(Regex("\\s+"))
                if (mp.size >= 2 && (mp[0].startsWith("/dev/block/$name") || mp[0].startsWith("/dev/$name"))) {
                    mp[1]
                } else null
            }.distinct()

            devices.add(
                OtgUsbDevice(
                    devName = name,
                    devPath = devPath,
                    vendor = vendor,
                    model = model,
                    sizeBytes = sizeBytes,
                    sysfsRealPath = real,
                    readOnly = ro,
                    mountedPoints = mntPoints
                )
            )
        }
        devices
    }

    private fun queryTargetDevNumbers(devName: String): Set<String> {
        val qSys = shellQuote("/sys/block/$devName")
        val out = Shell.cmd(
            """
            cat $qSys/dev 2>/dev/null || true
            for p in $qSys/${devName}[0-9]*/dev; do
                [ -f "${'$'}p" ] && cat "${'$'}p" 2>/dev/null || true
            done
            """.trimIndent()
        ).exec().out
        return out.map { it.trim() }.filter { Regex("^\\d+:\\d+$").matches(it) }.toSet()
    }

    private fun unmountOtgPartitionsClean(devName: String): Pair<Boolean, String> {
        val devNums = queryTargetDevNumbers(devName)
        val initialMounts = extractTargetMountPoints(
            devName,
            devNums,
            Shell.cmd("cat /proc/mounts 2>/dev/null").exec().out
        )
        if (initialMounts.isNotEmpty()) {
            Shell.cmd("sync").exec()
            // Unmount deepest paths first (handles nested bind mounts cleanly)
            for (mp in initialMounts.sortedByDescending { it.length }) {
                val qMp = shellQuote(mp)
                Shell.cmd("umount $qMp 2>/dev/null || umount -l $qMp 2>/dev/null || true").exec()
            }
            Shell.cmd("sync").exec()
        }
        val remaining = extractTargetMountPoints(
            devName,
            devNums,
            Shell.cmd("cat /proc/mounts 2>/dev/null").exec().out
        )
        return if (remaining.isEmpty()) {
            true to "OK"
        } else {
            false to "USB drive partition is still busy/mounted (${remaining.first()}). Close any file manager using it and retry."
        }
    }

    internal fun buildSha256RangeCommand(fileOrDevPath: String, totalBytesToRead: Long): String {
        val qPath = shellQuote(fileOrDevPath)
        val safeBytes = totalBytesToRead.coerceAtLeast(0L)
        val mb = safeBytes / 1_048_576L
        val remAfterMb = safeBytes % 1_048_576L
        val sec = remAfterMb / 512L
        val tail = remAfterMb % 512L
        val parts = mutableListOf<String>()
        if (mb > 0L) {
            parts.add("dd if=$qPath bs=1048576 count=$mb 2>/dev/null")
        }
        if (sec > 0L) {
            parts.add("dd if=$qPath bs=512 skip=${mb * 2048L} count=$sec 2>/dev/null")
        }
        if (tail > 0L) {
            parts.add("dd if=$qPath bs=1 skip=${mb * 1_048_576L + sec * 512L} count=$tail 2>/dev/null")
        }
        return when (parts.size) {
            0 -> "printf '' | sha256sum 2>/dev/null"
            1 -> "${parts[0]} | sha256sum 2>/dev/null"
            else -> "( ${parts.joinToString("; ")} ) | sha256sum 2>/dev/null"
        }
    }

    private fun sha256RangeToyboxSafe(fileOrDevPath: String, totalBytesToRead: Long): String {
        val cmd = buildSha256RangeCommand(fileOrDevPath, totalBytesToRead)
        return Shell.cmd(cmd).exec().out.firstOrNull().orEmpty().trim().substringBefore(' ')
    }

    suspend fun flashImageToOtg(
        context: Context,
        config: OtgFlashConfig,
        onProgress: (WindowsBuildProgress) -> Unit
    ): ActionResult = withContext(Dispatchers.IO) {
        BuildThermalHelper.resetCancelFlag()
        val ts = nowTs()
        val entries = mutableListOf<LogEntry>()
        val target = config.targetDevice
        val srcPath = UsbGadgetEngine.resolveDirectPath(config.sourceFile.path)
        val srcName = srcPath.substringAfterLast('/')
        val compFmt = CompressedImageEngine.detectFormat(srcName)

        // Layer 6: USB-C Port Mutual-Exclusion Guard
        if (UsbGadgetEngine.getStatus().isAnyMounted) {
            return@withContext ActionResult(
                false,
                "Unmount your virtual PC drive before flashing a physical USB-C OTG pen drive.",
                entries = entries
            )
        }

        // Re-verify hardware safety immediately before writing byte 0
        val freshList = scanUsbOtgDevices()
        val verifiedTarget = freshList.firstOrNull {
            it.devName == target.devName && it.sysfsRealPath == target.sysfsRealPath
        } ?: return@withContext ActionResult(
            false,
            "Target USB drive (${target.devPath}) disconnected or failed safety verification. Re-scan and retry.",
            entries = entries
        )

        val qSrc = shellQuote(srcPath)
        val qDev = shellQuote(verifiedTarget.devPath)
        val rawSrcBytes = Shell.cmd("stat -c '%s' $qSrc 2>/dev/null").exec().out
            .firstOrNull()?.trim()?.toLongOrNull() ?: config.sourceFile.sizeBytes
        if (rawSrcBytes <= 0L) {
            return@withContext ActionResult(false, "Source image not found or empty: $srcName", entries = entries)
        }

        val expectedWriteBytes: Long
        var streamCmd = ""
        if (compFmt != null) {
            val probe = CompressedImageEngine.probeCompressedImage(context, srcPath)
            expectedWriteBytes = if (probe?.sizeReliable == true) probe.uncompressedBytes else -1L
            if (expectedWriteBytes > 0L) {
                validateSourceFitsDevice(expectedWriteBytes, verifiedTarget.sizeBytes)?.let { err ->
                    return@withContext ActionResult(false, err, entries = entries)
                }
            }
            val toolBin = when (compFmt) {
                CompressedFormat.ZST -> CompressedImageEngine.findZstdBinary(context)
                    ?: return@withContext ActionResult(false, "Bundled zstd binary unavailable on this build.", entries = entries)
                CompressedFormat.XZ, CompressedFormat.GZ -> CompressedImageEngine.find7zBinary(context)
                    ?: return@withContext ActionResult(false, "Bundled 7-Zip extractor unavailable on this build.", entries = entries)
            }
            val qTool = shellQuote(toolBin)
            streamCmd = when (compFmt) {
                CompressedFormat.ZST -> "$qTool -d -c $qSrc"
                CompressedFormat.XZ, CompressedFormat.GZ -> "$qTool x -y -so $qSrc"
            }
        } else {
            expectedWriteBytes = rawSrcBytes
            validateSourceFitsDevice(expectedWriteBytes, verifiedTarget.sizeBytes)?.let { err ->
                return@withContext ActionResult(false, err, entries = entries)
            }
        }

        return@withContext runCatching {
            onProgress(WindowsBuildProgress("Unmounting USB partitions...", verifiedTarget.displayTitle, 0.04f, true))
            val (unmountedOk, umntErr) = unmountOtgPartitionsClean(verifiedTarget.devName)
            if (!unmountedOk) {
                return@withContext ActionResult(false, umntErr, entries = entries)
            }

            entries.add(LogEntry(ts, "otg-flash", "Flashing $srcName -> ${verifiedTarget.devPath} (${verifiedTarget.displayTitle}, ${UsbGadgetEngine.formatBytes(verifiedTarget.sizeBytes)})"))

            val statPath = "/sys/block/${verifiedTarget.devName}/stat"
            val qStat = shellQuote(statPath)
            val startSectors = parseSectorsWrittenFromStat(
                Shell.cmd("cat $qStat 2>/dev/null").exec().out.firstOrNull().orEmpty()
            )
            val prof = BuildThermalHelper.currentDefaultThermalProfile()
            val rcFile = "/data/local/tmp/pb_otg_pipe_rc"
            val qRc = shellQuote(rcFile)
            val headTmpFile = "/data/local/tmp/pb_otg_head_chk.bin"
            val qHeadTmp = shellQuote(headTmpFile)

            // Toybox/BusyBox/GNU portable dd (no GNU-only `conv=fsync`; `ibs=64k obs=4M` guarantees aligned block writes from pipes)
            val flashScript = if (compFmt == null) {
                """
                set -e
                ${BuildThermalHelper.thermalExecPrefix(prof)}
                ${'$'}THERM_PFX dd if=$qSrc of=$qDev bs=4M 2>&1
                sync
                """.trimIndent()
            } else {
                // POSIX-safe pipe error capture (no bash `pipefail` required)
                """
                set -e
                ${BuildThermalHelper.thermalExecPrefix(prof)}
                rm -f $qRc
                ( $streamCmd; echo ${'$'}? > $qRc ) | ${'$'}THERM_PFX dd of=$qDev ibs=64k obs=4M 2>&1
                PIPE_RC=${'$'}(cat $qRc 2>/dev/null || echo 1)
                rm -f $qRc
                [ "${'$'}PIPE_RC" = "0" ]
                sync
                """.trimIndent()
            }

            val pollJob = launch(Dispatchers.IO) {
                while (isActive) {
                    delay(500L)
                    val curSectors = parseSectorsWrittenFromStat(
                        Shell.cmd("cat $qStat 2>/dev/null").exec().out.firstOrNull().orEmpty()
                    )
                    val writtenBytes = ((curSectors - startSectors).coerceAtLeast(0L)) * 512L
                    if (expectedWriteBytes > 0L) {
                        val frac = (writtenBytes.toFloat() / expectedWriteBytes.toFloat()).coerceIn(0f, 0.96f)
                        val scaled = 0.06f + frac * (if (config.verifyAfterFlash) 0.78f else 0.90f)
                        onProgress(
                            WindowsBuildProgress(
                                stage = "Flashing USB-C Pen Drive...",
                                detail = "${UsbGadgetEngine.formatBytes(writtenBytes)} / ${UsbGadgetEngine.formatBytes(expectedWriteBytes)}",
                                progress = scaled,
                                isIndeterminate = false
                            )
                        )
                    } else {
                        onProgress(
                            WindowsBuildProgress(
                                stage = "Streaming to USB-C Pen Drive...",
                                detail = "Written ${UsbGadgetEngine.formatBytes(writtenBytes)} to ${verifiedTarget.devName}",
                                progress = 0.5f,
                                isIndeterminate = true
                            )
                        )
                    }
                }
            }

            val flashRes = BuildThermalHelper.runIsolatedRootScript(context, "pb_otg_flash", flashScript, trackPid = true)
            pollJob.cancel()

            if (!isActive || BuildThermalHelper.isBuildCancelled()) {
                Shell.cmd("rm -f $qRc $qHeadTmp 2>/dev/null || true; sync; blockdev --flushbufs $qDev 2>/dev/null || true").exec()
                return@withContext ActionResult(false, "USB-C OTG flash cancelled.", entries = entries)
            }
            if (!flashRes.isSuccess) {
                Shell.cmd("rm -f $qRc $qHeadTmp 2>/dev/null || true").exec()
                val errHint = (flashRes.out + flashRes.err).takeLast(2).joinToString(" ").take(150).trim()
                entries.add(LogEntry(ts, "otg-flash", "Flash failed: $errHint", true))
                return@withContext ActionResult(false, "USB-C OTG flash failed: ${errHint.ifBlank { "I/O error writing to pen drive" }}", entries = entries)
            }

            // Flush host page cache & re-read partition table
            Shell.cmd("sync && blockdev --flushbufs $qDev 2>/dev/null || true").exec()

            // Post-Flash Read-Back Verification (100% Toybox/BusyBox universal `dd`, zero `head -c`)
            var verifySummary = "Verification skipped"
            if (config.verifyAfterFlash) {
                if (!isActive || BuildThermalHelper.isBuildCancelled()) {
                    return@withContext ActionResult(false, "USB-C OTG flash cancelled before verification.", entries = entries)
                }
                if (compFmt == null) {
                    if (config.fullSha256Verify) {
                        onProgress(WindowsBuildProgress("Verifying full SHA-256...", verifiedTarget.devName, 0.88f, true))
                        val srcHash = Shell.cmd("sha256sum $qSrc 2>/dev/null").exec().out.firstOrNull().orEmpty().trim().substringBefore(' ')
                        val dstHash = sha256RangeToyboxSafe(verifiedTarget.devPath, expectedWriteBytes)
                        if (srcHash.length != 64 || srcHash != dstHash) {
                            entries.add(LogEntry(ts, "otg-flash", "Full SHA-256 mismatch! src=$srcHash dst=$dstHash", true))
                            return@withContext ActionResult(false, "Verification FAILED: USB pen drive data does not match source image (possible bad/fake flash).", entries = entries)
                        }
                        verifySummary = "Full SHA-256 verified"
                    } else {
                        onProgress(WindowsBuildProgress("Verifying head & tail blocks...", verifiedTarget.devName, 0.88f, true))
                        val checkBytes = expectedWriteBytes.coerceAtMost(VERIFY_CHUNK_BYTES)
                        val srcHead = sha256RangeToyboxSafe(srcPath, checkBytes)
                        val dstHead = sha256RangeToyboxSafe(verifiedTarget.devPath, checkBytes)
                        if (srcHead.length != 64 || srcHead != dstHead) {
                            entries.add(LogEntry(ts, "otg-flash", "Head block mismatch! src=$srcHead dst=$dstHead", true))
                            return@withContext ActionResult(false, "Verification FAILED on head blocks (possible faulty USB pen drive).", entries = entries)
                        }
                        if (!isActive || BuildThermalHelper.isBuildCancelled()) {
                            return@withContext ActionResult(false, "USB-C OTG flash cancelled during verification.", entries = entries)
                        }
                        val totalFullMb = expectedWriteBytes / (1024L * 1024L)
                        val tailSkipMb = (totalFullMb - VERIFY_CHUNK_MB).coerceAtLeast(0L)
                        if (totalFullMb > VERIFY_CHUNK_MB && tailSkipMb >= 1L) {
                            val tailCountMb = (totalFullMb - tailSkipMb).coerceIn(1L, VERIFY_CHUNK_MB)
                            val srcTail = Shell.cmd("dd if=$qSrc bs=1048576 skip=$tailSkipMb count=$tailCountMb 2>/dev/null | sha256sum 2>/dev/null").exec().out.firstOrNull().orEmpty().trim().substringBefore(' ')
                            val dstTail = Shell.cmd("dd if=$qDev bs=1048576 skip=$tailSkipMb count=$tailCountMb 2>/dev/null | sha256sum 2>/dev/null").exec().out.firstOrNull().orEmpty().trim().substringBefore(' ')
                            if (srcTail.length != 64 || srcTail != dstTail) {
                                entries.add(LogEntry(ts, "otg-flash", "Tail block mismatch! src=$srcTail dst=$dstTail", true))
                                return@withContext ActionResult(false, "Verification FAILED on tail blocks (possible fake-capacity USB drive).", entries = entries)
                            }
                        }
                        verifySummary = "Head & Tail blocks verified"
                    }
                } else {
                    onProgress(WindowsBuildProgress("Verifying written head blocks...", verifiedTarget.devName, 0.88f, true))
                    val checkBytes = if (expectedWriteBytes > 0L) expectedWriteBytes.coerceAtMost(VERIFY_CHUNK_BYTES) else VERIFY_CHUNK_BYTES
                    // Capture exact streamed head into a small <=16 MB temp file, then measure actual bytes
                    // so pipe partial-reads can NEVER cause a false hash mismatch against the raw USB block device.
                    val capRes = try {
                        Shell.cmd(
                            """
                            rm -f $qHeadTmp
                            if head -c 1 </dev/zero >/dev/null 2>&1; then
                                $streamCmd 2>/dev/null | head -c $checkBytes > $qHeadTmp 2>/dev/null || true
                            else
                                $streamCmd 2>/dev/null | dd bs=65536 count=${(checkBytes / 65536L).coerceAtLeast(1L)} > $qHeadTmp 2>/dev/null || true
                            fi
                            """.trimIndent()
                        ).exec()
                        val capBytes = Shell.cmd("stat -c '%s' $qHeadTmp 2>/dev/null").exec().out
                            .firstOrNull()?.trim()?.toLongOrNull() ?: 0L
                        if (capBytes <= 0L) {
                            Triple(0L, "", "")
                        } else {
                            val srcHead = Shell.cmd("sha256sum $qHeadTmp 2>/dev/null").exec().out
                                .firstOrNull().orEmpty().trim().substringBefore(' ')
                            val dstHead = sha256RangeToyboxSafe(verifiedTarget.devPath, capBytes)
                            Triple(capBytes, srcHead, dstHead)
                        }
                    } finally {
                        Shell.cmd("rm -f $qHeadTmp 2>/dev/null || true").exec()
                    }
                    val (verifiedBytes, srcHead, dstHead) = capRes
                    if (verifiedBytes <= 0L || srcHead.length != 64 || srcHead != dstHead) {
                        entries.add(LogEntry(ts, "otg-flash", "Streamed head block mismatch (${verifiedBytes} B)! src=$srcHead dst=$dstHead", true))
                        return@withContext ActionResult(false, "Verification FAILED on written head blocks.", entries = entries)
                    }
                    verifySummary = "Head ${UsbGadgetEngine.formatBytes(verifiedBytes)} verified"
                }
            }

            Shell.cmd("sync && blockdev --rereadpt $qDev 2>/dev/null || true").exec()
            entries.add(LogEntry(ts, "otg-flash", "Flashed $srcName to ${verifiedTarget.displayTitle} cleanly ($verifySummary)"))
            onProgress(WindowsBuildProgress("Flash Complete!", verifySummary, 1f, false))
            ActionResult(
                success = true,
                userMessage = "Flashed $srcName to ${verifiedTarget.displayTitle} ($verifySummary). Safe to unplug USB-C OTG drive.",
                entries = entries
            )
        }.getOrElse { e ->
            Shell.cmd("rm -f /data/local/tmp/pb_otg_pipe_rc /data/local/tmp/pb_otg_head_chk.bin 2>/dev/null || true").exec()
            entries.add(LogEntry(ts, "otg-flash", "Error: ${e.message}", true))
            ActionResult(false, "USB-C OTG flash failed: ${e.message ?: "Unexpected error"}", entries = entries)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OtgFlasherSheet(
    initialSource: ImageFile?,
    availableImages: List<ImageFile>,
    isAnyVirtualDriveMounted: Boolean,
    flashing: Boolean,
    progress: WindowsBuildProgress,
    onDismiss: () -> Unit,
    onUnmountAllVirtual: () -> Unit,
    onCancelFlash: () -> Unit,
    onStartFlash: (OtgFlashConfig) -> Unit
) {
    var selectedSource by remember(initialSource?.path) {
        mutableStateOf(initialSource ?: availableImages.firstOrNull())
    }
    var otgDevices by remember { mutableStateOf<List<OtgUsbDevice>>(emptyList()) }
    var selectedDevice by remember { mutableStateOf<OtgUsbDevice?>(null) }
    var scanning by remember { mutableStateOf(true) }
    var scanNonce by remember { mutableIntStateOf(0) }
    var verifyAfterFlash by remember { mutableStateOf(true) }
    var fullSha256Verify by remember { mutableStateOf(false) }
    var showConfirmFlashDialog by remember { mutableStateOf(false) }
    var confirmInput by remember { mutableStateOf("") }

    LaunchedEffect(scanNonce, isAnyVirtualDriveMounted) {
        if (isAnyVirtualDriveMounted) {
            scanning = false
            otgDevices = emptyList()
            selectedDevice = null
            return@LaunchedEffect
        }
        scanning = true
        val found = OtgUsbFlasher.scanUsbOtgDevices()
        otgDevices = found
        selectedDevice = found.firstOrNull { it.devPath == selectedDevice?.devPath } ?: found.firstOrNull()
        scanning = false
    }

    val activeSource = selectedSource
    val activeTarget = selectedDevice
    val isCompressedSrc = activeSource != null && CompressedImageEngine.detectFormat(activeSource.name) != null
    val fitError = if (activeSource != null && activeTarget != null && !isCompressedSrc) {
        OtgUsbFlasher.validateSourceFitsDevice(activeSource.sizeBytes, activeTarget.sizeBytes)
    } else null

    ModalBottomSheet(
        onDismissRequest = { if (!flashing) onDismiss() },
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
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(
                    modifier = Modifier.size(40.dp).clip(RoundedCornerShape(11.dp)).background(PocketColors.warningBg),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Rounded.Usb, null, tint = PocketColors.warning, modifier = Modifier.size(21.dp))
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text("USB-C OTG Pen Drive Flasher", style = MaterialTheme.typography.titleMedium, color = PocketColors.textPrimary)
                    Text("Write ISO, IMG, or .xz/.gz/.zst directly to a physical USB drive", style = MaterialTheme.typography.bodySmall, color = PocketColors.textSecondary)
                }
                if (!flashing && !isAnyVirtualDriveMounted) {
                    IconButton(onClick = { scanNonce++ }) {
                        Icon(Icons.Rounded.Refresh, "Rescan USB-C OTG", tint = PocketColors.textSecondary, modifier = Modifier.size(19.dp))
                    }
                }
            }

            if (flashing) {
                Surface(shape = RoundedCornerShape(14.dp), color = PocketColors.surfaceInput, border = BorderStroke(1.dp, PocketColors.accent)) {
                    Column(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
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
                        Text("Do not unplug your USB-C pen drive while flashing.", fontSize = 11.sp, color = PocketColors.warning)
                    }
                }
                OutlinedButton(
                    onClick = onCancelFlash,
                    modifier = Modifier.fillMaxWidth().height(46.dp),
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, PocketColors.danger)
                ) {
                    Icon(Icons.Rounded.Close, null, tint = PocketColors.danger, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Cancel Flash", color = PocketColors.danger, fontWeight = FontWeight.SemiBold)
                }
            } else if (isAnyVirtualDriveMounted) {
                Surface(shape = RoundedCornerShape(12.dp), color = PocketColors.warningBg, border = BorderStroke(1.dp, PocketColors.warning)) {
                    Column(modifier = Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Rounded.WarningAmber, null, tint = PocketColors.warning, modifier = Modifier.size(19.dp))
                            Text("Virtual PC Drive Currently Active", style = MaterialTheme.typography.labelLarge, color = PocketColors.textPrimary)
                        }
                        Text(
                            "Your phone's USB-C port cannot act as a virtual PC drive and a USB-C OTG Host at the same time. Eject all virtual drives first.",
                            style = MaterialTheme.typography.bodySmall,
                            color = PocketColors.textSecondary
                        )
                        Button(
                            onClick = onUnmountAllVirtual,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = PocketColors.danger, contentColor = Color.White)
                        ) {
                            Icon(Icons.Rounded.Eject, null, modifier = Modifier.size(17.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Eject Virtual Drive(s)", fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            } else {
                // Step 1: Target Physical USB-C OTG Drive
                Text("1. Target Physical USB-C Pen Drive", style = MaterialTheme.typography.labelLarge, color = PocketColors.textPrimary)
                if (scanning) {
                    Surface(shape = RoundedCornerShape(12.dp), color = PocketColors.surfaceInput, border = BorderStroke(0.5.dp, PocketColors.border)) {
                        Row(modifier = Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = PocketColors.accent)
                            Text("Scanning external USB-C OTG block devices...", style = MaterialTheme.typography.bodySmall, color = PocketColors.textSecondary)
                        }
                    }
                } else if (otgDevices.isEmpty()) {
                    Surface(shape = RoundedCornerShape(12.dp), color = PocketColors.surfaceInput, border = BorderStroke(0.5.dp, PocketColors.border)) {
                        Column(modifier = Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("No external USB-C pen drive detected", style = MaterialTheme.typography.labelLarge, color = PocketColors.textPrimary)
                            Text(
                                "Plug a physical USB pen drive into your phone via a USB-C OTG adapter (make sure OTG is enabled in Android Settings if your phone requires it), then tap Refresh above. Internal UFS/eMMC phone storage is permanently blocked from appearing here.",
                                style = MaterialTheme.typography.bodySmall,
                                color = PocketColors.textSecondary
                            )
                        }
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        otgDevices.forEach { dev ->
                            val sel = selectedDevice?.devPath == dev.devPath
                            Surface(
                                modifier = Modifier.fillMaxWidth().bounceClick { selectedDevice = dev },
                                shape = RoundedCornerShape(12.dp),
                                color = if (sel) PocketColors.accentBg else PocketColors.surfaceInput,
                                border = BorderStroke(if (sel) 1.5.dp else 0.5.dp, if (sel) PocketColors.accent else PocketColors.border)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    Icon(Icons.Rounded.Storage, null, tint = if (sel) PocketColors.accentSoft else PocketColors.textSecondary, modifier = Modifier.size(20.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(dev.displayTitle, style = MaterialTheme.typography.labelLarge, color = PocketColors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text(
                                            "${dev.devPath} · ${UsbGadgetEngine.formatBytes(dev.sizeBytes)} · External USB verified",
                                            style = PocketColors.monoStyle,
                                            color = PocketColors.textSecondary,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                    Icon(Icons.Rounded.VerifiedUser, null, tint = PocketColors.success, modifier = Modifier.size(16.dp))
                                }
                            }
                        }
                    }
                }

                // Step 2: Source Image Selector
                Text("2. Source Image (${availableImages.size} available)", style = MaterialTheme.typography.labelLarge, color = PocketColors.textPrimary)
                if (availableImages.isEmpty()) {
                    Surface(shape = RoundedCornerShape(12.dp), color = PocketColors.surfaceInput, border = BorderStroke(0.5.dp, PocketColors.border)) {
                        Text("No .iso, .img, or compressed images found in Your Drives.", modifier = Modifier.padding(14.dp), style = MaterialTheme.typography.bodySmall, color = PocketColors.textSecondary)
                    }
                } else {
                    Column(
                        modifier = Modifier.fillMaxWidth().heightIn(max = 172.dp).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        availableImages.forEach { img ->
                            val sel = selectedSource?.path == img.path
                            Surface(
                                modifier = Modifier.fillMaxWidth().bounceClick { selectedSource = img },
                                shape = RoundedCornerShape(9.dp),
                                color = if (sel) PocketColors.accentBg else PocketColors.surfaceInput,
                                border = BorderStroke(if (sel) 1.25.dp else 0.5.dp, if (sel) PocketColors.accent else PocketColors.border)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(img.name, modifier = Modifier.weight(1f), fontSize = 12.sp, color = if (sel) PocketColors.accentSoft else PocketColors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(UsbGadgetEngine.formatBytes(img.sizeBytes), style = PocketColors.monoStyle, color = PocketColors.textMuted)
                                }
                            }
                        }
                    }
                }

                // Step 3: Post-Flash Verification Options
                Surface(shape = RoundedCornerShape(12.dp), color = PocketColors.surfaceInput, border = BorderStroke(0.5.dp, PocketColors.border)) {
                    Column {
                        Row(
                            modifier = Modifier.fillMaxWidth().clickable { verifyAfterFlash = !verifyAfterFlash }.padding(horizontal = 13.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Read-Back Verification (Recommended)", style = MaterialTheme.typography.labelMedium, color = PocketColors.textPrimary)
                                Text("Catches faulty or fake-capacity USB drives immediately", fontSize = 11.sp, color = PocketColors.textSecondary)
                            }
                            PocketSwitch(checked = verifyAfterFlash, onCheckedChange = { verifyAfterFlash = it }, activeColor = PocketColors.success)
                        }
                        if (verifyAfterFlash && !isCompressedSrc) {
                            PocketHairlineDivider()
                            Row(
                                modifier = Modifier.fillMaxWidth().clickable { fullSha256Verify = !fullSha256Verify }.padding(horizontal = 13.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("Full Drive SHA-256 Check (Slower)", style = MaterialTheme.typography.labelMedium, color = PocketColors.textPrimary)
                                    Text("Off = fast Head 16 MB + Tail 16 MB verification", fontSize = 11.sp, color = PocketColors.textSecondary)
                                }
                                PocketSwitch(checked = fullSha256Verify, onCheckedChange = { fullSha256Verify = it }, activeColor = PocketColors.accent)
                            }
                        }
                    }
                }

                if (fitError != null) {
                    Surface(shape = RoundedCornerShape(10.dp), color = PocketColors.dangerBg, border = BorderStroke(0.75.dp, PocketColors.danger)) {
                        Row(modifier = Modifier.fillMaxWidth().padding(10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.WarningAmber, null, tint = PocketColors.danger, modifier = Modifier.size(16.dp))
                            Text(fitError, style = MaterialTheme.typography.bodySmall, color = PocketColors.textPrimary)
                        }
                    }
                }

                Button(
                    onClick = {
                        confirmInput = ""
                        showConfirmFlashDialog = true
                    },
                    enabled = activeSource != null && activeTarget != null && fitError == null,
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = PocketColors.danger, contentColor = Color.White)
                ) {
                    Icon(Icons.Rounded.WarningAmber, null, modifier = Modifier.size(17.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Erase & Flash USB Pen Drive...", fontWeight = FontWeight.SemiBold)
                }
            }

            Spacer(modifier = Modifier.height(20.dp))
        }
    }

    if (showConfirmFlashDialog && activeSource != null && activeTarget != null) {
        val typedOk = confirmInput.trim().equals("FLASH", ignoreCase = true)
        AlertDialog(
            onDismissRequest = { showConfirmFlashDialog = false },
            containerColor = PocketColors.surfaceCard,
            title = { Text("Confirm Destructive Flash", color = PocketColors.danger) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    Text(
                        "ALL DATA on this external USB drive will be permanently erased:",
                        style = MaterialTheme.typography.bodySmall,
                        color = PocketColors.textPrimary
                    )
                    Surface(shape = RoundedCornerShape(9.dp), color = PocketColors.dangerBg, border = BorderStroke(0.75.dp, PocketColors.danger)) {
                        Column(modifier = Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text("Target: ${activeTarget.displayTitle}", style = MaterialTheme.typography.labelLarge, color = PocketColors.textPrimary)
                            Text("Device: ${activeTarget.devPath} (${UsbGadgetEngine.formatBytes(activeTarget.sizeBytes)})", style = PocketColors.monoStyle, color = PocketColors.textSecondary)
                            Text("Source: ${activeSource.name}", style = PocketColors.monoStyle, color = PocketColors.textSecondary)
                        }
                    }
                    OutlinedTextField(
                        value = confirmInput,
                        onValueChange = { confirmInput = it.take(10) },
                        label = { Text("Type FLASH to confirm") },
                        placeholder = { Text("FLASH") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        showConfirmFlashDialog = false
                        onStartFlash(
                            OtgFlashConfig(
                                sourceFile = activeSource,
                                targetDevice = activeTarget,
                                verifyAfterFlash = verifyAfterFlash,
                                fullSha256Verify = fullSha256Verify && !isCompressedSrc
                            )
                        )
                    },
                    enabled = typedOk,
                    colors = ButtonDefaults.buttonColors(containerColor = PocketColors.danger, contentColor = Color.White)
                ) { Text("Start Flashing", fontWeight = FontWeight.SemiBold) }
            },
            dismissButton = {
                TextButton(onClick = { showConfirmFlashDialog = false }) { Text("Cancel", color = PocketColors.textSecondary) }
            }
        )
    }
}
