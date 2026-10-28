package com.drivercall.data.remote

import com.google.gson.annotations.SerializedName
import retrofit2.http.GET

data class IceServerDto(
    @SerializedName("urls") val urls: List<String>,
    @SerializedName("username") val username: String? = null,
    @SerializedName("credential") val credential: String? = null
)

data class IceConfigResponse(
    @SerializedName("iceServers") val iceServers: List<IceServerDto>,
    @SerializedName("ttlSeconds") val ttlSeconds: Long = 3_600,
    /**
     * Non-secret backend readiness hint. The RTC layer still validates the
     * returned URLs and credentials rather than trusting this flag alone.
     */
    @SerializedName("relayReady") val relayReady: Boolean = false
)

interface ConfigApi {
    @GET("api/config/ice")
    suspend fun getIceConfig(): IceConfigResponse
}
