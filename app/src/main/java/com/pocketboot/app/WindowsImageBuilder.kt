package com.pocketboot.app

import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BookmarkAdd
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.FolderCopy
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material.icons.rounded.Window
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class WindowsEditionItem(
    val index: Int,
    val name: String
)

data class WindowsBuildConfig(
    val isoFile: ImageFile, val outputDir: String, val outputName: String,
    val scheme: PartitionScheme = PartitionScheme.GPT,
    val selectedEditionIndex: Int? = null, val selectedEditionName: String? = null,
    val osKind: WindowsOsKind = WindowsOsKind.WIN11, val arch: String = "amd64",
    val experience: WindowsExperienceConfig = WindowsExperienceConfig(),
    val winUtil: WinUtilTweaksConfig = WinUtilTweaksConfig(),
    val eiCfgUnlock: Boolean = false,
    val bypassRequirements: Boolean = experience.bypassRequirements,
    val bypassMicrosoftAccount: Boolean = experience.bypassMicrosoftAccount,
    val disableDataCollection: Boolean = experience.disableDataCollection,
    val disableBitLocker: Boolean = experience.disableBitLocker,
    val disableSponsoredApps: Boolean = experience.disableSponsoredApps,
    val classicContextMenu: Boolean = experience.classicContextMenu,
    val skipWifiSetup: Boolean = experience.skipWifiSetup,
    val createLocalUser: Boolean = experience.createLocalUser,
    val localUsername: String = experience.localUsername,
    val localPassword: String = experience.localPassword,
    val skipOobe: Boolean = experience.skipOobe,
    val extraDriversDir: String = "", val autoMountAfterBuild: Boolean = true
)

data class WindowsBuildProgress(
    val stage: String = "Preparing...",
    val detail: String = "",
    val progress: Float = 0f,
    val isIndeterminate: Boolean = true
)

object WindowsImageBuilder {

    private const val UEFI_NTFS_URL = "https://raw.githubusercontent.com/pbatard/rufus/master/res/uefi/uefi-ntfs.img"
    private const val TEMP_MNT = "/data/local/tmp/pb_win_mnt"
    private const val TEMP_ISO_MNT = "/data/local/tmp/pb_iso_src"
    private const val TEMP_EDITION_MNT = "/data/local/tmp/pb_iso_editions"
    private const val FAT32_MAX_FILE = 4_294_967_295L

