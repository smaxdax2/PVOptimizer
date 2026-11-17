package com.pvoptimizer.app.data

import android.annotation.SuppressLint
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
import okhttp3.Response
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

class PvApiClient(private val prefs: AppPreferences) {

    private val gson = Gson()
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    @Volatile
    private var resolvedBaseUrl: String? = null

    // Trust manager that accepts self-signed / local router (FRITZ!Box / Raspberry Pi) certificates
    @SuppressLint("CustomX509TrustManager")
    private val trustAllManager = object : X509TrustManager {
        @SuppressLint("TrustAllX509TrustManager")
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}

        @SuppressLint("TrustAllX509TrustManager")
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}

        override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
    }

    private val sslContext: SSLContext = SSLContext.getInstance("TLS").apply {
        init(null, arrayOf<TrustManager>(trustAllManager), SecureRandom())
    }

    // In-memory + persistent cookie jar for pv_session, configured to accept self-signed certificates
    private val client: OkHttpClient = OkHttpClient.Builder()
        .sslSocketFactory(sslContext.socketFactory, trustAllManager)
        .hostnameVerifier { _, _ -> true }
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.SECONDS)
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

    private fun candidateBaseUrls(): List<String> {
        val raw = prefs.serverUrl.trim().trimEnd('/')
        if (raw.isEmpty()) {
            return listOf("http://192.168.178.1:8000")
        }

        val hostPort = raw.removePrefix("https://").removePrefix("http://").trimEnd('/')
        val hostOnly = hostPort.substringBefore(':')
        val hasPort = hostPort.contains(':')
        val isLocalHost = hostOnly.startsWith("192.168.") ||
            hostOnly.startsWith("10.") ||
            hostOnly.startsWith("172.") ||
            hostOnly.equals("localhost", ignoreCase = true) ||
            hostOnly.endsWith(".local", ignoreCase = true) ||
            hostOnly.endsWith(".fritz.box", ignoreCase = true)

        val candidates = mutableListOf<String>()
        resolvedBaseUrl?.let { candidates.add(it) }

        when {
            raw.startsWith("https://") -> {
                candidates.add("https://$hostPort")
                candidates.add("http://$hostPort")
                if (!hasPort) {
                    candidates.add("http://$hostOnly:8000")
                }
            }
            raw.startsWith("http://") -> {
                if (raw.contains("myfritz.net") && !hasPort) {
                    candidates.add("https://$hostPort")
                    candidates.add("http://$hostPort")
                    candidates.add("http://$hostOnly:8000")
                } else {
                    candidates.add("http://$hostPort")
                    candidates.add("https://$hostPort")
                    if (!hasPort) {
                        candidates.add("http://$hostOnly:8000")
                    }
                }
            }
            else -> {
                if (isLocalHost || hostPort.endsWith(":8000") || hostPort.endsWith(":80")) {
                    candidates.add("http://$hostPort")
                    if (!hasPort) {
                        candidates.add("http://$hostOnly:8000")
                    }
                    candidates.add("https://$hostPort")
                } else {
                    candidates.add("https://$hostPort")
                    candidates.add("http://$hostPort")
                    if (!hasPort) {
                        candidates.add("http://$hostOnly:8000")
                    }
                }
            }
        }

        return candidates.distinct()
    }

    private fun baseUrl(): String {
        return resolvedBaseUrl ?: candidateBaseUrls().first()
    }

    private suspend fun executeWithAuthRetry(buildRequest: (String) -> Request): Response {
        val currentBase = baseUrl()
        val request = buildRequest(currentBase)
        var response = client.newCall(request).execute()
        if (response.code == 401 || response.code == 403) {
            if (prefs.password.isNotEmpty() && login(prefs.password, currentBase).isSuccess) {
                response.close()
                response = client.newCall(buildRequest(currentBase)).execute()
            }
        }
        return response
    }

    suspend fun getStatus(): Result<InverterStatus> = withContext(Dispatchers.IO) {
        var lastError: Exception? = null

        for (candidate in candidateBaseUrls()) {
            try {
                val request = Request.Builder()
                    .url("$candidate/api/status")
                    .get()
                    .build()

                var response = client.newCall(request).execute()
                if (response.code == 401 || response.code == 403) {
                    if (prefs.password.isNotEmpty() && login(prefs.password, candidate).isSuccess) {
                        response.close()
                        response = client.newCall(request).execute()
                    } else {
                        resolvedBaseUrl = candidate
                        response.close()
                        return@withContext Result.failure(Exception("Login erforderlich"))
                    }
                }

                if (!response.isSuccessful) {
                    val code = response.code
                    response.close()
                    lastError = Exception("Serverfehler: $code")
                    continue
                }

                val body = response.body?.string().orEmpty()
                val trimmed = body.trimStart()
                if (!trimmed.startsWith("{") || !trimmed.contains("pv_power_w")) {
                    lastError = Exception("Ungültige Antwort von $candidate")
                    continue
                }

                val status = gson.fromJson(body, InverterStatus::class.java)
                resolvedBaseUrl = candidate
                return@withContext Result.success(status)
            } catch (e: Exception) {
                lastError = e
            }
        }

        Result.failure(lastError ?: Exception("Verbindungsfehler"))
    }

    suspend fun getConsumers(): Result<List<Consumer>> = withContext(Dispatchers.IO) {
        try {
            val response = executeWithAuthRetry { base ->
                Request.Builder()
                    .url("$base/api/consumers")
                    .get()
                    .build()
            }

            if (!response.isSuccessful) {
                val code = response.code
                response.close()
                return@withContext Result.failure(Exception("Fehler beim Abrufen der Verbraucher: $code"))
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
            val response = executeWithAuthRetry { base ->
                Request.Builder()
                    .url("$base/api/auth/status")
                    .get()
                    .build()
            }

            if (!response.isSuccessful) {
                val code = response.code
                response.close()
                return@withContext Result.failure(Exception("HTTP $code"))
            }

            val body = response.body?.string().orEmpty()
            val auth = gson.fromJson(body, AuthStatus::class.java)
            Result.success(auth)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun login(password: String, targetBaseUrl: String = baseUrl()): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val reqPayload = gson.toJson(LoginRequest(password = password, remember = true))
            val request = Request.Builder()
                .url("$targetBaseUrl/login")
                .post(reqPayload.toRequestBody(jsonMediaType))
                .build()

            val response = client.newCall(request).execute()
            val isSuccess = response.isSuccessful
            val code = response.code
            response.close()
            if (isSuccess) {
                prefs.password = password
                Result.success(true)
            } else {
                Result.failure(Exception("Falsches Passwort ($code)"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun setConsumerOverride(consumerId: String, isManualOverride: Boolean): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val payload = gson.toJson(OverrideRequest(isManualOverride = isManualOverride))
            val response = executeWithAuthRetry { base ->
                Request.Builder()
                    .url("$base/api/consumers/$consumerId/override")
                    .post(payload.toRequestBody(jsonMediaType))
                    .build()
            }
            val isSuccess = response.isSuccessful
            val code = response.code
            response.close()
            if (isSuccess) {
                Result.success(true)
            } else {
                Result.failure(Exception("Fehler: $code"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun toggleConsumer(consumerId: String, isOn: Boolean): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val payload = gson.toJson(ToggleRequest(isOn = isOn))
            val response = executeWithAuthRetry { base ->
                Request.Builder()
                    .url("$base/api/consumers/$consumerId/toggle")
                    .post(payload.toRequestBody(jsonMediaType))
                    .build()
            }
            val isSuccess = response.isSuccessful
            val code = response.code
            response.close()
            if (isSuccess) {
                Result.success(true)
            } else {
                Result.failure(Exception("Fehler: $code"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun setConsumerPower(consumerId: String, powerW: Double): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val configRes = executeWithAuthRetry { base ->
                Request.Builder().url("$base/api/config").get().build()
            }
            if (!configRes.isSuccessful) {
                configRes.close()
                return@withContext Result.failure(Exception("Konfiguration konnte nicht geladen werden"))
            }

            val body = configRes.body?.string().orEmpty()
            val jsonObject = gson.fromJson(body, com.google.gson.JsonObject::class.java)

            if (consumerId == "hw_1") {
                val whObj = jsonObject.getAsJsonObject("water_heater")
                    ?: return@withContext Result.failure(Exception("Warmwasser-Konfiguration nicht gefunden"))
                whObj.addProperty("power_w", powerW)
            } else {
                val plugsArray = jsonObject.getAsJsonArray("plugs")
                    ?: return@withContext Result.failure(Exception("Keine Steckdosen"))
                var found = false
                for (i in 0 until plugsArray.size()) {
                    val obj = plugsArray.get(i).asJsonObject
                    val ip = obj.get("ip")?.asString.orEmpty()
                    val id = "plug_" + ip.replace('.', '_')
                    if (id == consumerId) {
                        obj.addProperty("power_w", powerW)
                        found = true
                    }
                }
                if (!found) return@withContext Result.failure(Exception("Steckdose nicht gefunden"))
            }

            val postRes = executeWithAuthRetry { base ->
                Request.Builder()
                    .url("$base/api/config")
                    .post(jsonObject.toString().toRequestBody(jsonMediaType))
                    .build()
            }
            val ok = postRes.isSuccessful
            postRes.close()
            Result.success(ok)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun movePlugPriority(consumerId: String, direction: Int): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val configRes = executeWithAuthRetry { base ->
                Request.Builder().url("$base/api/config").get().build()
            }
            if (!configRes.isSuccessful) {
                configRes.close()
                return@withContext Result.failure(Exception("Konfiguration konnte nicht geladen werden"))
            }

            val body = configRes.body?.string().orEmpty()
            val jsonObject = gson.fromJson(body, com.google.gson.JsonObject::class.java)
            val plugsArray = jsonObject.getAsJsonArray("plugs") ?: return@withContext Result.failure(Exception("Keine Steckdosen"))

            val plugsList = mutableListOf<com.google.gson.JsonObject>()
            for (i in 0 until plugsArray.size()) {
                plugsList.add(plugsArray.get(i).asJsonObject)
            }

            plugsList.sortBy {
                if (it.has("priority") && !it.get("priority").isJsonNull) it.get("priority").asInt else 100
            }

            val index = plugsList.indexOfFirst {
                val ip = it.get("ip")?.asString.orEmpty()
                val id = "plug_" + ip.replace('.', '_')
                id == consumerId
            }

            if (index == -1) return@withContext Result.failure(Exception("Steckdose nicht gefunden"))
            val targetIdx = index + direction
            if (targetIdx < 0 || targetIdx >= plugsList.size) return@withContext Result.success(true)

            val temp = plugsList[index]
            plugsList[index] = plugsList[targetIdx]
            plugsList[targetIdx] = temp

            val newArray = com.google.gson.JsonArray()
            plugsList.forEachIndexed { idx, obj ->
                obj.addProperty("priority", idx + 1)
                newArray.add(obj)
            }
            jsonObject.add("plugs", newArray)

            val postRes = executeWithAuthRetry { base ->
                Request.Builder()
                    .url("$base/api/config")
                    .post(jsonObject.toString().toRequestBody(jsonMediaType))
                    .build()
            }
            val ok = postRes.isSuccessful
            postRes.close()
            Result.success(ok)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun setPlugEnabled(consumerId: String, isEnabled: Boolean): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val configRes = executeWithAuthRetry { base ->
                Request.Builder().url("$base/api/config").get().build()
            }
            if (!configRes.isSuccessful) {
                configRes.close()
                return@withContext Result.failure(Exception("Konfiguration konnte nicht geladen werden"))
            }

            val body = configRes.body?.string().orEmpty()
            val jsonObject = gson.fromJson(body, com.google.gson.JsonObject::class.java)
            val plugsArray = jsonObject.getAsJsonArray("plugs") ?: return@withContext Result.failure(Exception("Keine Steckdosen"))

            for (i in 0 until plugsArray.size()) {
                val obj = plugsArray.get(i).asJsonObject
                val ip = obj.get("ip")?.asString.orEmpty()
                val id = "plug_" + ip.replace('.', '_')
                if (id == consumerId) {
                    obj.addProperty("is_enabled", isEnabled)
                }
            }

            val postRes = executeWithAuthRetry { base ->
                Request.Builder()
                    .url("$base/api/config")
                    .post(jsonObject.toString().toRequestBody(jsonMediaType))
                    .build()
            }
            val ok = postRes.isSuccessful
            postRes.close()
            Result.success(ok)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun triggerScan(): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val postRes = executeWithAuthRetry { base ->
                Request.Builder()
                    .url("$base/api/scan")
                    .post("{}".toRequestBody(jsonMediaType))
                    .build()
            }
            val ok = postRes.isSuccessful
            postRes.close()
            Result.success(ok)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
