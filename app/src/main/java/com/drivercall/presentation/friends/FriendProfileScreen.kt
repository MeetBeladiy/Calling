package com.drivercall.presentation.friends

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.drivercall.domain.model.FriendStatus
import com.drivercall.presentation.components.EmptyState
import com.drivercall.presentation.components.ScreenScaffold
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun FriendProfileScreen(
    friendId: String,
    onBack: () -> Unit,
    onCall: (String, String) -> Unit,
    viewModel: FriendProfileViewModel = hiltViewModel()
) {
    val friend by viewModel.friend.collectAsState()
    var showRemoveDialog by remember { mutableStateOf(false) }
    var showBlockDialog by remember { mutableStateOf(false) }

    LaunchedEffect(viewModel.removed) {
        if (viewModel.removed) onBack()
    }

    ScreenScaffold(title = "Friend", onBack = onBack) { padding ->
        val current = friend
        if (current == null) {
            EmptyState(
                title = "Friend not found",
                message = "This contact may have been removed.",
                modifier = Modifier.padding(padding)
            )
            return@ScreenScaffold
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Surface(
                modifier = Modifier.size(96.dp),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer
            ) {
                Icon(
                    Icons.Filled.Person,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.padding(24.dp)
                )
            }
            Spacer(Modifier.height(16.dp))
            Text(text = current.name, style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(4.dp))
            Text(
                text = when (current.status) {
                    FriendStatus.ACCEPTED -> if (current.isOnline) "Online now" else lastActiveText(current.lastActiveAt)
                    FriendStatus.PENDING -> "Invitation pending"
                    FriendStatus.BLOCKED -> "Blocked"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(32.dp))

            Button(
                onClick = { onCall(current.friendUserId, current.name) },
                enabled = current.status == FriendStatus.ACCEPTED,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
            ) {
                Icon(Icons.Filled.Call, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(if (current.isOnline) "Call now" else "Call friend")
            }

            Spacer(Modifier.height(16.dp))

            Row(modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(
                    onClick = { showRemoveDialog = true },
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Filled.Delete, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Remove")
                }
                Spacer(Modifier.width(12.dp))
                OutlinedButton(
                    onClick = { showBlockDialog = true },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Icon(Icons.Filled.Block, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Block")
                }
            }

            viewModel.message?.let { msg ->
                Spacer(Modifier.height(12.dp))
                Text(text = msg, color = MaterialTheme.colorScheme.error)
            }
        }
    }

    if (showRemoveDialog) {
        AlertDialog(
            onDismissRequest = { showRemoveDialog = false },
            title = { Text("Remove friend?") },
            text = { Text("You will no longer be able to call each other.") },
            confirmButton = {
                TextButton(onClick = {
                    showRemoveDialog = false
                    viewModel.removeFriend()
                }) { Text("Remove") }
            },
            dismissButton = {
                TextButton(onClick = { showRemoveDialog = false }) { Text("Cancel") }
            }
        )
    }

    if (showBlockDialog) {
        AlertDialog(
            onDismissRequest = { showBlockDialog = false },
            title = { Text("Block this person?") },
            text = { Text("Blocked contacts cannot call you or send invitations.") },
            confirmButton = {
                TextButton(onClick = {
                    showBlockDialog = false
                    viewModel.blockUser()
                }) { Text("Block") }
            },
            dismissButton = {
                TextButton(onClick = { showBlockDialog = false }) { Text("Cancel") }
            }
        )
    }
}

private fun lastActiveText(lastActiveAt: Long?): String {
    if (lastActiveAt == null) return "Offline"
    val formatted = SimpleDateFormat("d MMM, HH:mm", Locale.getDefault())
        .format(Date(lastActiveAt))
    return "Last active $formatted"
}
