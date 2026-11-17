package com.aniplay.core.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** 卡片上顯示的一部作品 */
data class Anime(
    val id: Int,
    val name: String,
    val cover: String,
    /** 「更新至03集」「完結」之類的角標 */
    val remarks: String = "",
    val year: String = "",
    val score: String = "",
    /** 季度月份（一月／四月／七月／十月），相關作品排序用 */
    val month: String = "",
)

data class Episode(val name: String, val url: String)

/** 一條播放線路（繁中、簡中…） */
data class PlaySource(val name: String, val episodes: List<Episode>)

data class AnimeDetail(
    val anime: Anime,
    val subTitle: String,
    val blurb: String,
    val classes: List<String>,
    val area: String,
    val lang: String,
    val version: String,
    val director: String,
    val actors: String,
    val pubdate: String,
    val weekday: String,
    val sources: List<PlaySource>,
    /** 2 日番、21 劇場版…；相關作品分類用 */
    val typeId: Int = 0,
    /** 網站維護的同系列作品 id（含自己），可能有已下架的 */
    val relIds: List<Int> = emptyList(),
)

/** 同系列的其他作品，各組依年份、季度排序，包含目前這部 */
data class Related(val tv: List<Anime>, val ova: List<Anime>, val movie: List<Anime>) {
    val count get() = tv.size + ova.size + movie.size
}

data class Banner(val title: String, val description: String, val cover: String, val animeId: Int?)

/** 新番時間表的一天 */
data class WeekDay(val week: Int, val items: List<Anime>)

data class Page(val items: List<Anime>, val total: Int)

/** 列表查詢條件（分類、排序、篩選、搜尋都走同一支 API） */
data class Query(
    val typeId: Int? = null,
    val orderby: String = "pubdate",
    val name: String = "",
    val cls: String = "",
    val year: String = "",
    /** 新番季度月份：一月、四月、七月、十月 */
    val month: String = "",
    val lang: String = "",
    val version: String = "",
    val state: String = "",
    val letter: String = "",
)

data class TagOptions(
    val classes: List<String>,
    val langs: List<String>,
    val versions: List<String>,
    val states: List<String>,
)

object Api {
    val BASE = decode("aHR0cHM6Ly9tM3U4LmdpcmlnaXJpbG92ZS5jb20vYXBpLnBocC8=")
    private val IMG = decode("aHR0cHM6Ly9qcGcuZ2lyaWdpcmlsb3ZlLmNvbS8=")

    private fun decode(s: String) = String(android.util.Base64.decode(s, android.util.Base64.DEFAULT))
    const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Safari/537.36"

    val types = listOf(2 to "日番", 21 to "劇場版", 3 to "美番", 20 to "真人番劇", 24 to "BD副音軌", 26 to "演唱會及其他")

    /** 網站以季度分新番（存在 vod_area 欄位） */
    val months = listOf("一月", "四月", "七月", "十月")

