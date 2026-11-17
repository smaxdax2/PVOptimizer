package com.pvoptimizer.app.data

import android.annotation.SuppressLint
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.Collections
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
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

    private val cookieJar = object : CookieJar {
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
    }

    // Prefer IPv6 (AAAA) for myfritz.net domains because under DS-Lite the IPv4 (A) record
    // points to the FRITZ!Box / CGNAT (93.104.x.x) instead of the Raspberry Pi.
    private val smartDns = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            val addresses = Dns.SYSTEM.lookup(hostname)
            if (hostname.contains("myfritz.net", ignoreCase = true)) {
                return addresses.sortedBy { if (it is Inet6Address) 0 else 1 }
            }
            return addresses
        }
    }

    // Main HTTP client
    private val client: OkHttpClient = OkHttpClient.Builder()
        .sslSocketFactory(sslContext.socketFactory, trustAllManager)
        .hostnameVerifier { _, _ -> true }
        .dns(smartDns)
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.SECONDS)
        .cookieJar(cookieJar)
        .build()

    // Fast HTTP client for local LAN probing
    private val fastLanClient: OkHttpClient = client.newBuilder()
        .connectTimeout(400, TimeUnit.MILLISECONDS)
        .readTimeout(800, TimeUnit.MILLISECONDS)
        .build()

    private fun getLocalWifiSubnetPrefix(): String? {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return null
            for (intf in Collections.list(interfaces)) {
                if (!intf.isUp || intf.isLoopback) continue
                for (addr in Collections.list(intf.inetAddresses)) {
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        val ip = addr.hostAddress ?: continue
                        if (ip.startsWith("192.168.")) {
                            return ip.substringBeforeLast('.')
                        }
                    }
                }
            }
        } catch (_: Exception) {}
        return null
    }

    private fun normalizeLocalUrl(rawLocal: String): List<String> {
        val clean = rawLocal.trim().trimEnd('/')
        if (clean.isEmpty()) return emptyList()
        val hostPort = clean.removePrefix("http://").removePrefix("https://").trimEnd('/')
        val hostOnly = hostPort.substringBefore(':')
        val hasPort = hostPort.contains(':')
        val list = mutableListOf<String>()
        if (hasPort) {
            list.add("http://$hostPort")
            list.add("https://$hostPort")
        } else {
            list.add("http://$hostOnly:8000")
            list.add("http://$hostOnly")
            list.add("https://$hostOnly")
        }
        return list.distinct()
    }

    private fun candidateBaseUrls(): List<String> {
        val candidates = mutableListOf<String>()
        resolvedBaseUrl?.let { candidates.add(it) }

        // If a local server URL is saved (or auto-discovered), try it first when on Wi-Fi
        if (prefs.localServerUrl.isNotEmpty()) {
            candidates.addAll(normalizeLocalUrl(prefs.localServerUrl))
        }

        val raw = prefs.serverUrl.trim().trimEnd('/')
        if (raw.isNotEmpty()) {
            val hostPort = raw.removePrefix("https://").removePrefix("http://").trimEnd('/')
            val hostOnly = hostPort.substringBefore(':')
            val hasPort = hostPort.contains(':')
            val isLocalHost = hostOnly.startsWith("192.168.") ||
                hostOnly.startsWith("10.") ||
                hostOnly.startsWith("172.") ||
                hostOnly.equals("localhost", ignoreCase = true) ||
                hostOnly.endsWith(".local", ignoreCase = true) ||
                hostOnly.endsWith(".fritz.box", ignoreCase = true)

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
        }

        if (candidates.isEmpty()) {
            candidates.add("http://192.168.178.1:8000")
        }

        return candidates.distinct()
    }

    private fun isLocalCandidate(url: String): Boolean {
        val host = url.removePrefix("http://").removePrefix("https://").substringBefore(':').substringBefore('/')
        return host.startsWith("192.168.") ||
            host.startsWith("10.") ||
            host.startsWith("172.") ||
            host.endsWith(".fritz.box", ignoreCase = true) ||
            host.endsWith(".local", ignoreCase = true)
    }

    /**
     * Automatically discovers the Raspberry Pi running PV Optimizer in the local home Wi-Fi
     * when the MyFRITZ! WAN address cannot be hairpinned by the FRITZ!Box router.
     */
    private suspend fun discoverLocalPiInHomeNetwork(): Pair<String, InverterStatus>? = coroutineScope {
        val subnet = getLocalWifiSubnetPrefix() ?: return@coroutineScope null

        // 1. Try common Raspberry Pi hostnames on the FRITZ!Box LAN first
        val hostnameCandidates = listOf(
            "http://raspberrypi.fritz.box:8000",
            "http://raspberrypi:8000",
            "http://raspberrypi.local:8000",
            "http://pvoptimizer.fritz.box:8000",
            "http://raspberrypi.fritz.box"
        )
        for (hostUrl in hostnameCandidates) {
            try {
                val req = Request.Builder().url("$hostUrl/api/status").get().build()
                fastLanClient.newCall(req).execute().use { res ->
                    if (res.isSuccessful) {
                        val body = res.body?.string().orEmpty().trimStart()
                        if (body.startsWith("{") && body.contains("pv_power_w")) {
                            val status = gson.fromJson(body, InverterStatus::class.java)
                            prefs.localServerUrl = hostUrl
                            resolvedBaseUrl = hostUrl
                            return@coroutineScope Pair(hostUrl, status)
                        }
                    }
                }
            } catch (_: Exception) {}
        }

        // 2. Parallel scan of the local /24 subnet on port 8000
        val foundRef = AtomicReference<Pair<String, InverterStatus>?>(null)
        val semaphore = Semaphore(64)

        val jobs = (1..254).map { lastOctet ->
            async(Dispatchers.IO) {
                if (foundRef.get() != null) return@async
                semaphore.withPermit {
                    if (foundRef.get() != null) return@withPermit
                    val candidateUrl = "http://$subnet.$lastOctet:8000"
                    try {
                        val req = Request.Builder().url("$candidateUrl/api/status").get().build()
                        fastLanClient.newCall(req).execute().use { res ->
                            if (res.isSuccessful) {
                                val body = res.body?.string().orEmpty().trimStart()
                                if (body.startsWith("{") && body.contains("pv_power_w")) {
                                    val status = gson.fromJson(body, InverterStatus::class.java)
                                    foundRef.compareAndSet(null, Pair(candidateUrl, status))
                                }
                            }
                        }
                    } catch (_: Exception) {}
                }
            }
        }
        jobs.awaitAll()

        val discovered = foundRef.get()
        if (discovered != null) {
            prefs.localServerUrl = discovered.first
            resolvedBaseUrl = discovered.first
        }
        discovered
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
        var primaryError: Exception? = null

        for (candidate in candidateBaseUrls()) {
            try {
                val httpClient = if (isLocalCandidate(candidate) && candidate != resolvedBaseUrl) {
                    fastLanClient
                } else {
                    client
                }

                val request = Request.Builder()
                    .url("$candidate/api/status")
                    .get()
                    .build()

                var response = httpClient.newCall(request).execute()
                if (response.code == 401 || response.code == 403) {
                    val errBody = response.body?.string().orEmpty()
                    response.close()
                    // Only treat 401/403 as PV Optimizer if it comes from our FastAPI backend
                    if (errBody.contains("Fernzugriff") || errBody.contains("Unauthorized") || errBody.contains("detail")) {
                        if (prefs.password.isNotEmpty() && login(prefs.password, candidate).isSuccess) {
                            response = httpClient.newCall(request).execute()
                        } else {
                            resolvedBaseUrl = candidate
                            return@withContext Result.failure(Exception("Login erforderlich (Passwort in Einstellungen prüfen)"))
                        }
                    } else {
                        if (primaryError == null) primaryError = Exception("Serverfehler: ${response.code}")
                        continue
                    }
                }

                if (!response.isSuccessful) {
                    val code = response.code
                    response.close()
                    if (primaryError == null) primaryError = Exception("Serverfehler: $code")
                    continue
                }

                val body = response.body?.string().orEmpty()
                response.close()
                val trimmed = body.trimStart()
                if (!trimmed.startsWith("{") || !trimmed.contains("pv_power_w")) {
                    // Hit FRITZ!Box web UI instead of PV Optimizer
                    if (primaryError == null) {
                        primaryError = Exception("MyFRITZ!-Adresse zeigt im Heimnetz auf die FRITZ!Box statt auf den Raspberry Pi")
                    }
                    continue
                }

                val status = gson.fromJson(body, InverterStatus::class.java)
                resolvedBaseUrl = candidate
                return@withContext Result.success(status)
            } catch (e: Exception) {
                if (primaryError == null) {
                    primaryError = e
                }
            }
        }

        // If direct candidates failed and we are on home Wi-Fi (192.168.x.x), auto-discover the Raspberry Pi!
        val discovered = discoverLocalPiInHomeNetwork()
        if (discovered != null) {
            return@withContext Result.success(discovered.second)
        }

        Result.failure(primaryError ?: Exception("Verbindungsfehler"))
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