    private fun nowTs(): String = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())

    private fun shellQuote(value: String): String = "'${value.replace("'", "'\\''")}'"

    private fun xmlEscape(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")

    fun isLikelyWindowsName(fileName: String): Boolean =
        IsoInspector.quickGuessByName(fileName) == IsoOsType.WINDOWS

    /**
     * Reads Windows editions directly from sources/install.wim or sources/install.esd inside an ISO
     * in ~50ms by loop-mounting read-only and reading the 208-byte WIMHEADER_V1_PACKED + UTF-16LE XML chunk.
     */
    suspend fun inspectWindowsEditions(context: Context, isoPath: String): List<WindowsEditionItem> = withContext(Dispatchers.IO) {
        val resolved = UsbGadgetEngine.resolveDirectPath(isoPath)
        val qIso = shellQuote(resolved)
        val hdrTmp = File(context.cacheDir, "pb_wim_hdr_${System.nanoTime()}.bin")
        val xmlTmp = File(context.cacheDir, "pb_wim_xml_${System.nanoTime()}.bin")
        try {
            val mounted = Shell.cmd(
                """
                mkdir -p $TEMP_EDITION_MNT && umount $TEMP_EDITION_MNT 2>/dev/null || true
                mount -t udf -o loop,ro $qIso $TEMP_EDITION_MNT 2>/dev/null || \
                mount -t iso9660 -o loop,ro $qIso $TEMP_EDITION_MNT 2>/dev/null || \
                mount -o loop,ro $qIso $TEMP_EDITION_MNT 2>/dev/null
                """.trimIndent()
            ).exec().isSuccess
            if (!mounted) {
                // Fallback for kernels restricting UDF loop-mounts: peek install.wim/esd via bundled 7zzs + wimlib-imagex
                return@withContext inspectEditionsViaBundledTools(context, resolved)
            }

            val wimPath = Shell.cmd(
                "find $TEMP_EDITION_MNT -maxdepth 2 -type f \\( -iname 'install.wim' -o -iname 'install.esd' -o -iname 'install.swm' \\) 2>/dev/null | head -n 1"
            ).exec().out.firstOrNull().orEmpty().trim()
            if (wimPath.isBlank()) return@withContext emptyList()

            val qWim = shellQuote(wimPath)
            val qHdr = shellQuote(hdrTmp.absolutePath)
            val gotHdr = Shell.cmd("dd if=$qWim of=$qHdr bs=1 count=208 2>/dev/null && chmod 0644 $qHdr").exec().isSuccess
            if (!gotHdr || !hdrTmp.exists() || hdrTmp.length() < 104L) return@withContext emptyList()

            val hdr = hdrTmp.readBytes()
            val magic = String(hdr, 0, 5, Charsets.US_ASCII)
            if (magic != "MSWIM" && magic != "WLPWM") return@withContext emptyList()

            // WIMHEADER_V1_PACKED: rhXmlData (RESHDR_DISK_SHORT, 24 bytes) starts at offset 72
            // bytes 72..78: 7-byte LE size, byte 79: flags, bytes 80..87: 8-byte LE file offset
            var xmlSize = 0L
            for (i in 0 until 7) {
                xmlSize = xmlSize or ((hdr[72 + i].toLong() and 0xFFL) shl (8 * i))
            }
            val xmlOffset = ByteBuffer.wrap(hdr, 80, 8).order(ByteOrder.LITTLE_ENDIAN).long
            if (xmlOffset <= 0L || xmlSize <= 16L || xmlSize > 2L * 1024L * 1024L) return@withContext emptyList()

            val qXml = shellQuote(xmlTmp.absolutePath)
            val skipBlocks = xmlOffset / 512L
            val leadBytes = (xmlOffset % 512L).toInt()
            val totalReadBytes = leadBytes + xmlSize
            val blockCount = (totalReadBytes + 511L) / 512L
            val gotXml = Shell.cmd(
                "dd if=$qWim of=$qXml bs=512 skip=$skipBlocks count=$blockCount 2>/dev/null && chmod 0644 $qXml"
            ).exec().isSuccess
            if (!gotXml || !xmlTmp.exists()) return@withContext emptyList()

            val rawAll = xmlTmp.readBytes()
            if (rawAll.size < leadBytes + 4) return@withContext emptyList()
            val sliceLen = minOf(xmlSize.toInt(), rawAll.size - leadBytes)
            val xmlText = String(rawAll, leadBytes, sliceLen, Charsets.UTF_16LE)

            val imageRegex = Regex("""<IMAGE\s+INDEX="(\d+)"[^>]*>(.*?)</IMAGE>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
            val displayRegex = Regex("""<DISPLAYNAME>(.*?)</DISPLAYNAME>""", RegexOption.IGNORE_CASE)
            val nameRegex = Regex("""<NAME>(.*?)</NAME>""", RegexOption.IGNORE_CASE)

            val found = mutableListOf<WindowsEditionItem>()
            imageRegex.findAll(xmlText).forEach { m ->
                val idx = m.groupValues[1].toIntOrNull() ?: return@forEach
                val body = m.groupValues[2]
                val label = (displayRegex.find(body)?.groupValues?.get(1)
                    ?: nameRegex.find(body)?.groupValues?.get(1)
                    ?: "Edition #$idx").trim()
                if (label.isNotEmpty()) {
                    found.add(WindowsEditionItem(index = idx, name = label))
                }
            }
            found.sortedBy { it.index }
        } catch (_: Exception) {
            emptyList()
        } finally {
            hdrTmp.delete()
            xmlTmp.delete()
            Shell.cmd("umount $TEMP_EDITION_MNT 2>/dev/null || umount -l $TEMP_EDITION_MNT 2>/dev/null; rm -rf $TEMP_EDITION_MNT 2>/dev/null").exec()
        }
    }

    private fun inspectEditionsViaBundledTools(context: Context, resolvedIso: String): List<WindowsEditionItem> {
        val sevenZip = findBundledBinary(context, "lib7zzs.so", listOf("7zzs", "7zz", "7z")) ?: return emptyList()
        val wimlib = findBundledBinary(context, "libwimlib_imagex.so", listOf("wimlib-imagex")) ?: return emptyList()
        val tmpDir = File(context.cacheDir, "pb_ed_probe_${System.nanoTime()}").apply { mkdirs() }
        return try {
            val qIso = shellQuote(resolvedIso)
            val q7z = shellQuote(sevenZip)
            val qWim = shellQuote(wimlib)
            val qTmp = shellQuote(tmpDir.absolutePath)
            // Extract only install.wim/esd if small or use 7zzs listing to locate exact path
            val wimRel = Shell.cmd(
                "$q7z l -ba $qIso 2>/dev/null | awk '{print \$NF}' | grep -iE '(^|/)sources/install\\.(wim|esd)$' | head -n 1"
            ).exec().out.firstOrNull().orEmpty().trim()
            if (wimRel.isBlank()) return emptyList()
            val extOk = Shell.cmd("export TMPDIR=$qTmp && $q7z e -y -o$qTmp $qIso ${shellQuote(wimRel)} >/dev/null 2>&1").exec().isSuccess
            val extracted = tmpDir.listFiles()?.firstOrNull { it.isFile && it.length() > 1024L } ?: return emptyList()
            if (!extOk) return emptyList()
            val lines = Shell.cmd("export TMPDIR=$qTmp && $qWim info ${shellQuote(extracted.absolutePath)} 2>/dev/null").exec().out
            val found = mutableListOf<WindowsEditionItem>()
            var curIdx = -1
            var curName = ""
            lines.forEach { raw ->
                val line = raw.trim()
                when {
                    line.startsWith("Index:") -> {
                        if (curIdx > 0 && curName.isNotBlank()) found.add(WindowsEditionItem(curIdx, curName))
                        curIdx = line.substringAfter(':').trim().toIntOrNull() ?: -1
                        curName = ""
                    }
                    line.startsWith("Display Name:") -> {
                        curName = line.substringAfter(':').trim()
                    }
                    line.startsWith("Name:") && curName.isBlank() -> {
                        curName = line.substringAfter(':').trim()
                    }
                }
            }
            if (curIdx > 0 && curName.isNotBlank()) found.add(WindowsEditionItem(curIdx, curName))
            found.sortedBy { it.index }
        } catch (_: Exception) {
            emptyList()
        } finally {
            tmpDir.deleteRecursively()
        }
    }

    fun generateAutounattendXml(config: WindowsBuildConfig): String = WindowsCustomizationEngine.generateAutounattendXml(
        experience = config.experience, winUtil = config.winUtil, arch = config.arch,
        selectedEditionIndex = config.selectedEditionIndex, hasSpecializeScript = config.experience.hasAnyActive() || config.winUtil.hasAnyActive(), hasOemUpdates = false
    )

    private fun loadUefiNtfsImage(context: Context): ByteArray? {
        runCatching {
            context.assets.open("uefi-ntfs.img").use { stream ->
                val bytes = stream.readBytes()
                if (bytes.size >= 65536) return bytes
            }
        }
        val cached = File(context.filesDir, "uefi-ntfs.img")
        if (cached.exists() && cached.length() >= 65536L) {
            return runCatching { cached.readBytes() }.getOrNull()
        }
        return runCatching {
            val conn = (URL(UEFI_NTFS_URL).openConnection() as HttpURLConnection).apply {
                connectTimeout = 10000
                readTimeout = 15000
                instanceFollowRedirects = true
            }
            if (conn.responseCode in 200..299) {
                val bytes = conn.inputStream.use { it.readBytes() }
                if (bytes.size >= 65536) {
                    cached.writeBytes(bytes)
                    return bytes
                }
            }
            null
        }.getOrNull()
    }

    fun findBundledBinary(context: Context, libName: String, systemFallbacks: List<String> = emptyList()): String? {
        val bundled = File(context.applicationInfo.nativeLibraryDir, libName)
        if (bundled.exists() && bundled.canExecute()) return bundled.absolutePath

        if (systemFallbacks.isEmpty()) return null
        val listStr = systemFallbacks.joinToString(" ")
        val found = Shell.cmd(
            "for b in $listStr; do if command -v \"${'$'}b\" >/dev/null 2>&1; then command -v \"${'$'}b\"; break; elif [ -x \"${'$'}b\" ]; then echo \"${'$'}b\"; break; fi; done"
        ).exec().out.firstOrNull()?.trim()
        return found?.ifBlank { null }
    }

    suspend fun buildWindowsUsbImage(
        context: Context,
        config: WindowsBuildConfig,
        onProgress: (WindowsBuildProgress) -> Unit
    ): ActionResult = withContext(Dispatchers.IO) {
        val ts = nowTs()
        val entries = mutableListOf<LogEntry>()
        val safeDir = UsbGadgetEngine.resolveDirectPath(config.outputDir).trimEnd('/')
        val safeName = config.outputName.trim().let {
            if (it.endsWith(".img", ignoreCase = true)) it else "$it.img"
        }
        val outPath = "$safeDir/$safeName"
        val srcIsoPath = UsbGadgetEngine.resolveDirectPath(config.isoFile.path)
        val qIso = shellQuote(srcIsoPath)
        val qOut = shellQuote(outPath)
        val qDir = shellQuote(safeDir)

        var loopDev = ""

        fun cleanup(deletePartial: Boolean) {
            Shell.cmd(
                """
                sync 2>/dev/null
                umount $TEMP_MNT 2>/dev/null || umount -l $TEMP_MNT 2>/dev/null
                sleep 1
                umount $TEMP_ISO_MNT 2>/dev/null || umount -l $TEMP_ISO_MNT 2>/dev/null
                if [ -n ${shellQuote(loopDev)} ]; then
                    losetup -d ${shellQuote(loopDev)} 2>/dev/null || (sleep 1 && losetup -d ${shellQuote(loopDev)} 2>/dev/null)
                fi
                rm -rf $TEMP_MNT $TEMP_ISO_MNT 2>/dev/null
                """.trimIndent()
            ).exec()
            if (deletePartial) {
                Shell.cmd("rm -f $qOut 2>/dev/null").exec()
            }
        }

        return@withContext runCatching {
            if (!Shell.getShell().isRoot) {
                return@withContext ActionResult(false, "Root access is required to build a bootable USB image.", entries = entries)
            }

            onProgress(WindowsBuildProgress("Checking ISO & tools...", config.isoFile.name, 0.04f, true))

            val isoSize = Shell.cmd("stat -c '%s' $qIso 2>/dev/null")
                .exec().out.firstOrNull()?.trim()?.toLongOrNull() ?: config.isoFile.sizeBytes
            if (isoSize <= 0L) {
                return@withContext ActionResult(false, "Source ISO not found or empty.", entries = entries)
            }

            val uefiNtfsBytes = loadUefiNtfsImage(context)
            val layout = DiskPartitioner.calculateLayout(isoSize, uefiNtfsBytes?.size?.toLong() ?: (1024L * 1024L))

            val freeSpace = UsbGadgetEngine.getFreeSpaceBytes(safeDir)
            if (freeSpace > 0L && freeSpace < layout.totalBytes + (128L * 1024L * 1024L)) {
                val needed = UsbGadgetEngine.formatBytes(layout.totalBytes)
                val avail = UsbGadgetEngine.formatBytes(freeSpace)
                val reason = "Not enough free space. Need $needed, only $avail available."
                entries.add(LogEntry(ts, "win-build", reason, true))
                return@withContext ActionResult(false, reason, entries = entries)
            }

            val sevenZip = findBundledBinary(
                context,
                "lib7zzs.so",
                listOf("7zzs", "7zz", "7z", "/data/data/com.termux/files/usr/bin/7z", "/data/data/com.termux/files/usr/bin/7zz")
            )
            var hasOver4GbFile = isoSize > FAT32_MAX_FILE
            if (sevenZip != null) {
                entries.add(LogEntry(ts, "win-build", "Extractor: $sevenZip"))
                val maxEntrySize = Shell.cmd(
                    "${shellQuote(sevenZip)} l -slt $qIso 2>/dev/null | awk -F' = ' '/^Size = /{if(\$2+0>m)m=\$2+0} END{print m+0}'"
                ).exec().out.firstOrNull()?.trim()?.toLongOrNull() ?: 0L
                if (maxEntrySize > 0L) {
                    hasOver4GbFile = maxEntrySize > FAT32_MAX_FILE
                    entries.add(LogEntry(ts, "win-build", "Largest file in ISO: ${UsbGadgetEngine.formatBytes(maxEntrySize)}"))
                }
            }

            val ntfsFormatter = findBundledBinary(
                context,
                "libmkfs_ntfs.so",
                listOf("mkfs.ntfs", "mkntfs", "/system/bin/mkfs.ntfs", "/data/data/com.termux/files/usr/bin/mkntfs")
            )
            val ntfs3gBin = findBundledBinary(
                context,
                "libntfs_3g.so",
                listOf("ntfs-3g", "/system/bin/ntfs-3g", "/data/data/com.termux/files/usr/bin/ntfs-3g")
            )
            val msSysBin = findBundledBinary(
                context,
                "libms_sys.so",
                listOf("ms-sys", "/data/data/com.termux/files/usr/bin/ms-sys")
            )
            val procFs = Shell.cmd("cat /proc/filesystems 2>/dev/null").exec().out
                .map { it.trim().substringAfterLast('\t').substringAfterLast(' ').lowercase() }
                .filter { it.isNotEmpty() }
                .toSet()
            val kernelNtfsDriver = when {
                "ntfs3" in procFs -> "ntfs3"
                "tntfs" in procFs -> "tntfs"
                "ufsd" in procFs -> "ufsd"
                else -> null
            }
            val hasFuse = ("fuse" in procFs || "fuseblk" in procFs) && Shell.cmd("[ -e /dev/fuse ]").exec().isSuccess
            val canMountNtfs = kernelNtfsDriver != null || (ntfs3gBin != null && hasFuse)
            val useNtfs = ntfsFormatter != null && canMountNtfs

            entries.add(
                LogEntry(
                    ts,
                    "win-build",
                    "NTFS check: mkntfs=${ntfsFormatter != null}, kernel=${kernelNtfsDriver ?: "none"}, fuse=${hasFuse}, ntfs-3g=${ntfs3gBin != null}, ms-sys=${msSysBin != null}"
                )
            )

            if (!useNtfs && hasOver4GbFile) {
                val reason = if (ntfsFormatter == null) {
                    "Bundled mkfs.ntfs binary not found. Rebuild APK via GitHub Actions."
                } else {
                    "Cannot mount NTFS: kernel lacks ntfs3 and bundled ntfs-3g FUSE driver is unavailable."
                }
                entries.add(LogEntry(ts, "win-build", reason, true))
                return@withContext ActionResult(false, reason, entries = entries)
            }

            val needUefiNtfs = useNtfs && config.scheme == PartitionScheme.GPT
            if (needUefiNtfs && uefiNtfsBytes == null) {
                val reason = "Missing UEFI NTFS boot partition asset. Connect to internet once or rebuild APK."
                entries.add(LogEntry(ts, "win-build", reason, true))
                return@withContext ActionResult(false, reason, entries = entries)
            }

            onProgress(
                WindowsBuildProgress(
                    stage = "Creating ${config.scheme.label} image...",
                    detail = "${UsbGadgetEngine.formatBytes(layout.totalBytes)} (${if (useNtfs) "NTFS Boot Layout" else "FAT32"})",
                    progress = 0.12f,
                    isIndeterminate = false
                )
            )

            val allocRes = Shell.cmd(
                """
                mkdir -p $qDir && rm -f $qOut &&
                (truncate -s ${layout.totalBytes} $qOut 2>/dev/null || dd if=/dev/zero of=$qOut bs=1M count=0 seek=${layout.totalBytes / (1024L * 1024L)} 2>/dev/null)
                """.trimIndent()
            ).exec()
            if (!allocRes.isSuccess) {
                cleanup(deletePartial = true)
                return@withContext ActionResult(false, "Failed to allocate image file at $outPath.", entries = entries)
            }

            val part1IsFat32 = !useNtfs
            val includeUefiPart = useNtfs && uefiNtfsBytes != null
            val tableErr = writeTablesViaRoot(
                context = context,
                outImgPath = outPath,
                scheme = config.scheme,
                layout = layout,
                part1IsFat32 = part1IsFat32,
                uefiNtfsData = if (includeUefiPart) uefiNtfsBytes else null
            )
            if (tableErr != null) {
                entries.add(LogEntry(ts, "win-build", "Partition table write failed: $tableErr", true))
                cleanup(deletePartial = true)
                return@withContext ActionResult(false, "Failed to write partition table: $tableErr", entries = entries)
            }
            entries.add(
                LogEntry(
                    ts,
                    "win-build",
                    "Wrote ${config.scheme.label}: Part1 ${if (useNtfs) "NTFS" else "FAT32"} LBA=${layout.part1StartLba}" +
                        if (includeUefiPart) " + Part2 UEFI_NTFS LBA=${layout.part2StartLba}" else ""
                )
            )

            onProgress(WindowsBuildProgress("Formatting Partition 1...", if (useNtfs) "NTFS" else "FAT32", 0.22f, false))

            val loopCmd = """
                L=${'$'}(losetup -f -s -o ${layout.part1OffsetBytes} -S ${layout.part1SizeBytes} $qOut 2>/dev/null)
                if [ -z "${'$'}L" ]; then
                    L=${'$'}(losetup -f --show -o ${layout.part1OffsetBytes} --sizelimit ${layout.part1SizeBytes} $qOut 2>/dev/null)
                fi
                if [ -z "${'$'}L" ]; then
                    CAND=${'$'}(losetup -f 2>/dev/null)
                    if [ -n "${'$'}CAND" ]; then
                        if losetup -o ${layout.part1OffsetBytes} -S ${layout.part1SizeBytes} "${'$'}CAND" $qOut 2>/dev/null || \
                           losetup -o ${layout.part1OffsetBytes} --sizelimit ${layout.part1SizeBytes} "${'$'}CAND" $qOut 2>/dev/null; then
                            L="${'$'}CAND"
                        fi
                    fi
                fi
                echo "${'$'}L"
            """.trimIndent()
            loopDev = Shell.cmd(loopCmd).exec().out.lastOrNull().orEmpty().trim()
            if (loopDev.isBlank() || !loopDev.startsWith("/dev/")) {
                entries.add(LogEntry(ts, "win-build", "losetup failed to attach Partition 1", true))
                cleanup(deletePartial = true)
                return@withContext ActionResult(false, "Kernel loop device setup failed.", entries = entries)
            }
            val qLoop = shellQuote(loopDev)

            val fmtRes = if (useNtfs) {
                val qFmt = shellQuote(ntfsFormatter!!)
                Shell.cmd(
                    "$qFmt -F -Q -L POCKETBOOT -H 255 -S 63 -p ${layout.part1StartLba} $qLoop 2>&1"
                ).exec()
            } else {
                Shell.cmd(
                    """
                    if command -v mkfs.vfat >/dev/null 2>&1; then
                        mkfs.vfat -F 32 -n POCKETBOOT $qLoop 2>&1 || mkfs.vfat $qLoop 2>&1
                    elif command -v newfs_msdos >/dev/null 2>&1; then
                        newfs_msdos -F 32 -L POCKETBOOT $qLoop 2>&1
                    else
                        echo "No FAT32 formatter found"
                        exit 1
                    fi
                    """.trimIndent()
                ).exec()
            }
            if (!fmtRes.isSuccess) {
                val errTxt = (fmtRes.out + fmtRes.err).joinToString(" ").take(260)
                entries.add(LogEntry(ts, "win-build", "Format failed: $errTxt", true))
                cleanup(deletePartial = true)
                return@withContext ActionResult(false, "Filesystem format failed: $errTxt", entries = entries)
            }
            entries.add(LogEntry(ts, "win-build", "Formatted Partition 1 (${if (useNtfs) "NTFS" else "FAT32"}) on $loopDev"))

            if (config.scheme == PartitionScheme.MBR && msSysBin != null) {
                onProgress(WindowsBuildProgress("Writing Windows boot sector...", "Injecting Windows bootmgr VBR & MBR", 0.28f, false))
                val qMs = shellQuote(msSysBin)
                val vbrFlag = if (useNtfs) "-n" else "-8"
                val vbrRes = Shell.cmd("$qMs $vbrFlag -f $qLoop 2>&1").exec()
                if (vbrRes.isSuccess) {
                    entries.add(LogEntry(ts, "win-build", "Wrote Windows bootmgr VBR ($vbrFlag) via ms-sys"))
                } else {
                    entries.add(LogEntry(ts, "win-build", "ms-sys VBR warning: ${(vbrRes.out + vbrRes.err).joinToString(" ").take(160)}", true))
                }
            }

            val mntCmd = if (useNtfs) {
                val attempts = mutableListOf<String>()
                if (kernelNtfsDriver != null) {
                    attempts.add("mount -t $kernelNtfsDriver -o rw $qLoop $TEMP_MNT 2>&1")
                }
                if (ntfs3gBin != null && hasFuse) {
                    val qN3g = shellQuote(ntfs3gBin)
                    attempts.add("$qN3g -o rw,big_writes,noatime $qLoop $TEMP_MNT 2>&1")
                    attempts.add("$qN3g -o rw $qLoop $TEMP_MNT 2>&1")
                }
                "mkdir -p $TEMP_MNT && (${attempts.joinToString(" || ")})"
            } else {
                "mkdir -p $TEMP_MNT && (mount -t vfat -o rw $qLoop $TEMP_MNT 2>&1 || mount -o rw $qLoop $TEMP_MNT 2>&1)"
            }
            val mntRes = Shell.cmd(mntCmd).exec()
            if (mntRes.isSuccess && useNtfs) {
                val usedDriver = if (kernelNtfsDriver != null) "$kernelNtfsDriver / ntfs-3g" else "ntfs-3g (FUSE)"
                entries.add(LogEntry(ts, "win-build", "Mounted NTFS Partition 1 via $usedDriver"))
            }
            if (!mntRes.isSuccess) {
                val errTxt = (mntRes.out + mntRes.err).joinToString(" ").take(220)
                entries.add(LogEntry(ts, "win-build", "Mount Partition 1 failed: $errTxt", true))
                cleanup(deletePartial = true)
                return@withContext ActionResult(false, "Could not mount target partition: $errTxt", entries = entries)
            }

            onProgress(
                WindowsBuildProgress(
                    stage = "Extracting Windows ISO...",
                    detail = "Copying setup files (${UsbGadgetEngine.formatBytes(isoSize)}) — please keep screen on",
                    progress = 0.42f,
                    isIndeterminate = true
                )
            )

            val extractRes = if (sevenZip != null) {
                Shell.cmd("${shellQuote(sevenZip)} x -y -o$TEMP_MNT $qIso 2>&1").exec()
            } else {
                Shell.cmd(
                    """
                    mkdir -p $TEMP_ISO_MNT &&
                    (mount -t udf -o loop,ro $qIso $TEMP_ISO_MNT 2>&1 || mount -t iso9660 -o loop,ro $qIso $TEMP_ISO_MNT 2>&1 || mount -o loop,ro $qIso $TEMP_ISO_MNT 2>&1) &&
                    cp -rf $TEMP_ISO_MNT/. $TEMP_MNT/ 2>&1
                    """.trimIndent()
                ).exec()
            }
            if (!isActive) {
                cleanup(deletePartial = true)
                return@withContext ActionResult(false, "Build cancelled.", entries = entries)
            }
            if (!extractRes.isSuccess) {
                val errTxt = (extractRes.out + extractRes.err).takeLast(4).joinToString(" ").take(260)
                entries.add(LogEntry(ts, "win-build", "Extraction failed: $errTxt", true))
                cleanup(deletePartial = true)
                return@withContext ActionResult(false, "ISO extraction failed: $errTxt", entries = entries)
            }
            entries.add(LogEntry(ts, "win-build", "Extracted Windows ISO contents into Partition 1"))

            val wimBin = findBundledBinary(context, "libwimlib_imagex.so", listOf("wimlib-imagex"))
            val installWimCandidate = Shell.cmd("ls $TEMP_MNT/sources/install.wim $TEMP_MNT/sources/install.esd $TEMP_MNT/SOURCES/INSTALL.WIM $TEMP_MNT/SOURCES/INSTALL.ESD 2>/dev/null | head -n 1").exec().out.firstOrNull().orEmpty().trim()
            if (wimBin != null && installWimCandidate.isNotBlank()) {
                Shell.cmd(WindowsCustomizationEngine.applyWin7UefiFixSnippet(shellQuote(wimBin), shellQuote(installWimCandidate), TEMP_MNT)).exec()
                    .out.filter { it.startsWith("LOG|") }.forEach { l -> val p = l.split("|", limit = 3); if (p.size == 3) entries.add(LogEntry(ts, p[1], p[2])) }
            }
            if (config.experience.useCa2023Bootloaders && wimBin != null && installWimCandidate.isNotBlank()) {
                onProgress(WindowsBuildProgress("Applying CA 2023 bootloaders...", "Extracting bootmgfw_EX.efi", 0.83f, false))
                Shell.cmd(WindowsCustomizationEngine.applyCa2023BootloadersSnippet(shellQuote(wimBin), shellQuote(installWimCandidate), TEMP_MNT)).exec()
                    .out.filter { it.startsWith("LOG|") }.forEach { l -> val p = l.split("|", limit = 3); if (p.size == 3) entries.add(LogEntry(ts, p[1], p[2])) }
            }
            val anyBypass = config.experience.hasAnyActive() || config.winUtil.hasAnyActive() || config.selectedEditionIndex != null
            if (anyBypass) {
                onProgress(WindowsBuildProgress("Applying Windows tweaks...", "Writing autounattend.xml & scripts", 0.85f, false))
                val tmpXml = File(context.cacheDir, "autounattend.xml").apply { writeText(generateAutounattendXml(config), Charsets.UTF_8) }
                val oemPanther = "$TEMP_MNT/sources/\$OEM\$/\$\$/Panther"
                val oemScripts = "$TEMP_MNT/sources/\$OEM\$/\$\$/Setup/Scripts"
                val oemCustom = "$TEMP_MNT/sources/\$OEM\$/\$\$/Setup/Custom"
                val tmpSpec = File(context.cacheDir, "PocketBoot_Specialize.cmd").apply { writeText(WindowsCustomizationEngine.buildSpecializeScript(config.experience, config.winUtil), Charsets.UTF_8) }
                val customPath = config.experience.customPostInstallScriptPath.trim()
                val customFileName = if (customPath.isNotBlank()) customPath.substringAfterLast('/') else ""
                val tmpComp = File(context.cacheDir, "SetupComplete.cmd").apply { writeText(WindowsCustomizationEngine.buildSetupCompleteScript(false, customFileName.isNotBlank(), customFileName), Charsets.UTF_8) }
                val customCopyCmd = if (customPath.isNotBlank()) "mkdir -p ${shellQuote(oemCustom)} && cp -f ${shellQuote(UsbGadgetEngine.resolveDirectPath(customPath))} ${shellQuote(oemCustom)}/ 2>/dev/null || true" else "true"
                Shell.cmd("cp ${shellQuote(tmpXml.absolutePath)} $TEMP_MNT/autounattend.xml && mkdir -p ${shellQuote(oemPanther)} ${shellQuote(oemScripts)} && cp ${shellQuote(tmpXml.absolutePath)} ${shellQuote("$oemPanther/unattend.xml")} && cp ${shellQuote(tmpSpec.absolutePath)} ${shellQuote("$oemScripts/PocketBoot_Specialize.cmd")} && cp ${shellQuote(tmpComp.absolutePath)} ${shellQuote("$oemScripts/SetupComplete.cmd")} && $customCopyCmd").exec()
                tmpXml.delete(); tmpSpec.delete(); tmpComp.delete()
                entries.add(LogEntry(ts, "win-build", "Injected autounattend.xml (${config.arch}, Exp=${config.experience.activeCount()}, WinUtil=${config.winUtil.activeCount()})"))
            }
            if (config.selectedEditionIndex != null || config.eiCfgUnlock) {
                val tmpEi = File(context.cacheDir, "ei.cfg").apply { writeText(if (config.eiCfgUnlock && config.selectedEditionIndex == null) "[Channel]\r\nRetail\r\n" else "[Channel]\r\n_Default\r\n[VL]\r\n0\r\n", Charsets.UTF_8) }
                Shell.cmd("mkdir -p $TEMP_MNT/sources && cp ${shellQuote(tmpEi.absolutePath)} $TEMP_MNT/sources/ei.cfg").exec()
                tmpEi.delete()
                entries.add(LogEntry(ts, "win-build", "Wrote sources/ei.cfg"))
            }

            if (config.extraDriversDir.isNotBlank()) {
                val drvSrc = UsbGadgetEngine.resolveDirectPath(config.extraDriversDir)
                val qDrv = shellQuote(drvSrc)
                onProgress(WindowsBuildProgress("Copying Drivers / Apps...", UsbGadgetEngine.friendlyFolderName(drvSrc), 0.90f, true))
                val drvRes = Shell.cmd("if [ -d $qDrv ]; then mkdir -p $TEMP_MNT/Drivers && cp -rf $qDrv/. $TEMP_MNT/Drivers/ 2>&1; fi").exec()
                if (drvRes.isSuccess) {
                    entries.add(LogEntry(ts, "win-build", "Injected Drivers/Apps folder from $drvSrc into /Drivers"))
                } else {
                    entries.add(LogEntry(ts, "win-build", "Driver folder copy warning: ${(drvRes.out + drvRes.err).joinToString(" ").take(120)}", true))
                }
            }

            onProgress(WindowsBuildProgress("Finalizing bootable USB...", "Syncing NTFS & fixing permissions", 0.94f, false))
            cleanup(deletePartial = false)

            if (config.scheme == PartitionScheme.MBR && msSysBin != null) {
                val qMs = shellQuote(msSysBin)
                val mbrRes = Shell.cmd("$qMs -7 -f $qOut 2>&1").exec()
                if (mbrRes.isSuccess) {
                    entries.add(LogEntry(ts, "win-build", "Wrote Windows 7/10/11 MBR bootcode (-7) via ms-sys"))
                }
            }

            UsbGadgetEngine.fixUserStoragePermissions(context, safeDir, isDir = true)
            UsbGadgetEngine.fixUserStoragePermissions(context, outPath, isDir = false)

            onProgress(WindowsBuildProgress("Done!", safeName, 1f, false))
            entries.add(LogEntry(ts, "win-build", "Built Windows USB image: $outPath"))

            ActionResult(
                success = true,
                userMessage = "Built $safeName (${config.scheme.label}, ${if (useNtfs) "NTFS" else "FAT32"}).",
                createdPath = outPath,
                entries = entries
            )
        }.getOrElse { e ->
            cleanup(deletePartial = true)
            entries.add(LogEntry(ts, "win-build", "Build error: ${e.message}", true))
            ActionResult(false, "Build failed: ${e.message ?: "Unexpected error"}", entries = entries)
        }
    }

    private fun writeTablesViaRoot(
        context: Context,
        outImgPath: String,
        scheme: PartitionScheme,
        layout: DiskLayoutSpec,
        part1IsFat32: Boolean,
        uefiNtfsData: ByteArray?
    ): String? {
        val qOut = shellQuote(outImgPath)
        val includeUefiPart = uefiNtfsData != null && uefiNtfsData.isNotEmpty()

        if (scheme == PartitionScheme.MBR) {
            val mbrBytes = DiskPartitioner.buildMbrSector(
                layout = layout,
                part1IsFat32 = part1IsFat32,
                includeUefiPart = includeUefiPart
            )
            val fMbr = File(context.cacheDir, "pb_mbr.bin").apply { writeBytes(mbrBytes) }
            try {
                val res = Shell.cmd("dd if=${shellQuote(fMbr.absolutePath)} of=$qOut bs=512 seek=0 count=1 conv=notrunc 2>&1").exec()
                if (!res.isSuccess) return (res.out + res.err).joinToString(" ")
            } finally {
                fMbr.delete()
            }
        } else {
            val gpt = DiskPartitioner.buildGptBuffers(
                layout = layout,
                includeUefiPart = includeUefiPart
            )
            val fPrimary = File(context.cacheDir, "pb_gpt_prim.bin").apply { writeBytes(gpt.primarySectors) }
            val fBackup = File(context.cacheDir, "pb_gpt_back.bin").apply { writeBytes(gpt.backupSectors) }
            try {
                val res = Shell.cmd(
                    "dd if=${shellQuote(fPrimary.absolutePath)} of=$qOut bs=512 seek=0 count=34 conv=notrunc 2>&1 && " +
                        "dd if=${shellQuote(fBackup.absolutePath)} of=$qOut bs=512 seek=${layout.totalSectors - 33L} count=33 conv=notrunc 2>&1"
                ).exec()
                if (!res.isSuccess) return (res.out + res.err).joinToString(" ")
            } finally {
                fPrimary.delete()
                fBackup.delete()
            }
        }

        if (uefiNtfsData != null && uefiNtfsData.isNotEmpty()) {
            val fUefi = File(context.cacheDir, "pb_uefi_part.bin").apply { writeBytes(uefiNtfsData) }
            try {
                val res = Shell.cmd(
                    "dd if=${shellQuote(fUefi.absolutePath)} of=$qOut bs=512 seek=${layout.part2StartLba} conv=notrunc 2>&1"
                ).exec()
                if (!res.isSuccess) return (res.out + res.err).joinToString(" ")
            } finally {
                fUefi.delete()
            }
        }
        return null
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WindowsBuilderSheet(
    isoFile: ImageFile, defaultDir: String, presets: List<AutounattendPreset>, building: Boolean, progress: WindowsBuildProgress,
    onDismiss: () -> Unit, onCancelBuild: () -> Unit, onSavePreset: (AutounattendPreset) -> Unit, onDeletePreset: (String) -> Unit, onStartBuild: (WindowsBuildConfig) -> Unit
) {
    val context = LocalContext.current
    val detectedOs = remember(isoFile.name) { WindowsCustomizationEngine.detectWindowsOsKind(isoFile.name) }
    val detectedArch = remember(isoFile.name) { WindowsCustomizationEngine.detectProcessorArchitecture(isoFile.name) }
    val defaultScheme = remember(detectedOs, detectedArch) { if (detectedOs == WindowsOsKind.WIN7 || detectedArch == "x86") PartitionScheme.MBR else PartitionScheme.GPT }
    val baseName = remember(isoFile.name) { isoFile.name.substringBeforeLast('.').take(36) + "-USB.img" }
    var outputName by remember(isoFile.path) { mutableStateOf(baseName) }
    var scheme by remember(isoFile.path) { mutableStateOf(defaultScheme) }
    var editions by remember(isoFile.path) { mutableStateOf<List<WindowsEditionItem>>(emptyList()) }
    var inspectingEditions by remember(isoFile.path) { mutableStateOf(true) }
    var selectedEditionIndex by remember(isoFile.path) { mutableStateOf<Int?>(null) }
    var experience by remember(isoFile.path) { mutableStateOf(WindowsExperienceConfig.defaultForOs(detectedOs)) }
    var winUtil by remember(isoFile.path) { mutableStateOf(WinUtilTweaksConfig()) }
    var eiCfgUnlock by remember(isoFile.path) { mutableStateOf(false) }
    var extraDriversDir by remember(isoFile.path) { mutableStateOf("") }
    var showDriversFolderPicker by remember { mutableStateOf(false) }
    var autoMount by remember { mutableStateOf(true) }

    LaunchedEffect(isoFile.path) { inspectingEditions = true; editions = WindowsImageBuilder.inspectWindowsEditions(context, isoFile.path); inspectingEditions = false }
    val estNeeded = isoFile.sizeBytes + (512L * 1024L * 1024L)
    val freeSpace = remember(defaultDir) { UsbGadgetEngine.getFreeSpaceBytes(defaultDir) }
    val lowSpace = freeSpace in 1 until estNeeded

    ModalBottomSheet(onDismissRequest = { if (!building) onDismiss() }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = PocketColors.surfaceCard) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(13.dp)) {
            PocketSheetHeader(icon = Icons.Rounded.Window, tint = PocketColors.infoBlue, iconBg = PocketColors.infoBlueBg, title = "Windows USB Builder", subtitle = "${detectedOs.label} ($detectedArch) · ${UsbGadgetEngine.formatBytes(isoFile.sizeBytes)}", subtitleMono = true)
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
                    Text("Partition Scheme", style = MaterialTheme.typography.labelLarge, color = PocketColors.textPrimary)
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PartitionScheme.entries.forEach { s ->
                            val selected = scheme == s
                            Surface(modifier = Modifier.weight(1f).bounceClick { scheme = s }, shape = RoundedCornerShape(12.dp), color = if (selected) PocketColors.accentBg else PocketColors.surfaceInput, border = BorderStroke(if (selected) 1.5.dp else 0.75.dp, if (selected) PocketColors.accent else PocketColors.border)) {
                                Column(modifier = Modifier.padding(11.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text(s.label, style = MaterialTheme.typography.labelLarge, color = if (selected) PocketColors.accentSoft else PocketColors.textPrimary)
                                    Text(s.subtitle, fontSize = 10.5.sp, color = PocketColors.textSecondary)
                                }
                            }
                        }
                    }
                }
                if (inspectingEditions) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp, color = PocketColors.accent); Text("Inspecting Windows editions...", style = MaterialTheme.typography.bodySmall, color = PocketColors.textSecondary)
                    }
                } else if (editions.size >= 2) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Pre-Select Edition (Optional)", style = MaterialTheme.typography.labelLarge, color = PocketColors.textPrimary)
                        Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Surface(modifier = Modifier.bounceClick { selectedEditionIndex = null }, shape = RoundedCornerShape(9.dp), color = if (selectedEditionIndex == null) PocketColors.accentBg else PocketColors.surfaceInput, border = BorderStroke(0.75.dp, if (selectedEditionIndex == null) PocketColors.accent else PocketColors.border)) {
                                Text("Ask during Setup", modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp), style = MaterialTheme.typography.labelSmall, color = if (selectedEditionIndex == null) PocketColors.accentSoft else PocketColors.textSecondary)
                            }
                            editions.forEach { ed ->
                                val sel = selectedEditionIndex == ed.index
                                Surface(modifier = Modifier.bounceClick { selectedEditionIndex = ed.index }, shape = RoundedCornerShape(9.dp), color = if (sel) PocketColors.accentBg else PocketColors.surfaceInput, border = BorderStroke(0.75.dp, if (sel) PocketColors.accent else PocketColors.border)) {
                                    Text(ed.name, modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp), style = MaterialTheme.typography.labelSmall, color = if (sel) PocketColors.accentSoft else PocketColors.textPrimary)
                                }
                            }
                        }
                    }
                }
                WindowsTwoPaneCustomizer(
                    osKind = detectedOs, arch = detectedArch, experience = experience, winUtil = winUtil, presets = presets,
                    showCa2023Option = true, showCustomScriptOption = true, defaultBrowseDir = defaultDir,
                    onExperienceChange = { experience = it }, onWinUtilChange = { winUtil = it },
                    onApplyPreset = { p ->
                        scheme = runCatching { PartitionScheme.valueOf(p.schemeName) }.getOrDefault(scheme)
                        experience = WindowsExperienceConfig(p.bypassRequirements, p.bypassMicrosoftAccount, p.disableDataCollection, p.disableBitLocker, p.qolImprovements, p.disableSponsoredApps, p.classicContextMenu, p.skipWifiSetup, p.createLocalUser, p.localUsername, "", p.skipOobe, p.useCa2023Bootloaders, p.applySkuSiPolicy)
                        winUtil = WinUtilTweaksConfig.fromJsonString(p.winUtilJson)
                    },
                    onSavePreset = { n -> onSavePreset(AutounattendPreset(n, scheme.name, experience.bypassRequirements, experience.bypassMicrosoftAccount, experience.disableDataCollection, experience.disableBitLocker, experience.disableSponsoredApps, experience.classicContextMenu, experience.skipWifiSetup, experience.createLocalUser, experience.localUsername, experience.skipOobe, experience.qolImprovements, experience.useCa2023Bootloaders, experience.applySkuSiPolicy, winUtil.toJsonString())) },
                    onDeletePreset = onDeletePreset
                )
                Surface(shape = RoundedCornerShape(12.dp), color = PocketColors.surfaceInput, border = BorderStroke(0.5.dp, if (eiCfgUnlock) PocketColors.accent.copy(alpha = 0.55f) else PocketColors.border)) {
                    Row(modifier = Modifier.fillMaxWidth().clickable { eiCfgUnlock = !eiCfgUnlock }.padding(horizontal = 13.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Unlock All Editions (ei.cfg)", style = MaterialTheme.typography.labelMedium, color = PocketColors.textPrimary)
                            Text("Lets you choose Pro/Edu even on OEM-keyed laptops", fontSize = 11.sp, color = PocketColors.textSecondary)
                        }
                        PocketSwitch(checked = eiCfgUnlock, onCheckedChange = { eiCfgUnlock = it }, activeColor = PocketColors.accent)
                    }
                }
                Surface(modifier = Modifier.fillMaxWidth().bounceClick { showDriversFolderPicker = true }, shape = RoundedCornerShape(12.dp), color = PocketColors.surfaceInput, border = BorderStroke(0.75.dp, if (extraDriversDir.isNotBlank()) PocketColors.accent else PocketColors.border)) {
                    Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 13.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Icon(Icons.Rounded.FolderOpen, null, tint = PocketColors.accentSoft, modifier = Modifier.size(19.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Include Driver / Apps Folder (/Drivers)", style = MaterialTheme.typography.labelMedium, color = PocketColors.textPrimary)
                            Text(if (extraDriversDir.isNotBlank()) extraDriversDir.replace("/data/media/0", "/sdcard") else "Tap to browse and select a folder (Intel RST, Wi-Fi, etc.)", fontSize = 11.sp, color = if (extraDriversDir.isNotBlank()) PocketColors.accentSoft else PocketColors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        if (extraDriversDir.isNotBlank()) { IconButton(onClick = { extraDriversDir = "" }, modifier = Modifier.size(28.dp)) { Icon(Icons.Rounded.Close, "Clear folder", tint = PocketColors.textMuted, modifier = Modifier.size(15.dp)) } }
                        else Text("Browse", style = MaterialTheme.typography.labelSmall, color = PocketColors.accentSoft)
                    }
                }
                OutlinedTextField(value = outputName, onValueChange = { outputName = it }, label = { Text("Output Bootable USB Name") }, singleLine = true, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = PocketColors.accent, unfocusedBorderColor = PocketColors.border, focusedTextColor = PocketColors.textPrimary, unfocusedTextColor = PocketColors.textPrimary))
                Surface(shape = RoundedCornerShape(12.dp), color = PocketColors.surfaceInput, border = BorderStroke(0.5.dp, PocketColors.border)) {
                    Row(modifier = Modifier.fillMaxWidth().clickable { autoMount = !autoMount }.padding(horizontal = 13.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Auto-mount when finished", style = MaterialTheme.typography.labelMedium, color = PocketColors.textPrimary)
                            Text("Mounts immediately as Read-Only USB", fontSize = 11.sp, color = PocketColors.textSecondary)
                        }
                        PocketSwitch(checked = autoMount, onCheckedChange = { autoMount = it }, activeColor = PocketColors.accent)
                    }
                }
                if (lowSpace) {
                    Surface(shape = RoundedCornerShape(10.dp), color = PocketColors.warningBg, border = BorderStroke(0.5.dp, PocketColors.warning)) {
                        Row(modifier = Modifier.fillMaxWidth().padding(10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.WarningAmber, null, tint = PocketColors.warning, modifier = Modifier.size(16.dp))
                            Text("Need ~${UsbGadgetEngine.formatBytes(estNeeded)}, have ${UsbGadgetEngine.formatBytes(freeSpace)} free.", style = MaterialTheme.typography.bodySmall, color = PocketColors.textPrimary)
                        }
                    }
                }
                Button(onClick = { onStartBuild(WindowsBuildConfig(isoFile, defaultDir, outputName.trim().ifBlank { baseName }, scheme, selectedEditionIndex, editions.firstOrNull { it.index == selectedEditionIndex }?.name, detectedOs, detectedArch, experience, winUtil, eiCfgUnlock, extraDriversDir = extraDriversDir, autoMountAfterBuild = autoMount)) }, enabled = outputName.trim().isNotEmpty() && !lowSpace, modifier = Modifier.fillMaxWidth().height(48.dp), shape = RoundedCornerShape(12.dp), colors = ButtonDefaults.buttonColors(containerColor = PocketColors.accent, contentColor = PocketColors.onAccent)) {
                    Text("Build Bootable Windows USB (.img)", fontWeight = FontWeight.SemiBold)
                }
            }
            Spacer(modifier = Modifier.height(22.dp))
        }
    }
    if (showDriversFolderPicker) RootFolderPickerSheet("Select Drivers / Apps Folder", "Files inside will be copied to /Drivers on the Windows USB", extraDriversDir.ifBlank { defaultDir }, { showDriversFolderPicker = false }) { extraDriversDir = it; showDriversFolderPicker = false }
}
