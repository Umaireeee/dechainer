package io.github.warleysr.dechainer

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import io.github.warleysr.dechainer.activities.MainActivity
import io.github.warleysr.dechainer.urge.UrgeIntentToken
import io.github.warleysr.dechainer.urge.UrgeSource

/**
 * The Quick Settings tile (blueprint 6.2, D2): pull the shade down, tap it, and the ongoing urge
 * path starts, from any app, with no question first. It only opens the app with the source in the
 * intent; the app stores the entry and starts the lock before anything else. Ported from the
 * journal's ride tile.
 */
class UrgeTileService : TileService() {
    override fun onStartListening() {
        qsTile?.apply {
            state = Tile.STATE_INACTIVE
            updateTile()
        }
    }

    override fun onClick() {
        val intent = Intent(this, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_URGE_SOURCE, UrgeSource.TILE.name)
            // Proof that this came from the tile and not from another app (MainActivity is exported).
            .putExtra(MainActivity.EXTRA_URGE_TOKEN, UrgeIntentToken.shared.issue(SystemClock.elapsedRealtime()))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(
                PendingIntent.getActivity(this, 31, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            )
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }
}
