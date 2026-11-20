package io.github.warleysr.dechainer

import android.content.ComponentName
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import io.github.warleysr.dechainer.data.MusicLimitTracker
import io.github.warleysr.dechainer.data.NotificationGuard
import timber.log.Timber

/**
 * Listens for media playback so music listened to in the background counts toward its app's limit.
 * It only reacts to playback changes, so it costs nothing while nothing is playing.
 *
 * Android only lets an app read media sessions when it has notification access, which the user
 * grants in system settings.
 */
class MediaPlaybackListener : NotificationListenerService() {

    private val handler = Handler(Looper.getMainLooper())
    private val watchers = HashMap<MediaSession.Token, Watcher>()
    private var activeControllers: List<MediaController> = emptyList()

    private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
        watch(controllers.orEmpty())
    }

    private inner class Watcher(val controller: MediaController) : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) {
            notifyPackage(controller.packageName)
        }

        override fun onSessionDestroyed() {
            val token = controller.sessionToken
            watchers.remove(token)
            runCatching { controller.unregisterCallback(this) }
            activeControllers = activeControllers.filterNot { it.sessionToken == token }
            notifyPackage(controller.packageName)
        }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        NotificationGuard.setListenerWanted(applicationContext, true)
        try {
            val manager = getSystemService(MediaSessionManager::class.java) ?: return
            val component = ComponentName(this, MediaPlaybackListener::class.java)
            manager.addOnActiveSessionsChangedListener(sessionsListener, component, handler)
            watch(manager.getActiveSessions(component))
        } catch (e: SecurityException) {
            Timber.w(e, "Notification access unavailable for media session tracking")
        } catch (e: Exception) {
            Timber.e(e, "Failed connecting MediaPlaybackListener")
        }
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        cleanup()
        NotificationGuard.restoreListenerAsync(applicationContext)
    }

    override fun onDestroy() {
        cleanup()
        super.onDestroy()
    }

    private fun cleanup() {
        runCatching {
            getSystemService(MediaSessionManager::class.java)
                ?.removeOnActiveSessionsChangedListener(sessionsListener)
        }
        watchers.values.forEach { watcher ->
            runCatching { watcher.controller.unregisterCallback(watcher) }
        }
        watchers.clear()
        activeControllers = emptyList()
        MusicLimitTracker.onListenerLost(applicationContext)
    }

    /** Keeps a callback per active MediaSession.Token and reports package playback priority-first. */
    private fun watch(controllers: List<MediaController>) {
        val currentTokens = controllers.map { it.sessionToken }.toSet()
        val affectedPackages = HashSet<String>()

        for (token in watchers.keys.toList()) {
            if (token !in currentTokens) {
                val gone = watchers.remove(token) ?: continue
                runCatching { gone.controller.unregisterCallback(gone) }
                affectedPackages += gone.controller.packageName
            }
        }

        activeControllers = controllers
        for (controller in controllers) {
            val token = controller.sessionToken
            if (token !in watchers) {
                val watcher = Watcher(controller)
                watchers[token] = watcher
                runCatching { controller.registerCallback(watcher, handler) }
                affectedPackages += controller.packageName
            }
        }

        affectedPackages.forEach { notifyPackage(it) }
    }

    private fun notifyPackage(pkg: String) {
        val packageControllers = activeControllers.filter {
            it.packageName == pkg && it.sessionToken in watchers
        }
        val playingController = packageControllers.firstOrNull {
            it.playbackState?.state == PlaybackState.STATE_PLAYING
        }
        MusicLimitTracker.onPlaybackChanged(
            applicationContext,
            pkg,
            playingController,
            playingController != null
        )
    }
}
