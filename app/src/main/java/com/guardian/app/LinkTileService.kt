package com.guardian.app

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/** v1.13 Quick Settings tile "Check link": checks the link you just copied. */
class LinkTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        qsTile?.apply {
            state = Tile.STATE_INACTIVE
            label = "Check link"
            if (Build.VERSION.SDK_INT >= 29) subtitle = "Copied link"
            updateTile()
        }
    }

    override fun onClick() {
        super.onClick()
        val i = Intent(this, LinkCheckActivity::class.java)
            .setAction(LinkCheckActivity.ACTION_CHECK_CLIPBOARD)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 1, i, PendingIntent.FLAG_IMMUTABLE))
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(i)
        }
    }
}
