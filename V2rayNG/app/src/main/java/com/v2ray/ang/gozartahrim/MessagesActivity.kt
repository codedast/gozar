package com.v2ray.ang.gozartahrim

import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.v2ray.ang.R
import com.v2ray.ang.ui.BaseComponentActivity
import com.v2ray.ang.util.Utils

/**
 * GozarTahrim: a one-way "chat box" showing the most recent admin announcements and
 * GozarTahrim channel posts, each tagged with a coloured source label.
 */
class MessagesActivity : BaseComponentActivity() {

    @Composable
    override fun ScreenContent() {
        MessagesScreen()
    }
}

// Distinct colours per source.
private val AdminColor = Color(0xFF2E7D32)    // green — admin/server messages
private val ChannelColor = Color(0xFF1565C0)  // blue — GozarTahrim Telegram channel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MessagesScreen() {
    val context = LocalContext.current
    var messages by remember { mutableStateOf(MessageStore.all()) }
    var showClearConfirm by remember { mutableStateOf(false) }

    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            title = { Text(stringResource(R.string.gt_messages_clear)) },
            text = { Text(stringResource(R.string.gt_messages_clear_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    MessageStore.clear()
                    messages = emptyList()
                    showClearConfirm = false
                }) { Text(stringResource(R.string.gt_messages_clear)) }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirm = false }) {
                    Text(stringResource(R.string.gt_promo_close))
                }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.title_gt_messages)) },
                actions = {
                    if (messages.isNotEmpty()) {
                        TextButton(onClick = { showClearConfirm = true }) {
                            Text(stringResource(R.string.gt_messages_clear))
                        }
                    }
                }
            )
        }
    ) { padding ->
        if (messages.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(stringResource(R.string.gt_messages_empty), style = MaterialTheme.typography.bodyMedium)
            }
            return@Scaffold
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(messages) { m ->
                val isAdmin = m.source == GozarMsgSource.ADMIN
                val accent = if (isAdmin) AdminColor else ChannelColor
                val label = stringResource(
                    if (isAdmin) R.string.gt_msg_label_admin else R.string.gt_msg_label_channel
                )
                val safeLink = GozarLinks.safeWeb(m.link)
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(accent.copy(alpha = 0.10f))
                        .then(
                            if (safeLink != null)
                                Modifier.clickable { Utils.openUri(context, safeLink) }
                            else Modifier
                        )
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = label,
                            color = accent,
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp,
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(accent.copy(alpha = 0.18f))
                                .padding(horizontal = 8.dp, vertical = 2.dp)
                        )
                        Text(
                            text = DateUtils.getRelativeTimeSpanString(m.ts).toString(),
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (m.title.isNotEmpty()) {
                        Text(m.title, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
                    }
                    if (m.body.isNotEmpty()) {
                        Text(m.body, style = MaterialTheme.typography.bodyMedium)
                    }
                    if (m.link.isNotEmpty()) {
                        Text(
                            text = m.link,
                            color = accent,
                            fontSize = 12.sp,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        }
    }
}
