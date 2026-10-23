package com.aniplay.tv

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.Text
import com.aniplay.core.net.Api
import com.aniplay.core.net.Query
import com.aniplay.core.net.Store
import com.aniplay.core.net.WEEK_NAMES
import com.aniplay.core.ui.FeedViewModel
import com.aniplay.core.ui.HomeViewModel
import com.aniplay.core.ui.Tags
import com.aniplay.core.ui.WeekViewModel

@Composable
fun HomeScreen() {
    val nav = LocalNav.current
    val vm: HomeViewModel = viewModel()
    LaunchedEffect(Unit) { if (vm.rows.isEmpty()) vm.load() }

    val err = vm.error
    when {
        vm.rows.isEmpty() && vm.loading -> Loading(Modifier.fillMaxSize())
        vm.rows.isEmpty() && err != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            ErrorBox(err, onRetry = vm::load)
        }
        else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 20.dp)) {
            val later = Store.watchLater.take(15)
            if (later.isNotEmpty()) {
                item(key = "later") { AnimeRow("待看清單", later, onClick = nav.openAnime) }
            }
            val cont = Store.history.take(15)
            if (cont.isNotEmpty()) {
                item(key = "continue") {
                    AnimeRow(
                        "繼續觀看",
                        cont.map { it.anime.copy(remarks = "") },
                        onClick = nav.openAnime,
                        subtitles = cont.associate { it.anime.id to "看到第 ${it.episodeName} 集" },
                    )
                }
            }
            items(vm.rows, key = { it.title }) { row ->
                AnimeRow(row.title, row.items, onClick = nav.openAnime, onMore = { nav.openListing(row.title, row.query) })
            }
        }
    }
}

@Composable
fun BrowseScreen() {
    val nav = LocalNav.current
    var typeId by rememberSaveable { mutableIntStateOf(2) }
    var order by rememberSaveable { mutableStateOf("pubdate") }
    var cls by rememberSaveable { mutableStateOf("") }
    var year by rememberSaveable { mutableStateOf("") }
    fun query() = Query(typeId = typeId, orderby = order, cls = cls, year = year)
    val vm: FeedViewModel = viewModel(key = "browse") { FeedViewModel(query()) }
    fun reload() = vm.refresh(query())
    LaunchedEffect(Unit) { Tags.ensure() }

    Column(Modifier.fillMaxSize()) {
        ScreenTitle("分類") {
            if (vm.total > 0) Text("共 ${vm.total} 部", color = Gray)
        }
        ChipRow(Api.types.map { it.first.toString() to it.second }, typeId.toString()) { typeId = it.toInt(); reload() }
        ChipRow(Api.orders, order) { order = it; reload() }
        Tags.options?.let { t ->
            ChipRow(listOf("" to "全部類型") + t.classes.map { it to it }, cls) { cls = it; reload() }
        }
        ChipRow(listOf("" to "全部年份") + Tags.years.map { it to it }, year) { year = it; reload() }
        AnimeGrid(vm, onClick = nav.openAnime)
    }
}

@Composable
fun ListingScreen(title: String, query: Query) {
    val nav = LocalNav.current
    val vm: FeedViewModel = viewModel(key = "list:$query") { FeedViewModel(query) }
    Column(Modifier.fillMaxSize()) {
        ScreenTitle(title)
        AnimeGrid(vm, onClick = nav.openAnime)
    }
}

@Composable
fun WeekScreen() {
    val nav = LocalNav.current
    val vm: WeekViewModel = viewModel()
    LaunchedEffect(Unit) { if (vm.days.isEmpty()) vm.load() }
    val today = (java.util.Calendar.getInstance().get(java.util.Calendar.DAY_OF_WEEK) + 5) % 7
    var day by rememberSaveable { mutableIntStateOf(today) }

    Column(Modifier.fillMaxSize()) {
        ScreenTitle("新番時間表")
        LazyRow(
            contentPadding = PaddingValues(horizontal = 28.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(7) { i ->
                TvButton(onClick = { day = i }, selected = i == day) {
                    Text(if (i == today) "今天" else "週${WEEK_NAMES[i]}")
                }
            }
        }
        val err = vm.error
        when {
            vm.loading && vm.days.isEmpty() -> Loading(Modifier.fillMaxSize())
            err != null && vm.days.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                ErrorBox(err, onRetry = vm::load)
            }
            else -> {
                val list = vm.days.firstOrNull { it.week == day + 1 }?.items.orEmpty()
                LazyVerticalGrid(
                    columns = GridCells.Fixed(7),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 28.dp, vertical = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(18.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp),
                ) {
                    items(list, key = { it.id }) { a ->
                        AnimeCard(a.copy(remarks = ""), onClick = { nav.openAnime(a) }, subtitle = a.remarks)
                    }
                }
            }
        }
    }
}

