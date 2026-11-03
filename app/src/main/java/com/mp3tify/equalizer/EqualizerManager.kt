package com.mp3tify.equalizer

import android.content.Context
import android.media.AudioManager
import android.media.audiofx.Equalizer
import com.mp3tify.playback.PlaybackController

/** Wrapper around android.media.audiofx.Equalizer attached to the playback session. */
object EqualizerManager {

    private var equalizer: Equalizer? = null
    private var appCtx: Context? = null

    data class EqPreset(val key: String, val gains: IntArray)

    /** 5-band presets in millibels: Plano, Graves, Agudos, Vocal, Dance, Acústico. */
    val PRESETS = listOf(
        EqPreset("eq_flat", intArrayOf(0, 0, 0, 0, 0)),
        EqPreset("eq_bass", intArrayOf(900, 700, 300, 0, 0)),
        EqPreset("eq_treble", intArrayOf(0, 0, 200, 600, 900)),
        EqPreset("eq_vocal", intArrayOf(-200, 100, 500, 300, -100)),
        EqPreset("eq_dance", intArrayOf(700, 400, 0, 400, 600)),
        EqPreset("eq_acoustic", intArrayOf(400, 200, 100, 200, 300))
    )

    /** Store the app context so we can generate a fresh audio session id. */
    fun init(context: Context) {
        appCtx = context.applicationContext
    }

    /**
     * Attach the equalizer to an audio session. CRITICAL FIX (Bug eq no funciona):
     * if PlaybackController's session id is 0 (player not yet ready), generate a
     * fresh one with AudioManager.generateAudioSessionId() and use that.
     */
    fun init(): Boolean {
        return try {
            if (equalizer != null) return true
            var sessionId = PlaybackController.getAudioSessionId()
            if (sessionId == 0) {
                val ctx = appCtx
                if (ctx != null) {
                    val am = ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
                    sessionId = am?.generateAudioSessionId() ?: 0
                }
            }
            if (sessionId == 0) return false
            equalizer = Equalizer(0, sessionId)
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    fun setEnabled(enabled: Boolean) {
        try {
            equalizer?.enabled = enabled
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun applyPreset(preset: EqPreset): Boolean {
        return try {
            val eq = equalizer ?: return false
            val bands = eq.numberOfBands.toInt()
            val range = eq.bandLevelRange
            for (i in 0 until bands) {
                val gain = preset.gains.getOrElse(i) { 0 }
                val clamped = gain.coerceIn(range[0].toInt(), range[1].toInt())
                eq.setBandLevel(i.toShort(), clamped.toShort())
            }
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    fun setBandGain(band: Short, gain: Int) {
        try {
            equalizer?.setBandLevel(band, gain.toShort())
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun getBandCount(): Int =
        try {
            equalizer?.numberOfBands?.toInt() ?: 5
        } catch (e: Exception) {
            5
        }

    fun getMinBandLevel(): Int =
        try {
            equalizer?.bandLevelRange?.get(0)?.toInt() ?: -1500
        } catch (e: Exception) {
            -1500
        }

    fun getMaxBandLevel(): Int =
        try {
            equalizer?.bandLevelRange?.get(1)?.toInt() ?: 1500
        } catch (e: Exception) {
            1500
        }

    fun getBandLevel(band: Short): Int =
        try {
            equalizer?.getBandLevel(band)?.toInt() ?: 0
        } catch (e: Exception) {
            0
        }

    fun getBandFreqLabel(band: Short): String {
        return try {
            val eq = equalizer ?: return ""
            val range = eq.getBandFreqRange(band)
            val centerHz = (range[0].toInt() + range[1].toInt()) / 2 / 1000
            if (centerHz >= 1000) {
                String.format(java.util.Locale.US, "%.1f kHz", centerHz / 1000f)
            } else {
                "$centerHz Hz"
            }
        } catch (e: Exception) {
            ""
        }
    }

    fun release() {
        try {
            equalizer?.release()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        equalizer = null
    }
}
