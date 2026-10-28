/*
 * Copyright 2024 Abdallah Mehiz
 * https://github.com/abdallahmehiz/mpvKt
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package eu.kanade.tachiyomi.ui.player

import eu.kanade.tachiyomi.ui.player.settings.DecoderPreferences
import eu.kanade.tachiyomi.ui.player.utils.Anime4KManager
import `is`.xyz.mpv.MPVLib
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import logcat.LogPriority
import logcat.logcat
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/**
 * Runs every runtime video-option change on one background thread.
 *
 * mpv property writes are synchronous: each one waits for the mpv core and, for
 * renderer options such as sharpen/deband, rebuilds the GPU shader pipeline.
 * Doing that on the UI thread for every slider tick caused dropped UI frames and
 * repeated shader rebuilds. Here, writes are serialised off the main thread and
 * rapid changes to the same property are coalesced so only the latest value is
 * applied (at most one rebuild per [APPLY_INTERVAL_MS]). The final value is
 * always applied, so the resulting picture is identical.
 */
object MpvVideoTuner {
    private const val APPLY_INTERVAL_MS = 50L

    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "AniZen-mpv-video").apply { isDaemon = true }
    }
    private val scope = CoroutineScope(SupervisorJob() + executor.asCoroutineDispatcher())
    private val sequence = AtomicLong()
    private val pendingInts = ConcurrentHashMap<String, MutableStateFlow<Pair<Long, Int>?>>()

    fun post(block: () -> Unit) {
        scope.launch {
            try {
                block()
            } catch (e: Exception) {
                logcat("MpvVideoTuner", LogPriority.WARN) { "mpv video update failed: ${e.message}" }
            }
        }
    }

    /** Applies [value] to [property]; bursts of updates are coalesced to the latest value. */
    fun setInt(property: String, value: Int) {
        val flow = pendingInts.computeIfAbsent(property) { key ->
            MutableStateFlow<Pair<Long, Int>?>(null).also { state ->
                scope.launch {
                    state.filterNotNull().collect { (_, latest) ->
                        try {
                            MPVLib.setPropertyInt(key, latest)
                        } catch (e: Exception) {
                            logcat("MpvVideoTuner", LogPriority.WARN) { "Failed to set $key: ${e.message}" }
                        }
                        delay(APPLY_INTERVAL_MS)
                    }
                }
            }
        }
        // The sequence number makes every request distinct, so a value is re-applied
        // even if mpv was changed elsewhere in the meantime.
        flow.value = sequence.incrementAndGet() to value
    }
}

/**
 * Owns the software video-filter chain (`vf`).
 *
 * The "Use YUV420P" option used to add `format=yuv420p` unconditionally. That forced a
 * per-frame CPU conversion even when the decoder already produced 8-bit 4:2:0 frames
 * (HW copy outputs nv12, which renders identically), and with HW+ (zero-copy
 * mediacodec) the filter could not accept GPU surfaces, so mpv silently fell back to
 * software decoding. The conversion is now only inserted when it actually changes the
 * frames (software frames in another pixel format, e.g. 10-bit), which keeps the
 * picture identical while removing the wasted work.
 */
object VideoChain {
    private const val YUV_LABEL = "@anizen_yuv420p"
    private const val YUV_FILTER = "$YUV_LABEL:format=yuv420p"
    private const val DEBAND_LABEL = "@deband"
    private const val DEBAND_FILTER = "$DEBAND_LABEL:gradfun=radius=12"

    // 8-bit 4:2:0 layouts that the GPU renderer consumes exactly like yuv420p.
    private val NATIVE_420_FORMATS = setOf("nv12", "nv21")

    @Volatile private var yuvActive = false

    @Volatile private var cpuDebandActive = false

    /** Builds the initial `vf` value. Called once while mpv is being initialised. */
    fun initialChain(prefs: DecoderPreferences): String {
        // With hardware decoding requested the decoder outputs GPU surfaces or nv12,
        // neither of which needs the conversion; it is added later if decoding falls
        // back to a software format that does need it.
        yuvActive = prefs.useYUV420P().get() && !prefs.tryHWDecoding().get()
        cpuDebandActive = prefs.videoDebanding().get() == Debanding.CPU
        return listOfNotNull(
            YUV_FILTER.takeIf { yuvActive },
            DEBAND_FILTER.takeIf { cpuDebandActive },
        ).joinToString(",")
    }

    /** Re-evaluates the conversion filter after the decoder output format changed. */
    fun onDecoderOutputChanged(prefs: DecoderPreferences) {
        MpvVideoTuner.post { evaluateYuv(prefs) }
    }

    /** Switches decoder without letting the conversion filter force a software fallback. */
    fun switchDecoder(hwdec: String, prefs: DecoderPreferences) {
        MpvVideoTuner.post {
            if (hwdec != "no") setYuv(false)
            MPVLib.setPropertyString("hwdec", hwdec)
            evaluateYuv(prefs)
        }
    }

    fun setCpuDeband(enabled: Boolean) {
        MpvVideoTuner.post { applyCpuDeband(enabled) }
    }

    /** Equivalent of clearing `vf`: removes the CPU deband and conversion filters. */
    fun clear() {
        MpvVideoTuner.post {
            applyCpuDeband(false)
            setYuv(false)
        }
    }

    private fun evaluateYuv(prefs: DecoderPreferences) {
        if (!prefs.useYUV420P().get()) return
        val format = MPVLib.getPropertyString("video-dec-params/pixelformat") ?: return
        val hwFormat = MPVLib.getPropertyString("video-dec-params/hw-pixelformat")
        val hwdec = MPVLib.getPropertyString("hwdec-current")
        val gpuSurface = format == "mediacodec" || !hwFormat.isNullOrEmpty() || hwdec == "mediacodec"
        when {
            gpuSurface -> setYuv(false)
            format == "yuv420p" -> Unit // Already the target format; avoid a filter rebuild.
            format in NATIVE_420_FORMATS -> setYuv(false)
            else -> setYuv(true)
        }
    }

