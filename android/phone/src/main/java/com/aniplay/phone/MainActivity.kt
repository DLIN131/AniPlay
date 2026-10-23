package com.aniplay.phone

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.vector.ImageVector
import com.aniplay.core.net.Anime
import com.aniplay.core.Prefs
import com.aniplay.core.net.Query
import com.aniplay.core.util.Updater

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { AppTheme { AppRoot() } }
    }
}

enum class Tab(val label: String, val icon: ImageVector) {
    Home("首頁", Icons.Filled.Home),
    Browse("分類", Icons.Filled.List),
    Week("時間表", Icons.Filled.DateRange),
    Search("搜尋", Icons.Filled.Search),
    Mine("我的", Icons.Filled.Person),
}

sealed interface Route {
    data class Detail(val anime: Anime) : Route
    data class Listing(val title: String, val query: Query) : Route
}

class Nav(
    val openAnime: (Anime) -> Unit,
    val openListing: (String, Query) -> Unit,
    val back: () -> Unit,
)

val LocalNav = staticCompositionLocalOf<Nav> { error("Nav 尚未提供") }

@Composable
fun AppRoot() {
    var tab by rememberSaveable { mutableStateOf(Tab.Home) }
    val stack = remember { mutableStateListOf<Route>() }
    val nav = remember {
        Nav(
            openAnime = { stack.add(Route.Detail(it)) },
            openListing = { t, q -> stack.add(Route.Listing(t, q)) },
            back = { if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex) },
        )
    }
    BackHandler(enabled = stack.isNotEmpty()) { nav.back() }
    BackHandler(enabled = stack.isEmpty() && tab != Tab.Home) { tab = Tab.Home }

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

    CompositionLocalProvider(LocalNav provides nav) {
        // 分頁畫面常駐在下面，疊上去的詳情頁返回時分頁的捲動位置還在
        Box(Modifier.fillMaxSize()) {
            Scaffold(
                containerColor = Bg,
                bottomBar = {
                    NavigationBar(containerColor = Surface) {
                        Tab.entries.forEach { t ->
                            NavigationBarItem(
                                selected = t == tab,
                                onClick = { tab = t },
                                icon = { Icon(t.icon, contentDescription = null) },
                                label = { Text(t.label) },
                                colors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = Accent,
                                    selectedTextColor = Accent,
                                    indicatorColor = Accent.copy(alpha = 0.15f),
                                    unselectedIconColor = Gray,
                                    unselectedTextColor = Gray,
                                ),
                            )
                        }
                    }
                },
            ) { pad ->
                Box(Modifier.padding(pad)) {
                    when (tab) {
                        Tab.Home -> HomeScreen()
                        Tab.Browse -> BrowseScreen()
                        Tab.Week -> WeekScreen()
                        Tab.Search -> SearchScreen()
                        Tab.Mine -> MineScreen()
                    }
                }
            }
            stack.lastOrNull()?.let { top ->
                key(stack.size, top) {
                    when (top) {
                        is Route.Detail -> DetailScreen(top.anime)
                        is Route.Listing -> ListingScreen(top.title, top.query)
                    }
                }
            }
        }
    }
}
