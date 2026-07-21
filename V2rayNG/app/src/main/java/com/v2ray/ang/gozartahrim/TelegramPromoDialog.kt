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
import androidx.compose.runtime.LaunchedEffect
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
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.util.Utils

/** The public channel — also the source polled for new-post notifications. */
const val GT_CHANNEL_NAME = "gozartahrim"

/** The team's bot, used for buying a service and for support. */
const val GT_BOT_NAME = "gozartahrimbot"

const val GT_CHANNEL_URL = "https://t.me/$GT_CHANNEL_NAME"
const val GT_BOT_URL = "https://t.me/$GT_BOT_NAME"
const val GT_ADMIN_URL = "https://t.me/mehrzero"

private const val PROMO_INTERVAL_MS = 12L * 60 * 60 * 1000  // 12 hours

/** True if the promo hasn't been shown within the last 12 hours. */
private fun promoDue(): Boolean {
    val last = MmkvManager.decodeSettingsString(AppConfig.PREF_GT_TG_PROMO_LAST_SHOWN)?.toLongOrNull() ?: 0L
    return System.currentTimeMillis() - last >= PROMO_INTERVAL_MS
}

private fun markPromoShown() {
    MmkvManager.encodeSettings(AppConfig.PREF_GT_TG_PROMO_LAST_SHOWN, System.currentTimeMillis().toString())
}

/**
 * GozarTahrim: channel/support introduction popup.
 *
 * Shown at most once every 12 hours — re-evaluated on each ON_RESUME of the main screen, but
 * only actually displayed when 12 hours have passed since it was last shown.
 */
@Composable
fun TelegramPromoDialog() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var visible by remember { mutableStateOf(promoDue()) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && promoDue()) visible = true
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    if (!visible) return
    LaunchedEffect(Unit) { markPromoShown() }

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
