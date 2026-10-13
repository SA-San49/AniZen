package `is`.xyz.mpv

import android.view.Surface
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Compatibility facade for the static MPVLib API used by the app.
 *
 * mpv-android-lib 0.1.14 exposes an instance-based MPV API. Keeping this
 * facade lets the player migrate its native engine without spreading
 * lifecycle plumbing through every playback control.
 */
object MPVLib {
    private val eventObservers = CopyOnWriteArrayList<EventObserver>()
    private val logObservers = CopyOnWriteArrayList<LogObserver>()

    @Volatile
    private var instance: MPV? = null

    private val eventAdapter = object : MPV.EventObserver {
        override fun eventProperty(property: String) {
            eventObservers.forEach { it.eventProperty(property) }
        }

        override fun eventProperty(property: String, value: Long) {
            eventObservers.forEach { it.eventProperty(property, value) }
        }

        override fun eventProperty(property: String, value: Boolean) {
            eventObservers.forEach { it.eventProperty(property, value) }
        }

        override fun eventProperty(property: String, value: String) {
            eventObservers.forEach { it.eventProperty(property, value) }
        }

        override fun eventProperty(property: String, value: Double) {
            eventObservers.forEach { it.eventProperty(property, value) }
        }

        override fun eventProperty(property: String, value: MPVNode) = Unit

        override fun event(eventId: Int, data: MPVNode) {
            eventObservers.forEach { observer ->
                observer.event(eventId)
                if (eventId == mpvEventId.MPV_EVENT_END_FILE) {
                    val error = data["error"]?.asInt()?.toInt() ?: 0
                    if (error != 0) observer.efEvent(errorMessage(error))
                }
            }
        }
    }

    private val logAdapter = object : MPV.LogObserver {
        override fun logMessage(prefix: String, level: Int, text: String) {
            logObservers.forEach { it.logMessage(prefix, level, text) }
        }
    }

    @Synchronized
    fun bind(mpv: MPV) {
        instance?.let {
            it.removeObserver(eventAdapter)
            it.removeLogObserver(logAdapter)
        }
        instance = mpv
        mpv.addObserver(eventAdapter)
        mpv.addLogObserver(logAdapter)
    }

    @Synchronized
    fun unbind(mpv: MPV) {
        if (instance !== mpv) return
        mpv.removeObserver(eventAdapter)
        mpv.removeLogObserver(logAdapter)
        instance = null
        eventObservers.clear()
        logObservers.clear()
    }

    fun attachSurface(surface: Surface) {
        instance?.attachSurface(surface)
    }

    fun detachSurface() {
        instance?.detachSurface()
    }

    fun command(command: Array<String>) {
        instance?.command(*command)
    }

    fun setOptionString(name: String, value: String): Int =
        instance?.setOptionString(name, value) ?: -3

    fun getPropertyInt(property: String): Int? = instance?.getPropertyInt(property)

    fun setPropertyInt(property: String, value: Int) {
        instance?.setPropertyInt(property, value)
    }

    fun getPropertyDouble(property: String): Double? = instance?.getPropertyDouble(property)

    fun setPropertyDouble(property: String, value: Double) {
        instance?.setPropertyDouble(property, value)
    }

    fun getPropertyBoolean(property: String): Boolean? = instance?.getPropertyBoolean(property)

    fun setPropertyBoolean(property: String, value: Boolean) {
        instance?.setPropertyBoolean(property, value)
    }

    fun getPropertyString(property: String): String? = instance?.getPropertyString(property)

    fun setPropertyString(property: String, value: String) {
        instance?.setPropertyString(property, value)
    }

    fun observeProperty(property: String, format: Int) {
        instance?.observeProperty(property, format)
    }

    fun addObserver(observer: EventObserver) {
        eventObservers.addIfAbsent(observer)
    }

    fun removeObserver(observer: EventObserver) {
        eventObservers.remove(observer)
    }

    fun addLogObserver(observer: LogObserver) {
        logObservers.addIfAbsent(observer)
    }

    fun removeLogObserver(observer: LogObserver) {
        logObservers.remove(observer)
    }

    private fun errorMessage(error: Int): String = when (error) {
        -2 -> "out of memory"
        -4 -> "invalid parameter"
        -7 -> "option error"
        -8 -> "property not found"
        -11 -> "property error"
        -12 -> "command error"
        -13 -> "loading failed"
        -14 -> "audio output initialization failed"
        -15 -> "video output initialization failed"
        -16 -> "nothing to play"
        -17 -> "unknown format"
        -18 -> "unsupported"
        -19 -> "not implemented"
        -20 -> "generic error"
        else -> "mpv error $error"
    }

    interface EventObserver {
        fun eventProperty(property: String)
        fun eventProperty(property: String, value: Long)
        fun eventProperty(property: String, value: Boolean)
        fun eventProperty(property: String, value: String)
        fun eventProperty(property: String, value: Double)
        fun event(eventId: Int)
        fun efEvent(err: String?)
    }

    fun interface LogObserver {
        fun logMessage(prefix: String, level: Int, text: String)
    }

    object mpvFormat {
        const val MPV_FORMAT_NONE = 0
        const val MPV_FORMAT_STRING = 1
        const val MPV_FORMAT_OSD_STRING = 2
        const val MPV_FORMAT_FLAG = 3
        const val MPV_FORMAT_INT64 = 4
        const val MPV_FORMAT_DOUBLE = 5
        const val MPV_FORMAT_NODE = 6
        const val MPV_FORMAT_NODE_ARRAY = 7
        const val MPV_FORMAT_NODE_MAP = 8
        const val MPV_FORMAT_BYTE_ARRAY = 9
    }

    object mpvEventId {
        const val MPV_EVENT_NONE = 0
        const val MPV_EVENT_SHUTDOWN = 1
        const val MPV_EVENT_LOG_MESSAGE = 2
        const val MPV_EVENT_GET_PROPERTY_REPLY = 3
        const val MPV_EVENT_SET_PROPERTY_REPLY = 4
        const val MPV_EVENT_COMMAND_REPLY = 5
        const val MPV_EVENT_START_FILE = 6
        const val MPV_EVENT_END_FILE = 7
        const val MPV_EVENT_FILE_LOADED = 8
        const val MPV_EVENT_IDLE = 11
        const val MPV_EVENT_TICK = 14
        const val MPV_EVENT_CLIENT_MESSAGE = 16
        const val MPV_EVENT_VIDEO_RECONFIG = 17
        const val MPV_EVENT_AUDIO_RECONFIG = 18
        const val MPV_EVENT_SEEK = 20
        const val MPV_EVENT_PLAYBACK_RESTART = 21
        const val MPV_EVENT_PROPERTY_CHANGE = 22
        const val MPV_EVENT_QUEUE_OVERFLOW = 24
        const val MPV_EVENT_HOOK = 25
    }

    object mpvLogLevel {
        const val MPV_LOG_LEVEL_NONE = 0
        const val MPV_LOG_LEVEL_FATAL = 10
        const val MPV_LOG_LEVEL_ERROR = 20
        const val MPV_LOG_LEVEL_WARN = 30
        const val MPV_LOG_LEVEL_INFO = 40
        const val MPV_LOG_LEVEL_V = 50
        const val MPV_LOG_LEVEL_DEBUG = 60
        const val MPV_LOG_LEVEL_TRACE = 70
    }
}