    val orders = listOf(
        "pubdate" to "最新",
        "hits" to "總熱度",
        "hits_week" to "本週熱門",
        "hits_month" to "本月熱門",
        "hits_day" to "今日熱門",
        "score" to "評分",
    )

    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private suspend fun get(path: String, params: Map<String, String> = emptyMap()): Any = withContext(Dispatchers.IO) {
        val url = (BASE + path).toHttpUrl().newBuilder().apply {
            params.forEach { (k, v) -> addQueryParameter(k, v) }
        }.build()
        val req = Request.Builder().url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/json")
            .build()
        client.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw ApiException("伺服器錯誤（HTTP ${resp.code}）")
            val json = runCatching { JSONObject(body) }.getOrElse { throw ApiException("伺服器回應格式錯誤") }
            if (json.optInt("code") != 1) throw ApiException(json.optString("msg").ifEmpty { "載入失敗" })
            json.opt("info") ?: JSONObject.NULL
        }
    }

    fun img(path: String): String = when {
        path.isEmpty() -> ""
        path.startsWith("http") -> path
        path.startsWith("//") -> "https:$path"
        else -> IMG + path.trimStart('/')
    }

    // 網站有些文字是 HTML 跳脫過的（例如標題裡的 &amp;），還原成一般字元
    private fun JSONObject.str(key: String) = optString(key).takeUnless { it == "null" }.orEmpty().trim().let(::unescape)

    private fun unescape(s: String) = if ('&' !in s) s else s
        .replace("&nbsp;", " ")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace(Regex("&#(\\d+);")) { m -> m.groupValues[1].toIntOrNull()?.let { String(Character.toChars(it)) } ?: m.value }
        .replace(Regex("&#[xX]([0-9a-fA-F]+);")) { m -> m.groupValues[1].toIntOrNull(16)?.let { String(Character.toChars(it)) } ?: m.value }
        .replace("&amp;", "&")

    private fun parseAnime(o: JSONObject) = Anime(
        id = o.optInt("vod_id"),
        name = o.str("vod_name"),
        cover = img(o.str("vod_pic")),
        // 時間表的 vod_time 是「更新至03集 / 23點30分」，列表裡則是數字時間戳，不能拿來顯示
        remarks = o.str("vod_remarks").ifEmpty { (o.opt("vod_time") as? String)?.trim().orEmpty() },
        year = o.str("vod_year"),
        score = o.str("vod_score"),
        month = o.str("vod_area"),
    )

    suspend fun list(q: Query, offset: Int, limit: Int = 24): Page {
        val p = buildMap {
            q.typeId?.let { put("type_id", it.toString()) }
            put("offset", offset.toString())
            put("limit", limit.toString())
            put("orderby", q.orderby)
            // 網站資料是簡體，打繁體也要找得到
            if (q.name.isNotEmpty()) put("vod_name", ChineseConvert.toSimplified(q.name))
            if (q.cls.isNotEmpty()) put("vod_class", q.cls)
            if (q.year.isNotEmpty()) put("vod_year", q.year)
            if (q.month.isNotEmpty()) put("vod_area", q.month)
            if (q.lang.isNotEmpty()) put("vod_lang", q.lang)
            if (q.version.isNotEmpty()) put("vod_version", q.version)
            if (q.state.isNotEmpty()) put("vod_state", q.state)
            if (q.letter.isNotEmpty()) put("vod_letter", q.letter)
        }
        val info = get("Vod/get_list", p) as? JSONObject ?: return Page(emptyList(), 0)
        val rows = info.optJSONArray("rows") ?: JSONArray()
        return Page(List(rows.length()) { parseAnime(rows.getJSONObject(it)) }, info.optInt("total"))
    }

    suspend fun detail(id: Int): AnimeDetail {
        val o = get("Vod/get_detail", mapOf("vod_id" to id.toString())) as? JSONObject
            ?: throw ApiException("找不到這部作品")
        return AnimeDetail(
            anime = parseAnime(o),
            subTitle = o.str("vod_sub"),
            blurb = o.str("vod_content").let(::stripHtml).ifEmpty { o.str("vod_blurb") },
            classes = o.str("vod_class").split(',', '，', '/').map { it.trim() }.filter { it.isNotEmpty() },
            area = o.str("vod_area"),
            lang = o.str("vod_lang"),
            version = o.str("vod_version"),
            director = o.str("vod_director"),
            actors = o.str("vod_actor"),
            pubdate = o.str("vod_pubdate"),
            weekday = o.str("vod_weekday"),
            sources = parseSources(o.str("vod_play_from"), o.str("vod_play_url")),
            typeId = o.optInt("type_id"),
            relIds = o.str("vod_rel_vod").split(',').mapNotNull { it.trim().toIntOrNull() }.filter { it > 0 },
        )
    }

    private val SPECIAL = Regex("""OVA|OAD|\bSP\b|特别篇|特別篇|番外|总集篇|總集篇""", RegexOption.IGNORE_CASE)
    private val SERIES_CUT = Regex("""[\s：:（(]|第[一二三四五六七八九十百\d]+[季期部]|剧场版|劇場版|OVA|OAD|特别篇|特別篇""", RegexOption.IGNORE_CASE)
    private val MONTH_ORDER = mapOf("一月" to 1, "四月" to 4, "七月" to 7, "十月" to 10)

    /** 「关于我转生变成史莱姆这档事 第二季」→「关于我转生变成史莱姆这档事」 */
    private fun seriesName(name: String): String {
        val base = ChineseConvert.toSimplified(name).split(SERIES_CUT).first().trim()
        return if (base.length >= 2) base else ""
    }

    /**
     * 相關作品（同系列的各季、OVA・SP、劇場版）。兩個來源合併：
     * 網站維護的 vod_rel_vod（不同名字的前作也找得到）＋ 用系列名搜尋（依類型分三次查，順便知道分類）。
     */
    suspend fun related(d: AnimeDetail): Related = coroutineScope {
        val kind = LinkedHashMap<Int, String>()
        val items = LinkedHashMap<Int, Anime>()
        fun add(a: Anime, k: String) {
            if (a.id <= 0 || a.id in items) return
            items[a.id] = a
            kind[a.id] = if (k == "tv" && SPECIAL.containsMatchIn(a.name)) "ova" else k
        }
        fun kindOf(typeId: Int, version: String) = when {
            typeId == 21 -> "movie"
            version == "OVA" -> "ova"
            else -> "tv"
        }
        val base = seriesName(d.anime.name)
        if (base.isNotEmpty()) {
            fun search(q: Query) = async {
                runCatching { list(q, 0, 60).items }.getOrDefault(emptyList())
                    .filter { ChineseConvert.toSimplified(it.name).startsWith(base) }
            }
            val movies = search(Query(typeId = 21, name = base))
            val ovas = search(Query(typeId = 2, name = base, version = "OVA"))
            val tvs = search(Query(typeId = 2, name = base))
            movies.await().forEach { add(it, "movie") }
            ovas.await().forEach { add(it, "ova") }
            tvs.await().forEach { add(it, "tv") }
        }
        // 目前這部一定要在
        add(d.anime, kindOf(d.typeId, d.version))
        val gate = Semaphore(6)
        d.relIds.filter { it !in items }.take(40)
            .map { id -> async { gate.withPermit { runCatching { detail(id) }.getOrNull() } } }
            .awaitAll()
            .filterNotNull()
            .filter { it.typeId == 2 || it.typeId == 21 }
            .forEach { add(it.anime, kindOf(it.typeId, it.version)) }
        fun group(k: String) = items.values.filter { kind[it.id] == k }
            .sortedWith(compareBy({ (it.year.toIntOrNull() ?: 9999) * 100 + (MONTH_ORDER[it.month] ?: 0) }, { it.id }))
        Related(group("tv"), group("ova"), group("movie"))
    }

    /** vod_play_from = "cht$$$chs"，vod_play_url = "01$網址#02$網址$$$01$網址…" */
    fun parseSources(from: String, urls: String): List<PlaySource> {
        val names = from.split("$$$")
        return urls.split("$$$").mapIndexedNotNull { i, group ->
            val eps = group.split('#').mapNotNull { part ->
                val idx = part.indexOf('$')
                val name = if (idx >= 0) part.substring(0, idx) else ""
                val url = if (idx >= 0) part.substring(idx + 1) else part
                url.trim().takeIf { it.startsWith("http") }?.let { Episode(name.trim().ifEmpty { "播放" }, it) }
            }
            if (eps.isEmpty()) null else PlaySource(sourceLabel(names.getOrElse(i) { "" }, i), eps)
        }
    }

    private fun sourceLabel(code: String, index: Int) = when (code.lowercase()) {
        "cht" -> "繁體中文"
        "chs" -> "簡體中文"
        "" -> "線路 ${index + 1}"
        else -> code
    }

    private fun stripHtml(s: String) = s
        .replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")
        .replace(Regex("<[^>]+>"), "")
        .replace("&nbsp;", " ")
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .trim()

    suspend fun banners(): List<Banner> {
        val arr = get("App2/bannerList") as? JSONArray ?: return emptyList()
        return List(arr.length()) { arr.getJSONObject(it) }.mapNotNull { o ->
            // 只留連到站內作品的，廣告連結不要
            val id = Regex("^/video/(\\d+)").find(o.str("link"))?.groupValues?.get(1)?.toIntOrNull()
                ?: o.str("video_key").toIntOrNull()
                ?: return@mapNotNull null
            Banner(o.str("title"), o.str("description"), img(o.str("cover")), id)
        }
    }

    suspend fun week(): List<WeekDay> {
        val arr = get("App2/weekList") as? JSONArray ?: return emptyList()
        return List(arr.length()) { arr.getJSONObject(it) }.map { o ->
            val v = o.optJSONArray("video") ?: JSONArray()
            WeekDay(o.optInt("week"), List(v.length()) { parseAnime(v.getJSONObject(it)) })
        }.sortedBy { it.week }
    }

    suspend fun tagOptions(): TagOptions {
        val o = get("Vod/getAllTagOption") as? JSONObject ?: JSONObject()
        fun arr(k: String) = o.optJSONArray(k)?.let { a -> List(a.length()) { a.getString(it) } }.orEmpty()
        return TagOptions(arr("vod_class"), arr("vod_lang"), arr("vod_version"), arr("vod_state"))
    }
}

class ApiException(message: String) : Exception(message)

/** 錯誤訊息轉成給人看的中文 */
fun Throwable.friendly(): String = when (this) {
    is ApiException -> message.orEmpty()
    is java.net.UnknownHostException -> "沒有網路連線"
    is java.net.SocketTimeoutException -> "連線逾時，請重試"
    is java.io.IOException -> "網路錯誤：${message.orEmpty()}"
    else -> message ?: "載入失敗"
}

/** 星期一＝1 … 星期日＝7 */
val WEEK_NAMES = listOf("一", "二", "三", "四", "五", "六", "日")
