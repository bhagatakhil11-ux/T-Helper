package com.example.engine.indicators

import com.example.data.model.Candle
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

data class MacdOutput(
    val macd: Double,
    val signal: Double,
    val histogram: Double
)

data class BollingerOutput(
    val middle: Double,
    val upper: Double,
    val lower: Double,
    val bandwidth: Double,
    val percentB: Double
)

enum class CandlestickPattern {
    BULLISH_ENGULFING,
    BEARISH_ENGULFING,
    HAMMER,
    SHOOTING_STAR,
    DOJI,
    MORNING_STAR,
    EVENING_STAR,
    NONE
}

data class IndicatorSnapshot(
    val rsi14: Double,
    val macd: MacdOutput,
    val bollinger: BollingerOutput,
    val atr14: Double,
    val vwap: Double,
    val ema9: Double,
    val ema21: Double,
    val ema50: Double,
    val ema200: Double,
    val detectedPattern: CandlestickPattern
)

object TechnicalIndicators {

    fun calculateSMA(prices: List<Double>, period: Int): Double {
        if (prices.size < period || period <= 0) return prices.lastOrNull() ?: 0.0
        val slice = prices.takeLast(period)
        return slice.sum() / period
    }

    fun calculateEMA(prices: List<Double>, period: Int): Double {
        if (prices.isEmpty() || period <= 0) return 0.0
        if (prices.size < period) return calculateSMA(prices, prices.size)

        val multiplier = 2.0 / (period + 1.0)
        var ema = calculateSMA(prices.take(period), period)

        for (i in period until prices.size) {
            ema = (prices[i] - ema) * multiplier + ema
        }
        return ema
    }

    fun calculateEMASeries(prices: List<Double>, period: Int): List<Double> {
        if (prices.size < period) return emptyList()
        val result = mutableListOf<Double>()
        val multiplier = 2.0 / (period + 1.0)

        var ema = calculateSMA(prices.take(period), period)
        result.add(ema)

        for (i in period until prices.size) {
            ema = (prices[i] - ema) * multiplier + ema
            result.add(ema)
        }
        return result
    }

    /**
     * Relative Strength Index (RSI) using Wilder's smoothing method (default period: 14)
     */
    fun calculateRSI(candles: List<Candle>, period: Int = 14): Double {
        if (candles.size <= period) return 50.0

        val closes = candles.map { it.close }
        val changes = mutableListOf<Double>()
        for (i in 1 until closes.size) {
            changes.add(closes[i] - closes[i - 1])
        }

        var avgGain = 0.0
        var avgLoss = 0.0

        for (i in 0 until period) {
            val chg = changes[i]
            if (chg >= 0) avgGain += chg else avgLoss += abs(chg)
        }
        avgGain /= period
        avgLoss /= period

        for (i in period until changes.size) {
            val chg = changes[i]
            val gain = if (chg > 0) chg else 0.0
            val loss = if (chg < 0) abs(chg) else 0.0

            avgGain = (avgGain * (period - 1) + gain) / period
            avgLoss = (avgLoss * (period - 1) + loss) / period
        }

        if (avgLoss == 0.0) return 100.0
        val rs = avgGain / avgLoss
        return 100.0 - (100.0 / (1.0 + rs))
    }

    /**
     * MACD (Fast = 12, Slow = 26, Signal = 9)
     */
    fun calculateMACD(
        candles: List<Candle>,
        fastPeriod: Int = 12,
        slowPeriod: Int = 26,
        signalPeriod: Int = 9
    ): MacdOutput {
        if (candles.size < slowPeriod + signalPeriod - 1 || fastPeriod >= slowPeriod) {
            return MacdOutput(0.0, 0.0, 0.0)
        }
        val closes = candles.map { it.close }
        val fastSeries = calculateEMASeries(closes, fastPeriod)
        val slowSeries = calculateEMASeries(closes, slowPeriod)
        val offset = slowPeriod - fastPeriod

        val macdSeries = List(slowSeries.size) { j ->
            fastSeries[j + offset] - slowSeries[j]
        }

        val macd = macdSeries.lastOrNull() ?: 0.0
        val signal = calculateEMA(macdSeries, signalPeriod)
        val histogram = macd - signal

        return MacdOutput(macd = macd, signal = signal, histogram = histogram)
    }

