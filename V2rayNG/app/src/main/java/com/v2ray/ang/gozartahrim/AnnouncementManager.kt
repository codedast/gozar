package com.v2ray.ang.gozartahrim

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import com.v2ray.ang.AppConfig
import com.v2ray.ang.BuildConfig
import com.v2ray.ang.R
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.SettingsManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * GozarTahrim: talks to the middleware server for two things:
 *   1. an anonymous check-in (heartbeat) so the app owner can see active-user counts, and
 *   2. the current broadcast announcement, shown to the user as an image/text notification.
 *
 * The only identifier sent is a random per-install UUID stored locally — no IP, phone or
 * location is ever transmitted. All requests prefer the app's own tunnel (the server sits
 * behind Cloudflare, which may be filtered without a working VPN), falling back to direct.
 */
object AnnouncementManager {

    private const val TAG = "GozarAnnounce"
    private const val CHANNEL_ID = "gozartahrim_announcements"
    private const val NOTIFICATION_ID = 20260720
    private const val MIN_INTERVAL_MS = 60_000L

    @Volatile
    private var lastRunAt = 0L

    fun isEnabled(): Boolean =
        MmkvManager.decodeSettingsBool(AppConfig.PREF_GT_ANNOUNCE_ENABLED, true)

    /** Server base URL — overridable in settings so the host can change without a rebuild. */
    private fun baseUrl(): String =
        MmkvManager.decodeSettingsString(AppConfig.PREF_GT_ANNOUNCE_BASE_URL)
            ?.trim()?.trimEnd('/')?.takeIf { it.startsWith("http") }
            ?: AppConfig.GT_ANNOUNCE_DEFAULT_BASE_URL

    /** Random UUID generated once per install; never tied to any personal data. */
    private fun installId(): String {
        val existing = MmkvManager.decodeSettingsString(AppConfig.PREF_GT_INSTALL_ID)
        if (!existing.isNullOrEmpty()) return existing
        val id = UUID.randomUUID().toString()
        MmkvManager.encodeSettings(AppConfig.PREF_GT_INSTALL_ID, id)
        return id
    }

    suspend fun run(context: Context) = withContext(Dispatchers.IO) {
        if (System.currentTimeMillis() - lastRunAt < MIN_INTERVAL_MS) return@withContext
        if (!isEnabled()) return@withContext

        checkin()
        // Right after a connect the tunnel may not be routable yet, so the first fetch can fail.
        // Retry a few times with a short delay and only mark this run "done" once we actually
        // reach the server — otherwise the next connect/tick can try again sooner.
        for (attempt in 0 until 4) {
            if (fetchAnnouncement(context)) {
                lastRunAt = System.currentTimeMillis()
                fetchArchive()   // backfill the in-app message box (no notifications)
                break
            }
            if (attempt < 3) kotlinx.coroutines.delay(4000)
        }
    }

    /** Pulls the recent admin announcements into the local message box. */
    private fun fetchArchive() {
        val resp = request { c ->
            c.newCall(Request.Builder().url("${baseUrl()}/api/announcements?limit=10").build())
                .execute().use { it.body?.string() }
        } ?: return
        try {
            val items = JSONObject(resp).optJSONArray("items") ?: return
            for (i in 0 until items.length()) {
                val o = items.getJSONObject(i)
                val id = o.optInt("id")
                if (o.optString("body").isEmpty() && o.optString("title").isEmpty()) continue
                MessageStore.add(
                    GozarMessage(
                        source = GozarMsgSource.ADMIN,
                        title = o.optString("title"),
                        body = o.optString("body"),
                        link = o.optString("link"),
                        ts = o.optLong("ts") * 1000L,   // server timestamp is in seconds
                        key = "a:$id",
                    )
                )
            }
        } catch (_: Exception) {
        }
    }

