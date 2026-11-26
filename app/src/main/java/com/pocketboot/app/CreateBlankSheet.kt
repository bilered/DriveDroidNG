package com.pocketboot.app

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AddCircleOutline
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale
import kotlin.math.roundToInt
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.material.icons.rounded.WarningAmber

private enum class SizeUnit(val label: String) {
    MB("MB"),
    GB("GB")
}

@Composable
fun CreateBlankSheetContent(
    defaultDir: String,
    busy: Boolean,
    onCreate: (dir: String, name: String, sizeMb: Int, mountNow: Boolean) -> Unit
) {
    var fileName by remember { mutableStateOf("") }
    var selectedPresetMb by remember { mutableStateOf(1024) }
    var isCustomSize by remember { mutableStateOf(false) }
    var customSizeText by remember { mutableStateOf("2") }
    var customUnit by remember { mutableStateOf(SizeUnit.GB) }
    var mountImmediately by remember { mutableStateOf(true) }

    val trimmedName = fileName.trim()
    val isNameValid = trimmedName.isNotEmpty() && !trimmedName.contains('/') && !trimmedName.contains('\\')

    val effectiveSizeMb = if (isCustomSize) {
        val rawNum = customSizeText.toDoubleOrNull() ?: 0.0
        val mb = if (customUnit == SizeUnit.GB) (rawNum * 1024.0).roundToInt() else rawNum.roundToInt()
        mb.coerceIn(16, 65536)
    } else {
        selectedPresetMb
    }
    val customValid = !isCustomSize || ((customSizeText.toDoubleOrNull() ?: 0.0) > 0.0)

    val displaySize = if (effectiveSizeMb >= 1024) {
        String.format(Locale.US, "%.2f GB", effectiveSizeMb / 1024.0)
            .replace(Regex("(\\.\\d*?[1-9])0+ GB$"), "$1 GB")
            .replace(".00 GB", " GB")
    } else {
        "$effectiveSizeMb MB"
    }
    val finalName = UsbGadgetEngine.sanitizeOutputFileName(trimmedName, ".img", "blank-usb")
    var targetExists by remember(defaultDir) { mutableStateOf(false) }
    LaunchedEffect(defaultDir, finalName, isNameValid) {
        targetExists = if (isNameValid) UsbGadgetEngine.fileExistsInDir(defaultDir, finalName) else false
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .imePadding()
            .padding(horizontal = 20.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        PocketSheetHeader(
            icon = Icons.Rounded.AddCircleOutline,
            tint = PocketColors.infoBlue,
            iconBg = PocketColors.infoBlueBg,
            title = "Create Blank USB Drive",
            subtitle = "Saves into ${UsbGadgetEngine.friendlyFolderName(defaultDir)} · Sparse allocation"
        )

        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            PocketSectionHeader("Drive Name")
            OutlinedTextField(
                value = fileName,
                onValueChange = { fileName = it },
                placeholder = { Text("e.g. backup-usb") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                suffix = { Text(".img", style = MaterialTheme.typography.labelMedium, color = PocketColors.accentSoft) },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = PocketColors.accent,
                    unfocusedBorderColor = PocketColors.border,
                    focusedContainerColor = PocketColors.surfaceInput,
                    unfocusedContainerColor = PocketColors.surfaceInput
                )
            )
        }

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            PocketSectionHeader("Drive Size", trailing = displaySize)
            val presets = listOf(
                "512 MB" to 512,
                "1 GB" to 1024,
                "4 GB" to 4096,
                "8 GB" to 8192,
                "16 GB" to 16384,
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

            // Custom Size Input with MB / GB Switch
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
                            val filtered = if (customUnit == SizeUnit.GB) {
                                input.filter { it.isDigit() || it == '.' }
                            } else {
                                input.filter { it.isDigit() }
                            }
                            customSizeText = filtered
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
                            unfocusedContainerColor = PocketColors.surfaceInput
                        )
                    )

                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = PocketColors.surfaceInput,
                        border = BorderStroke(0.5.dp, PocketColors.border)
                    ) {
                        Row(modifier = Modifier.padding(4.dp)) {
                            SizeUnit.entries.forEach { unit ->
                                val active = customUnit == unit
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = if (active) PocketColors.accent else PocketColors.surfaceInput,
                                    modifier = Modifier.bounceClick {
                                        if (customUnit != unit) {
                                            val currentVal = customSizeText.toDoubleOrNull()
                                            customUnit = unit
                                            if (currentVal != null && currentVal > 0.0) {
                                                customSizeText = if (unit == SizeUnit.GB) {
                                                    String.format(Locale.US, "%.1f", (currentVal / 1024.0).coerceAtLeast(0.5))
                                                        .removeSuffix(".0")
                                                } else {
                                                    (currentVal * 1024.0).roundToInt().toString()
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

        Surface(
            shape = RoundedCornerShape(10.dp),
            color = PocketColors.surfaceInput,
            border = BorderStroke(0.5.dp, PocketColors.border)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(11.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(Icons.Rounded.Info, null, tint = PocketColors.accentSoft, modifier = Modifier.size(16.dp))
                Text(
                    text = "New blank drives are unformatted—format them as FAT32, exFAT, or NTFS on your PC when first plugged in.",
                    style = MaterialTheme.typography.bodySmall,
                    color = PocketColors.textSecondary
                )
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
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Mount immediately", style = MaterialTheme.typography.labelLarge, color = PocketColors.textPrimary)
                    Text("Mount as Writable USB once created", style = MaterialTheme.typography.bodySmall, color = PocketColors.textSecondary)
                }
                Spacer(modifier = Modifier.width(8.dp))
                PocketSwitch(
                    checked = mountImmediately,
                    onCheckedChange = { mountImmediately = it }
                )
            }
        }

        if (targetExists) {
            Surface(shape = RoundedCornerShape(10.dp), color = PocketColors.warningBg, border = BorderStroke(0.5.dp, PocketColors.warning)) {
                Row(modifier = Modifier.fillMaxWidth().padding(10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.WarningAmber, null, tint = PocketColors.warning, modifier = Modifier.size(16.dp))
                    Text("$finalName already exists in this folder and will be overwritten.", style = MaterialTheme.typography.bodySmall, color = PocketColors.textPrimary)
                }
            }
        }

        Button(
            onClick = { onCreate(defaultDir, finalName, effectiveSizeMb, mountImmediately) },
            enabled = !busy && isNameValid && customValid,
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = PocketColors.accent,
                contentColor = PocketColors.onAccent
            )
        ) {
            if (busy) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = PocketColors.onAccent)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Creating...", fontWeight = FontWeight.SemiBold)
            } else {
                Icon(Icons.Rounded.AddCircleOutline, null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(if (mountImmediately) "Create & Mount" else "Create Blank Drive", fontWeight = FontWeight.SemiBold)
            }
        }

        Spacer(modifier = Modifier.height(24.dp))
    }
}
