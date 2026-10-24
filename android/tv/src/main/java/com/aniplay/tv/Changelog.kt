package com.aniplay.tv

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.Text
import com.aniplay.core.ui.ChangelogViewModel
import com.aniplay.core.util.Updater
import kotlinx.coroutines.delay

/** 更新紀錄：每個版本更新了什麼。每個版本是一張可聚焦的卡片，方向鍵上下就能捲動 */
@Composable
fun ChangelogScreen() {
    val vm: ChangelogViewModel = viewModel()
    LaunchedEffect(Unit) { if (vm.notes.isEmpty()) vm.load() }

    Column(Modifier.fillMaxSize()) {
        ScreenTitle("更新紀錄") {
            Text("目前版本 ${Updater.currentVersionName}", color = Gray)
        }
        val err = vm.error
        when {
            vm.notes.isEmpty() && vm.loading -> Loading(Modifier.fillMaxSize())
            vm.notes.isEmpty() && err != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                ErrorBox(err, onRetry = vm::load)
            }
            else -> LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 28.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                items(vm.notes, key = { it.versionCode }) { n ->
                    val shape = RoundedCornerShape(12.dp)
                    Card(
                        onClick = {},
                        modifier = Modifier.fillMaxWidth(),
                        shape = CardDefaults.shape(shape),
                        scale = CardDefaults.scale(focusedScale = 1.01f),
                        border = CardDefaults.border(focusedBorder = Border(BorderStroke(2.dp, Color.White), shape = shape)),
                        colors = CardDefaults.colors(containerColor = CardBg, focusedContainerColor = CardFocusBg),
                    ) {
                        Column(Modifier.padding(18.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(n.version, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                                if (n.versionCode == Updater.currentVersionCode) {
                                    Spacer(Modifier.width(10.dp))
                                    Text(
                                        "目前版本",
                                        fontSize = 13.sp,
                                        modifier = Modifier
                                            .background(Accent, RoundedCornerShape(6.dp))
                                            .padding(horizontal = 8.dp, vertical = 2.dp),
                                    )
                                }
                                Spacer(Modifier.weight(1f))
                                Text(n.date, fontSize = 14.sp, color = Gray)
                            }
                            if (n.notes.isNotBlank()) {
                                Spacer(Modifier.height(10.dp))
                                Text(n.notes, fontSize = 16.sp, lineHeight = 24.sp, color = Color.White.copy(alpha = 0.88f))
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 剛更新完第一次開啟時顯示 */
@Composable
fun WhatsNewDialog(note: Updater.Note, onDismiss: () -> Unit) {
    val ok = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        delay(100)
        runCatching { ok.requestFocus() }
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier
                .width(640.dp)
                .background(CardBg, RoundedCornerShape(16.dp))
                .padding(32.dp)
        ) {
            Text("已更新到 ${note.version}", fontSize = 24.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            Text("這次更新了什麼（之後可以在側邊選單「更新紀錄」再看）", fontSize = 14.sp, color = Gray)
            Spacer(Modifier.height(14.dp))
            Text(
                note.notes.ifBlank { "（這個版本沒有寫更新說明）" },
                fontSize = 16.sp,
                lineHeight = 24.sp,
                modifier = Modifier
                    .heightIn(max = 320.dp)
                    .verticalScroll(rememberScrollState()),
            )
            Spacer(Modifier.height(24.dp))
            TvButton(onClick = onDismiss, modifier = Modifier.focusRequester(ok)) { Text("知道了") }
        }
    }
}
