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
import androidx.compose.material.icons.rounded.CheckBox
import androidx.compose.material.icons.rounded.CheckBoxOutlineBlank
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Compress
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.mutableStateListOf
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

data class IsoSlimConfig(
    val sourceIso: ImageFile,
    val outputDir: String,
    val outputName: String,
    val keepEditionIndices: List<Int>,
    val convertToEsd: Boolean,
    val arch: String = "amd64",
    val eiCfgUnlock: Boolean = false,
    val experience: WindowsExperienceConfig = WindowsExperienceConfig(),
    val winUtil: WinUtilTweaksConfig = WinUtilTweaksConfig()
)

object WindowsIsoSlimmer {

    private fun shellQuote(value: String): String = "'${value.replace("'", "'\\''")}'"

    suspend fun slimWindowsIso(
        context: Context,
        config: IsoSlimConfig,
        onProgress: (WindowsBuildProgress) -> Unit
    ): ActionResult = withContext(Dispatchers.IO) {
        val sevenZip = WindowsImageBuilder.findBundledBinary(context, "lib7zzs.so", listOf("7zzs", "7zz", "7z"))
            ?: return@withContext ActionResult(false, "Bundled 7-Zip extractor not found.")
        val wimlib = WindowsImageBuilder.findBundledBinary(context, "libwimlib_imagex.so", listOf("wimlib-imagex"))
            ?: return@withContext ActionResult(false, "Bundled wimlib-imagex not found.")
        val genisoimage = WindowsImageBuilder.findBundledBinary(context, "libgenisoimage.so", listOf("genisoimage", "mkisofs"))
        val xorriso = WindowsImageBuilder.findBundledBinary(context, "libxorriso.so", listOf("xorriso"))
        if (genisoimage == null && xorriso == null) {
            return@withContext ActionResult(false, "Bundled ISO packer (genisoimage/xorriso) not found.")
        }

        val srcPath = UsbGadgetEngine.resolveDirectPath(config.sourceIso.path)
        val outDir = UsbGadgetEngine.resolveDirectPath(config.outputDir).trimEnd('/')
        val outName = config.outputName.trim().let { if (it.endsWith(".iso", true)) it else "$it.iso" }
        val outPath = "$outDir/$outName"

        val workRoot = UsbGadgetEngine.createSafeTempWorkDir(context, outDir, "slim_${System.currentTimeMillis() % 100000}")
        val isoDir = "$workRoot/ISODIR"
        val tmpDir = "$workRoot/TMP"
        val qIso = shellQuote(srcPath)
        val qIsoDir = shellQuote(isoDir)
        val qTmp = shellQuote(tmpDir)
        val qOut = shellQuote(outPath)
        val q7z = shellQuote(sevenZip)
        val qWim = shellQuote(wimlib)
        val qGenIso = genisoimage?.let { shellQuote(it) }
        val qXor = xorriso?.let { shellQuote(it) }
        val envPrefix = "export TMPDIR=$qTmp HOME=$qTmp WIMLIB_IMAGEX_IGNORE_CASE=1"

        return@withContext runCatching {
            onProgress(WindowsBuildProgress("Extracting source Windows ISO...", config.sourceIso.name, 0.14f, true))
            Shell.cmd("mkdir -p $qIsoDir $qTmp").exec()
            val slimProf = WindowsBuildEngine.currentDefaultThermalProfile()
            val mmtArg = if (slimProf.sevenZipThreads > 0) "-mmt=${slimProf.sevenZipThreads}" else ""
            val extRes = WindowsBuildEngine.runIsolatedRootScript(context, "slim_ext", "set -e\n$envPrefix\n${WindowsBuildEngine.thermalExecPrefix(slimProf)}\n\$THERM_PFX $q7z x -y $mmtArg -o$qIsoDir $qIso")
            if (!isActive) {
                UsbGadgetEngine.cleanupTempWorkDir(workRoot)
                return@withContext ActionResult(false, "ISO slimming cancelled.")
            }
            if (!extRes.isSuccess) {
                throw IllegalStateException("Extraction failed: ${(extRes.out + extRes.err).takeLast(3).joinToString(" ").take(180)}")
            }

            val srcWim = Shell.cmd(
                "find $qIsoDir/sources -maxdepth 1 -type f \\( -iname 'install.wim' -o -iname 'install.esd' \\) 2>/dev/null | head -n 1"
            ).exec().out.firstOrNull().orEmpty().trim()
            if (srcWim.isBlank()) {
                throw IllegalStateException("No sources/install.wim or install.esd found inside ISO.")
            }

            val dstExt = if (config.convertToEsd) "esd" else "wim"
            val wimThrArg = WindowsBuildEngine.wimThreadArgFor(slimProf, isSolidEsd = config.convertToEsd)
            val compArg = if (config.convertToEsd) "--compress=LZMS --solid $wimThrArg" else "--compress=maximum $wimThrArg"
            val newInstall = "$tmpDir/install.$dstExt"
            val qSrcWim = shellQuote(srcWim)
            val qNewInstall = shellQuote(newInstall)

            config.keepEditionIndices.forEachIndexed { pos, edIdx ->
                if (!isActive) {
                    UsbGadgetEngine.cleanupTempWorkDir(workRoot)
                    return@withContext ActionResult(false, "ISO slimming cancelled.")
                }
                val pct = 0.35f + (pos.toFloat() / config.keepEditionIndices.size.coerceAtLeast(1).toFloat()) * 0.42f
                onProgress(
                    WindowsBuildProgress(
                        stage = "Exporting edition #$edIdx (${pos + 1}/${config.keepEditionIndices.size})...",
                        detail = if (config.convertToEsd) "Solid ESD compression (smallest size)" else "Standard WIM compression",
                        progress = pct,
                        isIndeterminate = false
                    )
                )
                val expRes = WindowsBuildEngine.runIsolatedRootScript(context, "slim_exp", "set -e\n$envPrefix\n${WindowsBuildEngine.thermalExecPrefix(slimProf)}\n\$THERM_PFX $qWim export $qSrcWim $edIdx $qNewInstall $compArg")
                if (!expRes.isSuccess) {
                    throw IllegalStateException("Edition export failed: ${(expRes.out + expRes.err).takeLast(3).joinToString(" ").take(180)}")
                }
            }

            // Replace old install.wim/esd with slimmed file
            val finalInstallInIso = "$isoDir/sources/install.$dstExt"
            Shell.cmd("rm -f $qIsoDir/sources/install.wim $qIsoDir/sources/install.esd $qIsoDir/sources/INSTALL.WIM $qIsoDir/sources/INSTALL.ESD && mv -f $qNewInstall ${shellQuote(finalInstallInIso)}").exec()

            // Optional Windows CA 2023 bootloaders, ei.cfg unlock, and Two-Pane Experience/WinUtil tweaks
            if (config.experience.useCa2023Bootloaders) {
                onProgress(WindowsBuildProgress("Applying CA 2023 bootloaders...", outName, 0.80f, false))
                WindowsBuildEngine.runIsolatedRootScript(
                    context,
                    "slim_ca2023",
                    WindowsCustomizationEngine.applyCa2023BootloadersSnippet(qWim, shellQuote(finalInstallInIso), qIsoDir)
                )
            }
            if (config.eiCfgUnlock) {
                val tmpEi = File(context.cacheDir, "slim_ei.cfg").apply { writeText("[Channel]\r\nRetail\r\n", Charsets.UTF_8) }
                Shell.cmd("cp -f ${shellQuote(tmpEi.absolutePath)} ${shellQuote("$isoDir/sources/ei.cfg")}").exec()
                tmpEi.delete()
            }
            if (config.experience.hasAnyActive() || config.winUtil.hasAnyActive()) {
                onProgress(WindowsBuildProgress("Injecting Experience & WinUtil tweaks...", "Writing autounattend.xml & Setup scripts", 0.83f, false))
                val safeArch = if (config.arch.lowercase() in setOf("x86", "arm64", "amd64")) config.arch.lowercase() else "amd64"
                val oemScripts = "$isoDir/sources/\$OEM\$/\$\$/Setup/Scripts"
                val oemCustom = "$isoDir/sources/\$OEM\$/\$\$/Setup/Custom"
                val oemPanther = "$isoDir/sources/\$OEM\$/\$\$/Panther"
                val customPath = config.experience.customPostInstallScriptPath.trim()
                val customName = if (customPath.isNotBlank()) customPath.substringAfterLast('/') else ""

                val tmpXml = File(context.cacheDir, "slim_auto.xml").apply {
                    writeText(WindowsCustomizationEngine.generateAutounattendXml(config.experience, config.winUtil, safeArch, null, true, false), Charsets.UTF_8)
                }
                val tmpSpec = File(context.cacheDir, "slim_spec.cmd").apply {
                    writeText(WindowsCustomizationEngine.buildSpecializeScript(config.experience, config.winUtil), Charsets.UTF_8)
                }
                val tmpComp = File(context.cacheDir, "slim_comp.cmd").apply {
                    writeText(WindowsCustomizationEngine.buildSetupCompleteScript(false, customName.isNotBlank(), customName), Charsets.UTF_8)
                }
                val customCopy = if (customPath.isNotBlank()) {
                    "mkdir -p ${shellQuote(oemCustom)} && cp -f ${shellQuote(UsbGadgetEngine.resolveDirectPath(customPath))} ${shellQuote(oemCustom)}/ 2>/dev/null || true"
                } else "true"
                Shell.cmd(
                    "mkdir -p ${shellQuote(oemPanther)} ${shellQuote(oemScripts)} && " +
                        "cp -f ${shellQuote(tmpXml.absolutePath)} ${shellQuote("$isoDir/autounattend.xml")} && " +
                        "cp -f ${shellQuote(tmpXml.absolutePath)} ${shellQuote("$oemPanther/unattend.xml")} && " +
                        "cp -f ${shellQuote(tmpSpec.absolutePath)} ${shellQuote("$oemScripts/PocketBoot_Specialize.cmd")} && " +
                        "cp -f ${shellQuote(tmpComp.absolutePath)} ${shellQuote("$oemScripts/SetupComplete.cmd")} && $customCopy"
                ).exec()
                tmpXml.delete(); tmpSpec.delete(); tmpComp.delete()
            }

            if (qGenIso == null && !config.convertToEsd) {
                val slimWimSz = Shell.cmd("stat -c '%s' ${shellQuote(finalInstallInIso)} 2>/dev/null || echo 0").exec().out.firstOrNull()?.trim()?.toLongOrNull() ?: 0L
                if (slimWimSz > 3_950_000_000L) {
                    onProgress(WindowsBuildProgress("Auto-splitting >4GB install.wim...", "cdfs.sys compatibility (3.5 GB chunks)", 0.85f, false))
                    val swmDst = "$isoDir/sources/install.swm"
                    WindowsBuildEngine.runIsolatedRootScript(context, "slim_swm", "set -e\n$envPrefix\n$qWim split ${shellQuote(finalInstallInIso)} ${shellQuote(swmDst)} 3500 >/dev/null 2>&1\nrm -f ${shellQuote(finalInstallInIso)}")
                }
            }

            onProgress(WindowsBuildProgress(if (qGenIso != null) "Rebuilding UDF 1.02 ISO..." else "Rebuilding bootable ISO...", outName, 0.87f, false))
            val preferNoPrompt = config.experience.hasAnyActive() || config.winUtil.hasAnyActive()
            val packCmd = if (qGenIso != null) {
                """
                set -e
                $envPrefix
                ${WindowsBuildEngine.thermalExecPrefix(slimProf)}
                mkdir -p ${shellQuote(outDir)}
                rm -f $qOut
                ISODIR=$qIsoDir
                cd "${'$'}ISODIR"
                EFI_BOOT=""
                if [ "${if (preferNoPrompt) "1" else "0"}" = "1" ]; then
                    EFI_BOOT=${'$'}(find . -maxdepth 4 -type f -iname 'efisys_noprompt.bin' 2>/dev/null | head -n 1 | sed 's|^\./||')
                fi
                if [ -z "${'$'}EFI_BOOT" ]; then
                    EFI_BOOT=${'$'}(find . -maxdepth 4 -type f -iname 'efisys.bin' 2>/dev/null | head -n 1 | sed 's|^\./||')
                fi
                BIOS_BOOT=${'$'}(find . -maxdepth 4 -type f -iname 'etfsboot.com' 2>/dev/null | head -n 1 | sed 's|^\./||')

                if [ -n "${'$'}BIOS_BOOT" ] && [ -n "${'$'}EFI_BOOT" ]; then
                    ${'$'}THERM_PFX $qGenIso -udf -allow-limited-size -iso-level 3 -J -joliet-long -D -N -relaxed-filenames -V "POCKETBOOT_SLIM" -b "${'$'}BIOS_BOOT" -no-emul-boot -boot-load-size 8 -eltorito-alt-boot -e "${'$'}EFI_BOOT" -no-emul-boot -o $qOut .
                elif [ -n "${'$'}EFI_BOOT" ]; then
                    ${'$'}THERM_PFX $qGenIso -udf -allow-limited-size -iso-level 3 -J -joliet-long -D -N -relaxed-filenames -V "POCKETBOOT_SLIM" -e "${'$'}EFI_BOOT" -no-emul-boot -o $qOut .
                else
                    ${'$'}THERM_PFX $qGenIso -udf -allow-limited-size -iso-level 3 -J -joliet-long -D -N -relaxed-filenames -V "POCKETBOOT_SLIM" -o $qOut .
                fi
                """.trimIndent()
            } else {
                """
                set -e
                $envPrefix
                ${WindowsBuildEngine.thermalExecPrefix(slimProf)}
                mkdir -p ${shellQuote(outDir)}
                rm -f $qOut
                ISODIR=$qIsoDir
                cd "${'$'}ISODIR"
                EFI_BOOT=""
                if [ "${if (preferNoPrompt) "1" else "0"}" = "1" ]; then
                    EFI_BOOT=${'$'}(find . -maxdepth 4 -type f -iname 'efisys_noprompt.bin' 2>/dev/null | head -n 1 | sed 's|^\./||')
                fi
                if [ -z "${'$'}EFI_BOOT" ]; then
                    EFI_BOOT=${'$'}(find . -maxdepth 4 -type f -iname 'efisys.bin' 2>/dev/null | head -n 1 | sed 's|^\./||')
                fi
                BIOS_BOOT=${'$'}(find . -maxdepth 4 -type f -iname 'etfsboot.com' 2>/dev/null | head -n 1 | sed 's|^\./||')

                if [ -n "${'$'}BIOS_BOOT" ] && [ -n "${'$'}EFI_BOOT" ]; then
                    ${'$'}THERM_PFX ${qXor!!} -as mkisofs --stdio_sync 16m -iso-level 3 -full-iso9660-filenames -J -joliet-long -volid "POCKETBOOT_SLIM" -b "${'$'}BIOS_BOOT" -no-emul-boot -boot-load-size 8 -eltorito-alt-boot -e "${'$'}EFI_BOOT" -no-emul-boot -o $qOut "${'$'}ISODIR"
                elif [ -n "${'$'}EFI_BOOT" ]; then
                    ${'$'}THERM_PFX ${qXor!!} -as mkisofs --stdio_sync 16m -iso-level 3 -full-iso9660-filenames -J -joliet-long -volid "POCKETBOOT_SLIM" -e "${'$'}EFI_BOOT" -no-emul-boot -o $qOut "${'$'}ISODIR"
                else
                    ${'$'}THERM_PFX ${qXor!!} -as mkisofs --stdio_sync 16m -iso-level 3 -full-iso9660-filenames -J -joliet-long -volid "POCKETBOOT_SLIM" -o $qOut "${'$'}ISODIR"
                fi
                """.trimIndent()
            }
            val xorRes = WindowsBuildEngine.runIsolatedRootScript(context, "slim_iso", packCmd)
            if (!xorRes.isSuccess) {
                throw IllegalStateException("ISO rebuild failed: ${(xorRes.out + xorRes.err).takeLast(3).joinToString(" ").take(180)}")
            }

            UsbGadgetEngine.fixUserStoragePermissions(context, outDir, isDir = true)
            UsbGadgetEngine.fixUserStoragePermissions(context, outPath, isDir = false)
            UsbGadgetEngine.cleanupTempWorkDir(workRoot)

            val newSize = Shell.cmd("stat -c '%s' $qOut 2>/dev/null").exec().out.firstOrNull()?.trim()?.toLongOrNull() ?: 0L
            val savedBytes = (config.sourceIso.sizeBytes - newSize).coerceAtLeast(0L)
            onProgress(WindowsBuildProgress("Done!", outName, 1f, false))
            ActionResult(
                success = true,
                userMessage = "Created $outName (${UsbGadgetEngine.formatBytes(newSize)} · saved ${UsbGadgetEngine.formatBytes(savedBytes)}).",
                createdPath = outPath
            )
        }.getOrElse { e ->
            UsbGadgetEngine.cleanupTempWorkDir(workRoot)
            ActionResult(false, "ISO Slimmer failed: ${e.message ?: "Unexpected error"}")
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WindowsIsoSlimmerSheet(
    isoFile: ImageFile,
    defaultDir: String,
    presets: List<AutounattendPreset> = emptyList(),
    building: Boolean,
    progress: WindowsBuildProgress,
    onDismiss: () -> Unit,
    onCancel: () -> Unit,
    onSavePreset: (AutounattendPreset) -> Unit = {},
    onDeletePreset: (String) -> Unit = {},
    onStartSlim: (IsoSlimConfig) -> Unit
) {
    val context = LocalContext.current
    val detectedOs = remember(isoFile.name) { WindowsCustomizationEngine.detectWindowsOsKind(isoFile.name) }
    val detectedArch = remember(isoFile.name) { WindowsCustomizationEngine.detectProcessorArchitecture(isoFile.name) }
    var loadingEditions by remember(isoFile.path) { mutableStateOf(true) }
    var editions by remember(isoFile.path) { mutableStateOf<List<WindowsEditionItem>>(emptyList()) }
    val selectedIndices = remember(isoFile.path) { mutableStateListOf<Int>() }
    var convertToEsd by remember { mutableStateOf(true) }
    var eiCfgUnlock by remember(isoFile.path) { mutableStateOf(false) }
    var experience by remember(isoFile.path) { mutableStateOf(WindowsExperienceConfig.defaultForOs(detectedOs)) }
    var winUtil by remember(isoFile.path) { mutableStateOf(WinUtilTweaksConfig()) }
    val defaultOutName = remember(isoFile.name) {
        isoFile.name.substringBeforeLast('.').take(34) + "-Slim.iso"
    }
    var outputName by remember(isoFile.path) { mutableStateOf(defaultOutName) }

    val freeSpace = remember(defaultDir) { UsbGadgetEngine.getFreeSpaceBytes(defaultDir) }
    val neededSpace = (isoFile.sizeBytes * 2L) + (512L * 1024L * 1024L)
    val lowSpace = freeSpace in 1L until neededSpace

    LaunchedEffect(isoFile.path) {
        loadingEditions = true
        editions = WindowsImageBuilder.inspectWindowsEditions(context, isoFile.path)
        selectedIndices.clear()
        val pro = editions.firstOrNull { it.name.contains("Pro", true) && !it.name.contains("Education", true) && !it.name.contains("N", true) }
            ?: editions.firstOrNull()
        if (pro != null) selectedIndices.add(pro.index)
        loadingEditions = false
    }

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
            verticalArrangement = Arrangement.spacedBy(13.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(
                    modifier = Modifier.size(42.dp).clip(RoundedCornerShape(12.dp)).background(PocketColors.violetBg),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Rounded.Compress, null, tint = PocketColors.violet, modifier = Modifier.size(22.dp))
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text("Windows ISO Slimmer", style = MaterialTheme.typography.titleLarge, color = PocketColors.textPrimary)
                    Text("${isoFile.name} · ${UsbGadgetEngine.formatBytes(isoFile.sizeBytes)}", style = PocketColors.monoStyle, color = PocketColors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }

            if (building) {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = PocketColors.surfaceInput,
                    border = BorderStroke(1.dp, PocketColors.accent)
                ) {
                    Column(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(progress.stage, style = MaterialTheme.typography.titleMedium, color = PocketColors.textPrimary)
                        if (progress.detail.isNotBlank()) {
                            Text(progress.detail, style = MaterialTheme.typography.bodySmall, color = PocketColors.textSecondary)
                        }
                        if (progress.isIndeterminate) {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(7.dp).clip(RoundedCornerShape(999.dp)), color = PocketColors.accent, trackColor = PocketColors.surfaceSubtle)
                        } else {
                            LinearProgressIndicator(progress = { progress.progress.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().height(7.dp).clip(RoundedCornerShape(999.dp)), color = PocketColors.accent, trackColor = PocketColors.surfaceSubtle)
                        }
                    }
                }
                OutlinedButton(
                    onClick = onCancel,
                    modifier = Modifier.fillMaxWidth().height(PocketTokens.PrimaryButtonHeight),
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, PocketColors.danger)
                ) {
                    Icon(Icons.Rounded.Close, null, tint = PocketColors.danger, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Cancel", color = PocketColors.danger, fontWeight = FontWeight.SemiBold)
                }
            } else if (loadingEditions) {
                Box(modifier = Modifier.fillMaxWidth().padding(vertical = 28.dp), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        CircularProgressIndicator(color = PocketColors.accent)
                        Text("Inspecting Windows editions inside ISO...", style = MaterialTheme.typography.bodySmall, color = PocketColors.textSecondary)
                    }
                }
            } else if (editions.isEmpty()) {
                Surface(shape = RoundedCornerShape(12.dp), color = PocketColors.warningBg, border = BorderStroke(0.5.dp, PocketColors.warning)) {
                    Text(
                        "Could not read install.wim/install.esd editions from this ISO.",
                        modifier = Modifier.padding(14.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = PocketColors.textPrimary
                    )
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    PocketSectionHeader("Editions to Keep", trailing = "${selectedIndices.size} / ${editions.size}")
                    Surface(shape = RoundedCornerShape(12.dp), color = PocketColors.surfaceInput, border = BorderStroke(0.5.dp, PocketColors.border)) {
                        Column {
                            editions.forEachIndexed { idx, ed ->
                                if (idx > 0) PocketHairlineDivider()
                                val checked = selectedIndices.contains(ed.index)
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            if (checked && selectedIndices.size > 1) selectedIndices.remove(ed.index)
                                            else if (!checked) selectedIndices.add(ed.index)
                                        }
                                        .padding(horizontal = 12.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    Icon(
                                        imageVector = if (checked) Icons.Rounded.CheckBox else Icons.Rounded.CheckBoxOutlineBlank,
                                        contentDescription = null,
                                        tint = if (checked) PocketColors.accent else PocketColors.textMuted,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Text("#${ed.index}", style = PocketColors.monoStyle, color = PocketColors.textMuted)
                                    Text(ed.name, style = MaterialTheme.typography.labelMedium, color = PocketColors.textPrimary, modifier = Modifier.weight(1f))
                                }
                            }
                        }
                    }
                }

                WindowsTwoPaneCustomizer(
                    osKind = detectedOs,
                    arch = detectedArch,
                    experience = experience,
                    winUtil = winUtil,
                    presets = presets,
                    showCa2023Option = true,
                    showCustomScriptOption = true,
                    defaultBrowseDir = defaultDir,
                    onExperienceChange = { experience = it },
                    onWinUtilChange = { winUtil = it },
                    onApplyPreset = { p ->
                        experience = WindowsExperienceConfig(
                            p.bypassRequirements, p.bypassMicrosoftAccount, p.disableDataCollection, p.disableBitLocker,
                            p.qolImprovements, p.disableSponsoredApps, p.classicContextMenu, p.skipWifiSetup,
                            p.createLocalUser, p.localUsername, "", p.skipOobe, p.useCa2023Bootloaders, p.applySkuSiPolicy
                        )
                        winUtil = WinUtilTweaksConfig.fromJsonString(p.winUtilJson)
                    },
                    onSavePreset = { n ->
                        onSavePreset(
                            AutounattendPreset(
                                n, PartitionScheme.GPT.name, experience.bypassRequirements, experience.bypassMicrosoftAccount,
                                experience.disableDataCollection, experience.disableBitLocker, experience.disableSponsoredApps,
                                experience.classicContextMenu, experience.skipWifiSetup, experience.createLocalUser,
                                experience.localUsername, experience.skipOobe, experience.qolImprovements,
                                experience.useCa2023Bootloaders, experience.applySkuSiPolicy, winUtil.toJsonString()
                            )
                        )
                    },
                    onDeletePreset = onDeletePreset
                )

                Surface(shape = RoundedCornerShape(12.dp), color = PocketColors.surfaceInput, border = BorderStroke(0.5.dp, PocketColors.border)) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier.fillMaxWidth().bounceClick { convertToEsd = !convertToEsd }.padding(horizontal = 14.dp, vertical = 11.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Recompress to Compact ESD", style = MaterialTheme.typography.labelMedium, color = PocketColors.textPrimary)
                                Text("Saves 1.5–2 GB extra (slower build)", fontSize = 11.sp, color = PocketColors.textSecondary)
                            }
                            PocketSwitch(checked = convertToEsd, onCheckedChange = { convertToEsd = it }, activeColor = PocketColors.success)
                        }
                        PocketHairlineDivider()
                        Row(
                            modifier = Modifier.fillMaxWidth().clickable { eiCfgUnlock = !eiCfgUnlock }.padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Unlock All Editions (ei.cfg)", style = MaterialTheme.typography.labelMedium, color = PocketColors.textPrimary)
                                Text("Lets you choose Pro/Edu even on OEM-keyed laptops", fontSize = 11.sp, color = PocketColors.textSecondary)
                            }
                            PocketSwitch(checked = eiCfgUnlock, onCheckedChange = { eiCfgUnlock = it }, activeColor = PocketColors.accent)
                        }
                    }
                }

