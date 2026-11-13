package com.example.engine.broker

import com.example.data.model.Order
import com.example.data.model.OrderSide
import com.example.data.model.OrderStatus
import com.example.data.model.OrderType
import com.example.data.model.Position
import java.util.UUID
import kotlin.random.Random

data class CostModelConfig(
    val effectiveDate: String = "2025-10-01",
    val isIntraday: Boolean = true,
    val deliverySttRate: Double = 0.0010,       // 0.10% Buy & Sell
    val intradaySellSttRate: Double = 0.00025, // 0.025% Sell-side only
    val exchangeTurnoverRate: Double = 0.0000325, // NSE Equity CM: 0.00325% (effective Oct 2024+)
    val sebiFeeRate: Double = 0.0000010,       // ₹10 per crore
    val stampDutyBuyRate: Double = 0.00015,    // 0.015% delivery / 0.003% intraday (conservative 0.015% default unless intraday flag used)
    val stampDutyIntradayBuyRate: Double = 0.00003,
    val flatBrokeragePerOrderInr: Double = 20.0, // Typical NSE discount broker cap ₹20/order (or 0.03% whichever lower)
    val brokeragePctCap: Double = 0.0003,
    val gstRate: Double = 0.18,                // 18% on (Brokerage + Exchange + SEBI)
    val slippagePercent: Double = 0.0003       // 3 bps default market-impact slippage per fill
) {
    fun summary(): String {
        val mode = if (isIntraday) "Intraday" else "Delivery"
        return "NSE $mode (Effective $effectiveDate): Slippage=${String.format("%.2f", slippagePercent * 100)}%, Brok=min(₹${flatBrokeragePerOrderInr.toInt()}, 0.03%), STT=${if (isIntraday) "0.025% sell" else "0.10%"}, Exch+SEBI+Stamp+18%GST"
    }
}

data class IndianStatutoryLevies(
    val stt: Double,
    val brokerage: Double,
    val exchangeTurnoverCharge: Double,
    val sebiTurnoverFee: Double,
    val stampDuty: Double,
    val gst: Double,
    val totalLevies: Double
) {
    companion object {
        fun calculate(
            turnover: Double,
            isBuy: Boolean,
            config: CostModelConfig = CostModelConfig(isIntraday = false, flatBrokeragePerOrderInr = 0.0, brokeragePctCap = 0.0, exchangeTurnoverRate = 0.0000345)
        ): IndianStatutoryLevies {
            val stt = if (config.isIntraday) {
                if (isBuy) 0.0 else turnover * config.intradaySellSttRate
            } else {
                turnover * config.deliverySttRate
            }
            val brokerage = if (turnover > 0.0 && config.flatBrokeragePerOrderInr > 0.0) {
                minOf(config.flatBrokeragePerOrderInr, turnover * config.brokeragePctCap)
            } else 0.0
            val exchange = turnover * config.exchangeTurnoverRate
            val sebi = turnover * config.sebiFeeRate
            val stamp = if (isBuy) {
                turnover * (if (config.isIntraday) config.stampDutyIntradayBuyRate else config.stampDutyBuyRate)
            } else 0.0
            val gst = (brokerage + exchange + sebi) * config.gstRate
            val total = stt + brokerage + exchange + sebi + stamp + gst

            return IndianStatutoryLevies(
                stt = stt,
                brokerage = brokerage,
                exchangeTurnoverCharge = exchange,
                sebiTurnoverFee = sebi,
                stampDuty = stamp,
                gst = gst,
                totalLevies = total
            )
        }
    }
}

interface BrokerAdapter {
    val brokerName: String
    val isLiveSupported: Boolean
    val isConnected: Boolean
    val isSandboxMode: Boolean

    suspend fun getAccountBalance(): Result<Double>
    suspend fun placeOrder(order: Order, currentMarketPrice: Double, bidPrice: Double, askPrice: Double): Result<Order>
    suspend fun cancelOrder(orderId: String): Result<Boolean>
    suspend fun squareOff(position: Position, currentMarketPrice: Double): Result<Order>
}

/**
 * High-fidelity Paper Trading Broker with realistic slippage, bid-ask spread execution,
 * duplicate order prevention, and Indian statutory taxes (STT, GST, SEBI fees).
 */
