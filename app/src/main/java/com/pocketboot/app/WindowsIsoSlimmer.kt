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
import androidx.compose.material.icons.rounded.CheckCircle
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

    fun cancelActiveSlim() {
        BuildThermalHelper.cancelActiveBuildProcess()
    }

    suspend fun slimWindowsIso(
        context: Context,
        config: IsoSlimConfig,
        onProgress: (WindowsBuildProgress) -> Unit
    ): ActionResult = withContext(Dispatchers.IO) {
        val sevenZip = WindowsImageBuilder.findBundledBinary(context, "lib7zzs.so", listOf("7zzs", "7zz", "7z"))
            ?: return@withContext ActionResult(false, "Bundled 7-Zip extractor not found.")
        val wimlib = WindowsImageBuilder.findBundledBinary(context, "libwimlib_imagex.so", listOf("wimlib-imagex"))
            ?: return@withContext ActionResult(false, "Bundled wimlib-imagex not found.")
        val xorriso = WindowsImageBuilder.findBundledBinary(context, "libxorriso.so", listOf("xorriso"))
            ?: return@withContext ActionResult(false, "Bundled ISO packer (xorriso) not found.")

        BuildThermalHelper.resetCancelFlag()

        val srcPath = UsbGadgetEngine.resolveDirectPath(config.sourceIso.path)
        val outDir = UsbGadgetEngine.resolveDirectPath(config.outputDir).trimEnd('/')
        val outName = config.outputName.trim().let { if (it.endsWith(".iso", true)) it else "$it.iso" }
        val outPath = "$outDir/$outName"
        val tmpOutPath = "$outDir/.pb_slim_${System.currentTimeMillis().toString().takeLast(6)}.tmp.iso"

        val workRoot = UsbGadgetEngine.createSafeTempWorkDir(context, outDir, "slim_${System.currentTimeMillis() % 100000}")
        val isoDir = "$workRoot/ISODIR"
        val tmpDir = "$workRoot/TMP"
        val qIso = shellQuote(srcPath)
        val qIsoDir = shellQuote(isoDir)
        val qTmp = shellQuote(tmpDir)
        val qOut = shellQuote(outPath)
        val qTmpOut = shellQuote(tmpOutPath)
        val q7z = shellQuote(sevenZip)
        val qWim = shellQuote(wimlib)
        val qXor = shellQuote(xorriso)
        val envPrefix = "export TMPDIR=$qTmp HOME=$qTmp WIMLIB_IMAGEX_IGNORE_CASE=1"

        fun cleanupAll() {
            Shell.cmd("rm -f $qTmpOut ${shellQuote(outDir)}/.pb_slim_*.tmp.iso 2>/dev/null || true").exec()
            UsbGadgetEngine.cleanupTempWorkDir(workRoot)
        }

        return@withContext runCatching {
            // Preserve official Microsoft ISO-9660 volume label (32 bytes at PVD offset 32808) when present
            val rawVolId = Shell.cmd(
                "MAG=\$(dd if=$qIso bs=1 skip=32769 count=5 2>/dev/null | tr -cd 'A-Z0-9'); " +
                    "if [ \"\$MAG\" = \"CD001\" ]; then dd if=$qIso bs=1 skip=32808 count=32 2>/dev/null | tr -cd 'A-Za-z0-9_-'; fi"
            ).exec().out.firstOrNull().orEmpty().trim().take(32)
            val volId = rawVolId.ifBlank { "POCKETBOOT_SLIM" }

            onProgress(WindowsBuildProgress("Extracting source Windows ISO...", config.sourceIso.name, 0.14f, true))
            Shell.cmd("mkdir -p $qIsoDir $qTmp").exec()
            val slimProf = BuildThermalHelper.currentDefaultThermalProfile()
            val mmtArg = BuildThermalHelper.sevenZipThreadArgFor(slimProf)
            BuildThermalHelper.waitForThermalCooldownIfNeeded(context, slimProf) { temp, resumeAt ->
                onProgress(WindowsBuildProgress("Cooling down phone...", String.format(java.util.Locale.US, "%.1f°C -> resuming at %.1f°C", temp, resumeAt), 0.14f, true))
            }
            if (!isActive || BuildThermalHelper.isBuildCancelled()) {
                cleanupAll()
                return@withContext ActionResult(false, "ISO slimming cancelled.")
            }
            val extRes = BuildThermalHelper.runIsolatedRootScript(
                context,
                "slim_ext",
                "set -e\n$envPrefix\n${BuildThermalHelper.thermalExecPrefix(slimProf)}\n\$THERM_PFX $q7z x -y $mmtArg -o$qIsoDir $qIso",
                trackPid = true
            )
            if (!isActive || BuildThermalHelper.isBuildCancelled()) {
                cleanupAll()
                return@withContext ActionResult(false, "ISO slimming cancelled.")
            }
            if (!extRes.isSuccess) {
                throw IllegalStateException("Extraction failed: ${(extRes.out + extRes.err).takeLast(3).joinToString(" ").take(180)}")
            }

            // Resolve actual case of sources/ folder (handles uppercase SOURCES/ on official & OEM ISOs)
            val sourcesDir = Shell.cmd(
                "find $qIsoDir -maxdepth 1 -mindepth 1 -type d -iname 'sources' 2>/dev/null | head -n 1"
            ).exec().out.firstOrNull().orEmpty().trim().ifBlank { "$isoDir/sources" }
            val qSourcesDir = shellQuote(sourcesDir)

            // Support install.wim, install.esd, AND split install.swm sources
            val srcWim = Shell.cmd(
                "find $qSourcesDir -maxdepth 1 -type f \\( -iname 'install.wim' -o -iname 'install.esd' -o -iname 'install.swm' \\) 2>/dev/null | head -n 1"
            ).exec().out.firstOrNull().orEmpty().trim()
            if (srcWim.isBlank()) {
                throw IllegalStateException("No sources/install.wim, install.esd, or install.swm found inside ISO.")
            }
            val srcIsSplitSwm = srcWim.lowercase().endsWith(".swm")
            val swmRefArg = if (srcIsSplitSwm) {
                val swmBase = srcWim.substringAfterLast('/')
                val swmPrefix = swmBase.substringBeforeLast('.')
                val swmExt = swmBase.substringAfterLast('.')
                "--ref=${shellQuote("$sourcesDir/${swmPrefix}*.${swmExt}")}"
            } else ""

            val dstExt = if (config.convertToEsd) "esd" else "wim"
            val wimThrArg = BuildThermalHelper.wimThreadArgFor(slimProf, isSolidEsd = config.convertToEsd)
            val compArg = if (config.convertToEsd) "--compress=LZMS --solid $wimThrArg" else "--compress=maximum $wimThrArg"
            val newInstall = "$tmpDir/install.$dstExt"
            val qSrcWim = shellQuote(srcWim)
            val qNewInstall = shellQuote(newInstall)

            config.keepEditionIndices.forEachIndexed { pos, edIdx ->
                if (!isActive || BuildThermalHelper.isBuildCancelled()) {
                    cleanupAll()
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
                BuildThermalHelper.waitForThermalCooldownIfNeeded(context, slimProf) { temp, resumeAt ->
                    onProgress(WindowsBuildProgress("Cooling down phone...", String.format(java.util.Locale.US, "%.1f°C -> resuming at %.1f°C", temp, resumeAt), pct, false))
                }
                if (!isActive || BuildThermalHelper.isBuildCancelled()) {
                    cleanupAll()
                    return@withContext ActionResult(false, "ISO slimming cancelled.")
                }
                val expRes = BuildThermalHelper.runIsolatedRootScript(
                    context,
                    "slim_exp",
                    "set -e\n$envPrefix\n${BuildThermalHelper.thermalExecPrefix(slimProf)}\n\$THERM_PFX $qWim export $qSrcWim $edIdx $qNewInstall $swmRefArg $compArg",
                    trackPid = true
                )
                if (!isActive || BuildThermalHelper.isBuildCancelled()) {
                    cleanupAll()
                    return@withContext ActionResult(false, "ISO slimming cancelled.")
                }
                if (!expRes.isSuccess) {
                    throw IllegalStateException("Edition export failed: ${(expRes.out + expRes.err).takeLast(3).joinToString(" ").take(180)}")
                }
            }

            // Replace old install.* inside resolved sourcesDir (handles any filename casing)
            var finalInstallInIso = "$sourcesDir/install.$dstExt"
            Shell.cmd(
                "find $qSourcesDir -maxdepth 1 -type f \\( -iname 'install.wim' -o -iname 'install.esd' -o -iname 'install*.swm' \\) -exec rm -f {} + 2>/dev/null || true; " +
                    "mv -f $qNewInstall ${shellQuote(finalInstallInIso)}"
            ).exec()

            // Windows 7 UEFI bootx64.efi extraction when missing, plus optional CA 2023 bootloaders
            BuildThermalHelper.runIsolatedRootScript(
                context,
                "slim_w7efi",
                WindowsCustomizationEngine.applyWin7UefiFixSnippet(qWim, shellQuote(finalInstallInIso), qIsoDir)
            )
            if (config.experience.useCa2023Bootloaders) {
                onProgress(WindowsBuildProgress("Applying CA 2023 bootloaders...", outName, 0.80f, false))
                BuildThermalHelper.runIsolatedRootScript(
                    context,
                    "slim_ca2023",
                    WindowsCustomizationEngine.applyCa2023BootloadersSnippet(qWim, shellQuote(finalInstallInIso), qIsoDir)
                )
            }
            if (config.eiCfgUnlock) {
                val tmpEi = File(context.cacheDir, "slim_ei.cfg").apply { writeText("[Channel]\r\nRetail\r\n", Charsets.UTF_8) }
                Shell.cmd(
                    "find $qSourcesDir -maxdepth 1 -type f -iname 'ei.cfg' -exec rm -f {} + 2>/dev/null || true; " +
                        "cp -f ${shellQuote(tmpEi.absolutePath)} ${shellQuote("$sourcesDir/ei.cfg")}"
                ).exec()
                tmpEi.delete()
            }

            // Only inject autounattend.xml + $OEM$ when actual Unattend/WinUtil tweaks are enabled (decoupled from CA 2023)
            if (config.experience.hasUnattendTweaks() || config.winUtil.hasAnyActive()) {
                onProgress(WindowsBuildProgress("Injecting Experience & WinUtil tweaks...", "Writing autounattend.xml & Setup scripts", 0.82f, false))
                val safeArch = if (config.arch.lowercase() in setOf("x86", "arm64", "amd64")) config.arch.lowercase() else "amd64"
                val langIniPath = Shell.cmd("find $qSourcesDir -maxdepth 1 -type f -iname 'lang.ini' 2>/dev/null | head -n 1").exec().out.firstOrNull().orEmpty().trim()
                val detectedLang = if (langIniPath.isNotBlank()) {
                    Shell.cmd("grep -iE '^[a-z]{2,3}(-[a-z0-9]{2,4}){1,2}[[:space:]]*=' ${shellQuote(langIniPath)} 2>/dev/null | head -n 1 | cut -d= -f1 | tr -d ' \\r\\n'").exec().out.firstOrNull().orEmpty().trim()
                } else ""
                val oemScripts = "$sourcesDir/\$OEM\$/\$\$/Setup/Scripts"
                val oemCustom = "$sourcesDir/\$OEM\$/\$\$/Setup/Custom"
                val oemPanther = "$sourcesDir/\$OEM\$/\$\$/Panther"
                val customPath = config.experience.customPostInstallScriptPath.trim()
                val customName = if (customPath.isNotBlank()) customPath.substringAfterLast('/') else ""

                val tmpXml = File(context.cacheDir, "slim_auto.xml").apply {
                    writeText(
                        WindowsCustomizationEngine.generateAutounattendXml(
                            config.experience, config.winUtil, safeArch, null, true, false,
                            includeWindowsPePass = true, uiLang = detectedLang
                        ),
                        Charsets.UTF_8
                    )
                }
                val tmpPantherXml = File(context.cacheDir, "slim_panther.xml").apply {
                    writeText(
                        WindowsCustomizationEngine.generateAutounattendXml(
                            config.experience, config.winUtil, safeArch, null, true, false,
                            includeWindowsPePass = false, uiLang = detectedLang
                        ),
                        Charsets.UTF_8
                    )
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
                        "cp -f ${shellQuote(tmpPantherXml.absolutePath)} ${shellQuote("$oemPanther/unattend.xml")} && " +
                        "cp -f ${shellQuote(tmpSpec.absolutePath)} ${shellQuote("$oemScripts/PocketBoot_Specialize.cmd")} && " +
                        "cp -f ${shellQuote(tmpComp.absolutePath)} ${shellQuote("$oemScripts/SetupComplete.cmd")} && $customCopy"
                ).exec()
                tmpXml.delete(); tmpPantherXml.delete(); tmpSpec.delete(); tmpComp.delete()
            }

            // ISO-9660 / cdfs.sys / FAT32 >3.95 GiB guard (handles BOTH Standard WIM AND multi-edition Compact ESD!)
            val finalInstallSz = Shell.cmd("stat -c '%s' ${shellQuote(finalInstallInIso)} 2>/dev/null || echo 0").exec().out.firstOrNull()?.trim()?.toLongOrNull() ?: 0L
            if (finalInstallSz > 3_950_000_000L) {
                val swmDst = "$sourcesDir/install.swm"
                if (config.convertToEsd) {
                    // Solid ESD cannot be split directly into .swm; convert to WIM & split into <=3.5 GB install*.swm chunks
                    onProgress(WindowsBuildProgress("Multi-edition ESD >4 GB — converting to split WIM...", "cdfs.sys + FAT32 safe (≤3.5 GB chunks)", 0.84f, false))
                    val convWim = "$tmpDir/install_split_src.wim"
                    val lzxThrArg = BuildThermalHelper.wimThreadArgFor(slimProf, isSolidEsd = false)
                    val convRes = BuildThermalHelper.runIsolatedRootScript(
                        context,
                        "slim_esd2wim",
                        "set -e\n$envPrefix\n${BuildThermalHelper.thermalExecPrefix(slimProf)}\n\$THERM_PFX $qWim export ${shellQuote(finalInstallInIso)} all ${shellQuote(convWim)} --compress=maximum $lzxThrArg\nrm -f ${shellQuote(finalInstallInIso)}",
                        trackPid = true
                    )
                    if (!convRes.isSuccess) throw IllegalStateException("Failed converting >4 GiB ESD for splitting")
                    finalInstallInIso = convWim
                }
                onProgress(WindowsBuildProgress("Auto-splitting >4 GB install.wim...", "cdfs.sys + FAT32 compatibility (3.5 GB chunks)", 0.85f, false))
                val swmRes = BuildThermalHelper.runIsolatedRootScript(
                    context,
                    "slim_swm",
                    "set -e\n$envPrefix\n${BuildThermalHelper.thermalExecPrefix(slimProf)}\n\$THERM_PFX $qWim split ${shellQuote(finalInstallInIso)} ${shellQuote(swmDst)} 3500 >/dev/null 2>&1\n" +
                        "if [ ! -s ${shellQuote(swmDst)} ] || [ ! -s ${shellQuote("$sourcesDir/install2.swm")} ]; then echo 'WIM split output missing' >&2; exit 1; fi\n" +
                        "rm -f ${shellQuote(finalInstallInIso)}",
                    trackPid = true
                )
                if (!swmRes.isSuccess) throw IllegalStateException("Failed to split >4 GiB install.wim")
            }

            BuildThermalHelper.waitForThermalCooldownIfNeeded(context, slimProf) { temp, resumeAt ->
                onProgress(WindowsBuildProgress("Cooling down phone...", String.format(java.util.Locale.US, "%.1f°C -> resuming at %.1f°C", temp, resumeAt), 0.86f, false))
            }
            if (!isActive || BuildThermalHelper.isBuildCancelled()) {
                cleanupAll()
                return@withContext ActionResult(false, "ISO slimming cancelled.")
            }

            // Purge stale MediaStore row before atomic repack if overwriting an existing ISO
            if (outPath.startsWith("/data/media/0")) {
                val pubOut = outPath.replaceFirst("/data/media/0", "/storage/emulated/0")
                val qWhere = shellQuote("_data='${pubOut.replace("'", "''")}'")
                Shell.cmd("content delete --user 0 --uri content://media/external/file --where $qWhere >/dev/null 2>&1 || true; rm -f $qTmpOut 2>/dev/null || true; sync").exec()
            }

            onProgress(WindowsBuildProgress("Rebuilding bootable ISO (xorriso)...", outName, 0.87f, false))
            // Match official Microsoft ISOs: ALWAYS prefer efisys.bin ("Press any key to boot...") to prevent Setup reboot loops
            val packCmd = """
                set -e
                $envPrefix
                ${BuildThermalHelper.thermalExecPrefix(slimProf)}
                mkdir -p ${shellQuote(outDir)}
                rm -f $qTmpOut
                ISODIR=$qIsoDir
                cd "${'$'}ISODIR"
                find . -exec touch {} + 2>/dev/null || true
                EFI_BOOT=${'$'}(find . -maxdepth 4 -type f -iname 'efisys.bin' 2>/dev/null | head -n 1 | sed 's|^\./||')
                if [ -z "${'$'}EFI_BOOT" ]; then
                    EFI_BOOT=${'$'}(find . -maxdepth 4 -type f -iname 'efisys_noprompt.bin' 2>/dev/null | head -n 1 | sed 's|^\./||')
                fi
                BIOS_BOOT=${'$'}(find . -maxdepth 4 -type f -iname 'etfsboot.com' 2>/dev/null | head -n 1 | sed 's|^\./||')
                BOOT_DIR=${'$'}(find . -maxdepth 1 -mindepth 1 -type d -iname 'boot' 2>/dev/null | head -n 1 | sed 's|^\./||')
                if [ -z "${'$'}BOOT_DIR" ]; then BOOT_DIR="boot"; fi
                if [ -n "${'$'}BIOS_BOOT" ] || [ -n "${'$'}EFI_BOOT" ]; then mkdir -p "${'$'}BOOT_DIR"; fi
                BOOT_CAT="${'$'}BOOT_DIR/boot.cat"

                if [ -n "${'$'}BIOS_BOOT" ] && [ -n "${'$'}EFI_BOOT" ]; then
                    ${'$'}THERM_PFX $qXor -as mkisofs --stdio_sync 16m -iso-level 3 -full-iso9660-filenames -J -joliet-long -c "${'$'}BOOT_CAT" -volid "$volId" -b "${'$'}BIOS_BOOT" -no-emul-boot -boot-load-size 8 -eltorito-alt-boot -e "${'$'}EFI_BOOT" -no-emul-boot -o $qTmpOut .
                elif [ -n "${'$'}EFI_BOOT" ]; then
                    ${'$'}THERM_PFX $qXor -as mkisofs --stdio_sync 16m -iso-level 3 -full-iso9660-filenames -J -joliet-long -c "${'$'}BOOT_CAT" -volid "$volId" -e "${'$'}EFI_BOOT" -no-emul-boot -o $qTmpOut .
                else
                    ${'$'}THERM_PFX $qXor -as mkisofs --stdio_sync 16m -iso-level 3 -full-iso9660-filenames -J -joliet-long -volid "$volId" -o $qTmpOut .
                fi
            """.trimIndent()
            val xorRes = BuildThermalHelper.runIsolatedRootScript(context, "slim_iso", packCmd, trackPid = true)
            if (!isActive || BuildThermalHelper.isBuildCancelled()) {
                cleanupAll()
                return@withContext ActionResult(false, "ISO slimming cancelled.")
            }
            if (!xorRes.isSuccess) {
                throw IllegalStateException("ISO rebuild failed: ${(xorRes.out + xorRes.err).takeLast(3).joinToString(" ").take(180)}")
            }

            // Gate 2A: ISO-9660 Primary Volume Descriptor exact volume-size check (catches any truncation)
            onProgress(WindowsBuildProgress("Verifying rebuilt ISO...", outName, 0.95f, false))
            val pvdRes = Shell.cmd(
                "sync; ACT=\$(stat -c '%s' $qTmpOut 2>/dev/null || echo 0); " +
                    "MAG=\$(dd if=$qTmpOut bs=1 skip=32769 count=5 2>/dev/null | tr -cd 'A-Z0-9'); " +
                    "SEC_HEX=\$(dd if=$qTmpOut bs=1 skip=32848 count=4 2>/dev/null | od -An -tx1 | tr -d ' \\r\\n'); " +
                    "BLK_HEX=\$(dd if=$qTmpOut bs=1 skip=32896 count=2 2>/dev/null | od -An -tx1 | tr -d ' \\r\\n'); " +
                    "echo \"PVD|\$ACT|\$MAG|\$SEC_HEX|\$BLK_HEX\""
            ).exec().out.firstOrNull { it.startsWith("PVD|") }
            if (pvdRes != null) {
                val pp = pvdRes.split('|')
                val actBytes = pp.getOrNull(1)?.trim()?.toLongOrNull() ?: 0L
                val magic = pp.getOrNull(2)?.trim().orEmpty()
                val secHex = pp.getOrNull(3)?.trim().orEmpty()
                val blkHex = pp.getOrNull(4)?.trim().orEmpty()
                if (magic != "CD001" || secHex.length != 8 || blkHex.length != 4) {
                    throw IllegalStateException("ISO-9660 header verification failed (magic=$magic)")
                }
                val pvdSectors = (0..3).fold(0L) { acc, idx ->
                    val b = secHex.substring(idx * 2, idx * 2 + 2).toLongOrNull(16) ?: 0L
                    acc or (b shl (idx * 8))
                }
                val blkSize = ((blkHex.substring(0, 2).toLongOrNull(16) ?: 0L) or ((blkHex.substring(2, 4).toLongOrNull(16) ?: 0L) shl 8)).coerceAtLeast(2048L)
                val expectedIsoBytes = pvdSectors * blkSize
                if (expectedIsoBytes <= 0L || actBytes < expectedIsoBytes) {
                    throw IllegalStateException("ISO truncation detected ($actBytes B < expected $expectedIsoBytes B)")
                }
            }

            // Gate 2B: Full 7zzs -tiso entry audit (verifies boot.wim, install.*, and 0 corrupt system files)
            val slimVerify = BuildThermalHelper.runIsolatedRootScript(context, "slim_verify", """
                set -e
                $q7z l -tiso -slt $qTmpOut 2>/dev/null | awk '
                    BEGIN { path=""; folder=""; sz=-1; bad=""; has_boot=0; has_inst=0; seen=0 }
                    function flush_rec() {
                        if (!seen) return
                        if (folder != "+") {
                            lp = tolower(path)
                            if (lp ~ /(^|[\/\\])sources[\/\\]boot\.wim$/ && sz > 0) has_boot = 1
                            if (lp ~ /(^|[\/\\])sources[\/\\]install\.(wim|esd|swm)$/ && sz > 0) has_inst = 1
                            if (sz == 0 && lp ~ /\.(wim|swm|esd|efi|dll|exe|mui|xml|cmd|bat|ini|cfg|sys|man|vbs|com|bin)$|(^|[\/\\])bootmgr$/) bad = path
                        }
                        path=""; folder=""; sz=-1; seen=0
                    }
                    /^Path = / { flush_rec(); path=substr(${'$'}0, 8); seen=1; next }
                    /^Folder = / { folder=substr(${'$'}0, 10); next }
                    /^Size = / { sz=substr(${'$'}0, 8)+0; next }
                    /^$/ { flush_rec(); next }
                    END {
                        flush_rec()
                        if (bad != "") { print "BAD|" bad; exit 1 }
                        if (!has_boot) { print "BAD|missing sources/boot.wim"; exit 1 }
                        if (!has_inst) { print "BAD|missing sources/install.*"; exit 1 }
                        print "OK"
                    }
                '
            """.trimIndent())
            if (!slimVerify.isSuccess) {
                val bad = slimVerify.out.firstOrNull { it.startsWith("BAD|") }?.substringAfter('|') ?: "unreadable ISO"
                throw IllegalStateException("Slimmed ISO integrity check failed: $bad")
            }

            // Atomic finalize + permissions + Gate 2C MTP/MediaStore byte-exact size sync
            val mvRes = Shell.cmd("mv -f $qTmpOut $qOut && sync").exec()
            if (!mvRes.isSuccess) throw IllegalStateException("Failed finalizing ISO rename into $outName")

            UsbGadgetEngine.fixUserStoragePermissions(context, outDir, isDir = true)
            UsbGadgetEngine.fixUserStoragePermissions(context, outPath, isDir = false)
            val newSize = Shell.cmd("stat -c '%s' $qOut 2>/dev/null").exec().out.firstOrNull()?.trim()?.toLongOrNull() ?: 0L
            UsbGadgetEngine.syncMediaStoreFileSize(context, outPath, newSize)
            UsbGadgetEngine.cleanupTempWorkDir(workRoot)

            val savedBytes = (config.sourceIso.sizeBytes - newSize).coerceAtLeast(0L)
            onProgress(WindowsBuildProgress("Done!", outName, 1f, false))
            ActionResult(
                success = true,
                userMessage = "Created $outName (${UsbGadgetEngine.formatBytes(newSize)} · saved ${UsbGadgetEngine.formatBytes(savedBytes)}).",
                createdPath = outPath
            )
        }.getOrElse { e ->
            cleanupAll()
            val msg = if (BuildThermalHelper.isBuildCancelled()) "ISO slimming cancelled." else "ISO Slimmer failed: ${e.message ?: "Unexpected error"}"
            ActionResult(false, msg)
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
    completedMessage: String? = null,
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
    var eiCfgTouchedByUser by remember(isoFile.path) { mutableStateOf(false) }
    var experience by remember(isoFile.path) { mutableStateOf(WindowsExperienceConfig.cleanStock()) }
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
        if (!eiCfgTouchedByUser) {
            eiCfgUnlock = editions.size > 1 && selectedIndices.size < editions.size
        }
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

            if (!building && !completedMessage.isNullOrBlank()) {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = PocketColors.successBg.copy(alpha = 0.55f),
                    border = BorderStroke(1.dp, PocketColors.success)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(15.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(Icons.Rounded.CheckCircle, null, tint = PocketColors.success, modifier = Modifier.size(24.dp))
                        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text("Custom Slim ISO Ready", style = MaterialTheme.typography.titleMedium, color = PocketColors.success)
                            Text(completedMessage, style = MaterialTheme.typography.bodySmall, color = PocketColors.textPrimary)
                        }
                    }
                }
                Button(
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth().height(PocketTokens.PrimaryButtonHeight),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = PocketColors.success, contentColor = PocketColors.onAccent)
                ) {
                    Icon(Icons.Rounded.CheckCircle, null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Done & Dismiss", fontWeight = FontWeight.SemiBold)
                }
            } else if (building) {
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
                                            if (!eiCfgTouchedByUser) {
                                                eiCfgUnlock = editions.size > 1 && selectedIndices.size < editions.size
                                            }
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
                            modifier = Modifier.fillMaxWidth().clickable { eiCfgTouchedByUser = true; eiCfgUnlock = !eiCfgUnlock }.padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Unlock All Editions (ei.cfg)", style = MaterialTheme.typography.labelMedium, color = PocketColors.textPrimary)
                                Text("Auto-enabled when trimming editions so OEM Home laptops install Pro cleanly", fontSize = 11.sp, color = PocketColors.textSecondary)
                            }
                            PocketSwitch(checked = eiCfgUnlock, onCheckedChange = { eiCfgTouchedByUser = true; eiCfgUnlock = it }, activeColor = PocketColors.accent)
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
