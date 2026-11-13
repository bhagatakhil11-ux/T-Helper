package com.example.engine.strategy

import com.example.data.model.Candle
import com.example.data.model.DecisionSignal
import com.example.data.model.DecisionType
import com.example.data.model.FeedStatus
import com.example.data.model.HistoricalDatasetMetadata
import com.example.data.model.MarketInstrument
import com.example.data.model.MarketSession
import com.example.data.model.MlMovementClass
import com.example.data.model.RiskSettings
import com.example.data.model.StrategyScore
import com.example.engine.indicators.CandlestickPattern
import com.example.engine.indicators.IndicatorSnapshot
import com.example.engine.indicators.TechnicalIndicators
import com.example.engine.ml.QuantMlEngine
import java.util.UUID
import kotlin.math.abs
import kotlin.math.max

interface TradingStrategy {
    val name: String
    val weight: Double
    fun evaluate(
        symbol: String,
        candles: List<Candle>,
        snapshot: IndicatorSnapshot,
        atr: Double
    ): StrategyScore
}

class RsiMomentumStrategy(override val weight: Double = 0.25) : TradingStrategy {
    override val name: String = "RSI Momentum"

    override fun evaluate(
        symbol: String,
        candles: List<Candle>,
        snapshot: IndicatorSnapshot,
        atr: Double
    ): StrategyScore {
        val rsi = snapshot.rsi14
        val currentPrice = candles.last().close

        return when {
            rsi < 28.0 -> StrategyScore(
                strategyName = name,
                signal = DecisionType.BUY,
                score = 0.85,
                weight = weight,
                confidence = 0.85,
                entryCondition = "RSI < 28 (Extreme Oversold)",
                exitCondition = "RSI reverts above 50 or Stop Loss triggered",
                rationale = "RSI severely oversold at ${String.format("%.1f", rsi)} (< 28), high probability mean-reversion rebound."
            )
            rsi in 28.0..38.0 -> StrategyScore(
                strategyName = name,
                signal = DecisionType.BUY,
                score = 0.55,
                weight = weight,
                confidence = 0.60,
                entryCondition = "RSI in 28-38 Accumulation Zone",
                exitCondition = "Target 1:2 R:R achieved or RSI > 60",
                rationale = "RSI oversold zone at ${String.format("%.1f", rsi)}, bullish momentum accumulating."
            )
            rsi > 72.0 -> StrategyScore(
                strategyName = name,
                signal = DecisionType.SELL,
                score = -0.85,
                weight = weight,
                confidence = 0.85,
                entryCondition = "RSI > 72 (Overbought Exhaustion)",
                exitCondition = "RSI drops below 50 or Stop Loss",
                rationale = "RSI severely overbought at ${String.format("%.1f", rsi)} (> 72), risk of exhaustion breakdown."
            )
            rsi in 62.0..72.0 -> StrategyScore(
                strategyName = name,
                signal = DecisionType.SELL,
                score = -0.50,
                weight = weight,
                confidence = 0.55,
                entryCondition = "RSI approaching overbought ceiling",
                exitCondition = "Mean reversion to 50",
                rationale = "RSI elevated at ${String.format("%.1f", rsi)}, upside momentum slowing."
            )
            else -> StrategyScore(
                strategyName = name,
                signal = DecisionType.HOLD,
                score = 0.0,
                weight = weight,
                confidence = 0.40,
                entryCondition = "Neutral",
                exitCondition = "N/A",
                rationale = "RSI neutral at ${String.format("%.1f", rsi)} within 40-60 equilibrium band."
            )
        }
    }
}

class MacdTrendStrategy(override val weight: Double = 0.25) : TradingStrategy {
    override val name: String = "MACD Trend"

