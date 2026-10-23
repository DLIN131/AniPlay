package com.aniplay.phone

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.aniplay.core.net.Anime
import com.aniplay.core.net.Api
import com.aniplay.core.net.Query
import com.aniplay.core.net.Store
import com.aniplay.core.net.WEEK_NAMES
import com.aniplay.core.ui.FeedViewModel
import com.aniplay.core.ui.HomeViewModel
import com.aniplay.core.ui.Tags
import com.aniplay.core.ui.WeekViewModel
import com.aniplay.core.util.Updater
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import android.widget.Toast
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext

@Composable
fun TopTitle(title: String, onBack: (() -> Unit)? = null, actions: @Composable () -> Unit = {}) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(Bg)
            .statusBarsPadding()
            .padding(horizontal = 4.dp, vertical = 4.dp)
            .height(48.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回") }
        } else {
            Spacer(Modifier.padding(start = 12.dp))
        }
        Text(
            title,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        actions()
    }
}

// ---------- 首頁 ----------

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun HomeScreen() {
    val nav = LocalNav.current
    val vm: HomeViewModel = viewModel()
    LaunchedEffect(Unit) { if (vm.rows.isEmpty()) vm.load() }

    Column(Modifier.fillMaxSize()) {
        TopTitle("AniPlay")
        val err = vm.error
        when {
            vm.rows.isEmpty() && vm.loading -> Loading(Modifier.fillMaxSize())
            vm.rows.isEmpty() && err != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                ErrorBox(err, onRetry = vm::load)
            }
            else -> PullToRefreshBox(isRefreshing = vm.loading, onRefresh = vm::load) {
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
                    if (vm.banners.isNotEmpty()) {
                        item(key = "banner") {
                            val pager = rememberPagerState { vm.banners.size }
                            LaunchedEffect(pager) {
                                while (true) {
                                    delay(5_000)
                                    if (!pager.isScrollInProgress) {
                                        pager.animateScrollToPage((pager.currentPage + 1) % vm.banners.size)
                                    }
                                }
                            }
                            HorizontalPager(
                                state = pager,
                                contentPadding = PaddingValues(horizontal = 16.dp),
                                pageSpacing = 10.dp,
                            ) { i ->
                                val b = vm.banners[i]
                                Box(
                                    Modifier
                                        .fillMaxWidth()
                                        .aspectRatio(16f / 8f)
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(CardBg)
                                        .clickable { b.animeId?.let { nav.openAnime(Anime(it, b.title, "")) } }
                                ) {
                                    AsyncImage(
                                        model = b.cover,
                                        contentDescription = null,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.fillMaxSize(),
                                    )
                                    Column(
                                        Modifier
                                            .align(Alignment.BottomStart)
                                            .fillMaxWidth()
                                            .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.85f))))
                                            .padding(12.dp)
                                    ) {
                                        Text(b.title, fontSize = 17.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                                        if (b.description.isNotEmpty()) {
                                            Text(b.description, fontSize = 12.sp, color = Color.White.copy(alpha = 0.8f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                                        }
                                    }
                                }
                            }
                        }
                    }
                    val continueList = Store.history.take(12)
                    if (continueList.isNotEmpty()) {
                        item(key = "continue") {
                            SectionTitle("繼續觀看")
                            AnimeRow(continueList.map { it.anime.copy(remarks = "看到第 ${it.episodeName} 集") }, onClick = nav.openAnime)
                        }
                    }
                    items(vm.rows, key = { it.title }) { row ->
                        SectionTitle(row.title, onMore = { nav.openListing(row.title, row.query) })
                        AnimeRow(row.items, onClick = nav.openAnime)
                    }
                }
            }
        }
    }
}

// ---------- 分類 ----------

@Composable
fun BrowseScreen() {
    var typeId by rememberSaveable { mutableIntStateOf(2) }
    var order by rememberSaveable { mutableStateOf("pubdate") }
    var cls by rememberSaveable { mutableStateOf("") }
    var year by rememberSaveable { mutableStateOf("") }
    var version by rememberSaveable { mutableStateOf("") }
    fun query() = Query(typeId = typeId, orderby = order, cls = cls, year = year, version = version)
    val vm: FeedViewModel = viewModel(key = "browse") { FeedViewModel(query()) }
    fun reload() = vm.refresh(query())
    LaunchedEffect(Unit) { Tags.ensure() }
    val tags = Tags.options

    Column(Modifier.fillMaxSize()) {
        TopTitle("分類")
        AnimeGrid(vm, header = {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.padding(bottom = 4.dp)) {
                ChipRow(Api.types.map { it.first.toString() to it.second }, typeId.toString()) {
                    typeId = it.toInt(); reload()
                }
                ChipRow(Api.orders, order) { order = it; reload() }
                if (tags != null) {
                    ChipRow(listOf("" to "全部類型") + tags.classes.map { it to it }, cls) { cls = it; reload() }
                    ChipRow(listOf("" to "全部版本") + tags.versions.map { it to it }, version) { version = it; reload() }
                }
                ChipRow(listOf("" to "全部年份") + Tags.years.map { it to it }, year) { year = it; reload() }
                if (vm.total > 0) Text("共 ${vm.total} 部", fontSize = 12.sp, color = Gray, modifier = Modifier.padding(start = 16.dp, top = 4.dp))
            }
        })
    }
}

