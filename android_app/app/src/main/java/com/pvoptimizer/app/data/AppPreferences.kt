package com.pvoptimizer.app.data

import android.content.Context
import android.content.SharedPreferences

class AppPreferences(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("pv_optimizer_prefs", Context.MODE_PRIVATE)

    var serverUrl: String
        get() = prefs.getString("server_url", "http://192.168.178.1:8000") ?: ""
        set(value) = prefs.edit().putString("server_url", value.trim().trimEnd('/')).apply()

    var localServerUrl: String
        get() = prefs.getString("local_server_url", "") ?: ""
        set(value) = prefs.edit().putString("local_server_url", value.trim().trimEnd('/')).apply()

    var password: String
        get() = prefs.getString("password", "") ?: ""
        set(value) = prefs.edit().putString("password", value).apply()

    var sessionCookie: String
        get() = prefs.getString("session_cookie", "") ?: ""
        set(value) = prefs.edit().putString("session_cookie", value).apply()

    fun clearSession() {
        prefs.edit().remove("session_cookie").apply()
    }
}
