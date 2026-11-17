package com.aniplay.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.Text
import com.aniplay.core.net.Anime
import kotlinx.coroutines.delay

/** 長按「繼續觀看」的卡片：確認要不要從繼續觀看移除（觀看紀錄保留）。預設焦點在「取消」，避免誤按 */
@Composable
fun RemoveContinueDialog(anime: Anime, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val cancel = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        delay(100)
        runCatching { cancel.requestFocus() }
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier
                .width(560.dp)
                .background(CardBg, RoundedCornerShape(16.dp))
                .padding(32.dp)
        ) {
            Text("從繼續觀看移除？", fontSize = 24.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(12.dp))
            Text("「${anime.name}」不會再出現在繼續觀看，觀看紀錄會保留。之後再播放就會回來。", fontSize = 16.sp, color = Gray, lineHeight = 24.sp)
            Spacer(Modifier.height(24.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TvButton(onClick = onDismiss, modifier = Modifier.focusRequester(cancel)) { Text("取消") }
                TvButton(onClick = onConfirm) { Text("移除") }
            }
        }
    }
}
