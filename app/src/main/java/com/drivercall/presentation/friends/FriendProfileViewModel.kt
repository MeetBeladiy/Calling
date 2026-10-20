package com.drivercall.presentation.friends

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.drivercall.domain.model.Friend
import com.drivercall.domain.usecase.BlockUserUseCase
import com.drivercall.domain.usecase.GetFriendsUseCase
import com.drivercall.domain.usecase.RemoveFriendUseCase
import com.drivercall.domain.util.Result
import com.drivercall.presentation.navigation.Routes
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class FriendProfileViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    getFriendsUseCase: GetFriendsUseCase,
    private val removeFriendUseCase: RemoveFriendUseCase,
    private val blockUserUseCase: BlockUserUseCase
) : ViewModel() {

    private val friendId: String =
        savedStateHandle.get<String>(Routes.FriendProfile.ARG_FRIEND_ID).orEmpty()

    val friend: StateFlow<Friend?> = getFriendsUseCase.observe()
        .map { list -> list.firstOrNull { it.id == friendId } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    var message by mutableStateOf<String?>(null)
        private set

    var removed by mutableStateOf(false)
        private set

    fun removeFriend() {
        viewModelScope.launch {
            when (val result = removeFriendUseCase(friendId)) {
                is Result.Success -> removed = true
                is Result.Error -> message = result.message
                Result.Loading -> Unit
            }
        }
    }

    fun blockUser() {
        viewModelScope.launch {
            when (val result = blockUserUseCase(friendId)) {
                is Result.Success -> removed = true
                is Result.Error -> message = result.message
                Result.Loading -> Unit
            }
        }
    }

    fun clearMessage() { message = null }
}
