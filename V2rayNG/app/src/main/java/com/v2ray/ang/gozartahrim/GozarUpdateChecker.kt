package com.v2ray.ang.gozartahrim

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.util.LogUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit

/**
 * GozarTahrim: checks the GitHub releases of the app repo for a newer version and, when one is
 * available, raises a notification linking to the release page.
 *
 * "Newer" = the latest release's tag differs from this build's [AppConfig.GT_RELEASE_TAG]
 * (GitHub's /releases/latest already returns the most recent published release). The user is
 * notified at most once per new tag.
 */
object GozarUpdateChecker {

    private const val TAG = "GozarUpdate"
    private const val CHANNEL_ID = "gozartahrim_updates"
    private const val NOTIFICATION_ID = 20260721
    private const val MIN_INTERVAL_MS = 6L * 60 * 60 * 1000  // check at most every 6 hours

    @Volatile
    private var lastCheckAt = 0L

    suspend fun check(context: Context) = withContext(Dispatchers.IO) {
        if (System.currentTimeMillis() - lastCheckAt < MIN_INTERVAL_MS) return@withContext
        lastCheckAt = System.currentTimeMillis()

        val url = "https://api.github.com/repos/${AppConfig.GT_UPDATE_REPO}/releases/latest"
        val body = fetch(url) ?: return@withContext
        try {
            val json = JSONObject(body)
            val tag = json.optString("tag_name")
            if (tag.isEmpty() || tag == AppConfig.GT_RELEASE_TAG) return@withContext
            if (tag == MmkvManager.decodeSettingsString(AppConfig.PREF_GT_UPDATE_LAST_TAG)) return@withContext

            val page = GozarLinks.safeWeb(json.optString("html_url"))
                ?: "https://github.com/${AppConfig.GT_UPDATE_REPO}/releases/latest"
            showNotification(context, tag, page)
            MmkvManager.encodeSettings(AppConfig.PREF_GT_UPDATE_LAST_TAG, tag)
            LogUtil.i(AppConfig.TAG, "$TAG: newer release available: $tag")
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "$TAG: parse failed", e)
        }
    }

    /** Proxy-first (works while connected), then direct — GitHub API may be filtered without a VPN. */
    private fun fetch(url: String): String? {
        fun client(viaProxy: Boolean): OkHttpClient {
            val b = OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
            if (viaProxy) {
                val port = SettingsManager.getHttpPort()
                if (port != 0) b.proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", port)))
            }
            return b.build()
        }

        fun run(c: OkHttpClient): String? = try {
            c.newCall(
                Request.Builder().url(url).header("Accept", "application/vnd.github+json").build()
            ).execute().use { if (it.isSuccessful) it.body?.string() else null }
        } catch (_: Exception) {
            null
        }

        return run(client(true))?.takeIf { it.isNotEmpty() } ?: run(client(false))
    }

    private fun showNotification(context: Context, tag: String, pageUrl: String) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.title_gt_update),
                    NotificationManager.IMPORTANCE_DEFAULT
                )
            )
        }
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(pageUrl)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val pending = PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val text = context.getString(R.string.gt_update_available, tag)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_name)
            .setContentTitle(context.getString(R.string.title_gt_update))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText("$text\n$pageUrl"))
            .setAutoCancel(true)
            .setContentIntent(pending)
            .build()
        try {
            nm.notify(NOTIFICATION_ID, notification)
        } catch (_: SecurityException) {
        }
    }
}