    /**
     * Bollinger Bands (Period = 20, Multiplier = 2.0)
     */
    fun calculateBollingerBands(
        candles: List<Candle>,
        period: Int = 20,
        k: Double = 2.0
    ): BollingerOutput {
        if (candles.isEmpty()) return BollingerOutput(0.0, 0.0, 0.0, 0.0, 0.5)
        val n = min(candles.size, period)
        val slice = candles.takeLast(n).map { it.close }
        val middle = slice.sum() / n

        var variance = 0.0
        for (price in slice) {
            variance += (price - middle) * (price - middle)
        }
        val stdDev = sqrt(variance / n)

        val upper = middle + (k * stdDev)
        val lower = middle - (k * stdDev)
        val bandwidth = if (middle > 0) ((upper - lower) / middle) * 100.0 else 0.0

        val currentPrice = candles.last().close
        val bandDiff = upper - lower
        val percentB = if (bandDiff > 0) (currentPrice - lower) / bandDiff else 0.5

        return BollingerOutput(
            middle = middle,
            upper = upper,
            lower = lower,
            bandwidth = bandwidth,
            percentB = percentB
        )
    }

    /**
     * Average True Range (ATR)
     */
    fun calculateATR(candles: List<Candle>, period: Int = 14): Double {
        if (candles.size < 2) return (candles.lastOrNull()?.let { it.high - it.low }) ?: 1.0

        val trList = mutableListOf<Double>()
        for (i in 1 until candles.size) {
            val cur = candles[i]
            val prevClose = candles[i - 1].close
            val tr1 = cur.high - cur.low
            val tr2 = abs(cur.high - prevClose)
            val tr3 = abs(cur.low - prevClose)
            trList.add(max(tr1, max(tr2, tr3)))
        }

        if (trList.size < period) {
            return trList.average()
        }

        var atr = trList.take(period).average()
        for (i in period until trList.size) {
            atr = (atr * (period - 1) + trList[i]) / period
        }
        return atr
    }

    /**
     * Volume Weighted Average Price (VWAP)
     */
    fun calculateVWAP(candles: List<Candle>): Double {
        if (candles.isEmpty()) return 0.0
        var cumulativeTPV = 0.0
        var cumulativeVol = 0.0

        for (candle in candles) {
            val typicalPrice = (candle.high + candle.low + candle.close) / 3.0
            cumulativeTPV += typicalPrice * candle.volume
            cumulativeVol += candle.volume
        }

        return if (cumulativeVol > 0) cumulativeTPV / cumulativeVol else candles.last().close
    }

