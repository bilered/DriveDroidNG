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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
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

enum class LinuxPersistenceStyle(val title: String, val volumeLabel: String, val subtitle: String) {
    CASPER_RW("Ubuntu / Mint / Pop!_OS", "casper-rw", "ext4 partition labeled casper-rw"),
    DEBIAN_KALI("Debian / Kali / Tails", "persistence", "ext4 labeled persistence + persistence.conf")
}

data class LinuxBuildConfig(
    val isoFile: ImageFile,
    val outputDir: String,
    val outputName: String,
    val persistenceGb: Int = 2,
    val style: LinuxPersistenceStyle = LinuxPersistenceStyle.CASPER_RW,
    val autoMountAfterBuild: Boolean = true
)

object LinuxImageBuilder {

    private const val TEMP_LINUX_MNT = "/data/local/tmp/pb_linux_persist"

    private fun nowTs(): String = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())

    private fun shellQuote(value: String): String = "'${value.replace("'", "'\\''")}'"

    suspend fun buildLinuxPersistentImage(
        context: Context,
        config: LinuxBuildConfig,
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
                umount $TEMP_LINUX_MNT 2>/dev/null || umount -l $TEMP_LINUX_MNT 2>/dev/null
                [ -n ${shellQuote(loopDev)} ] && losetup -d ${shellQuote(loopDev)} 2>/dev/null
                rm -rf $TEMP_LINUX_MNT 2>/dev/null
                """.trimIndent()
            ).exec()
            if (deletePartial) {
                Shell.cmd("rm -f $qOut 2>/dev/null").exec()
            }
        }

        return@withContext runCatching {
            if (!Shell.getShell().isRoot) {
                return@withContext ActionResult(false, "Root access is required.", entries = entries)
            }

            onProgress(WindowsBuildProgress("Checking Linux ISO...", config.isoFile.name, 0.05f, true))

            val isoSize = Shell.cmd("stat -c '%s' $qIso 2>/dev/null")
                .exec().out.firstOrNull()?.trim()?.toLongOrNull() ?: config.isoFile.sizeBytes
            if (isoSize <= 0L) {
                return@withContext ActionResult(false, "Source Linux ISO not found or empty.", entries = entries)
            }

            val persistBytes = config.persistenceGb.coerceIn(1, 32).toLong() * 1024L * 1024L * 1024L
            val alignBytes = 1024L * 1024L
            val persistOffsetBytes = ((isoSize + alignBytes - 1L) / alignBytes) * alignBytes
            val totalBytes = persistOffsetBytes + persistBytes
            val persistStartLba = persistOffsetBytes / 512L
            val persistSectors = persistBytes / 512L

            val freeSpace = UsbGadgetEngine.getFreeSpaceBytes(safeDir)
            if (freeSpace > 0L && freeSpace < totalBytes + (64L * 1024L * 1024L)) {
                val msg = "Not enough free space. Need ${UsbGadgetEngine.formatBytes(totalBytes)}, have ${UsbGadgetEngine.formatBytes(freeSpace)}."
                entries.add(LogEntry(ts, "linux-build", msg, true))
                return@withContext ActionResult(false, msg, entries = entries)
            }

            onProgress(
                WindowsBuildProgress(
                    stage = "Copying Linux ISO...",
                    detail = "${UsbGadgetEngine.formatBytes(isoSize)} + ${config.persistenceGb} GB ${config.style.volumeLabel}",
                    progress = 0.28f,
                    isIndeterminate = true
                )
            )

            val cpRes = Shell.cmd("mkdir -p $qDir && rm -f $qOut && cp -f $qIso $qOut 2>&1").exec()
            if (!isActive) {
                cleanup(deletePartial = true)
                return@withContext ActionResult(false, "Build cancelled.", entries = entries)
            }
            if (!cpRes.isSuccess) {
                val err = (cpRes.out + cpRes.err).joinToString(" ").take(200)
                cleanup(deletePartial = true)
                return@withContext ActionResult(false, "Failed to copy ISO: $err", entries = entries)
            }

            val expandRes = Shell.cmd(
                "truncate -s $totalBytes $qOut 2>&1 || dd if=/dev/zero of=$qOut bs=1M count=0 seek=${totalBytes / (1024L * 1024L)} 2>&1"
            ).exec()
            if (!expandRes.isSuccess) {
                cleanup(deletePartial = true)
                return@withContext ActionResult(false, "Failed to expand image for persistence.", entries = entries)
            }

            onProgress(WindowsBuildProgress("Adding persistence partition...", "Updating MBR partition table", 0.58f, false))

            val tmpMbrIn = File(context.cacheDir, "pb_linux_mbr_in.bin")
            val tmpMbrOut = File(context.cacheDir, "pb_linux_mbr_out.bin")
            try {
                Shell.cmd("dd if=$qOut of=${shellQuote(tmpMbrIn.absolutePath)} bs=512 count=1 2>/dev/null").exec()
                val origMbr = if (tmpMbrIn.exists()) tmpMbrIn.readBytes() else ByteArray(0)
                val patchedMbr = DiskPartitioner.appendLinuxPersistenceMbr(origMbr, persistStartLba, persistSectors)
                if (patchedMbr == null) {
                    entries.add(LogEntry(ts, "linux-build", "All 4 MBR slots occupied or non-hybrid ISO header", true))
                    cleanup(deletePartial = true)
                    return@withContext ActionResult(false, "Could not append persistence partition (MBR slots full).", entries = entries)
                }
                tmpMbrOut.writeBytes(patchedMbr)
                Shell.cmd("dd if=${shellQuote(tmpMbrOut.absolutePath)} of=$qOut bs=512 count=1 conv=notrunc 2>/dev/null").exec()
            } finally {
                tmpMbrIn.delete()
                tmpMbrOut.delete()
            }
            entries.add(LogEntry(ts, "linux-build", "Appended ext4 partition at LBA=$persistStartLba (${config.persistenceGb} GB)"))

            onProgress(WindowsBuildProgress("Formatting ext4 persistence...", "Label: ${config.style.volumeLabel}", 0.74f, false))

            val loopCmd = """
                L=${'$'}(losetup -f -s -o $persistOffsetBytes -S $persistBytes $qOut 2>/dev/null)
                if [ -z "${'$'}L" ]; then
                    L=${'$'}(losetup -f --show -o $persistOffsetBytes --sizelimit $persistBytes $qOut 2>/dev/null)
                fi
                if [ -z "${'$'}L" ]; then
                    CAND=${'$'}(losetup -f 2>/dev/null)
                    if [ -n "${'$'}CAND" ]; then
                        if losetup -o $persistOffsetBytes -S $persistBytes "${'$'}CAND" $qOut 2>/dev/null || \
                           losetup -o $persistOffsetBytes --sizelimit $persistBytes "${'$'}CAND" $qOut 2>/dev/null; then
                            L="${'$'}CAND"
                        fi
                    fi
                fi
                echo "${'$'}L"
            """.trimIndent()
            loopDev = Shell.cmd(loopCmd).exec().out.lastOrNull().orEmpty().trim()
            if (loopDev.isBlank() || !loopDev.startsWith("/dev/")) {
                cleanup(deletePartial = true)
                return@withContext ActionResult(false, "Could not attach loop device for persistence partition.", entries = entries)
            }

            val qLoop = shellQuote(loopDev)
            val label = config.style.volumeLabel
            val ext4Res = Shell.cmd(
                """
                if command -v mke2fs >/dev/null 2>&1; then
                    mke2fs -t ext4 -F -L $label $qLoop 2>&1
                elif command -v mkfs.ext4 >/dev/null 2>&1; then
                    mkfs.ext4 -F -L $label $qLoop 2>&1
                elif [ -x /system/bin/mke2fs ]; then
                    /system/bin/mke2fs -t ext4 -F -L $label $qLoop 2>&1
                else
                    echo "No mke2fs/mkfs.ext4 binary found on device"
                    exit 1
                fi
                """.trimIndent()
            ).exec()
            if (!ext4Res.isSuccess) {
                val err = (ext4Res.out + ext4Res.err).joinToString(" ").take(220)
                entries.add(LogEntry(ts, "linux-build", "ext4 format failed: $err", true))
                cleanup(deletePartial = true)
                return@withContext ActionResult(false, "ext4 format failed: $err", entries = entries)
            }

            if (config.style == LinuxPersistenceStyle.DEBIAN_KALI) {
                onProgress(WindowsBuildProgress("Writing persistence.conf...", "/ union", 0.88f, false))
                val confRes = Shell.cmd(
                    """
                    mkdir -p $TEMP_LINUX_MNT &&
                    mount -t ext4 -o rw $qLoop $TEMP_LINUX_MNT 2>&1 &&
                    printf '/ union\n' > $TEMP_LINUX_MNT/persistence.conf
                    """.trimIndent()
                ).exec()
                if (confRes.isSuccess) {
                    entries.add(LogEntry(ts, "linux-build", "Created /persistence.conf (/ union)"))
                }
            }

            onProgress(WindowsBuildProgress("Finalizing Linux USB...", "Syncing & fixing permissions", 0.95f, false))
            cleanup(deletePartial = false)

            UsbGadgetEngine.fixUserStoragePermissions(context, safeDir, isDir = true)
            UsbGadgetEngine.fixUserStoragePermissions(context, outPath, isDir = false)

            onProgress(WindowsBuildProgress("Done!", safeName, 1f, false))
            entries.add(LogEntry(ts, "linux-build", "Built Persistent Linux USB: $outPath"))

            ActionResult(
                success = true,
                userMessage = "Built $safeName with ${config.persistenceGb} GB ${config.style.volumeLabel}.",
                createdPath = outPath,
                entries = entries
            )
        }.getOrElse { e ->
            cleanup(deletePartial = true)
            entries.add(LogEntry(ts, "linux-build", "Build error: ${e.message}", true))
            ActionResult(false, "Linux build failed: ${e.message ?: "Unexpected error"}", entries = entries)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LinuxBuilderSheet(
    isoFile: ImageFile,
    defaultDir: String,
    building: Boolean,
    progress: WindowsBuildProgress,
    onDismiss: () -> Unit,
    onCancelBuild: () -> Unit,
    onStartBuild: (LinuxBuildConfig) -> Unit
) {
    val defaultStyle = remember(isoFile.name) {
        val low = isoFile.name.lowercase()
        if (low.contains("kali") || low.contains("debian") || low.contains("tails") || low.contains("parrot")) {
            LinuxPersistenceStyle.DEBIAN_KALI
        } else {
            LinuxPersistenceStyle.CASPER_RW
        }
    }
    val baseName = remember(isoFile.name) {
        isoFile.name.substringBeforeLast('.').take(34) + "-Persist.img"
    }
    var outputName by remember(isoFile.path) { mutableStateOf(baseName) }
    var style by remember(isoFile.path) { mutableStateOf(defaultStyle) }
    var persistGb by remember { mutableIntStateOf(2) }
    var autoMount by remember { mutableStateOf(true) }

    val estNeeded = isoFile.sizeBytes + (persistGb.toLong() * 1024L * 1024L * 1024L) + (8L * 1024L * 1024L)
    val freeSpace = remember(defaultDir) { UsbGadgetEngine.getFreeSpaceBytes(defaultDir) }
    val lowSpace = freeSpace in 1 until estNeeded

    ModalBottomSheet(
        onDismissRequest = { if (!building) onDismiss() },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = PocketColors.surfaceCard
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
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
                        .background(PocketColors.successBg),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Rounded.Terminal, null, tint = PocketColors.success, modifier = Modifier.size(22.dp))
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text("Linux Persistent USB", style = MaterialTheme.typography.titleLarge, color = PocketColors.textPrimary)
                    Text(
                        text = "${isoFile.name} • ${UsbGadgetEngine.formatBytes(isoFile.sizeBytes)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = PocketColors.textSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            if (building) {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = PocketColors.surfaceInput,
                    border = BorderStroke(1.dp, PocketColors.success)
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
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(7.dp)
                                    .clip(RoundedCornerShape(999.dp)),
                                color = PocketColors.success,
                                trackColor = PocketColors.surfaceSubtle
                            )
                        } else {
                            LinearProgressIndicator(
                                progress = { progress.progress.coerceIn(0f, 1f) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(7.dp)
                                    .clip(RoundedCornerShape(999.dp)),
                                color = PocketColors.success,
                                trackColor = PocketColors.surfaceSubtle
                            )
                        }
                    }
                }

                OutlinedButton(
                    onClick = onCancelBuild,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, PocketColors.danger)
                ) {
                    Icon(Icons.Rounded.Close, null, tint = PocketColors.danger, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Cancel Build", color = PocketColors.danger, fontWeight = FontWeight.SemiBold)
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Distro Persistence Format", style = MaterialTheme.typography.labelLarge, color = PocketColors.textPrimary)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        LinuxPersistenceStyle.entries.forEach { s ->
                            val selected = style == s
                            Surface(
                                modifier = Modifier
                                    .weight(1f)
                                    .bounceClick { style = s },
                                shape = RoundedCornerShape(12.dp),
                                color = if (selected) PocketColors.successBg else PocketColors.surfaceInput,
                                border = BorderStroke(if (selected) 1.5.dp else 1.dp, if (selected) PocketColors.success else PocketColors.border)
                            ) {
                                Column(
                                    modifier = Modifier.padding(12.dp),
                                    verticalArrangement = Arrangement.spacedBy(2.dp)
                                ) {
                                    Text(
                                        text = s.title,
                                        style = MaterialTheme.typography.labelLarge,
                                        color = if (selected) PocketColors.success else PocketColors.textPrimary
                                    )
                                    Text(s.subtitle, fontSize = 11.sp, color = PocketColors.textSecondary)
                                }
                            }
                        }
                    }
                }

                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Persistent Storage Size (ext4)", style = MaterialTheme.typography.labelLarge, color = PocketColors.textPrimary)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        listOf(1, 2, 4, 8).forEach { gb ->
                            val selected = persistGb == gb
                            Surface(
                                modifier = Modifier
                                    .weight(1f)
                                    .bounceClick { persistGb = gb },
                                shape = RoundedCornerShape(10.dp),
                                color = if (selected) PocketColors.accentBg else PocketColors.surfaceInput,
                                border = BorderStroke(if (selected) 1.5.dp else 1.dp, if (selected) PocketColors.accent else PocketColors.border)
                            ) {
                                Box(
                                    modifier = Modifier.padding(vertical = 10.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = "$gb GB",
                                        fontWeight = FontWeight.SemiBold,
                                        color = if (selected) PocketColors.accentSoft else PocketColors.textPrimary
                                    )
                                }
                            }
                        }
                    }
                }

                OutlinedTextField(
                    value = outputName,
                    onValueChange = { outputName = it },
                    label = { Text("Output Persistent USB Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = PocketColors.success,
                        unfocusedBorderColor = PocketColors.border,
                        focusedTextColor = PocketColors.textPrimary,
                        unfocusedTextColor = PocketColors.textPrimary
                    )
                )

                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = PocketColors.surfaceInput,
                    border = BorderStroke(0.5.dp, PocketColors.border)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { autoMount = !autoMount }
                            .padding(horizontal = 14.dp, vertical = 11.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text("Mount as Writable USB when done", style = MaterialTheme.typography.labelMedium, color = PocketColors.textPrimary)
                            Text("Writable USB lets Linux save changes", fontSize = 11.sp, color = PocketColors.textSecondary)
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        PocketSwitch(checked = autoMount, onCheckedChange = { autoMount = it }, activeColor = PocketColors.success)
                    }
                }

                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = if (lowSpace) PocketColors.dangerBg else PocketColors.accentBg,
                    border = BorderStroke(1.dp, if (lowSpace) PocketColors.danger else PocketColors.border)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = if (lowSpace) Icons.Rounded.WarningAmber else Icons.Rounded.Info,
                            contentDescription = null,
                            tint = if (lowSpace) PocketColors.danger else PocketColors.accentSoft,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            text = "Needs ~${UsbGadgetEngine.formatBytes(estNeeded)} in ${UsbGadgetEngine.friendlyFolderName(defaultDir)} (${UsbGadgetEngine.formatBytes(freeSpace)} free). Select Persistent boot option in GRUB/Live menu.",
                            style = MaterialTheme.typography.bodySmall,
                            color = PocketColors.textPrimary
                        )
                    }
                }

                Button(
                    onClick = {
                        onStartBuild(
                            LinuxBuildConfig(
                                isoFile = isoFile,
                                outputDir = defaultDir,
                                outputName = outputName.ifBlank { baseName },
                                persistenceGb = persistGb,
                                style = style,
                                autoMountAfterBuild = autoMount
                            )
                        )
                    },
                    enabled = !lowSpace && outputName.isNotBlank(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(PocketTokens.PrimaryButtonHeight),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = PocketColors.success,
                        contentColor = PocketColors.onAccent
                    )
                ) {
                    Text("Build Persistent USB", fontWeight = FontWeight.SemiBold)
                }
            }

            Spacer(modifier = Modifier.height(22.dp))
        }
    }
}
