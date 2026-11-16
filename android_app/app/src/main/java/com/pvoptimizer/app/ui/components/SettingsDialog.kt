package com.pvoptimizer.app.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pvoptimizer.app.ui.theme.*

@Composable
fun SettingsDialog(
    initialUrl: String,
    initialPassword: String,
    onDismiss: () -> Unit,
    onSave: (url: String, password: String) -> Unit
) {
    var urlText by remember { mutableStateOf(initialUrl) }
    var passwordText by remember { mutableStateOf(initialPassword) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = CardBg,
        shape = RoundedCornerShape(20.dp),
        title = {
            Text(
                text = "⚙️ Verbindungseinstellungen",
                color = TextPrimary,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "Geben Sie Ihre MyFRITZ!-Adresse oder die lokale Raspberry Pi IP-Adresse ein:",
                    color = TextSecondary,
                    fontSize = 13.sp
                )

                Spacer(modifier = Modifier.height(16.dp))

                OutlinedTextField(
                    value = urlText,
                    onValueChange = { urlText = it },
                    label = { Text("Server-URL") },
                    placeholder = { Text("https://dein-pi.myfritz.net") },
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

                Spacer(modifier = Modifier.height(12.dp))

                OutlinedTextField(
                    value = passwordText,
                    onValueChange = { passwordText = it },
                    label = { Text("Passwort (für Fernzugriff)") },
                    placeholder = { Text("Standard: admin") },
                    visualTransformation = PasswordVisualTransformation(),
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

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "💡 Im Heimnetz (WLAN) ist kein Passwort erforderlich. Von unterwegs schützt dieses Passwort Ihren Zugang.",
                    color = TextSecondary,
                    fontSize = 11.sp
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(urlText, passwordText) },
                colors = ButtonDefaults.buttonColors(containerColor = SolarGold, contentColor = BgDark)
            ) {
                Text("Speichern", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Abbrechen", color = TextSecondary)
            }
        }
    )
}
