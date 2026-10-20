package com.drivercall.presentation.history

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.drivercall.domain.model.CallRecord
import com.drivercall.domain.model.CallType
import com.drivercall.presentation.components.EmptyState
import com.drivercall.presentation.components.ScreenScaffold
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun CallHistoryScreen(
    onBack: () -> Unit,
    viewModel: CallHistoryViewModel = hiltViewModel()
) {
    val history by viewModel.history.collectAsState()

    // Customer (cellular) calls are kept visually separate from VoIP calls.
    val voipCalls = history.filter { it.type == CallType.VOIP }
    val cellularCalls = history.filter { it.type == CallType.CELLULAR }

    ScreenScaffold(title = "Recent Calls", onBack = onBack) { padding ->
        if (history.isEmpty()) {
            EmptyState(
                title = "No calls yet",
                message = "Your Calling App and customer calls will appear here.",
                modifier = Modifier.padding(padding)
            )
            return@ScreenScaffold
        }
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            if (voipCalls.isNotEmpty()) {
                item {
                    SectionHeader("Calling App (internet)")
                }
                items(voipCalls, key = { it.id }) { record ->
                    CallRow(record)
                }
            }
            if (cellularCalls.isNotEmpty()) {
                item {
                    SectionHeader("Customer (cellular)")
                }
                items(cellularCalls, key = { it.id }) { record ->
                    CallRow(record)
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
    )
}

@Composable
private fun CallRow(record: CallRecord) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = if (record.type == CallType.VOIP) Icons.Filled.Call
                else Icons.Filled.PhoneAndroid,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(text = record.peerName, style = MaterialTheme.typography.titleMedium)
                Text(
                    text = formatDateTime(record.startTimeMillis),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = formatDuration(record.durationSeconds),
                    style = MaterialTheme.typography.labelLarge
                )
                if (record.reconnectionCount > 0) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = "${record.reconnectionCount} reconnects",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

private fun formatDateTime(millis: Long): String =
    SimpleDateFormat("d MMM, HH:mm", Locale.getDefault()).format(Date(millis))

private fun formatDuration(seconds: Long): String {
    val m = seconds / 60
    val s = seconds % 60
    return if (m > 0) "${m}m ${s}s" else "${s}s"
}
