package com.aniplay.phone

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.aniplay.core.net.Anime
import com.aniplay.core.net.AnimeDetail
import com.aniplay.core.net.Query
import com.aniplay.core.net.Store
import com.aniplay.core.player.PlayerActivity
import com.aniplay.core.ui.DetailViewModel

@Composable
fun DetailScreen(anime: Anime) {
    val nav = LocalNav.current
    val vm: DetailViewModel = viewModel(key = "detail:${anime.id}") { DetailViewModel(anime.id) }
    LaunchedEffect(Unit) { if (vm.detail == null) vm.load() }

    Surface(Modifier.fillMaxSize(), color = Bg) {
        Column(Modifier.fillMaxSize()) {
            TopTitle(vm.detail?.anime?.name ?: anime.name, onBack = nav.back)
            val d = vm.detail
            val err = vm.error
            when {
                d != null -> DetailBody(d)
                err != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    ErrorBox(err, onRetry = vm::load)
                }
                else -> Loading(Modifier.fillMaxSize())
            }
        }
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
    var expanded by rememberSaveable { mutableStateOf(false) }

    fun play(src: Int, ep: Int) {
        val eps = d.sources[src].episodes
        PlayerActivity.start(context, a, src, eps.map { it.name }, eps.map { it.url }, ep)
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .navigationBarsPadding()
    ) {
        // 頂部：模糊背景＋封面＋基本資料
        Box(Modifier.fillMaxWidth()) {
            AsyncImage(
                model = a.cover,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .matchParentSize()
                    .blur(24.dp),
            )
            Box(
                Modifier
                    .matchParentSize()
                    .background(Brush.verticalGradient(listOf(Bg.copy(alpha = 0.55f), Bg)))
            )
            Row(Modifier.padding(16.dp)) {
                AsyncImage(
                    model = a.cover,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .width(120.dp)
                        .height(168.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(CardBg),
                )
                Spacer(Modifier.width(14.dp))
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(a.name, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    if (d.subTitle.isNotEmpty()) Text(d.subTitle, fontSize = 12.sp, color = Gray, maxLines = 2)
                    val info = listOf(a.year, d.area, d.version, d.lang).filter { it.isNotEmpty() }.joinToString(" · ")
                    if (info.isNotEmpty()) Text(info, fontSize = 12.sp, color = Gray)
                    if (a.remarks.isNotEmpty()) Text(a.remarks, fontSize = 12.sp, color = Accent)
                    if (a.score.isNotEmpty() && a.score != "0.0") Text("評分 ${a.score}", fontSize = 12.sp, color = Gray)
                    if (d.weekday.isNotEmpty()) Text("每週${d.weekday}更新", fontSize = 12.sp, color = Gray)
                }
            }
        }

        // 播放、收藏
        Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (d.sources.isNotEmpty()) {
                val resume = last?.takeIf { it.source == source && it.episode < d.sources[source].episodes.size }
                Button(
                    onClick = { play(source, resume?.episode ?: 0) },
                    colors = ButtonDefaults.buttonColors(containerColor = Accent),
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = null)
                    Spacer(Modifier.width(4.dp))
                    Text(if (resume != null) "繼續看第 ${resume.episodeName} 集" else "開始播放", maxLines = 1)
                }
            }
            val fav = Store.isFavorite(a.id)
            OutlinedButton(onClick = { Store.toggleFavorite(a) }) {
                Icon(
                    if (fav) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                    contentDescription = null,
                    tint = Accent,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(4.dp))
                Text(if (fav) "已收藏" else "收藏", color = Color.White)
            }
            val later = Store.isWatchLater(a.id)
            OutlinedButton(onClick = { Store.toggleWatchLater(a) }) {
                Icon(
                    if (later) Icons.Filled.Check else Icons.Filled.Add,
                    contentDescription = null,
                    tint = Accent,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(4.dp))
                Text("待看", color = Color.White)
            }
        }

        // 類型標籤
        if (d.classes.isNotEmpty()) {
            FlowRow(
                Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                d.classes.forEach { c ->
                    Text(
                        c,
                        fontSize = 12.sp,
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(CardBg)
                            .clickable { nav.openListing(c, Query(cls = c, orderby = "hits")) }
                            .padding(horizontal = 10.dp, vertical = 4.dp),
                    )
                }
            }
        }

        // 簡介
        if (d.blurb.isNotEmpty()) {
            Text(
                d.blurb,
                fontSize = 13.sp,
                lineHeight = 20.sp,
                color = Color.White.copy(alpha = 0.85f),
                maxLines = if (expanded) Int.MAX_VALUE else 4,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .padding(horizontal = 16.dp)
                    .clickable { expanded = !expanded },
            )
        }
        val staff = listOfNotNull(
            d.director.takeIf { it.isNotEmpty() }?.let { "導演：$it" },
            d.actors.takeIf { it.isNotEmpty() }?.let { "聲優：$it" },
        )
        if (expanded) staff.forEach {
            Text(it, fontSize = 12.sp, color = Gray, modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp))
        }

        // 線路與選集
        if (d.sources.isEmpty()) {
            Text("這部作品暫時沒有播放來源", color = Gray, modifier = Modifier.padding(16.dp))
        } else {
            Spacer(Modifier.height(12.dp))
            if (d.sources.size > 1) {
                ChipRow(d.sources.mapIndexed { i, s -> i.toString() to s.name }, source.toString()) { source = it.toInt() }
            }
            val eps = d.sources[source].episodes
            Text("選集（共 ${eps.size} 集）", fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 8.dp))
            FlowRow(
                Modifier.padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                eps.forEachIndexed { i, e ->
                    val current = last != null && last.source == source && last.episode == i
                    Text(
                        e.name,
                        fontSize = 13.sp,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        color = if (current) Color.White else Color.White.copy(alpha = 0.9f),
                        modifier = Modifier
                            .width(76.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (current) Accent else CardBg)
                            .clickable { play(source, i) }
                            .padding(vertical = 10.dp, horizontal = 4.dp),
                    )
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}
