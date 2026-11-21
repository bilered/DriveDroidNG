package com.pocketboot.app

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class UnmountTileService : TileService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onStartListening() {
        super.onStartListening()
        scope.launch {
            val status = UsbGadgetEngine.getStatus()
            qsTile?.apply {
                if (status.isAnyMounted) {
                    state = Tile.STATE_ACTIVE; label = "Eject USB"
                    subtitle = status.mountedFile.substringAfterLast('/').ifBlank { status.secondaryMountedFile.substringAfterLast('/').ifBlank { "Tap to eject" } }
                } else { state = Tile.STATE_INACTIVE; label = "PocketBoot"; subtitle = "Tap to open" }
                updateTile()
            }
        }
    }

    override fun onClick() {
        super.onClick()
        scope.launch {
            val status = UsbGadgetEngine.getStatus()
            if (status.isAnyMounted) {
                UsbKeepAliveService.stop(applicationContext); UsbGadgetEngine.unmountImage(null); NotificationHelper.cancel(applicationContext)
                qsTile?.apply { state = Tile.STATE_INACTIVE; label = "PocketBoot"; subtitle = "USB Ejected"; updateTile() }
            } else {
                val intent = Intent(applicationContext, MainActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP }
                runCatching {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) startActivityAndCollapse(PendingIntent.getActivity(applicationContext, 77, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
                    else { @Suppress("DEPRECATION") startActivityAndCollapse(intent) }
                }
            }
        }
    }

    override fun onDestroy() { scope.cancel(); super.onDestroy() }
}
