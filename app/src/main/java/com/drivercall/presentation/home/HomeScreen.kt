package com.drivercall.presentation.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.drivercall.domain.model.NetworkStatus
import com.drivercall.domain.model.NetworkType

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onFriendsClick: () -> Unit,
    onCustomerCallClick: () -> Unit,
    onDriverModeClick: () -> Unit,
    onDiagnosticsClick: () -> Unit,
    onHistoryClick: () -> Unit,
    onSettingsClick: () -> Unit,
    viewModel: HomeViewModel = hiltViewModel()
) {
    val profile by viewModel.profile.collectAsState()
    val networkStatus by viewModel.networkStatus.collectAsState()

    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(20.dp)
                .verticalScroll(rememberScrollState())
        ) {
            // Profile header
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    modifier = Modifier.size(56.dp),
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primaryContainer
                ) {
                    Icon(
                        imageVector = Icons.Filled.Person,
                        contentDescription = "Profile",
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.padding(14.dp)
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(
                        text = profile?.name ?: "Driver",
                        style = MaterialTheme.typography.titleLarge
                    )
                    Text(
                        text = profile?.email ?: "",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(Modifier.height(20.dp))

            ConnectionStatusCard(networkStatus)

            Spacer(Modifier.height(20.dp))

            Text(
                text = "Quick actions",
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(Modifier.height(12.dp))

            HomeActionCard(
                icon = Icons.Filled.Call,
                title = "Start Friend Call",
                subtitle = "Choose a friend for a secure internet call",
                onClick = onFriendsClick
            )
            Spacer(Modifier.height(12.dp))
            HomeActionCard(
                icon = Icons.Filled.Call,
                title = "Call Customer",
                subtitle = "Dial a number without losing your friend session",
                onClick = onCustomerCallClick
            )
            Spacer(Modifier.height(12.dp))
            HomeActionCard(
                icon = Icons.Filled.DirectionsCar,
                title = "Driver Mode",
                subtitle = "Large, glanceable in-call controls",
                onClick = onDriverModeClick
            )
            Spacer(Modifier.height(12.dp))
            HomeActionCard(
                icon = Icons.Filled.NetworkCheck,
                title = "Network Diagnostics",
                subtitle = "See call quality and connection details",
                onClick = onDiagnosticsClick
            )
            Spacer(Modifier.height(12.dp))
            HomeActionCard(
                icon = Icons.Filled.People,
                title = "Friends",
                subtitle = "Add friends and manage invitations",
                onClick = onFriendsClick
            )
            Spacer(Modifier.height(12.dp))
            HomeActionCard(
                icon = Icons.Filled.History,
                title = "Recent Calls",
                subtitle = "Your VoIP and customer call history",
                onClick = onHistoryClick
            )
            Spacer(Modifier.height(12.dp))
            HomeActionCard(
                icon = Icons.Filled.Settings,
                title = "Settings",
                subtitle = "Theme, privacy, help and sign out",
                onClick = onSettingsClick
            )
        }
    }
}

@Composable
private fun ConnectionStatusCard(status: NetworkStatus) {
    val (label, color) = when (status) {
        is NetworkStatus.Available -> when (status.type) {
            NetworkType.WIFI -> "Connected · Wi-Fi" to MaterialTheme.colorScheme.primary
            NetworkType.CELLULAR_5G -> "Connected · 5G" to MaterialTheme.colorScheme.primary
            NetworkType.CELLULAR_4G -> "Connected · 4G" to MaterialTheme.colorScheme.primary
            NetworkType.CELLULAR -> "Connected · Mobile data" to MaterialTheme.colorScheme.primary
            NetworkType.ETHERNET -> "Connected · Ethernet" to MaterialTheme.colorScheme.primary
            NetworkType.VPN -> "Connected · VPN" to MaterialTheme.colorScheme.primary
            NetworkType.OTHER -> "Connected" to MaterialTheme.colorScheme.primary
        }
        NetworkStatus.Unavailable ->
            "No internet" to MaterialTheme.colorScheme.error
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "Connection status",
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                color = color
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HomeActionCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    enabled: Boolean = true
) {
    Card(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (enabled) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(28.dp)
            )
            Spacer(Modifier.width(16.dp))
            Column {
                Text(text = title, style = MaterialTheme.typography.titleMedium)
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