    override fun evaluate(
        symbol: String,
        candles: List<Candle>,
        snapshot: IndicatorSnapshot,
        atr: Double
    ): StrategyScore {
        val macd = snapshot.macd.macd
        val signal = snapshot.macd.signal
        val hist = snapshot.macd.histogram

        return when {
            hist > 0 && macd > signal && macd > 0 -> StrategyScore(
                strategyName = name,
                signal = DecisionType.BUY,
                score = 0.80,
                weight = weight,
                confidence = 0.80,
                entryCondition = "MACD line > Signal line above zero axis",
                exitCondition = "Histogram turns negative or Trailing Stop",
                rationale = "MACD positive crossover above centerline; histogram accelerating (+${String.format("%.2f", hist)})."
            )
            hist > 0 && macd > signal -> StrategyScore(
                strategyName = name,
                signal = DecisionType.BUY,
                score = 0.55,
                weight = weight,
                confidence = 0.60,
                entryCondition = "MACD Bullish Convergence crossing signal",
                exitCondition = "MACD crosses below signal",
                rationale = "MACD bullish convergence crossing signal line upward."
            )
            hist < 0 && macd < signal && macd < 0 -> StrategyScore(
                strategyName = name,
                signal = DecisionType.SELL,
                score = -0.80,
                weight = weight,
                confidence = 0.80,
                entryCondition = "MACD line < Signal line below zero axis",
                exitCondition = "Histogram turns positive or Stop hit",
                rationale = "MACD bearish breakdown below centerline; histogram expanding (-${String.format("%.2f", abs(hist))})."
            )
            hist < 0 && macd < signal -> StrategyScore(
                strategyName = name,
                signal = DecisionType.SELL,
                score = -0.55,
                weight = weight,
                confidence = 0.60,
                entryCondition = "MACD Bearish Divergence",
                exitCondition = "Exit on reverse crossover",
                rationale = "MACD negative divergence falling below signal line."
            )
            else -> StrategyScore(
                strategyName = name,
                signal = DecisionType.HOLD,
                score = 0.0,
                weight = weight,
                confidence = 0.40,
                entryCondition = "Neutral",
                exitCondition = "N/A",
                rationale = "MACD histogram flattened; waiting for clean trend convergence."
            )
        }
    }
}

class BollingerMeanReversionStrategy(override val weight: Double = 0.25) : TradingStrategy {
    override val name: String = "Bollinger Mean Reversion"

    override fun evaluate(
        symbol: String,
        candles: List<Candle>,
        snapshot: IndicatorSnapshot,
        atr: Double
    ): StrategyScore {
        val currentPrice = candles.last().close
        val bb = snapshot.bollinger

        return when {
            currentPrice <= bb.lower && snapshot.rsi14 < 35.0 -> StrategyScore(
                strategyName = name,
                signal = DecisionType.BUY,
                score = 0.90,
                weight = weight,
                confidence = 0.85,
                entryCondition = "Close <= Lower Band (₹${String.format("%.1f", bb.lower)}) + RSI < 35",
                exitCondition = "Close reaches Middle SMA Band (₹${String.format("%.1f", bb.middle)})",
                rationale = "Price pierced Lower Bollinger Band with oversold oscillator confirmation."
            )
            currentPrice <= bb.lower -> StrategyScore(
                strategyName = name,
                signal = DecisionType.BUY,
                score = 0.60,
                weight = weight,
                confidence = 0.60,
                entryCondition = "Close <= Lower Band (2.0σ)",
                exitCondition = "Target Middle Band",
                rationale = "Price testing Lower Bollinger Band (₹${String.format("%.1f", bb.lower)}), mean-reversion setup."
            )
            currentPrice >= bb.upper && snapshot.rsi14 > 65.0 -> StrategyScore(
                strategyName = name,
                signal = DecisionType.SELL,
                score = -0.90,
                weight = weight,
                confidence = 0.85,
                entryCondition = "Close >= Upper Band (₹${String.format("%.1f", bb.upper)}) + RSI > 65",
                exitCondition = "Target Middle SMA Band",
                rationale = "Price extended beyond Upper Bollinger Band with overbought RSI."
            )
            currentPrice >= bb.upper -> StrategyScore(
                strategyName = name,
                signal = DecisionType.SELL,
                score = -0.60,
                weight = weight,
                confidence = 0.60,
                entryCondition = "Close >= Upper Band",
                exitCondition = "Target Middle Band",
                rationale = "Price touching Upper Bollinger Band, elevated reversion probability."
            )
            else -> StrategyScore(
                strategyName = name,
                signal = DecisionType.HOLD,
                score = 0.0,
                weight = weight,
                confidence = 0.40,
                entryCondition = "Neutral",
                exitCondition = "N/A",
                rationale = "Price oscillating comfortably within Bollinger envelope (Bandwidth: ${String.format("%.1f", bb.bandwidth)}%)."
            )
        }
    }
}

class VolumeConfirmedTrendBreakoutStrategy(override val weight: Double = 0.25) : TradingStrategy {
    override val name: String = "Volume Trend Breakout"

