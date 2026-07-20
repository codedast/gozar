package com.v2ray.ang.gozartahrim

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.v2ray.ang.R
import com.v2ray.ang.util.Utils

/** The public channel — also the source polled for new-post notifications. */
const val GT_CHANNEL_NAME = "gozartahrim"

/** The team's bot, used for buying a service and for support. */
const val GT_BOT_NAME = "gozartahrimbot"

const val GT_CHANNEL_URL = "https://t.me/$GT_CHANNEL_NAME"
const val GT_BOT_URL = "https://t.me/$GT_BOT_NAME"
const val GT_ADMIN_URL = "https://t.me/mehrzero"

/**
 * GozarTahrim: channel/support introduction popup.
 *
 * Re-shown on every ON_RESUME of the main screen, so it reappears whenever the user comes
 * back to the app — not just on a cold start.
 */
@Composable
fun TelegramPromoDialog() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var visible by remember { mutableStateOf(true) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) visible = true
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    if (!visible) return

    fun open(url: String) {
        visible = false
        Utils.openUri(context, url)
    }

    AlertDialog(
        onDismissRequest = { visible = false },
        title = { Text(stringResource(R.string.app_name)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.gt_promo_message))
                Text(
                    text = stringResource(
                        R.string.gt_promo_handles,
                        GT_CHANNEL_NAME, GT_BOT_NAME, "mehrzero"
                    ),
                    style = MaterialTheme.typography.bodySmall.copy(
                        textDirection = TextDirection.Ltr
                    )
                )
                TextButton(
                    onClick = { open(GT_CHANNEL_URL) },
                    modifier = Modifier.fillMaxWidth()
                ) { Text(stringResource(R.string.gt_promo_channel)) }
                TextButton(
                    onClick = { open(GT_BOT_URL) },
                    modifier = Modifier.fillMaxWidth()
                ) { Text(stringResource(R.string.gt_promo_bot)) }
                TextButton(
                    onClick = { open(GT_ADMIN_URL) },
                    modifier = Modifier.fillMaxWidth()
                ) { Text(stringResource(R.string.gt_promo_admin)) }
            }
        },
        confirmButton = {
            TextButton(onClick = { visible = false }) {
                Text(stringResource(R.string.gt_promo_close))
            }
        }
    )
}
