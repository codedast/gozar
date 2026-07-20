package com.v2ray.ang.gozartahrim.altip

import com.v2ray.ang.AppConfig
import com.v2ray.ang.dto.UrlContentRequest
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.dto.entities.SubscriptionItem
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.SettingsChangeManager
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.util.HttpUtil
import com.v2ray.ang.util.JsonUtil
import com.v2ray.ang.util.LogUtil
import com.v2ray.ang.util.Utils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.InetSocketAddress
import java.net.Socket
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager
import kotlin.random.Random

/**
 * GozarTahrim: finds alternate front-IPs for an existing VLESS+Cloudflare profile.
 *
 * Samples IPs from Cloudflare's official IPv4 ranges and from FOFA search results, then
 * validates every candidate with a TCP connect + TLS handshake against the profile's original
 * SNI. Valid candidates are cloned into a new local subscription group, leaving SNI/host
 * untouched so TLS still targets the original domain.
 *
 * Ported from AltIpFinderHandler.cs in the GozarTahrim Windows fork.
 */
object AltIpFinderManager {

    private const val TAG = "AltIpFinder"

    // Embedded default key so the feature works out of the box; only overridden when the user
    // explicitly sets their own key in settings.
    private const val DEFAULT_FOFA_API_KEY = "e07547984526bca8f6716578e68e5f5d"
    private const val CLOUDFLARE_IPV4_URL = "https://www.cloudflare.com/ips-v4"
    private const val FOFA_SEARCH_URL = "https://fofa.info/api/v1/search/all"

    // cloudflare.com's WAF returns 403 for non-browser User-Agents.
    private const val BROWSER_USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

    // ---- settings ----

    fun getSampleCount(): Int =
        MmkvManager.decodeSettingsString(AppConfig.PREF_GT_ALTIP_SAMPLE_COUNT)?.toIntOrNull() ?: 30

    fun getConcurrency(): Int =
        MmkvManager.decodeSettingsString(AppConfig.PREF_GT_ALTIP_CONCURRENCY)?.toIntOrNull() ?: 20

    fun getTimeoutMs(): Int =
        MmkvManager.decodeSettingsString(AppConfig.PREF_GT_ALTIP_TIMEOUT_MS)?.toIntOrNull() ?: 5000

    fun getCountryCode(): String =
        MmkvManager.decodeSettingsString(AppConfig.PREF_GT_ALTIP_COUNTRY)?.takeIf { it.isNotEmpty() } ?: "IR"

    private fun getFofaApiKey(): String =
        MmkvManager.decodeSettingsString(AppConfig.PREF_GT_ALTIP_FOFA_KEY)?.takeIf { it.isNotEmpty() }
            ?: DEFAULT_FOFA_API_KEY

    fun buildDefaultQuery(countryCode: String): String =
        "\"cloudflare\" && country=\"$countryCode\" && port=\"443\" && server==\"cloudflare\""

    // ---- sources ----

