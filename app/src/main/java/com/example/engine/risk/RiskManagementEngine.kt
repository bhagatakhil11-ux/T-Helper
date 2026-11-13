package com.example.engine.risk

import com.example.data.model.Order
import com.example.data.model.OrderSide
import com.example.data.model.Portfolio
import com.example.data.model.Position
import com.example.data.model.RiskSettings

data class RiskValidationResult(
    val isApproved: Boolean,
    val rejectionReason: String = "",
    val sanitizedQuantity: Int = 0
)

data class AutoExitTrigger(
    val position: Position,
    val reason: String,
    val isProfit: Boolean
)

class RiskManagementEngine {

    /**
     * Validates whether a proposed order complies with all capital and risk guardrails
     */
    fun validateOrder(
        order: Order,
        portfolio: Portfolio,
        openPositions: List<Position>,
        riskSettings: RiskSettings
    ): RiskValidationResult {
        // 1. Emergency Halt Check
        if (riskSettings.emergencyHalt) {
            return RiskValidationResult(
                isApproved = false,
                rejectionReason = "EMERGENCY HALT ACTIVE: All trading activities are locked by kill switch."
            )
        }

        // 2. Daily Drawdown Circuit Breaker
        val dailyLossPercent = if (portfolio.startingDayCapital > 0) {
            ((portfolio.startingDayCapital - portfolio.currentNetWorth) / portfolio.startingDayCapital) * 100.0
        } else 0.0

        if (dailyLossPercent >= riskSettings.maxDailyDrawdownPercent) {
            return RiskValidationResult(
                isApproved = false,
                rejectionReason = "DAILY DRAWDOWN CIRCUIT BREAKER HIT: Daily loss of ${String.format("%.2f", dailyLossPercent)}% exceeds maximum allowed limit of ${riskSettings.maxDailyDrawdownPercent}%. Trading halted for the session."
            )
        }

        // 3. Consecutive Loss Protection
        if (portfolio.consecutiveLossCount >= riskSettings.maxConsecutiveLosses) {
            val cooldownDurationMs = 15 * 60 * 1000L // 15-minute cooldown
            val elapsed = System.currentTimeMillis() - portfolio.lastLossTimestamp
            if (elapsed < cooldownDurationMs) {
                val remainingMins = ((cooldownDurationMs - elapsed) / 60000L) + 1
                return RiskValidationResult(
                    isApproved = false,
                    rejectionReason = "CONSECUTIVE LOSS COOLDOWN: Triggered after ${portfolio.consecutiveLossCount} consecutive losses. Cooldown remaining: ${remainingMins}m."
                )
            }
        }

        // 4. Open Positions Limit Check
        val existingPositionForSymbol = openPositions.find { it.symbol == order.symbol }
        if (order.side == OrderSide.BUY && existingPositionForSymbol == null) {
            if (openPositions.size >= riskSettings.maxOpenPositions) {
                return RiskValidationResult(
                    isApproved = false,
                    rejectionReason = "MAX OPEN POSITIONS REACHED: Currently holding ${openPositions.size} of max ${riskSettings.maxOpenPositions} positions. Cannot add ${order.symbol}."
                )
            }
        }

        // 5. Maximum Portfolio Exposure Check
        val orderCost = order.quantity * order.price
        val currentInvested = openPositions.sumOf { it.currentPrice * it.quantity }
        val maxAllowedExposure = portfolio.totalCapital * (riskSettings.maxPortfolioExposurePercent / 100.0)

        if (order.side == OrderSide.BUY && (currentInvested + orderCost) > maxAllowedExposure) {
            val remainingExposure = maxAllowedExposure - currentInvested
            if (remainingExposure <= 0) {
                return RiskValidationResult(
                    isApproved = false,
                    rejectionReason = "PORTFOLIO EXPOSURE LIMIT REACHED: Committed ₹${String.format("%.0f", currentInvested)} exceeds max exposure cap (${riskSettings.maxPortfolioExposurePercent}% of capital)."
                )
            }
            val allowedQty = (remainingExposure / order.price).toInt()
            if (allowedQty <= 0) {
                return RiskValidationResult(
                    isApproved = false,
                    rejectionReason = "EXPOSURE CAP EXCEEDED: Cannot open position with remaining exposure room ₹${String.format("%.0f", remainingExposure)}."
                )
            }
            return RiskValidationResult(
                isApproved = true,
                sanitizedQuantity = minOf(order.quantity, allowedQty)
            )
        }

        // 6. Available Cash Balance Check
        if (order.side == OrderSide.BUY) {
            if (orderCost > portfolio.availableCash) {
                val affordableQty = (portfolio.availableCash * 0.95 / order.price).toInt()
                if (affordableQty <= 0) {
                    return RiskValidationResult(
                        isApproved = false,
                        rejectionReason = "INSUFFICIENT CAPITAL: Order value ₹${String.format("%.2f", orderCost)} exceeds available cash ₹${String.format("%.2f", portfolio.availableCash)}."
                    )
                }
                return RiskValidationResult(
                    isApproved = true,
                    sanitizedQuantity = affordableQty
                )
            }
        }

        // 7. Quantity Sanity Check
        if (order.quantity <= 0) {
            return RiskValidationResult(
                isApproved = false,
                rejectionReason = "INVALID QUANTITY: Proposed quantity must be at least 1."
            )
        }

        return RiskValidationResult(
            isApproved = true,
            sanitizedQuantity = order.quantity
        )
    }

