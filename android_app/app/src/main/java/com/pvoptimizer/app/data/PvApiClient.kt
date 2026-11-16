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
        // If user entered http:// with a myfritz domain, upgrade to https:// because MyFRITZ uses port 443
        if (url.startsWith("http://") && url.contains("myfritz.net") && !url.substringAfter("http://").contains(":")) {
            url = "https://" + url.removePrefix("http://")
        } else if (!url.startsWith("http://") && !url.startsWith("https://")) {
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

            var response = client.newCall(request).execute()
            if (response.code == 401 || response.code == 403) {
                if (prefs.password.isNotEmpty() && login(prefs.password).isSuccess) {
                    response = client.newCall(request).execute()
                }
            }

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

            var response = client.newCall(request).execute()
            if (response.code == 401 || response.code == 403) {
                if (prefs.password.isNotEmpty() && login(prefs.password).isSuccess) {
                    response = client.newCall(request).execute()
                }
            }

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

    suspend fun movePlugPriority(consumerId: String, direction: Int): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val configReq = Request.Builder().url("${baseUrl()}/api/config").get().build()
            var configRes = client.newCall(configReq).execute()
            if (configRes.code == 401 || configRes.code == 403) {
                if (prefs.password.isNotEmpty() && login(prefs.password).isSuccess) {
                    configRes = client.newCall(configReq).execute()
                }
            }
            if (!configRes.isSuccessful) return@withContext Result.failure(Exception("Konfiguration konnte nicht geladen werden"))

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

            val postReq = Request.Builder()
                .url("${baseUrl()}/api/config")
                .post(jsonObject.toString().toRequestBody(jsonMediaType))
                .build()

            val postRes = client.newCall(postReq).execute()
            Result.success(postRes.isSuccessful)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun setPlugEnabled(consumerId: String, isEnabled: Boolean): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val configReq = Request.Builder().url("${baseUrl()}/api/config").get().build()
            var configRes = client.newCall(configReq).execute()
            if (configRes.code == 401 || configRes.code == 403) {
                if (prefs.password.isNotEmpty() && login(prefs.password).isSuccess) {
                    configRes = client.newCall(configReq).execute()
                }
            }
            if (!configRes.isSuccessful) return@withContext Result.failure(Exception("Konfiguration konnte nicht geladen werden"))

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

            val postReq = Request.Builder()
                .url("${baseUrl()}/api/config")
                .post(jsonObject.toString().toRequestBody(jsonMediaType))
                .build()

            val postRes = client.newCall(postReq).execute()
            Result.success(postRes.isSuccessful)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun triggerScan(): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val postReq = Request.Builder()
                .url("${baseUrl()}/api/scan")
                .post("{}".toRequestBody(jsonMediaType))
                .build()

            var postRes = client.newCall(postReq).execute()
            if (postRes.code == 401 || postRes.code == 403) {
                if (prefs.password.isNotEmpty() && login(prefs.password).isSuccess) {
                    postRes = client.newCall(postReq).execute()
                }
            }
            Result.success(postRes.isSuccessful)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
