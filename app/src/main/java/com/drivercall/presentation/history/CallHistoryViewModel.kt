package com.drivercall.presentation.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.drivercall.domain.model.CallRecord
import com.drivercall.domain.usecase.ObserveCallHistoryUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class CallHistoryViewModel @Inject constructor(
    observeCallHistoryUseCase: ObserveCallHistoryUseCase
) : ViewModel() {
    val history: StateFlow<List<CallRecord>> = observeCallHistoryUseCase()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
}
