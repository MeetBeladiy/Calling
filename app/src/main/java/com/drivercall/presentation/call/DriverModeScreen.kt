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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.PhoneForwarded
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.drivercall.domain.call.CallStage
import com.drivercall.presentation.components.ScreenScaffold

@Composable
fun DriverModeScreen(
    onBack: () -> Unit,
    onCustomerCall: () -> Unit,
    viewModel: CallViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()
    ScreenScaffold(title = "Driver Mode", onBack = onBack) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Surface(
                shape = RoundedCornerShape(28.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(if (state.hasLiveSession) state.peerName.ifBlank { "Friend call" } else "No active friend call", style = MaterialTheme.typography.headlineSmall)
                    Spacer(Modifier.height(8.dp))
                    Text(state.statusMessage, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                }
            }
            Spacer(Modifier.height(24.dp))
            Button(
                onClick = viewModel::toggleMute,
                enabled = state.hasLiveSession,
                modifier = Modifier.fillMaxWidth().height(88.dp)
            ) {
                Icon(if (state.muted) Icons.Filled.MicOff else Icons.Filled.Mic, contentDescription = null, modifier = Modifier.size(36.dp))
                Text(if (state.muted) "  UNMUTE" else "  MUTE", style = MaterialTheme.typography.titleLarge)
            }
            Spacer(Modifier.height(16.dp))
            if (state.stage in setOf(CallStage.RECONNECTING, CallStage.VOIP_DISCONNECTED)) {
                Button(onClick = viewModel::reconnect, modifier = Modifier.fillMaxWidth().height(88.dp)) {
                    Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(36.dp))
                    Text("  RETRY", style = MaterialTheme.typography.titleLarge)
                }
            } else {
                Button(onClick = onCustomerCall, enabled = state.canTalk, modifier = Modifier.fillMaxWidth().height(88.dp)) {
                    Icon(Icons.Filled.PhoneForwarded, contentDescription = null, modifier = Modifier.size(36.dp))
                    Text("  CUSTOMER", style = MaterialTheme.typography.titleLarge)
                }
            }
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = viewModel::end,
                enabled = state.hasLiveSession,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                modifier = Modifier.fillMaxWidth().height(88.dp)
            ) {
                Icon(Icons.Filled.CallEnd, contentDescription = null, modifier = Modifier.size(36.dp))
                Text("  END CALL", style = MaterialTheme.typography.titleLarge)
            }
        }
    }
}
