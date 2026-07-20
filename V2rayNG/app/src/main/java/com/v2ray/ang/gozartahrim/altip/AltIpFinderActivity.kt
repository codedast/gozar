package com.v2ray.ang.gozartahrim.altip

import android.os.Bundle
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.v2ray.ang.R
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.ui.BaseComponentActivity

/**
 * GozarTahrim: Alt IP Finder screen — finds alternate Cloudflare front-IPs for the
 * currently selected VLESS profile and saves the working ones as a new server group.
 */
class AltIpFinderActivity : BaseComponentActivity() {

    private val viewModel: AltIpFinderViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        viewModel.load(MmkvManager.getSelectServer())
        super.onCreate(savedInstanceState)
    }

    @Composable
    override fun ScreenContent() {
        AltIpFinderScreen(viewModel = viewModel)
    }
}

/**
 * Latin-only content (IPs, SNI hostnames, FOFA queries) is forced to LTR so it does not get
 * reordered when the UI language is right-to-left.
 */
private val ltrMono
    @Composable get() = MaterialTheme.typography.bodySmall.copy(
        fontFamily = FontFamily.Monospace,
        textDirection = TextDirection.Ltr
    )

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AltIpFinderScreen(viewModel: AltIpFinderViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var countryMenuOpen by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text(stringResource(R.string.title_gt_alt_ip_finder)) })
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            val profile = state.sourceProfile
            if (profile == null) {
                Text(stringResource(R.string.gt_altip_no_server))
                return@Column
            }
            if (!AltIpFinderManager.isSupportedProfile(profile)) {
                Text(stringResource(R.string.gt_altip_unsupported))
                return@Column
            }

            Text(
                text = stringResource(R.string.gt_altip_server_label, profile.remarks),
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                text = stringResource(
                    R.string.gt_altip_sni_label,
                    AltIpFinderManager.resolveSni(profile)
                ),
                style = ltrMono
            )

            // Country preset
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = { countryMenuOpen = true }, enabled = !state.running) {
                    val preset = FofaCountryPresets.findByCode(state.countryCode)
                    Text(preset?.displayName ?: state.countryCode)
                }
                DropdownMenu(
                    expanded = countryMenuOpen,
                    onDismissRequest = { countryMenuOpen = false }
                ) {
                    FofaCountryPresets.all.forEach { preset ->
                        DropdownMenuItem(
                            text = { Text(preset.displayName) },
                            onClick = {
                                countryMenuOpen = false
                                viewModel.setCountry(preset.code)
                            }
                        )
                    }
                }
            }

            OutlinedTextField(
                value = state.query,
                onValueChange = viewModel::setQuery,
                label = { Text(stringResource(R.string.gt_altip_query)) },
                enabled = !state.running,
                textStyle = ltrMono,
                modifier = Modifier.fillMaxWidth()
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = state.sampleCount,
                    onValueChange = viewModel::setSampleCount,
                    label = { Text(stringResource(R.string.gt_altip_sample_count)) },
                    enabled = !state.running,
                    modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = state.concurrency,
                    onValueChange = viewModel::setConcurrency,
                    label = { Text(stringResource(R.string.gt_altip_concurrency)) },
                    enabled = !state.running,
                    modifier = Modifier.weight(1f)
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { viewModel.start() }, enabled = !state.running) {
                    Text(stringResource(R.string.gt_altip_start))
                }
                if (state.running) {
                    OutlinedButton(onClick = { viewModel.cancel() }) {
                        Text(stringResource(R.string.gt_altip_cancel))
                    }
                }
            }

            if (state.statusText.isNotEmpty()) {
                Text(state.statusText, style = MaterialTheme.typography.bodySmall)
            }

            if (state.running && state.total > 0) {
                LinearProgressIndicator(
                    progress = { state.tested.toFloat() / state.total.toFloat() },
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    stringResource(R.string.gt_altip_progress, state.tested, state.total),
                    style = MaterialTheme.typography.bodySmall
                )
            }

            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(state.results) { result ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "${result.source.label}  ${result.ip}",
                                style = ltrMono
                            )
                            Text(
                                text = when {
                                    result.tlsOk -> "TLS ✓ ${result.latencyMs} ms"
                                    result.tcpOk -> "TCP ✓ ${result.latencyMs} ms"
                                    else -> "✗"
                                },
                                style = ltrMono
                            )
                        }
                    }
                }
            }
        }
    }
}
