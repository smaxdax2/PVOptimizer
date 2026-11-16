package com.pvoptimizer.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pvoptimizer.app.data.Consumer
import com.pvoptimizer.app.ui.theme.*

@Composable
fun ConsumerCard(
    consumer: Consumer,
    onToggle: (Boolean) -> Unit,
    onOverrideChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val isHeater = consumer.type == "WaterHeater"
    val accent = if (isHeater) BatteryCyan else SolarGold
    val statusColor = if (consumer.isOn) GridFeedIn else TextSecondary

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = CardBg),
        border = BorderStroke(1.dp, if (consumer.isOn) accent.copy(alpha = 0.5f) else CardBorder)
    ) {
        Column(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth()
        ) {
            // Header: Name + Priority Badge
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(accent.copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = if (isHeater) Icons.Default.WaterDrop else Icons.Default.Bolt,
                            contentDescription = null,
                            tint = accent,
                            modifier = Modifier.size(20.dp)
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
                        Text(
                            text = if (isHeater) "Priorität 1 (Höchste)" else "Priorität ${consumer.priority}",
                            color = TextSecondary,
                            fontSize = 11.sp
                        )
                    }
                }

                // Power consumption badge
                Surface(
                    color = if (consumer.isOn) GridFeedIn.copy(alpha = 0.15f) else CardBorder.copy(alpha = 0.5f),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        text = if (consumer.isOn) "%.0f W".format(consumer.powerW) else "Aus (0 W)",
                        color = if (consumer.isOn) GridFeedIn else TextSecondary,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))
            HorizontalDivider(color = CardBorder, thickness = 1.dp)
            Spacer(modifier = Modifier.height(12.dp))

            // Footer: Mode badge (Auto vs Manual) + Switch
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
                            // If override is off, enabling manual switch turns override ON first
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
}
