package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalance
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.RiskSettings
import com.example.data.model.formatInr
import com.example.ui.theme.CardBorder
import com.example.ui.theme.CardDark
import com.example.ui.theme.CardDarkElevated
import com.example.ui.theme.QuantCyan
import com.example.ui.theme.QuantCyanDim
import com.example.ui.theme.QuantGold
import com.example.ui.theme.QuantGreen
import com.example.ui.theme.QuantGreenDim
import com.example.ui.theme.QuantRed
import com.example.ui.theme.QuantRedDim
import com.example.ui.theme.TerminalBg
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextSubtle
import com.example.ui.theme.TextWhite
import com.example.ui.viewmodel.TradingViewModel

@Composable
fun RiskSettingsScreen(viewModel: TradingViewModel) {
    val riskSettings by viewModel.riskSettings.collectAsState()
    val selectedBroker by viewModel.selectedBroker.collectAsState()

    var riskPerTrade by remember(riskSettings) { mutableFloatStateOf(riskSettings.maxRiskPerTradePercent.toFloat()) }
    var maxDrawdown by remember(riskSettings) { mutableFloatStateOf(riskSettings.maxDailyDrawdownPercent.toFloat()) }
    var maxPositions by remember(riskSettings) { mutableFloatStateOf(riskSettings.maxOpenPositions.toFloat()) }
    var maxExposure by remember(riskSettings) { mutableFloatStateOf(riskSettings.maxPortfolioExposurePercent.toFloat()) }
    var consecutiveLosses by remember(riskSettings) { mutableFloatStateOf(riskSettings.maxConsecutiveLosses.toFloat()) }
    var stopLossAtr by remember(riskSettings) { mutableFloatStateOf(riskSettings.stopLossAtrMultiplier.toFloat()) }
    var takeProfitAtr by remember(riskSettings) { mutableFloatStateOf(riskSettings.takeProfitAtrMultiplier.toFloat()) }
    var trailingStopEnabled by remember(riskSettings) { mutableStateOf(riskSettings.trailingStopEnabled) }

    var brokerChoice by remember(selectedBroker) { mutableStateOf(selectedBroker) }
    var upstoxToken by remember { mutableStateOf("") }
    var isUpstoxSandbox by remember { mutableStateOf(true) }
    var showSavedMessage by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(TerminalBg)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Top Header
        Column {
            Text(
                text = "Capital, Risk & Upstox Integration",
                color = TextWhite,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "Strict Capital Preservation & Broker Connectivity",
                color = TextMuted,
                fontSize = 12.sp
            )
        }

        // Emergency Kill Switch Banner
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = if (riskSettings.emergencyHalt) QuantRedDim else CardDark
            ),
            shape = RoundedCornerShape(14.dp),
            border = androidx.compose.foundation.BorderStroke(
                1.dp,
                if (riskSettings.emergencyHalt) QuantRed else CardBorder
            )
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.Security,
                            contentDescription = "Security",
                            tint = if (riskSettings.emergencyHalt) QuantRed else QuantGreen,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (riskSettings.emergencyHalt) "EMERGENCY HALT ACTIVE" else "Kill Switch Armed",
                            color = TextWhite,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    if (riskSettings.emergencyHalt) {
                        Button(
                            onClick = { viewModel.resetKillSwitch() },
                            colors = ButtonDefaults.buttonColors(containerColor = QuantGold),
                            shape = RoundedCornerShape(6.dp)
                        ) {
                            Text("Disarm", color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                        }
                    } else {
                        Button(
                            onClick = { viewModel.triggerEmergencyKillSwitch() },
                            colors = ButtonDefaults.buttonColors(containerColor = QuantRed),
                            shape = RoundedCornerShape(6.dp),
                            modifier = Modifier.testTag("kill_switch_trigger_button")
                        ) {
                            Text("Trigger Kill Switch", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                        }
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = if (riskSettings.emergencyHalt)
                        "All autonomous order routing is locked and open positions were liquidated."
                    else
                        "Instantly halts agent, cancels pending orders, and initiates controlled position square-off.",
                    color = TextMuted,
                    fontSize = 11.sp
                )
            }
        }

        // Risk Controls Form Card
        Text(
            text = "AUTONOMOUS RISK PARAMETERS",
            color = TextSubtle,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.8.sp
        )

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = CardDark),
            shape = RoundedCornerShape(14.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, CardBorder)
        ) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                // Max Risk Per Trade
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Risk Per Trade (Default 0.5%)", color = TextWhite, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                        Text("${String.format("%.2f", riskPerTrade)}% of Capital", color = QuantCyan, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    }
                    Slider(
                        value = riskPerTrade,
                        onValueChange = { riskPerTrade = it },
                        valueRange = 0.1f..2.5f,
                        steps = 23,
                        colors = SliderDefaults.colors(thumbColor = QuantCyan, activeTrackColor = QuantCyan)
                    )
                    Text("Strictly caps maximum monetary risk per individual trade.", color = TextSubtle, fontSize = 10.sp)
                }

                // Daily Drawdown Limit
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Max Daily Drawdown Circuit Breaker", color = TextWhite, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                        Text("${String.format("%.1f", maxDrawdown)}%", color = QuantRed, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    }
                    Slider(
                        value = maxDrawdown,
                        onValueChange = { maxDrawdown = it },
                        valueRange = 1.0f..5.0f,
                        steps = 7,
                        colors = SliderDefaults.colors(thumbColor = QuantRed, activeTrackColor = QuantRed)
                    )
                    Text("Auto-halts all trading for the session if day drawdown breaches this limit.", color = TextSubtle, fontSize = 10.sp)
                }

                // Max Open Positions & Max Exposure
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Max Concurrent Positions", color = TextWhite, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                        Text("${maxPositions.toInt()} Stocks", color = QuantGold, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    }
                    Slider(
                        value = maxPositions,
                        onValueChange = { maxPositions = it },
                        valueRange = 1f..6f,
                        steps = 4,
                        colors = SliderDefaults.colors(thumbColor = QuantGold, activeTrackColor = QuantGold)
                    )
                }

                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Max Portfolio Capital Exposure", color = TextWhite, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                        Text("${maxExposure.toInt()}% of Capital", color = QuantCyan, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    }
                    Slider(
                        value = maxExposure,
                        onValueChange = { maxExposure = it },
                        valueRange = 20f..80f,
                        steps = 5,
                        colors = SliderDefaults.colors(thumbColor = QuantCyan, activeTrackColor = QuantCyan)
                    )
                }

                // Consecutive Loss Cooldown
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Consecutive Loss Protection", color = TextWhite, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                        Text("${consecutiveLosses.toInt()} Losses", color = QuantRed, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    }
                    Slider(
                        value = consecutiveLosses,
                        onValueChange = { consecutiveLosses = it },
                        valueRange = 2f..5f,
                        steps = 2,
                        colors = SliderDefaults.colors(thumbColor = QuantRed, activeTrackColor = QuantRed)
                    )
                    Text("Forces a 15-minute trading cooldown if consecutive losses occur.", color = TextSubtle, fontSize = 10.sp)
                }

                // Trailing Stop Toggle
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("Dynamic ATR Trailing Stop Loss", color = TextWhite, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                        Text("Trails stop price upward as stock rises.", color = TextSubtle, fontSize = 11.sp)
                    }
                    Switch(
                        checked = trailingStopEnabled,
                        onCheckedChange = { trailingStopEnabled = it },
                        colors = SwitchDefaults.colors(checkedThumbColor = QuantGreen, checkedTrackColor = QuantGreenDim)
                    )
                }

                // Save Risk Settings Button
                Button(
                    onClick = {
                        val newSettings = riskSettings.copy(
                            maxRiskPerTradePercent = riskPerTrade.toDouble(),
                            maxDailyDrawdownPercent = maxDrawdown.toDouble(),
                            maxOpenPositions = maxPositions.toInt(),
                            maxPortfolioExposurePercent = maxExposure.toDouble(),
                            maxConsecutiveLosses = consecutiveLosses.toInt(),
                            stopLossAtrMultiplier = stopLossAtr.toDouble(),
                            takeProfitAtrMultiplier = takeProfitAtr.toDouble(),
                            trailingStopEnabled = trailingStopEnabled
                        )
                        viewModel.updateRiskSettings(newSettings)
                        showSavedMessage = true
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("save_risk_settings_button"),
                    colors = ButtonDefaults.buttonColors(containerColor = QuantCyan),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Icon(Icons.Default.Check, contentDescription = null, tint = Color.Black, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Apply Risk Guardrails", color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                }

                if (showSavedMessage) {
                    Text(
                        "Risk parameters saved and enforced in autonomous decision engine.",
                        color = QuantGreen,
                        fontSize = 11.sp
                    )
                }
            }
        }

        // Broker Integration Manager Card
        Text(
            text = "BROKER ADAPTER & UPSTOX API INTEGRATION",
            color = TextSubtle,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.8.sp
        )

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = CardDark),
            shape = RoundedCornerShape(14.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, CardBorder)
        ) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Select Execution Broker", color = TextWhite, fontSize = 13.sp, fontWeight = FontWeight.Medium)

                listOf(
                    "Paper Trading (Simulated)" to "Zero risk simulation with realistic Indian taxes (STT/GST) and slippage",
                    "Upstox Pro" to "Upstox Developer v2 REST & WebSocket API (Preferred Initial Broker)",
                    "Zerodha Kite" to "Kite Connect API (Future Adapter)",
                    "Angel One" to "SmartAPI Trading Platform (Future Adapter)"
                ).forEach { (brokerName, subtitle) ->
                    val isSelected = brokerName == brokerChoice
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (isSelected) CardDarkElevated else Color.Transparent)
                            .border(1.dp, if (isSelected) QuantCyan else CardBorder, RoundedCornerShape(8.dp))
                            .clickable {
                                brokerChoice = brokerName
                                viewModel.selectBroker(brokerName, upstoxToken, isSandbox = isUpstoxSandbox)
                            }
                            .padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.AccountBalance,
                            contentDescription = null,
                            tint = if (isSelected) QuantCyan else TextSubtle,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(brokerName, color = if (isSelected) QuantCyan else TextWhite, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                            Text(subtitle, color = TextSubtle, fontSize = 10.sp)
                        }
                    }
                }

                if (brokerChoice == "Upstox Pro") {
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text("Upstox Sandbox Mode", color = TextWhite, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                            Text("Test with virtual funds against Upstox Sandbox endpoints", color = TextSubtle, fontSize = 10.sp)
                        }
                        Switch(
                            checked = isUpstoxSandbox,
                            onCheckedChange = { isUpstoxSandbox = it },
                            colors = SwitchDefaults.colors(checkedThumbColor = QuantCyan, checkedTrackColor = QuantCyanDim)
                        )
                    }

                    OutlinedTextField(
                        value = upstoxToken,
                        onValueChange = { upstoxToken = it },
                        label = { Text("Upstox Bearer Access Token") },
                        placeholder = { Text("Paste token from Upstox Developer Console") },
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = QuantCyan,
                            unfocusedBorderColor = CardBorder,
                            focusedTextColor = TextWhite,
                            unfocusedTextColor = TextWhite
                        )
                    )

                    Button(
                        onClick = { viewModel.selectBroker(brokerChoice, upstoxToken, isSandbox = isUpstoxSandbox) },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = CardDarkElevated),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text("Connect Upstox API", color = QuantCyan, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }

                // Regulatory Disclaimer
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(CardDarkElevated, RoundedCornerShape(8.dp))
                        .padding(10.dp)
                ) {
                    Row(verticalAlignment = Alignment.Top) {
                        Icon(Icons.Default.Lock, contentDescription = null, tint = QuantGold, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "SEBI / Exchange Policy: Live real-money trading is disabled by default. Upstox Sandbox and realistic Paper Simulation are active with verified statutory tax calculations.",
                            color = TextMuted,
                            fontSize = 11.sp,
                            lineHeight = 15.sp
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(20.dp))
    }
}
