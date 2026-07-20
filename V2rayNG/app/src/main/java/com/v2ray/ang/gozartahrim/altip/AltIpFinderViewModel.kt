package com.v2ray.ang.gozartahrim.altip

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.util.LogUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class AltIpUiState(
    val countryCode: String = "IR",
    val query: String = "",
    val sampleCount: String = "30",
    val concurrency: String = "20",
    val running: Boolean = false,
    val statusText: String = "",
    val tested: Int = 0,
    val total: Int = 0,
    val results: List<AltIpFinderResult> = emptyList(),
    val sourceProfile: ProfileItem? = null,
    val sourceGuid: String? = null,
)

class AltIpFinderViewModel(application: Application) : AndroidViewModel(application) {

    private val _state = MutableStateFlow(AltIpUiState())
    val state: StateFlow<AltIpUiState> = _state.asStateFlow()

    private var searchJob: Job? = null

    private fun str(resId: Int, vararg args: Any): String =
        getApplication<Application>().getString(resId, *args)

    fun load(guid: String?) {
        val country = AltIpFinderManager.getCountryCode()
        val profile = guid?.let { MmkvManager.decodeServerConfig(it) }
        _state.value = _state.value.copy(
            sourceGuid = guid,
            sourceProfile = profile,
            countryCode = country,
            query = MmkvManager.decodeSettingsString(AppConfig.PREF_GT_ALTIP_QUERY)
                ?.takeIf { it.isNotEmpty() } ?: AltIpFinderManager.buildDefaultQuery(country),
            sampleCount = AltIpFinderManager.getSampleCount().toString(),
            concurrency = AltIpFinderManager.getConcurrency().toString(),
        )
    }

    fun setCountry(code: String) {
        MmkvManager.encodeSettings(AppConfig.PREF_GT_ALTIP_COUNTRY, code)
        // Regenerate the query for the new country unless the user typed a custom one.
        val current = _state.value
        val wasDefault = current.query == AltIpFinderManager.buildDefaultQuery(current.countryCode)
        _state.value = current.copy(
            countryCode = code,
            query = if (wasDefault) AltIpFinderManager.buildDefaultQuery(code) else current.query
        )
    }

    fun setQuery(q: String) {
        _state.value = _state.value.copy(query = q)
    }

    fun setSampleCount(v: String) {
        _state.value = _state.value.copy(sampleCount = v)
        v.toIntOrNull()?.let { MmkvManager.encodeSettings(AppConfig.PREF_GT_ALTIP_SAMPLE_COUNT, it.toString()) }
    }

    fun setConcurrency(v: String) {
        _state.value = _state.value.copy(concurrency = v)
        v.toIntOrNull()?.let { MmkvManager.encodeSettings(AppConfig.PREF_GT_ALTIP_CONCURRENCY, it.toString()) }
    }

    fun cancel() {
        searchJob?.cancel()
        searchJob = null
        _state.value = _state.value.copy(running = false, statusText = str(R.string.gt_altip_cancelled))
    }

    fun start() {
        val profile = _state.value.sourceProfile
        if (profile == null || _state.value.running) return

        MmkvManager.encodeSettings(AppConfig.PREF_GT_ALTIP_QUERY, _state.value.query)

        searchJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                _state.value = _state.value.copy(
                    running = true,
                    results = emptyList(),
                    tested = 0,
                    total = 0,
                    statusText = str(R.string.gt_altip_fetching_cf)
                )

                val cfIps = AltIpFinderManager.fetchCloudflareIpv4()

                _state.value = _state.value.copy(statusText = str(R.string.gt_altip_searching_fofa))
                val fofaIps = AltIpFinderManager.fetchFofaIps(
                    countryCode = _state.value.countryCode,
                    rawQuery = _state.value.query
                )

                val candidates =
                    cfIps.map { it to EAltIpSource.CLOUDFLARE } + fofaIps.map { it to EAltIpSource.FOFA }

                if (candidates.isEmpty()) {
                    _state.value = _state.value.copy(
                        running = false,
                        statusText = str(R.string.gt_altip_none_found)
                    )
                    return@launch
                }

                val port = profile.serverPort?.toIntOrNull() ?: 443
                val sni = AltIpFinderManager.resolveSni(profile)

                _state.value = _state.value.copy(
                    total = candidates.size,
                    statusText = str(R.string.gt_altip_testing, candidates.size)
                )

                val results = AltIpFinderManager.runTests(candidates, port, sni) { r ->
                    val s = _state.value
                    _state.value = s.copy(
                        tested = s.tested + 1,
                        results = (s.results + r).sortedWith(
                            compareByDescending<AltIpFinderResult> { it.tlsOk }
                                .thenByDescending { it.tcpOk }
                                .thenBy { if (it.latencyMs < 0) Int.MAX_VALUE else it.latencyMs }
                        )
                    )
                }

                val (added, subId) = AltIpFinderManager.addValidCandidatesAsGroup(profile, results)
                val message = if (added > 0) {
                    str(R.string.gt_altip_saved, added, AltIpFinderManager.groupNameFor(profile))
                } else {
                    str(R.string.gt_altip_none_valid)
                }

                _state.value = _state.value.copy(running = false, statusText = message)
                LogUtil.i(AppConfig.TAG, "AltIpFinder: finished, added=$added subId=$subId")
            } catch (e: Exception) {
                LogUtil.e(AppConfig.TAG, "AltIpFinder: search failed", e)
                _state.value = _state.value.copy(
                    running = false,
                    statusText = str(R.string.gt_altip_error, e.message ?: "")
                )
            }
        }
    }
}