@Composable
fun ListingScreen(title: String, query: Query) {
    val nav = LocalNav.current
    val vm: FeedViewModel = viewModel(key = "list:$query") { FeedViewModel(query) }
    Surface(Modifier.fillMaxSize(), color = Bg) {
        Column(Modifier.fillMaxSize()) {
            TopTitle(title, onBack = nav.back)
            AnimeGrid(vm)
        }
    }
}

// ---------- 時間表 ----------

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun WeekScreen() {
    val nav = LocalNav.current
    val vm: WeekViewModel = viewModel()
    LaunchedEffect(Unit) { if (vm.days.isEmpty()) vm.load() }
    // Calendar：星期日＝1，換成星期一＝0
    val today = (java.util.Calendar.getInstance().get(java.util.Calendar.DAY_OF_WEEK) + 5) % 7
    var day by rememberSaveable { mutableIntStateOf(today) }

    Column(Modifier.fillMaxSize()) {
        TopTitle("新番時間表")
        PrimaryTabRow(selectedTabIndex = day, containerColor = Bg, contentColor = Accent) {
            WEEK_NAMES.forEachIndexed { i, n ->
                Tab(
                    selected = i == day,
                    onClick = { day = i },
                    text = { Text(if (i == today) "今天" else "週$n", color = if (i == day) Accent else Gray, maxLines = 1) },
                )
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
                    columns = GridCells.Adaptive(108.dp),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    items(list, key = { it.id }) { a ->
                        AnimeCard(a.copy(remarks = ""), onClick = { nav.openAnime(a) }, subtitle = a.remarks)
                    }
                }
            }
        }
    }
}

// ---------- 搜尋 ----------

@Composable
fun SearchScreen() {
    var text by rememberSaveable { mutableStateOf("") }
    var submitted by rememberSaveable { mutableStateOf("") }
    val focus = LocalFocusManager.current
    val vm: FeedViewModel = viewModel(key = "search") { FeedViewModel(Query(orderby = "hits")) }

    fun submit(word: String) {
        val w = word.trim()
        if (w.isEmpty()) return
        text = w
        submitted = w
        Store.addSearchWord(w)
        focus.clearFocus()
        vm.refresh(Query(name = w, orderby = "hits"))
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier
                .statusBarsPadding()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.weight(1f),
                singleLine = true,
                placeholder = { Text("搜尋作品名稱") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                trailingIcon = {
                    if (text.isNotEmpty()) IconButton(onClick = { text = ""; submitted = "" }) {
                        Icon(Icons.Filled.Close, contentDescription = "清除")
                    }
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { submit(text) }),
                shape = RoundedCornerShape(24.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Accent,
                    unfocusedBorderColor = CardBg,
                    focusedContainerColor = Surface,
                    unfocusedContainerColor = Surface,
                ),
            )
        }
        if (submitted.isEmpty()) {
            if (Store.searchWords.isNotEmpty()) {
                Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("搜尋紀錄", color = Gray, modifier = Modifier.weight(1f))
                    TextButton(onClick = Store::clearSearchWords) { Text("清除", color = Gray) }
                }
                LazyColumn {
                    items(Store.searchWords.toList()) { w ->
                        Text(
                            w,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { submit(w) }
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                        )
                    }
                }
            }
        } else {
            AnimeGrid(vm)
        }
    }
}

// ---------- 我的：收藏、觀看紀錄 ----------

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun MineScreen() {
    val nav = LocalNav.current
    var page by rememberSaveable { mutableIntStateOf(0) }
    var confirmClear by remember { mutableStateOf(false) }
    var checking by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    Column(Modifier.fillMaxSize()) {
        TopTitle("我的") {
            if (page == 1 && Store.history.isNotEmpty()) {
                TextButton(onClick = { confirmClear = true }) { Text("清除紀錄", color = Gray) }
            }
            TextButton(
                onClick = {
                    if (!checking) {
                        checking = true
                        scope.launch {
                            val r = runCatching { Updater.check() }
                            checking = false
                            r.getOrNull()?.let { Updater.pending = it } ?: Toast.makeText(
                                context,
                                if (r.isFailure) "檢查失敗，請稍後再試" else "已經是最新版（${Updater.currentVersionName}）",
                                Toast.LENGTH_SHORT,
                            ).show()
                        }
                    }
                },
            ) { Text(if (checking) "檢查中…" else "檢查更新", color = Gray) }
        }
        PrimaryScrollableTabRow(selectedTabIndex = page, containerColor = Bg, contentColor = Accent, edgePadding = 12.dp) {
            listOf("收藏 ${Store.favorites.size}", "觀看紀錄 ${Store.history.size}").forEachIndexed { i, t ->
                Tab(selected = i == page, onClick = { page = i }, text = { Text(t, color = if (i == page) Accent else Gray) })
            }
        }
        val list: List<Pair<Anime, String?>> = if (page == 0) {
            Store.favorites.map { it to null }
        } else {
            Store.history.map { it.anime.copy(remarks = "") to "看到第 ${it.episodeName} 集" }
        }
        if (list.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(if (page == 0) "還沒有收藏，在作品頁按「收藏」加入" else "還沒有觀看紀錄", color = Gray)
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(108.dp),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                items(list, key = { it.first.id }) { (a, sub) ->
                    AnimeCard(a, onClick = { nav.openAnime(a) }, subtitle = sub)
                }
            }
        }
    }
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("清除觀看紀錄？") },
            text = { Text("所有觀看紀錄和播放進度都會刪除。") },
            confirmButton = {
                TextButton(onClick = { Store.clearHistory(); confirmClear = false }) { Text("清除", color = Accent) }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("取消") } },
            containerColor = Surface,
        )
    }
}