    override fun evaluate(
        symbol: String,
        candles: List<Candle>,
        snapshot: IndicatorSnapshot,
        atr: Double
    ): StrategyScore {
        if (candles.size < 21) {
            return StrategyScore(name, DecisionType.HOLD, 0.0, weight, 0.0, "N/A", "N/A", "Insufficient candle history.")
        }

        val currentPrice = candles.last().close
        val lookback20 = candles.dropLast(1).takeLast(20)
        val highestHigh20 = lookback20.maxOf { it.high }
        val lowestLow20 = lookback20.minOf { it.low }
        val avgVolume20 = lookback20.map { it.volume }.average()
        val currentVolume = candles.last().volume
        val volumeSurge = if (avgVolume20 > 0) currentVolume / avgVolume20 else 1.0

        val emaBullish = snapshot.ema9 > snapshot.ema21 && snapshot.ema21 > snapshot.ema50
        val emaBearish = snapshot.ema9 < snapshot.ema21 && snapshot.ema21 < snapshot.ema50

        return when {
            currentPrice > highestHigh20 && volumeSurge >= 1.3 && emaBullish -> StrategyScore(
                strategyName = name,
                signal = DecisionType.BUY,
                score = 0.95,
                weight = weight,
                confidence = 0.90,
                entryCondition = "Breakout > 20-period High (₹${String.format("%.1f", highestHigh20)}) + Vol Surge ${String.format("%.1f", volumeSurge)}x",
                exitCondition = "Trailing ATR stop or 20-period swing low breakdown",
                rationale = "20-period High breakout with ${String.format("%.1f", volumeSurge)}x volume surge & aligned EMAs."
            )
            currentPrice > highestHigh20 -> StrategyScore(
                strategyName = name,
                signal = DecisionType.BUY,
                score = 0.65,
                weight = weight,
                confidence = 0.65,
                entryCondition = "Breakout > 20-period swing High",
                exitCondition = "Close drops below breakout bar low",
                rationale = "Resistance breakout above 20-period swing high."
            )
            currentPrice < lowestLow20 && volumeSurge >= 1.3 && emaBearish -> StrategyScore(
                strategyName = name,
                signal = DecisionType.SELL,
                score = -0.95,
                weight = weight,
                confidence = 0.90,
                entryCondition = "Breakdown < 20-period Low + Vol Surge",
                exitCondition = "Close recovers above breakdown bar high",
                rationale = "20-period Low breakdown with volume expansion & downward EMA alignment."
            )
            currentPrice < lowestLow20 -> StrategyScore(
                strategyName = name,
                signal = DecisionType.SELL,
                score = -0.65,
                weight = weight,
                confidence = 0.65,
                entryCondition = "Breakdown < 20-period swing Low",
                exitCondition = "Stop hit",
                rationale = "Support breakdown below 20-period swing low."
            )
            else -> StrategyScore(
                strategyName = name,
                signal = DecisionType.HOLD,
                score = 0.0,
                weight = weight,
                confidence = 0.40,
                entryCondition = "Neutral",
                exitCondition = "N/A",
                rationale = "Consolidating inside 20-period range [₹${String.format("%.1f", lowestLow20)} - ₹${String.format("%.1f", highestHigh20)}]."
            )
        }
    }
}

