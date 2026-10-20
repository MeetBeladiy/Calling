package com.drivercall.presentation.auth

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.drivercall.domain.usecase.RegisterUseCase
import com.drivercall.domain.util.Result
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class RegisterViewModel @Inject constructor(
    private val registerUseCase: RegisterUseCase
) : ViewModel() {

    var uiState by mutableStateOf(RegisterUiState())
        private set

    fun onNameChange(value: String) { uiState = uiState.copy(name = value, errorMessage = null) }
    fun onEmailChange(value: String) { uiState = uiState.copy(email = value, errorMessage = null) }
    fun onPasswordChange(value: String) { uiState = uiState.copy(password = value, errorMessage = null) }

    fun register() {
        if (uiState.isLoading) return
        uiState = uiState.copy(isLoading = true, errorMessage = null)
        viewModelScope.launch {
            when (val result = registerUseCase(uiState.name, uiState.email, uiState.password)) {
                is Result.Success -> uiState = uiState.copy(isLoading = false, registerSuccess = true)
                is Result.Error -> uiState = uiState.copy(isLoading = false, errorMessage = result.message)
                Result.Loading -> Unit
            }
        }
    }

    fun consumeSuccess() {
        uiState = uiState.copy(registerSuccess = false)
    }
}
