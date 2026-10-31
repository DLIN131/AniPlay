package com.aniplay.core.player

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.view.View
import com.aniplay.core.net.Danmaku

/**
 * 彈幕層。每一則彈幕的位置都由「影片目前時間 − 彈幕時間」直接算出來，不靠自己的計時器，
 * 所以暫停會停住、拖動進度會跳到對的位置、倍速播放時跟著變快，永遠和影片時間軸對齊。
 *
 * 效能：每則彈幕出現時先把「描邊＋文字」畫成一張小 Bitmap，之後每一幀只貼圖。
 * 以前每一幀都重畫所有文字的描邊，彈幕一多就超過一幀的時間而卡頓。
 */
class DanmakuView(context: Context) : View(context) {

    /** 回傳影片目前播放位置（毫秒） */
    var positionMs: () -> Long = { 0L }

    /** 時間微調（毫秒，正數＝彈幕延後出現） */
    var offsetMs = 0L
        set(value) {
            field = value
            reset()
        }

    var showing = true
        set(value) {
            field = value
            reset()
            invalidate()
        }

    var sizeScale = 1f
        set(value) {
            field = value
            reset()
        }

    var opacity = 0.85f

    /** 捲動彈幕可以使用的畫面高度比例 */
    var area = 0.5f
        set(value) {
            field = value
            reset()
        }

    private var items: List<Danmaku> = emptyList()

    private class Active(val d: Danmaku, val width: Float, val lane: Int) {
        /** 預先畫好的小圖，在畫的時候才產生（每幀有上限） */
        var bmp: Bitmap? = null
    }

    private val active = ArrayList<Active>()
    /** 每條捲動軌道上最後一則彈幕 */
    private val scrollLanes = HashMap<Int, Active>()
    private val topLanes = HashMap<Int, Active>()
    private val bottomLanes = HashMap<Int, Active>()
    private var next = 0
    private var lastPos = Double.NaN

