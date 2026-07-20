package com.v2ray.ang.gozartahrim

import com.v2ray.ang.AppConfig
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.SpeedtestManager
import com.v2ray.ang.util.LogUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * GozarTahrim: periodically delay-tests the servers of the active profile's subscription group
 * and switches the selected server to whichever currently tests best (lowest TCP delay).
 *
 * Only runs for a group that has explicitly opted in via [SubscriptionItem.autoConnectEnabled] —
 * this is a per-group setting, never a blanket toggle across every subscription.
 *
 * Ported from AutoConnectManager.cs in the GozarTahrim Windows fork. The Windows version ranks
 * by measured speed then delay; Android's core only exposes a TCP connect probe, so ranking here
 * is by delay alone.
 */
object AutoConnectManager {

    private const val TAG = "AutoConnectManager"

    @Volatile
    private var isRunning = false

    fun getIntervalMinutes(): Int =
        MmkvManager.decodeSettingsString(AppConfig.PREF_GT_AUTOCONNECT_INTERVAL)?.toIntOrNull() ?: 30

    fun getBatchSize(): Int =
        MmkvManager.decodeSettingsString(AppConfig.PREF_GT_AUTOCONNECT_BATCH)?.toIntOrNull() ?: 5

    /**
     * Returns the guid of a better server if one was found and selected, otherwise null.
     * Self-throttles: does nothing until [getIntervalMinutes] has elapsed since the last check.
     */
    suspend fun checkAndSwitch(): String? = withContext(Dispatchers.IO) {
        if (isRunning) return@withContext null

        val nowSec = System.currentTimeMillis() / 1000
        val lastCheck =
            MmkvManager.decodeSettingsString(AppConfig.PREF_GT_AUTOCONNECT_LAST_CHECK)?.toLongOrNull() ?: 0L
        if (nowSec - lastCheck < getIntervalMinutes() * 60L) return@withContext null

        isRunning = true
        try {
            runCheck()
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "$TAG: check failed", e)
            null
        } finally {
            MmkvManager.encodeSettings(AppConfig.PREF_GT_AUTOCONNECT_LAST_CHECK, nowSec.toString())
            isRunning = false
        }
    }

    private fun runCheck(): String? {
        val currentGuid = MmkvManager.getSelectServer() ?: return null
        val current = MmkvManager.decodeServerConfig(currentGuid) ?: return null
        val subId = current.subscriptionId
        if (subId.isEmpty()) return null

        val sub = MmkvManager.decodeSubscription(subId) ?: return null
        if (!sub.autoConnectEnabled) return null

        val candidates = MmkvManager.decodeServerList(subId)
            .take(getBatchSize().coerceAtLeast(1))
        if (candidates.isEmpty()) return null

        LogUtil.i(AppConfig.TAG, "$TAG: testing ${candidates.size} server(s) in group for a better connection")

        var bestGuid: String? = null
        var bestDelay = Long.MAX_VALUE
        var currentDelay = Long.MAX_VALUE

        candidates.forEach { guid ->
            val profile = MmkvManager.decodeServerConfig(guid) ?: return@forEach
            val host = profile.server ?: return@forEach
            val port = profile.serverPort?.toIntOrNull() ?: return@forEach

            val delay = SpeedtestManager.socketConnectTime(host, port, 3000)
            if (delay > 0) {
                MmkvManager.encodeServerTestDelayMillis(guid, delay)
                if (guid == currentGuid) currentDelay = delay
                if (delay < bestDelay) {
                    bestDelay = delay
                    bestGuid = guid
                }
            }
        }

        val winner = bestGuid ?: return null
        if (winner == currentGuid) return null
        // Only switch when the winner is meaningfully better, so we don't flap between servers
        // whose delays differ by measurement noise.
        if (currentDelay != Long.MAX_VALUE && bestDelay > currentDelay - 100) return null

        MmkvManager.setSelectServer(winner)
        LogUtil.i(AppConfig.TAG, "$TAG: switched to a better server $winner (delay=${bestDelay}ms)")
        return winner
    }
}
