/* Nuvix Copyright (C) 2026 Nuvix Contributors. GPL-3.0-or-later. */
package app.opentv.multiview

import android.app.ActivityManager
import android.content.Context
import android.media.MediaCodecList
import android.os.Build

data class MultiViewHardwareCapability(val maximumPlayers: Int, val message: String, val verified: Boolean = false)

/** Estimates admission only; each additional stream must still prove itself at runtime. */
interface MultiViewHardware {
    val capability: MultiViewHardwareCapability
    fun decoderFailed(workingPlayers: Int) {}
}

class AndroidMultiViewHardware(context: Context) : MultiViewHardware {
    private val memory = context.applicationContext.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    private var runtimeMaximum = 4
    private val estimate: Int = runCatching {
        if (memory.isLowRamDevice || memory.memoryClass < 128) return@runCatching 2
        val h264 = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter { codec ->
            !codec.isEncoder && codec.supportedTypes.any { it.equals("video/avc", true) } &&
                if (Build.VERSION.SDK_INT >= 29) codec.isHardwareAccelerated else
                    !codec.name.startsWith("OMX.google.") && !codec.name.startsWith("c2.android.")
        }
        // Do not sum codec aliases; these commonly describe the same physical engine.
        val advertised = h264.maxOfOrNull { it.getCapabilitiesForType("video/avc").maxSupportedInstances } ?: 1
        minOf(advertised, if (memory.memoryClass < 256) 3 else 4).coerceIn(1, 4)
    }.getOrDefault(2)
    override val capability: MultiViewHardwareCapability get() {
        val maximum = minOf(estimate, runtimeMaximum)
        return MultiViewHardwareCapability(maximum,
            "Up to $maximum panels estimated. Codec profile, resolution, surfaces and device load may reduce capacity; channels start one at a time.")
    }
    override fun decoderFailed(workingPlayers: Int) { runtimeMaximum = minOf(runtimeMaximum, workingPlayers.coerceAtLeast(1)) }
}