    /**
     * Downloads via the app's local proxy when a core is running, otherwise falls back to a
     * direct connection. Both cloudflare.com and fofa.info are commonly blocked by ISP-level
     * censorship, so the tunnel is usually required to reach them at all.
     */
    private suspend fun downloadText(url: String): String = withContext(Dispatchers.IO) {
        val viaProxy = try {
            HttpUtil.getUrlContentWithUserAgent(
                UrlContentRequest(
                    url = url,
                    userAgent = BROWSER_USER_AGENT,
                    timeout = 15000,
                    httpPort = SettingsManager.getHttpPort(),
                    proxyUsername = SettingsManager.getSocksUsername(),
                    proxyPassword = SettingsManager.getSocksPassword(),
                )
            )
        } catch (e: Exception) {
            LogUtil.w(AppConfig.TAG, "$TAG: proxy download failed for $url (${e.message})")
            ""
        }
        if (viaProxy.isNotEmpty()) return@withContext viaProxy

        try {
            HttpUtil.getUrlContentWithUserAgent(
                UrlContentRequest(url = url, userAgent = BROWSER_USER_AGENT, timeout = 15000)
            )
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "$TAG: direct download failed for $url", e)
            ""
        }
    }

    suspend fun fetchCloudflareIpv4(): List<String> {
        val text = downloadText(CLOUDFLARE_IPV4_URL)
        val cidrs = text.split('\n')
            .map { it.trim() }
            .filter { it.isNotEmpty() && it.contains('/') }
        return sampleIpsFromCidrs(cidrs, getSampleCount())
    }

    suspend fun fetchFofaIps(countryCode: String? = null, rawQuery: String? = null): List<String> {
        val country = countryCode?.takeIf { it.isNotEmpty() } ?: getCountryCode()
        val query = rawQuery?.takeIf { it.isNotEmpty() } ?: buildDefaultQuery(country)
        val qbase64 = android.util.Base64.encodeToString(
            query.toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP
        )
        val url = "$FOFA_SEARCH_URL?key=${getFofaApiKey()}&qbase64=$qbase64&fields=ip,port&size=${getSampleCount()}"

        val resp = downloadText(url)
        if (resp.isEmpty()) return emptyList()

        return try {
            val root = JSONObject(resp)
            if (root.optBoolean("error", false)) {
                LogUtil.e(AppConfig.TAG, "$TAG: FOFA error: ${root.optString("errmsg")}")
                return emptyList()
            }
            val results = root.optJSONArray("results") ?: return emptyList()
            val ips = LinkedHashSet<String>()
            for (i in 0 until results.length()) {
                val row = results.optJSONArray(i) ?: continue
                val ip = row.optString(0)
                // skip IPv6 and blanks
                if (ip.isNotEmpty() && !ip.contains(':')) ips.add(ip)
            }
            ips.toList()
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "$TAG: failed to parse FOFA response", e)
            emptyList()
        }
    }

    // ---- CIDR sampling ----

    private fun sampleIpsFromCidrs(cidrs: List<String>, count: Int): List<String> {
        if (cidrs.isEmpty() || count <= 0) return emptyList()
        val result = LinkedHashSet<String>()
        var attempts = 0
        val maxAttempts = count * 10
        while (result.size < count && attempts < maxAttempts) {
            attempts++
            randomIpInCidr(cidrs[Random.nextInt(cidrs.size)])?.let { result.add(it) }
        }
        return result.toList()
    }

    private fun randomIpInCidr(cidr: String): String? {
        val parts = cidr.split('/')
        if (parts.size != 2) return null
        val prefixLen = parts[1].toIntOrNull() ?: return null
        val octets = parts[0].split('.')
        if (octets.size != 4) return null

        var base = 0L
        for (o in octets) {
            val v = o.toIntOrNull() ?: return null
            if (v !in 0..255) return null
            base = (base shl 8) or v.toLong()
        }

        val hostBits = 32 - prefixLen
        if (hostBits <= 0) return parts[0]
        val hostCount = 1L shl hostBits
        val offset = if (hostCount <= 2L) 0L else Random.nextLong(1, hostCount - 1)
        val v = (base + offset) and 0xFFFFFFFFL

        return "${(v shr 24) and 0xFF}.${(v shr 16) and 0xFF}.${(v shr 8) and 0xFF}.${v and 0xFF}"
    }

    // ---- candidate testing ----

    /**
     * TCP-connects to the candidate and then attempts a TLS handshake using the original SNI.
     * A candidate that connects over TCP but fails TLS is still kept (usable-but-unverified),
     * matching the Windows fork's behaviour.
     */
    fun testCandidate(ip: String, port: Int, sni: String): AltIpFinderResult {
        val result = AltIpFinderResult(ip = ip)
        val timeout = getTimeoutMs()
        var socket: Socket? = null
        try {
            val start = System.currentTimeMillis()
            socket = Socket()
            socket.connect(InetSocketAddress(ip, port), timeout)
            result.tcpOk = true
            result.latencyMs = (System.currentTimeMillis() - start).toInt()
        } catch (_: Exception) {
            runCatching { socket?.close() }
            return result
        }

        try {
            socket.soTimeout = timeout
            val ctx = SSLContext.getInstance("TLS")
            ctx.init(null, arrayOf(TRUST_ALL), SecureRandom())
            val sslSocket = ctx.socketFactory.createSocket(socket, sni, port, true) as SSLSocket
            // Set SNI explicitly so the handshake targets the original domain, not the raw IP.
            runCatching {
                val params = sslSocket.sslParameters
                params.serverNames = listOf(javax.net.ssl.SNIHostName(sni))
                sslSocket.sslParameters = params
            }
            sslSocket.startHandshake()
            result.tlsOk = true
            runCatching { sslSocket.close() }
        } catch (_: Exception) {
            // TCP reachable but TLS handshake failed; still a usable-but-unverified candidate.
        } finally {
            runCatching { socket.close() }
        }

        return result
    }

    suspend fun runTests(
        candidates: List<Pair<String, EAltIpSource>>,
        port: Int,
        sni: String,
        onProgress: ((AltIpFinderResult) -> Unit)? = null,
    ): List<AltIpFinderResult> = coroutineScope {
        val semaphore = Semaphore(getConcurrency().coerceAtLeast(1))
        val jobs = candidates.map { (ip, source) ->
            async(Dispatchers.IO) {
                semaphore.withPermit {
                    val r = try {
                        testCandidate(ip, port, sni)
                    } catch (e: Exception) {
                        LogUtil.e(AppConfig.TAG, "$TAG: test failed for $ip", e)
                        AltIpFinderResult(ip = ip)
                    }
                    r.source = source
                    onProgress?.invoke(r)
                    r
                }
            }
        }
        jobs.map { it.await() }
    }

    // ---- saving results ----

    /**
     * Keeps generated names short: profile remarks already carry the IP, and an overlong
     * group name pushes the subscription tabs out of shape.
     */
    private fun shortRemarks(remarks: String, max: Int = 4): String {
        val trimmed = remarks.trim()
        return if (trimmed.length <= max) trimmed else trimmed.substring(0, max)
    }

    /** Name of the subscription group generated for a source profile. */
    fun groupNameFor(source: ProfileItem): String = "${shortRemarks(source.remarks)} IPs"

    /**
     * Creates a new local (non-remote) subscription group and clones the source profile into it
     * once per valid candidate IP. Only the address is replaced — SNI/host stay untouched.
     *
     * @return Pair of (number added, new subscription id) or (0, null) when nothing was valid.
     */
    fun addValidCandidatesAsGroup(
        source: ProfileItem,
        results: List<AltIpFinderResult>,
    ): Pair<Int, String?> {
        val valid = results.filter { it.tcpOk }
        if (valid.isEmpty()) return 0 to null

        val subId = Utils.getUuid()
        MmkvManager.encodeSubscription(
            subId,
            SubscriptionItem(
                remarks = groupNameFor(source),
                url = "",
                enabled = true,
            )
        )

        val serverList = MmkvManager.decodeServerList(subId)
        var added = 0
        valid.forEach { candidate ->
            try {
                val clone = source.copy(
                    subscriptionId = subId,
                    server = candidate.ip,
                    remarks = "${shortRemarks(source.remarks)}${candidate.source.label} [${candidate.ip}]",
                )
                val key = Utils.getUuid()
                MmkvManager.encodeProfileDirect(key, JsonUtil.toJson(clone))
                if (!serverList.contains(key)) serverList.add(key)
                added++
            } catch (e: Exception) {
                LogUtil.e(AppConfig.TAG, "$TAG: failed to clone profile for ${candidate.ip}", e)
            }
        }
        MmkvManager.encodeServerList(serverList, subId)

        // Tell MainActivity to rebuild its group tabs / server list when we return, otherwise the
        // new group only shows up after a full app restart.
        SettingsChangeManager.makeSetupGroupTab()

        LogUtil.i(AppConfig.TAG, "$TAG: saved $added alternate IP profile(s) into group $subId")
        return added to subId
    }

    fun isSupportedProfile(profile: ProfileItem?): Boolean =
        profile != null && profile.configType == EConfigType.VLESS

    /**
     * The SNI the candidates must be validated against: explicit SNI, else the WS/HTTP host,
     * else the original server domain.
     */
    fun resolveSni(profile: ProfileItem): String =
        profile.sni?.takeIf { it.isNotEmpty() }
            ?: profile.host?.takeIf { it.isNotEmpty() }
            ?: profile.server.orEmpty()

    private val TRUST_ALL = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }
}
