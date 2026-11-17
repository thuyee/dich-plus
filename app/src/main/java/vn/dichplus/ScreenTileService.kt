package vn.dichplus

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

class ScreenTileService : TileService() {
    override fun onStartListening() = refresh(ScreenService.running)

    private fun refresh(on: Boolean) {
        qsTile?.apply {
            label = "Dịch màn hình"
            state = if (on) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            updateTile()
        }
    }

    override fun onClick() {
        if (ScreenService.running) {
            stopService(Intent(this, ScreenService::class.java))
            refresh(false)
            return
        }
        val i = Intent(this, ProjectionActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, i, PendingIntent.FLAG_IMMUTABLE))
        } else {
            @Suppress("DEPRECATION") startActivityAndCollapse(i)
        }
    }
}
