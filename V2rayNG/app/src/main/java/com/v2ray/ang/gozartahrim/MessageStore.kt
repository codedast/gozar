package com.v2ray.ang.gozartahrim

import com.v2ray.ang.AppConfig
import com.v2ray.ang.handler.MmkvManager
import org.json.JSONArray
import org.json.JSONObject

/** Where a stored message came from — drives the label and colour in the message box. */
enum class GozarMsgSource { ADMIN, CHANNEL }

data class GozarMessage(
    val source: GozarMsgSource,
    val title: String,
    val body: String,
    val link: String,
    val ts: Long,
    /** Stable de-dup key, e.g. "a:7" for admin announcement 7 or "c:gozartahrim/42". */
    val key: String,
)

/**
 * GozarTahrim: a small local inbox of the most recent admin announcements and channel posts,
 * shown in the app as a one-way chat box. Persisted in MMKV as a JSON array, newest first,
 * capped so it never grows unbounded.
 */
object MessageStore {

    private const val MAX = 30

    fun all(): List<GozarMessage> {
        val raw = MmkvManager.decodeSettingsString(AppConfig.PREF_GT_MESSAGES) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                GozarMessage(
                    source = if (o.optString("src") == "channel") GozarMsgSource.CHANNEL else GozarMsgSource.ADMIN,
                    title = o.optString("title"),
                    body = o.optString("body"),
                    link = o.optString("link"),
                    ts = o.optLong("ts"),
                    key = o.optString("key"),
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** Adds a message if its key isn't already present; keeps the list newest-first and capped. */
    @Synchronized
    fun add(msg: GozarMessage) {
        val current = all().toMutableList()
        if (current.any { it.key == msg.key }) return
        current.add(0, msg)
        current.sortByDescending { it.ts }
        val trimmed = current.take(MAX)
        save(trimmed)
    }

    /** Clears the whole inbox. */
    @Synchronized
    fun clear() {
        MmkvManager.encodeSettings(AppConfig.PREF_GT_MESSAGES, "[]")
    }

    private fun save(list: List<GozarMessage>) {
        val arr = JSONArray()
        list.forEach { m ->
            arr.put(
                JSONObject()
                    .put("src", if (m.source == GozarMsgSource.CHANNEL) "channel" else "admin")
                    .put("title", m.title)
                    .put("body", m.body)
                    .put("link", m.link)
                    .put("ts", m.ts)
                    .put("key", m.key)
            )
        }
        MmkvManager.encodeSettings(AppConfig.PREF_GT_MESSAGES, arr.toString())
    }
}