                OutlinedTextField(
                    value = outputName,
                    onValueChange = { outputName = it },
                    label = { Text("Output Slim ISO Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = PocketColors.accent,
                        unfocusedBorderColor = PocketColors.border
                    )
                )

                Surface(
                    shape = RoundedCornerShape(11.dp),
                    color = if (lowSpace) PocketColors.dangerBg else PocketColors.surfaceInput,
                    border = BorderStroke(0.5.dp, if (lowSpace) PocketColors.danger else PocketColors.border)
                ) {
                    Row(modifier = Modifier.fillMaxWidth().padding(11.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(if (lowSpace) Icons.Rounded.WarningAmber else Icons.Rounded.Info, null, tint = if (lowSpace) PocketColors.danger else PocketColors.accentSoft, modifier = Modifier.size(17.dp))
                        Text(
                            text = if (lowSpace) "Need ~${UsbGadgetEngine.formatBytes(neededSpace)} free temporarily (have ${UsbGadgetEngine.formatBytes(freeSpace)})."
                            else "Uses ${UsbGadgetEngine.friendlyFolderName(defaultDir)}/.pocketboot_tmp during build (${UsbGadgetEngine.formatBytes(freeSpace)} free).",
                            style = MaterialTheme.typography.bodySmall,
                            color = PocketColors.textPrimary
                        )
                    }
                }

                Button(
                    onClick = {
                        onStartSlim(
                            IsoSlimConfig(
                                sourceIso = isoFile,
                                outputDir = defaultDir,
                                outputName = outputName.ifBlank { defaultOutName },
                                keepEditionIndices = selectedIndices.sorted(),
                                convertToEsd = convertToEsd,
                                arch = detectedArch,
                                eiCfgUnlock = eiCfgUnlock,
                                experience = experience,
                                winUtil = winUtil
                            )
                        )
                    },
                    enabled = !lowSpace && selectedIndices.isNotEmpty() && outputName.isNotBlank(),
                    modifier = Modifier.fillMaxWidth().height(PocketTokens.PrimaryButtonHeight),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = PocketColors.accent, contentColor = PocketColors.onAccent)
                ) {
                    Text("Build Slim Windows ISO", fontWeight = FontWeight.SemiBold)
                }
            }

            Spacer(modifier = Modifier.height(22.dp))
        }
    }
}
