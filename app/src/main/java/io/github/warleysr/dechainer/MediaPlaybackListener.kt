package io.github.warleysr.dechainer

import android.content.ComponentName
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import io.github.warleysr.dechainer.data.MusicLimitTracker

/**
 * Listens for media playback so music listened to in the background counts toward its app's limit.
 * It only reacts to playback changes, so it costs nothing while nothing is playing.
 *
 * Android only lets an app read media sessions when it has notification access, which the user
 * grants in system settings.
 */
class MediaPlaybackListener : NotificationListenerService() {

    private val handler = Handler(Looper.getMainLooper())
    private val watchers = HashMap<String, Watcher>()

    private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
        watch(controllers.orEmpty())
    }

    private inner class Watcher(val controller: MediaController) : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) {
            val playing = state?.state == PlaybackState.STATE_PLAYING
            MusicLimitTracker.onPlaybackChanged(applicationContext, controller.packageName, controller, playing)
        }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        val manager = getSystemService(MediaSessionManager::class.java)
        val component = ComponentName(this, MediaPlaybackListener::class.java)
        manager.addOnActiveSessionsChangedListener(sessionsListener, component, handler)
        watch(manager.getActiveSessions(component))
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        getSystemService(MediaSessionManager::class.java)
            .removeOnActiveSessionsChangedListener(sessionsListener)
        watchers.values.forEach { it.controller.unregisterCallback(it) }
        watchers.clear()
        MusicLimitTracker.onListenerLost(applicationContext)
    }

    /** Keeps one callback per app with an active session, and drops the ones that went away. */
    private fun watch(controllers: List<MediaController>) {
        val current = controllers.associateBy { it.packageName }

        for (pkg in watchers.keys.toList()) {
            if (pkg !in current) {
                val gone = watchers.remove(pkg) ?: continue
                gone.controller.unregisterCallback(gone)
                MusicLimitTracker.onPlaybackChanged(applicationContext, pkg, null, false)
            }
        }

        for ((pkg, controller) in current) {
            if (watchers.containsKey(pkg)) continue
            val watcher = Watcher(controller)
            watchers[pkg] = watcher
            controller.registerCallback(watcher, handler)
            watcher.onPlaybackStateChanged(controller.playbackState)
        }
    }
}
