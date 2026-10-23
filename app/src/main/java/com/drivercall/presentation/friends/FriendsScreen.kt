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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.drivercall.domain.model.Friend
import com.drivercall.domain.model.FriendStatus
import com.drivercall.domain.model.User
import com.drivercall.presentation.components.EmptyState
import com.drivercall.presentation.components.ScreenScaffold

@Composable
fun FriendsScreen(
    onFriendClick: (String) -> Unit,
    onBack: () -> Unit,
    viewModel: FriendsViewModel = hiltViewModel()
) {
    val friends by viewModel.friends.collectAsState()
    val state = viewModel.uiState
    val snackbarHost = remember { SnackbarHostState() }
    val incomingRequests = friends.filter { it.status == FriendStatus.PENDING_INCOMING }
    val acceptedFriends = friends.filter { it.status == FriendStatus.ACCEPTED }
    val sentRequests = friends.filter { it.status == FriendStatus.PENDING_OUTGOING }

    LaunchedEffect(state.actionMessage) {
        state.actionMessage?.let {
            snackbarHost.showSnackbar(it)
            viewModel.clearMessage()
        }
    }

    ScreenScaffold(
        title = "Friends",
        onBack = onBack,
        actions = {
            IconButton(onClick = viewModel::refresh, enabled = !state.isRefreshing) {
                if (state.isRefreshing) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Filled.Refresh, contentDescription = "Refresh friend requests")
                }
            }
        },
        snackbarHostState = snackbarHost
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            OutlinedTextField(
                value = state.searchQuery,
                onValueChange = viewModel::onSearchQueryChange,
                label = { Text("Search people by name or email") },
                leadingIcon = { Icon(Icons.Filled.PersonAdd, contentDescription = null) },
                trailingIcon = {
                    if (state.isSearching) CircularProgressIndicator(Modifier.size(20.dp))
                },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            )

            LazyColumn(modifier = Modifier.fillMaxSize()) {
                if (state.searchResults.isNotEmpty()) {
                    item {
                        Text(
                            text = "Search results",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                        )
                    }
                    items(state.searchResults, key = { it.id }) { user ->
                        SearchResultRow(
                            user = user,
                            onInvite = { viewModel.sendInvite(user) }
                        )
                    }
                    item { Spacer(Modifier.height(8.dp)) }
                }

                if (incomingRequests.isNotEmpty()) {
                    item {
                        Text(
                            text = "Friend requests (${incomingRequests.size})",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                        )
                    }
                    items(incomingRequests, key = { it.id }) { friend ->
                        FriendRow(
                            friend = friend,
                            onClick = { onFriendClick(friend.id) },
                            onAccept = { viewModel.acceptInvite(friend) }
                        )
                    }
                    item { Spacer(Modifier.height(8.dp)) }
                }

                item {
                    Text(
                        text = "Your friends (${acceptedFriends.size})",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                }

                if (acceptedFriends.isEmpty() && incomingRequests.isEmpty() && sentRequests.isEmpty() && !state.isRefreshing) {
                    item {
                        EmptyState(
                            title = "No friends yet",
                            message = "Search above to find people and send them an invitation."
                        )
                    }
                }

                items(acceptedFriends, key = { it.id }) { friend ->
                    FriendRow(
                        friend = friend,
                        onClick = { onFriendClick(friend.id) },
                        onAccept = null
                    )
                }

                if (sentRequests.isNotEmpty()) {
                    item {
                        Text(
                            text = "Sent requests (${sentRequests.size})",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                        )
                    }
                    items(sentRequests, key = { it.id }) { friend ->
                        FriendRow(
                            friend = friend,
                            onClick = { onFriendClick(friend.id) },
                            onAccept = null
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FriendRow(
    friend: Friend,
    onClick: () -> Unit,
    onAccept: (() -> Unit)?
) {
    Card(
        onClick = onClick,
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
            BadgedBox(
                badge = {
                    if (friend.isOnline) Badge(containerColor = MaterialTheme.colorScheme.primary)
                }
            ) {
                Surface(
                    modifier = Modifier.size(48.dp),
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primaryContainer
                ) {
                    Icon(
                        Icons.Filled.Person,
                        contentDescription = null,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(text = friend.name, style = MaterialTheme.typography.titleMedium)
                Text(
                    text = when (friend.status) {
                        FriendStatus.ACCEPTED -> if (friend.isOnline) "Online" else "Offline"
                        FriendStatus.PENDING_INCOMING -> "Wants to be your friend"
                        FriendStatus.PENDING_OUTGOING -> "Waiting for acceptance"
                        FriendStatus.BLOCKED -> "Blocked"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (onAccept != null) {
                IconButton(onClick = onAccept) {
                    Icon(Icons.Filled.Check, contentDescription = "Accept invitation")
                }
            }
        }
    }
}

@Composable
private fun SearchResultRow(
    user: User,
    onInvite: () -> Unit
) {
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
            Surface(
                modifier = Modifier.size(48.dp),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer
            ) {
                Icon(Icons.Filled.Person, contentDescription = null, modifier = Modifier.padding(12.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(text = user.name, style = MaterialTheme.typography.titleMedium)
                Text(
                    text = user.email,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = onInvite) {
                Icon(Icons.Filled.PersonAdd, contentDescription = "Send invitation")
            }
        }
    }
}
