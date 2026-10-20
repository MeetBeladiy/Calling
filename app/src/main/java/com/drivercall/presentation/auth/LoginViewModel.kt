package com.drivercall.presentation.auth

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.drivercall.domain.usecase.LoginUseCase
import com.drivercall.domain.usecase.RequestPasswordResetUseCase
import com.drivercall.domain.util.Result
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class LoginViewModel @Inject constructor(
    private val loginUseCase: LoginUseCase,
    private val requestPasswordResetUseCase: RequestPasswordResetUseCase
) : ViewModel() {

    var uiState by mutableStateOf(LoginUiState())
        private set

    fun onEmailChange(value: String) {
        uiState = uiState.copy(email = value, errorMessage = null, resetMessage = null)
    }

    fun onPasswordChange(value: String) {
        uiState = uiState.copy(password = value, errorMessage = null)
    }

    fun login() {
        if (uiState.isLoading) return
        uiState = uiState.copy(isLoading = true, errorMessage = null)
        viewModelScope.launch {
            when (val result = loginUseCase(uiState.email, uiState.password)) {
                is Result.Success -> uiState = uiState.copy(isLoading = false, loginSuccess = true)
                is Result.Error -> uiState = uiState.copy(isLoading = false, errorMessage = result.message)
                Result.Loading -> Unit
            }
        }
    }

    fun consumeSuccess() {
        uiState = uiState.copy(loginSuccess = false)
    }

    fun requestPasswordReset() {
        if (uiState.isLoading) return
        uiState = uiState.copy(isLoading = true, errorMessage = null, resetMessage = null)
        viewModelScope.launch {
            when (val result = requestPasswordResetUseCase(uiState.email)) {
                is Result.Success -> uiState = uiState.copy(
                    isLoading = false,
                    resetMessage = "If that account exists, reset instructions will be sent."
                )
                is Result.Error -> uiState = uiState.copy(
                    isLoading = false,
                    errorMessage = result.message
                )
                Result.Loading -> Unit
            }
        }
    }
}
