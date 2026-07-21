package com.v2ray.ang.gozartahrim

/**
 * GozarTahrim: link safety.
 *
 * Announcement and channel-post links are remote-controlled content. Opening them blindly via
 * ACTION_VIEW would let a crafted link use a non-web scheme (javascript:, intent:, file:,
 * content:, …) to trigger unintended actions on the device. Every link that originates from the
 * server or the Telegram channel MUST pass through [safeWeb] before it is opened.
 */
object GozarLinks {

    /** Returns the URL only if it is a plain http(s) web link; otherwise null. */
    fun safeWeb(url: String?): String? {
        val u = url?.trim() ?: return null
        val lower = u.lowercase()
        return if (lower.startsWith("http://") || lower.startsWith("https://")) u else null
    }
}