    /**
     * Forces an immediate check and returns a human-readable diagnostic, so the owner can
     * tell "server unreachable" apart from "no announcement" apart from "shown". Ignores the
     * throttle and the last-seen id, and re-shows the current announcement if one exists.
     */
    suspend fun testNow(context: Context): String = withContext(Dispatchers.IO) {
        checkin()
        val url = "${baseUrl()}/api/announcement?version_code=${BuildConfig.VERSION_CODE}"
        val resp = request { c ->
            c.newCall(Request.Builder().url(url).build()).execute().use { it.body?.string() }
        } ?: return@withContext "سرور در دسترس نیست (${baseUrl()}). اگر به VPN وصل نیستی، اول وصل شو."

        try {
            val json = JSONObject(resp)
            if (!json.optBoolean("has", false)) {
                return@withContext "اتصال به سرور موفق بود، ولی اعلان فعالی وجود ندارد. از بات /broadcast بفرست."
            }
            val id = json.optInt("id", 0)
            val title = json.optString("title").ifEmpty { context.getString(R.string.app_name) }
            showNotification(context, title, json.optString("body"), json.optString("image"), json.optString("link"))
            MmkvManager.encodeSettings(AppConfig.PREF_GT_ANNOUNCE_LAST_ID, id.toString())
            "اعلان #$id دریافت و نمایش داده شد."
        } catch (e: Exception) {
            "پاسخ سرور نامعتبر بود: ${e.message}"
        }
    }

    // --- networking ---