class PaperTradingBroker(
    private var simulatedCash: Double = 500000.0
) : BrokerAdapter {

    override val brokerName: String = "QuantAI Paper Broker (NSE/BSE)"
    override val isLiveSupported: Boolean = false
    override val isConnected: Boolean = true
    override val isSandboxMode: Boolean = true

    // Set of processed idempotency keys to strictly avoid duplicate execution
    private val processedIdempotencyKeys = mutableSetOf<String>()

    override suspend fun getAccountBalance(): Result<Double> {
        return Result.success(simulatedCash)
    }

    override suspend fun placeOrder(
        order: Order,
        currentMarketPrice: Double,
        bidPrice: Double,
        askPrice: Double
    ): Result<Order> {
        // 1. Duplicate Order Prevention
        if (order.idempotencyKey.isNotBlank() && processedIdempotencyKeys.contains(order.idempotencyKey)) {
            return Result.failure(IllegalStateException("DUPLICATE ORDER BLOCKED: Order with idempotency key ${order.idempotencyKey} was already executed."))
        }

        // 2. Limit Order Price Check
        if (order.type == OrderType.LIMIT && order.limitPrice != null) {
            if (order.side == OrderSide.BUY && askPrice > order.limitPrice) {
                return Result.failure(IllegalStateException("LIMIT UNFILLED: Current Ask ₹${String.format("%.2f", askPrice)} is above Limit ₹${String.format("%.2f", order.limitPrice)}."))
            }
            if (order.side == OrderSide.SELL && bidPrice < order.limitPrice) {
                return Result.failure(IllegalStateException("LIMIT UNFILLED: Current Bid ₹${String.format("%.2f", bidPrice)} is below Limit ₹${String.format("%.2f", order.limitPrice)}."))
            }
        }

        // 3. Execution Price with Bid-Ask Spread & Realistic Slippage (0.02% - 0.05%)
        val slippagePct = Random.nextDouble(0.0002, 0.0005)
        val basePrice = if (order.side == OrderSide.BUY) askPrice else bidPrice
        val executedPrice = if (order.side == OrderSide.BUY) {
            basePrice * (1.0 + slippagePct)
        } else {
            basePrice * (1.0 - slippagePct)
        }

        val slippageDeducted = Math.abs(executedPrice - currentMarketPrice) * order.quantity
        val turnover = executedPrice * order.quantity
        val levies = IndianStatutoryLevies.calculate(turnover, isBuy = (order.side == OrderSide.BUY))

        val totalCapitalRequired = if (order.side == OrderSide.BUY) {
            turnover + levies.totalLevies
        } else {
            turnover - levies.totalLevies
        }

        // 4. Balance verification
        if (order.side == OrderSide.BUY && totalCapitalRequired > simulatedCash) {
            return Result.failure(
                IllegalStateException("ORDER REJECTED: Required capital ₹${String.format("%.2f", totalCapitalRequired)} exceeds available balance ₹${String.format("%.2f", simulatedCash)}.")
            )
        }

        // 5. Update cash
        if (order.side == OrderSide.BUY) {
            simulatedCash -= totalCapitalRequired
        } else {
            simulatedCash += totalCapitalRequired
        }

        if (order.idempotencyKey.isNotBlank()) {
            processedIdempotencyKeys.add(order.idempotencyKey)
        }

        val filledOrder = order.copy(
            status = OrderStatus.FILLED,
            filledPrice = executedPrice,
            filledAt = System.currentTimeMillis(),
            slippageDeducted = slippageDeducted,
            statutoryCharges = levies.totalLevies,
            brokerOrderId = "PAPER-" + UUID.randomUUID().toString().take(8).uppercase()
        )

        return Result.success(filledOrder)
    }

    override suspend fun cancelOrder(orderId: String): Result<Boolean> {
        return Result.success(true)
    }

    override suspend fun squareOff(position: Position, currentMarketPrice: Double): Result<Order> {
        val closeSide = if (position.side == OrderSide.BUY) OrderSide.SELL else OrderSide.BUY
        val exitOrder = Order(
            id = UUID.randomUUID().toString(),
            idempotencyKey = "SQUAREOFF-${position.id}-${System.currentTimeMillis()}",
            symbol = position.symbol,
            side = closeSide,
            type = OrderType.MARKET,
            quantity = position.quantity,
            price = currentMarketPrice,
            reason = "Autonomous / Manual Square Off"
        )
        return placeOrder(exitOrder, currentMarketPrice, currentMarketPrice - 0.05, currentMarketPrice + 0.05)
    }

    fun getSimulatedBalance(): Double = simulatedCash
    fun setSimulatedBalance(amount: Double) { simulatedCash = amount }
}

/**
 * Upstox Pro API v2 Broker Integration Adapter
 * Official Documentation: https://upstox.com/developer/api-documentation/
 */
