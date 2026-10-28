package com.drivercall.data.call

import com.drivercall.domain.model.NetworkStatus
import com.drivercall.domain.model.NetworkType
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkTransitionPolicyTest {
    private fun available(type: NetworkType) = NetworkStatus.Available(
        type = type,
        validated = true,
        metered = type != NetworkType.WIFI,
        downstreamKbps = 10_000
    )

    @Test
    fun `4G and 5G labels are one cellular transport family`() {
        assertFalse(
            shouldRestartIceForTransportChange(
                available(NetworkType.CELLULAR_4G),
                available(NetworkType.CELLULAR_5G)
            )
        )
        assertFalse(
            shouldRestartIceForTransportChange(
                available(NetworkType.CELLULAR),
                available(NetworkType.CELLULAR_4G)
            )
        )
    }

    @Test
    fun `Wi-Fi to mobile data is a real transport handover`() {
        assertTrue(
            shouldRestartIceForTransportChange(
                available(NetworkType.WIFI),
                available(NetworkType.CELLULAR_5G)
            )
        )
    }

    @Test
    fun `temporary unavailable state is not itself a transport handover`() {
        assertFalse(
            shouldRestartIceForTransportChange(
                NetworkStatus.Unavailable,
                available(NetworkType.CELLULAR_4G)
            )
        )
    }
}