    /**
     * Updates trailing stop-loss for open position based on price action
     */
    fun updateTrailingStop(
        position: Position,
        newPrice: Double,
        atr: Double,
        atrMultiplier: Double
    ): Position {
        if (position.side == OrderSide.BUY) {
            val newHighest = maxOf(position.highestPriceSeen, newPrice)
            val trailingStop = newHighest - (atr * atrMultiplier)
            val updatedStopLoss = maxOf(position.stopLoss, trailingStop)

            return position.copy(
                currentPrice = newPrice,
                highestPriceSeen = newHighest,
                stopLoss = updatedStopLoss
            )
        } else {
            val newLowest = minOf(position.lowestPriceSeen, newPrice)
            val trailingStop = newLowest + (atr * atrMultiplier)
            val updatedStopLoss = minOf(position.stopLoss, trailingStop)

            return position.copy(
                currentPrice = newPrice,
                lowestPriceSeen = newLowest,
                stopLoss = updatedStopLoss
            )
        }
    }

    /**
     * Evaluates if a position should be automatically exited due to Stop-Loss or Take-Profit
     */
    fun checkAutoExit(position: Position): AutoExitTrigger? {
        if (position.side == OrderSide.BUY) {
            if (position.currentPrice <= position.stopLoss) {
                return AutoExitTrigger(
                    position = position,
                    reason = "STOP LOSS TRIGGERED: Current price ₹${String.format("%.2f", position.currentPrice)} hit stop-loss level ₹${String.format("%.2f", position.stopLoss)}.",
                    isProfit = false
                )
            }
            if (position.targetPrice > 0 && position.currentPrice >= position.targetPrice) {
                return AutoExitTrigger(
                    position = position,
                    reason = "TARGET PROFIT REACHED: Current price ₹${String.format("%.2f", position.currentPrice)} achieved target ₹${String.format("%.2f", position.targetPrice)}.",
                    isProfit = true
                )
            }
        } else {
            if (position.currentPrice >= position.stopLoss) {
                return AutoExitTrigger(
                    position = position,
                    reason = "STOP LOSS TRIGGERED: Current price ₹${String.format("%.2f", position.currentPrice)} hit stop-loss level ₹${String.format("%.2f", position.stopLoss)}.",
                    isProfit = false
                )
            }
            if (position.targetPrice > 0 && position.currentPrice <= position.targetPrice) {
                return AutoExitTrigger(
                    position = position,
                    reason = "TARGET PROFIT REACHED: Current price ₹${String.format("%.2f", position.currentPrice)} achieved target ₹${String.format("%.2f", position.targetPrice)}.",
                    isProfit = true
                )
            }
        }
        return null
    }
}