    /**
     * Candlestick Pattern Recognition Engine
     */
    fun detectPattern(candles: List<Candle>): CandlestickPattern {
        if (candles.size < 3) return CandlestickPattern.NONE

        val c1 = candles[candles.size - 3]
        val c2 = candles[candles.size - 2]
        val c3 = candles.last()

        val body3 = abs(c3.close - c3.open)
        val range3 = c3.high - c3.low
        val isBullish3 = c3.close >= c3.open

        val body2 = abs(c2.close - c2.open)
        val isBullish2 = c2.close >= c2.open

        // Doji check on latest candle
        if (range3 > 0 && (body3 / range3) <= 0.1) {
            return CandlestickPattern.DOJI
        }

        // Bullish Engulfing: c2 bearish, c3 bullish completely engulfs c2 body
        if (!isBullish2 && isBullish3 && c3.open <= c2.close && c3.close >= c2.open) {
            return CandlestickPattern.BULLISH_ENGULFING
        }

        // Bearish Engulfing: c2 bullish, c3 bearish completely engulfs c2 body
        if (isBullish2 && !isBullish3 && c3.open >= c2.close && c3.close <= c2.open) {
            return CandlestickPattern.BEARISH_ENGULFING
        }

        // Hammer: Long lower shadow >= 2x body, tiny upper shadow, bullish or small bearish body
        val lowerShadow3 = if (isBullish3) c3.open - c3.low else c3.close - c3.low
        val upperShadow3 = if (isBullish3) c3.high - c3.close else c3.high - c3.open
        if (range3 > 0 && lowerShadow3 >= 2 * body3 && upperShadow3 <= body3 * 0.5) {
            return CandlestickPattern.HAMMER
        }

        // Shooting Star: Long upper shadow >= 2x body, tiny lower shadow
        if (range3 > 0 && upperShadow3 >= 2 * body3 && lowerShadow3 <= body3 * 0.5) {
            return CandlestickPattern.SHOOTING_STAR
        }

        // Morning Star (3 candles: Bearish, Small body gap, Strong Bullish)
        if (!c1.isBullish && body2 < (c1.high - c1.low) * 0.3 && c3.isBullish && c3.close > (c1.open + c1.close) / 2) {
            return CandlestickPattern.MORNING_STAR
        }

        // Evening Star (3 candles: Bullish, Small body gap, Strong Bearish)
        if (c1.isBullish && body2 < (c1.high - c1.low) * 0.3 && !c3.isBullish && c3.close < (c1.open + c1.close) / 2) {
            return CandlestickPattern.EVENING_STAR
        }

        return CandlestickPattern.NONE
    }

    /**
     * Identifies local Support & Resistance pivots over lookback
     */
    fun calculateSupportResistance(candles: List<Candle>, lookback: Int = 20): Pair<Double, Double> {
        if (candles.isEmpty()) return Pair(0.0, 0.0)
        val slice = candles.takeLast(lookback)
        val resistance = slice.maxOf { it.high }
        val support = slice.minOf { it.low }
        return Pair(support, resistance)
    }

    /**
     * Determines current macro market regime
     */
    fun detectMarketRegime(candles: List<Candle>, snapshot: IndicatorSnapshot): String {
        if (candles.size < 25) return "Data Insufficient"
        val isEmaBullish = snapshot.ema9 > snapshot.ema21 && snapshot.ema21 > snapshot.ema50
        val isEmaBearish = snapshot.ema9 < snapshot.ema21 && snapshot.ema21 < snapshot.ema50

        return when {
            isEmaBullish && snapshot.rsi14 > 55.0 -> "Trending Bullish (Strong Momentum)"
            isEmaBearish && snapshot.rsi14 < 45.0 -> "Trending Bearish (Downward Pressure)"
            snapshot.bollinger.bandwidth > 4.5 -> "Volatile Range Expansion"
            snapshot.bollinger.bandwidth < 2.0 -> "Low Volatility Squeeze"
            else -> "Neutral Mean-Reverting Equilibrium"
        }
    }

    /**
     * Builds full indicator snapshot for current candle stream
     */
    fun computeSnapshot(candles: List<Candle>): IndicatorSnapshot {
        val closes = candles.map { it.close }
        return IndicatorSnapshot(
            rsi14 = calculateRSI(candles, 14),
            macd = calculateMACD(candles),
            bollinger = calculateBollingerBands(candles, 20, 2.0),
            atr14 = calculateATR(candles, 14),
            vwap = calculateVWAP(candles),
            ema9 = calculateEMA(closes, 9),
            ema21 = calculateEMA(closes, 21),
            ema50 = calculateEMA(closes, 50),
            ema200 = calculateEMA(closes, 200),
            detectedPattern = detectPattern(candles)
        )
    }
}

