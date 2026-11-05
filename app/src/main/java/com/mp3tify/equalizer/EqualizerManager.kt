package com.mp3tify.equalizer

import android.content.Context
import android.media.audiofx.Equalizer
import com.mp3tify.playback.PlaybackController

/** Wrapper around android.media.audiofx.Equalizer attached to the playback session. */
object EqualizerManager {

    private var equalizer: Equalizer? = null
    private var appCtx: Context? = null

    // CRITICAL (Bug EQ no funciona): persistir el estado del usuario para re-aplicarlo
    // cuando el EQ se re-attacha a una nueva sesión (ExoPlayer puede cambiar sessionId
    // tras el primer playback).
    private var userEnabled: Boolean = true
    private var userBandGains: IntArray = IntArray(5) // 0 = sin cambio

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
     * Attach the equalizer to the playback session.
     *
     * CRITICAL FIX (Bug EQ no funciona):
     * NO generar un sessionId alternativo si PlaybackController reporta 0. Eso attachaba
     * el EQ a una sesión vacía distinta de la que ExoPlayer usa, y el EQ no afectaba el
     * audio. Ahora: si PlaybackController.getAudioSessionId() devuelve 0 (player aún no
     * listo), se retorna false y la UI muestra "needs audio" + botón Reintentar. Cuando
     * el player se conecta y commitea su sessionId, refreshState() de PlaybackController
     * llamará a reattachToSession() automáticamente.
     */
    fun init(): Boolean {
        return try {
            if (equalizer != null) return true
            val sessionId = PlaybackController.getAudioSessionId()
            if (sessionId == 0) return false
            attachInternal(sessionId)
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    /**
     * Re-attach the equalizer to a new audio session id (called by PlaybackController when
     * ExoPlayer's audioSessionId changes after first playback). Preserves the user's enabled
     * state and band gains so the EQ stays applied across track changes.
     */
    fun reattachToSession(newSessionId: Int) {
        try {
            if (newSessionId == 0) return
            // Si ya estamos attachados a la misma sesión, no hacer nada
            val currentEq = equalizer
            if (currentEq != null) {
                // Liberar el EQ viejo y crear uno nuevo sobre la nueva sesión
                try { currentEq.enabled = false } catch (_: Exception) {}
                try { currentEq.release() } catch (_: Exception) {}
                equalizer = null
            }
            attachInternal(newSessionId)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * Crea el Equalizer sobre sessionId y re-aplica el estado del usuario (enabled + band gains).
     */
    private fun attachInternal(sessionId: Int) {
        val eq = Equalizer(0, sessionId)
        equalizer = eq
        // Re-aplicar el estado del usuario
        try { eq.enabled = userEnabled } catch (_: Exception) {}
        val bands = eq.numberOfBands.toInt()
        val range = eq.bandLevelRange
        for (i in 0 until bands) {
            val gain = userBandGains.getOrElse(i) { 0 }
            if (gain != 0) {
                val clamped = gain.coerceIn(range[0].toInt(), range[1].toInt())
                try { eq.setBandLevel(i.toShort(), clamped.toShort()) } catch (_: Exception) {}
            }
        }
    }

    fun setEnabled(enabled: Boolean) {
        userEnabled = enabled
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
                // Persistir para re-aplicar tras re-attach
                if (i < userBandGains.size) userBandGains[i] = gain
            }
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    fun setBandGain(band: Short, gain: Int) {
        val idx = band.toInt()
        if (idx in 0 until userBandGains.size) userBandGains[idx] = gain
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