    private val density = resources.displayMetrics.density
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.DEFAULT_BOLD
    }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.DEFAULT_BOLD
        style = Paint.Style.STROKE
        color = Color.BLACK
    }
    /** 貼小圖用，只負責整體透明度 */
    private val bmpPaint = Paint()

    /** 小圖四周留給描邊的空間 */
    private fun pad() = (stroke.strokeWidth / 2f).toInt() + 2

    private fun sprite(d: Danmaku, w: Float): Bitmap {
        val p = pad()
        val fm = fill.fontMetrics
        val bw = (w + p * 2).toInt().coerceAtLeast(1)
        val bh = (fm.descent - fm.ascent + p * 2).toInt().coerceAtLeast(1)
        val b = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        val baseY = p - fm.ascent
        fill.color = d.color
        fill.alpha = 255
        stroke.alpha = 204
        c.drawText(d.text, p.toFloat(), baseY, stroke)
        c.drawText(d.text, p.toFloat(), baseY, fill)
        return b
    }

    fun setDanmaku(list: List<Danmaku>) {
        items = list
        reset()
        invalidate()
    }

    /** 換集、拖動進度、改設定後重新從目前時間開始排 */
    fun reset() {
        active.clear()
        scrollLanes.clear()
        topLanes.clear()
        bottomLanes.clear()
        lastPos = Double.NaN
    }

    private fun textSize() = (if (height > 0 && height < width) height / 22f else 22f * density) * sizeScale

    private fun lineHeight() = textSize() * 1.25f

    private fun speed(w: Float) = ((width + w) / SCROLL_SECONDS).toFloat()

    /** 第一個時間 ≥ t 的位置 */
    private fun lowerBound(t: Double): Int {
        var lo = 0
        var hi = items.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (items[mid].time < t) lo = mid + 1 else hi = mid
        }
        return lo
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        reset()
    }

    override fun onDraw(canvas: Canvas) {
        if (!showing || items.isEmpty() || width == 0) return
        val pos = (positionMs() - offsetMs) / 1000.0

        // 往回拖或往前跳超過 1 秒：重新排，把「現在應該還在畫面上」的彈幕也補上（位置由時間差算出）
        if (lastPos.isNaN() || pos < lastPos - 0.05 || pos - lastPos > 1.0) {
            reset()
            next = lowerBound(pos - SCROLL_SECONDS)
        }
        lastPos = pos

        val size = textSize()
        fill.textSize = size
        stroke.textSize = size
        stroke.strokeWidth = (size / 12f).coerceAtLeast(1.5f)
        val line = lineHeight()

        while (next < items.size && items[next].time <= pos) {
            val d = items[next++]
            val age = pos - d.time
            when (d.type) {
                Danmaku.Type.SCROLL -> if (age < SCROLL_SECONDS) spawnScroll(d, line)
                else -> if (age < FIXED_SECONDS) spawnFixed(d, line)
            }
        }

        bmpPaint.alpha = (opacity * 255).toInt()
        // 開始播放、拖動進度時畫面上一下子要補幾十則，每幀最多新畫 12 張，分散到後面幾幀
        var budget = SPRITES_PER_FRAME
        val ascent = fill.fontMetrics.ascent
        val p = pad()
        val it = active.iterator()
        while (it.hasNext()) {
            val a = it.next()
            val age = (pos - a.d.time).toFloat()
            val x: Float
            val y: Float
            when (a.d.type) {
                Danmaku.Type.SCROLL -> {
                    if (age > SCROLL_SECONDS) {
                        it.remove(); continue
                    }
                    x = width - age * speed(a.width)
                    y = line * (a.lane + 1) - line * 0.22f
                }
                Danmaku.Type.TOP -> {
                    if (age > FIXED_SECONDS) {
                        it.remove(); continue
                    }
                    x = (width - a.width) / 2f
                    y = line * (a.lane + 1) - line * 0.22f
                }
                Danmaku.Type.BOTTOM -> {
                    if (age > FIXED_SECONDS) {
                        it.remove(); continue
                    }
                    x = (width - a.width) / 2f
                    y = height - line * a.lane - line * 0.35f
                }
            }
            val b = a.bmp ?: if (budget > 0) {
                budget--
                sprite(a.d, a.width).also { a.bmp = it }
            } else {
                continue
            }
            // 對齊到整數像素，貼圖不用重新取樣
            canvas.drawBitmap(b, Math.round(x - p).toFloat(), Math.round(y + ascent - p).toFloat(), bmpPaint)
        }

        postInvalidateOnAnimation()
    }

    private fun spawnScroll(d: Danmaku, line: Float) {
        val w = fill.measureText(d.text)
        val lanes = ((height * area) / line).toInt().coerceAtLeast(1)
        for (lane in 0 until lanes) {
            val last = scrollLanes[lane]
            if (last == null || fits(last, d, w)) {
                val a = Active(d, w, lane)
                scrollLanes[lane] = a
                active += a
                return
            }
        }
        // 每條軌道都滿了就不顯示，避免疊在一起看不清楚
    }

    /** 新彈幕和同軌道上一則不會重疊：上一則的尾巴已經離開右邊，而且新的在上一則離開畫面前追不上它 */
    private fun fits(last: Active, d: Danmaku, w: Float): Boolean {
        val gap = 16 * density
        val lastX = width - (d.time - last.d.time) * speed(last.width)
        if (lastX + last.width + gap > width) return false
        val lastExit = last.d.time + SCROLL_SECONDS
        val newXAtExit = width - (lastExit - d.time) * speed(w)
        return newXAtExit >= 0
    }

    private fun spawnFixed(d: Danmaku, line: Float) {
        val lanes = if (d.type == Danmaku.Type.TOP) topLanes else bottomLanes
        val count = ((height * 0.3f) / line).toInt().coerceAtLeast(1)
        for (lane in 0 until count) {
            val last = lanes[lane]
            if (last == null || d.time - last.d.time >= FIXED_SECONDS) {
                val a = Active(d, fill.measureText(d.text), lane)
                lanes[lane] = a
                active += a
                return
            }
        }
    }

    companion object {
        /** 捲動彈幕從右到左走完畫面的影片秒數 */
        const val SCROLL_SECONDS = 8.0
        const val FIXED_SECONDS = 4.0
        const val SPRITES_PER_FRAME = 12
    }
}
