package com.pvoptimizer.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pvoptimizer.app.data.Consumer
import com.pvoptimizer.app.ui.theme.*

@Composable
fun ConsumerCard(
    consumer: Consumer,
    plugIndex: Int = -1,
    totalPlugs: Int = 0,
    onToggle: (Boolean) -> Unit,
    onOverrideChange: (Boolean) -> Unit,
    onPowerChange: ((Double) -> Unit)? = null,
    onEnabledChange: ((Boolean) -> Unit)? = null,
    onMovePriority: ((Int) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val isHeater = consumer.type == "WaterHeater"
    val accent = if (isHeater) BatteryCyan else SolarGold
    val statusColor = if (consumer.isOn) GridFeedIn else TextSecondary
    val isEnabled = consumer.isEnabled

    val isFirstPlug = plugIndex == 0
    val isLastPlug = plugIndex >= 0 && plugIndex == totalPlugs - 1

    var showEditPowerDialog by remember { mutableStateOf(false) }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .alpha(if (!isHeater && !isEnabled) 0.6f else 1.0f),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = CardBg),
        border = BorderStroke(1.dp, if (consumer.isOn) accent.copy(alpha = 0.5f) else CardBorder)
    ) {
        Column(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth()
        ) {
            // Header: Left (Icon + Name + Priority) | Right (Editable Power Badge)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    // Consumer Icon
                    Box(
                        modifier = Modifier
                            .size(38.dp)
                            .clip(CircleShape)
                            .background(accent.copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = if (isHeater) Icons.Default.WaterDrop else Icons.Default.Bolt,
                            contentDescription = null,
                            tint = accent,
                            modifier = Modifier.size(22.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(12.dp))

                    Column {
                        Text(
                            text = consumer.name,
                            color = TextPrimary,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (isHeater) {
                                Surface(
                                    color = BatteryCyan.copy(alpha = 0.15f),
                                    shape = RoundedCornerShape(6.dp)
                                ) {
                                    Text(
                                        text = "★ Höchste Prio (Fest: Relais)",
                                        color = BatteryCyan,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                            } else {
                                Surface(
                                    color = SolarGold.copy(alpha = 0.15f),
                                    shape = RoundedCornerShape(6.dp)
                                ) {
                                    Text(
                                        text = "Priorität #${if (plugIndex >= 0) plugIndex + 1 else consumer.priority}",
                                        color = SolarGold,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                                if (consumer.ip.isNotEmpty()) {
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = consumer.ip,
                                        color = TextSecondary,
                                        fontSize = 11.sp
                                    )
                                }
                            }
                        }
                    }
                }

                // Editable Power consumption badge (always displays configured W, clickable to edit)
                Surface(
                    color = if (consumer.isOn) GridFeedIn.copy(alpha = 0.15f) else BgDark,
                    shape = RoundedCornerShape(8.dp),
                    border = BorderStroke(
                        1.dp,
                        if (consumer.isOn) GridFeedIn.copy(alpha = 0.5f) else CardBorder
                    ),
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(enabled = onPowerChange != null) {
                            showEditPowerDialog = true
                        }
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        Text(
                            text = "%.0f W".format(consumer.powerW),
                            color = if (consumer.isOn) GridFeedIn else TextPrimary,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        if (onPowerChange != null) {
                            Spacer(modifier = Modifier.width(5.dp))
                            Icon(
                                imageVector = Icons.Default.Edit,
                                contentDescription = "Leistung bearbeiten",
                                tint = SolarGold,
                                modifier = Modifier.size(14.dp)
                            )
                        }
                    }
                }
            }

            // Priority reordering buttons (▲ Up and ▼ Down) for Smart Plugs
            if (!isHeater && onMovePriority != null) {
                Spacer(modifier = Modifier.height(10.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "Reihenfolge anpassen:",
                            color = TextSecondary,
                            fontSize = 12.sp
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        IconButton(
                            onClick = { onMovePriority(-1) },
                            enabled = !isFirstPlug,
                            modifier = Modifier.size(30.dp)
                        ) {
                            Icon(
                                Icons.Default.KeyboardArrowUp,
                                contentDescription = "Priorität erhöhen",
                                tint = if (!isFirstPlug) SolarGold else TextSecondary.copy(alpha = 0.3f)
                            )
                        }
                        IconButton(
                            onClick = { onMovePriority(1) },
                            enabled = !isLastPlug,
                            modifier = Modifier.size(30.dp)
                        ) {
                            Icon(
                                Icons.Default.KeyboardArrowDown,
                                contentDescription = "Priorität verringern",
                                tint = if (!isLastPlug) SolarGold else TextSecondary.copy(alpha = 0.3f)
                            )
                        }
                    }

                    // Aktiviert / Deaktiviert Switch
                    if (onEnabledChange != null) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = if (isEnabled) "Aktiviert" else "Deaktiviert",
                                color = if (isEnabled) GridFeedIn else TextSecondary,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.padding(end = 6.dp)
                            )
                            Switch(
                                checked = isEnabled,
                                onCheckedChange = onEnabledChange,
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = TextPrimary,
                                    checkedTrackColor = GridFeedIn,
                                    uncheckedThumbColor = TextSecondary,
                                    uncheckedTrackColor = BgDark
                                ),
                                modifier = Modifier.height(24.dp)
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))
            HorizontalDivider(color = CardBorder, thickness = 1.dp)
            Spacer(modifier = Modifier.height(10.dp))

            // Footer: Mode badge (Auto vs Manual) + On/Off Switch
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                // Auto / Manual toggle chip
                FilterChip(
                    selected = consumer.isManualOverride,
                    onClick = { onOverrideChange(!consumer.isManualOverride) },
                    label = {
                        Text(
                            text = if (consumer.isManualOverride) "✋ Manuell" else "⚡ Automatik (PV)",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium
                        )
                    },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = accent.copy(alpha = 0.2f),
                        selectedLabelColor = accent,
                        containerColor = BgDark,
                        labelColor = TextSecondary
                    ),
                    border = BorderStroke(1.dp, if (consumer.isManualOverride) accent else CardBorder)
                )

                // On/Off Switch
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = if (consumer.isOn) "AN" else "AUS",
                        color = statusColor,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(end = 8.dp)
                    )
                    Switch(
                        checked = consumer.isOn,
                        onCheckedChange = { isChecked ->
                            if (!consumer.isManualOverride) {
                                onOverrideChange(true)
                            }
                            onToggle(isChecked)
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = TextPrimary,
                            checkedTrackColor = GridFeedIn,
                            uncheckedThumbColor = TextSecondary,
                            uncheckedTrackColor = BgDark
                        )
                    )
                }
            }
        }
    }

    if (showEditPowerDialog && onPowerChange != null) {
        var powerText by remember(consumer.powerW) {
            mutableStateOf("%.0f".format(consumer.powerW))
        }

        AlertDialog(
            onDismissRequest = { showEditPowerDialog = false },
            containerColor = CardBg,
            shape = RoundedCornerShape(20.dp),
            title = {
                Text(
                    text = "⚡ Leistung anpassen",
                    color = TextPrimary,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = "Nennleistung für „${consumer.name}“ in Watt (W) festlegen:",
                        color = TextSecondary,
                        fontSize = 13.sp
                    )
                    Spacer(modifier = Modifier.height(14.dp))
                    OutlinedTextField(
                        value = powerText,
                        onValueChange = { powerText = it.filter { ch -> ch.isDigit() || ch == '.' || ch == ',' } },
                        label = { Text("Leistung (W)") },
                        suffix = { Text("W", color = TextSecondary) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary,
                            focusedBorderColor = SolarGold,
                            unfocusedBorderColor = CardBorder,
                            focusedLabelColor = SolarGold,
                            unfocusedLabelColor = TextSecondary
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val parsed = powerText.replace(',', '.').toDoubleOrNull()
                        if (parsed != null && parsed >= 0.0) {
                            onPowerChange(parsed)
                            showEditPowerDialog = false
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = SolarGold,
                        contentColor = BgDark
                    )
                ) {
                    Text("Speichern", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showEditPowerDialog = false }) {
                    Text("Abbrechen", color = TextSecondary)
                }
            }
        )
    }
}
