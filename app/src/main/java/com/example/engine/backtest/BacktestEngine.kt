package com.example.engine.backtest

import com.example.data.model.BacktestResult
import com.example.data.model.Candle
import com.example.data.model.DataOrigin
import com.example.data.model.DataQualityThresholds
import com.example.data.model.DecisionType
import com.example.data.model.Exchange
import com.example.data.model.FeedStatus
import com.example.data.model.HistoricalDatasetMetadata
import com.example.data.model.MarketInstrument
import com.example.data.model.OrderSide
import com.example.data.model.ResearchQualityStatus
import com.example.data.model.RiskSettings
import com.example.data.model.VerificationStatus
import com.example.engine.broker.CostModelConfig
import com.example.engine.broker.IndianStatutoryLevies
import com.example.engine.strategy.EnsembleDecisionEngine
import kotlin.math.abs
import kotlin.math.sqrt

class BacktestEngine(
    val decisionEngine: EnsembleDecisionEngine = EnsembleDecisionEngine()
) {
    private data class PendingEntry(
        val signalBarIndex: Int,
        val quantity: Int,
        val stopLossDistance: Double,
        val targetDistance: Double
    )

    fun runBacktest(
        symbol: String,
        candles: List<Candle>,
        initialCapital: Double = 500000.0,
        riskSettings: RiskSettings = RiskSettings(),
        slippagePercent: Double = 0.0003,
        timeframe: String = "1m",
        sourceName: String? = null,
        startBarIndex: Int = 25,
        evaluationScope: String = "Single-Series Simulation",
        datasetMeta: HistoricalDatasetMetadata? = null,
        costModelConfig: CostModelConfig = CostModelConfig(
            isIntraday = timeframe != "1D",
            slippagePercent = slippagePercent
        ),
        researchMinBarsOverrides: Map<String, Int> = DataQualityThresholds.defaultResearchMinBars
    ): BacktestResult {
        val resolvedOrigin = datasetMeta?.sourceType ?: when {
            candles.any { it.feedStatus == FeedStatus.SIMULATED } -> DataOrigin.SIMULATED
            candles.any { it.feedStatus == FeedStatus.LIVE } -> DataOrigin.BROKER_VENDOR
            else -> DataOrigin.IMPORTED_CSV
        }
        val resolvedVerification = datasetMeta?.verificationStatus ?: when (resolvedOrigin) {
            DataOrigin.SIMULATED -> VerificationStatus.SIMULATED_NOT_REAL
            DataOrigin.TEST_FIXTURE -> VerificationStatus.TEST_FIXTURE_NOT_MARKET_DATA
            DataOrigin.IMPORTED_CSV -> VerificationStatus.IMPORTED_CSV_UNVERIFIED
            DataOrigin.BROKER_VENDOR -> VerificationStatus.VERIFIED_EXCHANGE_OR_VENDOR
        }
        val dominantFeedStatus = when {
            resolvedOrigin == DataOrigin.SIMULATED || resolvedOrigin == DataOrigin.TEST_FIXTURE -> FeedStatus.SIMULATED
            candles.any { it.feedStatus == FeedStatus.HISTORICAL } -> FeedStatus.HISTORICAL
            candles.any { it.feedStatus == FeedStatus.LIVE } -> FeedStatus.LIVE
            else -> FeedStatus.SIMULATED
        }
        val resolvedSource = sourceName ?: datasetMeta?.sourceName ?: if (dominantFeedStatus == FeedStatus.HISTORICAL) {
            "Imported Historical CSV (Unverified)"
        } else {
            "Simulated Market Feed"
        }

        val mlReport = if (decisionEngine.mlEngine.isModelValidFor(symbol, timeframe, datasetMeta)) {
            decisionEngine.mlEngine.getValidationReport()
        } else null

        val researchStatus = DataQualityThresholds.evaluateQuality(candles.size, timeframe, researchMinBarsOverrides)
        val execAssumption = "Signal at Bar[t] Close -> Next-Bar Bar[t+1] Open + ${String.format("%.2f", costModelConfig.slippagePercent * 100)}% Slippage; Conservative SL-First Intrabar Exit"

        val effectiveStart = startBarIndex.coerceAtLeast(25)
        if (candles.size < 35 || effectiveStart >= candles.size) {
            return BacktestResult(
                symbol = symbol,
                totalTrades = 0,
                winningTrades = 0,
                losingTrades = 0,
                winRatePercent = 0.0,
                profitFactor = 0.0,
                maxDrawdownPercent = 0.0,
                sharpeRatio = null,
                expectancy = 0.0,
                netProfit = 0.0,
                netProfitPercent = 0.0,
                totalSlippageDeducted = 0.0,
                totalChargesDeducted = 0.0,
                equityCurve = listOf(initialCapital),
                timeframe = timeframe,
                feedStatus = dominantFeedStatus,
                sourceName = resolvedSource,
                candleCount = candles.size,
                evaluatedBarCount = 0,
                startTimestamp = candles.firstOrNull()?.timestamp ?: 0L,
                endTimestamp = candles.lastOrNull()?.timestamp ?: 0L,
                evaluationScope = evaluationScope,
                mlValidationReport = mlReport,
                datasetId = datasetMeta?.datasetId ?: "",
                datasetChecksum = datasetMeta?.sha256Checksum ?: "",
                sourceType = resolvedOrigin,
                verificationStatus = resolvedVerification,
                sharpeStatusReason = "N/A: insufficient bars for evaluation (< 35 bars or start >= size)",
                executionAssumption = execAssumption,
                costModelSummary = costModelConfig.summary(),
                researchQualityStatus = researchStatus
            )
        }

        var capital = initialCapital
        var peakCapital = initialCapital
        var maxDrawdownPct = 0.0

        val equityCurve = mutableListOf(initialCapital)
        val barReturns = mutableListOf<Double>()
        var prevMarkToMarketEquity = initialCapital

        var inPosition = false
        var positionSide = OrderSide.BUY
        var entryPrice = 0.0
        var stopLoss = 0.0
        var targetPrice = 0.0
        var quantity = 0
        var currentEntryLevies = 0.0
        var pendingEntry: PendingEntry? = null

        var winningTrades = 0
        var losingTrades = 0
        var grossProfit = 0.0
        var grossLoss = 0.0
        var totalSlippage = 0.0
        var totalCharges = 0.0

        for (i in effectiveStart until candles.size) {
            val currentCandle = candles[i]
            var enteredThisBar = false

            // 1. Execute any pending order scheduled from Bar[i - 1] Close at Bar[i] Open
            if (!inPosition && pendingEntry != null) {
                val p = pendingEntry!!
                pendingEntry = null
                val rawOpen = currentCandle.open
                val entrySlippage = rawOpen * costModelConfig.slippagePercent
                val actualEntryPrice = rawOpen + entrySlippage
                val entryTurnover = actualEntryPrice * p.quantity
                val entryLevies = IndianStatutoryLevies.calculate(entryTurnover, isBuy = true, config = costModelConfig)

                // Guard: do not open if entry levies + notional exceed cash (margin safety)
                if (entryTurnover + entryLevies.totalLevies <= capital) {
                    totalSlippage += (entrySlippage * p.quantity)
                    totalCharges += entryLevies.totalLevies

                    inPosition = true
                    enteredThisBar = true
                    positionSide = OrderSide.BUY
                    entryPrice = actualEntryPrice
                    stopLoss = (actualEntryPrice - p.stopLossDistance).coerceAtLeast(0.05)
                    targetPrice = actualEntryPrice + p.targetDistance
                    quantity = p.quantity
                    currentEntryLevies = entryLevies.totalLevies
                }
            }

            // 2. Check conservative intrabar Stop-Loss / Target exits on Bar[i]
            if (inPosition) {
                var exited = false
                var rawExitPrice = currentCandle.close

                if (positionSide == OrderSide.BUY) {
                    if (!enteredThisBar && currentCandle.open <= stopLoss) {
                        // Gap-down open below Stop Loss fills conservatively at Open
                        rawExitPrice = currentCandle.open
                        exited = true
                    } else if (currentCandle.low <= stopLoss) {
                        // Conservative SL priority if both SL and TP fall inside the bar range
                        rawExitPrice = stopLoss
                        exited = true
                    } else if (!enteredThisBar && currentCandle.open >= targetPrice) {
                        rawExitPrice = currentCandle.open
                        exited = true
                    } else if (currentCandle.high >= targetPrice) {
                        rawExitPrice = targetPrice
                        exited = true
                    }
                }

                if (exited) {
                    val exitSlippage = rawExitPrice * costModelConfig.slippagePercent
                    val actualExitPrice = (rawExitPrice - exitSlippage).coerceAtLeast(0.05)
                    val exitTurnover = actualExitPrice * quantity
                    val exitLevies = IndianStatutoryLevies.calculate(exitTurnover, isBuy = false, config = costModelConfig)

                    totalSlippage += (exitSlippage * quantity)
                    totalCharges += exitLevies.totalLevies

                    val pnl = ((actualExitPrice - entryPrice) * quantity) - currentEntryLevies - exitLevies.totalLevies
                    capital += pnl

                    if (pnl > 0) {
                        winningTrades++
                        grossProfit += pnl
                    } else {
                        losingTrades++
                        grossLoss += abs(pnl)
                    }

                    inPosition = false
                    currentEntryLevies = 0.0
                }
            }

            // 3. Evaluate mark-to-market equity & intrabar drawdown (includes unrealized P&L!)
            val unrealizedAtClose = if (inPosition) {
                ((currentCandle.close - entryPrice) * quantity) - currentEntryLevies
            } else 0.0
            val unrealizedWorstLow = if (inPosition) {
                ((currentCandle.low - entryPrice) * quantity) - currentEntryLevies
            } else 0.0

            val barCloseMtmEquity = capital + unrealizedAtClose
            val barWorstMtmEquity = capital + unrealizedWorstLow

            if (barCloseMtmEquity > peakCapital) {
                peakCapital = barCloseMtmEquity
            }
            if (peakCapital > 0.0) {
                val intrabarDd = ((peakCapital - barWorstMtmEquity) / peakCapital) * 100.0
                if (intrabarDd > maxDrawdownPct) maxDrawdownPct = intrabarDd
            }

            if (prevMarkToMarketEquity > 0.0) {
                barReturns.add((barCloseMtmEquity - prevMarkToMarketEquity) / prevMarkToMarketEquity)
            }
            prevMarkToMarketEquity = barCloseMtmEquity
            equityCurve.add(barCloseMtmEquity)

            // 4. Generate signal strictly on Bar[0..i] at Bar[i] Close for execution at Bar[i + 1] Open
            if (!inPosition && pendingEntry == null && i < candles.size - 1) {
                val historicalSubWindow = candles.take(i + 1)
                val dummyInstrument = MarketInstrument(
                    symbol = symbol,
                    name = symbol,
                    exchange = Exchange.NSE,
                    isTradable = true,
                    currentPrice = currentCandle.close,
                    openPrice = currentCandle.open,
                    high24h = currentCandle.high,
                    low24h = currentCandle.low,
                    changePercent = 0.0,
                    volume = currentCandle.volume,
                    feedStatus = currentCandle.feedStatus
                )

                val signal = decisionEngine.evaluate(
                    instrument = dummyInstrument,
                    candles = historicalSubWindow,
                    riskSettings = riskSettings,
                    availableCash = capital,
                    timeframe = timeframe,
                    datasetMeta = datasetMeta
                )
                if (signal.type == DecisionType.BUY && signal.confidence >= 0.42 && signal.suggestedQuantity > 0) {
                    val slDist = (signal.entryPrice - signal.stopLoss).coerceAtLeast(currentCandle.close * 0.005)
                    val tpDist = (signal.targetPrice - signal.entryPrice).coerceAtLeast(slDist * 1.5)
                    pendingEntry = PendingEntry(
                        signalBarIndex = i,
                        quantity = signal.suggestedQuantity,
                        stopLossDistance = slDist,
                        targetDistance = tpDist
                    )
                }
            }
        }

        // Liquidate any open position at final candle Close with full exit slippage & levies
        if (inPosition) {
            val lastClose = candles.last().close
            val exitSlippage = lastClose * costModelConfig.slippagePercent
            val actualExitPrice = (lastClose - exitSlippage).coerceAtLeast(0.05)
            val exitTurnover = actualExitPrice * quantity
            val exitLevies = IndianStatutoryLevies.calculate(exitTurnover, isBuy = false, config = costModelConfig)

            totalSlippage += (exitSlippage * quantity)
            totalCharges += exitLevies.totalLevies

            val pnl = ((actualExitPrice - entryPrice) * quantity) - currentEntryLevies - exitLevies.totalLevies
            capital += pnl
            if (pnl > 0) {
                winningTrades++
                grossProfit += pnl
            } else {
                losingTrades++
                grossLoss += abs(pnl)
            }
            equityCurve[equityCurve.lastIndex] = capital
            if (capital < peakCapital && peakCapital > 0.0) {
                val dd = ((peakCapital - capital) / peakCapital) * 100.0
                if (dd > maxDrawdownPct) maxDrawdownPct = dd
            }
        }

        val totalTrades = winningTrades + losingTrades
        val evaluatedBars = candles.size - effectiveStart
        val winRate = if (totalTrades > 0) (winningTrades.toDouble() / totalTrades) * 100.0 else 0.0
        val profitFactor = if (grossLoss > 0) grossProfit / grossLoss else if (grossProfit > 0) 9.99 else 0.0
        val netProfit = capital - initialCapital
        val netProfitPct = (netProfit / initialCapital) * 100.0

        val avgWin = if (winningTrades > 0) grossProfit / winningTrades else 0.0
        val avgLoss = if (losingTrades > 0) grossLoss / losingTrades else 0.0
        val expectancy = if (totalTrades > 0) ((winRate / 100.0) * avgWin) - ((1.0 - (winRate / 100.0)) * avgLoss) else 0.0

        val barsPerYear = when (timeframe) {
            "1D" -> 252.0
            "15m" -> 252.0 * 25.0
            "5m" -> 252.0 * 75.0
            else -> 252.0 * 375.0
        }

        val (sharpeRatio, sharpeReason) = when {
            evaluatedBars < 30 || totalTrades < 5 ->
                null to "N/A: statistically invalid (requires >= 30 bars & >= 5 closed trades; got $evaluatedBars bars, $totalTrades trades)"
            barReturns.size < 2 ->
                null to "N/A: insufficient return observations"
            else -> {
                val meanReturn = barReturns.average()
                val variance = barReturns.sumOf { (it - meanReturn) * (it - meanReturn) } / (barReturns.size - 1)
                val stdDev = sqrt(variance)
                if (stdDev <= 1e-12) {
                    null to "N/A: zero return standard deviation"
                } else {
                    ((meanReturn / stdDev) * sqrt(barsPerYear)) to "Annualized MTM bar Sharpe (bars/yr=${barsPerYear.toInt()})"
                }
            }
        }

        return BacktestResult(
            symbol = symbol,
            totalTrades = totalTrades,
            winningTrades = winningTrades,
            losingTrades = losingTrades,
            winRatePercent = winRate,
            profitFactor = profitFactor,
            maxDrawdownPercent = maxDrawdownPct,
            sharpeRatio = sharpeRatio,
            expectancy = expectancy,
            netProfit = netProfit,
            netProfitPercent = netProfitPct,
            totalSlippageDeducted = totalSlippage,
            totalChargesDeducted = totalCharges,
            equityCurve = equityCurve,
            timeframe = timeframe,
            feedStatus = dominantFeedStatus,
            sourceName = resolvedSource,
            candleCount = candles.size,
            evaluatedBarCount = evaluatedBars,
            startTimestamp = candles[effectiveStart].timestamp,
            endTimestamp = candles.last().timestamp,
            evaluationScope = evaluationScope,
            mlValidationReport = mlReport,
            datasetId = datasetMeta?.datasetId ?: "",
            datasetChecksum = datasetMeta?.sha256Checksum ?: "",
            sourceType = resolvedOrigin,
            verificationStatus = resolvedVerification,
            sharpeStatusReason = sharpeReason,
            executionAssumption = execAssumption,
            costModelSummary = costModelConfig.summary(),
            researchQualityStatus = researchStatus
        )
    }
}
