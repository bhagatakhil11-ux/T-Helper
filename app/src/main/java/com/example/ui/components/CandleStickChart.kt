package com.example.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.Candle
import com.example.engine.indicators.IndicatorSnapshot
import com.example.engine.indicators.TechnicalIndicators
import com.example.ui.theme.CardDarkElevated
import com.example.ui.theme.QuantCyan
import com.example.ui.theme.QuantGreen
import com.example.ui.theme.QuantPurple
import com.example.ui.theme.QuantRed
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextWhite
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun CandleStickChart(
    candles: List<Candle>,
    snapshot: IndicatorSnapshot?,
    modifier: Modifier = Modifier,
    showIndicators: Boolean = true
) {
    if (candles.isEmpty()) {
        Box(
            modifier = modifier
                .fillMaxWidth()
                .height(280.dp)
                .background(CardDarkElevated, RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center
        ) {
            Text("Aggregating live market candles...", color = TextMuted, fontSize = 13.sp)
        }
        return
    }

    var selectedCandleIndex by remember { mutableStateOf<Int?>(null) }
    val displayCandles = remember(candles) { candles.takeLast(35) }

    val minPrice = remember(displayCandles) { (displayCandles.minOfOrNull { it.low } ?: 100.0) * 0.998 }
    val maxPrice = remember(displayCandles) { (displayCandles.maxOfOrNull { it.high } ?: 100.0) * 1.002 }
    val priceRange = remember(minPrice, maxPrice) { (maxPrice - minPrice).coerceAtLeast(1.0) }

    val maxVolume = remember(displayCandles) { (displayCandles.maxOfOrNull { it.volume } ?: 1000L).coerceAtLeast(1L) }

    val selectedCandle = selectedCandleIndex?.let { idx ->
        if (idx in displayCandles.indices) displayCandles[idx] else displayCandles.lastOrNull()
    } ?: displayCandles.lastOrNull()

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(290.dp)
            .background(CardDarkElevated, RoundedCornerShape(14.dp))
            .padding(8.dp)
            .testTag("candlestick_chart")
    ) {
        // Floating HUD Header
        if (selectedCandle != null) {
            val isBullish = selectedCandle.close >= selectedCandle.open
            val chgPct = if (selectedCandle.open > 0) ((selectedCandle.close - selectedCandle.open) / selectedCandle.open) * 100.0 else 0.0
            val timeStr = remember(selectedCandle.timestamp) {
                SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(selectedCandle.timestamp))
            }

            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .background(Color(0xDD0A0E17), RoundedCornerShape(6.dp))
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Text(
                    text = "$timeStr | O: ${String.format("%.1f", selectedCandle.open)} H: ${String.format("%.1f", selectedCandle.high)} L: ${String.format("%.1f", selectedCandle.low)} C: ${String.format("%.1f", selectedCandle.close)} (${if (isBullish) "+" else ""}${String.format("%.2f", chgPct)}%)",
                    style = TextStyle(
                        color = if (isBullish) QuantGreen else QuantRed,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Medium
                    )
                )
            }
        }

        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = 26.dp, bottom = 18.dp, end = 45.dp)
                .pointerInput(displayCandles) {
                    detectTapGestures(
                        onPress = { offset ->
                            val candleWidth = size.width / displayCandles.size
                            val index = (offset.x / candleWidth).toInt().coerceIn(0, displayCandles.size - 1)
                            selectedCandleIndex = index
                        }
                    )
                }
        ) {
            val chartWidth = size.width
            val chartHeight = size.height
            val candleCount = displayCandles.size
            val candleStep = chartWidth / candleCount
            val candleBarWidth = (candleStep * 0.72f).coerceAtLeast(3f)

            // Price to Y conversion function
            fun priceToY(price: Double): Float {
                val normalized = (price - minPrice) / priceRange
                return (chartHeight * (1.0 - normalized)).toFloat()
            }

            // Draw horizontal price grid lines
            val gridSteps = 4
            for (i in 0..gridSteps) {
                val gridY = (chartHeight * (i.toFloat() / gridSteps))
                drawLine(
                    color = Color(0x22FFFFFF),
                    start = Offset(0f, gridY),
                    end = Offset(chartWidth, gridY),
                    strokeWidth = 1f
                )
            }

            // Bollinger Bands shading and line overlays if enabled
            if (showIndicators && snapshot != null && displayCandles.size >= 10) {
                val bbUpperY = priceToY(snapshot.bollinger.upper)
                val bbLowerY = priceToY(snapshot.bollinger.lower)
                val bbMidY = priceToY(snapshot.bollinger.middle)

                // Bollinger Bands shaded channel
                val bandPath = Path().apply {
                    moveTo(0f, bbUpperY)
                    lineTo(chartWidth, bbUpperY)
                    lineTo(chartWidth, bbLowerY)
                    lineTo(0f, bbLowerY)
                    close()
                }
                drawPath(bandPath, Color(0x1000E5FF))

                // Bollinger Upper & Lower Lines
                drawLine(QuantCyan.copy(alpha = 0.5f), Offset(0f, bbUpperY), Offset(chartWidth, bbUpperY), 1.5f)
                drawLine(QuantCyan.copy(alpha = 0.5f), Offset(0f, bbLowerY), Offset(chartWidth, bbLowerY), 1.5f)
                drawLine(QuantCyan.copy(alpha = 0.3f), Offset(0f, bbMidY), Offset(chartWidth, bbMidY), 1f)
            }

            // Draw Candlesticks & Volume
            for (i in displayCandles.indices) {
                val candle = displayCandles[i]
                val centerX = (i * candleStep) + (candleStep / 2f)

                val highY = priceToY(candle.high)
                val lowY = priceToY(candle.low)
                val openY = priceToY(candle.open)
                val closeY = priceToY(candle.close)

                val isBull = candle.close >= candle.open
                val candleColor = if (isBull) QuantGreen else QuantRed

                // Volume Bar at bottom (takes bottom 22% of chart)
                val volRatio = (candle.volume.toFloat() / maxVolume).coerceIn(0.05f, 1f)
                val volHeight = (chartHeight * 0.22f) * volRatio
                val volY = chartHeight - volHeight
                drawRect(
                    color = candleColor.copy(alpha = 0.25f),
                    topLeft = Offset(centerX - (candleBarWidth / 2f), volY),
                    size = Size(candleBarWidth, volHeight)
                )

                // Wick Line
                drawLine(
                    color = candleColor,
                    start = Offset(centerX, highY),
                    end = Offset(centerX, lowY),
                    strokeWidth = 1.6f,
                    cap = StrokeCap.Round
                )

                // Candle Body Rect
                val topBodyY = minOf(openY, closeY)
                val bodyHeight = (maxOf(openY, closeY) - topBodyY).coerceAtLeast(2f)

                drawRect(
                    color = candleColor,
                    topLeft = Offset(centerX - (candleBarWidth / 2f), topBodyY),
                    size = Size(candleBarWidth, bodyHeight)
                )
            }

            // Overlay EMA 9 & EMA 21 curves
            if (showIndicators && displayCandles.size >= 10) {
                val closes = displayCandles.map { it.close }
                val ema9 = TechnicalIndicators.calculateEMASeries(closes, 9)
                val ema21 = TechnicalIndicators.calculateEMASeries(closes, minOf(21, closes.size))

                if (ema9.isNotEmpty()) {
                    val emaPath = Path()
                    val offsetIndex = displayCandles.size - ema9.size
                    for (k in ema9.indices) {
                        val cx = ((k + offsetIndex) * candleStep) + (candleStep / 2f)
                        val cy = priceToY(ema9[k])
                        if (k == 0) emaPath.moveTo(cx, cy) else emaPath.lineTo(cx, cy)
                    }
                    drawPath(emaPath, color = QuantCyan, style = Stroke(width = 2.2f))
                }

                if (ema21.isNotEmpty()) {
                    val ema21Path = Path()
                    val offsetIndex = displayCandles.size - ema21.size
                    for (k in ema21.indices) {
                        val cx = ((k + offsetIndex) * candleStep) + (candleStep / 2f)
                        val cy = priceToY(ema21[k])
                        if (k == 0) ema21Path.moveTo(cx, cy) else ema21Path.lineTo(cx, cy)
                    }
                    drawPath(ema21Path, color = QuantPurple, style = Stroke(width = 2f))
                }
            }

            // Crosshair overlay if touched
            selectedCandleIndex?.let { idx ->
                if (idx in displayCandles.indices) {
                    val crosshairX = (idx * candleStep) + (candleStep / 2f)
                    val crosshairY = priceToY(displayCandles[idx].close)

                    drawLine(
                        color = Color.White.copy(alpha = 0.5f),
                        start = Offset(crosshairX, 0f),
                        end = Offset(crosshairX, chartHeight),
                        strokeWidth = 1f
                    )
                    drawLine(
                        color = Color.White.copy(alpha = 0.5f),
                        start = Offset(0f, crosshairY),
                        end = Offset(chartWidth, crosshairY),
                        strokeWidth = 1f
                    )
                    drawCircle(Color.White, radius = 4f, center = Offset(crosshairX, crosshairY))
                }
            }
        }
    }
}
