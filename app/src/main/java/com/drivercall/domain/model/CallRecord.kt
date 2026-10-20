package com.drivercall.domain.model

enum class CallType {
    /** DriverCall VoIP call (WebRTC). */
    VOIP,
    /** Normal cellular customer call placed through the phone network. */
    CELLULAR
}

data class CallRecord(
    val id: String,
    val peerName: String,
    val peerId: String?,
    val startTimeMillis: Long,
    val durationSeconds: Long,
    val type: CallType,
    val reconnectionCount: Int,
    /** Short human summary, e.g. "Good", "2 reconnects". Null when unknown. */
    val qualitySummary: String?
)
