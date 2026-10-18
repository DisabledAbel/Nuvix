/*
 * Nuvix buffering preferences. Copyright (C) 2026 Nuvix Contributors.
 * Licensed under the GNU General Public License v3.0 or later.
 * Loading threshold behavior follows Media3 DefaultLoadControl, Copyright (C) 2016
 * The Android Open Source Project, licensed under Apache-2.0 (licenses/Apache-2.0.txt).
 */
package app.opentv.player

import android.app.ActivityManager
import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** A bounded reservoir, expressed in milliseconds. All Media3 ordering constraints are enforced. */
data class BufferPolicy(
    val minimumMs: Int,
    val maximumMs: Int,
    val playbackMs: Int,
    val rebufferMs: Int,
) {
    init {
        require(minimumMs in 1_000..60_000) { "Minimum buffer must be between 1,000 and 60,000 ms." }
        require(maximumMs in minimumMs..120_000) { "Maximum buffer must be at least the minimum and at most 120,000 ms." }
        require(playbackMs in 500..30_000) { "Playback threshold must be between 500 and 30,000 ms." }
        require(rebufferMs in 500..30_000) { "Rebuffer threshold must be between 500 and 30,000 ms." }
        require(playbackMs <= minimumMs && rebufferMs <= minimumMs) {
            "Playback and rebuffer thresholds must not exceed the minimum buffer."
        }
    }

    /** Preserve loading hysteresis and a byte target; retain a 500 ms forward floor for DVR. */
    fun shouldContinueLoading(bufferedUs: Long, speed: Float, allocatedBytes: Int, budgetBytes: Int, wasLoading: Boolean): Boolean {
        // The allocator also contains retained DVR samples. A size-only stop with no forward
        // samples deadlocks playback, so always permit a small forward reservoir to refill.
        if ((allocatedBytes >= budgetBytes && bufferedUs >= 500_000) || bufferedUs >= maximumMs * 1_000L) return false
        val minimumUs = (minimumMs * 1_000L * speed.coerceAtLeast(1f)).toLong()
            .coerceAtMost(maximumMs * 1_000L)
        return if (bufferedUs < minimumUs) true else wasLoading
    }

    /** Checks whether duration, speed and live-target conditions permit playback to start. */
    fun shouldStartPlayback(bufferedUs: Long, speed: Float, rebuffering: Boolean, targetLiveOffsetUs: Long?, budgetReached: Boolean): Boolean {
        val thresholdUs = (if (rebuffering) rebufferMs else playbackMs) * 1_000L
        val effectiveThreshold = targetLiveOffsetUs?.let { minOf(thresholdUs, it / 2) } ?: thresholdUs
        return bufferedUs / speed.coerceAtLeast(0.01f) >= effectiveThreshold || budgetReached
    }

    companion object {
        val SMALL = BufferPolicy(5_000, 15_000, 1_000, 2_000)
        val MEDIUM = BufferPolicy(15_000, 60_000, 2_500, 5_000)
        val LARGE = BufferPolicy(30_000, 90_000, 5_000, 8_000)
        val LOW_MEMORY_AUTO = BufferPolicy(8_000, 30_000, 1_500, 3_000)
        val PREVIEW = BufferPolicy(5_000, 15_000, 1_000, 1_500)
    }
}

/** Named buffering presets, including automatic sizing and a custom configuration. */
enum class BufferPreset(val label: String) {
    AUTO("Automatic"), SMALL("Small"), MEDIUM("Medium"), LARGE("Large"), CUSTOM("Custom"),
}

/** Selected buffer preset and the custom timing policy used when applicable. */
data class BufferConfiguration(
    val preset: BufferPreset = BufferPreset.AUTO,
    val custom: BufferPolicy = BufferPolicy.MEDIUM,
) {
    /** Resolves the selected preset to a concrete policy, accounting for constrained devices. */
    fun resolve(lowMemory: Boolean): BufferPolicy = when (preset) {
        BufferPreset.AUTO -> if (lowMemory) BufferPolicy.LOW_MEMORY_AUTO else BufferPolicy.MEDIUM
        BufferPreset.SMALL -> BufferPolicy.SMALL
        BufferPreset.MEDIUM -> BufferPolicy.MEDIUM
        BufferPreset.LARGE -> BufferPolicy.LARGE
        BufferPreset.CUSTOM -> custom
    }
}

/** Local settings; a live player observes this flow and updates its reservoir without rebuilding. */
class BufferSettings private constructor(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("nuvix_buffer", Context.MODE_PRIVATE)
    private val _configuration = MutableStateFlow(read())
    val configuration: StateFlow<BufferConfiguration> = _configuration.asStateFlow()

    /** Persists and publishes the selected buffer configuration. */
    fun set(configuration: BufferConfiguration) {
        prefs.edit()
            .putString("preset", configuration.preset.name)
            .putInt("minimum", configuration.custom.minimumMs)
            .putInt("maximum", configuration.custom.maximumMs)
            .putInt("playback", configuration.custom.playbackMs)
            .putInt("rebuffer", configuration.custom.rebufferMs)
            .apply()
        _configuration.value = configuration
    }

    /** Restores saved buffering preferences with defaults for invalid values. */
    private fun read(): BufferConfiguration = runCatching {
        BufferConfiguration(
            preset = BufferPreset.valueOf(prefs.getString("preset", "AUTO")!!),
            custom = BufferPolicy(
                prefs.getInt("minimum", 15_000), prefs.getInt("maximum", 60_000),
                prefs.getInt("playback", 2_500), prefs.getInt("rebuffer", 5_000),
            ),
        )
    }.getOrDefault(BufferConfiguration())

    companion object {
        @Volatile private var instance: BufferSettings? = null
        /** Returns the process-wide buffer settings store. */
        fun get(context: Context): BufferSettings = instance ?: synchronized(this) {
            instance ?: BufferSettings(context.applicationContext).also { instance = it }
        }

        /** Checks whether the device should use the constrained-memory automatic buffer policy. */
        fun isLowMemory(context: Context): Boolean {
            val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            return manager?.isLowRamDevice == true || (manager?.memoryClass ?: 256) <= 128
        }
    }
}