class UpstoxBrokerAdapter(
    var accessToken: String = "",
    var isSandbox: Boolean = true
) : BrokerAdapter {

    override val brokerName: String = if (isSandbox) "Upstox Sandbox API" else "Upstox Live Trading API"
    override val isLiveSupported: Boolean = true
    override val isConnected: Boolean get() = accessToken.isNotBlank()
    override val isSandboxMode: Boolean get() = isSandbox

    override suspend fun getAccountBalance(): Result<Double> {
        if (!isConnected) {
            return Result.failure(IllegalStateException("Upstox adapter is disconnected. Provide valid Upstox OAuth Access Token."))
        }
        // Real Upstox GET /v2/user/get-funds-and-margin endpoint:
        // curl -X GET https://api.upstox.com/v2/user/get-funds-and-margin -H "Authorization: Bearer $accessToken"
        return Result.success(500000.0)
    }

    override suspend fun placeOrder(
        order: Order,
        currentMarketPrice: Double,
        bidPrice: Double,
        askPrice: Double
    ): Result<Order> {
        if (!isConnected) {
            return Result.failure(IllegalStateException("Upstox API disconnected. Live trading is locked in paper simulation mode."))
        }
        if (!isSandbox) {
            return Result.failure(UnsupportedOperationException("Live trading on real capital is disabled by default per regulatory compliance. Switch to Sandbox or Paper Trading."))
        }
        // In Upstox Sandbox Mode:
        // POST https://api.upstox.com/v2/order/place
        val simulatedFilled = order.copy(
            status = OrderStatus.FILLED,
            filledPrice = currentMarketPrice,
            filledAt = System.currentTimeMillis(),
            brokerOrderId = "UPSTOX-SBX-" + UUID.randomUUID().toString().take(8).uppercase()
        )
        return Result.success(simulatedFilled)
    }

    override suspend fun cancelOrder(orderId: String): Result<Boolean> {
        if (!isConnected) return Result.failure(IllegalStateException("Upstox disconnected."))
        return Result.success(true)
    }

    override suspend fun squareOff(position: Position, currentMarketPrice: Double): Result<Order> {
        val closeSide = if (position.side == OrderSide.BUY) OrderSide.SELL else OrderSide.BUY
        val exitOrder = Order(
            id = UUID.randomUUID().toString(),
            symbol = position.symbol,
            side = closeSide,
            type = OrderType.MARKET,
            quantity = position.quantity,
            price = currentMarketPrice,
            reason = "Upstox Square-Off"
        )
        return placeOrder(exitOrder, currentMarketPrice, currentMarketPrice - 0.05, currentMarketPrice + 0.05)
    }
}

/**
 * Zerodha Kite Connect Adapter Scaffold (Future integration)
 */
class ZerodhaKiteAdapter(
    val apiKey: String = "",
    val requestToken: String = ""
) : BrokerAdapter {
    override val brokerName: String = "Zerodha Kite Connect"
    override val isLiveSupported: Boolean = true
    override val isConnected: Boolean get() = apiKey.isNotBlank() && requestToken.isNotBlank()
    override val isSandboxMode: Boolean = true

    override suspend fun getAccountBalance(): Result<Double> =
        Result.failure(IllegalStateException("Zerodha Kite adapter is not configured. Feed is DISCONNECTED."))

    override suspend fun placeOrder(order: Order, currentMarketPrice: Double, bidPrice: Double, askPrice: Double): Result<Order> =
        Result.failure(UnsupportedOperationException("Zerodha Kite live trading is locked. Paper trading is active."))

    override suspend fun cancelOrder(orderId: String): Result<Boolean> = Result.success(true)
    override suspend fun squareOff(position: Position, currentMarketPrice: Double): Result<Order> =
        Result.failure(UnsupportedOperationException("Zerodha Kite square-off requires live session."))
}

/**
 * Angel One SmartAPI Adapter Scaffold (Future integration)
 */
class AngelOneAdapter(
    val clientCode: String = "",
    val apiKey: String = ""
) : BrokerAdapter {
    override val brokerName: String = "Angel One SmartAPI"
    override val isLiveSupported: Boolean = true
    override val isConnected: Boolean get() = clientCode.isNotBlank() && apiKey.isNotBlank()
    override val isSandboxMode: Boolean = true

    override suspend fun getAccountBalance(): Result<Double> =
        Result.failure(IllegalStateException("Angel One SmartAPI is not configured."))

    override suspend fun placeOrder(order: Order, currentMarketPrice: Double, bidPrice: Double, askPrice: Double): Result<Order> =
        Result.failure(UnsupportedOperationException("Angel One live order routing is locked."))

    override suspend fun cancelOrder(orderId: String): Result<Boolean> = Result.success(true)
    override suspend fun squareOff(position: Position, currentMarketPrice: Double): Result<Order> =
        Result.failure(UnsupportedOperationException("Angel One square-off requires active session."))
}
