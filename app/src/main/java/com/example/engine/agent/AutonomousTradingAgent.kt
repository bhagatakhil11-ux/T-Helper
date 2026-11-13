package com.example.engine.agent

import com.example.data.model.AgentStatus
import com.example.data.model.AuditLogEntry
import com.example.data.model.DecisionSignal
import com.example.data.model.DecisionType
import com.example.data.model.FeedStatus
import com.example.data.model.MarketInstrument
import com.example.data.model.MarketSession
import com.example.data.model.Order
import com.example.data.model.OrderSide
import com.example.data.model.OrderStatus
import com.example.data.model.OrderType
import com.example.data.model.Portfolio
import com.example.data.model.Position
import com.example.data.model.RiskSettings
import com.example.data.repository.MarketRepository
import com.example.data.repository.MarketSessionDetector
import com.example.engine.broker.BrokerAdapter
import com.example.engine.broker.PaperTradingBroker
import com.example.engine.risk.RiskManagementEngine
import com.example.engine.strategy.EnsembleDecisionEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.UUID
import kotlin.math.abs

class AutonomousTradingAgent(
    private val marketRepository: MarketRepository,
    var broker: BrokerAdapter = PaperTradingBroker(),
    private val decisionEngine: EnsembleDecisionEngine = EnsembleDecisionEngine(),
    private val riskEngine: RiskManagementEngine = RiskManagementEngine(),
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default)
) {
    private val _agentStatus = MutableStateFlow(AgentStatus.RUNNING)
    val agentStatus: StateFlow<AgentStatus> = _agentStatus.asStateFlow()

    private val _portfolio = MutableStateFlow(Portfolio())
    val portfolio: StateFlow<Portfolio> = _portfolio.asStateFlow()

    private val _positions = MutableStateFlow<List<Position>>(emptyList())
    val positions: StateFlow<List<Position>> = _positions.asStateFlow()

    private val _orders = MutableStateFlow<List<Order>>(emptyList())
    val orders: StateFlow<List<Order>> = _orders.asStateFlow()

    private val _auditLogs = MutableStateFlow<List<AuditLogEntry>>(emptyList())
    val auditLogs: StateFlow<List<AuditLogEntry>> = _auditLogs.asStateFlow()

    private val _lastDecision = MutableStateFlow<DecisionSignal?>(null)
    val lastDecision: StateFlow<DecisionSignal?> = _lastDecision.asStateFlow()

    private val _riskSettings = MutableStateFlow(RiskSettings())
    val riskSettings: StateFlow<RiskSettings> = _riskSettings.asStateFlow()

    private val _currentSession = MutableStateFlow(MarketSessionDetector.getCurrentSession())
    val currentSession: StateFlow<MarketSession> = _currentSession.asStateFlow()

    private var loopJob: Job? = null
    private var cycleCount = 0

    init {
        startAgentLoop()
    }

    fun startAgentLoop() {
        if (loopJob?.isActive == true) return
        _agentStatus.value = AgentStatus.RUNNING
        addLog("SYSTEM", "Autonomous agent loop initiated. Mode: ${broker.brokerName}", feedStatus = marketRepository.feedStatus.value)

        loopJob = scope.launch {
            while (isActive) {
                try {
                    runAgentCycle()
                } catch (e: Exception) {
                    addLog("ERROR", "Agent cycle exception: ${e.message}")
                }
                delay(2500) // 2.5-second tick interval
            }
        }
    }

    fun stopAgent() {
        _agentStatus.value = AgentStatus.STOPPED
        addLog("SYSTEM", "Autonomous agent paused by user.")
    }

    fun resumeAgent() {
        if (_riskSettings.value.emergencyHalt) {
            addLog("RISK", "Cannot resume while Emergency Kill Switch is active.")
            return
        }
        _agentStatus.value = AgentStatus.RUNNING
        addLog("SYSTEM", "Autonomous agent resumed.")
    }

    fun triggerEmergencyKillSwitch() {
        _riskSettings.value = _riskSettings.value.copy(emergencyHalt = true)
        _agentStatus.value = AgentStatus.EMERGENCY_HALTED
        addLog("EMERGENCY", "KILL SWITCH TRIGGERED! Halting agent and initiating emergency square-off.")

        scope.launch {
            val currentPositions = _positions.value.toList()
            for (pos in currentPositions) {
                squareOffPosition(pos, "Emergency Kill Switch Square-Off")
            }
        }
    }

    fun resetKillSwitch() {
        _riskSettings.value = _riskSettings.value.copy(emergencyHalt = false)
        _agentStatus.value = AgentStatus.STOPPED
        addLog("SYSTEM", "Emergency kill switch disarmed. Agent in paused state.")
    }

    fun updateRiskSettings(newSettings: RiskSettings) {
        _riskSettings.value = newSettings
        addLog("RISK", "Risk parameters updated: Risk/Trade=${newSettings.maxRiskPerTradePercent}%, MaxDD=${newSettings.maxDailyDrawdownPercent}%.")
    }

    private suspend fun runAgentCycle() {
        cycleCount++
        _currentSession.value = MarketSessionDetector.getCurrentSession()

        // 1. Ingest market ticks
        val ticks = marketRepository.tickMarket()

        // 2. Data Staleness Guardrail
        if (marketRepository.isFeedStale()) {
            addLog("FEED", "Market data feed is stale (> 3 mins). Order execution suspended.")
            return
        }

        // 3. Update existing open positions with new prices and evaluate trailing stops
        updatePositionsWithTicks(ticks)

        // 4. If agent is paused or halted, skip new trade evaluation
        if (_agentStatus.value != AgentStatus.RUNNING) return

        // 5. Select tradable instrument (filter out benchmark indices like NIFTY 50 and BANKNIFTY)
        val tradableInstruments = marketRepository.instruments.value.filter { it.isTradable }
        if (tradableInstruments.isEmpty()) return

        val targetInstrument = tradableInstruments[cycleCount % tradableInstruments.size]
        val symbol = targetInstrument.symbol
        val candles = marketRepository.getCandles(symbol)
        val currentPrice = ticks[symbol] ?: targetInstrument.currentPrice

        _agentStatus.value = AgentStatus.ANALYZING

        // 6. Evaluate Strategy Ensemble with Machine Learning
        val signal = decisionEngine.evaluate(
            instrument = targetInstrument,
            candles = candles,
            riskSettings = _riskSettings.value,
            availableCash = _portfolio.value.availableCash,
            marketSession = _currentSession.value
        )
        _lastDecision.value = signal

        if (signal.type == DecisionType.BUY && signal.suggestedQuantity > 0) {
            val proposedOrder = Order(
                id = UUID.randomUUID().toString(),
                idempotencyKey = signal.idempotencyKey,
                symbol = symbol,
                side = OrderSide.BUY,
                type = OrderType.MARKET,
                quantity = signal.suggestedQuantity,
                price = currentPrice,
                stopLoss = signal.stopLoss,
                targetPrice = signal.targetPrice,
                reason = signal.rationale
            )

            // Independent Risk Engine validation
            val riskCheck = riskEngine.validateOrder(
                order = proposedOrder,
                portfolio = _portfolio.value,
                openPositions = _positions.value,
                riskSettings = _riskSettings.value
            )

            if (riskCheck.isApproved) {
                _agentStatus.value = AgentStatus.EXECUTING
                executeApprovedOrder(
                    order = proposedOrder.copy(quantity = riskCheck.sanitizedQuantity),
                    instrument = targetInstrument,
                    signal = signal
                )
            } else {
                addLog(symbol, "TRADE REJECTED BY RISK GUARD: ${riskCheck.rejectionReason}", isDecision = true)
            }
        } else if (signal.type == DecisionType.SELL) {
            val holding = _positions.value.find { it.symbol == symbol && it.side == OrderSide.BUY }
            if (holding != null) {
                squareOffPosition(holding, "Ensemble Sell Signal (${String.format("%.0f", signal.confidence * 100)}% Conf)")
            }
        }

        _agentStatus.value = AgentStatus.RUNNING
    }

    private suspend fun executeApprovedOrder(
        order: Order,
        instrument: MarketInstrument,
        signal: DecisionSignal
    ) {
        val result = broker.placeOrder(
            order = order,
            currentMarketPrice = instrument.currentPrice,
            bidPrice = instrument.bidPrice,
            askPrice = instrument.askPrice
        )

        result.onSuccess { filledOrder ->
            _orders.value = (listOf(filledOrder) + _orders.value).take(100)

            val newPosition = Position(
                id = UUID.randomUUID().toString(),
                symbol = filledOrder.symbol,
                side = filledOrder.side,
                quantity = filledOrder.quantity,
                entryPrice = filledOrder.filledPrice,
                currentPrice = filledOrder.filledPrice,
                stopLoss = signal.stopLoss,
                targetPrice = signal.targetPrice,
                openedAt = System.currentTimeMillis()
            )
            _positions.value = _positions.value + newPosition

            recalculatePortfolio()
            addLog(
                symbol = filledOrder.symbol,
                details = "ORDER EXECUTED: Bought ${filledOrder.quantity} @ ₹${String.format("%.2f", filledOrder.filledPrice)} (Slippage: ₹${String.format("%.2f", filledOrder.slippageDeducted)}, Levies: ₹${String.format("%.2f", filledOrder.statutoryCharges)}) | SL: ₹${String.format("%.2f", signal.stopLoss)}, Target: ₹${String.format("%.2f", signal.targetPrice)}",
                isDecision = true,
                feedStatus = instrument.feedStatus
            )
        }.onFailure { err ->
            addLog(order.symbol, "ORDER FAILED: ${err.message}")
        }
    }

    private suspend fun updatePositionsWithTicks(ticks: Map<String, Double>) {
        val currentPositions = _positions.value.toMutableList()
        val toRemove = mutableListOf<Position>()

        for (i in currentPositions.indices) {
            val pos = currentPositions[i]
            val newPrice = ticks[pos.symbol] ?: continue

            val updatedPos = riskEngine.updateTrailingStop(
                position = pos,
                newPrice = newPrice,
                atr = newPrice * 0.008,
                atrMultiplier = _riskSettings.value.stopLossAtrMultiplier
            )
            currentPositions[i] = updatedPos

            val autoExit = riskEngine.checkAutoExit(updatedPos)
            if (autoExit != null) {
                toRemove.add(updatedPos)
                squareOffPosition(updatedPos, autoExit.reason)
            }
        }

        _positions.value = currentPositions.filterNot { toRemove.contains(it) }
        recalculatePortfolio()
    }

    suspend fun squareOffPosition(position: Position, reason: String) {
        val currentPrice = position.currentPrice
        val result = broker.squareOff(position, currentPrice)
        result.onSuccess { exitOrder ->
            _orders.value = (listOf(exitOrder) + _orders.value).take(100)
            _positions.value = _positions.value.filterNot { it.id == position.id }

            val pnl = (exitOrder.filledPrice - position.entryPrice) * position.quantity
            val dayRealized = _portfolio.value.dayRealizedPnl + pnl
            val totalRealized = _portfolio.value.totalRealizedPnl + pnl

            val newConsecutiveLosses = if (pnl < 0) {
                _portfolio.value.consecutiveLossCount + 1
            } else {
                0
            }
            val lastLossTime = if (pnl < 0) System.currentTimeMillis() else _portfolio.value.lastLossTimestamp

            _portfolio.value = _portfolio.value.copy(
                dayRealizedPnl = dayRealized,
                totalRealizedPnl = totalRealized,
                consecutiveLossCount = newConsecutiveLosses,
                lastLossTimestamp = lastLossTime
            )
            recalculatePortfolio()

            val pnlStr = (if (pnl >= 0) "+₹" else "-₹") + String.format("%.2f", abs(pnl))
            addLog(position.symbol, "SQUARE OFF ($pnlStr): $reason")
        }.onFailure { err ->
            addLog(position.symbol, "Square off failed: ${err.message}. Retrying or alert.")
        }
    }

    private fun recalculatePortfolio() {
        val invested = _positions.value.sumOf { it.currentPrice * it.quantity }
        val brokerBalance = when (val b = broker) {
            is PaperTradingBroker -> b.getSimulatedBalance()
            else -> _portfolio.value.availableCash
        }
        _portfolio.value = _portfolio.value.copy(
            investedValue = invested,
            availableCash = brokerBalance
        )
    }

    private fun addLog(
        symbol: String,
        details: String,
        isDecision: Boolean = false,
        feedStatus: FeedStatus = FeedStatus.SIMULATED
    ) {
        val entry = AuditLogEntry(
            id = UUID.randomUUID().toString(),
            timestamp = System.currentTimeMillis(),
            symbol = symbol,
            action = if (isDecision) "DECISION" else "EXEC",
            details = details,
            isDecision = isDecision,
            feedStatus = feedStatus
        )
        _auditLogs.value = (listOf(entry) + _auditLogs.value).take(150)
    }
}
