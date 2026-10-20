package com.drivercall.domain.util

/**
 * A simple result wrapper used across repositories and use cases.
 * Keeps error handling explicit without throwing through layers.
 */
sealed interface Result<out T> {
    data class Success<T>(val data: T) : Result<T>
    data class Error(val message: String, val cause: Throwable? = null) : Result<Nothing>
    data object Loading : Result<Nothing>
}
