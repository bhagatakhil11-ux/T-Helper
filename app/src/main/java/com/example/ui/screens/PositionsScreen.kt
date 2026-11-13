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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.TrendingUp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
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
import com.example.data.model.OrderSide
import com.example.data.model.OrderStatus
import com.example.data.model.Position
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun PositionsScreen(viewModel: TradingViewModel) {
    val positions by viewModel.positions.collectAsState()
    val orders by viewModel.orders.collectAsState()

    var selectedSubTab by remember { mutableStateOf(0) } // 0 = Positions, 1 = Orders

    val totalUnrealizedPnl = positions.sumOf { it.unrealizedPnl }
    val isOverallProfitable = totalUnrealizedPnl >= 0

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(TerminalBg)
            .padding(16.dp)
    ) {
        // Top Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Positions & Orders",
                color = TextWhite,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold
            )

            // Sub Tab Selector
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(CardDark)
                    .border(1.dp, CardBorder, RoundedCornerShape(8.dp))
                    .padding(3.dp)
            ) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(if (selectedSubTab == 0) CardDarkElevated else Color.Transparent)
                        .clickable { selectedSubTab = 0 }
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                        .testTag("positions_tab")
                ) {
                    Text(
                        text = "Positions (${positions.size})",
                        color = if (selectedSubTab == 0) QuantCyan else TextMuted,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(if (selectedSubTab == 1) CardDarkElevated else Color.Transparent)
                        .clickable { selectedSubTab = 1 }
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                        .testTag("orders_tab")
                ) {
                    Text(
                        text = "Orders (${orders.size})",
                        color = if (selectedSubTab == 1) QuantCyan else TextMuted,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        if (selectedSubTab == 0) {
            // Positions Overview Card
            if (positions.isNotEmpty()) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = CardDark),
                    shape = RoundedCornerShape(12.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, CardBorder)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text("Total Unrealized P&L", color = TextMuted, fontSize = 11.sp)
                            val pnlColor = if (isOverallProfitable) QuantGreen else QuantRed
                            Text(
                                text = "${if (isOverallProfitable) "+" else ""}${formatInr(totalUnrealizedPnl)}",
                                color = pnlColor,
                                fontSize = 18.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        Box(
                            modifier = Modifier
                                .background(if (isOverallProfitable) QuantGreenDim else QuantRedDim, RoundedCornerShape(6.dp))
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Text(
                                text = "${positions.size} Active Trades",
                                color = if (isOverallProfitable) QuantGreen else QuantRed,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(14.dp))
            }

            // Positions List
            if (positions.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(bottom = 60.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Default.TrendingUp,
                            contentDescription = null,
                            tint = TextSubtle,
                            modifier = Modifier.size(48.dp)
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "No Open Positions",
                            color = TextWhite,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "The autonomous engine is scanning NSE instruments for confirmed entry signals.",
                            color = TextMuted,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(horizontal = 32.dp),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                    }
                }
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(positions, key = { it.id }) { pos ->
                        PositionCard(
                            position = pos,
                            onSquareOff = { viewModel.squareOffPosition(pos) }
                        )
                    }
                }
            }
        } else {
            // Orders History List
            if (orders.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(bottom = 60.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.History, contentDescription = null, tint = TextSubtle, modifier = Modifier.size(48.dp))
                        Spacer(modifier = Modifier.height(12.dp))
                        Text("No Orders Executed", color = TextWhite, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.height(6.dp))
                        Text("Orders will be logged here with timestamps and execution audit details.", color = TextMuted, fontSize = 12.sp)
                    }
                }
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(orders, key = { it.id }) { ord ->
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = CardDark),
                            shape = RoundedCornerShape(12.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, CardBorder)
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        val isBuy = ord.side == OrderSide.BUY
                                        Box(
                                            modifier = Modifier
                                                .background(if (isBuy) QuantGreenDim else QuantRedDim, RoundedCornerShape(4.dp))
                                                .padding(horizontal = 6.dp, vertical = 2.dp)
                                        ) {
                                            Text(
                                                text = ord.side.name,
                                                color = if (isBuy) QuantGreen else QuantRed,
                                                fontSize = 10.sp,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(ord.symbol, color = TextWhite, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text("Qty: ${ord.quantity}", color = TextMuted, fontSize = 12.sp)
                                    }

                                    // Status Badge
                                    val isFilled = ord.status == OrderStatus.FILLED
                                    Box(
                                        modifier = Modifier
                                            .background(if (isFilled) Color(0x2200E676) else Color(0x22FF3D57), RoundedCornerShape(4.dp))
                                            .padding(horizontal = 6.dp, vertical = 2.dp)
                                    ) {
                                        Text(
                                            text = ord.status.name,
                                            color = if (isFilled) QuantGreen else QuantRed,
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.height(6.dp))

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = "Executed Price: ₹${String.format("%.2f", ord.filledPrice)}",
                                        color = TextWhite,
                                        fontSize = 12.sp,
                                        fontFamily = FontFamily.Monospace
                                    )
                                    val timeStr = ord.filledAt?.let {
                                        SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(it))
                                    } ?: ""
                                    Text(timeStr, color = TextSubtle, fontSize = 11.sp)
                                }

                                if (ord.statutoryCharges > 0 || ord.slippageDeducted > 0) {
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = "Friction: Slippage -₹${String.format("%.2f", ord.slippageDeducted)} | Levies -₹${String.format("%.2f", ord.statutoryCharges)}",
                                        color = TextSubtle,
                                        fontSize = 10.sp,
                                        fontFamily = FontFamily.Monospace
                                    )
                                }
                                if (ord.reason.isNotBlank()) {
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = ord.reason,
                                        color = TextMuted,
                                        fontSize = 11.sp,
                                        maxLines = 2
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PositionCard(
    position: Position,
    onSquareOff: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("position_card_${position.symbol}"),
        colors = CardDefaults.cardColors(containerColor = CardDark),
        shape = RoundedCornerShape(14.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, CardBorder)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val isBuy = position.side == OrderSide.BUY
                    Box(
                        modifier = Modifier
                            .background(if (isBuy) QuantGreenDim else QuantRedDim, RoundedCornerShape(4.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = position.side.name,
                            color = if (isBuy) QuantGreen else QuantRed,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = position.symbol,
                        color = TextWhite,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "${position.quantity} shares",
                        color = TextMuted,
                        fontSize = 12.sp
                    )
                }

                // Unrealized P&L
                val isProfitable = position.isProfitable
                val pnlColor = if (isProfitable) QuantGreen else QuantRed
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = "${if (isProfitable) "+" else ""}${formatInr(position.unrealizedPnl)}",
                        color = pnlColor,
                        fontSize = 15.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "${if (isProfitable) "+" else ""}${String.format("%.2f", position.unrealizedPnlPercent)}%",
                        color = pnlColor,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Pricing & Exit Levels
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text("Entry Price", color = TextMuted, fontSize = 11.sp)
                    Text("₹${String.format("%.2f", position.entryPrice)}", color = TextWhite, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                }
                Column {
                    Text("Current Price", color = TextMuted, fontSize = 11.sp)
                    Text("₹${String.format("%.2f", position.currentPrice)}", color = TextWhite, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                }
                Column {
                    Text("Trailing Stop", color = TextMuted, fontSize = 11.sp)
                    Text("₹${String.format("%.2f", position.stopLoss)}", color = QuantRed, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text("Target", color = TextMuted, fontSize = 11.sp)
                    Text("₹${String.format("%.2f", position.targetPrice)}", color = QuantGreen, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Square Off Action
            Button(
                onClick = onSquareOff,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(36.dp)
                    .testTag("square_off_button_${position.symbol}"),
                colors = ButtonDefaults.buttonColors(containerColor = CardDarkElevated),
                shape = RoundedCornerShape(8.dp)
            ) {
                Icon(Icons.Default.Close, contentDescription = "Square Off", tint = QuantRed, modifier = Modifier.size(14.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Square Off Position", color = QuantRed, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}
