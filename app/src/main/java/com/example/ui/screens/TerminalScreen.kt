package com.example.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.AgentStatus
import com.example.data.model.DecisionType
import com.example.data.model.FeedStatus
import com.example.data.model.MarketSession
import com.example.data.model.VerificationStatus
import com.example.data.model.formatInr
import com.example.ui.components.CandleStickChart
import com.example.ui.theme.CardBorder
import com.example.ui.theme.CardDark
import com.example.ui.theme.CardDarkElevated
import com.example.ui.theme.QuantCyan
import com.example.ui.theme.QuantCyanDim
import com.example.ui.theme.QuantGold
import com.example.ui.theme.QuantGreen
import com.example.ui.theme.QuantGreenDim
import com.example.ui.theme.QuantPurple
import com.example.ui.theme.QuantRed
import com.example.ui.theme.QuantRedDim
import com.example.ui.theme.TerminalBg
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextSubtle
import com.example.ui.theme.TextWhite
import com.example.ui.viewmodel.TradingViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun TerminalScreen(viewModel: TradingViewModel) {
    val portfolio by viewModel.portfolio.collectAsState()
    val agentStatus by viewModel.agentStatus.collectAsState()
    val instruments by viewModel.instruments.collectAsState()
    val selectedSymbol by viewModel.selectedSymbol.collectAsState()
    val candles by viewModel.currentCandles.collectAsState()
    val snapshot by viewModel.currentSnapshot.collectAsState()
    val lastDecision by viewModel.lastDecision.collectAsState()
    val selectedTimeframe by viewModel.selectedTimeframe.collectAsState()
    val auditLogs by viewModel.auditLogs.collectAsState()
    val currentSession by viewModel.currentSession.collectAsState()
    val feedStatus by viewModel.feedStatus.collectAsState()
    val verificationStatus by viewModel.verificationStatus.collectAsState()
    val dataSourceName by viewModel.dataSourceName.collectAsState()

    var showIndicatorOverlay by remember { mutableStateOf(true) }

    val currentInst = instruments.find { it.symbol == selectedSymbol } ?: instruments.firstOrNull()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(TerminalBg)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Top System Status Bar
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "QuantAI",
                        color = TextWhite,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Box(
                        modifier = Modifier
                            .background(QuantCyanDim, RoundedCornerShape(4.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = "NSE / BSE",
                            color = QuantCyan,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
                Text(
                    text = "Autonomous Quantitative Engine",
                    color = TextMuted,
                    fontSize = 12.sp
                )
            }

            // Agent Status Badge
            val statusColor by animateColorAsState(
                targetValue = when (agentStatus) {
                    AgentStatus.RUNNING -> QuantGreen
                    AgentStatus.ANALYZING -> QuantCyan
                    AgentStatus.EXECUTING -> QuantGold
                    AgentStatus.EMERGENCY_HALTED -> QuantRed
                    AgentStatus.STOPPED -> TextMuted
                }, label = "statusColor"
            )

            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .background(statusColor.copy(alpha = 0.15f))
                    .border(1.dp, statusColor.copy(alpha = 0.6f), RoundedCornerShape(20.dp))
                    .padding(horizontal = 10.dp, vertical = 5.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(7.dp)
                            .background(statusColor, CircleShape)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = agentStatus.name,
                        color = statusColor,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        // Real Market Data Feed & Session Indicator Banner
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(CardDark)
                .border(1.dp, CardBorder, RoundedCornerShape(8.dp))
                .padding(horizontal = 10.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val feedColor = when {
                    verificationStatus == VerificationStatus.VERIFIED_EXCHANGE_OR_VENDOR && feedStatus == FeedStatus.LIVE -> QuantGreen
                    verificationStatus == VerificationStatus.VERIFIED_EXCHANGE_OR_VENDOR && feedStatus == FeedStatus.HISTORICAL -> QuantGreen
                    verificationStatus == VerificationStatus.IMPORTED_CSV_UNVERIFIED -> QuantCyan
                    feedStatus == FeedStatus.DELAYED -> Color(0xFFFF9800)
                    feedStatus == FeedStatus.DISCONNECTED -> QuantRed
                    else -> QuantGold
                }
                Box(modifier = Modifier.size(7.dp).background(feedColor, CircleShape))
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "${feedStatus.name} [${verificationStatus.name}]: $dataSourceName",
                    color = feedColor,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }

            val sessionText = when (currentSession) {
                MarketSession.PRE_OPEN -> "PRE-OPEN (09:00-09:15)"
                MarketSession.REGULAR_HOURS -> "NSE OPEN (09:15-15:30)"
                MarketSession.POST_CLOSE -> "POST-CLOSE (15:30-16:00)"
                MarketSession.CLOSED -> "NSE CLOSED"
            }
            Text(
                text = sessionText,
                color = if (currentSession == MarketSession.REGULAR_HOURS) QuantGreen else TextSubtle,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold
            )
        }

        // Portfolio Capital Summary Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = CardDark),
            shape = RoundedCornerShape(16.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, CardBorder)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Top
                ) {
                    Column {
                        Text(
                            text = "TOTAL TRADING CAPITAL",
                            color = TextSubtle,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            letterSpacing = 0.8.sp
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = formatInr(portfolio.currentNetWorth),
                            color = TextWhite,
                            fontSize = 24.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    // Today's P&L Pill
                    val isPositiveDay = portfolio.dayTotalPnl >= 0
                    val dayPnlColor = if (isPositiveDay) QuantGreen else QuantRed
                    val dayPnlBg = if (isPositiveDay) QuantGreenDim else QuantRedDim

                    Box(
                        modifier = Modifier
                            .background(dayPnlBg, RoundedCornerShape(8.dp))
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        Column(horizontalAlignment = Alignment.End) {
                            Text(
                                text = "${if (isPositiveDay) "+" else ""}${formatInr(portfolio.dayTotalPnl)}",
                                color = dayPnlColor,
                                fontSize = 13.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "${if (isPositiveDay) "+" else ""}${String.format("%.2f", portfolio.dayTotalPnlPercent)}% today",
                                color = dayPnlColor.copy(alpha = 0.8f),
                                fontSize = 11.sp
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text("Available Cash", color = TextMuted, fontSize = 11.sp)
                        Text(
                            formatInr(portfolio.availableCash),
                            color = TextWhite,
                            fontSize = 14.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Medium
                        )
                    }
                    Column {
                        Text("Invested Margin", color = TextMuted, fontSize = 11.sp)
                        Text(
                            formatInr(portfolio.investedValue),
                            color = TextWhite,
                            fontSize = 14.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Medium
                        )
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text("Realized P&L", color = TextMuted, fontSize = 11.sp)
                        val relColor = if (portfolio.totalRealizedPnl >= 0) QuantGreen else QuantRed
                        Text(
                            "${if (portfolio.totalRealizedPnl >= 0) "+" else ""}${formatInr(portfolio.totalRealizedPnl)}",
                            color = relColor,
                            fontSize = 14.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Autonomous Agent Controls
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (agentStatus == AgentStatus.RUNNING || agentStatus == AgentStatus.ANALYZING || agentStatus == AgentStatus.EXECUTING) {
                        Button(
                            onClick = { viewModel.pauseAgent() },
                            modifier = Modifier
                                .weight(1f)
                                .testTag("pause_agent_button"),
                            colors = ButtonDefaults.buttonColors(containerColor = CardDarkElevated),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Icon(Icons.Default.Pause, contentDescription = "Pause", tint = QuantGold, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Pause Agent", color = TextWhite, fontSize = 12.sp)
                        }
                    } else if (agentStatus == AgentStatus.STOPPED) {
                        Button(
                            onClick = { viewModel.startAgent() },
                            modifier = Modifier
                                .weight(1f)
                                .testTag("start_agent_button"),
                            colors = ButtonDefaults.buttonColors(containerColor = QuantGreen),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Icon(Icons.Default.PlayArrow, contentDescription = "Resume", tint = Color.Black, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Start Agent", color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        }
                    } else if (agentStatus == AgentStatus.EMERGENCY_HALTED) {
                        Button(
                            onClick = { viewModel.resetKillSwitch() },
                            modifier = Modifier
                                .weight(1f)
                                .testTag("reset_killswitch_button"),
                            colors = ButtonDefaults.buttonColors(containerColor = QuantGold),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Icon(Icons.Default.Security, contentDescription = "Disarm", tint = Color.Black, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Disarm Kill Switch", color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        }
                    }

                    OutlinedButton(
                        onClick = { viewModel.triggerEmergencyKillSwitch() },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("kill_switch_button"),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = QuantRed),
                        border = androidx.compose.foundation.BorderStroke(1.dp, QuantRed),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Icon(Icons.Default.Warning, contentDescription = "Kill Switch", tint = QuantRed, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Kill Switch", color = QuantRed, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        // Horizontal Instruments Watchlist Selector
        Text(
            text = "NSE WATCHLIST & TICK STREAM",
            color = TextSubtle,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.8.sp
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            instruments.forEach { inst ->
                val isSelected = inst.symbol == selectedSymbol
                val chipBg = if (isSelected) CardDarkElevated else CardDark
                val borderColor = if (isSelected) QuantCyan else CardBorder

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(chipBg)
                        .border(1.dp, borderColor, RoundedCornerShape(10.dp))
                        .clickable { viewModel.selectSymbol(inst.symbol) }
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                        .testTag("watchlist_item_${inst.symbol}")
                ) {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = inst.symbol,
                                color = if (isSelected) QuantCyan else TextWhite,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            if (!inst.isTradable) {
                                Box(
                                    modifier = Modifier
                                        .background(Color(0x33FFC107), RoundedCornerShape(2.dp))
                                        .padding(horizontal = 3.dp, vertical = 1.dp)
                                ) {
                                    Text("INDEX", color = QuantGold, fontSize = 8.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "₹${String.format("%.2f", inst.currentPrice)}",
                            color = TextWhite,
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace
                        )
                        val chgColor = if (inst.isPositive) QuantGreen else QuantRed
                        Text(
                            text = "${if (inst.isPositive) "+" else ""}${String.format("%.2f", inst.changePercent)}%",
                            color = chgColor,
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }
        }

        // Selected Symbol Header & Timeframe Selector
        if (currentInst != null) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "${currentInst.name} (${currentInst.symbol})",
                            color = TextWhite,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        if (!currentInst.isTradable) {
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("(Benchmark Index)", color = QuantGold, fontSize = 11.sp)
                        }
                    }
                    Text(
                        text = "₹${String.format("%.2f", currentInst.currentPrice)} | Bid: ₹${String.format("%.2f", currentInst.bidPrice)} Ask: ₹${String.format("%.2f", currentInst.askPrice)}",
                        color = TextMuted,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }

                // Timeframe Chips
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf("1m", "5m", "15m", "1D").forEach { tf ->
                        val isTfSelected = tf == selectedTimeframe
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(if (isTfSelected) QuantCyanDim else CardDark)
                                .clickable { viewModel.setTimeframe(tf) }
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Text(
                                text = tf,
                                color = if (isTfSelected) QuantCyan else TextMuted,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }

        // Live Candlestick Chart
        CandleStickChart(
            candles = candles,
            snapshot = snapshot,
            showIndicators = showIndicatorOverlay
        )

        // Overlay Indicator Controls
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(modifier = Modifier.size(8.dp).background(QuantCyan, CircleShape))
                Spacer(modifier = Modifier.width(4.dp))
                Text("EMA 9 & BB", color = TextMuted, fontSize = 11.sp)
                Spacer(modifier = Modifier.width(10.dp))
                Box(modifier = Modifier.size(8.dp).background(QuantPurple, CircleShape))
                Spacer(modifier = Modifier.width(4.dp))
                Text("EMA 21", color = TextMuted, fontSize = 11.sp)
            }

            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(CardDark)
                    .clickable { showIndicatorOverlay = !showIndicatorOverlay }
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Text(
                    text = if (showIndicatorOverlay) "Hide Overlays" else "Show Overlays",
                    color = QuantCyan,
                    fontSize = 11.sp
                )
            }
        }

        // Autonomous AI Decision Radar Card
        Text(
            text = "AI QUANTITATIVE INTELLIGENCE & ML PREDICTIONS",
            color = TextSubtle,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.8.sp
        )

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = CardDark),
            shape = RoundedCornerShape(16.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, CardBorder)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                val decType = lastDecision?.type ?: DecisionType.NO_TRADE
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Bolt, contentDescription = "AI", tint = QuantCyan, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Ensemble Decision Engine",
                            color = TextWhite,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    // Signal Badge
                    val decColor = when (decType) {
                        DecisionType.BUY -> QuantGreen
                        DecisionType.SELL -> QuantRed
                        DecisionType.HOLD -> QuantGold
                        DecisionType.NO_TRADE -> TextMuted
                    }
                    val decBg = when (decType) {
                        DecisionType.BUY -> QuantGreenDim
                        DecisionType.SELL -> QuantRedDim
                        DecisionType.HOLD -> Color(0x33FFC107)
                        DecisionType.NO_TRADE -> CardDarkElevated
                    }

                    Box(
                        modifier = Modifier
                            .background(decBg, RoundedCornerShape(6.dp))
                            .padding(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = decType.name,
                            color = decColor,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Confidence Gauge
                val confPct = (lastDecision?.confidence ?: 0.0) * 100.0
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Model Confidence", color = TextMuted, fontSize = 12.sp)
                    Text(
                        "${String.format("%.0f", confPct)}%",
                        color = if (confPct >= 42) QuantGreen else TextMuted,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                LinearProgressIndicator(
                    progress = { (lastDecision?.confidence?.toFloat() ?: 0f) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp)),
                    color = if (decType == DecisionType.BUY) QuantGreen else if (decType == DecisionType.SELL) QuantRed else QuantCyan,
                    trackColor = CardDarkElevated,
                )

                Spacer(modifier = Modifier.height(12.dp))

                // Machine Learning Classification Probabilities
                val ml = lastDecision?.mlPrediction
                if (ml != null) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(CardDarkElevated)
                            .padding(8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Psychology, contentDescription = null, tint = QuantPurple, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(ml.modelVersion, color = QuantPurple, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                        Text(
                            text = if (ml.isTrained) {
                                "Up: ${String.format("%.0f", ml.probUpward * 100)}% | Flat: ${String.format("%.0f", ml.probSideways * 100)}% | Down: ${String.format("%.0f", ml.probDownward * 100)}%"
                            } else {
                                "UNTRAINED (Train in Strategy Lab)"
                            },
                            color = if (ml.isTrained) TextWhite else TextMuted,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                }

                // Proposed Order Execution Levels if Buy/Sell
                if (lastDecision?.type == DecisionType.BUY || lastDecision?.type == DecisionType.SELL) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text("Entry Price", color = TextMuted, fontSize = 11.sp)
                            Text(
                                "₹${String.format("%.2f", lastDecision?.entryPrice ?: 0.0)}",
                                color = TextWhite,
                                fontSize = 13.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Medium
                            )
                        }
                        Column {
                            Text("Stop Loss (SL)", color = TextMuted, fontSize = 11.sp)
                            Text(
                                "₹${String.format("%.2f", lastDecision?.stopLoss ?: 0.0)}",
                                color = QuantRed,
                                fontSize = 13.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Medium
                            )
                        }
                        Column {
                            Text("Target (TP)", color = TextMuted, fontSize = 11.sp)
                            Text(
                                "₹${String.format("%.2f", lastDecision?.targetPrice ?: 0.0)}",
                                color = QuantGreen,
                                fontSize = 13.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Medium
                            )
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text("R:R Ratio", color = TextMuted, fontSize = 11.sp)
                            Text(
                                "1 : ${String.format("%.1f", lastDecision?.riskRewardRatio ?: 2.0)}",
                                color = QuantGold,
                                fontSize = 13.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                }

                // Rationale text
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(CardDarkElevated, RoundedCornerShape(8.dp))
                        .padding(10.dp)
                ) {
                    Text(
                        text = lastDecision?.rationale ?: "Scanning market data for valid statistical setups across NSE instruments...",
                        color = TextWhite,
                        fontSize = 12.sp,
                        lineHeight = 16.sp
                    )
                }
            }
        }

        // Live Audit Log Snippet
        Text(
            text = "AGENT ACTIVITY & DECISION LOG",
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
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (auditLogs.isEmpty()) {
                    Text("No logs yet. Initializing agent loop...", color = TextMuted, fontSize = 12.sp)
                } else {
                    auditLogs.take(5).forEach { log ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.Top
                        ) {
                            val dotColor = if (log.action == "DECISION") QuantCyan else if (log.details.contains("EXACT") || log.details.contains("EXECUTED")) QuantGreen else if (log.details.contains("REJECTED")) QuantRed else TextMuted
                            Box(
                                modifier = Modifier
                                    .padding(top = 4.dp)
                                    .size(6.dp)
                                    .background(dotColor, CircleShape)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                Row {
                                    Text(
                                        text = log.symbol,
                                        color = QuantCyan,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = log.action,
                                        color = TextSubtle,
                                        fontSize = 10.sp
                                    )
                                }
                                Text(
                                    text = log.details,
                                    color = TextWhite,
                                    fontSize = 11.sp,
                                    lineHeight = 15.sp
                                )
                            }
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(20.dp))
    }
}
