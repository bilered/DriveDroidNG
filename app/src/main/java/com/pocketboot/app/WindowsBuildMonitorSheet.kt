package com.pocketboot.app

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import java.util.Locale

class IsoBuilderService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notif = NotificationHelper.buildIsoBuilderNotification(this, WindowsBuildEngine.conversionState.value)
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) startForeground(NotificationHelper.BUILD_NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            else startForeground(NotificationHelper.BUILD_NOTIF_ID, notif)
        }
        runCatching {
            if (wakeLock == null) wakeLock = getSystemService(PowerManager::class.java)?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PocketBoot::IsoBuilder")?.apply { setReferenceCounted(false) }
            if (wakeLock?.isHeld == false) wakeLock?.acquire(90L * 60L * 1000L)
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() { runCatching { if (wakeLock?.isHeld == true) wakeLock?.release() }; super.onDestroy() }

    companion object {
        fun start(context: Context) { runCatching { ContextCompat.startForegroundService(context, Intent(context, IsoBuilderService::class.java)) } }
        fun stop(context: Context) { runCatching { context.stopService(Intent(context, IsoBuilderService::class.java)) }; NotificationHelper.cancelBuildNotification(context) }
    }
}

class IsoBuilderActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action == NotificationHelper.ACTION_BUILD_CANCEL) WindowsBuildEngine.cancelConversion()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WindowsBuildMonitorSheet(state: WindowsConversionJobState, onDismiss: () -> Unit, onToggleAutoMount: (Boolean) -> Unit, onCancelBuild: () -> Unit) {
    val pctFormatted = String.format(Locale.US, "%.1f%%", (state.overallProgress * 100f).coerceIn(0f, 100f))
    val stepTitles = listOf(1 to "Convert Edition CABs", 2 to "Assemble boot.wim & WinPE", 3 to "Export install.wim/esd + Winre.wim", 4 to "Inject \$OEM\$ & Pack Output")
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = PocketColors.surfaceCard) {
        Column(modifier = Modifier.fillMaxWidth().fillMaxHeight(0.88f).padding(horizontal = 18.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            PocketSheetHeader(icon = Icons.Rounded.Build, tint = PocketColors.accentSoft, iconBg = PocketColors.accentBg, title = state.title, subtitle = "${state.outputFileName} · ${state.outputMode.label}", subtitleMono = true)
            Surface(shape = RoundedCornerShape(14.dp), color = PocketColors.surfaceInput, border = BorderStroke(1.dp, PocketColors.accent.copy(alpha = 0.65f))) {
                Column(modifier = Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(state.stageLabel, style = MaterialTheme.typography.labelLarge, color = PocketColors.textPrimary)
                            Text(state.detailText, style = MaterialTheme.typography.bodySmall, color = PocketColors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Text(pctFormatted, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = PocketColors.accentSoft)
                    }
                    LinearProgressIndicator(progress = { state.overallProgress.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(999.dp)), color = PocketColors.accent, trackColor = PocketColors.surfaceSubtle)
                    if (state.queuedCount > 0) Text("Queue: ${state.queuedCount} waiting (${state.queuedTitles.joinToString(", ")})", style = PocketColors.monoStyle, color = PocketColors.infoBlue, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MonitorMetricBox(Icons.Rounded.Speed, "Disk Speed", if (state.diskSpeedBytesPerSec >= 1024L) FastIsoDownloader.formatSpeed(state.diskSpeedBytesPerSec) else "Computing...", PocketColors.accentSoft, Modifier.weight(1f))
                    MonitorMetricBox(Icons.Rounded.Schedule, "Elapsed / ETA", "${formatDurationShort(state.elapsedSeconds)} · ${if (state.etaSeconds > 0L) FastIsoDownloader.formatEta(state.etaSeconds) else "est..."}", PocketColors.textPrimary, Modifier.weight(1f))
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MonitorMetricBox(Icons.Rounded.Storage, "Output / Free", "${UsbGadgetEngine.formatBytes(state.currentOutputBytes)} · ${UsbGadgetEngine.formatBytes(state.freeSpaceBytes)} free", PocketColors.textPrimary, Modifier.weight(1f))
                    MonitorMetricBox(Icons.Rounded.Memory, "Binary / Battery", "${state.activeBinary.ifBlank { "shell" }} · ${state.batteryPct}%", PocketColors.infoBlue, Modifier.weight(1f))
                }
            }
            Surface(shape = RoundedCornerShape(14.dp), color = PocketColors.surfaceInput, border = BorderStroke(0.5.dp, PocketColors.border)) {
                Column(modifier = Modifier.fillMaxWidth().padding(13.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    Text("PIPELINE TIMELINE", fontSize = 10.5.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp, color = PocketColors.textMuted)
                    stepTitles.forEach { (stepNum, label) ->
                        val isDone = state.currentStep > stepNum; val isCurrent = state.currentStep == stepNum; val stepDur = state.stepDurationsSec[stepNum]
                        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Box(modifier = Modifier.size(22.dp).clip(CircleShape).background(when { isDone -> PocketColors.successBg; isCurrent -> PocketColors.accentBg; else -> PocketColors.surfaceSubtle }), contentAlignment = Alignment.Center) {
                                if (isDone) Icon(Icons.Rounded.CheckCircle, null, tint = PocketColors.success, modifier = Modifier.size(15.dp))
                                else Text("$stepNum", fontSize = 10.5.sp, fontWeight = FontWeight.Bold, color = if (isCurrent) PocketColors.accentSoft else PocketColors.textMuted)
                            }
                            Text(label, style = MaterialTheme.typography.labelMedium, color = when { isDone -> PocketColors.success; isCurrent -> PocketColors.textPrimary; else -> PocketColors.textMuted }, modifier = Modifier.weight(1f))
                            Text(when { stepDur != null -> formatDurationShort(stepDur); isCurrent -> "Active"; else -> "Pending" }, style = PocketColors.monoStyle, color = if (isCurrent) PocketColors.accentSoft else PocketColors.textMuted)
                        }
                    }
                }
            }
            Surface(shape = RoundedCornerShape(14.dp), color = PocketColors.bgCanvas, border = BorderStroke(0.5.dp, PocketColors.border)) {
                Column(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Icon(Icons.Rounded.Terminal, null, tint = PocketColors.accentSoft, modifier = Modifier.size(14.dp))
                        Text("LIVE BINARY OUTPUT", fontSize = 10.5.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp, color = PocketColors.textMuted)
                    }
                    Text(if (state.consoleTail.isEmpty()) "Waiting for native binary output..." else state.consoleTail.takeLast(15).joinToString("\n"), style = PocketColors.monoStyle, fontSize = 10.5.sp, color = PocketColors.textSecondary)
                }
            }
            Surface(shape = RoundedCornerShape(12.dp), color = PocketColors.surfaceInput, border = BorderStroke(0.5.dp, PocketColors.border)) {
                Row(modifier = Modifier.fillMaxWidth().clickable { onToggleAutoMount(!state.autoMount) }.padding(horizontal = 13.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Auto-mount when finished", style = MaterialTheme.typography.labelMedium, color = PocketColors.textPrimary)
                        Text("Immediately connects finished drive to USB-C", fontSize = 11.sp, color = PocketColors.textSecondary)
                    }
                    PocketSwitch(checked = state.autoMount, onCheckedChange = onToggleAutoMount, activeColor = PocketColors.accent)
                }
            }
            Row(modifier = Modifier.fillMaxWidth().padding(bottom = 22.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { onCancelBuild(); onDismiss() }, modifier = Modifier.weight(1f).height(48.dp), shape = RoundedCornerShape(12.dp), border = BorderStroke(1.dp, PocketColors.danger)) {
                    Icon(Icons.Rounded.Close, null, tint = PocketColors.danger, modifier = Modifier.size(17.dp)); Spacer(modifier = Modifier.width(5.dp)); Text("Cancel Build", color = PocketColors.danger, fontWeight = FontWeight.SemiBold)
                }
                Button(onClick = onDismiss, modifier = Modifier.weight(1f).height(48.dp), shape = RoundedCornerShape(12.dp), colors = ButtonDefaults.buttonColors(containerColor = PocketColors.accent, contentColor = PocketColors.onAccent)) { Text("Run in Background", fontWeight = FontWeight.SemiBold) }
            }
        }
    }
}

@Composable
private fun MonitorMetricBox(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, value: String, valueColor: Color, modifier: Modifier = Modifier) {
    Surface(modifier = modifier, shape = RoundedCornerShape(12.dp), color = PocketColors.surfaceInput, border = BorderStroke(0.5.dp, PocketColors.border)) {
        Column(modifier = Modifier.padding(horizontal = 11.dp, vertical = 9.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                Icon(icon, null, tint = PocketColors.textMuted, modifier = Modifier.size(13.dp)); Text(label, style = MaterialTheme.typography.labelSmall, color = PocketColors.textMuted)
            }
            Text(value, style = PocketColors.monoStyle, color = valueColor, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

fun formatDurationShort(totalSeconds: Long): String {
    val s = totalSeconds.coerceAtLeast(0L); val mins = s / 60L; val rem = s % 60L
    return if (mins >= 60L) "${mins / 60L}h ${mins % 60L}m" else if (mins > 0L) "${mins}m ${rem}s" else "${rem}s"
}
