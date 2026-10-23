package com.aniplay.phone

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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.aniplay.core.net.Anime
import com.aniplay.core.ui.FeedViewModel

val Accent = Color(0xFFFF6B9A)
val Bg = Color(0xFF121218)
val Surface = Color(0xFF1C1C24)
val CardBg = Color(0xFF24242E)
val Gray = Color(0xFFA0A0AE)

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Accent,
            onPrimary = Color.White,
            background = Bg,
            onBackground = Color.White,
            surface = Surface,
            onSurface = Color.White,
            surfaceVariant = CardBg,
            secondaryContainer = Accent.copy(alpha = 0.25f),
        )
    ) {
        CompositionLocalProvider(LocalContentColor provides Color.White, content = content)
    }
}

/** 直式封面卡片 */
@Composable
fun AnimeCard(item: Anime, onClick: () -> Unit, modifier: Modifier = Modifier, subtitle: String? = null) {
    Column(modifier.clickable(onClick = onClick)) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(3f / 4.2f)
                .clip(RoundedCornerShape(10.dp))
                .background(CardBg)
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
                    fontSize = 10.sp,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.8f))))
                        .padding(horizontal = 6.dp, vertical = 4.dp),
                )
            }
            if (item.score.isNotEmpty() && item.score != "0.0") {
                Text(
                    item.score,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(4.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(Accent)
                        .padding(horizontal = 4.dp, vertical = 1.dp),
                )
            }
        }
        Spacer(Modifier.height(5.dp))
        Text(
            item.name,
            fontSize = 13.sp,
            lineHeight = 17.sp,
            maxLines = 2,
            minLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (subtitle != null) {
            Text(subtitle, fontSize = 11.sp, color = Gray, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
fun Loading(modifier: Modifier = Modifier, size: Int = 40) {
    Box(modifier, contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = Accent, modifier = Modifier.size(size.dp))
    }
}

@Composable
fun ErrorBox(message: String, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(message, color = Gray, fontSize = 15.sp)
        TextButton(onClick = onRetry) { Text("重試", color = Accent) }
    }
}

@Composable
fun SectionTitle(title: String, onMore: (() -> Unit)? = null) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 8.dp, top = 16.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        if (onMore != null) TextButton(onClick = onMore) { Text("更多 ›", color = Gray) }
    }
}

@Composable
fun AnimeRow(items: List<Anime>, onClick: (Anime) -> Unit) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(items, key = { it.id }) { a ->
            AnimeCard(a, onClick = { onClick(a) }, modifier = Modifier.width(112.dp))
        }
    }
}

@Composable
fun ChipRow(options: List<Pair<String, String>>, selected: String, onSelect: (String) -> Unit) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        items(options, key = { it.first }) { (value, label) ->
            FilterChip(
                selected = value == selected,
                onClick = { if (value != selected) onSelect(value) },
                label = { Text(label, fontSize = 13.sp) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = Accent,
                    selectedLabelColor = Color.White,
                    labelColor = Gray,
                ),
                border = null,
            )
        }
    }
}

/** 分頁載入的作品網格 */
@Composable
fun AnimeGrid(vm: FeedViewModel, modifier: Modifier = Modifier, header: (@Composable () -> Unit)? = null) {
    val nav = LocalNav.current
    LaunchedEffect(vm) { vm.ensureLoaded() }

    LazyVerticalGrid(
        columns = GridCells.Adaptive(108.dp),
        state = vm.gridState,
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (header != null) item(span = { GridItemSpan(maxLineSpan) }) { header() }
        itemsIndexed(vm.items, key = { _, it -> it.id }) { index, item ->
            if (index >= vm.items.size - 9) {
                LaunchedEffect(vm.items.size) { vm.loadMore() }
            }
            AnimeCard(item, onClick = { nav.openAnime(item) })
        }
        item(span = { GridItemSpan(maxLineSpan) }) {
            val err = vm.error
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(16.dp), contentAlignment = Alignment.Center
            ) {
                when {
                    err != null -> ErrorBox(err, onRetry = { vm.loadMore() })
                    vm.loading -> Loading(size = 32)
                    vm.items.isEmpty() -> Text("這裡什麼都沒有", color = Gray)
                    vm.end -> Text("沒有更多了", color = Gray, fontSize = 12.sp)
                }
            }
        }
    }
}
