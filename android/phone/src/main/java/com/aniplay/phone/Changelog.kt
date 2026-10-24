package com.aniplay.phone

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aniplay.core.ui.ChangelogViewModel
import com.aniplay.core.util.Updater

/** 更新紀錄：每個版本更新了什麼（GitHub Releases 的說明） */
@Composable
fun ChangelogScreen() {
    val nav = LocalNav.current
    val vm: ChangelogViewModel = viewModel()
    LaunchedEffect(Unit) { if (vm.notes.isEmpty()) vm.load() }

    Surface(Modifier.fillMaxSize(), color = Bg) {
        Column(Modifier.fillMaxSize()) {
            TopTitle("更新紀錄", onBack = nav.back)
            val err = vm.error
            when {
                vm.notes.isEmpty() && vm.loading -> Loading(Modifier.fillMaxSize())
                vm.notes.isEmpty() && err != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    ErrorBox(err, onRetry = vm::load)
                }
                else -> LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(vm.notes, key = { it.versionCode }) { n -> NoteCard(n, current = n.versionCode == Updater.currentVersionCode) }
                }
            }
        }
    }
}

@Composable
private fun NoteCard(n: Updater.Note, current: Boolean) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(CardBg, RoundedCornerShape(12.dp))
            .padding(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(n.version, fontSize = 17.sp, fontWeight = FontWeight.Bold)
            if (current) {
                Spacer(Modifier.padding(start = 8.dp))
                Text(
                    "目前版本",
                    fontSize = 11.sp,
                    color = Color.White,
                    modifier = Modifier
                        .background(Accent, RoundedCornerShape(6.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
            Spacer(Modifier.weight(1f))
            Text(n.date, fontSize = 12.sp, color = Gray)
        }
        if (n.notes.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(n.notes, fontSize = 14.sp, lineHeight = 21.sp, color = Color.White.copy(alpha = 0.88f))
        }
    }
}

/** 剛更新完第一次開啟時顯示 */
@Composable
fun WhatsNewDialog(note: Updater.Note, onDismiss: () -> Unit, onMore: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("已更新到 ${note.version}") },
        text = {
            Column(
                Modifier
                    .heightIn(max = 360.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text("這次更新了什麼", fontSize = 13.sp, color = Gray)
                Spacer(Modifier.height(8.dp))
                Text(note.notes.ifBlank { "（這個版本沒有寫更新說明）" }, fontSize = 14.sp, lineHeight = 21.sp)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("知道了", color = Accent) } },
        dismissButton = { TextButton(onClick = onMore) { Text("所有更新紀錄", color = Gray) } },
        containerColor = Surface,
    )
}
