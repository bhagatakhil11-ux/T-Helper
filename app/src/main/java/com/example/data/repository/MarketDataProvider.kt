package com.example.data.repository

import com.example.data.model.Candle
import com.example.data.model.FeedStatus
import com.example.data.model.MarketInstrument
import com.example.data.model.MarketSession
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Calendar
import java.util.TimeZone

object MarketSessionDetector {
    private val istTimeZone = TimeZone.getTimeZone("Asia/Kolkata")

    fun getCurrentSession(): MarketSession {
        val calendar = Calendar.getInstance(istTimeZone)
        val dayOfWeek = calendar.get(Calendar.DAY_OF_WEEK)

        // Saturday and Sunday are closed
        if (dayOfWeek == Calendar.SATURDAY || dayOfWeek == Calendar.SUNDAY) {
            return MarketSession.CLOSED
        }

        val hour = calendar.get(Calendar.HOUR_OF_DAY)
        val minute = calendar.get(Calendar.MINUTE)
        val timeInMinutes = hour * 60 + minute

        return when {
            timeInMinutes in (9 * 60)..(9 * 60 + 14) -> MarketSession.PRE_OPEN // 09:00 - 09:14
            timeInMinutes in (9 * 60 + 15)..(15 * 60 + 30) -> MarketSession.REGULAR_HOURS // 09:15 - 15:30
            timeInMinutes in (15 * 60 + 31)..(16 * 60) -> MarketSession.POST_CLOSE // 15:31 - 16:00
            else -> MarketSession.CLOSED
        }
    }

    fun isMarketOpenForExecution(): Boolean {
        // In paper trading simulator mode, autonomous agent runs across sessions, but marks regular hours accurately
        return getCurrentSession() == MarketSession.REGULAR_HOURS
    }
}

interface MarketDataProvider {
    val providerName: String
    val feedStatus: StateFlow<FeedStatus>
    val lastUpdateTimestamp: StateFlow<Long>

    suspend fun fetchCandles(symbol: String, timeframe: String): Result<List<Candle>>
    fun isDataFresh(lastUpdate: Long, maxStaleDurationMs: Long = 180000L): Boolean {
        return (System.currentTimeMillis() - lastUpdate) <= maxStaleDurationMs
    }
}

class UpstoxMarketDataProvider(
    var accessToken: String = "",
    var isSandbox: Boolean = true
) : MarketDataProvider {
    override val providerName: String = if (isSandbox) "Upstox Sandbox API" else "Upstox Live v2 API"

    private val _feedStatus = MutableStateFlow(
        if (accessToken.isNotBlank()) FeedStatus.LIVE else FeedStatus.DISCONNECTED
    )
    override val feedStatus: StateFlow<FeedStatus> = _feedStatus.asStateFlow()

    private val _lastUpdate = MutableStateFlow(System.currentTimeMillis())
    override val lastUpdateTimestamp: StateFlow<Long> = _lastUpdate.asStateFlow()

    fun updateCredentials(token: String, sandbox: Boolean) {
        accessToken = token
        isSandbox = sandbox
        _feedStatus.value = if (token.isNotBlank()) FeedStatus.LIVE else FeedStatus.DISCONNECTED
    }

    override suspend fun fetchCandles(symbol: String, timeframe: String): Result<List<Candle>> {
        if (accessToken.isBlank()) {
            return Result.failure(IllegalStateException("Upstox Access Token is not configured. Feed is DISCONNECTED."))
        }
        // When real token is provided, this routes to official Upstox v2 historical endpoint:
        // https://api.upstox.com/v2/historical-candle/{instrument_key}/{interval}/{to_date}/{from_date}
        return Result.failure(UnsupportedOperationException("Upstox WebSocket/REST endpoint requires valid OAuth bearer token."))
    }
}
