package com.drivercall.presentation.auth

data class LoginUiState(
    val email: String = "",
    val password: String = "",
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val resetMessage: String? = null,
    val loginSuccess: Boolean = false
)
