package com.drivercall.presentation.call

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BluetoothAudio
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PhoneForwarded
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.drivercall.domain.call.CallAudioEndpoint
import com.drivercall.domain.call.CallQuality
import com.drivercall.domain.call.CallStage
import com.drivercall.domain.call.CallState
import kotlinx.coroutines.delay

@Composable
fun ActiveCallScreen(
    onBack: () -> Unit,
    onCustomerCall: () -> Unit,
    onDriverMode: () -> Unit,
    onDiagnostics: () -> Unit,
    viewModel: CallViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()
    val endpoints by viewModel.audioEndpoints.collectAsState()
    val selectedEndpoint by viewModel.currentAudioEndpoint.collectAsState()
    var showAudio by remember { mutableStateOf(false) }
    var sawCallSession by remember { mutableStateOf(state.shouldShowCallUi) }

    LaunchedEffect(state.shouldShowCallUi) {
        if (state.shouldShowCallUi) sawCallSession = true
        else if (sawCallSession) onBack()
    }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 36.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDiagnostics) {
                    Icon(Icons.Filled.NetworkCheck, contentDescription = null)
                    Text("  Details")
                }
            }
            Spacer(Modifier.weight(0.5f))
            Surface(modifier = Modifier.size(120.dp), shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer) {
                Icon(Icons.Filled.Person, contentDescription = null, modifier = Modifier.padding(30.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
            }
            Spacer(Modifier.height(20.dp))
            Text(state.peerName.ifBlank { "Friend call" }, style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(8.dp))
            Text(state.statusMessage, style = MaterialTheme.typography.bodyLarge, color = qualityColor(state.quality))
            CallTimer(state)
            Spacer(Modifier.height(20.dp))
            StatusCard(state)
            Spacer(Modifier.weight(1f))

            when {
                state.incoming && state.stage == CallStage.RINGING -> IncomingActions(viewModel::decline, viewModel::accept)
                state.stage in setOf(CallStage.VOIP_DISCONNECTED, CallStage.RECONNECTING) -> {
                    Button(onClick = viewModel::reconnect, modifier = Modifier.fillMaxWidth().height(60.dp)) {
                        Icon(Icons.Filled.Refresh, contentDescription = null)
                        Text("  Retry connection")
                    }
                    Spacer(Modifier.height(12.dp))
                    OutlinedButton(onClick = viewModel::end, modifier = Modifier.fillMaxWidth().height(56.dp)) { Text("End call") }
                }
                else -> {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        CallControl(if (state.muted) Icons.Filled.MicOff else Icons.Filled.Mic, if (state.muted) "Unmute" else "Mute", viewModel::toggleMute)
                        CallControl(Icons.Filled.BluetoothAudio, selectedEndpoint?.name ?: "Audio", { showAudio = true })
                        CallControl(Icons.Filled.DirectionsCar, "Driver", onDriverMode)
                    }
                    Spacer(Modifier.height(24.dp))
                    Button(
                        onClick = onCustomerCall,
                        enabled = state.canTalk,
                        modifier = Modifier.fillMaxWidth().height(56.dp)
                    ) {
                        Icon(Icons.Filled.PhoneForwarded, contentDescription = null)
                        Text("  Call customer")
                    }
                    Spacer(Modifier.height(12.dp))
                    Button(
                        onClick = viewModel::end,
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                        modifier = Modifier.fillMaxWidth().height(64.dp)
                    ) {
                        Icon(Icons.Filled.CallEnd, contentDescription = null)
                        Text("  End friend call")
                    }
                }
            }
        }
    }

    if (showAudio) AudioPicker(endpoints, selectedEndpoint?.id, { showAudio = false }) {
        viewModel.selectAudio(it)
        showAudio = false
    }
}

@Composable
private fun IncomingActions(onDecline: () -> Unit, onAccept: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            FilledIconButton(onClick = onDecline, modifier = Modifier.size(72.dp), colors = androidx.compose.material3.IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.error)) {
                Icon(Icons.Filled.CallEnd, "Decline call", modifier = Modifier.size(32.dp))
            }
            Text("Decline")
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            FilledIconButton(onClick = onAccept, modifier = Modifier.size(72.dp)) {
                Icon(Icons.Filled.Call, "Accept call", modifier = Modifier.size(32.dp))
            }
            Text("Accept")
        }
    }
}

@Composable
private fun CallControl(icon: ImageVector, label: String, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        FilledTonalIconButton(onClick = onClick, modifier = Modifier.size(64.dp)) {
            Icon(icon, contentDescription = label, modifier = Modifier.size(28.dp))
        }
        Spacer(Modifier.height(6.dp))
        Text(label, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun StatusCard(state: CallState) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant), modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.fillMaxWidth().padding(14.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Column {
                Text("Call quality", style = MaterialTheme.typography.bodyMedium)
                Text(state.quality.displayName(), style = MaterialTheme.typography.titleMedium, color = qualityColor(state.quality))
            }
            Column(horizontalAlignment = Alignment.End) {
                Text("Noise cancellation", style = MaterialTheme.typography.bodyMedium)
                Text("On", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            }
        }
        Text(
            "Network: ${state.diagnostics.networkLabel}",
            modifier = Modifier.padding(start = 14.dp, end = 14.dp, bottom = 14.dp),
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

@Composable
private fun CallTimer(state: CallState) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val connectedAt = state.connectedAtEpochMs
    LaunchedEffect(connectedAt) {
        while (connectedAt != null) {
            now = System.currentTimeMillis()
            delay(1_000)
        }
    }
    if (connectedAt != null) {
        val total = ((now - connectedAt) / 1_000).coerceAtLeast(0)
        Text("%02d:%02d".format(total / 60, total % 60), style = MaterialTheme.typography.titleLarge)
    }
}

@Composable
private fun AudioPicker(endpoints: List<CallAudioEndpoint>, selectedId: String?, dismiss: () -> Unit, select: (String) -> Unit) {
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text("Play audio through") },
        text = {
            Column {
                if (endpoints.isEmpty()) Text("No audio devices are currently available.")
                endpoints.forEach { endpoint ->
                    TextButton(onClick = { select(endpoint.id) }, modifier = Modifier.fillMaxWidth()) {
                        Text(if (endpoint.id == selectedId) "✓  ${endpoint.name}" else endpoint.name)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = dismiss) { Text("Close") } }
    )
}

@Composable
private fun qualityColor(quality: CallQuality): Color = when (quality) {
    CallQuality.EXCELLENT, CallQuality.GOOD -> MaterialTheme.colorScheme.primary
    CallQuality.WEAK -> Color(0xFFFFB020)
    CallQuality.NO_INTERNET -> MaterialTheme.colorScheme.error
    CallQuality.UNKNOWN -> MaterialTheme.colorScheme.onSurfaceVariant
}

private fun CallQuality.displayName() = name.lowercase().replace('_', ' ').replaceFirstChar(Char::uppercase)
