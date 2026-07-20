package com.v2ray.ang.gozartahrim

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.text.HtmlCompat
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.dto.UrlContentRequest
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.util.HttpUtil
import com.v2ray.ang.util.LogUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * GozarTahrim: polls the public web preview of a Telegram channel (https://t.me/s/<channel>)
 * for new posts and raises a native Android notification for each new one.
 *
 * No bot token is embedded (it would be extractable from the APK) and no backend is needed —
 * the channel's own public preview page is the transport.
 *
 * Ported from TelegramChannelNotifyManager.cs in the GozarTahrim Windows fork.
 */
object TelegramChannelNotifyManager {

    private const val TAG = "TelegramChannelNotify"
    private const val CHANNEL_ID = "gozartahrim_channel_news"
    private const val NOTIFICATION_ID = 20260719
    private const val MIN_INTERVAL_MS = 60_000L

    private val postRegex = Regex("""data-post="([^"]+)"""")
    private val textRegex = Regex("""tgme_widget_message_text[^>]*>(.*?)</div>""", RegexOption.DOT_MATCHES_ALL)
    private val linkRegex = Regex("""<a[^>]+href="(https?://[^"]+)"""")

    @Volatile
    private var isRunning = false

    @Volatile
    private var lastCheckAt = 0L

    fun isEnabled(): Boolean =
        MmkvManager.decodeSettingsBool(AppConfig.PREF_GT_TG_ENABLED, true)

    fun getChannel(): String =
        MmkvManager.decodeSettingsString(AppConfig.PREF_GT_TG_CHANNEL)?.takeIf { it.isNotEmpty() }
            ?: GT_CHANNEL_NAME

    /**
     * Fetches the channel preview page and notifies when the newest post differs from the last
     * seen one. Safe to call often — it self-throttles to one real check per minute.
     */
    suspend fun checkForNewPost(context: Context) = withContext(Dispatchers.IO) {
        if (isRunning) return@withContext
        if (System.currentTimeMillis() - lastCheckAt < MIN_INTERVAL_MS) return@withContext
        if (!isEnabled()) return@withContext

        val channel = getChannel()
        if (channel.isEmpty()) return@withContext

        isRunning = true
        lastCheckAt = System.currentTimeMillis()
        try {
            val url = "https://t.me/s/$channel"
            // Prefer the app's own tunnel (Telegram is filtered in Iran, so a direct fetch only
            // works when the user is already connected); retried on every tick either way.
            val html = fetch(url, viaProxy = true).ifEmpty { fetch(url, viaProxy = false) }
            if (html.isEmpty()) {
                LogUtil.i(AppConfig.TAG, "$TAG: fetch failed (no proxy/direct access to $url)")
                return@withContext
            }

            val postMatches = postRegex.findAll(html).toList()
            if (postMatches.isEmpty()) {
                LogUtil.i(AppConfig.TAG, "$TAG: fetch ok but no messages found (history may be hidden)")
                return@withContext
            }

            val lastMatch = postMatches.last()
            val postId = lastMatch.groupValues[1]
            val lastSeen = MmkvManager.decodeSettingsString(AppConfig.PREF_GT_TG_LAST_POST_ID)
            if (postId == lastSeen) return@withContext

            val block = html.substring(lastMatch.range.first)
            val messageHtml = textRegex.find(block)?.groupValues?.get(1).orEmpty()
            val messageText = HtmlCompat.fromHtml(messageHtml, HtmlCompat.FROM_HTML_MODE_LEGACY)
                .toString().trim()

            val link = linkRegex.findAll(messageHtml)
                .map { it.groupValues[1] }
                .firstOrNull { !it.contains("t.me/") || it.contains("t.me/$channel/") }
                ?: "https://t.me/$channel/${postId.substringAfterLast('/')}"

            MmkvManager.encodeSettings(AppConfig.PREF_GT_TG_LAST_POST_ID, postId)

            if (messageText.isEmpty()) {
                LogUtil.i(AppConfig.TAG, "$TAG: new post $postId had no text, skipping notification")
                return@withContext
            }

            LogUtil.i(AppConfig.TAG, "$TAG: publishing notification for $postId")
            showNotification(context, messageText, link)
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "$TAG: check failed", e)
        } finally {
            isRunning = false
        }
    }

    private fun fetch(url: String, viaProxy: Boolean): String = try {
        if (viaProxy) {
            HttpUtil.getUrlContentWithUserAgent(
                UrlContentRequest(
                    url = url,
                    timeout = 15000,
                    httpPort = SettingsManager.getHttpPort(),
                    proxyUsername = SettingsManager.getSocksUsername(),
                    proxyPassword = SettingsManager.getSocksPassword(),
                )
            )
        } else {
            HttpUtil.getUrlContentWithUserAgent(UrlContentRequest(url = url, timeout = 15000))
        }
    } catch (e: Exception) {
        LogUtil.w(AppConfig.TAG, "$TAG: fetch(viaProxy=$viaProxy) failed: ${e.message}")
        ""
    }

    private fun showNotification(context: Context, message: String, link: String) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.title_gt_tg_channel_news),
                NotificationManager.IMPORTANCE_DEFAULT
            )
            nm.createNotificationChannel(ch)
        }

        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(link)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val pending = PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_name)
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText(message.take(120))
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setAutoCancel(true)
            .setContentIntent(pending)
            .build()

        try {
            nm.notify(NOTIFICATION_ID, notification)
        } catch (e: SecurityException) {
            // POST_NOTIFICATIONS not granted (Android 13+); nothing to do but skip.
            LogUtil.w(AppConfig.TAG, "$TAG: notification permission missing: ${e.message}")
        }
    }
}
