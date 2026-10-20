package com.drivercall.presentation.call

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.drivercall.presentation.components.ScreenScaffold

@Composable
fun NetworkDiagnosticsScreen(onBack: () -> Unit, viewModel: CallViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsState()
    val diagnostics = state.diagnostics
    ScreenScaffold(title = "Network diagnostics", onBack = onBack) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).padding(20.dp)) {
            Text("Live call health", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(8.dp))
            Text(
                if (state.hasLiveSession) "Metrics update while your friend call is active." else "Start a friend call to collect live audio metrics.",
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(20.dp))
            MetricCard("Connection", diagnostics.networkLabel)
            MetricCard("Quality", state.quality.name.lowercase().replace('_', ' ').replaceFirstChar(Char::uppercase))
            MetricCard("Latency", diagnostics.latencyMs?.let { "$it ms" } ?: "Not available")
            MetricCard("Jitter", diagnostics.jitterMs?.let { "$it ms" } ?: "Not available")
            MetricCard("Packet loss", diagnostics.packetLossPercent?.let { "%.1f%%".format(it) } ?: "Not available")
            MetricCard("Outgoing bitrate", diagnostics.availableOutgoingBitrateKbps?.let { "$it kbps" } ?: "Not available")
            MetricCard("Reconnects", diagnostics.reconnectionCount.toString())
        }
    }
}

@Composable
private fun MetricCard(label: String, value: String) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.titleLarge)
        }
    }
}
