package eu.kanade.tachiyomi.ui.player

import android.widget.Toast
import eu.kanade.tachiyomi.ui.player.settings.DecoderPreferences
import eu.kanade.tachiyomi.util.system.toast
import `is`.xyz.mpv.MPVLib
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import uy.kohesive.injekt.injectLazy

class PlayerObserver(val activity: PlayerActivity) :
    MPVLib.EventObserver,
    MPVLib.LogObserver {

    private fun postToPlayer(block: () -> Unit) {
        if (activity.player.isExiting || !activity.player.initialized) return
        activity.runOnUiThread {
            if (activity.player.isExiting || !activity.player.initialized) return@runOnUiThread
            try {
                block()
            } catch (e: Exception) {
                // MPV can emit a final callback while a source is being replaced or
                // the Activity is being destroyed. Never let that callback crash the app.
                logcat(LogPriority.ERROR, e) { "Player callback failed" }
            }
        }
    }

    override fun eventProperty(property: String) {
        postToPlayer { activity.onObserverEvent(property) }
    }

    override fun eventProperty(property: String, value: Long) {
        postToPlayer { activity.onObserverEvent(property, value) }
    }

    override fun eventProperty(property: String, value: Boolean) {
        postToPlayer { activity.onObserverEvent(property, value) }
    }

    private val decoderPreferences: DecoderPreferences by injectLazy()

    override fun eventProperty(property: String, value: String) {
        if (property == "video-dec-params/pixelformat" || property == "hwdec-current") {
            // Handled on the player's video thread, not the UI thread.
            if (!activity.player.isExiting && activity.player.initialized) {
                VideoChain.onDecoderOutputChanged(decoderPreferences)
            }
        }
        postToPlayer { activity.onObserverEvent(property, value) }
    }

    override fun eventProperty(property: String, value: Double) {
        postToPlayer { activity.onObserverEvent(property, value) }
    }

    override fun event(eventId: Int) {
        postToPlayer { activity.event(eventId) }
    }

    override fun efEvent(err: String?) {
        if (err == null) return // Ignore normal EOF or file replacement events
        
        var errorMessage = err
        if (!httpError.isNullOrEmpty()) {
            errorMessage += ": $httpError"
            httpError = null
        }
        postToPlayer { activity.onVideoError(errorMessage) }
    }

    private var httpError: String? = null

    override fun logMessage(prefix: String, level: Int, text: String) {
        val logPriority = when (level) {
            MPVLib.mpvLogLevel.MPV_LOG_LEVEL_FATAL, MPVLib.mpvLogLevel.MPV_LOG_LEVEL_ERROR -> LogPriority.ERROR
            MPVLib.mpvLogLevel.MPV_LOG_LEVEL_WARN -> LogPriority.WARN
            MPVLib.mpvLogLevel.MPV_LOG_LEVEL_INFO -> LogPriority.INFO
            else -> LogPriority.VERBOSE
        }
        if (text.contains("HTTP error")) httpError = text
        logcat.logcat("mpv/$prefix", logPriority) { text }

        if (level == MPVLib.mpvLogLevel.MPV_LOG_LEVEL_ERROR || level == MPVLib.mpvLogLevel.MPV_LOG_LEVEL_FATAL ||
            text.contains("Cannot open", ignoreCase = true) || text.contains("failed to open", ignoreCase = true)
        ) {
            postToPlayer { activity.viewModel.handleMpvLogFailure(text) }
        }
    }
}