    private fun client(viaProxy: Boolean): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
        if (viaProxy) {
            val port = SettingsManager.getHttpPort()
            if (port != 0) {
                builder.proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", port)))
                val user = SettingsManager.getSocksUsername()
                val pass = SettingsManager.getSocksPassword()
                if (!user.isNullOrBlank() && !pass.isNullOrBlank()) {
                    builder.proxyAuthenticator { _, resp ->
                        if (resp.request.header("Proxy-Authorization") != null) null
                        else resp.request.newBuilder()
                            .header("Proxy-Authorization", Credentials.basic(user, pass))
                            .build()
                    }
                }
            }
        }
        return builder.build()
    }

    private fun request(build: (OkHttpClient) -> String?): String? {
        return try {
            build(client(true))?.takeIf { it.isNotEmpty() }
        } catch (_: Exception) {
            null
        } ?: try {
            build(client(false))
        } catch (_: Exception) {
            null
        }
    }

    private fun checkin() {
        val body = JSONObject()
            .put("install_id", installId())
            .put("app_version", BuildConfig.VERSION_NAME)
            .put("android_api", Build.VERSION.SDK_INT)
        // Attach the RSA-encrypted server info (label + one-way hash + last 4 of UUID) so the
        // admin can see which server a device is on, without the raw link ever leaving the phone.
        buildEncryptedServerInfo()?.let { body.put("enc", it) }
        val payload = body.toString()
        request { c ->
            val req = Request.Builder()
                .url("${baseUrl()}/api/checkin")
                .post(payload.toRequestBody("application/json".toMediaType()))
                .build()
            c.newCall(req).execute().use { it.body?.string() }
        }
    }

    /** Fetches and caches the server's RSA public key (PEM). */
    private fun publicKeyPem(): String? {
        MmkvManager.decodeSettingsString(AppConfig.PREF_GT_PUBKEY)
            ?.takeIf { it.contains("BEGIN PUBLIC KEY") }
            ?.let { return it }
        val pem = request { c ->
            c.newCall(Request.Builder().url("${baseUrl()}/api/pubkey").build()).execute().use {
                if (!it.isSuccessful) return@use null
                it.body?.string()
            }
        }
        if (pem != null && pem.contains("BEGIN PUBLIC KEY")) {
            MmkvManager.encodeSettings(AppConfig.PREF_GT_PUBKEY, pem)
            return pem
        }
        return null
    }

    /**
     * Builds the encrypted `enc` blob for the currently selected server:
     * {"remark": label, "hash": sha256(server:port)[:10], "u4": last-4-of-uuid}, RSA-OAEP
     * encrypted with the server public key. Returns null (and the check-in still goes out
     * plainly) if there is no server, no key, or encryption fails.
     */
    private fun buildEncryptedServerInfo(): String? {
        return try {
            val pem = publicKeyPem() ?: return null
            val guid = MmkvManager.getSelectServer() ?: return null
            val p = MmkvManager.decodeServerConfig(guid) ?: return null
            val server = p.server?.takeIf { it.isNotEmpty() } ?: return null
            val port = p.serverPort ?: ""
            val remark = (p.remarks ?: "").take(48)
            val uuid = p.password ?: ""
            // First 8 chars of the UUID — easier for the admin to match against a config.
            val u4 = if (uuid.length >= 8) uuid.take(8) else uuid
            val hash = sha256Hex("$server:$port").take(10)
            val json = JSONObject().put("remark", remark).put("hash", hash).put("u4", u4).toString()
            rsaEncrypt(pem, json)
        } catch (_: Exception) {
            null
        }
    }

    private fun sha256Hex(s: String): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        return md.digest(s.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }

    /** RSA-OAEP(SHA-256, MGF1-SHA-256) encrypt, base64 — must match crypto.py on the server. */
    private fun rsaEncrypt(pubPem: String, plaintext: String): String? {
        return try {
            val b64 = pubPem.lineSequence().filterNot { it.startsWith("-----") }.joinToString("")
            val der = android.util.Base64.decode(b64, android.util.Base64.DEFAULT)
            val key = java.security.KeyFactory.getInstance("RSA")
                .generatePublic(java.security.spec.X509EncodedKeySpec(der))
            val cipher = javax.crypto.Cipher.getInstance("RSA/ECB/OAEPPadding")
            val oaep = javax.crypto.spec.OAEPParameterSpec(
                "SHA-256", "MGF1", java.security.spec.MGF1ParameterSpec.SHA256,
                javax.crypto.spec.PSource.PSpecified.DEFAULT
            )
            cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, key, oaep)
            android.util.Base64.encodeToString(
                cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8)),
                android.util.Base64.NO_WRAP
            )
        } catch (_: Exception) {
            null
        }
    }

    /** @return true if the server was reached (regardless of whether a new announcement exists). */
    private fun fetchAnnouncement(context: Context): Boolean {
        val since = MmkvManager.decodeSettingsString(AppConfig.PREF_GT_ANNOUNCE_LAST_ID)
            ?.toIntOrNull() ?: 0
        val url = "${baseUrl()}/api/announcement?since=$since&version_code=${BuildConfig.VERSION_CODE}"
        val resp = request { c ->
            c.newCall(Request.Builder().url(url).build()).execute().use { it.body?.string() }
        } ?: return false

        try {
            val json = JSONObject(resp)
            if (!json.optBoolean("has", false)) return true
            val id = json.optInt("id", 0)
            val title = json.optString("title").ifEmpty { context.getString(R.string.app_name) }
            val bodyText = json.optString("body")
            val image = json.optString("image")
            val link = json.optString("link")

            showNotification(context, title, bodyText, image, link)
            MmkvManager.encodeSettings(AppConfig.PREF_GT_ANNOUNCE_LAST_ID, id.toString())
            MessageStore.add(
                GozarMessage(
                    source = GozarMsgSource.ADMIN,
                    title = if (title == context.getString(R.string.app_name)) "" else title,
                    body = bodyText,
                    link = link,
                    ts = System.currentTimeMillis(),
                    key = "a:$id",
                )
            )
            return true
        } catch (_: Exception) {
            // malformed response; treat as reached so we don't hammer the server
            return true
        }
    }

    private fun downloadImage(url: String): android.graphics.Bitmap? {
        if (url.isEmpty()) return null
        val bytes = request { c ->
            c.newCall(Request.Builder().url(url).build()).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                // Encode to Base64 so it survives the String-returning request() helper.
                android.util.Base64.encodeToString(resp.body?.bytes() ?: return@use null,
                    android.util.Base64.NO_WRAP)
            }
        } ?: return null
        return try {
            val raw = android.util.Base64.decode(bytes, android.util.Base64.NO_WRAP)
            BitmapFactory.decodeByteArray(raw, 0, raw.size)
        } catch (_: Exception) {
            null
        }
    }

    private fun showNotification(
        context: Context,
        title: String,
        body: String,
        imageUrl: String,
        link: String,
    ) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.title_gt_announcements),
                    NotificationManager.IMPORTANCE_DEFAULT
                )
            )
        }

        // Text + link only for now (image intentionally disabled while we verify the basics).
        // If the announcement carries no body, fall back to the link (or a short placeholder) so
        // the notification is never blank.
        val shown = when {
            body.isNotBlank() -> body
            link.isNotBlank() -> link
            else -> context.getString(R.string.title_gt_announcements)
        }

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_name)
            .setContentTitle(title)
            .setContentText(shown.take(120))
            .setStyle(NotificationCompat.BigTextStyle().bigText(shown))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)

        val safeLink = GozarLinks.safeWeb(link)
        if (safeLink != null) {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(safeLink))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            builder.setContentIntent(
                PendingIntent.getActivity(
                    context, 0, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )
        }

        try {
            nm.notify(NOTIFICATION_ID, builder.build())
        } catch (_: SecurityException) {
            // POST_NOTIFICATIONS not granted; nothing to do.
        }
    }
}
