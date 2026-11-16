package com.pvoptimizer.app.data

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

class PvApiClient(private val prefs: AppPreferences) {

    private val gson = Gson()
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    // In-memory + persistent cookie jar for pv_session
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .cookieJar(object : CookieJar {
            override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
                for (cookie in cookies) {
                    if (cookie.name == "pv_session") {
                        prefs.sessionCookie = cookie.value
                    }
                }
            }

            override fun loadForRequest(url: HttpUrl): List<Cookie> {
                val token = prefs.sessionCookie
                if (token.isNotEmpty()) {
                    return listOf(
                        Cookie.Builder()
                            .name("pv_session")
                            .value(token)
                            .domain(url.host)
                            .path("/")
                            .build()
                    )
                }
                return emptyList()
            }
        })
        .build()

    private fun baseUrl(): String {
        var url = prefs.serverUrl.trim().trimEnd('/')
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            url = "https://$url"
        }
        return url
    }

    suspend fun getStatus(): Result<InverterStatus> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("${baseUrl()}/api/status")
                .get()
                .build()

            val response = client.newCall(request).execute()
            if (response.code == 401 || response.code == 403) {
                // Try auto-login if password is saved
                if (prefs.password.isNotEmpty() && login(prefs.password).isSuccess) {
                    val retry = client.newCall(request).execute()
                    val body = retry.body?.string().orEmpty()
                    return@withContext Result.success(gson.fromJson(body, InverterStatus::class.java))
                }
                return@withContext Result.failure(Exception("Login erforderlich"))
            }

            if (!response.isSuccessful) {
                return@withContext Result.failure(Exception("Serverfehler: ${response.code}"))
            }

            val body = response.body?.string().orEmpty()
            val status = gson.fromJson(body, InverterStatus::class.java)
            Result.success(status)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getConsumers(): Result<List<Consumer>> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("${baseUrl()}/api/consumers")
                .get()
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                return@withContext Result.failure(Exception("Fehler beim Abrufen der Verbraucher: ${response.code}"))
            }

            val body = response.body?.string().orEmpty()
            val type = object : TypeToken<List<Consumer>>() {}.type
            val consumers: List<Consumer> = gson.fromJson(body, type)
            // Sort: Priority 0 (Water Heater) first, then priority ascending
            Result.success(consumers.sortedBy { it.priority })
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getAuthStatus(): Result<AuthStatus> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("${baseUrl()}/api/auth/status")
                .get()
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                return@withContext Result.failure(Exception("HTTP ${response.code}"))
            }

            val body = response.body?.string().orEmpty()
            val auth = gson.fromJson(body, AuthStatus::class.java)
            Result.success(auth)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun login(password: String): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val reqPayload = gson.toJson(LoginRequest(password = password, remember = true))
            val request = Request.Builder()
                .url("${baseUrl()}/login")
                .post(reqPayload.toRequestBody(jsonMediaType))
                .build()

            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                prefs.password = password
                Result.success(true)
            } else {
                Result.failure(Exception("Falsches Passwort (${response.code})"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun setConsumerOverride(consumerId: String, isManualOverride: Boolean): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val payload = gson.toJson(OverrideRequest(isManualOverride = isManualOverride))
            val request = Request.Builder()
                .url("${baseUrl()}/api/consumers/$consumerId/override")
                .post(payload.toRequestBody(jsonMediaType))
                .build()

            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                Result.success(true)
            } else {
                Result.failure(Exception("Fehler: ${response.code}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun toggleConsumer(consumerId: String, isOn: Boolean): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val payload = gson.toJson(ToggleRequest(isOn = isOn))
            val request = Request.Builder()
                .url("${baseUrl()}/api/consumers/$consumerId/toggle")
                .post(payload.toRequestBody(jsonMediaType))
                .build()

            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                Result.success(true)
            } else {
                Result.failure(Exception("Fehler: ${response.code}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
