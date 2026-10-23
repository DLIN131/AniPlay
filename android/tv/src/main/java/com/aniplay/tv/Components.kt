package com.aniplay.tv

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import com.aniplay.core.net.Anime
import com.aniplay.core.ui.FeedViewModel
import kotlinx.coroutines.delay
import androidx.compose.material3.MaterialTheme as M3Theme
import androidx.compose.material3.darkColorScheme as m3DarkColorScheme
import androidx.tv.material3.MaterialTheme as TvTheme
import androidx.tv.material3.darkColorScheme as tvDarkColorScheme

val Accent = Color(0xFFFF6B9A)
val Bg = Color(0xFF101016)
val CardBg = Color(0xFF1E1E26)
val CardFocusBg = Color(0xFF2C2C36)
val Gray = Color(0xFFA0A0AE)

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    TvTheme(
        colorScheme = tvDarkColorScheme(
            primary = Accent,
            onPrimary = Color.White,
            background = Bg,
            onBackground = Color.White,
            surface = CardBg,
            onSurface = Color.White,
        )
    ) {
        M3Theme(colorScheme = m3DarkColorScheme(primary = Accent, background = Bg, surface = CardBg)) {
            CompositionLocalProvider(
                androidx.tv.material3.LocalContentColor provides Color.White,
                androidx.compose.material3.LocalContentColor provides Color.White,
                content = content,
            )
        }
    }
}

@Composable
fun TvButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    content: @Composable RowScope.() -> Unit,
) {
    Button(
        onClick = onClick,
        modifier = modifier,
        scale = ButtonDefaults.scale(focusedScale = 1.06f),
        colors = ButtonDefaults.colors(
            containerColor = if (selected) Accent else Color.White.copy(alpha = 0.1f),
            contentColor = Color.White,
            focusedContainerColor = Color.White,
            focusedContentColor = Color.Black,
            pressedContainerColor = Color.White.copy(alpha = 0.8f),
            pressedContentColor = Color.Black,
        ),
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 8.dp),
        content = content,
    )
}

@Composable
fun AnimeCard(item: Anime, onClick: () -> Unit, modifier: Modifier = Modifier, subtitle: String? = null) {
    val shape = RoundedCornerShape(10.dp)
    Card(
        onClick = onClick,
        modifier = modifier,
        shape = CardDefaults.shape(shape),
        scale = CardDefaults.scale(focusedScale = 1.07f),
        border = CardDefaults.border(focusedBorder = Border(BorderStroke(3.dp, Color.White), shape = shape)),
        colors = CardDefaults.colors(containerColor = CardBg, focusedContainerColor = CardFocusBg),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(3f / 4.2f)
                .background(Color(0xFF26262E))
        ) {
            AsyncImage(
                model = item.cover,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            if (item.remarks.isNotEmpty()) {
                Text(
                    item.remarks,
                    fontSize = 11.sp,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.8f))))
                        .padding(horizontal = 8.dp, vertical = 5.dp),
                )
            }
        }
        Column(Modifier.padding(horizontal = 8.dp, vertical = 6.dp)) {
            Text(item.name, fontSize = 13.sp, lineHeight = 17.sp, maxLines = 2, minLines = 2, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) {
                Text(subtitle, fontSize = 11.sp, color = Gray, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
fun Loading(modifier: Modifier = Modifier, size: Int = 48) {
    Box(modifier, contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = Accent, modifier = Modifier.size(size.dp))
    }
}

@Composable
fun ErrorBox(message: String, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(message, color = Gray, fontSize = 16.sp)
        Spacer(Modifier.height(12.dp))
        TvButton(onClick = onRetry) { Text("重試") }
    }
}

@Composable
fun ScreenTitle(title: String, modifier: Modifier = Modifier, actions: @Composable RowScope.() -> Unit = {}) {
    Row(
        modifier
            .fillMaxWidth()
            .padding(start = 28.dp, end = 28.dp, top = 22.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, fontSize = 26.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.weight(1f))
        actions()
    }
}

@Composable
fun ChipRow(options: List<Pair<String, String>>, selected: String, onSelect: (String) -> Unit) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 28.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(options, key = { it.first }) { (value, label) ->
            TvButton(onClick = { if (value != selected) onSelect(value) }, selected = value == selected) { Text(label) }
        }
    }
}

/** 一列橫向卡片，最後可接「查看更多」 */
@Composable
fun AnimeRow(
    title: String,
    items: List<Anime>,
    onClick: (Anime) -> Unit,
    modifier: Modifier = Modifier,
    onMore: (() -> Unit)? = null,
    subtitles: Map<Int, String> = emptyMap(),
) {
    Column(modifier) {
        Text(title, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 28.dp, bottom = 2.dp))
        LazyRow(
            contentPadding = PaddingValues(horizontal = 28.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            items(items, key = { it.id }) { a ->
                AnimeCard(a, onClick = { onClick(a) }, modifier = Modifier.width(140.dp), subtitle = subtitles[a.id])
            }
            if (onMore != null) {
                item(key = "#more") {
                    val shape = RoundedCornerShape(10.dp)
                    Card(
                        onClick = onMore,
                        modifier = Modifier
                            .width(140.dp)
                            .aspectRatio(3f / 4.2f),
                        shape = CardDefaults.shape(shape),
                        scale = CardDefaults.scale(focusedScale = 1.07f),
                        border = CardDefaults.border(focusedBorder = Border(BorderStroke(3.dp, Color.White), shape = shape)),
                        colors = CardDefaults.colors(containerColor = CardBg, focusedContainerColor = CardFocusBg),
                    ) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("查看更多 ›", fontSize = 16.sp) }
                    }
                }
            }
        }
    }
}

@Composable
fun AnimeGrid(vm: FeedViewModel, onClick: (Anime) -> Unit, modifier: Modifier = Modifier) {
    LaunchedEffect(vm) { vm.ensureLoaded() }

    if (vm.items.isEmpty()) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            val err = vm.error
            when {
                vm.loading -> Loading()
                err != null -> ErrorBox(err, onRetry = { vm.loadMore() })
                else -> Text("這裡什麼都沒有", color = Gray, fontSize = 18.sp)
            }
        }
        return
    }

    val restore = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        if (vm.restoreFocus) {
            vm.restoreFocus = false
            delay(80)
            runCatching { restore.requestFocus() }
        }
    }

    LazyVerticalGrid(
        columns = GridCells.Fixed(7),
        state = vm.gridState,
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 28.dp, vertical = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(18.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        itemsIndexed(vm.items, key = { _, it -> it.id }) { index, item ->
            if (index >= vm.items.size - 14) {
                LaunchedEffect(vm.items.size) { vm.loadMore() }
            }
            AnimeCard(
                item = item,
                onClick = {
                    vm.focusIndex = index
                    vm.restoreFocus = true
                    onClick(item)
                },
                modifier = if (index == vm.focusIndex) Modifier.focusRequester(restore) else Modifier,
            )
        }
        val err = vm.error
        if (vm.loading || err != null) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(16.dp), contentAlignment = Alignment.Center
                ) {
                    if (err != null) ErrorBox(err, onRetry = { vm.loadMore() }) else Loading(size = 32)
                }
            }
        }
    }
}
