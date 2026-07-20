package com.v2ray.ang.gozartahrim.altip

/**
 * Where an alternate front-IP candidate came from.
 * Mirrors EAltIpSource in the GozarTahrim Windows fork.
 */
enum class EAltIpSource(val label: String) {
    CLOUDFLARE("CL"),
    FOFA("F"),
}

/**
 * Result of validating one candidate IP with a TCP connect + TLS handshake.
 */
data class AltIpFinderResult(
    val ip: String,
    var source: EAltIpSource = EAltIpSource.CLOUDFLARE,
    var tcpOk: Boolean = false,
    var tlsOk: Boolean = false,
    var latencyMs: Int = -1,
)

data class FofaCountryPreset(
    val code: String,
    val countryName: String,
    val priority: Boolean = false,
) {
    val displayName: String
        get() = if (priority) "★ $countryName ($code)" else "$countryName ($code)"
}

/**
 * Country presets for the FOFA query. The six priority countries stay in this exact
 * order and are starred; the rest follow alphabetically by name.
 */
object FofaCountryPresets {

    private val priority = listOf(
        "IR" to "Iran",
        "US" to "United States",
        "TR" to "Turkey",
        "DE" to "Germany",
        "GB" to "United Kingdom",
        "CN" to "China",
    )

    private val others = listOf(
        "FR" to "France",
        "NL" to "Netherlands",
        "CA" to "Canada",
        "AE" to "United Arab Emirates",
        "RU" to "Russia",
        "JP" to "Japan",
        "KR" to "South Korea",
        "IN" to "India",
        "BR" to "Brazil",
        "AU" to "Australia",
        "IT" to "Italy",
        "ES" to "Spain",
        "SE" to "Sweden",
        "CH" to "Switzerland",
        "SG" to "Singapore",
        "HK" to "Hong Kong",
        "TW" to "Taiwan",
        "PL" to "Poland",
        "FI" to "Finland",
        "NO" to "Norway",
        "DK" to "Denmark",
        "BE" to "Belgium",
        "AT" to "Austria",
        "IE" to "Ireland",
        "PT" to "Portugal",
        "GR" to "Greece",
        "CZ" to "Czechia",
        "RO" to "Romania",
        "UA" to "Ukraine",
        "IL" to "Israel",
        "SA" to "Saudi Arabia",
        "EG" to "Egypt",
        "ZA" to "South Africa",
        "MX" to "Mexico",
        "AR" to "Argentina",
        "ID" to "Indonesia",
        "MY" to "Malaysia",
        "TH" to "Thailand",
        "VN" to "Vietnam",
        "PH" to "Philippines",
        "PK" to "Pakistan",
        "IQ" to "Iraq",
        "KW" to "Kuwait",
        "QA" to "Qatar",
        "BH" to "Bahrain",
        "OM" to "Oman",
        "JO" to "Jordan",
        "AZ" to "Azerbaijan",
        "GE" to "Georgia",
        "AM" to "Armenia",
        "KZ" to "Kazakhstan",
    )

    val all: List<FofaCountryPreset> by lazy {
        priority.map { FofaCountryPreset(it.first, it.second, true) } +
            others.sortedBy { it.second }.map { FofaCountryPreset(it.first, it.second, false) }
    }

    fun findByCode(code: String?): FofaCountryPreset? {
        if (code.isNullOrEmpty()) return null
        return all.firstOrNull { it.code.equals(code, ignoreCase = true) }
    }
}