@Composable
fun SearchScreen() {
    val nav = LocalNav.current
    var text by rememberSaveable { mutableStateOf("") }
    var submitted by rememberSaveable { mutableStateOf("") }
    var focused by remember { mutableStateOf(false) }
    val vm: FeedViewModel = viewModel(key = "search") { FeedViewModel(Query(orderby = "hits")) }

    fun submit(word: String) {
        val w = word.trim()
        if (w.isEmpty()) return
        text = w
        submitted = w
        Store.addSearchWord(w)
        vm.refresh(Query(name = w, orderby = "hits"))
    }

    Column(Modifier.fillMaxSize()) {
        ScreenTitle("搜尋")
        Row(Modifier.padding(horizontal = 28.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            BasicTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                textStyle = TextStyle(color = Color.White, fontSize = 18.sp),
                cursorBrush = SolidColor(Accent),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { submit(text) }),
                modifier = Modifier
                    .width(520.dp)
                    .onFocusChanged { focused = it.isFocused }
                    .background(CardBg, RoundedCornerShape(24.dp))
                    .border(BorderStroke(2.dp, if (focused) Color.White else Color.Transparent), RoundedCornerShape(24.dp))
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                decorationBox = { inner ->
                    if (text.isEmpty()) Text("輸入作品名稱，按 OK 開啟鍵盤", color = Gray, fontSize = 18.sp)
                    inner()
                },
            )
            Spacer(Modifier.width(12.dp))
            TvButton(onClick = { submit(text) }) { Text("搜尋") }
        }
        if (submitted.isEmpty()) {
            if (Store.searchWords.isNotEmpty()) {
                Row(Modifier.padding(horizontal = 28.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("搜尋紀錄", color = Gray)
                    Spacer(Modifier.width(12.dp))
                    TvButton(onClick = Store::clearSearchWords) { Text("清除") }
                }
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 28.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(Store.searchWords.toList()) { w -> TvButton(onClick = { submit(w) }) { Text(w) } }
                }
            }
        } else {
            AnimeGrid(vm, onClick = nav.openAnime)
        }
    }
}

enum class LocalList(val title: String, val empty: String) {
    Favorites("收藏", "還沒有收藏，在作品頁按「收藏」加入"),
    WatchLater("待看清單", "待看清單是空的，在作品頁按「＋ 待看」加入"),
    History("觀看紀錄", "還沒有觀看紀錄"),
}

@Composable
fun LocalListScreen(kind: LocalList) {
    val nav = LocalNav.current
    Column(Modifier.fillMaxSize()) {
        ScreenTitle(kind.title) {
            if (kind == LocalList.History && Store.history.isNotEmpty()) TvButton(onClick = Store::clearHistory) { Text("清除全部紀錄") }
        }
        val list: List<Pair<com.aniplay.core.net.Anime, String?>> = when (kind) {
            LocalList.Favorites -> Store.favorites.map { it to null }
            LocalList.WatchLater -> Store.watchLater.map { it to null }
            LocalList.History -> Store.history.map { it.anime.copy(remarks = "") to "看到第 ${it.episodeName} 集" }
        }
        if (list.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(kind.empty, color = Gray, fontSize = 18.sp)
            }
            return
        }
        LazyVerticalGrid(
            columns = GridCells.Fixed(7),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 28.dp, vertical = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(18.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            items(list, key = { it.first.id }) { (a, sub) ->
                AnimeCard(a, onClick = { nav.openAnime(a) }, subtitle = sub)
            }
        }
        Spacer(Modifier.height(8.dp))
        Box(Modifier.fillMaxWidth())
    }
}
