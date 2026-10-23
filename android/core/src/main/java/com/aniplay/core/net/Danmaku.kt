package com.aniplay.core.net

import android.util.Xml
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.MultipartBody
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream

/** 一則彈幕；time 是影片時間（秒） */
data class Danmaku(val time: Double, val text: String, val color: Int, val type: Type) {
    enum class Type { SCROLL, TOP, BOTTOM }
}

/**
 * 彈幕有兩個來源，合併後依時間排序：
 * 1. 站內使用者發的彈幕（Scrolling/getScrolling）
 * 2. 外部彙整彈幕 XML（各彈幕站匯總，B 站 &lt;d p="時間,模式,字號,顏色,…"&gt; 格式），
 *    網址由 Scrolling/getVodOutScrolling 給；沒給時照網站的規則由 m3u8 路徑推出 .xml。
 * 兩個來源的時間都是對應同一個影片檔的秒數，播放器直接用播放位置對齊。
 */
object DanmakuApi {

    suspend fun load(playUrl: String): List<Danmaku> = coroutineScope {
        val internal = async { runCatching { internal(playUrl) }.getOrDefault(emptyList()) }
        val external = async { runCatching { external(playUrl) }.getOrDefault(emptyList()) }
        (internal.await() + external.await()).sortedBy { it.time }
    }

    private fun post(path: String, playUrl: String): Any? {
        val body = MultipartBody.Builder().setType(MultipartBody.FORM).addFormDataPart("play_url", playUrl).build()
        val req = Request.Builder().url(Api.BASE + path)
            .header("User-Agent", Api.USER_AGENT)
            .header("Accept", "application/json")
            .post(body)
            .build()
        Api.client.newCall(req).execute().use { r ->
            if (!r.isSuccessful) return null
            val json = JSONObject(r.body?.string().orEmpty())
            if (json.optInt("code") != 1) return null
            return json.opt("info")
        }
    }

    private suspend fun internal(playUrl: String): List<Danmaku> = withContext(Dispatchers.IO) {
        val arr = post("Scrolling/getScrolling", playUrl) as? JSONArray ?: return@withContext emptyList()
        List(arr.length()) { arr.optJSONObject(it) }.mapNotNull { o ->
            val j = o?.opt("scroll_json")?.let { if (it is JSONObject) it else runCatching { JSONObject(it.toString()) }.getOrNull() }
                ?: return@mapNotNull null
            val time = j.optDouble("time", -1.0)
            val text = j.optString("content").ifEmpty { j.optString("text") }.trim()
            if (time < 0 || time.isNaN() || text.isEmpty()) return@mapNotNull null
            val color = parseColor(j.optJSONObject("style")?.optString("color")?.takeIf { it.isNotEmpty() } ?: j.optString("color"))
            // 站內：1 捲動、2 頂部、3 底部
            val type = when (j.optInt("mode", 1)) {
                2 -> Danmaku.Type.TOP
                3 -> Danmaku.Type.BOTTOM
                else -> Danmaku.Type.SCROLL
            }
            Danmaku(time, text, color, type)
        }
    }

    private suspend fun external(playUrl: String): List<Danmaku> = withContext(Dispatchers.IO) {
        val url = (post("Scrolling/getVodOutScrolling", playUrl) as? String)?.trim()?.takeIf { it.startsWith("http") }
            ?: fallbackXml(playUrl)
            ?: return@withContext emptyList()
        val req = Request.Builder().url(url).header("User-Agent", Api.USER_AGENT).build()
        Api.client.newCall(req).execute().use { r ->
            if (!r.isSuccessful) return@withContext emptyList()
            parseXml(r.body!!.byteStream())
        }
    }

    /** 自家 CDN 的 …/01/playlist.m3u8 → …/01.xml（和網站播放器同樣的規則） */
    private fun fallbackXml(playUrl: String): String? {
        val u = runCatching { java.net.URI(playUrl) }.getOrNull() ?: return null
        val host = u.host ?: return null
        val base = Api.BASE.substringAfter("://").substringBefore('/').substringAfter('.')
        if (u.scheme != "https" || !host.endsWith(".$base") || !u.path.startsWith("/zijian/") ||
            !u.path.endsWith("/playlist.m3u8")
        ) return null
        return "https://$host" + u.path.removeSuffix("/playlist.m3u8") + ".xml"
    }

    private fun parseXml(input: InputStream): List<Danmaku> {
        val out = ArrayList<Danmaku>()
        val p = Xml.newPullParser()
        p.setInput(input, "UTF-8")
        var ev = p.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            if (ev == XmlPullParser.START_TAG && p.name == "d") {
                val attr = p.getAttributeValue(null, "p").orEmpty().split(',')
                val text = runCatching { p.nextText() }.getOrDefault("").trim()
                val time = attr.getOrNull(0)?.toDoubleOrNull()
                if (time != null && time >= 0 && text.isNotEmpty()) {
                    // B 站：1~3 捲動、4 底部、5 頂部
                    val type = when (attr.getOrNull(1)?.toIntOrNull()) {
                        5 -> Danmaku.Type.TOP
                        4 -> Danmaku.Type.BOTTOM
                        else -> Danmaku.Type.SCROLL
                    }
                    val rgb = attr.getOrNull(3)?.toLongOrNull()?.toInt() ?: 0xFFFFFF
                    out += Danmaku(time, text, (0xFF000000.toInt()) or (rgb and 0xFFFFFF), type)
                }
            }
            ev = p.next()
        }
        return out
    }

    /** 站內彈幕的顏色："#ff0000"、"ff0000" 或十進位數字 → ARGB */
    private fun parseColor(s: String?): Int {
        val t = s?.trim().orEmpty()
        val rgb = when {
            t.startsWith("#") && t.length == 7 -> t.substring(1).toIntOrNull(16)
            t.length == 6 && t.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' } -> t.toIntOrNull(16)
            else -> t.toLongOrNull()?.toInt()
        } ?: 0xFFFFFF
        return (0xFF000000.toInt()) or (rgb and 0xFFFFFF)
    }
}
