package com.drivercall.domain.usecase

import com.drivercall.data.network.NetworkMonitor
import com.drivercall.domain.model.NetworkStatus
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

/**
 * Exposes live connectivity state for the Home "Connection Status" card.
 * (Full network-switch handling for calls arrives in Phase 4.)
 */
class ObserveNetworkStatusUseCase @Inject constructor(
    private val networkMonitor: NetworkMonitor
) {
    operator fun invoke(): Flow<NetworkStatus> = networkMonitor.networkStatus
}
