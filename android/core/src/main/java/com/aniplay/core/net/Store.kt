package com.aniplay.core.net

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.mutableStateListOf
import org.json.JSONArray
import org.json.JSONObject

/** 觀看紀錄裡的一筆：看到哪條線路的哪一集 */
data class HistoryItem(val anime: Anime, val source: Int, val episode: Int, val episodeName: String)

/** 本機的收藏、待看清單、觀看紀錄、播放進度（不登入網站帳號，全部存在裝置上） */
object Store {
    private const val MAX_HISTORY = 300
    private lateinit var sp: SharedPreferences

    val favorites = mutableStateListOf<Anime>()
    /** 待看清單：想看但還沒開始看的作品，新加的在前面 */
    val watchLater = mutableStateListOf<Anime>()
    val history = mutableStateListOf<HistoryItem>()
    val searchWords = mutableStateListOf<String>()

    fun init(context: Context) {
        if (::sp.isInitialized) return
        sp = context.getSharedPreferences("store", Context.MODE_PRIVATE)
        favorites.addAll(loadAnimes(sp.getString("favorites", "[]")!!))
        watchLater.addAll(loadAnimes(sp.getString("watch_later", "[]")!!))
        history.addAll(loadHistory())
        searchWords.addAll(sp.getString("search_words", "").orEmpty().split('\n').filter { it.isNotBlank() })
    }

    // ---------- 搜尋紀錄 ----------

    fun addSearchWord(word: String) {
        searchWords.remove(word)
        searchWords.add(0, word)
        while (searchWords.size > 20) searchWords.removeAt(searchWords.lastIndex)
        sp.edit().putString("search_words", searchWords.joinToString("\n")).apply()
    }

    fun clearSearchWords() {
        searchWords.clear()
        sp.edit().remove("search_words").apply()
    }

    // ---------- 收藏 ----------

    fun isFavorite(id: Int) = favorites.any { it.id == id }

    fun toggleFavorite(a: Anime) {
        if (!favorites.removeAll { it.id == a.id }) favorites.add(0, a)
        sp.edit().putString("favorites", saveAnimes(favorites)).apply()
    }

    // ---------- 待看清單 ----------

    fun isWatchLater(id: Int) = watchLater.any { it.id == id }

    fun toggleWatchLater(a: Anime) {
        if (!watchLater.removeAll { it.id == a.id }) watchLater.add(0, a)
        sp.edit().putString("watch_later", saveAnimes(watchLater)).apply()
    }

    // ---------- 觀看紀錄 ----------

    fun lastWatched(id: Int) = history.firstOrNull { it.anime.id == id }

    fun addHistory(item: HistoryItem) {
        history.removeAll { it.anime.id == item.anime.id }
        history.add(0, item)
        while (history.size > MAX_HISTORY) history.removeAt(history.lastIndex)
        saveHistory()
    }

    fun removeHistory(id: Int) {
        history.removeAll { it.anime.id == id }
        saveHistory()
    }

    fun clearHistory() {
        history.clear()
        sp.edit().apply {
            sp.all.keys.filter { it.startsWith("pos_") }.forEach(::remove)
            remove("history")
        }.apply()
    }

    // ---------- 播放進度（毫秒，看完就清掉） ----------

    private fun posKey(id: Int, source: Int, episode: Int) = "pos_${id}_${source}_$episode"

    fun position(id: Int, source: Int, episode: Int) = sp.getLong(posKey(id, source, episode), 0L)

    fun savePosition(id: Int, source: Int, episode: Int, ms: Long) {
        val k = posKey(id, source, episode)
        sp.edit().apply { if (ms > 0) putLong(k, ms) else remove(k) }.apply()
    }

    // ---------- JSON ----------

    private fun animeJson(a: Anime) = JSONObject()
        .put("id", a.id).put("name", a.name).put("cover", a.cover)
        .put("remarks", a.remarks).put("year", a.year).put("score", a.score)

    private fun parseAnime(o: JSONObject) = Anime(
        id = o.getInt("id"),
        name = o.optString("name"),
        cover = o.optString("cover"),
        remarks = o.optString("remarks"),
        year = o.optString("year"),
        score = o.optString("score"),
    )

    private fun loadAnimes(s: String): List<Anime> = runCatching {
        val arr = JSONArray(s)
        List(arr.length()) { parseAnime(arr.getJSONObject(it)) }
    }.getOrDefault(emptyList())

    private fun saveAnimes(list: List<Anime>) = JSONArray().apply { list.forEach { put(animeJson(it)) } }.toString()

    private fun loadHistory(): List<HistoryItem> = runCatching {
        val arr = JSONArray(sp.getString("history", "[]"))
        List(arr.length()) {
            val o = arr.getJSONObject(it)
            HistoryItem(parseAnime(o), o.optInt("source"), o.optInt("episode"), o.optString("episodeName"))
        }
    }.getOrDefault(emptyList())

    private fun saveHistory() {
        val arr = JSONArray()
        history.forEach {
            arr.put(
                animeJson(it.anime)
                    .put("source", it.source)
                    .put("episode", it.episode)
                    .put("episodeName", it.episodeName)
            )
        }
        sp.edit().putString("history", arr.toString()).apply()
    }
}
