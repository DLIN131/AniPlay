package com.aniplay.core

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import com.aniplay.core.net.Store

class App : Application() {
    companion object {
        lateinit var instance: App
            private set
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        Store.init(this)
    }
}

/** 偏好設定 */
object Prefs {
    private val sp: SharedPreferences by lazy {
        App.instance.getSharedPreferences("prefs", Context.MODE_PRIVATE)
    }

    var danmakuOn: Boolean
        get() = sp.getBoolean("danmaku_on", true)
        set(value) = sp.edit().putBoolean("danmaku_on", value).apply()

    /** 字體大小倍率 */
    var danmakuSize: Float
        get() = sp.getFloat("danmaku_size", 1f)
        set(value) = sp.edit().putFloat("danmaku_size", value).apply()

    var danmakuAlpha: Float
        get() = sp.getFloat("danmaku_alpha", 0.85f)
        set(value) = sp.edit().putFloat("danmaku_alpha", value).apply()

    /** 顯示範圍：畫面上方多少比例可以出現捲動彈幕 */
    var danmakuArea: Float
        get() = sp.getFloat("danmaku_area", 0.5f)
        set(value) = sp.edit().putFloat("danmaku_area", value).apply()

    /** 彈幕時間微調（毫秒，正數＝彈幕延後出現），每部作品各自記住 */
    fun danmakuOffset(animeId: Int): Long = sp.getLong("danmaku_offset_$animeId", 0L)

    fun setDanmakuOffset(animeId: Int, ms: Long) =
        sp.edit().apply { if (ms != 0L) putLong("danmaku_offset_$animeId", ms) else remove("danmaku_offset_$animeId") }.apply()

    /** 上次開啟時的 versionCode，用來判斷「剛更新完」要顯示更新內容 */
    var lastSeenVersion: Int
        get() = sp.getInt("last_seen_version", 0)
        set(value) = sp.edit().putInt("last_seen_version", value).apply()

    /** 使用者選「略過這個版本」的 versionCode，自動檢查時不再提示這一版 */
    var skippedVersion: Int
        get() = sp.getInt("skipped_version", 0)
        set(value) = sp.edit().putInt("skipped_version", value).apply()
}
