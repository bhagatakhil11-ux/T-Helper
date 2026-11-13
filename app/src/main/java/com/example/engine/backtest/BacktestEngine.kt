package com.example.engine.backtest

import com.example.data.model.BacktestResult
import com.example.data.model.Candle
import com.example.data.model.DecisionType
import com.example.data.model.Exchange
import com.example.data.model.FeedStatus
import com.example.data.model.MarketInstrument
import com.example.data.model.OrderSide
import com.example.data.model.RiskSettings
import com.example.engine.broker.IndianStatutoryLevies
import com.example.engine.strategy.EnsembleDecisionEngine
import kotlin.math.abs
import kotlin.math.sqrt

class BacktestEngine(
    val decisionEngine: EnsembleDecisionEngine = EnsembleDecisionEngine()
) {
    fun runBacktest(
        symbol: String,
        candles: List<Candle>,
        initialCapital: Double = 500000.0,
        riskSettings: RiskSettings = RiskSettings(),
        slippagePercent: Double = 0.0003, // 0.03% slippage per fill
        timeframe: String = "1m",
        sourceName: String? = null,
        startBarIndex: Int = 25,
        evaluationScope: String = "Full Series"
    ): BacktestResult {
        val dominantFeedStatus = when {
            candles.any { it.feedStatus == FeedStatus.SIMULATED } -> FeedStatus.SIMULATED
            candles.any { it.feedStatus == FeedStatus.HISTORICAL } -> FeedStatus.HISTORICAL
            candles.any { it.feedStatus == FeedStatus.LIVE } -> FeedStatus.LIVE
            else -> FeedStatus.SIMULATED
        }
        val resolvedSource = sourceName ?: if (dominantFeedStatus == FeedStatus.HISTORICAL) {
            "Imported Historical CSV"
        } else {
            "Simulated Market Feed"
        }
        val mlReport = decisionEngine.mlEngine.getValidationReport()?.takeIf {
            it.trainedSymbol == symbol && it.timeframe == timeframe
        }

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
                sharpeRatio = 0.0,
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
                mlValidationReport = mlReport
            )
        }

        var capital = initialCapital
        var peakCapital = initialCapital
        var maxDrawdownPct = 0.0

        val equityCurve = mutableListOf(initialCapital)
        val tradeReturns = mutableListOf<Double>()

        var inPosition = false
        var positionSide = OrderSide.BUY
        var entryPrice = 0.0
        var stopLoss = 0.0
        var targetPrice = 0.0
        var quantity = 0
        var currentEntryLevies = 0.0

        var winningTrades = 0
        var losingTrades = 0
        var grossProfit = 0.0
        var grossLoss = 0.0

        var totalSlippage = 0.0
        var totalCharges = 0.0

        // Walk-forward simulation avoiding look-ahead bias
        for (i in effectiveStart until candles.size) {
            val historicalSubWindow = candles.take(i + 1)
            val currentCandle = historicalSubWindow.last()
            val currentPrice = currentCandle.close

            if (inPosition) {
                var exited = false
                var rawExitPrice = currentPrice

                if (positionSide == OrderSide.BUY) {
                    if (currentCandle.low <= stopLoss) {
                        rawExitPrice = stopLoss
                        exited = true
                    } else if (currentCandle.high >= targetPrice) {
                        rawExitPrice = targetPrice
                        exited = true
                    }
                }

                if (exited) {
                    // Apply exit slippage and exit statutory levies
                    val exitSlippage = rawExitPrice * slippagePercent
                    val actualExitPrice = rawExitPrice - exitSlippage
                    val exitTurnover = actualExitPrice * quantity
                    val exitLevies = IndianStatutoryLevies.calculate(exitTurnover, isBuy = false)

                    totalSlippage += (exitSlippage * quantity)
                    totalCharges += exitLevies.totalLevies

                    val pnl = ((actualExitPrice - entryPrice) * quantity) - currentEntryLevies - exitLevies.totalLevies
                    capital += pnl
                    tradeReturns.add(pnl)

                    if (pnl > 0) {
                        winningTrades++
                        grossProfit += pnl
                    } else {
                        losingTrades++
                        grossLoss += abs(pnl)
                    }

                    inPosition = false
                    currentEntryLevies = 0.0
                    equityCurve.add(capital)

                    if (capital > peakCapital) {
                        peakCapital = capital
                    } else if (peakCapital > 0) {
                        val dd = ((peakCapital - capital) / peakCapital) * 100.0
                        if (dd > maxDrawdownPct) maxDrawdownPct = dd
                    }
                }
            } else {
                // Check entry signal strictly from historicalSubWindow (no lookahead)
                val dummyInstrument = MarketInstrument(
                    symbol = symbol,
                    name = symbol,
                    exchange = Exchange.NSE,
                    isTradable = true,
                    currentPrice = currentPrice,
                    openPrice = currentCandle.open,
                    high24h = currentCandle.high,
                    low24h = currentCandle.low,
                    changePercent = 0.0,
                    volume = currentCandle.volume,
                    feedStatus = currentCandle.feedStatus
                )

                val signal = decisionEngine.evaluate(dummyInstrument, historicalSubWindow, riskSettings, capital)
                if (signal.type == DecisionType.BUY && signal.confidence >= 0.42 && signal.suggestedQuantity > 0) {
                    // Apply entry slippage and entry statutory charges
                    val entrySlippage = currentPrice * slippagePercent
                    val actualEntryPrice = currentPrice + entrySlippage
                    val entryTurnover = actualEntryPrice * signal.suggestedQuantity
                    val entryLevies = IndianStatutoryLevies.calculate(entryTurnover, isBuy = true)

                    totalSlippage += (entrySlippage * signal.suggestedQuantity)
                    totalCharges += entryLevies.totalLevies

                    inPosition = true
                    positionSide = OrderSide.BUY
                    entryPrice = actualEntryPrice
                    stopLoss = signal.stopLoss
                    targetPrice = signal.targetPrice
                    quantity = signal.suggestedQuantity
                    currentEntryLevies = entryLevies.totalLevies
                }
            }
        }

        // Close any lingering position at final candle
        if (inPosition) {
            val lastClose = candles.last().close
            val exitSlippage = lastClose * slippagePercent
            val actualExitPrice = lastClose - exitSlippage
            val exitTurnover = actualExitPrice * quantity
            val exitLevies = IndianStatutoryLevies.calculate(exitTurnover, isBuy = false)

            totalSlippage += (exitSlippage * quantity)
            totalCharges += exitLevies.totalLevies

            val pnl = ((actualExitPrice - entryPrice) * quantity) - currentEntryLevies - exitLevies.totalLevies
            capital += pnl
            tradeReturns.add(pnl)
            if (pnl >= 0) {
                winningTrades++
                grossProfit += pnl
            } else {
                losingTrades++
                grossLoss += abs(pnl)
            }
            equityCurve.add(capital)
            if (capital < peakCapital && peakCapital > 0) {
                val dd = ((peakCapital - capital) / peakCapital) * 100.0
                if (dd > maxDrawdownPct) maxDrawdownPct = dd
            }
        }

        val totalTrades = winningTrades + losingTrades
        val winRate = if (totalTrades > 0) (winningTrades.toDouble() / totalTrades) * 100.0 else 0.0
        val profitFactor = if (grossLoss > 0) grossProfit / grossLoss else if (grossProfit > 0) 9.99 else 0.0
        val netProfit = capital - initialCapital
        val netProfitPct = (netProfit / initialCapital) * 100.0

        val avgWin = if (winningTrades > 0) grossProfit / winningTrades else 0.0
        val avgLoss = if (losingTrades > 0) grossLoss / losingTrades else 0.0
        val expectancy = if (totalTrades > 0) ((winRate / 100.0) * avgWin) - ((1.0 - (winRate / 100.0)) * avgLoss) else 0.0

        // Annualized Sharpe Ratio calculation (never fabricated when <= 2 trades)
        val sharpeRatio = if (tradeReturns.size > 2) {
            val meanReturn = tradeReturns.average()
            val variance = tradeReturns.map { (it - meanReturn) * (it - meanReturn) }.sum() / tradeReturns.size
            val stdDev = sqrt(variance)
            if (stdDev > 0) (meanReturn / stdDev) * sqrt(252.0) else 0.0
        } else 0.0

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
            evaluatedBarCount = candles.size - effectiveStart,
            startTimestamp = candles[effectiveStart].timestamp,
            endTimestamp = candles.last().timestamp,
            evaluationScope = evaluationScope,
            mlValidationReport = mlReport
        )
    }
}