    private fun setYuv(enabled: Boolean) {
        if (enabled == yuvActive) return
        if (enabled) {
            MPVLib.command(arrayOf("vf", "pre", YUV_FILTER))
        } else {
            MPVLib.command(arrayOf("vf", "remove", YUV_LABEL))
        }
        yuvActive = enabled
    }

    private fun applyCpuDeband(enabled: Boolean) {
        if (enabled == cpuDebandActive) return
        if (enabled) {
            MPVLib.command(arrayOf("vf", "add", DEBAND_FILTER))
        } else {
            MPVLib.command(arrayOf("vf", "remove", DEBAND_LABEL))
        }
        cpuDebandActive = enabled
    }
}

fun applyFilter(filter: VideoFilters, value: Int, prefs: DecoderPreferences) {
    MpvVideoTuner.setInt(filter.mpvProperty, value)
}

fun applyDebandMode(mode: Debanding, prefs: DecoderPreferences) {
    when (mode) {
        Debanding.None -> {
            MpvVideoTuner.post { MPVLib.setPropertyBoolean("deband", false) }
            VideoChain.setCpuDeband(false)
        }
        Debanding.CPU -> {
            MpvVideoTuner.post { MPVLib.setPropertyBoolean("deband", false) }
            VideoChain.setCpuDeband(true)
        }
        Debanding.GPU -> {
            VideoChain.setCpuDeband(false)
            // Push the parameters first and enable the shader last, so the GPU
            // pipeline is rebuilt once with the final settings instead of once per value.
            DebandSettings.entries.forEach {
                MpvVideoTuner.setInt(it.mpvProperty, it.preference(prefs).get())
            }
            MpvVideoTuner.post { MPVLib.setPropertyBoolean("deband", true) }
        }
    }
}

fun applyDebandSetting(setting: DebandSettings, value: Int) {
    MpvVideoTuner.setInt(setting.mpvProperty, value)
}

fun buildVFChain(decoderPreferences: DecoderPreferences): String {
    val useYuv420p = decoderPreferences.useYUV420P().get()

    return if (useYuv420p) {
        "format=yuv420p"
    } else {
        ""
    }
}

fun applyTheme(theme: VideoFilterTheme, prefs: DecoderPreferences) {
    prefs.brightnessFilter().set(theme.brightness)
    prefs.contrastFilter().set(theme.contrast)
    prefs.saturationFilter().set(theme.saturation)
    prefs.gammaFilter().set(theme.gamma)
    prefs.hueFilter().set(theme.hue)
    prefs.sharpenFilter().set(theme.sharpen)

    // Reset deband
    prefs.debandFilter().set(0)
    prefs.grainFilter().set(0)
    prefs.debandThreshold().set(32)
    prefs.debandRange().set(16)

    // Apply direct properties (off the UI thread, coalesced with slider updates)
    MpvVideoTuner.setInt("brightness", theme.brightness)
    MpvVideoTuner.setInt("contrast", theme.contrast)
    MpvVideoTuner.setInt("saturation", theme.saturation)
    MpvVideoTuner.setInt("gamma", theme.gamma)
    MpvVideoTuner.setInt("hue", theme.hue)
    MpvVideoTuner.setInt("sharpen", theme.sharpen)

    // Reset the software chain: drop CPU deband and keep the YUV420P conversion only
    // where it is actually needed (never on hardware surfaces).
    VideoChain.setCpuDeband(false)
    VideoChain.onDecoderOutputChanged(prefs)

    // Reset deband engine properties: disable first, so changing the parameters
    // does not rebuild an active deband shader four times.
    MpvVideoTuner.post { MPVLib.setPropertyBoolean("deband", false) }
    MpvVideoTuner.setInt("deband-iterations", 1)
    MpvVideoTuner.setInt("deband-threshold", 32)
    MpvVideoTuner.setInt("deband-range", 16)
    MpvVideoTuner.setInt("deband-grain", 48)
}

fun applyAnime4K(prefs: DecoderPreferences, manager: Anime4KManager, isInit: Boolean = false) {
    val enabled = prefs.enableAnime4K().get()
    
    // DEFENSIVE: Anime4K is incompatible with gpu-next in current builds
    val gpuNext = prefs.gpuNext().get()
    if (enabled && gpuNext) {
        logcat("Anime4K", LogPriority.WARN) { "Anime4K is incompatible with gpu-next. Skipping." }
        if (!isInit) MPVLib.setPropertyString("glsl-shaders", "")
        return
    }

    val mode = try {
        Anime4KManager.Mode.valueOf(prefs.anime4kMode().get())
    } catch (e: Exception) {
        Anime4KManager.Mode.OFF
    }
    val quality = try {
        Anime4KManager.Quality.valueOf(prefs.anime4kQuality().get())
    } catch (e: Exception) {
        Anime4KManager.Quality.BALANCED
    }

    // Ensure initialization happened
    manager.initialize()

    val chain = if (enabled) manager.getShaderChain(mode, quality) else ""
    logcat("Anime4K", LogPriority.DEBUG) { "Applying Anime4K chain (enabled=$enabled): $chain" }
    
    if (chain.isNotEmpty()) {
        if (isInit) {
            MPVLib.setOptionString("glsl-shaders", chain)
        } else {
            MPVLib.setPropertyString("glsl-shaders", chain)
        }
    } else {
        if (isInit) {
            MPVLib.setOptionString("glsl-shaders", "")
        } else {
            MPVLib.setPropertyString("glsl-shaders", "")
        }
    }
}
