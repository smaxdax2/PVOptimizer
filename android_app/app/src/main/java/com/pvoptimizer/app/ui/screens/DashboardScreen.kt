package com.pvoptimizer.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pvoptimizer.app.data.AuthStatus
import com.pvoptimizer.app.data.Consumer
import com.pvoptimizer.app.data.InverterStatus
import com.pvoptimizer.app.ui.components.ConsumerCard
import com.pvoptimizer.app.ui.components.MetricCard
import com.pvoptimizer.app.ui.components.SettingsDialog
import com.pvoptimizer.app.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    status: InverterStatus,
    consumers: List<Consumer>,
    authStatus: AuthStatus?,
    isConnected: Boolean,
    errorMessage: String?,
    currentUrl: String,
    currentPassword: String,
    onRefresh: () -> Unit,
    onSaveSettings: (url: String, password: String) -> Unit,
    onToggleConsumer: (consumerId: String, isOn: Boolean) -> Unit,
    onOverrideConsumer: (consumerId: String, isManual: Boolean) -> Unit
) {
    var showSettings by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "☀️ PV Optimizer",
                            color = SolarGold,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                },
                actions = {
                    // Status Pill
                    Surface(
                        color = when {
                            !isConnected -> GridImport.copy(alpha = 0.2f)
                            authStatus?.isLocal == true -> GridFeedIn.copy(alpha = 0.2f)
                            else -> AccentBlue.copy(alpha = 0.2f)
                        },
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.padding(end = 6.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Text(
                                text = when {
                                    !isConnected -> "● Offline"
                                    authStatus?.isLocal == true -> "🏠 Heimnetz"
                                    else -> "🌐 Fernzugriff"
                                },
                                color = when {
                                    !isConnected -> GridImport
                                    authStatus?.isLocal == true -> GridFeedIn
                                    else -> AccentBlue
                                },
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }

                    IconButton(onClick = onRefresh) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Aktualisieren",
                            tint = TextPrimary
                        )
                    }

                    IconButton(onClick = { showSettings = true }) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = "Einstellungen",
                            tint = TextPrimary
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = BgDark)
            )
        },
        containerColor = BgDark
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Error banner if any
            if (errorMessage != null && !isConnected) {
                item {
                    Surface(
                        color = GridImport.copy(alpha = 0.15f),
                        shape = RoundedCornerShape(12.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, GridImport.copy(alpha = 0.4f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(14.dp)
                        ) {
                            Icon(Icons.Default.ErrorOutline, contentDescription = null, tint = GridImport)
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = errorMessage,
                                color = TextPrimary,
                                fontSize = 13.sp
                            )
                        }
                    }
                }
            }

            // 2x2 Metric Tiles
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    MetricCard(
                        title = "Photovoltaik",
                        valueText = "%.0f W".format(status.pvPowerW),
                        subText = if (status.pvPowerW > 50) "Erzeugung aktiv" else "Keine Sonneneinstrahlung",
                        icon = Icons.Default.WbSunny,
                        accentColor = SolarGold,
                        modifier = Modifier.weight(1f)
                    )

                    val isExporting = status.gridPowerW < 0
                    MetricCard(
                        title = if (isExporting) "Netzeinspeisung" else "Netzbezug",
                        valueText = "%.0f W".format(kotlin.math.abs(status.gridPowerW)),
                        subText = if (isExporting) "Überschuss wird vergütet" else "Aus dem Netz bezogen",
                        icon = Icons.Default.ElectricBolt,
                        accentColor = if (isExporting) GridFeedIn else GridImport,
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    MetricCard(
                        title = "Batterie-Speicher",
                        valueText = "%.0f %%".format(status.batteryPercent),
                        subText = "Kapazität",
                        icon = Icons.Default.BatteryChargingFull,
                        accentColor = BatteryCyan,
                        modifier = Modifier.weight(1f)
                    )

                    MetricCard(
                        title = "Hausverbrauch",
                        valueText = "%.0f W".format(status.loadPowerW),
                        subText = "Aktuelle Gesamtlast",
                        icon = Icons.Default.Home,
                        accentColor = HomePurple,
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            // Consumers Header
            item {
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "Intelligente Verbraucher",
                        color = TextPrimary,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "${consumers.size} Geräte",
                        color = TextSecondary,
                        fontSize = 13.sp
                    )
                }
            }

            // Consumers List
            if (consumers.isEmpty()) {
                item {
                    Surface(
                        color = CardBg,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp)
                    ) {
                        Text(
                            text = "Keine Verbraucher konfiguriert oder Suche läuft…",
                            color = TextSecondary,
                            fontSize = 14.sp,
                            modifier = Modifier.padding(16.dp)
                        )
                    }
                }
            } else {
                items(consumers, key = { it.id }) { consumer ->
                    ConsumerCard(
                        consumer = consumer,
                        onToggle = { isOn -> onToggleConsumer(consumer.id, isOn) },
                        onOverrideChange = { isManual -> onOverrideConsumer(consumer.id, isManual) }
                    )
                }
            }

            item {
                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }

    if (showSettings) {
        SettingsDialog(
            initialUrl = currentUrl,
            initialPassword = currentPassword,
            onDismiss = { showSettings = false },
            onSave = { url, pw ->
                onSaveSettings(url, pw)
                showSettings = false
            }
        )
    }
}
