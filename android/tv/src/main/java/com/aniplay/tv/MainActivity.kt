package com.aniplay.tv

import android.app.Activity
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.DrawerValue
import androidx.tv.material3.Icon
import androidx.tv.material3.ModalNavigationDrawer
import androidx.tv.material3.NavigationDrawerItem
import androidx.tv.material3.Text
import androidx.tv.material3.rememberDrawerState
import com.aniplay.core.net.Anime
import com.aniplay.core.Prefs
import com.aniplay.core.net.Query
import com.aniplay.core.util.Updater
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 同一個 Release 裡手機版是 AniPlay.apk，電視版要抓自己的檔案
        Updater.apkName = "AniPlayTV.apk"
        setContent { AppTheme { AppRoot() } }
    }
}

enum class Tab(val label: String, val icon: ImageVector) {
    Home("首頁", Icons.Filled.Home),
    Browse("分類", Icons.Filled.List),
    Week("時間表", Icons.Filled.DateRange),
    Search("搜尋", Icons.Filled.Search),
    Favorites("收藏", Icons.Filled.Favorite),
    WatchLater("待看清單", Icons.Filled.Add),
    History("觀看紀錄", Icons.Filled.Refresh),
}

sealed interface Route {
    data class Detail(val anime: Anime) : Route
    data class Listing(val title: String, val query: Query) : Route
}

class Nav(
    val openAnime: (Anime) -> Unit,
    val openListing: (String, Query) -> Unit,
)

val LocalNav = staticCompositionLocalOf<Nav> { error("Nav 尚未提供") }

@Composable
fun AppRoot() {
    var tab by rememberSaveable { mutableStateOf(Tab.Home) }
    var firstLaunch by remember { mutableStateOf(true) }
    val stack = remember { mutableStateListOf<Route>() }
    val nav = remember {
        Nav(
            openAnime = { stack.add(Route.Detail(it)) },
            openListing = { t, q -> stack.add(Route.Listing(t, q)) },
        )
    }

    CompositionLocalProvider(LocalNav provides nav) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Bg)
        ) {
            val top = stack.lastOrNull()
            if (top == null) {
                HomeScaffold(tab = tab, onTab = { tab = it }, focusDrawer = firstLaunch, onFocused = { firstLaunch = false })
            } else {
                key(stack.size, top) {
                    when (top) {
                        is Route.Detail -> DetailScreen(top.anime)
                        is Route.Listing -> ListingScreen(top.title, top.query)
                    }
                }
            }
        }
    }
    BackHandler(enabled = stack.isNotEmpty()) { stack.removeAt(stack.lastIndex) }

    // 開啟時檢查 GitHub 有沒有新版，由使用者決定要不要更新（debug 版的 versionCode 固定是 1，不自動檢查）
    LaunchedEffect(Unit) {
        if (!Updater.isDebugBuild) {
            runCatching { Updater.check() }.getOrNull()
                ?.takeIf { it.versionCode != Prefs.skippedVersion }
                ?.let { Updater.pending = it }
        }
    }
    Updater.pending?.let { release ->
        UpdateDialog(
            release,
            onDismiss = { Updater.pending = null },
            onSkip = {
                Prefs.skippedVersion = release.versionCode
                Updater.pending = null
            },
        )
    }
}

@Composable
fun HomeScaffold(tab: Tab, onTab: (Tab) -> Unit, focusDrawer: Boolean, onFocused: () -> Unit) {
    val activity = LocalContext.current as Activity
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val requesters = remember { Tab.entries.associateWith { FocusRequester() } }
    var drawerFocused by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        if (focusDrawer) {
            delay(100)
            runCatching { requesters.getValue(tab).requestFocus() }
            onFocused()
        }
    }

    // 內容區按返回先回到側邊選單，在選單上再按返回才離開
    BackHandler {
        if (drawerFocused) activity.finish()
        else runCatching { requesters.getValue(tab).requestFocus() }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = { value ->
            Column(
                Modifier
                    .fillMaxHeight()
                    .background(if (value == DrawerValue.Open) Color(0xFF18181F) else Color.Transparent)
                    .verticalScroll(rememberScrollState())
                    .padding(12.dp)
                    .onFocusChanged { drawerFocused = it.hasFocus }
                    .selectableGroup(),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Spacer(Modifier.height(16.dp))
                Text(
                    if (value == DrawerValue.Open) "AniPlay" else "A",
                    color = Accent,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(start = 16.dp, bottom = 16.dp),
                )
                Tab.entries.forEach { t ->
                    NavigationDrawerItem(
                        selected = t == tab,
                        onClick = { onTab(t) },
                        leadingContent = { Icon(t.icon, contentDescription = null) },
                        modifier = Modifier.focusRequester(requesters.getValue(t)),
                    ) { Text(t.label) }
                }
            }
        },
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .padding(start = 92.dp)
        ) {
            when (tab) {
                Tab.Home -> HomeScreen()
                Tab.Browse -> BrowseScreen()
                Tab.Week -> WeekScreen()
                Tab.Search -> SearchScreen()
                Tab.Favorites -> LocalListScreen(LocalList.Favorites)
                Tab.WatchLater -> LocalListScreen(LocalList.WatchLater)
                Tab.History -> LocalListScreen(LocalList.History)
            }
        }
    }
}
