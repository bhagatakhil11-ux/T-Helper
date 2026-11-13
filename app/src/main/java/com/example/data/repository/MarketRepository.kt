package com.example.data.repository

import com.example.data.local.HistoricalDataLocalStore
import com.example.data.model.Candle
import com.example.data.model.Exchange
import com.example.data.model.FeedStatus
import com.example.data.model.HistoricalDatasetMetadata
import com.example.data.model.MarketInstrument
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.sin
import kotlin.random.Random

class MarketRepository(
    val upstoxProvider: UpstoxMarketDataProvider = UpstoxMarketDataProvider(),
    private val localStore: HistoricalDataLocalStore? = null
) {
    private val _feedStatus = MutableStateFlow(FeedStatus.SIMULATED)
    val feedStatus: StateFlow<FeedStatus> = _feedStatus.asStateFlow()

    private val _dataSourceName = MutableStateFlow("NSE Paper Market Feed (Simulated)")
    val dataSourceName: StateFlow<String> = _dataSourceName.asStateFlow()

    private val _lastTickTime = MutableStateFlow(System.currentTimeMillis())
    val lastTickTime: StateFlow<Long> = _lastTickTime.asStateFlow()

    private val _historicalDatasets = MutableStateFlow<Map<String, HistoricalDatasetMetadata>>(emptyMap())
    val historicalDatasets: StateFlow<Map<String, HistoricalDatasetMetadata>> = _historicalDatasets.asStateFlow()

    private var upstoxLiveActive = false
    private var activeSymbolForProvenance = "RELIANCE"
    private var activeTimeframeForProvenance = "1m"

    private val _instruments = MutableStateFlow<List<MarketInstrument>>(
        listOf(
            // Benchmark Indices (Non-tradable analytical references)
            MarketInstrument("NIFTY 50", "Nifty 50 Benchmark Index", Exchange.NSE, isTradable = false, 24150.80, 24080.00, 24220.00, 24040.00, 0.29, 128500000, lotSize = 25, feedStatus = FeedStatus.SIMULATED),
            MarketInstrument("BANKNIFTY", "Nifty Bank Benchmark Index", Exchange.NSE, isTradable = false, 51840.40, 51650.00, 52020.00, 51580.00, 0.37, 85400000, lotSize = 15, feedStatus = FeedStatus.SIMULATED),

            // Tradable Indian Large-Cap Equities
            MarketInstrument("RELIANCE", "Reliance Industries Ltd", Exchange.NSE, isTradable = true, 1278.50, 1269.00, 1286.00, 1264.50, 0.75, 14200000, lotSize = 1, feedStatus = FeedStatus.SIMULATED),
            MarketInstrument("TCS", "Tata Consultancy Services Ltd", Exchange.NSE, isTradable = true, 4185.00, 4210.00, 4225.00, 4160.00, -0.59, 3200000, lotSize = 1, feedStatus = FeedStatus.SIMULATED),
            MarketInstrument("INFY", "Infosys Ltd", Exchange.NSE, isTradable = true, 1842.20, 1825.00, 1855.00, 1820.00, 0.94, 9800000, lotSize = 1, feedStatus = FeedStatus.SIMULATED),
            MarketInstrument("HDFCBANK", "HDFC Bank Ltd", Exchange.NSE, isTradable = true, 1748.90, 1738.00, 1756.00, 1735.00, 0.63, 18400000, lotSize = 1, feedStatus = FeedStatus.SIMULATED),
            MarketInstrument("ICICIBANK", "ICICI Bank Ltd", Exchange.NSE, isTradable = true, 1264.10, 1255.00, 1272.00, 1251.00, 0.72, 11500000, lotSize = 1, feedStatus = FeedStatus.SIMULATED),
            MarketInstrument("TATAMOTORS", "Tata Motors Ltd", Exchange.NSE, isTradable = true, 824.75, 835.00, 838.00, 818.50, -1.23, 16800000, lotSize = 1, feedStatus = FeedStatus.SIMULATED),
            MarketInstrument("SBIN", "State Bank of India", Exchange.NSE, isTradable = true, 812.30, 805.00, 818.00, 802.00, 0.91, 21000000, lotSize = 1, feedStatus = FeedStatus.SIMULATED),
            MarketInstrument("BHARTIARTL", "Bharti Airtel Ltd", Exchange.NSE, isTradable = true, 1594.60, 1582.00, 1605.00, 1578.00, 0.80, 7600000, lotSize = 1, feedStatus = FeedStatus.SIMULATED)
        )
    )
    val instruments: StateFlow<List<MarketInstrument>> = _instruments.asStateFlow()

    // Simulated buffers remain strictly isolated from genuine imported historical datasets
    private val simulatedCandleMap = ConcurrentHashMap<String, MutableList<Candle>>()
    private val historicalCandleMap = ConcurrentHashMap<String, List<Candle>>()

    init {
        for (inst in _instruments.value) {
            simulatedCandleMap[inst.symbol] = generateRealisticHistory(inst.currentPrice, 400).toMutableList()
        }
    }

    private fun datasetKey(symbol: String, timeframe: String): String =
        "${symbol.trim().uppercase()}|${timeframe.trim()}"

    /**
     * Ingests validated genuine historical OHLCV candles into memory.
     * Enforces strict provenance: synthetic/simulated candles are NEVER accepted as HISTORICAL.
     */
    fun ingestHistoricalCandles(
        symbol: String,
        candles: List<Candle>,
        timeframe: String = "1m",
        sourceName: String = "External Historical CSV",
        metadata: HistoricalDatasetMetadata? = null
    ) {
        if (candles.isEmpty()) return
        require(candles.none { it.feedStatus == FeedStatus.SIMULATED }) {
            "Refusing to ingest simulated candles as historical market data."
        }

        val normalizedSymbol = symbol.trim().uppercase()
        val sortedHistorical = candles
            .map { it.copy(feedStatus = FeedStatus.HISTORICAL) }
            .sortedBy { it.timestamp }

        val key = datasetKey(normalizedSymbol, timeframe)
        historicalCandleMap[key] = sortedHistorical

        val resolvedMeta = metadata ?: HistoricalDatasetMetadata(
            symbol = normalizedSymbol,
            timeframe = timeframe,
            sourceName = sourceName,
            feedStatus = FeedStatus.HISTORICAL,
            importedAt = System.currentTimeMillis(),
            candleCount = sortedHistorical.size,
            startTimestamp = sortedHistorical.first().timestamp,
            endTimestamp = sortedHistorical.last().timestamp
        )
        _historicalDatasets.value = _historicalDatasets.value + (key to resolvedMeta)

        // Ensure instrument exists in watchlist and reflects latest historical bar
        val latest = sortedHistorical.last()
        val first = sortedHistorical.first()
        val existingList = _instruments.value.toMutableList()
        val idx = existingList.indexOfFirst { it.symbol == normalizedSymbol }
        val pctChg = if (first.open > 0) ((latest.close - first.open) / first.open) * 100.0 else 0.0

        if (idx >= 0) {
            val prev = existingList[idx]
            existingList[idx] = prev.copy(
                currentPrice = latest.close,
                openPrice = latest.open,
                high24h = sortedHistorical.takeLast(50).maxOf { it.high },
                low24h = sortedHistorical.takeLast(50).minOf { it.low },
                changePercent = pctChg,
                volume = latest.volume,
                bidPrice = latest.close - 0.05,
                askPrice = latest.close + 0.05,
                feedStatus = FeedStatus.HISTORICAL,
                lastUpdated = latest.timestamp
            )
        } else {
            existingList.add(
                MarketInstrument(
                    symbol = normalizedSymbol,
                    name = "$normalizedSymbol (NSE Imported)",
                    exchange = Exchange.NSE,
                    isTradable = true,
                    currentPrice = latest.close,
                    openPrice = latest.open,
                    high24h = sortedHistorical.takeLast(50).maxOf { it.high },
                    low24h = sortedHistorical.takeLast(50).minOf { it.low },
                    changePercent = pctChg,
                    volume = latest.volume,
                    lotSize = 1,
                    feedStatus = FeedStatus.HISTORICAL,
                    lastUpdated = latest.timestamp
                )
            )
            simulatedCandleMap[normalizedSymbol] = generateRealisticHistory(latest.close, 400).toMutableList()
        }
        _instruments.value = existingList
        refreshProvenance(activeSymbolForProvenance, activeTimeframeForProvenance)
    }

    suspend fun persistAndIngestHistoricalDataset(
        metadata: HistoricalDatasetMetadata,
        candles: List<Candle>
    ) {
        ingestHistoricalCandles(
            symbol = metadata.symbol,
            candles = candles,
            timeframe = metadata.timeframe,
            sourceName = metadata.sourceName,
            metadata = metadata
        )
        localStore?.saveDataset(metadata, candles)
    }

    suspend fun loadPersistedHistoricalDatasets() {
        val store = localStore ?: return
        val datasets = store.loadAllDatasets()
        for ((meta, candles) in datasets) {
            if (candles.isNotEmpty()) {
                ingestHistoricalCandles(
                    symbol = meta.symbol,
                    candles = candles,
                    timeframe = meta.timeframe,
                    sourceName = meta.sourceName,
                    metadata = meta
                )
            }
        }
    }

    suspend fun deleteHistoricalDataset(symbol: String, timeframe: String) {
        val normalizedSymbol = symbol.trim().uppercase()
        val key = datasetKey(normalizedSymbol, timeframe)
        historicalCandleMap.remove(key)
        _historicalDatasets.value = _historicalDatasets.value - key
        localStore?.deleteDataset(normalizedSymbol, timeframe)

        // Revert instrument feed status if no other historical dataset remains for symbol
        val stillHasHistorical = _historicalDatasets.value.values.any { it.symbol == normalizedSymbol }
        if (!stillHasHistorical) {
            _instruments.value = _instruments.value.map { inst ->
                if (inst.symbol == normalizedSymbol) {
                    inst.copy(feedStatus = if (upstoxLiveActive) FeedStatus.LIVE else FeedStatus.SIMULATED)
                } else inst
            }
        }
        refreshProvenance(activeSymbolForProvenance, activeTimeframeForProvenance)
    }

    fun getDatasetProvenance(symbol: String, timeframe: String): HistoricalDatasetMetadata? {
        val normSym = symbol.trim().uppercase()
        val exact = _historicalDatasets.value[datasetKey(normSym, timeframe)]
        if (exact != null) return exact

        // Check if higher timeframe can be derived from imported historical 1m or 5m candles
        if (timeframe in listOf("5m", "15m", "1D")) {
            val base1m = _historicalDatasets.value[datasetKey(normSym, "1m")]
            if (base1m != null) {
                return base1m.copy(
                    timeframe = timeframe,
                    sourceName = "${base1m.sourceName} (Aggregated from 1m)"
                )
            }
        }
        if (timeframe == "15m") {
            val base5m = _historicalDatasets.value[datasetKey(normSym, "5m")]
            if (base5m != null) {
                return base5m.copy(
                    timeframe = timeframe,
                    sourceName = "${base5m.sourceName} (Aggregated from 5m)"
                )
            }
        }
        return null
    }

    fun isUsingHistoricalData(symbol: String, timeframe: String): Boolean {
        return getDatasetProvenance(symbol, timeframe) != null
    }

    fun refreshProvenance(symbol: String, timeframe: String) {
        activeSymbolForProvenance = symbol
        activeTimeframeForProvenance = timeframe
        val histMeta = getDatasetProvenance(symbol, timeframe)
        when {
            histMeta != null -> {
                _feedStatus.value = FeedStatus.HISTORICAL
                _dataSourceName.value = "${histMeta.sourceName} (${histMeta.symbol} ${histMeta.timeframe}, ${histMeta.candleCount} bars)"
            }
            upstoxLiveActive -> {
                _feedStatus.value = FeedStatus.LIVE
                _dataSourceName.value = upstoxProvider.providerName
            }
            else -> {
                _feedStatus.value = FeedStatus.SIMULATED
                _dataSourceName.value = "NSE Paper Market Feed (Simulated)"
            }
        }
    }

    fun getCandles(symbol: String, timeframe: String = "1m"): List<Candle> {
        val normSym = symbol.trim().uppercase()

        // 1. Exact historical dataset match for (symbol, timeframe)
        val exactHistorical = historicalCandleMap[datasetKey(normSym, timeframe)]
        if (!exactHistorical.isNullOrEmpty()) {
            return exactHistorical
        }

        // 2. Derived higher timeframe from imported historical 1m/5m (never mixing with simulated data)
        val hist1m = historicalCandleMap[datasetKey(normSym, "1m")]
        if (!hist1m.isNullOrEmpty() && timeframe != "1m") {
            return when (timeframe) {
                "5m" -> aggregateCandles(hist1m, 5)
                "15m" -> aggregateCandles(hist1m, 15)
                "1D" -> aggregateCandles(hist1m, 375) // 375 mins per NSE session (09:15-15:30)
                else -> hist1m
            }
        }
        if (timeframe == "15m") {
            val hist5m = historicalCandleMap[datasetKey(normSym, "5m")]
            if (!hist5m.isNullOrEmpty()) {
                return aggregateCandles(hist5m, 3)
            }
        }

        // 3. Fallback to simulated buffer
        val base1m = simulatedCandleMap[normSym]?.toList() ?: emptyList()
        return when (timeframe) {
            "5m" -> aggregateCandles(base1m, 5)
            "15m" -> aggregateCandles(base1m, 15)
            "1D" -> aggregateCandles(base1m, 30)
            else -> base1m
        }
    }

    fun isFeedStale(): Boolean {
        return (System.currentTimeMillis() - _lastTickTime.value) > 180000L // 3 minutes
    }

    fun switchFeedProvider(useUpstox: Boolean, token: String = "", sandbox: Boolean = true) {
        if (useUpstox && token.isNotBlank()) {
            upstoxProvider.updateCredentials(token, sandbox)
            upstoxLiveActive = true
        } else {
            upstoxLiveActive = false
        }
        refreshProvenance(activeSymbolForProvenance, activeTimeframeForProvenance)
    }

    /**
     * Ingests simulated tick updates across tracked simulated streams.
     * Never modifies or contaminates imported HISTORICAL datasets.
     */
    fun tickMarket(): Map<String, Double> {
        val now = System.currentTimeMillis()
        _lastTickTime.value = now

        val updatedList = mutableListOf<MarketInstrument>()
        val tickPrices = mutableMapOf<String, Double>()

        for (inst in _instruments.value) {
            // Preserve genuine historical snapshot values for symbols currently backed by historical data
            val hasHistorical = _historicalDatasets.value.values.any { it.symbol == inst.symbol }
            if (hasHistorical && !upstoxLiveActive) {
                tickPrices[inst.symbol] = inst.currentPrice
                updatedList.add(inst.copy(feedStatus = FeedStatus.HISTORICAL))
                continue
            }

            val deltaPct = Random.nextDouble(-0.0020, 0.0022)
            val newPrice = (inst.currentPrice * (1.0 + deltaPct)).coerceAtLeast(1.0)
            val roundedPrice = (Math.round(newPrice * 20.0) / 20.0)
            tickPrices[inst.symbol] = roundedPrice

            val newHigh = maxOf(inst.high24h, roundedPrice)
            val newLow = minOf(inst.low24h, roundedPrice)
            val changePct = if (inst.openPrice > 0) ((roundedPrice - inst.openPrice) / inst.openPrice) * 100.0 else 0.0
            val volAddition = Random.nextLong(300, 3000)

            val simStatus = if (upstoxLiveActive) FeedStatus.LIVE else FeedStatus.SIMULATED
            val updated = inst.copy(
                currentPrice = roundedPrice,
                bidPrice = roundedPrice - 0.05,
                askPrice = roundedPrice + 0.05,
                high24h = newHigh,
                low24h = newLow,
                changePercent = changePct,
                volume = inst.volume + volAddition,
                feedStatus = simStatus,
                lastUpdated = now
            )
            updatedList.add(updated)

            val history = simulatedCandleMap[inst.symbol]
            if (history != null && history.isNotEmpty()) {
                val lastCandle = history.last()
                if (now - lastCandle.timestamp < 60000) {
                    val updatedCandle = lastCandle.copy(
                        high = maxOf(lastCandle.high, roundedPrice),
                        low = minOf(lastCandle.low, roundedPrice),
                        close = roundedPrice,
                        volume = lastCandle.volume + volAddition,
                        feedStatus = simStatus
                    )
                    history[history.size - 1] = updatedCandle
                } else {
                    val newCandle = Candle(
                        timestamp = now,
                        open = lastCandle.close,
                        high = maxOf(lastCandle.close, roundedPrice),
                        low = minOf(lastCandle.close, roundedPrice),
                        close = roundedPrice,
                        volume = volAddition,
                        feedStatus = simStatus
                    )
                    history.add(newCandle)
                    if (history.size > 600) history.removeAt(0)
                }
            }
        }

        _instruments.value = updatedList
        return tickPrices
    }

    private fun aggregateCandles(candles: List<Candle>, groupSize: Int): List<Candle> {
        if (candles.isEmpty() || groupSize <= 1) return candles
        val aggregated = mutableListOf<Candle>()

        for (i in candles.indices step groupSize) {
            val chunk = candles.subList(i, minOf(i + groupSize, candles.size))
            if (chunk.isNotEmpty()) {
                aggregated.add(
                    Candle(
                        timestamp = chunk.first().timestamp,
                        open = chunk.first().open,
                        high = chunk.maxOf { it.high },
                        low = chunk.minOf { it.low },
                        close = chunk.last().close,
                        volume = chunk.sumOf { it.volume },
                        feedStatus = chunk.first().feedStatus
                    )
                )
            }
        }
        return aggregated
    }

    private fun generateRealisticHistory(basePrice: Double, count: Int): List<Candle> {
        val candles = mutableListOf<Candle>()
        var currentPrice = basePrice * 0.96
        val now = System.currentTimeMillis()
        val intervalMs = 60000L

        for (i in 0 until count) {
            val timestamp = now - (count - i) * intervalMs
            val trendFactor = sin(i.toDouble() / 9.0) * 0.0028
            val randomFactor = Random.nextDouble(-0.0035, 0.0038)
            val pctChange = trendFactor + randomFactor

            val open = currentPrice
            val close = (open * (1.0 + pctChange)).coerceAtLeast(1.0)
            val wickUpper = abs(Random.nextDouble(0.0008, 0.0035))
            val wickLower = abs(Random.nextDouble(0.0008, 0.0035))

            val high = maxOf(open, close) * (1.0 + wickUpper)
            val low = minOf(open, close) * (1.0 - wickLower)
            val volume = Random.nextLong(20000, 220000)

            candles.add(
                Candle(
                    timestamp = timestamp,
                    open = Math.round(open * 20.0) / 20.0,
                    high = Math.round(high * 20.0) / 20.0,
                    low = Math.round(low * 20.0) / 20.0,
                    close = Math.round(close * 20.0) / 20.0,
                    volume = volume,
                    feedStatus = FeedStatus.SIMULATED
                )
            )
            currentPrice = close
        }
        return candles
    }
}
