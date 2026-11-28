package com.mp3tify.ui.components

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Outline
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.mp3tify.MainActivity
import com.mp3tify.R
import com.mp3tify.data.model.Song
import com.mp3tify.data.model.displayArtist
import com.mp3tify.data.model.displayTitle
import com.mp3tify.playback.PlaybackController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

object FloatingMiniPlayer {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var wm: WindowManager? = null
    private var rootView: LinearLayout? = null
    private var collectJob: Job? = null
    private var artJob: Job? = null
    private var hideActionsJob: Job? = null
    private var lastSongId: Long? = null
    private var savedX = -1
    private var savedY = -1
    private var isDarkTheme = true
    private var accentColorArgb = 0xFF32C98B.toInt()

    fun setTheme(dark: Boolean, accentArgb: Int) { isDarkTheme = dark; accentColorArgb = accentArgb }

    fun show(context: Context) {
        val appCtx = context.applicationContext
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(appCtx)) return
        if (rootView != null) return
        val windowManager = appCtx.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return
        wm = windowManager
        val dm = appCtx.resources.displayMetrics
        fun dp(v: Int): Int = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), dm).toInt()
        fun dpF(v: Int): Float = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), dm)
        val isDark = isDarkTheme
        val bgArgb = if (isDark) 0xF016181D.toInt() else 0xF5F7F8FA.toInt()
        val borderArgb = if (isDark) 0x2EFFFFFF else 0x22000000
        val titleArgb = if (isDark) Color.WHITE else 0xFF14161A.toInt()
        val subArgb = if (isDark) 0xFFB0B6C2.toInt() else 0xFF5C626E.toInt()
        val artBgArgb = if (isDark) 0xFF23262E.toInt() else 0xFFE3E6EC.toInt()

        val card = LinearLayout(appCtx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(6))
            background = GradientDrawable().apply { setColor(bgArgb); cornerRadius = dpF(16); setStroke(dp(1), borderArgb) }
            elevation = dpF(10)
        }
        val topRow = LinearLayout(appCtx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(24)).apply { bottomMargin = dp(4) }
        }
        fun topBtn(iconRes: Int, onClick: () -> Unit): ImageButton = ImageButton(appCtx).apply {
            setImageResource(iconRes); setColorFilter(subArgb); setBackgroundColor(Color.TRANSPARENT)
            scaleType = ImageView.ScaleType.FIT_CENTER; setPadding(dp(4), dp(2), dp(4), dp(2))
            layoutParams = LinearLayout.LayoutParams(0, dp(24), 1f)
            setOnClickListener { onClick() }
        }
        topRow.addView(topBtn(R.drawable.ic_pip_settings) {
            hide()
            val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${appCtx.packageName}"))
            else Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${appCtx.packageName}"))
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try { appCtx.startActivity(intent) } catch (_: Exception) {
                try { appCtx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${appCtx.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) {}
            }
        })
        topRow.addView(topBtn(R.drawable.ic_pip_expand) {
            hide()
            try { appCtx.startActivity(Intent(appCtx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)) } catch (_: Exception) {}
        })
        topRow.addView(topBtn(R.drawable.ic_pip_close) { hide() })
        card.addView(topRow)

        val infoRow = LinearLayout(appCtx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        val coverRadius = dpF(8)
        val coverIv = ImageView(appCtx).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(artBgArgb)
            outlineProvider = object : ViewOutlineProvider() { override fun getOutline(view: View, outline: Outline) { outline.setRoundRect(0, 0, view.width, view.height, coverRadius) } }
            clipToOutline = true
            layoutParams = LinearLayout.LayoutParams(dp(42), dp(42)).apply { marginEnd = dp(9) }
        }
        val textCol = LinearLayout(appCtx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val titleTv = TextView(appCtx).apply {
            setTextColor(titleArgb); setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f); setTypeface(null, Typeface.BOLD)
            isSingleLine = true; ellipsize = TextUtils.TruncateAt.END
        }
        val artistTv = TextView(appCtx).apply {
            setTextColor(subArgb); setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            isSingleLine = true; ellipsize = TextUtils.TruncateAt.END
        }
        textCol.addView(titleTv); textCol.addView(artistTv)
        infoRow.addView(coverIv); infoRow.addView(textCol)
        card.addView(infoRow)

        val controlsRow = LinearLayout(appCtx).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) }
        }
        fun ctrlBtn(iconRes: Int, sizeDp: Int, tint: Int, onClick: () -> Unit): ImageButton = ImageButton(appCtx).apply {
            setImageResource(iconRes); setColorFilter(tint); setBackgroundColor(Color.TRANSPARENT)
            scaleType = ImageView.ScaleType.FIT_CENTER; setPadding(dp(4), dp(3), dp(4), dp(3))
            layoutParams = LinearLayout.LayoutParams(0, dp(sizeDp), 1f)
            setOnClickListener { onClick() }
        }
        val btnPrev = ctrlBtn(R.drawable.ic_pip_prev, 30, titleArgb) { PlaybackController.skipToPrevious() }
        val btnPlayPause = ctrlBtn(R.drawable.ic_pip_pause, 34, accentColorArgb) {
            if (PlaybackController.barMeta.value.isPlaying) PlaybackController.pause() else PlaybackController.play()
        }
        val btnNext = ctrlBtn(R.drawable.ic_pip_next, 30, titleArgb) { PlaybackController.skipToNext() }
        controlsRow.addView(btnPrev); controlsRow.addView(btnPlayPause); controlsRow.addView(btnNext)
        card.addView(controlsRow)

        val widthPx = dp(236)
        if (savedX < 0) savedX = (dm.widthPixels - widthPx - dp(16)).coerceAtLeast(dp(8))
        if (savedY < 0) savedY = dp(64)
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
        val lp = WindowManager.LayoutParams(widthPx, WindowManager.LayoutParams.WRAP_CONTENT, type, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.START; x = savedX; y = savedY
        }

        val slop = ViewConfiguration.get(appCtx).scaledTouchSlop
        var startRawX = 0f; var startRawY = 0f; var startWinX = 0; var startWinY = 0; var dragging = false
        val touchHandler = View.OnTouchListener { _, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> { startRawX = ev.rawX; startRawY = ev.rawY; startWinX = lp.x; startWinY = lp.y; dragging = false; true }
                MotionEvent.ACTION_MOVE -> {
                    val dx = ev.rawX - startRawX; val dy = ev.rawY - startRawY
                    if (!dragging && (abs(dx) > slop || abs(dy) > slop)) dragging = true
                    if (dragging) {
                        lp.x = (startWinX + dx.toInt()).coerceIn(0, (dm.widthPixels - widthPx).coerceAtLeast(0))
                        lp.y = (startWinY + dy.toInt()).coerceIn(0, (dm.heightPixels - dp(110)).coerceAtLeast(0))
                        savedX = lp.x; savedY = lp.y
                        try { windowManager.updateViewLayout(card, lp) } catch (_: Exception) {}
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!dragging) {
                        hideActionsJob?.cancel()
                        if (topRow.visibility == View.VISIBLE) topRow.visibility = View.GONE
                        else { topRow.visibility = View.VISIBLE; hideActionsJob = scope.launch { delay(3500); topRow.visibility = View.GONE } }
                    }
                    true
                }
                else -> false
            }
        }
        infoRow.setOnTouchListener(touchHandler)
        card.setOnTouchListener(touchHandler)

        try { windowManager.addView(card, lp); rootView = card } catch (_: Exception) { return }

        fun bindArt(song: Song?) {
            val sid = song?.id
            if (sid == lastSongId && coverIv.drawable != null) return
            lastSongId = sid; artJob?.cancel()
            fun showPlaceholder() { coverIv.scaleType = ImageView.ScaleType.CENTER_INSIDE; coverIv.setColorFilter(subArgb); coverIv.setImageResource(R.drawable.ic_music_note) }
            fun showBmp(bmp: android.graphics.Bitmap) { coverIv.clearColorFilter(); coverIv.scaleType = ImageView.ScaleType.CENTER_CROP; coverIv.setImageBitmap(bmp) }
            if (song == null) { showPlaceholder(); return }
            val primary = song.artUri?.takeIf { it.isNotBlank() } ?: song.filePath
            val cached = if (primary.isNotBlank()) AlbumArtCache.peek(primary, 256) else null
            if (cached != null) { showBmp(cached); return }
            showPlaceholder()
            artJob = scope.launch {
                val bmp = withContext(AlbumArtCache.dispatcher) { try { AlbumArtCache.decode(appCtx, primary, 256, song.filePath) } catch (_: Exception) { null } }
                if (lastSongId == sid && bmp != null) showBmp(bmp)
            }
        }

        collectJob = scope.launch {
            PlaybackController.barMeta.collect { meta ->
                val s = meta.currentSong
                titleTv.text = s?.displayTitle ?: appCtx.getString(R.string.app_name)
                artistTv.text = s?.displayArtist ?: ""
                btnPlayPause.setImageResource(if (meta.isPlaying) R.drawable.ic_pip_pause else R.drawable.ic_pip_play)
                bindArt(s)
            }
        }
    }

    fun hide() {
        hideActionsJob?.cancel(); hideActionsJob = null
        collectJob?.cancel(); collectJob = null
        artJob?.cancel(); artJob = null
        lastSongId = null
        val v = rootView ?: return
        rootView = null
        try { wm?.removeViewImmediate(v) } catch (_: Exception) {}
        wm = null
    }
}
