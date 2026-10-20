package com.drivercall.presentation.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.drivercall.domain.model.NetworkStatus
import com.drivercall.domain.model.User
import com.drivercall.domain.usecase.GetProfileUseCase
import com.drivercall.domain.usecase.ObserveNetworkStatusUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class HomeViewModel @Inject constructor(
    getProfileUseCase: GetProfileUseCase,
    observeNetworkStatus: ObserveNetworkStatusUseCase
) : ViewModel() {

    val profile: StateFlow<User?> = getProfileUseCase.observe()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val networkStatus: StateFlow<NetworkStatus> = observeNetworkStatus()
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            NetworkStatus.Unavailable
        )

    init {
        viewModelScope.launch {
            getProfileUseCase.refresh()
        }
    }
}
