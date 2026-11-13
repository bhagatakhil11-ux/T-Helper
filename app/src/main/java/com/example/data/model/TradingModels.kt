package com.example.data.model

import java.text.NumberFormat
import java.util.Locale

enum class Exchange {
    NSE, BSE
}

enum class FeedStatus {
    LIVE,
    HISTORICAL,
    DELAYED,
    SIMULATED,
    DISCONNECTED
}

enum class MarketSession {
    PRE_OPEN,        // 09:00 - 09:15 IST
    REGULAR_HOURS,   // 09:15 - 15:30 IST
    POST_CLOSE,      // 15:30 - 16:00 IST
    CLOSED           // Night / Weekend / Holiday
}

enum class OrderSide {
    BUY, SELL
}

enum class OrderType {
    MARKET, LIMIT, STOP_LOSS
}

enum class OrderStatus {
    PENDING, FILLED, REJECTED, CANCELLED
}

enum class DecisionType {
    BUY, SELL, HOLD, NO_TRADE
}

enum class AgentStatus {
    STOPPED, RUNNING, ANALYZING, EXECUTING, EMERGENCY_HALTED
}

enum class MlMovementClass {
    UPWARD, DOWNWARD, SIDEWAYS
}

data class MarketInstrument(
    val symbol: String,
    val name: String,
    val exchange: Exchange = Exchange.NSE,
    val isTradable: Boolean = true, // False for Benchmark Indices (NIFTY 50, BANKNIFTY)
    val currentPrice: Double,
    val openPrice: Double,
    val high24h: Double,
    val low24h: Double,
    val changePercent: Double,
    val volume: Long,
    val lotSize: Int = 1,
    val tickSize: Double = 0.05,
    val bidPrice: Double = currentPrice - 0.05,
    val askPrice: Double = currentPrice + 0.05,
    val feedStatus: FeedStatus = FeedStatus.SIMULATED,
    val lastUpdated: Long = System.currentTimeMillis()
) {
    val changeAmount: Double get() = currentPrice - openPrice
    val isPositive: Boolean get() = changePercent >= 0

    fun formatPrice(): String = formatInr(currentPrice)
}

data class Candle(
    val timestamp: Long,
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
    val volume: Long,
    val feedStatus: FeedStatus = FeedStatus.SIMULATED
) {
    val isBullish: Boolean get() = close >= open
}

data class Order(
    val id: String,
    val idempotencyKey: String = id,
    val symbol: String,
    val side: OrderSide,
    val type: OrderType,
    val quantity: Int,
    val price: Double,
    val limitPrice: Double? = null,
    val stopLoss: Double? = null,
    val targetPrice: Double? = null,
    val status: OrderStatus = OrderStatus.PENDING,
    val filledPrice: Double = 0.0,
    val filledAt: Long? = null,
    val slippageDeducted: Double = 0.0,
    val statutoryCharges: Double = 0.0,
    val reason: String = "",
    val brokerOrderId: String = ""
)

data class Position(
    val id: String,
    val symbol: String,
    val side: OrderSide,
    val quantity: Int,
    val entryPrice: Double,
    val currentPrice: Double,
    val stopLoss: Double,
    val targetPrice: Double,
    val openedAt: Long,
    val highestPriceSeen: Double = currentPrice,
    val lowestPriceSeen: Double = currentPrice
) {
    val unrealizedPnl: Double
        get() = if (side == OrderSide.BUY) {
            (currentPrice - entryPrice) * quantity
        } else {
            (entryPrice - currentPrice) * quantity
        }

    val unrealizedPnlPercent: Double
        get() = if (entryPrice > 0) (unrealizedPnl / (entryPrice * quantity)) * 100.0 else 0.0

    val isProfitable: Boolean get() = unrealizedPnl >= 0
}

data class StrategyScore(
    val strategyName: String,
    val signal: DecisionType,
    val score: Double, // -1.0 (strong sell) to +1.0 (strong buy)
    val weight: Double,
    val confidence: Double,
    val entryCondition: String,
    val exitCondition: String,
    val rationale: String
)

data class MlClassMetrics(
    val movementClass: MlMovementClass,
    val support: Int,
    val precision: Double,
    val recall: Double,
    val f1Score: Double
)

data class MlValidationReport(
    val modelVersion: String,
    val trainedSymbol: String,
    val timeframe: String,
    val trainedAt: Long,
    val isSyntheticData: Boolean,
    val forwardHorizon: Int,
    val costFriction: Double,
    val totalSamples: Int,
    val trainSamples: Int,
    val validationSamples: Int,
    val testSamples: Int,
    val purgedSamples: Int,
    val trainClassCounts: Map<MlMovementClass, Int>,
    val testClassCounts: Map<MlMovementClass, Int>,
    val testAccuracy: Double,
    val baselineMajorityAccuracy: Double,
    val baselineMomentumAccuracy: Double,
    val macroF1: Double,
    val testLogLoss: Double,
    val testBrierScore: Double,
    val walkForwardAccuracy: Double,
    val walkForwardFolds: Int,
    val classMetrics: List<MlClassMetrics>,
    val confusionMatrix: List<List<Int>>, // 3x3 rows=Actual(UP,DOWN,SIDEWAYS), cols=Predicted(UP,DOWN,SIDEWAYS)
    val featureImportances: Map<String, Double>,
    val passedValidationGate: Boolean,
    val validationGateReason: String,
    val testStartCandleIndex: Int = -1
)

