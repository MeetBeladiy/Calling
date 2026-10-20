package com.drivercall.data.security

import com.drivercall.BuildConfig
import com.google.gson.JsonParser
import okhttp3.Authenticator
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.Route
import javax.inject.Inject
import javax.inject.Singleton

/** Rotates the short-lived access token once, without ever logging either token. */
@Singleton
class SessionAuthenticator @Inject constructor(
    private val tokenStorage: TokenStorage
) : Authenticator {
    private val lock = Any()

    override fun authenticate(route: Route?, response: Response): Request? {
        if (responseCount(response) >= 2) return null
        val accessUsed = response.request.header("Authorization")
            ?.removePrefix("Bearer ")

        synchronized(lock) {
            val newestAccess = tokenStorage.getToken()
            if (!newestAccess.isNullOrBlank() && newestAccess != accessUsed) {
                return response.request.newBuilder()
                    .header("Authorization", "Bearer $newestAccess")
                    .build()
            }
            val refresh = tokenStorage.getRefreshToken() ?: return null
            val body = "{\"refreshToken\":${quoteJson(refresh)}}"
                .toRequestBody("application/json".toMediaType())
            val refreshRequest = Request.Builder()
                .url(BuildConfig.BASE_URL.trimEnd('/') + "/api/auth/refresh")
                .post(body)
                .build()
            val refreshResponse = runCatching {
                OkHttpClient.Builder().build().newCall(refreshRequest).execute()
            }.getOrNull() ?: return null
            refreshResponse.use { result ->
                if (!result.isSuccessful) {
                    tokenStorage.clear()
                    return null
                }
                val json = runCatching {
                    JsonParser.parseString(result.body?.string()).asJsonObject
                }.getOrNull() ?: return null
                val tokens = json.getAsJsonObject("tokens") ?: return null
                val accessToken = tokens.get("accessToken")?.asString ?: return null
                val refreshToken = tokens.get("refreshToken")?.asString ?: return null
                tokenStorage.saveTokens(accessToken, refreshToken)
                return response.request.newBuilder()
                    .header("Authorization", "Bearer $accessToken")
                    .build()
            }
        }
    }

    private fun responseCount(response: Response): Int {
        var current: Response? = response
        var count = 1
        while (current?.priorResponse != null) {
            count++
            current = current.priorResponse
        }
        return count
    }

    private fun quoteJson(value: String): String = buildString(value.length + 2) {
        append('"')
        value.forEach { character ->
            when (character) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(character)
            }
        }
        append('"')
    }
}
