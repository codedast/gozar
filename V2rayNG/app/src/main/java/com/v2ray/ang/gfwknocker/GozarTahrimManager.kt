package com.v2ray.ang.gfwknocker

import com.v2ray.ang.AppConfig
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.util.Utils
import com.v2ray.ang.util.LogUtil

/**
 * GozarTahrim (گذرتحریم) native fragmentor manager.
 *
 * Controls the in-app Kotlin [TLS_Fragmentor] local proxy which splits the first
 * outbound packet (the TLS ClientHello) into `num_fragment` random chunks with a
 * small delay between them, defeating SNI-based DPI filtering used by some Iranian
 * ISPs. This is the "native engine" path; the default GozarTahrim mode instead maps
 * the same parameters onto Xray-core's built-in fragment engine (see
 * CoreOutboundBuilder.updateOutboundFragment).
 */
object GozarTahrimManager {

    private var fragmentor: TLS_Fragmentor? = null

    @Volatile
    var listenPort: Int = 0
        private set

    /**
     * Starts the native fragmentor if GozarTahrim + native-engine are both enabled.
     * @param targetIp real destination IP (e.g. the resolved CDN edge address)
     * @param targetPort real destination port
     * @return local listen port the fragmentor is bound to, or 0 if not started
     */
    fun start(targetIp: String, targetPort: Int): Int {
        if (!MmkvManager.decodeSettingsBool(AppConfig.PREF_GT_ENABLED, false)) return 0
        if (!MmkvManager.decodeSettingsBool(AppConfig.PREF_GT_NATIVE_PROXY, false)) return 0

        stop()

        val numFragment = MmkvManager.decodeSettingsString(AppConfig.PREF_GT_NUM_FRAGMENT)
            ?.toIntOrNull()?.coerceIn(2, 500) ?: 67
        // Stored in milliseconds; TLS_Fragmentor expects seconds.
        val sleepMs = MmkvManager.decodeSettingsString(AppConfig.PREF_GT_FRAGMENT_SLEEP)
            ?.toIntOrNull()?.coerceAtLeast(0) ?: 1
        val port = Utils.findRandomFreePort()

        return try {
            val f = TLS_Fragmentor(
                listen_ip = "127.0.0.1",
                listen_port = port,
                target_ip = targetIp,
                target_port = targetPort,
                isFragment = true,
                num_fragment = numFragment,
                fragment_sleep = sleepMs / 1000.0
            )
            f.start()
            listenPort = f.get_listen_port()
            fragmentor = f
            LogUtil.i(
                AppConfig.TAG,
                "GozarTahrim native fragmentor listening at 127.0.0.1:$listenPort -> $targetIp:$targetPort " +
                    "(num_fragment=$numFragment, sleep=${sleepMs}ms)"
            )
            listenPort
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "GozarTahrim native fragmentor failed to start", e)
            0
        }
    }

    fun stop() {
        try {
            fragmentor?.safely_stop_server()
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "GozarTahrim stop error", e)
        } finally {
            fragmentor = null
            listenPort = 0
        }
    }
}
