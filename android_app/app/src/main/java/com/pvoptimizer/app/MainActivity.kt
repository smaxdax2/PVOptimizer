package com.pvoptimizer.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.lifecycle.lifecycleScope
import com.pvoptimizer.app.data.AuthStatus
import com.pvoptimizer.app.data.Consumer
import com.pvoptimizer.app.data.InverterStatus
import com.pvoptimizer.app.data.PvApiClient
import com.pvoptimizer.app.data.AppPreferences
import com.pvoptimizer.app.ui.screens.DashboardScreen
import com.pvoptimizer.app.ui.theme.PVOptimizerTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private lateinit var prefs: AppPreferences
    private lateinit var client: PvApiClient

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = AppPreferences(this)
        client = PvApiClient(prefs)

        setContent {
            PVOptimizerTheme {
                var status by remember { mutableStateOf(InverterStatus()) }
                var consumers by remember { mutableStateOf<List<Consumer>>(emptyList()) }
                var authStatus by remember { mutableStateOf<AuthStatus?>(null) }
                var isConnected by remember { mutableStateOf(false) }
                var errorMessage by remember { mutableStateOf<String?>(null) }

                fun refreshData() {
                    lifecycleScope.launch {
                        // 1. Fetch Inverter Status
                        val statusRes = client.getStatus()
                        if (statusRes.isSuccess) {
                            status = statusRes.getOrNull() ?: InverterStatus()
                            isConnected = true
                            errorMessage = null
                        } else {
                            isConnected = false
                            errorMessage = statusRes.exceptionOrNull()?.localizedMessage ?: "Verbindungsfehler"
                        }

                        // 2. Fetch Consumers
                        val consumersRes = client.getConsumers()
                        if (consumersRes.isSuccess) {
                            consumers = consumersRes.getOrNull() ?: emptyList()
                        }

                        // 3. Fetch Auth Status
                        val authRes = client.getAuthStatus()
                        if (authRes.isSuccess) {
                            authStatus = authRes.getOrNull()
                        }

                        // Trigger widget update
                        sendBroadcast(Intent("com.pvoptimizer.app.ACTION_REFRESH_WIDGET"))
                    }
                }

                // Initial load & Polling loop (every 3 seconds)
                LaunchedEffect(Unit) {
                    while (isActive) {
                        refreshData()
                        delay(3000)
                    }
                }

                DashboardScreen(
                    status = status,
                    consumers = consumers,
                    authStatus = authStatus,
                    isConnected = isConnected,
                    errorMessage = errorMessage,
                    currentUrl = prefs.serverUrl,
                    currentPassword = prefs.password,
                    onRefresh = { refreshData() },
                    onSaveSettings = { newUrl, newPassword ->
                        prefs.serverUrl = newUrl
                        prefs.password = newPassword
                        prefs.clearSession()
                        client = PvApiClient(prefs)
                        refreshData()
                    },
                    onToggleConsumer = { consumerId, isOn ->
                        lifecycleScope.launch {
                            client.toggleConsumer(consumerId, isOn)
                            refreshData()
                        }
                    },
                    onOverrideConsumer = { consumerId, isManual ->
                        lifecycleScope.launch {
                            client.setConsumerOverride(consumerId, isManual)
                            refreshData()
                        }
                    }
                )
            }
        }
    }
}
