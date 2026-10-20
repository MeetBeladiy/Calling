package com.drivercall.domain.model

enum class NetworkType {
    WIFI,
    CELLULAR_5G,
    CELLULAR_4G,
    CELLULAR,
    ETHERNET,
    VPN,
    OTHER
}

/** Current connectivity state as seen by the app. */
sealed interface NetworkStatus {
    data class Available(
        val type: NetworkType,
        val validated: Boolean,
        val metered: Boolean,
        val downstreamKbps: Int
    ) : NetworkStatus {
        val label: String
            get() = when (type) {
                NetworkType.WIFI -> "Wi-Fi"
                NetworkType.CELLULAR_5G -> "5G"
                NetworkType.CELLULAR_4G -> "4G"
                NetworkType.CELLULAR -> "Mobile data"
                NetworkType.ETHERNET -> "Ethernet"
                NetworkType.VPN -> "VPN"
                NetworkType.OTHER -> "Internet"
            }
    }
    data object Unavailable : NetworkStatus
}
