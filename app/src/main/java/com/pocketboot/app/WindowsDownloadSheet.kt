package com.pocketboot.app

import android.content.ClipData
import android.content.ClipboardManager
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Tag
import androidx.compose.material.icons.rounded.Window
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WindowsDownloadSheet(
    family: WindowsCatalogFamily,
    turboConnections: Int,
    onDismiss: () -> Unit,
    onStartDirectDownload: (title: String, url: String, fileName: String, expectedSha256: String) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var directLoadingReleases by remember(family.id) { mutableStateOf(false) }
    var directReleaseGroups by remember(family.id) { mutableStateOf<List<DirectReleaseGroup>>(emptyList()) }
    var selectedDirectGroup by remember(family.id) { mutableStateOf<DirectReleaseGroup?>(null) }
    var directWantArm64 by remember(family.id) { mutableStateOf(false) }
    var directWin10Arch by remember(family.id) { mutableStateOf("x64") }
    var directSessionId by remember(family.id) { mutableStateOf("") }
    var directSkus by remember(family.id) { mutableStateOf<List<DirectLanguageSku>>(emptyList()) }
    var directSkuLoading by remember(family.id) { mutableStateOf(false) }
    var selectedDirectSku by remember(family.id) { mutableStateOf<DirectLanguageSku?>(null) }
    var directLinks by remember(family.id) { mutableStateOf<List<DirectIsoLink>>(emptyList()) }
    var directLinkLoading by remember(family.id) { mutableStateOf(false) }
    var selectedDirectLink by remember(family.id) { mutableStateOf<DirectIsoLink?>(null) }
    var directError by remember(family.id) { mutableStateOf<String?>(null) }
    var retryNonce by remember(family.id) { mutableStateOf(0) }

    var langMenuOpen by remember { mutableStateOf(false) }
    var langSearch by remember { mutableStateOf("") }

    val activeDirectRelease: DirectWindowsRelease? = remember(selectedDirectGroup, directWantArm64) {
        val grp = selectedDirectGroup ?: return@remember null
        if (directWantArm64 && grp.arm64Release != null) grp.arm64Release else grp.defaultRelease
    }

    fun loadDirectReleases(force: Boolean) {
        scope.launch {
            directLoadingReleases = true
            directError = null
            val raw = WindowsDirectCatalog.fetchReleases(family, forceRefresh = force)
            val groups = WindowsDirectCatalog.groupReleases(raw)
            directReleaseGroups = groups
            selectedDirectGroup = groups.firstOrNull()
            directWantArm64 = false
            directLoadingReleases = false
        }
    }

    LaunchedEffect(family.id) {
        if (directReleaseGroups.isEmpty()) loadDirectReleases(force = false)
    }

    // Fetch languages when active Edition ID changes or user taps Retry
    LaunchedEffect(activeDirectRelease?.editionId, retryNonce) {
        val rel = activeDirectRelease ?: return@LaunchedEffect
        directSkuLoading = true
        directError = null
        directLinks = emptyList()
        selectedDirectLink = null
        val res = WindowsDirectCatalog.fetchLanguages(context, rel)
        if (res.data != null) {
            directSessionId = res.data.first
            directSkus = res.data.second
            selectedDirectSku = directSkus.firstOrNull { it.localizedLanguage.contains("English (United States)", true) }
                ?: directSkus.firstOrNull()
        } else {
            directSkus = emptyList()
            selectedDirectSku = null
            directError = res.errorMessage
        }
        directSkuLoading = false
    }

    // Fetch signed CDN links when language changes
    LaunchedEffect(activeDirectRelease?.editionId, selectedDirectSku?.skuId, retryNonce) {
        val rel = activeDirectRelease ?: return@LaunchedEffect
        val sku = selectedDirectSku ?: return@LaunchedEffect
        if (directSessionId.isBlank()) return@LaunchedEffect
        directLinkLoading = true
        directError = null
        val res = WindowsDirectCatalog.fetchDownloadLinks(context, rel, directSessionId, sku.skuId)
        if (res.data != null) {
            directLinks = res.data
            selectedDirectLink = res.data.firstOrNull { it.arch.equals(directWin10Arch, true) } ?: res.data.firstOrNull()
        } else {
            directLinks = emptyList()
            selectedDirectLink = null
            directError = res.errorMessage
        }
        directLinkLoading = false
    }

    LaunchedEffect(directWin10Arch, directLinks) {
        if (directLinks.size > 1) {
            selectedDirectLink = directLinks.firstOrNull { it.arch.equals(directWin10Arch, true) } ?: directLinks.firstOrNull()
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
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
            PocketSheetHeader(
                icon = Icons.Rounded.Window,
                tint = PocketColors.infoBlue,
                iconBg = PocketColors.infoBlueBg,
                title = family.title,
                subtitle = "Direct Official Retail ISO from Microsoft CDN",
                trailing = {
                    IconButton(onClick = { loadDirectReleases(force = true); retryNonce++ }) {
                        Icon(Icons.Rounded.Refresh, "Refresh", tint = PocketColors.textSecondary)
                    }
                }
            )

            if (directLoadingReleases) {
                Box(modifier = Modifier.fillMaxWidth().padding(vertical = 28.dp), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        CircularProgressIndicator(color = PocketColors.accent)
                        Text("Loading official Windows retail releases...", style = MaterialTheme.typography.bodySmall, color = PocketColors.textSecondary)
                    }
                }
            } else {
                if (directReleaseGroups.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        PocketSectionHeader("Release Version")
                        Row(
                            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(7.dp)
                        ) {
                            directReleaseGroups.forEachIndexed { idx, grp ->
                                val isSel = grp.shortChipLabel == selectedDirectGroup?.shortChipLabel
                                Surface(
                                    modifier = Modifier.bounceClick {
                                        selectedDirectGroup = grp
                                        if (directWantArm64 && grp.arm64Release == null) directWantArm64 = false
                                    },
                                    shape = RoundedCornerShape(10.dp),
                                    color = if (isSel) PocketColors.accentBg else PocketColors.surfaceInput,
                                    border = BorderStroke(if (isSel) 1.dp else 0.5.dp, if (isSel) PocketColors.accent else PocketColors.border)
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                                    ) {
                                        Text(
                                            text = grp.shortChipLabel,
                                            style = MaterialTheme.typography.labelMedium,
                                            fontWeight = if (isSel) FontWeight.SemiBold else FontWeight.Medium,
                                            color = if (isSel) PocketColors.accentSoft else PocketColors.textSecondary
                                        )
                                        if (idx == 0) {
                                            Text("Latest", fontSize = 9.5.sp, fontWeight = FontWeight.SemiBold, color = PocketColors.success)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                selectedDirectGroup?.let { grp ->
                    val hasWin11Pair = grp.x64Release != null && grp.arm64Release != null
                    val isWin10Dual = grp.osFamily == "win10" || grp.osFamily == "win81"
                    if (hasWin11Pair || isWin10Dual) {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            PocketSectionHeader("Architecture")
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                if (hasWin11Pair) {
                                    listOf(false to "x64 (PC / Laptop)", true to "ARM64 (Snapdragon)").forEach { (isArm, lbl) ->
                                        val isSel = directWantArm64 == isArm
                                        Surface(
                                            modifier = Modifier.weight(1f).bounceClick { directWantArm64 = isArm },
                                            shape = RoundedCornerShape(PocketTokens.CompactButtonRadius),
                                            color = if (isSel) PocketColors.infoBlueBg else PocketColors.surfaceInput,
                                            border = BorderStroke(if (isSel) 1.dp else 0.5.dp, if (isSel) PocketColors.infoBlue else PocketColors.border)
                                        ) {
                                            Row(
                                                modifier = Modifier.padding(vertical = 9.dp, horizontal = 8.dp),
                                                horizontalArrangement = Arrangement.Center,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Icon(Icons.Rounded.Memory, null, tint = if (isSel) PocketColors.infoBlue else PocketColors.textMuted, modifier = Modifier.size(15.dp))
                                                Spacer(modifier = Modifier.width(5.dp))
                                                Text(lbl, style = MaterialTheme.typography.labelMedium, color = if (isSel) PocketColors.infoBlue else PocketColors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                            }
                                        }
                                    }
                                } else {
                                    listOf("x64" to "x64 (64-bit)", "x86" to "x86 (32-bit)").forEach { (archCode, lbl) ->
                                        val isSel = directWin10Arch.equals(archCode, true)
                                        Surface(
                                            modifier = Modifier.weight(1f).bounceClick { directWin10Arch = archCode },
                                            shape = RoundedCornerShape(PocketTokens.CompactButtonRadius),
                                            color = if (isSel) PocketColors.infoBlueBg else PocketColors.surfaceInput,
                                            border = BorderStroke(if (isSel) 1.dp else 0.5.dp, if (isSel) PocketColors.infoBlue else PocketColors.border)
                                        ) {
                                            Row(
                                                modifier = Modifier.padding(vertical = 9.dp, horizontal = 8.dp),
                                                horizontalArrangement = Arrangement.Center,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Icon(Icons.Rounded.Memory, null, tint = if (isSel) PocketColors.infoBlue else PocketColors.textMuted, modifier = Modifier.size(15.dp))
                                                Spacer(modifier = Modifier.width(5.dp))
                                                Text(lbl, style = MaterialTheme.typography.labelMedium, color = if (isSel) PocketColors.infoBlue else PocketColors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                activeDirectRelease?.let { rel ->
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Surface(
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp),
                            color = PocketColors.surfaceInput,
                            border = BorderStroke(0.5.dp, PocketColors.border)
                        ) {
                            Row(modifier = Modifier.padding(11.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Icon(Icons.Rounded.Tag, null, tint = PocketColors.infoBlue, modifier = Modifier.size(18.dp))
                                Column {
                                    Text("EDITION ID", fontSize = 10.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.7.sp, color = PocketColors.textMuted)
                                    val badgeArch = if (rel.osFamily == "win10" || rel.osFamily == "win81") directWin10Arch else if (rel.isArm64Only) "ARM64" else "x64"
                                    Text("#${rel.editionId} · $badgeArch", style = PocketColors.monoStyle, color = PocketColors.textPrimary)
                                }
                            }
                        }
                        Surface(
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp),
                            color = PocketColors.surfaceInput,
                            border = BorderStroke(0.5.dp, PocketColors.border)
                        ) {
                            Row(modifier = Modifier.padding(11.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Icon(Icons.Rounded.CalendarMonth, null, tint = PocketColors.success, modifier = Modifier.size(18.dp))
                                Column {
                                    Text("RELEASE", fontSize = 10.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.7.sp, color = PocketColors.textMuted)
                                    Text(rel.releaseLabel.take(24), style = PocketColors.monoStyle, color = PocketColors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                    }
                }

                if (directError != null) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = PocketColors.warningBg,
                        border = BorderStroke(0.75.dp, PocketColors.warning)
                    ) {
                        Column(modifier = Modifier.fillMaxWidth().padding(13.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Icon(Icons.Rounded.ErrorOutline, null, tint = PocketColors.warning, modifier = Modifier.size(19.dp))
                                Text(directError!!, style = MaterialTheme.typography.bodySmall, color = PocketColors.textPrimary)
                            }
                            Button(
                                onClick = { retryNonce++ },
                                shape = RoundedCornerShape(9.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = PocketColors.accent, contentColor = PocketColors.onAccent)
                            ) {
                                Text("Retry Microsoft CDN", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                }

                if (directSkuLoading || directSkus.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        PocketSectionHeader("Language", trailing = if (directSkuLoading) "Connecting..." else "${directSkus.size} available")
                        Box(modifier = Modifier.fillMaxWidth()) {
                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .bounceClick(enabled = !directSkuLoading && directSkus.isNotEmpty()) {
                                        langSearch = ""
                                        langMenuOpen = true
                                    },
                                shape = RoundedCornerShape(12.dp),
                                color = PocketColors.surfaceInput,
                                border = BorderStroke(0.75.dp, PocketColors.accent.copy(alpha = 0.7f))
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 13.dp, vertical = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    if (directSkuLoading) {
                                        CircularProgressIndicator(modifier = Modifier.size(17.dp), strokeWidth = 2.dp, color = PocketColors.accent)
                                        Text("Connecting to Microsoft servers...", style = MaterialTheme.typography.bodySmall, color = PocketColors.textSecondary)
                                    } else {
                                        Icon(Icons.Rounded.Language, null, tint = PocketColors.accentSoft, modifier = Modifier.size(19.dp))
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(selectedDirectSku?.localizedLanguage ?: "Select language", style = MaterialTheme.typography.labelLarge, color = PocketColors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                            Text(
                                                text = if (directLinkLoading) "Generating official 24h CDN URL..." else (selectedDirectLink?.fileName ?: "Official Microsoft CDN"),
                                                style = PocketColors.monoStyle,
                                                color = PocketColors.textMuted,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                        Icon(Icons.Rounded.ExpandMore, null, tint = PocketColors.textSecondary, modifier = Modifier.size(19.dp))
                                    }
                                }
                            }

                            DropdownMenu(
                                expanded = langMenuOpen,
                                onDismissRequest = { langMenuOpen = false },
                                modifier = Modifier.fillMaxWidth(0.86f).background(PocketColors.surfaceCard)
                            ) {
                                if (directSkus.size > 6) {
                                    OutlinedTextField(
                                        value = langSearch,
                                        onValueChange = { langSearch = it },
                                        placeholder = { Text("Search language...", fontSize = 12.sp) },
                                        leadingIcon = { Icon(Icons.Rounded.Search, null, modifier = Modifier.size(16.dp)) },
                                        singleLine = true,
                                        modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp),
                                        shape = RoundedCornerShape(10.dp)
                                    )
                                }
                                directSkus.filter {
                                    langSearch.isBlank() || it.localizedLanguage.contains(langSearch.trim(), true) || it.language.contains(langSearch.trim(), true)
                                }.forEach { sku ->
                                    val isSel = sku.skuId == selectedDirectSku?.skuId
                                    DropdownMenuItem(
                                        text = { Text(sku.localizedLanguage, style = MaterialTheme.typography.labelLarge, color = if (isSel) PocketColors.accentSoft else PocketColors.textPrimary) },
                                        leadingIcon = if (isSel) {
                                            { Icon(Icons.Rounded.Check, null, tint = PocketColors.accentSoft, modifier = Modifier.size(16.dp)) }
                                        } else null,
                                        onClick = {
                                            selectedDirectSku = sku
                                            langMenuOpen = false
                                        }
                                    )
                                }
                            }
                        }
                    }
                }

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                    OutlinedButton(
                        onClick = {
                            val link = selectedDirectLink ?: return@OutlinedButton
                            val cm = context.getSystemService(ClipboardManager::class.java)
                            cm?.setPrimaryClip(ClipData.newPlainText("Windows ISO Link", link.url))
                            Toast.makeText(context, "Copied direct link for external download managers.", Toast.LENGTH_SHORT).show()
                        },
                        enabled = selectedDirectLink != null && !directLinkLoading,
                        modifier = Modifier.weight(0.95f).height(48.dp),
                        shape = RoundedCornerShape(12.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp),
                        border = BorderStroke(0.75.dp, PocketColors.border)
                    ) {
                        Icon(Icons.Rounded.ContentCopy, null, tint = PocketColors.accentSoft, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(5.dp))
                        Text("Copy Link", fontSize = 12.5.sp, color = PocketColors.textPrimary)
                    }

                    Button(
                        onClick = {
                            val link = selectedDirectLink ?: return@Button
                            val relName = activeDirectRelease?.releaseLabel ?: family.title
                            onDismiss()
                            onStartDirectDownload("$relName (${selectedDirectSku?.localizedLanguage ?: link.arch})", link.url, link.fileName, "")
                        },
                        enabled = selectedDirectLink != null && !directLinkLoading,
                        modifier = Modifier.weight(1.35f).height(48.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = PocketColors.accent, contentColor = PocketColors.onAccent)
                    ) {
                        if (directLinkLoading) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = PocketColors.onAccent)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Signing...")
                        } else {
                            Icon(Icons.Rounded.Download, null, modifier = Modifier.size(17.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Turbo (${turboConnections}x)", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(22.dp))
        }
    }
}
