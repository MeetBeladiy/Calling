package com.drivercall.presentation.friends

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.drivercall.domain.model.Friend
import com.drivercall.domain.model.User
import com.drivercall.domain.usecase.AcceptFriendInviteUseCase
import com.drivercall.domain.usecase.GetFriendsUseCase
import com.drivercall.domain.usecase.SearchUsersUseCase
import com.drivercall.domain.usecase.SendFriendInviteUseCase
import com.drivercall.domain.util.Result
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class FriendsUiState(
    val searchQuery: String = "",
    val searchResults: List<User> = emptyList(),
    val isSearching: Boolean = false,
    val actionMessage: String? = null,
    val isRefreshing: Boolean = false
)

@HiltViewModel
class FriendsViewModel @Inject constructor(
    private val getFriendsUseCase: GetFriendsUseCase,
    private val searchUsersUseCase: SearchUsersUseCase,
    private val sendFriendInviteUseCase: SendFriendInviteUseCase,
    private val acceptFriendInviteUseCase: AcceptFriendInviteUseCase
) : ViewModel() {

    val friends: StateFlow<List<Friend>> = getFriendsUseCase.observe()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    var uiState by mutableStateOf(FriendsUiState())
        private set

    private var searchJob: Job? = null

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            uiState = uiState.copy(isRefreshing = true)
            getFriendsUseCase.refresh()
            uiState = uiState.copy(isRefreshing = false)
        }
    }

    fun onSearchQueryChange(query: String) {
        uiState = uiState.copy(searchQuery = query, actionMessage = null)
        searchJob?.cancel()
        if (query.trim().length < 2) {
            uiState = uiState.copy(searchResults = emptyList(), isSearching = false)
            return
        }
        searchJob = viewModelScope.launch {
            delay(400) // debounce
            uiState = uiState.copy(isSearching = true)
            when (val result = searchUsersUseCase(query)) {
                is Result.Success -> uiState = uiState.copy(
                    searchResults = result.data,
                    isSearching = false
                )
                is Result.Error -> uiState = uiState.copy(
                    searchResults = emptyList(),
                    isSearching = false,
                    actionMessage = result.message
                )
                Result.Loading -> Unit
            }
        }
    }

    fun sendInvite(user: User) {
        viewModelScope.launch {
            when (val result = sendFriendInviteUseCase(user.id)) {
                is Result.Success -> uiState = uiState.copy(
                    actionMessage = "Invitation sent to ${user.name}."
                )
                is Result.Error -> uiState = uiState.copy(actionMessage = result.message)
                Result.Loading -> Unit
            }
        }
    }

    fun acceptInvite(friend: Friend) {
        viewModelScope.launch {
            when (val result = acceptFriendInviteUseCase(friend.id)) {
                is Result.Success -> uiState = uiState.copy(
                    actionMessage = "${friend.name} added to your friends."
                )
                is Result.Error -> uiState = uiState.copy(actionMessage = result.message)
                Result.Loading -> Unit
            }
        }
    }

    fun clearMessage() {
        uiState = uiState.copy(actionMessage = null)
    }
}
