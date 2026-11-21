package com.pocketboot.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

object NotificationHelper {
    const val CHANNEL_ID = "pocketboot_usb_status"
    const val NOTIF_ID = 1001

    const val DL_CHANNEL_ID = "pocketboot_turbo_download"
    const val DL_NOTIF_ID = 1002
    const val DL_DONE_NOTIF_ID = 1003

    const val ACTION_DL_PAUSE = "com.pocketboot.app.ACTION_DL_PAUSE"
    const val ACTION_DL_RESUME = "com.pocketboot.app.ACTION_DL_RESUME"
    const val ACTION_DL_CANCEL = "com.pocketboot.app.ACTION_DL_CANCEL"

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = context.getSystemService(NotificationManager::class.java) ?: return
            val usbChannel = NotificationChannel(
                CHANNEL_ID,
                "Mounted USB Drive",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps the virtual USB drive active and prevents Android USB resets"
                setShowBadge(false)
            }
            val dlChannel = NotificationChannel(
                DL_CHANNEL_ID,
                "ISO Turbo Downloads",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Live progress, speed, and pause/resume controls for ISO downloads"
                setShowBadge(false)
            }
            nm.createNotificationChannel(usbChannel)
            nm.createNotificationChannel(dlChannel)
        }
    }

    private fun hasNotifPerm(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun buildMountedNotification(
        context: Context,
        title: String,
        subtitle: String
    ): Notification {
        ensureChannel(context)
        val openIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openPending = PendingIntent.getActivity(
            context,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val unmountIntent = Intent(context, DisconnectReceiver::class.java)
        val unmountPending = PendingIntent.getBroadcast(
            context,
            1,
            unmountIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_usb)
            .setContentTitle(title)
            .setContentText(subtitle)
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(openPending)
            .addAction(R.drawable.ic_usb, "Unmount All", unmountPending)
            .build()
    }

    fun buildDownloadNotification(context: Context, state: TurboDownloadState?): Notification {
        ensureChannel(context)
        val openIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openPending = PendingIntent.getActivity(
            context,
            10,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(context, DL_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_usb)
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openPending)

        if (state == null) {
            return builder
                .setContentTitle("PocketBoot Turbo Downloader")
                .setContentText("Preparing download...")
                .setProgress(0, 0, true)
                .build()
        }

        val pct = (state.progress * 100f).toInt().coerceIn(0, 100)
        val title = if (state.isPaused) "Paused: ${state.title}" else "${state.title} ($pct%)"
        val detail = when {
            state.isPaused -> "${UsbGadgetEngine.formatBytes(state.downloadedBytes)} / ${UsbGadgetEngine.formatBytes(state.totalBytes)} · Tap Resume"
            state.totalBytes > 0L -> "${FastIsoDownloader.formatSpeed(state.speedBytesPerSec)} · ETA ${FastIsoDownloader.formatEta(state.etaSeconds)} · ${state.activeThreads}x"
            else -> "${UsbGadgetEngine.formatBytes(state.downloadedBytes)} · ${FastIsoDownloader.formatSpeed(state.speedBytesPerSec)}"
        }

        builder
            .setContentTitle(title)
            .setContentText(detail)
            .setProgress(100, pct, state.totalBytes <= 0L)

        if (state.supportsRange) {
            val toggleAction = if (state.isPaused) ACTION_DL_RESUME else ACTION_DL_PAUSE
            val toggleLabel = if (state.isPaused) "Resume" else "Pause"
            val togglePending = PendingIntent.getBroadcast(
                context,
                11,
                Intent(context, TurboDownloadActionReceiver::class.java).setAction(toggleAction),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(R.drawable.ic_usb, toggleLabel, togglePending)
        }

        val cancelPending = PendingIntent.getBroadcast(
            context,
            12,
            Intent(context, TurboDownloadActionReceiver::class.java).setAction(ACTION_DL_CANCEL),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        builder.addAction(R.drawable.ic_usb, "Cancel", cancelPending)

        return builder.build()
    }

    fun updateDownloadProgress(context: Context, state: TurboDownloadState) {
        if (!hasNotifPerm(context)) return
        runCatching {
            val notif = buildDownloadNotification(context, state)
            context.getSystemService(NotificationManager::class.java)?.notify(DL_NOTIF_ID, notif)
        }
    }

    fun showDownloadComplete(context: Context, title: String, subtitle: String) {
        if (!hasNotifPerm(context)) return
        runCatching {
            ensureChannel(context)
            val openIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val openPending = PendingIntent.getActivity(
                context,
                13,
                openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val notif = NotificationCompat.Builder(context, DL_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_usb)
                .setContentTitle("Downloaded: $title")
                .setContentText(subtitle)
                .setAutoCancel(true)
                .setContentIntent(openPending)
                .build()
            context.getSystemService(NotificationManager::class.java)?.notify(DL_DONE_NOTIF_ID, notif)
        }
    }

    fun showMounted(context: Context, fileName: String, modeLabel: String, extraText: String? = null) {
        runCatching {
            if (!hasNotifPerm(context)) return
            val sub = if (extraText.isNullOrBlank()) {
                "$modeLabel • Keep-Alive active"
            } else {
                "$modeLabel • $extraText"
            }
            val notif = buildMountedNotification(context, "Mounted: $fileName", sub)
            context.getSystemService(NotificationManager::class.java)?.notify(NOTIF_ID, notif)
        }
    }

    fun cancel(context: Context) {
        runCatching {
            context.getSystemService(NotificationManager::class.java)?.cancel(NOTIF_ID)
        }
    }
}

class DisconnectReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                UsbKeepAliveService.stop(context)
                UsbGadgetEngine.unmountImage(lunIndex = null)
                NotificationHelper.cancel(context)
            } finally {
                pendingResult.finish()
            }
        }
    }
}

class TurboDownloadActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val isWinBuild = WindowsBuildEngine.jobState.value != null
        when (intent?.action) {
            NotificationHelper.ACTION_DL_PAUSE -> {
                if (isWinBuild) WindowsBuildEngine.pauseBuild() else FastIsoDownloader.pause()
            }
            NotificationHelper.ACTION_DL_RESUME -> {
                if (isWinBuild) WindowsBuildEngine.resumeBuild() else FastIsoDownloader.resume()
            }
            NotificationHelper.ACTION_DL_CANCEL -> {
                if (isWinBuild) WindowsBuildEngine.cancelBuild(discardTemp = false) else FastIsoDownloader.cancel(deleteTemp = true)
            }
        }
    }
}
