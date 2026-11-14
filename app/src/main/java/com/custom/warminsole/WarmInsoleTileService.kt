package com.custom.warminsole

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.core.content.ContextCompat

class WarmInsoleTileService : TileService() {

    companion object {
        fun requestTileUpdate(context: Context) {
            try {
                requestListeningState(
                    context,
                    ComponentName(context, WarmInsoleTileService::class.java)
                )
            } catch (_: Exception) {
            }
        }
    }

    override fun onStartListening() {
        super.onStartListening()
        syncTileVisualState()
    }

    override fun onClick() {
        super.onClick()
        val isActuallyActive = (InsoleKeepAliveService.isConnected1 || InsoleKeepAliveService.isConnected2) &&
            InsoleKeepAliveService.isHeatingOn

        val action = if (isActuallyActive) {
            "CMD_TILE_TOGGLE_OFF"
        } else {
            "CMD_TILE_AUTO_45"
        }

        val intent = Intent(this, InsoleKeepAliveService::class.java).apply {
            this.action = action
        }
        ContextCompat.startForegroundService(this, intent)
        syncTileVisualState()
    }

    private fun syncTileVisualState() {
        val tile = qsTile ?: return
        val isActuallyActive = (InsoleKeepAliveService.isConnected1 || InsoleKeepAliveService.isConnected2) &&
            InsoleKeepAliveService.isHeatingOn

        tile.label = "WarmInsole"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = ""
        }

        if (isActuallyActive) {
            tile.state = Tile.STATE_ACTIVE
            tile.icon = Icon.createWithResource(this, R.drawable.ic_tile_insole_on)
        } else {
            tile.state = Tile.STATE_INACTIVE
            tile.icon = Icon.createWithResource(this, R.drawable.ic_tile_insole_off)
        }

        tile.updateTile()
    }
}
