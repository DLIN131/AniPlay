package com.aniplay.core.player

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.SystemClock
import android.view.GestureDetector
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.annotation.OptIn
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.aniplay.core.net.Anime
import com.aniplay.core.net.Api
import com.aniplay.core.net.HistoryItem
import com.aniplay.core.net.Store
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 全螢幕播放器，手機和電視共用。影片是 HLS（m3u8）。
 *
 * 觸控：點一下顯示／隱藏控制列，雙擊左右半邊快退／快進 10 秒，控制列有上一集、下一集、選集、倍速。
 * 遙控器：左右 = 快退／快進（長按加速），OK = 暫停／播放，長按 OK = 按住期間 2 倍速，
 * 上鍵或選單鍵 = 選集，下鍵 = 顯示進度列，返回 = 離開。看完自動播下一集。
 */
@OptIn(UnstableApi::class)
class PlayerActivity : ComponentActivity() {

    companion object {
        private const val EXTRA_ID = "id"
        private const val EXTRA_NAME = "name"
        private const val EXTRA_COVER = "cover"
        private const val EXTRA_REMARKS = "remarks"
        private const val EXTRA_SOURCE = "source"
        private const val EXTRA_EP_NAMES = "ep_names"
        private const val EXTRA_EP_URLS = "ep_urls"
        private const val EXTRA_EPISODE = "episode"
        private const val SEEK_STEP_MS = 10_000L
        private const val BOOST_SPEED = 2f
        private const val AUTO_REPEAT_GAP_MS = 200L
        private const val OVERLAY_TIMEOUT_MS = 4_000L
        private const val ACCENT = 0xFFFF6B9A.toInt()

        fun start(context: Context, anime: Anime, source: Int, names: List<String>, urls: List<String>, episode: Int) {
            context.startActivity(
                Intent(context, PlayerActivity::class.java)
                    .putExtra(EXTRA_ID, anime.id)
                    .putExtra(EXTRA_NAME, anime.name)
                    .putExtra(EXTRA_COVER, anime.cover)
                    .putExtra(EXTRA_REMARKS, anime.remarks)
                    .putExtra(EXTRA_SOURCE, source)
                    .putExtra(EXTRA_EP_NAMES, names.toTypedArray())
                    .putExtra(EXTRA_EP_URLS, urls.toTypedArray())
                    .putExtra(EXTRA_EPISODE, episode)
            )
        }
    }

    private lateinit var player: ExoPlayer
    private lateinit var anime: Anime
    private var source = 0
    private var names: List<String> = emptyList()
    private var urls: List<String> = emptyList()
    private var episode = 0
    private var ended = false

    private lateinit var controls: View
    private lateinit var titleView: TextView
    private lateinit var status: TextView
    private lateinit var timeText: TextView
    private lateinit var seekBar: SeekBar
    private lateinit var hint: TextView
    private lateinit var playBtn: TextView
    private lateinit var prevBtn: TextView
    private lateinit var nextBtn: TextView
    private lateinit var speedBtn: TextView
    private var hintJob: Job? = null
    private var overlayJob: Job? = null
    private var dragging = false
    private var speed = 1f

    private var seekTarget: Long? = null
    private var okDown = false
    private var boosting = false
    private var lastOkUp = 0L
    private var boostRelease: Job? = null

