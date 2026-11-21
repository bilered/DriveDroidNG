package com.pocketboot.app

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
            val prefs = AppPrefs(applicationContext).load()
            val favName = prefs.favoriteQuickMountPath.substringAfterLast('/').ifBlank { "" }
            qsTile?.apply {
                if (status.isAnyMounted) {
                    state = Tile.STATE_ACTIVE
                    label = "Eject USB"
                    subtitle = status.mountedFile.substringAfterLast('/').ifBlank {
                        status.secondaryMountedFile.substringAfterLast('/').ifBlank { "Tap to unmount" }
                    }
                } else if (favName.isNotEmpty()) {
                    state = Tile.STATE_INACTIVE
                    label = "Mount Favorite"
                    subtitle = favName
                } else {
                    state = Tile.STATE_INACTIVE
                    label = "PocketBoot USB"
                    subtitle = "No favorite set"
                }
                updateTile()
            }
        }
    }

    override fun onClick() {
        super.onClick()
        scope.launch {
            val status = UsbGadgetEngine.getStatus()
            val prefs = AppPrefs(applicationContext).load()

            if (status.isAnyMounted) {
                UsbKeepAliveService.stop(applicationContext)
                UsbGadgetEngine.unmountImage(lunIndex = null)
                NotificationHelper.cancel(applicationContext)
                qsTile?.apply {
                    state = Tile.STATE_INACTIVE
                    label = if (prefs.favoriteQuickMountPath.isNotBlank()) "Mount Favorite" else "PocketBoot USB"
                    subtitle = "USB Restored"
                    updateTile()
                }
            } else if (prefs.favoriteQuickMountPath.isNotBlank()) {
                val path = prefs.favoriteQuickMountPath
                val savedModeStr = prefs.fileModes[path]
                val mode = runCatching {
                    if (!savedModeStr.isNullOrBlank()) MountMode.valueOf(savedModeStr) else MountMode.AUTO
                }.getOrDefault(MountMode.AUTO)

                qsTile?.apply {
                    subtitle = "Mounting..."
                    updateTile()
                }
                val res = UsbGadgetEngine.mountImage(
                    path = path,
                    requestedMode = mode,
                    settings = prefs,
                    lunIndex = 0
                )
                if (res.success) {
                    val fileName = path.substringAfterLast('/')
                    val resolvedMode = mode.resolveForFile(path, prefs.fileModes)
                    UsbKeepAliveService.start(
                        applicationContext,
                        "Mounted: $fileName",
                        "${resolvedMode.shortLabel} · Keep-Alive active",
                        prefs.keepAdbActive
                    )
                    qsTile?.apply {
                        state = Tile.STATE_ACTIVE
                        label = "Eject USB"
                        subtitle = fileName
                        updateTile()
                    }
                } else {
                    qsTile?.apply {
                        state = Tile.STATE_INACTIVE
                        subtitle = "Mount failed"
                        updateTile()
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