data class MlPrediction(
    val modelVersion: String = "QuantAI-RF-v2.0",
    val isTrained: Boolean = false,
    val isSyntheticData: Boolean = true,
    val probUpward: Double,
    val probDownward: Double,
    val probSideways: Double,
    val predictedClass: MlMovementClass,
    val confidence: Double,
    val trainingSampleCount: Int,
    val outOfSampleAccuracy: Double,
    val featureImportances: Map<String, Double> = emptyMap(),
    val evaluatedAt: Long = System.currentTimeMillis()
)

data class DecisionSignal(
    val type: DecisionType,
    val symbol: String,
    val confidence: Double, // 0.0 to 1.0
    val entryPrice: Double,
    val stopLoss: Double,
    val targetPrice: Double,
    val suggestedQuantity: Int,
    val riskRewardRatio: Double,
    val rationale: String,
    val feedStatus: FeedStatus = FeedStatus.SIMULATED,
    val marketSession: MarketSession = MarketSession.REGULAR_HOURS,
    val marketRegime: String = "Neutral Equilibrium",
    val mlPrediction: MlPrediction? = null,
    val strategyScores: List<StrategyScore> = emptyList(),
    val timestamp: Long = System.currentTimeMillis(),
    val idempotencyKey: String = ""
)

data class Portfolio(
    val totalCapital: Double = 500000.0, // Default ₹5 Lakhs
    val availableCash: Double = 500000.0,
    val investedValue: Double = 0.0,
    val dayRealizedPnl: Double = 0.0,
    val totalRealizedPnl: Double = 0.0,
    val startingDayCapital: Double = 500000.0,
    val consecutiveLossCount: Int = 0,
    val lastLossTimestamp: Long = 0L
) {
    val currentNetWorth: Double get() = availableCash + investedValue
    val dayTotalPnl: Double get() = currentNetWorth - startingDayCapital
    val dayTotalPnlPercent: Double
        get() = if (startingDayCapital > 0) (dayTotalPnl / startingDayCapital) * 100.0 else 0.0
}

data class RiskSettings(
    val totalAllocatedCapital: Double = 500000.0,
    val maxRiskPerTradePercent: Double = 0.5, // Conservative default: 0.5%
    val maxDailyDrawdownPercent: Double = 2.0, // Conservative default: 2.0%
    val maxOpenPositions: Int = 4,
    val maxPortfolioExposurePercent: Double = 50.0, // Max 50% capital committed at once
    val maxConsecutiveLosses: Int = 3, // Cooldown after 3 consecutive losses
    val stopLossAtrMultiplier: Double = 1.8,
    val takeProfitAtrMultiplier: Double = 3.6, // 1:2 Risk/Reward
    val emergencyHalt: Boolean = false,
    val trailingStopEnabled: Boolean = true
)

data class BacktestResult(
    val symbol: String,
    val totalTrades: Int,
    val winningTrades: Int,
    val losingTrades: Int,
    val winRatePercent: Double,
    val profitFactor: Double,
    val maxDrawdownPercent: Double,
    val sharpeRatio: Double,
    val expectancy: Double,
    val netProfit: Double,
    val netProfitPercent: Double,
    val totalSlippageDeducted: Double,
    val totalChargesDeducted: Double,
    val equityCurve: List<Double>,
    val timeframe: String = "1m",
    val feedStatus: FeedStatus = FeedStatus.SIMULATED,
    val sourceName: String = "Simulated Feed",
    val candleCount: Int = 0,
    val evaluatedBarCount: Int = 0,
    val startTimestamp: Long = 0L,
    val endTimestamp: Long = 0L,
    val evaluationScope: String = "Full Series",
    val mlValidationReport: MlValidationReport? = null
)

data class HistoricalDatasetMetadata(
    val symbol: String,
    val timeframe: String, // "1m", "5m", "15m", "1D"
    val sourceName: String,
    val feedStatus: FeedStatus = FeedStatus.HISTORICAL,
    val importedAt: Long,
    val candleCount: Int,
    val startTimestamp: Long,
    val endTimestamp: Long,
    val duplicateCount: Int = 0,
    val invalidRowCount: Int = 0,
    val missingCandleCount: Int = 0,
    val warnings: List<String> = emptyList()
)

data class HistoricalImportResult(
    val success: Boolean,
    val symbol: String,
    val timeframe: String,
    val metadata: HistoricalDatasetMetadata? = null,
    val candles: List<Candle> = emptyList(),
    val totalRowsRead: Int = 0,
    val acceptedCount: Int = 0,
    val rejectedRowCount: Int = 0,
    val duplicateCount: Int = 0,
    val missingCandleCount: Int = 0,
    val warnings: List<String> = emptyList(),
    val errors: List<String> = emptyList()
)

data class BatchHistoricalImportResult(
    val success: Boolean,
    val importedDatasets: List<HistoricalImportResult> = emptyList(),
    val totalRowsRead: Int = 0,
    val totalAcceptedCandles: Int = 0,
    val totalRejectedRows: Int = 0,
    val totalDuplicates: Int = 0,
    val totalMissingCandles: Int = 0,
    val warnings: List<String> = emptyList(),
    val errors: List<String> = emptyList()
)

data class AuditLogEntry(
    val id: String,
    val timestamp: Long,
    val symbol: String,
    val action: String,
    val details: String,
    val isDecision: Boolean = false,
    val feedStatus: FeedStatus = FeedStatus.SIMULATED
)

fun formatInr(amount: Double): String {
    val format = NumberFormat.getCurrencyInstance(Locale("en", "IN"))
    return format.format(amount)
}
