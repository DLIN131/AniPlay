package com.aniplay.core.ui

import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aniplay.core.net.Anime
import com.aniplay.core.net.AnimeDetail
import com.aniplay.core.net.Api
import com.aniplay.core.net.Banner
import com.aniplay.core.net.Query
import com.aniplay.core.net.TagOptions
import com.aniplay.core.net.WeekDay
import com.aniplay.core.net.friendly
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.launch

/** 首頁的一列 */
data class HomeRow(val title: String, val query: Query, val items: List<Anime>)

class HomeViewModel : ViewModel() {
    var banners by mutableStateOf<List<Banner>>(emptyList())
        private set
    val rows = mutableStateListOf<HomeRow>()
    var loading by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    private val sections = listOf(
        "本週熱門" to Query(orderby = "hits_week"),
        "最新日番" to Query(typeId = 2, orderby = "pubdate"),
        "劇場版" to Query(typeId = 21, orderby = "pubdate"),
        "美番" to Query(typeId = 3, orderby = "pubdate"),
        "高分推薦" to Query(orderby = "score"),
        "人氣經典" to Query(orderby = "hits"),
    )

    fun load() {
        if (loading) return
        loading = true
        error = null
        viewModelScope.launch {
            try {
                val b = async { runCatching { Api.banners() }.getOrDefault(emptyList()) }
                val parts = sections.map { (title, q) -> async { HomeRow(title, q, Api.list(q, 0, 16).items) } }
                val r = parts.map { it.await() }
                banners = b.await()
                rows.clear()
                rows.addAll(r.filter { it.items.isNotEmpty() })
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.friendly()
            } finally {
                loading = false
            }
        }
    }
}

/** 分頁載入的作品列表（分類、篩選、搜尋、查看更多） */
class FeedViewModel(query: Query) : ViewModel() {
    var query by mutableStateOf(query)
        private set
    val items = mutableStateListOf<Anime>()
    var total by mutableStateOf(0)
        private set
    var loading by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var end by mutableStateOf(false)
        private set

    val gridState = LazyGridState()

    /** 點進詳情頁前聚焦的位置，返回時還原（電視版用） */
    var focusIndex = -1
    var restoreFocus = false

    private var generation = 0

    fun ensureLoaded() {
        if (items.isEmpty() && !loading && error == null && !end) loadMore()
    }

    fun loadMore() {
        if (loading || end) return
        val gen = ++generation
        loading = true
        error = null
        viewModelScope.launch {
            try {
                val p = Api.list(query, items.size, PAGE)
                if (gen != generation) return@launch
                val seen = items.mapTo(HashSet()) { it.id }
                items.addAll(p.items.filter { it.id !in seen })
                total = p.total
                if (p.items.size < PAGE || items.size >= p.total) end = true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (gen == generation) error = e.friendly()
            } finally {
                if (gen == generation) loading = false
            }
        }
    }

    fun refresh(newQuery: Query = query) {
        query = newQuery
        generation++
        items.clear()
        total = 0
        loading = false
        error = null
        end = false
        focusIndex = -1
        restoreFocus = false
        viewModelScope.launch { runCatching { gridState.scrollToItem(0) } }
        loadMore()
    }

    companion object {
        const val PAGE = 30
    }
}

class DetailViewModel(private val id: Int) : ViewModel() {
    var detail by mutableStateOf<AnimeDetail?>(null)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    fun load() {
        error = null
        viewModelScope.launch {
            try {
                detail = Api.detail(id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.friendly()
            }
        }
    }
}

class WeekViewModel : ViewModel() {
    var days by mutableStateOf<List<WeekDay>>(emptyList())
        private set
    var loading by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    fun load() {
        if (loading) return
        loading = true
        error = null
        viewModelScope.launch {
            try {
                days = Api.week()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.friendly()
            } finally {
                loading = false
            }
        }
    }
}

/** 篩選用的標籤選項，抓一次就快取 */
object Tags {
    var options by mutableStateOf<TagOptions?>(null)
        private set

    suspend fun ensure() {
        if (options == null) options = runCatching { Api.tagOptions() }.getOrNull()
    }

    /** 年份：今年往回 */
    val years: List<String> by lazy {
        val now = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)
        (now downTo 2000).map { it.toString() }
    }
}

/** 更新紀錄（GitHub Releases 的說明） */
class ChangelogViewModel : ViewModel() {
    var notes by mutableStateOf<List<com.aniplay.core.util.Updater.Note>>(emptyList())
        private set
    var loading by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    fun load() {
        if (loading) return
        loading = true
        error = null
        viewModelScope.launch {
            try {
                notes = com.aniplay.core.util.Updater.fetchChangelog()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.friendly()
            } finally {
                loading = false
            }
        }
    }
}
