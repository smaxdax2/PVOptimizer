package com.pvoptimizer.app.data

import com.google.gson.annotations.SerializedName

data class InverterStatus(
    @SerializedName("pv_power_w") val pvPowerW: Double = 0.0,
    @SerializedName("grid_power_w") val gridPowerW: Double = 0.0,
    @SerializedName("load_power_w") val loadPowerW: Double = 0.0,
    @SerializedName("battery_percent") val batteryPercent: Double = 0.0,
    @SerializedName("error") val error: String? = null
)

data class Consumer(
    @SerializedName("id") val id: String = "",
    @SerializedName("name") val name: String = "",
    @SerializedName("power_w") val powerW: Double = 0.0,
    @SerializedName("priority") val priority: Int = 100,
    @SerializedName("is_on") val isOn: Boolean = false,
    @SerializedName("is_manual_override") val isManualOverride: Boolean = false,
    @SerializedName("is_online") val isOnline: Boolean = true,
    @SerializedName("is_enabled") val isEnabled: Boolean = true,
    @SerializedName("ip") val ip: String = "",
    @SerializedName("type") val type: String = "GenericConsumer"
)

data class AuthStatus(
    @SerializedName("is_local") val isLocal: Boolean = false,
    @SerializedName("is_authenticated") val isAuthenticated: Boolean = false,
    @SerializedName("auth_enabled") val authEnabled: Boolean = true,
    @SerializedName("client_ip") val clientIp: String = ""
)

data class LoginRequest(
    val password: String,
    val remember: Boolean = true
)

data class OverrideRequest(
    @SerializedName("is_manual_override") val isManualOverride: Boolean
)

data class ToggleRequest(
    @SerializedName("is_on") val isOn: Boolean
)