    private val mediaSourceFactory by lazy {
        val http = OkHttpDataSource.Factory(Api.client).setUserAgent(Api.USER_AGENT)
        DefaultMediaSourceFactory(this).setDataSourceFactory(http)
    }

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (!isPlaying) showOverlay() else updateOverlay()
        }

        override fun onPlaybackStateChanged(state: Int) {
            if (state == Player.STATE_ENDED) {
                ended = true
                Store.savePosition(anime.id, source, episode, 0L)
                if (episode < urls.lastIndex) {
                    showHint("自動播放下一集")
                    playEpisode(episode + 1, 0L)
                } else {
                    showHint("已經是最後一集")
                    showOverlay()
                }
            }
            updateOverlay()
        }

        override fun onPlayerError(error: PlaybackException) {
            showHint("播放失敗：${error.errorCodeName}", long = true)
            showOverlay()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        anime = Anime(
            id = intent.getIntExtra(EXTRA_ID, 0),
            name = intent.getStringExtra(EXTRA_NAME).orEmpty(),
            cover = intent.getStringExtra(EXTRA_COVER).orEmpty(),
            remarks = intent.getStringExtra(EXTRA_REMARKS).orEmpty(),
        )
        source = intent.getIntExtra(EXTRA_SOURCE, 0)
        names = intent.getStringArrayExtra(EXTRA_EP_NAMES)?.toList().orEmpty()
        urls = intent.getStringArrayExtra(EXTRA_EP_URLS)?.toList().orEmpty()
        episode = intent.getIntExtra(EXTRA_EPISODE, 0).coerceIn(0, (urls.size - 1).coerceAtLeast(0))
        if (urls.isEmpty()) {
            finish()
            return
        }

        player = ExoPlayer.Builder(this).setMediaSourceFactory(mediaSourceFactory).build()
            .also { it.addListener(listener) }

        val playerView = PlayerView(this).apply {
            player = this@PlayerActivity.player
            useController = false
            isFocusable = false
            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
            setBackgroundColor(Color.BLACK)
            keepScreenOn = true
        }
        hint = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 16f
            setBackgroundColor(0xAA000000.toInt())
            setPadding(28, 14, 28, 14)
            visibility = View.GONE
        }
        val gestures = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (controls.visibility == View.VISIBLE) controls.visibility = View.GONE else showOverlay()
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                val w = window.decorView.width
                when {
                    e.x < w / 3f -> seekRelative(-SEEK_STEP_MS)
                    e.x > w * 2 / 3f -> seekRelative(SEEK_STEP_MS)
                    else -> togglePlay()
                }
                return true
            }
        })
        val touchLayer = View(this).apply {
            setOnTouchListener { _, ev -> gestures.onTouchEvent(ev); true }
        }

        setContentView(FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(playerView)
            addView(touchLayer, FrameLayout.LayoutParams(-1, -1))
            controls = buildOverlay()
            addView(controls, FrameLayout.LayoutParams(-1, -1))
            addView(hint, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply {
                topMargin = 40
                rightMargin = 40
            })
        })

        onBackPressedDispatcher.addCallback(this) { finish() }

        val startMs = Store.position(anime.id, source, episode)
        playEpisode(episode, startMs)
        if (startMs > 0) showHint("從 ${fmtTime(startMs)} 繼續播放")
        startTicker()
    }

    private fun button(text: String, onClick: () -> Unit) = TextView(this).apply {
        this.text = text
        setTextColor(Color.WHITE)
        textSize = 15f
        gravity = Gravity.CENTER
        setPadding(26, 14, 26, 14)
        isFocusable = false
        background = GradientDrawable().apply {
            cornerRadius = 40f
            setColor(0x33FFFFFF)
        }
        setOnClickListener {
            onClick()
            showOverlay()
        }
    }

    private fun buildOverlay(): View {
        titleView = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 17f
            maxLines = 1
            setShadowLayer(6f, 0f, 0f, Color.BLACK)
        }
        val back = button("‹ 返回") { finish() }
        val episodes = button("選集") { chooseEpisode() }
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(40, 32, 40, 24)
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0xAA000000.toInt(), 0x00000000)
            )
            addView(back)
            addView(titleView, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = 24; rightMargin = 24 })
            addView(episodes)
        }

        status = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 15f
            minWidth = 80
        }
        timeText = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 14f
            setPadding(16, 0, 0, 0)
        }
        seekBar = SeekBar(this).apply {
            max = 1000
            isFocusable = false
            progressTintList = ColorStateList.valueOf(ACCENT)
            thumbTintList = ColorStateList.valueOf(ACCENT)
            secondaryProgressTintList = ColorStateList.valueOf(0x66FFFFFF)
            progressBackgroundTintList = ColorStateList.valueOf(0x33FFFFFF)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                    if (fromUser) {
                        val dur = duration() ?: return
                        timeText.text = "${fmtTime(dur * p / 1000)} / ${fmtTime(dur)}"
                    }
                }

                override fun onStartTrackingTouch(sb: SeekBar) {
                    dragging = true
                    showOverlay()
                }

                override fun onStopTrackingTouch(sb: SeekBar) {
                    dragging = false
                    duration()?.let { player.seekTo(it * sb.progress / 1000) }
                    showOverlay()
                }
            })
        }
        val seekRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(status)
            addView(seekBar, LinearLayout.LayoutParams(0, -2, 1f))
            addView(timeText)
        }

        prevBtn = button("⏮ 上一集") { if (episode > 0) playEpisode(episode - 1, positionFor(episode - 1)) }
        playBtn = button("❚❚") { togglePlay() }
        nextBtn = button("下一集 ⏭") { if (episode < urls.lastIndex) playEpisode(episode + 1, positionFor(episode + 1)) }
        speedBtn = button("1.0x") { chooseSpeed() }
        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 12, 0, 0)
            fun gap() = View(context).also { addView(it, LinearLayout.LayoutParams(20, 1)) }
            addView(button("-10s") { seekRelative(-SEEK_STEP_MS) }); gap()
            addView(playBtn); gap()
            addView(button("+10s") { seekRelative(SEEK_STEP_MS) })
            addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
            addView(prevBtn); gap()
            addView(nextBtn); gap()
            addView(speedBtn)
        }

        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 40, 40, 32)
            background = GradientDrawable(
                GradientDrawable.Orientation.BOTTOM_TOP, intArrayOf(0xCC000000.toInt(), 0x00000000)
            )
            addView(seekRow)
            addView(btnRow)
        }

        return FrameLayout(this).apply {
            addView(top, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))
            addView(bottom, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
            visibility = View.GONE
        }
    }

    private fun positionFor(ep: Int) = Store.position(anime.id, source, ep)

    private fun playEpisode(ep: Int, positionMs: Long) {
        if (::player.isInitialized && ep != episode) saveProgress()
        episode = ep
        ended = false
        val name = names.getOrElse(ep) { "${ep + 1}" }
        titleView.text = if (urls.size > 1) "${anime.name}　第 $name 集" else anime.name
        prevBtn.alpha = if (ep > 0) 1f else 0.35f
        nextBtn.alpha = if (ep < urls.lastIndex) 1f else 0.35f
        val url = urls[ep]
        val item = MediaItem.Builder().setUri(url).apply {
            if (url.contains(".m3u8", ignoreCase = true)) setMimeType(MimeTypes.APPLICATION_M3U8)
        }.build()
        player.setMediaItem(item, positionMs.coerceAtLeast(0L))
        player.prepare()
        player.playWhenReady = true
        Store.addHistory(HistoryItem(anime, source, ep, name))
        showOverlay()
    }

    private fun duration(): Long? = player.duration.takeIf { it != C.TIME_UNSET && it > 0 }

    // ---------- 控制列 ----------

    private fun showOverlay() {
        controls.visibility = View.VISIBLE
        updateOverlay()
        overlayJob?.cancel()
        overlayJob = lifecycleScope.launch {
            delay(OVERLAY_TIMEOUT_MS)
            while (isActive && (!player.isPlaying || seekTarget != null || boosting || dragging)) delay(500)
            controls.visibility = View.GONE
        }
    }

    private fun updateOverlay() {
        if (controls.visibility != View.VISIBLE) return
        playBtn.text = if (player.playWhenReady && !ended) "❚❚" else "▶"
        status.text = when {
            boosting -> "▶▶ 2x"
            seekTarget != null -> if (seekTarget!! >= player.currentPosition) "▶▶" else "◀◀"
            player.playbackState == Player.STATE_BUFFERING -> "…"
            else -> ""
        }
        if (dragging) return
        val dur = duration() ?: 0L
        val pos = seekTarget ?: player.currentPosition
        timeText.text = "${fmtTime(pos)} / ${if (dur > 0) fmtTime(dur) else "--:--"}"
        if (dur > 0) {
            seekBar.progress = (pos * 1000 / dur).toInt()
            seekBar.secondaryProgress = (player.bufferedPosition * 1000 / dur).toInt()
        }
    }

    private fun startTicker() {
        lifecycleScope.launch {
            while (isActive) {
                updateOverlay()
                delay(500)
            }
        }
    }

    // ---------- 動作 ----------

    private fun seekRelative(delta: Long) {
        val dur = duration() ?: return
        player.seekTo((player.currentPosition + delta).coerceIn(0L, dur))
        showHint(if (delta > 0) "快進 ${delta / 1000} 秒" else "快退 ${-delta / 1000} 秒")
        showOverlay()
    }

    private fun seekBy(forward: Boolean, repeat: Int) {
        val dur = duration() ?: return
        val base = seekTarget ?: player.currentPosition
        val step = if (repeat == 0) SEEK_STEP_MS else (dur / 150).coerceIn(2_000L, 30_000L)
        seekTarget = (base + if (forward) step else -step).coerceIn(0L, dur)
        showOverlay()
    }

    private fun commitSeek() {
        val target = seekTarget ?: return
        seekTarget = null
        player.seekTo(target)
        if (ended) {
            ended = false
            player.play()
        }
        showOverlay()
    }

    private fun startBoost() {
        boosting = true
        seekTarget = null
        if (!player.isPlaying) player.play()
        player.setPlaybackSpeed(BOOST_SPEED)
        showOverlay()
    }

    private fun stopBoost() {
        boosting = false
        player.setPlaybackSpeed(speed)
        showOverlay()
    }

    private fun togglePlay() {
        if (ended) {
            ended = false
            player.seekTo(0)
            player.play()
        } else {
            player.playWhenReady = !player.playWhenReady
        }
        showOverlay()
    }

    private fun chooseEpisode() {
        val labels = names.mapIndexed { i, n -> "第 $n 集" + if (i == episode) "（播放中）" else "" }.toTypedArray()
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("選集")
            .setSingleChoiceItems(labels, episode) { d, i ->
                d.dismiss()
                if (i != episode) playEpisode(i, positionFor(i))
            }
            .show()
    }

    private fun chooseSpeed() {
        val speeds = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)
        val labels = speeds.map { "${it}x" }.toTypedArray()
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("播放速度")
            .setSingleChoiceItems(labels, speeds.indexOf(speed).coerceAtLeast(0)) { d, i ->
                d.dismiss()
                speed = speeds[i]
                player.setPlaybackSpeed(speed)
                speedBtn.text = "${speed}x"
            }
            .show()
    }

    // ---------- 遙控器 ----------

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val down = event.action == KeyEvent.ACTION_DOWN
        val first = down && event.repeatCount == 0

        when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_REWIND -> {
                if (down) seekBy(false, event.repeatCount) else commitSeek()
                return true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                if (down) seekBy(true, event.repeatCount) else commitSeek()
                return true
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER,
            KeyEvent.KEYCODE_SPACE -> {
                // 短按：暫停／播放；長按：放開前以 2 倍速播放。
                // 部分紅外線遙控器長按是連續送很密的「按下＋放開」，所以用間隔判斷。
                val now = SystemClock.uptimeMillis()
                if (down) {
                    if (event.repeatCount == 0) {
                        okDown = true
                        if (now - lastOkUp < AUTO_REPEAT_GAP_MS) {
                            boostRelease?.cancel()
                            if (!boosting) startBoost()
                        }
                    } else if (okDown && !boosting) {
                        startBoost()
                    }
                } else {
                    lastOkUp = now
                    if (boosting) {
                        boostRelease?.cancel()
                        boostRelease = lifecycleScope.launch {
                            delay(AUTO_REPEAT_GAP_MS)
                            stopBoost()
                        }
                    } else if (okDown) {
                        togglePlay()
                    }
                    okDown = false
                }
                return true
            }
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                if (first) togglePlay()
                return true
            }
            KeyEvent.KEYCODE_MEDIA_PLAY -> {
                if (first) { player.play(); showOverlay() }
                return true
            }
            KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                if (first) { player.pause(); showOverlay() }
                return true
            }
            KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_CHANNEL_UP -> {
                if (first && episode < urls.lastIndex) playEpisode(episode + 1, positionFor(episode + 1))
                return true
            }
            KeyEvent.KEYCODE_MEDIA_PREVIOUS, KeyEvent.KEYCODE_CHANNEL_DOWN -> {
                if (first && episode > 0) playEpisode(episode - 1, positionFor(episode - 1))
                return true
            }
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_SETTINGS -> {
                if (first) chooseEpisode()
                return true
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (first) {
                    if (controls.visibility == View.VISIBLE && player.isPlaying) controls.visibility = View.GONE
                    else showOverlay()
                }
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun showHint(text: String, long: Boolean = false) {
        hint.text = text
        hint.visibility = View.VISIBLE
        hintJob?.cancel()
        hintJob = lifecycleScope.launch {
            delay(if (long) 6_000 else 1_800)
            hint.visibility = View.GONE
        }
    }

    /** 看到最後 30 秒內就當作看完，下次從頭開始 */
    private fun saveProgress() {
        val pos = player.currentPosition
        val dur = player.duration
        val done = ended || (dur > 0 && dur - pos < 30_000)
        Store.savePosition(anime.id, source, episode, if (done) 0L else pos)
    }

    override fun onPause() {
        super.onPause()
        if (!::player.isInitialized) return
        boostRelease?.cancel()
        if (boosting) stopBoost()
        okDown = false
        saveProgress()
        player.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::player.isInitialized) player.release()
    }
}

/** 毫秒 → 1:23:45 或 12:34 */
fun fmtTime(ms: Long): String {
    val s = ms / 1000
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}
