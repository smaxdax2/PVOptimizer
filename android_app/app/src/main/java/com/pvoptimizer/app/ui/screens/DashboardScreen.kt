package com.pvoptimizer.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
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
    isScanning: Boolean = false,
    errorMessage: String?,
    currentUrl: String,
    currentPassword: String,
    onRefresh: () -> Unit,
    onSaveSettings: (url: String, password: String) -> Unit,
    onToggleConsumer: (consumerId: String, isOn: Boolean) -> Unit,
    onOverrideConsumer: (consumerId: String, isManual: Boolean) -> Unit,
    onPowerChange: (consumerId: String, powerW: Double) -> Unit,
    onEnabledChange: (consumerId: String, isEnabled: Boolean) -> Unit,
    onMovePriority: (consumerId: String, direction: Int) -> Unit,
    onTriggerScan: () -> Unit
) {
    var showSettings by remember { mutableStateOf(false) }

    val waterHeater = consumers.firstOrNull { it.type == "WaterHeater" || it.id == "hw_1" }
    val plugs = consumers.filter { it.type != "WaterHeater" && it.id != "hw_1" }

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

            // Consumers Header + Search (Scan) Button
            item {
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column {
                        Text(
                            text = "Intelligente Verbraucher",
                            color = TextPrimary,
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "${consumers.size} Geräte (${plugs.count { it.isEnabled }} aktiv)",
                            color = TextSecondary,
                            fontSize = 12.sp
                        )
                    }

                    // Suchen (Scan) Button
                    FilledTonalButton(
                        onClick = onTriggerScan,
                        enabled = !isScanning,
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = CardBorder,
                            contentColor = TextPrimary
                        ),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        if (isScanning) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(14.dp),
                                color = SolarGold,
                                strokeWidth = 2.dp
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Scan…", fontSize = 12.sp)
                        } else {
                            Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Suchen", fontSize = 12.sp)
                        }
                    }
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
                // 1. Water Heater (always pinned to top if present)
                if (waterHeater != null) {
                    item(key = waterHeater.id) {
                        ConsumerCard(
                            consumer = waterHeater,
                            onToggle = { isOn -> onToggleConsumer(waterHeater.id, isOn) },
                            onOverrideChange = { isManual -> onOverrideConsumer(waterHeater.id, isManual) },
                            onPowerChange = { newPower -> onPowerChange(waterHeater.id, newPower) }
                        )
                    }
                }

                // 2. Smart Plugs (ordered by priority, with reordering & enable toggles)
                itemsIndexed(plugs, key = { _, plug -> plug.id }) { index, plug ->
                    ConsumerCard(
                        consumer = plug,
                        plugIndex = index,
                        totalPlugs = plugs.size,
                        onToggle = { isOn -> onToggleConsumer(plug.id, isOn) },
                        onOverrideChange = { isManual -> onOverrideConsumer(plug.id, isManual) },
                        onPowerChange = { newPower -> onPowerChange(plug.id, newPower) },
                        onEnabledChange = { isEnabled -> onEnabledChange(plug.id, isEnabled) },
                        onMovePriority = { direction -> onMovePriority(plug.id, direction) }
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
