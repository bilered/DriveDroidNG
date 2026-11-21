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
    val isoFile: ImageFile,
    val outputDir: String,
    val outputName: String,
    val scheme: PartitionScheme = PartitionScheme.GPT,
    val selectedEditionIndex: Int? = null,
    val selectedEditionName: String? = null,
    val bypassRequirements: Boolean = true,
    val bypassMicrosoftAccount: Boolean = true,
    val disableDataCollection: Boolean = true,
    val disableBitLocker: Boolean = true,
    val disableSponsoredApps: Boolean = false,
    val classicContextMenu: Boolean = false,
    val skipWifiSetup: Boolean = true,
    val createLocalUser: Boolean = false,
    val localUsername: String = "",
    val localPassword: String = "",
    val skipOobe: Boolean = false,
    val extraDriversDir: String = "",
    val autoMountAfterBuild: Boolean = true
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

    fun generateAutounattendXml(config: WindowsBuildConfig): String {
        val peCommands = mutableListOf<Pair<String, String>>()
        if (config.bypassRequirements) {
            peCommands.add("Bypass TPM Check" to """reg add "HKLM\SYSTEM\Setup\LabConfig" /v BypassTPMCheck /t REG_DWORD /d 1 /f""")
            peCommands.add("Bypass Secure Boot Check" to """reg add "HKLM\SYSTEM\Setup\LabConfig" /v BypassSecureBootCheck /t REG_DWORD /d 1 /f""")
            peCommands.add("Bypass RAM Check" to """reg add "HKLM\SYSTEM\Setup\LabConfig" /v BypassRAMCheck /t REG_DWORD /d 1 /f""")
            peCommands.add("Bypass CPU Check" to """reg add "HKLM\SYSTEM\Setup\LabConfig" /v BypassCPUCheck /t REG_DWORD /d 1 /f""")
            peCommands.add("Bypass Storage Check" to """reg add "HKLM\SYSTEM\Setup\LabConfig" /v BypassStorageCheck /t REG_DWORD /d 1 /f""")
        }

        val specCommands = mutableListOf<Pair<String, String>>()
        if (config.bypassMicrosoftAccount) {
            specCommands.add("Bypass Microsoft Account (OOBE)" to """reg add "HKLM\SOFTWARE\Microsoft\Windows\CurrentVersion\OOBE" /v BypassNRO /t REG_DWORD /d 1 /f""")
        }
        if (config.disableBitLocker) {
            specCommands.add("Disable Automatic BitLocker" to """reg add "HKLM\SYSTEM\CurrentControlSet\Control\BitLocker" /v PreventDeviceEncryption /t REG_DWORD /d 1 /f""")
        }
        if (config.disableDataCollection) {
            specCommands.add("Disable Telemetry" to """reg add "HKLM\SOFTWARE\Policies\Microsoft\Windows\DataCollection" /v AllowTelemetry /t REG_DWORD /d 0 /f""")
        }
        if (config.disableSponsoredApps) {
            specCommands.add("Disable Consumer Features" to """reg add "HKLM\SOFTWARE\Policies\Microsoft\Windows\CloudContent" /v DisableWindowsConsumerFeatures /t REG_DWORD /d 1 /f""")
        }

        val firstLogonCommands = mutableListOf<Pair<String, String>>()
        if (config.disableSponsoredApps) {
            firstLogonCommands.add("Disable Silent App Installs" to """reg add "HKCU\Software\Microsoft\Windows\CurrentVersion\ContentDeliveryManager" /v SilentInstalledAppsEnabled /t REG_DWORD /d 0 /f""")
        }
        if (config.classicContextMenu) {
            firstLogonCommands.add("Restore Classic Context Menu" to """reg add "HKCU\Software\Classes\CLSID\{86ca1aa0-34aa-4e8b-a509-50c905bae2a2}\InprocServer32" /f /ve""")
        }

        val sb = StringBuilder()
        sb.appendLine("""<?xml version="1.0" encoding="utf-8"?>""")
        sb.appendLine("""<unattend xmlns="urn:schemas-microsoft-com:unattend">""")

        if (peCommands.isNotEmpty() || config.selectedEditionIndex != null) {
            sb.appendLine("""  <settings pass="windowsPE">""")
            sb.appendLine("""    <component name="Microsoft-Windows-Setup" processorArchitecture="amd64" publicKeyToken="31bf3856ad364e35" language="neutral" versionScope="nonSxS" xmlns:wcm="http://schemas.microsoft.com/WMIConfig/2002/State">""")
            if (config.selectedEditionIndex != null) {
                sb.appendLine("""      <ImageInstall>""")
                sb.appendLine("""        <OSImage>""")
                sb.appendLine("""          <InstallFrom>""")
                sb.appendLine("""            <MetaData wcm:action="add">""")
                sb.appendLine("""              <Key>/IMAGE/INDEX</Key>""")
                sb.appendLine("""              <Value>${config.selectedEditionIndex}</Value>""")
                sb.appendLine("""            </MetaData>""")
                sb.appendLine("""          </InstallFrom>""")
                sb.appendLine("""        </OSImage>""")
                sb.appendLine("""      </ImageInstall>""")
                sb.appendLine("""      <UserData>""")
                sb.appendLine("""        <AcceptEula>true</AcceptEula>""")
                sb.appendLine("""        <ProductKey>""")
                sb.appendLine("""          <Key></Key>""")
                sb.appendLine("""          <WillShowUI>Never</WillShowUI>""")
                sb.appendLine("""        </ProductKey>""")
                sb.appendLine("""      </UserData>""")
            }
            if (peCommands.isNotEmpty()) {
                sb.appendLine("""      <RunSynchronous>""")
                peCommands.forEachIndexed { idx, (desc, cmd) ->
                    sb.appendLine("""        <RunSynchronousCommand wcm:action="add">""")
                    sb.appendLine("""          <Order>${idx + 1}</Order>""")
                    sb.appendLine("""          <Description>$desc</Description>""")
                    sb.appendLine("""          <Path>$cmd</Path>""")
                    sb.appendLine("""        </RunSynchronousCommand>""")
                }
                sb.appendLine("""      </RunSynchronous>""")
            }
            sb.appendLine("""    </component>""")
            sb.appendLine("""  </settings>""")
        }

        if (specCommands.isNotEmpty()) {
            sb.appendLine("""  <settings pass="specialize">""")
            sb.appendLine("""    <component name="Microsoft-Windows-Deployment" processorArchitecture="amd64" publicKeyToken="31bf3856ad364e35" language="neutral" versionScope="nonSxS" xmlns:wcm="http://schemas.microsoft.com/WMIConfig/2002/State">""")
            sb.appendLine("""      <RunSynchronous>""")
            specCommands.forEachIndexed { idx, (desc, cmd) ->
                sb.appendLine("""        <RunSynchronousCommand wcm:action="add">""")
                sb.appendLine("""          <Order>${idx + 1}</Order>""")
                sb.appendLine("""          <Description>$desc</Description>""")
                sb.appendLine("""          <Path>$cmd</Path>""")
                sb.appendLine("""        </RunSynchronousCommand>""")
            }
            sb.appendLine("""      </RunSynchronous>""")
            sb.appendLine("""    </component>""")
            sb.appendLine("""  </settings>""")
        }

        val cleanUser = if (config.createLocalUser) config.localUsername.trim().take(20) else ""
        val needOobePass = config.disableDataCollection || config.bypassMicrosoftAccount ||
            config.skipWifiSetup || config.skipOobe || cleanUser.isNotEmpty() || firstLogonCommands.isNotEmpty()

        if (needOobePass) {
            sb.appendLine("""  <settings pass="oobeSystem">""")
            sb.appendLine("""    <component name="Microsoft-Windows-Shell-Setup" processorArchitecture="amd64" publicKeyToken="31bf3856ad364e35" language="neutral" versionScope="nonSxS" xmlns:wcm="http://schemas.microsoft.com/WMIConfig/2002/State">""")
            sb.appendLine("""      <OOBE>""")
            if (config.disableDataCollection || config.skipOobe) {
                sb.appendLine("""        <HideEULAPage>true</HideEULAPage>""")
                sb.appendLine("""        <HideOEMRegistrationScreen>true</HideOEMRegistrationScreen>""")
                sb.appendLine("""        <ProtectYourPC>3</ProtectYourPC>""")
            }
            if (config.bypassMicrosoftAccount || config.skipOobe) {
                sb.appendLine("""        <HideOnlineAccountScreens>true</HideOnlineAccountScreens>""")
            }
            if (config.skipWifiSetup || config.bypassMicrosoftAccount || config.skipOobe) {
                sb.appendLine("""        <HideWirelessSetupInOOBE>true</HideWirelessSetupInOOBE>""")
            }
            if (config.skipOobe) {
                sb.appendLine("""        <SkipMachineOOBE>true</SkipMachineOOBE>""")
                sb.appendLine("""        <SkipUserOOBE>true</SkipUserOOBE>""")
            }
            sb.appendLine("""      </OOBE>""")
            if (cleanUser.isNotEmpty()) {
                val safeUser = xmlEscape(cleanUser)
                val safePass = xmlEscape(config.localPassword.take(32))
                sb.appendLine("""      <UserAccounts>""")
                sb.appendLine("""        <LocalAccounts>""")
                sb.appendLine("""          <LocalAccount wcm:action="add">""")
                sb.appendLine("""            <Name>$safeUser</Name>""")
                sb.appendLine("""            <DisplayName>$safeUser</DisplayName>""")
                sb.appendLine("""            <Group>Administrators</Group>""")
                sb.appendLine("""            <Password>""")
                sb.appendLine("""              <Value>$safePass</Value>""")
                sb.appendLine("""              <PlainText>true</PlainText>""")
                sb.appendLine("""            </Password>""")
                sb.appendLine("""          </LocalAccount>""")
                sb.appendLine("""        </LocalAccounts>""")
                sb.appendLine("""      </UserAccounts>""")
            }
            if (firstLogonCommands.isNotEmpty()) {
                sb.appendLine("""      <FirstLogonCommands>""")
                firstLogonCommands.forEachIndexed { idx, (desc, cmd) ->
                    sb.appendLine("""        <SynchronousCommand wcm:action="add">""")
                    sb.appendLine("""          <Order>${idx + 1}</Order>""")
                    sb.appendLine("""          <Description>$desc</Description>""")
                    sb.appendLine("""          <CommandLine>$cmd</CommandLine>""")
                    sb.appendLine("""        </SynchronousCommand>""")
                }
                sb.appendLine("""      </FirstLogonCommands>""")
            }
            sb.appendLine("""    </component>""")
            sb.appendLine("""  </settings>""")
        }

        sb.appendLine("""</unattend>""")
        return sb.toString()
    }

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

            val anyBypass = config.bypassRequirements || config.bypassMicrosoftAccount ||
                config.disableDataCollection || config.disableBitLocker ||
                config.disableSponsoredApps || config.classicContextMenu ||
                config.skipWifiSetup || (config.createLocalUser && config.localUsername.isNotBlank()) ||
                config.skipOobe || config.selectedEditionIndex != null
            if (anyBypass) {
                onProgress(WindowsBuildProgress("Applying Windows bypasses...", "Writing autounattend.xml", 0.85f, false))
                val xml = generateAutounattendXml(config)
                val tmpXml = File(context.cacheDir, "autounattend.xml")
                tmpXml.writeText(xml, Charsets.UTF_8)
                val qXml = shellQuote(tmpXml.absolutePath)
                val oemPanther = "$TEMP_MNT/sources/\$OEM\$/\$\$/Panther"
                val qOemDir = shellQuote(oemPanther)
                val qOemFile = shellQuote("$oemPanther/unattend.xml")
                Shell.cmd(
                    """
                    cp $qXml $TEMP_MNT/autounattend.xml &&
                    mkdir -p $qOemDir &&
                    cp $qXml $qOemFile
                    """.trimIndent()
                ).exec()
                tmpXml.delete()
                if (config.selectedEditionIndex != null) {
                    val tmpEi = File(context.cacheDir, "ei.cfg")
                    tmpEi.writeText("[Channel]\r\n_Default\r\n[VL]\r\n0\r\n", Charsets.UTF_8)
                    Shell.cmd("mkdir -p $TEMP_MNT/sources && cp ${shellQuote(tmpEi.absolutePath)} $TEMP_MNT/sources/ei.cfg").exec()
                    tmpEi.delete()
                    entries.add(LogEntry(ts, "win-build", "Preselected Windows edition #${config.selectedEditionIndex} (${config.selectedEditionName ?: ""}) + wrote sources/ei.cfg"))
                }
                entries.add(LogEntry(ts, "win-build", "Injected autounattend.xml (TPM=${config.bypassRequirements}, MSA=${config.bypassMicrosoftAccount}, Edition=${config.selectedEditionIndex ?: "ask"}, User=${if (config.createLocalUser) config.localUsername.ifBlank { "none" } else "none"})"))
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
    isoFile: ImageFile,
    defaultDir: String,
    presets: List<AutounattendPreset>,
    building: Boolean,
    progress: WindowsBuildProgress,
    onDismiss: () -> Unit,
    onCancelBuild: () -> Unit,
    onSavePreset: (AutounattendPreset) -> Unit,
    onDeletePreset: (String) -> Unit,
    onStartBuild: (WindowsBuildConfig) -> Unit
) {
    val context = LocalContext.current
    val baseName = remember(isoFile.name) {
        isoFile.name.substringBeforeLast('.').take(36) + "-USB.img"
    }
    var outputName by remember(isoFile.path) { mutableStateOf(baseName) }
    var scheme by remember { mutableStateOf(PartitionScheme.GPT) }

    // Multi-edition ISO detection & preselection
    var editions by remember(isoFile.path) { mutableStateOf<List<WindowsEditionItem>>(emptyList()) }
    var inspectingEditions by remember(isoFile.path) { mutableStateOf(true) }
    var selectedEdition by remember(isoFile.path) { mutableStateOf<WindowsEditionItem?>(null) }
    var editionMenuExpanded by remember { mutableStateOf(false) }

    // Collapsible Windows Experience Dropdown state & toggles
    var experienceExpanded by remember { mutableStateOf(false) }
    var bypassRequirements by remember { mutableStateOf(true) }
    var bypassMsa by remember { mutableStateOf(true) }
    var disablePrivacy by remember { mutableStateOf(true) }
    var disableBitLocker by remember { mutableStateOf(true) }
    var disableSponsoredApps by remember { mutableStateOf(false) }
    var classicContextMenu by remember { mutableStateOf(false) }
    var skipWifiSetup by remember { mutableStateOf(true) }
    var skipOobe by remember { mutableStateOf(false) }
    var createLocalUser by remember { mutableStateOf(false) }
    var localUsername by remember { mutableStateOf("") }
    var localPassword by remember { mutableStateOf("") }
    var autoMount by remember { mutableStateOf(true) }

    var extraDriversDir by remember { mutableStateOf("") }
    var showDriversDirDialog by remember { mutableStateOf(false) }
    var showSavePresetDialog by remember { mutableStateOf(false) }
    var newPresetName by remember { mutableStateOf("") }

    LaunchedEffect(isoFile.path) {
        inspectingEditions = true
        editions = WindowsImageBuilder.inspectWindowsEditions(context, isoFile.path)
        inspectingEditions = false
    }

    val activeTweaksCount = listOf(
        bypassRequirements, bypassMsa, disablePrivacy, disableBitLocker,
        disableSponsoredApps, classicContextMenu, skipWifiSetup, skipOobe,
        createLocalUser && localUsername.isNotBlank()
    ).count { it }

    val estNeeded = remember(isoFile.sizeBytes) {
        val ov = (isoFile.sizeBytes / 8L).coerceAtLeast(384L * 1024L * 1024L)
        isoFile.sizeBytes + ov + (4L * 1024L * 1024L)
    }
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
            verticalArrangement = Arrangement.spacedBy(13.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(42.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(PocketColors.infoBlueBg),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Rounded.Build, null, tint = PocketColors.infoBlue, modifier = Modifier.size(22.dp))
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text("Windows USB Builder", style = MaterialTheme.typography.titleLarge, color = PocketColors.textPrimary)
                    Text(
                        text = "${isoFile.name} · ${UsbGadgetEngine.formatBytes(isoFile.sizeBytes)}",
                        style = PocketColors.monoStyle,
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
                    border = BorderStroke(1.dp, PocketColors.accent)
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
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
                        Text(
                            text = "Keep PocketBoot open while building. Extraction typically takes 2–5 minutes.",
                            style = MaterialTheme.typography.labelSmall,
                            color = PocketColors.textMuted
                        )
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
                    Text("Cancel Build", color = PocketColors.danger, fontWeight = FontWeight.SemiBold)
                }
            } else {
                // Saved Presets Bar
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Custom Presets", style = MaterialTheme.typography.labelLarge, color = PocketColors.textPrimary)
                        TextButton(
                            onClick = { showSavePresetDialog = true },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                        ) {
                            Icon(Icons.Rounded.BookmarkAdd, null, tint = PocketColors.accentSoft, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Save Current", fontSize = 12.sp, color = PocketColors.accentSoft)
                        }
                    }

                    if (presets.isEmpty()) {
                        Text(
                            text = "Save bypass and username profiles for 1-tap reuse.",
                            style = MaterialTheme.typography.bodySmall,
                            color = PocketColors.textMuted
                        )
                    } else {
                        Row(
                            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            presets.forEach { preset ->
                                Surface(
                                    shape = RoundedCornerShape(10.dp),
                                    color = PocketColors.surfaceInput,
                                    border = BorderStroke(0.5.dp, PocketColors.border)
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .clickable {
                                                scheme = runCatching { PartitionScheme.valueOf(preset.schemeName) }.getOrDefault(PartitionScheme.GPT)
                                                bypassRequirements = preset.bypassRequirements
                                                bypassMsa = preset.bypassMicrosoftAccount
                                                disablePrivacy = preset.disableDataCollection
                                                disableBitLocker = preset.disableBitLocker
                                                disableSponsoredApps = preset.disableSponsoredApps
                                                classicContextMenu = preset.classicContextMenu
                                                skipWifiSetup = preset.skipWifiSetup
                                                createLocalUser = preset.createLocalUser
                                                localUsername = preset.localUsername
                                                skipOobe = preset.skipOobe
                                            }
                                            .padding(start = 10.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Text(preset.name, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = PocketColors.textPrimary)
                                        Icon(
                                            imageVector = Icons.Rounded.DeleteOutline,
                                            contentDescription = "Delete preset",
                                            tint = PocketColors.textMuted,
                                            modifier = Modifier.size(16.dp).clickable { onDeletePreset(preset.name) }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // Partition Scheme Selector
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    PocketSectionHeader("Partition Scheme")
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PartitionScheme.entries.forEach { s ->
                            val selected = scheme == s
                            Surface(
                                modifier = Modifier.weight(1f).bounceClick { scheme = s },
                                shape = RoundedCornerShape(12.dp),
                                color = if (selected) PocketColors.accentBg else PocketColors.surfaceInput,
                                border = BorderStroke(if (selected) 1.5.dp else 0.5.dp, if (selected) PocketColors.accent else PocketColors.border)
                            ) {
                                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text(s.label, style = MaterialTheme.typography.labelLarge, color = if (selected) PocketColors.accentSoft else PocketColors.textPrimary)
                                    Text(
                                        text = if (s == PartitionScheme.GPT) "NTFS + UEFI Boot (Win 11)" else "NTFS + Legacy BIOS MBR",
                                        fontSize = 11.sp,
                                        color = PocketColors.textSecondary
                                    )
                                }
                            }
                        }
                    }
                }

                // Multi-Edition Preselection Dropdown (auto-detected from install.wim / install.esd)
                if (inspectingEditions || editions.size > 1) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Windows Edition", style = MaterialTheme.typography.labelLarge, color = PocketColors.textPrimary)
                            if (editions.size > 1) {
                                Text("${editions.size} editions in ISO", style = PocketColors.monoStyle, color = PocketColors.accentSoft)
                            }
                        }
                        Box(modifier = Modifier.fillMaxWidth()) {
                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable(enabled = !inspectingEditions && editions.size > 1) { editionMenuExpanded = true },
                                shape = RoundedCornerShape(12.dp),
                                color = PocketColors.surfaceInput,
                                border = BorderStroke(0.75.dp, if (selectedEdition != null) PocketColors.accent else PocketColors.border)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 13.dp, vertical = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    if (inspectingEditions) {
                                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = PocketColors.accent)
                                        Text("Scanning ISO editions...", style = MaterialTheme.typography.bodySmall, color = PocketColors.textSecondary)
                                    } else {
                                        Icon(Icons.Rounded.Window, null, tint = PocketColors.infoBlue, modifier = Modifier.size(18.dp))
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = selectedEdition?.name ?: "Ask during Windows Setup (Default)",
                                                style = MaterialTheme.typography.labelMedium,
                                                color = PocketColors.textPrimary,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            Text(
                                                text = if (selectedEdition != null) "Preselected · Index #${selectedEdition!!.index} (skips edition screen)" else "Tap to preselect a specific edition",
                                                fontSize = 11.sp,
                                                color = PocketColors.textSecondary
                                            )
                                        }
                                        Icon(Icons.Rounded.ExpandMore, null, tint = PocketColors.textSecondary, modifier = Modifier.size(18.dp))
                                    }
                                }
                            }

                            DropdownMenu(
                                expanded = editionMenuExpanded,
                                onDismissRequest = { editionMenuExpanded = false },
                                modifier = Modifier.background(PocketColors.surfaceCard)
                            ) {
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            "Ask during Windows Setup (Default)",
                                            style = MaterialTheme.typography.labelMedium,
                                            color = if (selectedEdition == null) PocketColors.accentSoft else PocketColors.textPrimary
                                        )
                                    },
                                    leadingIcon = if (selectedEdition == null) {
                                        { Icon(Icons.Rounded.Check, null, tint = PocketColors.accentSoft, modifier = Modifier.size(16.dp)) }
                                    } else null,
                                    onClick = {
                                        selectedEdition = null
                                        editionMenuExpanded = false
                                    }
                                )
                                PocketHairlineDivider()
                                editions.forEach { ed ->
                                    val isSel = selectedEdition?.index == ed.index
                                    DropdownMenuItem(
                                        text = {
                                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                                Text("#${ed.index}", style = PocketColors.monoStyle, color = PocketColors.textMuted)
                                                Text(
                                                    ed.name,
                                                    style = MaterialTheme.typography.labelMedium,
                                                    color = if (isSel) PocketColors.accentSoft else PocketColors.textPrimary
                                                )
                                            }
                                        },
                                        leadingIcon = if (isSel) {
                                            { Icon(Icons.Rounded.Check, null, tint = PocketColors.accentSoft, modifier = Modifier.size(16.dp)) }
                                        } else null,
                                        onClick = {
                                            selectedEdition = ed
                                            editionMenuExpanded = false
                                        }
                                    )
                                }
                            }
                        }
                    }
                }

                // Collapsible Dropdown Menu for Windows Experience Bypasses & Local Admin User
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .animateContentSize(spring(stiffness = Spring.StiffnessMediumLow)),
                    shape = RoundedCornerShape(14.dp),
                    color = PocketColors.surfaceInput,
                    border = BorderStroke(if (experienceExpanded) 1.dp else 0.75.dp, if (experienceExpanded) PocketColors.accent else PocketColors.border)
                ) {
                    Column {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .bounceClick { experienceExpanded = !experienceExpanded }
                                .padding(horizontal = 14.dp, vertical = 13.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(34.dp)
                                    .clip(RoundedCornerShape(9.dp))
                                    .background(PocketColors.accentBg),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Rounded.Tune, null, tint = PocketColors.accentSoft, modifier = Modifier.size(18.dp))
                            }
                            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text("Windows Experience & Bypasses", style = MaterialTheme.typography.labelLarge, color = PocketColors.textPrimary)
                                    Surface(shape = RoundedCornerShape(6.dp), color = PocketColors.successBg) {
                                        Text(
                                            text = "$activeTweaksCount on",
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = PocketColors.success
                                        )
                                    }
                                }
                                Text(
                                    text = if (experienceExpanded) "Tap to collapse options" else "TPM, MSA, BitLocker, Bloat, Classic Menu & Local Admin",
                                    fontSize = 11.sp,
                                    color = PocketColors.textSecondary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            Icon(
                                imageVector = if (experienceExpanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                                contentDescription = "Toggle experience options",
                                tint = PocketColors.textSecondary,
                                modifier = Modifier.size(20.dp)
                            )
                        }

                        AnimatedVisibility(
                            visible = experienceExpanded,
                            enter = fadeIn() + expandVertically(),
                            exit = fadeOut() + shrinkVertically()
                        ) {
                            Column {
                                PocketHairlineDivider()
                                RufusToggleRow(
                                    title = "Remove 4GB+ RAM, Secure Boot & TPM 2.0",
                                    subtitle = "LabConfig hardware bypass",
                                    checked = bypassRequirements,
                                    onCheckedChange = { bypassRequirements = it }
                                )
                                PocketHairlineDivider()
                                RufusToggleRow(
                                    title = "Remove online Microsoft Account",
                                    subtitle = "Offline local account (BypassNRO)",
                                    checked = bypassMsa,
                                    onCheckedChange = { bypassMsa = it }
                                )
                                PocketHairlineDivider()
                                RufusToggleRow(
                                    title = "Disable data collection & privacy prompts",
                                    subtitle = "Skip telemetry & EULA prompts",
                                    checked = disablePrivacy,
                                    onCheckedChange = { disablePrivacy = it }
                                )
                                PocketHairlineDivider()
                                RufusToggleRow(
                                    title = "Disable automatic BitLocker encryption",
                                    subtitle = "Prevent forced BitLocker",
                                    checked = disableBitLocker,
                                    onCheckedChange = { disableBitLocker = it }
                                )
                                PocketHairlineDivider()
                                RufusToggleRow(
                                    title = "Disable sponsored apps & consumer bloat",
                                    subtitle = "Blocks auto-installed promo apps",
                                    checked = disableSponsoredApps,
                                    onCheckedChange = { disableSponsoredApps = it }
                                )
                                PocketHairlineDivider()
                                RufusToggleRow(
                                    title = "Restore classic right-click menu",
                                    subtitle = "Full Windows 10 context menu on Win 11",
                                    checked = classicContextMenu,
                                    onCheckedChange = { classicContextMenu = it }
                                )
                                PocketHairlineDivider()
                                RufusToggleRow(
                                    title = "Skip Wi-Fi / Network setup in OOBE",
                                    subtitle = "Stay offline during initial setup",
                                    checked = skipWifiSetup,
                                    onCheckedChange = { skipWifiSetup = it }
                                )
                                PocketHairlineDivider()
                                RufusToggleRow(
                                    title = "Skip OOBE setup screens",
                                    subtitle = "Unattended boot to desktop",
                                    checked = skipOobe,
                                    onCheckedChange = { skipOobe = it }
                                )
                                PocketHairlineDivider()
                                RufusToggleRow(
                                    title = "Create Local Admin User",
                                    subtitle = "Pre-create offline administrator account",
                                    checked = createLocalUser,
                                    onCheckedChange = { createLocalUser = it }
                                )

                                AnimatedVisibility(
                                    visible = createLocalUser,
                                    enter = fadeIn() + expandVertically(),
                                    exit = fadeOut() + shrinkVertically()
                                ) {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(start = 14.dp, end = 14.dp, bottom = 14.dp, top = 4.dp),
                                        verticalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        OutlinedTextField(
                                            value = localUsername,
                                            onValueChange = { localUsername = it.filter { ch -> ch.isLetterOrDigit() || ch == '_' || ch == '-' || ch == ' ' }.take(20) },
                                            label = { Text("Local Admin Username") },
                                            placeholder = { Text("e.g. Admin") },
                                            leadingIcon = { Icon(Icons.Rounded.Person, null, tint = PocketColors.accentSoft, modifier = Modifier.size(18.dp)) },
                                            singleLine = true,
                                            modifier = Modifier.fillMaxWidth(),
                                            shape = RoundedCornerShape(10.dp),
                                            colors = OutlinedTextFieldDefaults.colors(
                                                focusedBorderColor = PocketColors.accent,
                                                unfocusedBorderColor = PocketColors.border,
                                                focusedTextColor = PocketColors.textPrimary,
                                                unfocusedTextColor = PocketColors.textPrimary
                                            )
                                        )
                                        OutlinedTextField(
                                            value = localPassword,
                                            onValueChange = { localPassword = it.take(32) },
                                            label = { Text("Password (optional)") },
                                            placeholder = { Text("Leave blank for no password") },
                                            leadingIcon = { Icon(Icons.Rounded.Lock, null, tint = PocketColors.textMuted, modifier = Modifier.size(17.dp)) },
                                            singleLine = true,
                                            modifier = Modifier.fillMaxWidth(),
                                            shape = RoundedCornerShape(10.dp),
                                            colors = OutlinedTextFieldDefaults.colors(
                                                focusedBorderColor = PocketColors.accent,
                                                unfocusedBorderColor = PocketColors.border,
                                                focusedTextColor = PocketColors.textPrimary,
                                                unfocusedTextColor = PocketColors.textPrimary
                                            )
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // Optional Driver / Post-Install Apps Folder Injection
                Surface(
                    modifier = Modifier.fillMaxWidth().bounceClick { showDriversDirDialog = true },
                    shape = RoundedCornerShape(12.dp),
                    color = PocketColors.surfaceInput,
                    border = BorderStroke(0.75.dp, if (extraDriversDir.isNotBlank()) PocketColors.accent else PocketColors.border)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 13.dp, vertical = 11.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Box(
                            modifier = Modifier.size(34.dp).clip(RoundedCornerShape(9.dp)).background(PocketColors.infoBlueBg),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Rounded.FolderCopy, null, tint = PocketColors.infoBlue, modifier = Modifier.size(18.dp))
                        }
                        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                            Text("Include Driver / Apps Folder", style = MaterialTheme.typography.labelMedium, color = PocketColors.textPrimary)
                            Text(
                                text = if (extraDriversDir.isNotBlank()) {
                                    "Copying into /Drivers: ${UsbGadgetEngine.friendlyFolderName(extraDriversDir)}"
                                } else {
                                    "Optional · inject NVMe, Wi-Fi drivers or setup apps into /Drivers"
                                },
                                fontSize = 11.sp,
                                color = if (extraDriversDir.isNotBlank()) PocketColors.accentSoft else PocketColors.textSecondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        if (extraDriversDir.isNotBlank()) {
                            Icon(
                                imageVector = Icons.Rounded.Close,
                                contentDescription = "Clear drivers folder",
                                tint = PocketColors.textMuted,
                                modifier = Modifier.size(18.dp).clickable { extraDriversDir = "" }
                            )
                        }
                    }
                }

                // Output Image Name
                OutlinedTextField(
                    value = outputName,
                    onValueChange = { outputName = it },
                    label = { Text("Output USB Image Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = PocketColors.accent,
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
                    RufusToggleRow(
                        title = "Mount USB image automatically when done",
                        subtitle = "Auto-mount on LUN 0 when finished",
                        checked = autoMount,
                        onCheckedChange = { autoMount = it }
                    )
                }

                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = if (lowSpace) PocketColors.dangerBg else PocketColors.accentBg,
                    border = BorderStroke(0.5.dp, if (lowSpace) PocketColors.danger else PocketColors.border)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(12.dp),
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
                            text = if (lowSpace) {
                                "Not enough free space in ${UsbGadgetEngine.friendlyFolderName(defaultDir)}. Need ~${UsbGadgetEngine.formatBytes(estNeeded)}, have ${UsbGadgetEngine.formatBytes(freeSpace)}."
                            } else {
                                "Needs ~${UsbGadgetEngine.formatBytes(estNeeded)} in ${UsbGadgetEngine.friendlyFolderName(defaultDir)} (${UsbGadgetEngine.formatBytes(freeSpace)} free)."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = PocketColors.textPrimary
                        )
                    }
                }

                Button(
                    onClick = {
                        onStartBuild(
                            WindowsBuildConfig(
                                isoFile = isoFile,
                                outputDir = defaultDir,
                                outputName = outputName.ifBlank { baseName },
                                scheme = scheme,
                                selectedEditionIndex = selectedEdition?.index,
                                selectedEditionName = selectedEdition?.name,
                                bypassRequirements = bypassRequirements,
                                bypassMicrosoftAccount = bypassMsa,
                                disableDataCollection = disablePrivacy,
                                disableBitLocker = disableBitLocker,
                                disableSponsoredApps = disableSponsoredApps,
                                classicContextMenu = classicContextMenu,
                                skipWifiSetup = skipWifiSetup,
                                createLocalUser = createLocalUser,
                                localUsername = localUsername.trim(),
                                localPassword = localPassword,
                                skipOobe = skipOobe,
                                extraDriversDir = extraDriversDir.trim(),
                                autoMountAfterBuild = autoMount
                            )
                        )
                    },
                    enabled = !lowSpace && outputName.isNotBlank() && (!createLocalUser || localUsername.trim().isNotEmpty()),
                    modifier = Modifier.fillMaxWidth().height(PocketTokens.PrimaryButtonHeight),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = PocketColors.accent, contentColor = PocketColors.onAccent)
                ) {
                    Text("Build Windows USB", fontWeight = FontWeight.SemiBold)
                }
            }

            Spacer(modifier = Modifier.height(22.dp))
        }
    }

    if (showDriversDirDialog) {
        var draftDir by remember(extraDriversDir) { mutableStateOf(extraDriversDir.ifBlank { "/sdcard/Drivers" }) }
        AlertDialog(
            onDismissRequest = { showDriversDirDialog = false },
            containerColor = PocketColors.surfaceCard,
            title = { Text("Include Driver / Apps Folder", color = PocketColors.textPrimary) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Files in this folder will be copied into /Drivers on your bootable Windows USB (ideal for Intel RST/NVMe storage or Wi-Fi drivers).",
                        style = MaterialTheme.typography.bodySmall,
                        color = PocketColors.textSecondary
                    )
                    OutlinedTextField(
                        value = draftDir,
                        onValueChange = { draftDir = it },
                        label = { Text("Folder path") },
                        placeholder = { Text("/sdcard/Drivers") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        extraDriversDir = UsbGadgetEngine.resolveDirectPath(draftDir)
                        showDriversDirDialog = false
                    },
                    enabled = draftDir.trim().isNotEmpty(),
                    colors = ButtonDefaults.buttonColors(containerColor = PocketColors.accent, contentColor = PocketColors.onAccent)
                ) {
                    Text("Set Folder", fontWeight = FontWeight.SemiBold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDriversDirDialog = false }) {
                    Text("Cancel", color = PocketColors.textSecondary)
                }
            }
        )
    }

    if (showSavePresetDialog) {
        AlertDialog(
            onDismissRequest = { showSavePresetDialog = false },
            containerColor = PocketColors.surfaceCard,
            title = { Text("Save Preset", color = PocketColors.textPrimary) },
            text = {
                OutlinedTextField(
                    value = newPresetName,
                    onValueChange = { newPresetName = it.take(28) },
                    label = { Text("Preset Name") },
                    placeholder = { Text("e.g., Win11 Clean Local") },
                    singleLine = true
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        val clean = newPresetName.trim()
                        if (clean.isNotEmpty()) {
                            onSavePreset(
                                AutounattendPreset(
                                    name = clean,
                                    schemeName = scheme.name,
                                    bypassRequirements = bypassRequirements,
                                    bypassMicrosoftAccount = bypassMsa,
                                    disableDataCollection = disablePrivacy,
                                    disableBitLocker = disableBitLocker,
                                    disableSponsoredApps = disableSponsoredApps,
                                    classicContextMenu = classicContextMenu,
                                    skipWifiSetup = skipWifiSetup,
                                    createLocalUser = createLocalUser,
                                    localUsername = localUsername.trim(),
                                    skipOobe = skipOobe
                                )
                            )
                            newPresetName = ""
                            showSavePresetDialog = false
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = PocketColors.accent, contentColor = PocketColors.onAccent)
                ) {
                    Text("Save", fontWeight = FontWeight.SemiBold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showSavePresetDialog = false }) {
                    Text("Cancel", color = PocketColors.textSecondary)
                }
            }
        )
    }
}

@Composable
private fun RufusToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.labelMedium, color = PocketColors.textPrimary)
            Text(subtitle, fontSize = 11.sp, color = PocketColors.textSecondary)
        }
        Spacer(modifier = Modifier.width(10.dp))
        PocketSwitch(checked = checked, onCheckedChange = onCheckedChange, activeColor = PocketColors.success)
    }
}
