package com.mp3tify.playback

import android.app.Activity
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.lang.ref.WeakReference

/**
 * Temporizador de sueño centralizado para que no se pierda al navegar entre pantallas.
 */
object SleepTimerManager {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var timerJob: Job? = null
    private var activityRef: WeakReference<Activity>? = null

    private val _activeMinutes = MutableStateFlow(0)
    val activeMinutes: StateFlow<Int> = _activeMinutes.asStateFlow()

    fun attachActivity(activity: Activity) {
        activityRef = WeakReference(activity)
    }

    fun start(context: Context, minutes: Int) {
        val appCtx = context.applicationContext
        timerJob?.cancel()
        _activeMinutes.value = minutes
        timerJob = scope.launch {
            delay(minutes * 60_000L)
            PlaybackController.pause()
            _activeMinutes.value = 0
            WakeLockManager.turnOffScreen(activityRef?.get(), appCtx)
        }
    }

    fun cancel() {
        timerJob?.cancel()
        timerJob = null
        _activeMinutes.value = 0
    }
}
