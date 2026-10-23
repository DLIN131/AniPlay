package com.aniplay.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import com.aniplay.core.net.Anime
import com.aniplay.core.net.AnimeDetail
import com.aniplay.core.net.Query
import com.aniplay.core.net.Store
import com.aniplay.core.player.PlayerActivity
import com.aniplay.core.ui.DetailViewModel
import kotlinx.coroutines.delay

@Composable
fun DetailScreen(anime: Anime) {
    val vm: DetailViewModel = viewModel(key = "detail:${anime.id}") { DetailViewModel(anime.id) }
    LaunchedEffect(Unit) { if (vm.detail == null) vm.load() }
    val d = vm.detail
    val err = vm.error
    when {
        d != null -> DetailBody(d)
        err != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { ErrorBox(err, onRetry = vm::load) }
        else -> Loading(Modifier.fillMaxSize())
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DetailBody(d: AnimeDetail) {
    val context = LocalContext.current
    val nav = LocalNav.current
    val a = d.anime
    val last = Store.lastWatched(a.id)
    var source by rememberSaveable { mutableIntStateOf(last?.source?.coerceIn(0, (d.sources.size - 1).coerceAtLeast(0)) ?: 0) }
    val playFocus = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        delay(100)
        runCatching { playFocus.requestFocus() }
    }

    fun play(src: Int, ep: Int) {
        val eps = d.sources[src].episodes
        PlayerActivity.start(context, a, src, eps.map { it.name }, eps.map { it.url }, ep)
    }

    Box(Modifier.fillMaxSize()) {
        AsyncImage(
            model = a.cover,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .matchParentSize()
                .blur(40.dp),
        )
        Box(
            Modifier
                .matchParentSize()
                .background(Brush.horizontalGradient(listOf(Bg.copy(alpha = 0.92f), Bg.copy(alpha = 0.75f))))
        )
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 48.dp, vertical = 32.dp)
        ) {
            Row {
                AsyncImage(
                    model = a.cover,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .width(200.dp)
                        .height(280.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(CardBg),
                )
                Spacer(Modifier.width(32.dp))
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(a.name, fontSize = 30.sp, fontWeight = FontWeight.Bold)
                    if (d.subTitle.isNotEmpty()) Text(d.subTitle, color = Gray, fontSize = 15.sp)
                    val info = listOf(a.year, d.area, d.version, d.lang, a.remarks).filter { it.isNotEmpty() }.joinToString("  ·  ")
                    if (info.isNotEmpty()) Text(info, color = Gray, fontSize = 15.sp)
                    if (d.classes.isNotEmpty()) Text(d.classes.joinToString(" / "), color = Accent, fontSize = 15.sp)
                    if (d.blurb.isNotEmpty()) {
                        Text(
                            d.blurb,
                            fontSize = 15.sp,
                            lineHeight = 22.sp,
                            color = Color.White.copy(alpha = 0.85f),
                            maxLines = 4,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (d.sources.isNotEmpty()) {
                            val resume = last?.takeIf { it.source == source && it.episode < d.sources[source].episodes.size }
                            TvButton(
                                onClick = { play(source, resume?.episode ?: 0) },
                                modifier = Modifier.focusRequester(playFocus),
                            ) { Text(if (resume != null) "▶ 繼續看第 ${resume.episodeName} 集" else "▶ 開始播放") }
                        }
                        val fav = Store.isFavorite(a.id)
                        TvButton(
                            onClick = { Store.toggleFavorite(a) },
                            modifier = if (d.sources.isEmpty()) Modifier.focusRequester(playFocus) else Modifier,
                        ) { Text(if (fav) "♥ 已收藏" else "♡ 收藏") }
                        d.classes.firstOrNull()?.let { c ->
                            TvButton(onClick = { nav.openListing(c, Query(cls = c, orderby = "hits")) }) { Text("更多$c") }
                        }
                    }
                }
            }

            if (d.sources.isEmpty()) {
                Spacer(Modifier.height(24.dp))
                Text("這部作品暫時沒有播放來源", color = Gray, fontSize = 18.sp)
            } else {
                Spacer(Modifier.height(24.dp))
                if (d.sources.size > 1) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        d.sources.forEachIndexed { i, s ->
                            TvButton(onClick = { source = i }, selected = i == source) { Text(s.name) }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                }
                val eps = d.sources[source].episodes
                Text("選集（共 ${eps.size} 集）", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(10.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    eps.forEachIndexed { i, e ->
                        val current = last != null && last.source == source && last.episode == i
                        TvButton(
                            onClick = { play(source, i) },
                            selected = current,
                            modifier = Modifier.width(96.dp),
                        ) { Text(e.name, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    }
                }
            }
        }
    }
}