class EnsembleDecisionEngine(
    private val strategies: List<TradingStrategy> = listOf(
        RsiMomentumStrategy(weight = 0.25),
        MacdTrendStrategy(weight = 0.25),
        BollingerMeanReversionStrategy(weight = 0.25),
        VolumeConfirmedTrendBreakoutStrategy(weight = 0.25)
    ),
    val mlEngine: QuantMlEngine = QuantMlEngine()
) {
    fun evaluate(
        instrument: MarketInstrument,
        candles: List<Candle>,
        riskSettings: RiskSettings,
        availableCash: Double,
        marketSession: MarketSession = MarketSession.REGULAR_HOURS,
        timeframe: String = "1m",
        datasetMeta: HistoricalDatasetMetadata? = null
    ): DecisionSignal {
        val symbol = instrument.symbol
        val currentPrice = instrument.currentPrice

        // 1. Benchmark Index Guardrail: NIFTY 50 and BANKNIFTY are analytical benchmarks, NOT directly tradable stocks
        if (!instrument.isTradable) {
            return DecisionSignal(
                type = DecisionType.NO_TRADE,
                symbol = symbol,
                confidence = 0.0,
                entryPrice = currentPrice,
                stopLoss = 0.0,
                targetPrice = 0.0,
                suggestedQuantity = 0,
                riskRewardRatio = 0.0,
                rationale = "NO TRADE: $symbol is a Benchmark Index analytical reference, not a directly tradable equity stock per QuantAI design specifications.",
                feedStatus = instrument.feedStatus,
                marketSession = marketSession,
                marketRegime = "Index Benchmark",
                mlPrediction = null,
                strategyScores = emptyList(),
                idempotencyKey = "NO_TRADE-$symbol-${System.currentTimeMillis() / 60000}"
            )
        }

        // 2. Data sufficiency check
        if (candles.size < 25) {
            return DecisionSignal(
                type = DecisionType.NO_TRADE,
                symbol = symbol,
                confidence = 0.0,
                entryPrice = currentPrice,
                stopLoss = 0.0,
                targetPrice = 0.0,
                suggestedQuantity = 0,
                riskRewardRatio = 0.0,
                rationale = "NO TRADE: Insufficient market data. At least 25 historical candles required for statistical validation.",
                feedStatus = instrument.feedStatus,
                marketSession = marketSession,
                marketRegime = "Data Insufficient",
                mlPrediction = null,
                strategyScores = emptyList(),
                idempotencyKey = "NO_TRADE-$symbol-${System.currentTimeMillis() / 60000}"
            )
        }

        val snapshot = TechnicalIndicators.computeSnapshot(candles)
        val atr = max(snapshot.atr14, currentPrice * 0.005)
        val marketRegime = TechnicalIndicators.detectMarketRegime(candles, snapshot)

        // 3. Evaluate 4 Independent Technical Strategies
        val scores = strategies.map { it.evaluate(symbol, candles, snapshot, atr) }

        var totalWeight = 0.0
        var weightedScoreSum = 0.0
        for (score in scores) {
            totalWeight += score.weight
            weightedScoreSum += score.score * score.weight
        }
        val techCompositeScore = if (totalWeight > 0) weightedScoreSum / totalWeight else 0.0

        // 4. Evaluate Machine Learning Prediction Engine (strictly bound by symbol + timeframe + datasetId + featureVersion + modelVersion)
        val trainedReport = mlEngine.getValidationReport()
        val bindingMatches = mlEngine.isModelValidFor(symbol, timeframe, datasetMeta)
        val mlPred = if (bindingMatches) mlEngine.predict(candles) else null
        val mlActive = mlPred != null && mlPred.isTrained
        val mlScore = if (mlActive) {
            val pUp = mlPred!!.probUpward
            val pDown = mlPred.probDownward
            val pSide = mlPred.probSideways
            (pUp - pDown) * (1.0 - pSide * 0.5)
        } else 0.0

        // 5. Candlestick Pattern boost/penalty
        val pattern = snapshot.detectedPattern
        val patternBonus = when (pattern) {
            CandlestickPattern.BULLISH_ENGULFING, CandlestickPattern.MORNING_STAR, CandlestickPattern.HAMMER -> 0.10
            CandlestickPattern.BEARISH_ENGULFING, CandlestickPattern.EVENING_STAR, CandlestickPattern.SHOOTING_STAR -> -0.10
            else -> 0.0
        }

        // 6. Combine: 60% Technical Strategies + 30% Machine Learning Classifier + 10% Candlestick Pattern
        val finalCombinedScore = (techCompositeScore * 0.60 + mlScore * 0.30 + patternBonus).coerceIn(-1.0, 1.0)
        val combinedConfidence = abs(finalCombinedScore).coerceIn(0.0, 1.0)

        // Strict Decision Threshold: Requires >= 0.42 confidence AND ML non-contradiction (when ML is trained)
        val decisionType = when {
            finalCombinedScore >= 0.42 && (!mlActive || mlPred!!.predictedClass != MlMovementClass.DOWNWARD) -> DecisionType.BUY
            finalCombinedScore <= -0.42 && (!mlActive || mlPred!!.predictedClass != MlMovementClass.UPWARD) -> DecisionType.SELL
            combinedConfidence in 0.20..0.41 -> DecisionType.HOLD
            else -> DecisionType.NO_TRADE
        }

        // Calculate Stop Loss & Target Levels
        val stopLossDist = atr * riskSettings.stopLossAtrMultiplier
        val targetDist = atr * riskSettings.takeProfitAtrMultiplier

        val stopLoss: Double
        val targetPrice: Double

        if (decisionType == DecisionType.BUY) {
            stopLoss = (currentPrice - stopLossDist).coerceAtLeast(0.05)
            targetPrice = currentPrice + targetDist
        } else if (decisionType == DecisionType.SELL) {
            stopLoss = currentPrice + stopLossDist
            targetPrice = (currentPrice - targetDist).coerceAtLeast(0.05)
        } else {
            stopLoss = 0.0
            targetPrice = 0.0
        }

        val riskReward = if (stopLossDist > 0) targetDist / stopLossDist else 2.0

        // Conservative Position Sizing
        val capitalAtRisk = riskSettings.totalAllocatedCapital * (riskSettings.maxRiskPerTradePercent / 100.0)
        val suggestedQty = if (stopLossDist > 0 && decisionType == DecisionType.BUY) {
            val maxSharesByRisk = (capitalAtRisk / stopLossDist).toInt()
            val maxSharesByCash = (availableCash * 0.85 / currentPrice).toInt()
            max(1, minOf(maxSharesByRisk, maxSharesByCash))
        } else 0

        // Build auditable explanation narrative
        val rationaleBuilder = StringBuilder()
        when (decisionType) {
            DecisionType.BUY -> {
                rationaleBuilder.append("ENSEMBLE BUY SIGNAL (${String.format("%.0f", combinedConfidence * 100)}% Confidence): ")
                val bullishStrategies = scores.filter { it.score > 0 }
                rationaleBuilder.append(bullishStrategies.joinToString(" | ") { "${it.strategyName}: ${it.rationale}" })
                if (mlActive) {
                    rationaleBuilder.append(" [ML ${mlPred!!.modelVersion}: P(Up)=${String.format("%.0f", mlPred.probUpward * 100)}%, P(Down)=${String.format("%.0f", mlPred.probDownward * 100)}%]")
                } else if (trainedReport != null && !bindingMatches) {
                    rationaleBuilder.append(" [ML trained on ${trainedReport.trainedSymbol}/${trainedReport.timeframe}, skipped for $symbol/$timeframe]")
                } else {
                    rationaleBuilder.append(" [ML Untrained]")
                }
                if (pattern != CandlestickPattern.NONE) {
                    rationaleBuilder.append(" [Pattern: $pattern]")
                }
            }
            DecisionType.SELL -> {
                rationaleBuilder.append("ENSEMBLE SELL SIGNAL (${String.format("%.0f", combinedConfidence * 100)}% Confidence): ")
                val bearishStrategies = scores.filter { it.score < 0 }
                rationaleBuilder.append(bearishStrategies.joinToString(" | ") { "${it.strategyName}: ${it.rationale}" })
                if (mlActive) {
                    rationaleBuilder.append(" [ML ${mlPred!!.modelVersion}: P(Down)=${String.format("%.0f", mlPred.probDownward * 100)}%]")
                } else if (trainedReport != null && !bindingMatches) {
                    rationaleBuilder.append(" [ML trained on ${trainedReport.trainedSymbol}/${trainedReport.timeframe}, skipped for $symbol/$timeframe]")
                } else {
                    rationaleBuilder.append(" [ML Untrained]")
                }
            }
            DecisionType.HOLD -> {
                rationaleBuilder.append("HOLD (${String.format("%.0f", combinedConfidence * 100)}% Conviction): Ambiguous momentum. Market regime is $marketRegime.")
            }
            DecisionType.NO_TRADE -> {
                rationaleBuilder.append("NO TRADE: Insufficient statistical edge. Combined ensemble score (${String.format("%.2f", finalCombinedScore)}) did not meet the confidence threshold (0.42). Capital preserved.")
            }
        }

        val idempotencyKey = "DEC-${symbol}-${System.currentTimeMillis() / 60000}-${decisionType.name}"

        return DecisionSignal(
            type = decisionType,
            symbol = symbol,
            confidence = combinedConfidence,
            entryPrice = currentPrice,
            stopLoss = stopLoss,
            targetPrice = targetPrice,
            suggestedQuantity = suggestedQty,
            riskRewardRatio = riskReward,
            rationale = rationaleBuilder.toString(),
            feedStatus = instrument.feedStatus,
            marketSession = marketSession,
            marketRegime = marketRegime,
            mlPrediction = mlPred,
            strategyScores = scores,
            timestamp = System.currentTimeMillis(),
            idempotencyKey = idempotencyKey
        )
    }
}
