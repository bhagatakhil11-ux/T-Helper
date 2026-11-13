package com.example.ui.viewmodel

import android.app.Application
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.data.local.HistoricalDataLocalStore
import com.example.data.local.QuantDatabase
import com.example.data.model.AgentStatus
import com.example.data.model.AuditLogEntry
import com.example.data.model.BacktestResult
import com.example.data.model.BatchHistoricalImportResult
import com.example.data.model.Candle
import com.example.data.model.DataOrigin
import com.example.data.model.DataQualityThresholds
import com.example.data.model.DecisionSignal
import com.example.data.model.FeedStatus
import com.example.data.model.HistoricalDatasetMetadata
import com.example.data.model.MarketInstrument
import com.example.data.model.MarketSession
import com.example.data.model.MlPrediction
import com.example.data.model.MlValidationReport
import com.example.data.model.Order
import com.example.data.model.Portfolio
import com.example.data.model.Position
import com.example.data.model.ResearchQualityStatus
import com.example.data.model.RiskSettings
import com.example.data.model.VerificationStatus
import com.example.data.repository.HistoricalCsvImporter
import com.example.data.repository.MarketRepository
import com.example.engine.agent.AutonomousTradingAgent
import com.example.engine.backtest.BacktestEngine
import com.example.engine.broker.AngelOneAdapter
import com.example.engine.broker.PaperTradingBroker
import com.example.engine.broker.UpstoxBrokerAdapter
import com.example.engine.broker.ZerodhaKiteAdapter
import com.example.engine.indicators.IndicatorSnapshot
import com.example.engine.indicators.TechnicalIndicators
import com.example.engine.ml.MlModelStore
import com.example.engine.ml.QuantMlEngine
import com.example.engine.strategy.EnsembleDecisionEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class TradingViewModel(
    private val marketRepository: MarketRepository = MarketRepository(),
    val mlEngine: QuantMlEngine = QuantMlEngine(),
    val decisionEngine: EnsembleDecisionEngine = EnsembleDecisionEngine(mlEngine = mlEngine),
    private val backtestEngine: BacktestEngine = BacktestEngine(decisionEngine = decisionEngine),
    private val modelStore: MlModelStore? = null
) : ViewModel() {

    private val agent = AutonomousTradingAgent(
        marketRepository = marketRepository,
        decisionEngine = decisionEngine,
        scope = viewModelScope
    )

    val portfolio: StateFlow<Portfolio> = agent.portfolio
    val agentStatus: StateFlow<AgentStatus> = agent.agentStatus
    val positions: StateFlow<List<Position>> = agent.positions
    val orders: StateFlow<List<Order>> = agent.orders
    val auditLogs: StateFlow<List<AuditLogEntry>> = agent.auditLogs
    val lastDecision: StateFlow<DecisionSignal?> = agent.lastDecision
    val riskSettings: StateFlow<RiskSettings> = agent.riskSettings
    val instruments: StateFlow<List<MarketInstrument>> = marketRepository.instruments
    val currentSession: StateFlow<MarketSession> = agent.currentSession
    val feedStatus: StateFlow<FeedStatus> = marketRepository.feedStatus
    val dataOrigin: StateFlow<DataOrigin> = marketRepository.dataOrigin
    val verificationStatus: StateFlow<VerificationStatus> = marketRepository.verificationStatus
    val dataSourceName: StateFlow<String> = marketRepository.dataSourceName
    val historicalDatasets: StateFlow<Map<String, HistoricalDatasetMetadata>> = marketRepository.historicalDatasets

    private val _selectedSymbol = MutableStateFlow("RELIANCE")
    val selectedSymbol: StateFlow<String> = _selectedSymbol.asStateFlow()

    private val _selectedTimeframe = MutableStateFlow("1m")
    val selectedTimeframe: StateFlow<String> = _selectedTimeframe.asStateFlow()

    private val _activeTab = MutableStateFlow(0)
    val activeTab: StateFlow<Int> = _activeTab.asStateFlow()

    private val _backtestResult = MutableStateFlow<BacktestResult?>(null)
    val backtestResult: StateFlow<BacktestResult?> = _backtestResult.asStateFlow()

    private val _isBacktesting = MutableStateFlow(false)
    val isBacktesting: StateFlow<Boolean> = _isBacktesting.asStateFlow()

    private val _unseenTestBacktestOnly = MutableStateFlow(false)
    val unseenTestBacktestOnly: StateFlow<Boolean> = _unseenTestBacktestOnly.asStateFlow()

    private val _isTrainingMl = MutableStateFlow(false)
    val isTrainingMl: StateFlow<Boolean> = _isTrainingMl.asStateFlow()

    private val _isImportingCsv = MutableStateFlow(false)
    val isImportingCsv: StateFlow<Boolean> = _isImportingCsv.asStateFlow()

    private val _lastImportResult = MutableStateFlow<BatchHistoricalImportResult?>(null)
    val lastImportResult: StateFlow<BatchHistoricalImportResult?> = _lastImportResult.asStateFlow()

    private val _templateExportStatus = MutableStateFlow<String?>(null)
    val templateExportStatus: StateFlow<String?> = _templateExportStatus.asStateFlow()

    private val _mlValidationReport = MutableStateFlow<MlValidationReport?>(null)
    val mlValidationReport: StateFlow<MlValidationReport?> = _mlValidationReport.asStateFlow()

    private val _lastMlTrainingSummary = MutableStateFlow("Untrained — Tap Train & Validate (Rule Strategies Active)")
    val lastMlTrainingSummary: StateFlow<String> = _lastMlTrainingSummary.asStateFlow()

    private val _selectedBroker = MutableStateFlow("Paper Trading (Simulated)")
    val selectedBroker: StateFlow<String> = _selectedBroker.asStateFlow()

    private val _mlRefreshTrigger = MutableStateFlow(0L)

    val activeDatasetProvenance: StateFlow<HistoricalDatasetMetadata?> = combine(
        _selectedSymbol,
        _selectedTimeframe,
        historicalDatasets
    ) { symbol, tf, _ ->
        marketRepository.getDatasetProvenance(symbol, tf)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val currentCandles: StateFlow<List<Candle>> = combine(
        _selectedSymbol,
        _selectedTimeframe,
        instruments,
        historicalDatasets
    ) { symbol, tf, _, _ ->
        marketRepository.getCandles(symbol, tf)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val currentSnapshot: StateFlow<IndicatorSnapshot?> = currentCandles
        .map { candles ->
            if (candles.isNotEmpty()) {
                TechnicalIndicators.computeSnapshot(candles)
            } else null
        }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val currentMlPrediction: StateFlow<MlPrediction?> = combine(
        currentCandles,
        _selectedSymbol,
        _selectedTimeframe,
        historicalDatasets,
        _mlRefreshTrigger
    ) { candles, sym, tf, _, _ ->
        val prov = marketRepository.getDatasetProvenance(sym, tf)
        if (candles.size >= 26 && mlEngine.isModelValidFor(sym, tf, prov)) {
            mlEngine.predict(candles)
        } else null
    }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    init {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                marketRepository.loadPersistedHistoricalDatasets()
            }
            marketRepository.refreshProvenance(_selectedSymbol.value, _selectedTimeframe.value)

            val persisted = withContext(Dispatchers.IO) { modelStore?.load() }
            if (persisted != null) {
                val rep = persisted.validationReport
                val currentProv = marketRepository.getDatasetProvenance(rep.trainedSymbol, rep.timeframe)
                mlEngine.loadPersistedModel(persisted)
                if (mlEngine.isModelValidFor(rep.trainedSymbol, rep.timeframe, currentProv)) {
                    _mlValidationReport.value = rep
                    val accPct = String.format("%.1f", rep.testAccuracy * 100.0)
                    _lastMlTrainingSummary.value =
                        "Loaded ${rep.modelVersion} [${rep.verificationStatus.name} | ${rep.trainedSymbol} ${rep.timeframe}] | OOS Acc: $accPct% (${rep.testSamples} test / ${rep.trainSamples} train)"
                } else {
                    mlEngine.invalidateModel()
                    withContext(Dispatchers.IO) { modelStore?.clear() }
                    _mlValidationReport.value = null
                    _lastMlTrainingSummary.value =
                        "Persisted model invalidated (dataset changed, deleted, or feature/model version mismatch) — retrain required."
                }
                _mlRefreshTrigger.value = System.currentTimeMillis()
            }
            runBacktest(_selectedSymbol.value, _selectedTimeframe.value)
        }
    }

    fun selectSymbol(symbol: String) {
        val norm = symbol.trim().uppercase()
        _selectedSymbol.value = norm
        marketRepository.refreshProvenance(norm, _selectedTimeframe.value)
        _mlRefreshTrigger.value = System.currentTimeMillis()
    }

    fun setTimeframe(tf: String) {
        _selectedTimeframe.value = tf
        marketRepository.refreshProvenance(_selectedSymbol.value, tf)
        _mlRefreshTrigger.value = System.currentTimeMillis()
    }

    fun setUnseenTestBacktestOnly(enabled: Boolean) {
        _unseenTestBacktestOnly.value = enabled
    }

    fun setActiveTab(tab: Int) {
        _activeTab.value = tab
    }

    fun startAgent() {
        agent.resumeAgent()
    }

    fun pauseAgent() {
        agent.stopAgent()
    }

    fun triggerEmergencyKillSwitch() {
        agent.triggerEmergencyKillSwitch()
    }

    fun resetKillSwitch() {
        agent.resetKillSwitch()
    }

    fun updateRiskSettings(newSettings: RiskSettings) {
        agent.updateRiskSettings(newSettings)
    }

    fun squareOffPosition(position: Position) {
        viewModelScope.launch {
            agent.squareOffPosition(position, "User manual square off")
        }
    }

    fun importHistoricalCsvFromUri(
        context: Context,
        uri: Uri,
        targetSymbol: String,
        timeframe: String,
        useSymbolColumnIfPresent: Boolean = true
    ) {
        viewModelScope.launch {
            _isImportingCsv.value = true
            val fileName = withContext(Dispatchers.IO) {
                resolveDisplayName(context, uri) ?: uri.lastPathSegment ?: "imported_ohlcv.csv"
            }
            val result = withContext(Dispatchers.IO) {
                try {
                    val stream = context.contentResolver.openInputStream(uri)
                        ?: return@withContext BatchHistoricalImportResult(
                            success = false,
                            errors = listOf("Unable to open selected CSV file stream.")
                        )
                    stream.use { input ->
                        HistoricalCsvImporter.parseAndValidate(
                            inputStream = input,
                            defaultSymbol = targetSymbol,
                            timeframe = timeframe,
                            sourceName = fileName,
                            useSymbolColumnIfPresent = useSymbolColumnIfPresent
                        )
                    }
                } catch (e: Exception) {
                    BatchHistoricalImportResult(
                        success = false,
                        errors = listOf("Failed to read CSV: ${e.message ?: "Unknown I/O error"}")
                    )
                }
            }

            if (result.success) {
                val batchPairs = result.importedDatasets.mapNotNull { ds ->
                    ds.metadata?.let { meta -> meta to ds.candles }
                }
                withContext(Dispatchers.IO) {
                    marketRepository.persistAndIngestHistoricalBatch(batchPairs)
                }
                invalidateStaleModelIfAffected(batchPairs.map { it.first.symbol to it.first.timeframe })

                val primary = result.importedDatasets.firstOrNull()
                if (primary != null) {
                    _selectedSymbol.value = primary.symbol
                    _selectedTimeframe.value = primary.timeframe
                    marketRepository.refreshProvenance(primary.symbol, primary.timeframe)
                    _mlRefreshTrigger.value = System.currentTimeMillis()
                    runBacktest(primary.symbol, primary.timeframe)
                }
            }

            _lastImportResult.value = result
            _isImportingCsv.value = false
        }
    }

    fun importHistoricalCsvText(
        csvText: String,
        targetSymbol: String,
        timeframe: String,
        sourceName: String = "Pasted CSV",
        useSymbolColumnIfPresent: Boolean = true
    ) {
        viewModelScope.launch {
            _isImportingCsv.value = true
            val result = withContext(Dispatchers.Default) {
                HistoricalCsvImporter.parseAndValidateText(
                    csvText = csvText,
                    defaultSymbol = targetSymbol,
                    timeframe = timeframe,
                    sourceName = sourceName,
                    useSymbolColumnIfPresent = useSymbolColumnIfPresent
                )
            }
            if (result.success) {
                val batchPairs = result.importedDatasets.mapNotNull { ds ->
                    ds.metadata?.let { meta -> meta to ds.candles }
                }
                withContext(Dispatchers.IO) {
                    marketRepository.persistAndIngestHistoricalBatch(batchPairs)
                }
                invalidateStaleModelIfAffected(batchPairs.map { it.first.symbol to it.first.timeframe })

                val primary = result.importedDatasets.firstOrNull()
                if (primary != null) {
                    _selectedSymbol.value = primary.symbol
                    _selectedTimeframe.value = primary.timeframe
                    marketRepository.refreshProvenance(primary.symbol, primary.timeframe)
                    _mlRefreshTrigger.value = System.currentTimeMillis()
                    runBacktest(primary.symbol, primary.timeframe)
                }
            }
            _lastImportResult.value = result
            _isImportingCsv.value = false
        }
    }

    fun exportSampleCsvTemplate(context: Context, uri: Uri) {
        viewModelScope.launch {
            val status = withContext(Dispatchers.IO) {
                try {
                    context.contentResolver.openOutputStream(uri)?.use { out ->
                        out.write(HistoricalCsvImporter.SAMPLE_CSV_TEMPLATE.toByteArray(Charsets.UTF_8))
                    } ?: return@withContext "Unable to write template file."
                    "Sample CSV template saved successfully."
                } catch (e: Exception) {
                    "Failed to save template: ${e.message ?: "Unknown error"}"
                }
            }
            _templateExportStatus.value = status
        }
    }

    fun deleteHistoricalDataset(symbol: String, timeframe: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                marketRepository.deleteHistoricalDataset(symbol, timeframe)
            }
            invalidateStaleModelIfAffected(listOf(symbol.trim().uppercase() to timeframe))
            marketRepository.refreshProvenance(_selectedSymbol.value, _selectedTimeframe.value)
            _mlRefreshTrigger.value = System.currentTimeMillis()
            if (_selectedSymbol.value == symbol.trim().uppercase() && _selectedTimeframe.value == timeframe) {
                runBacktest(_selectedSymbol.value, _selectedTimeframe.value)
            }
        }
    }

    private suspend fun invalidateStaleModelIfAffected(affectedKeys: List<Pair<String, String>>) {
        val rep = mlEngine.getValidationReport() ?: return
        val impacted = affectedKeys.any { (sym, tf) ->
            rep.trainedSymbol == sym && (rep.timeframe == tf || tf == "1m" || tf == "5m")
        }
        val prov = marketRepository.getDatasetProvenance(rep.trainedSymbol, rep.timeframe)
        if (impacted || !mlEngine.isModelValidFor(rep.trainedSymbol, rep.timeframe, prov)) {
            mlEngine.invalidateModel()
            _mlValidationReport.value = null
            _lastMlTrainingSummary.value =
                "Model invalidated (${rep.trainedSymbol} ${rep.timeframe}): underlying dataset was replaced or deleted — retrain required."
            withContext(Dispatchers.IO) { modelStore?.clear() }
        }
    }

    private fun resolveDisplayName(context: Context, uri: Uri): String? {
        return try {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) cursor.getString(idx) else null
                } else null
            }
        } catch (_: Exception) {
            null
        }
    }

    fun runBacktest(
        symbol: String,
        timeframe: String = _selectedTimeframe.value,
        unseenTestOnly: Boolean = _unseenTestBacktestOnly.value
    ) {
        viewModelScope.launch {
            _isBacktesting.value = true
            val result = withContext(Dispatchers.Default) {
                val candles = marketRepository.getCandles(symbol, timeframe)
                val prov = marketRepository.getDatasetProvenance(symbol, timeframe)
                val sourceLabel = prov?.sourceName ?: "NSE Paper Market Feed (Simulated)"

                val rep = mlEngine.getValidationReport()
                val matchesTrained = mlEngine.isModelValidFor(symbol, timeframe, prov) && rep != null
                val startIndex = if (unseenTestOnly && matchesTrained && rep!!.testStartCandleIndex >= 25) {
                    rep.testStartCandleIndex
                } else {
                    25
                }
                val scopeLabel = when {
                    unseenTestOnly && matchesTrained && rep!!.testStartCandleIndex >= 25 ->
                        "Fixed Holdout Unseen Test Split (bars ${rep.testStartCandleIndex}..${candles.size - 1})"
                    matchesTrained ->
                        "Single-Series Simulation (includes Train/Val bars; enable Unseen Test Split for OOS)"
                    else ->
                        "Single-Series Simulation (Rule Strategies; ML Unbound)"
                }

                backtestEngine.runBacktest(
                    symbol = symbol,
                    candles = candles,
                    initialCapital = portfolio.value.totalCapital,
                    riskSettings = riskSettings.value,
                    timeframe = timeframe,
                    sourceName = sourceLabel,
                    startBarIndex = startIndex,
                    evaluationScope = scopeLabel,
                    datasetMeta = prov
                )
            }
            _backtestResult.value = result
            _isBacktesting.value = false
        }
    }

    fun trainMlModel(symbol: String) {
        viewModelScope.launch {
            _isTrainingMl.value = true
            val requestedTf = _selectedTimeframe.value
            val candles = marketRepository.getCandles(symbol, requestedTf)
            if (candles.size < DataQualityThresholds.MIN_SMOKE_TEST_BARS) {
                _lastMlTrainingSummary.value =
                    "Training skipped ($symbol $requestedTf): ${candles.size} bars available, >= ${DataQualityThresholds.MIN_SMOKE_TEST_BARS} smoke-test minimum required (research grade: >= ${DataQualityThresholds.requiredResearchBars(requestedTf)} bars)."
                _isTrainingMl.value = false
                return@launch
            }

            val outcome = withContext(Dispatchers.Default) {
                val datasetMeta = marketRepository.getDatasetProvenance(symbol, requestedTf)
                mlEngine.trainAndValidate(
                    candles = candles,
                    symbol = symbol,
                    timeframe = requestedTf,
                    datasetMeta = datasetMeta
                )
            }
            if (outcome != null) {
                val rep = outcome.report
                _mlValidationReport.value = rep
                val qualityNote = if (rep.researchQualityStatus == ResearchQualityStatus.LOW_CONFIDENCE_INSUFFICIENT_DATA) {
                    " [LOW_CONFIDENCE_INSUFFICIENT_DATA: ${candles.size}/${rep.researchMinRequiredBars} research bars]"
                } else ""
                if (outcome.deployed) {
                    withContext(Dispatchers.IO) {
                        mlEngine.exportPersistedModel()?.let { modelStore?.save(it) }
                    }
                    val accPct = String.format("%.1f", rep.testAccuracy * 100.0)
                    val majPct = String.format("%.1f", rep.baselineMajorityAccuracy * 100.0)
                    _lastMlTrainingSummary.value =
                        "Deployed ${rep.modelVersion} [${rep.verificationStatus.name} | $symbol ${rep.timeframe}]$qualityNote | OOS Acc: $accPct% vs Maj $majPct% (${rep.trainSamples}/${rep.validationSamples}/${rep.testSamples} split, ${rep.purgedSamples} purged)"
                } else {
                    _lastMlTrainingSummary.value =
                        "Gate Rejected [${rep.verificationStatus.name} | $symbol ${rep.timeframe}]$qualityNote: ${rep.validationGateReason}"
                }
                _mlRefreshTrigger.value = System.currentTimeMillis()
                runBacktest(symbol, rep.timeframe, _unseenTestBacktestOnly.value)
            } else {
                _lastMlTrainingSummary.value =
                    "Training skipped: insufficient labeled samples for purged Train/Val/Test split."
            }
            _isTrainingMl.value = false
        }
    }

    fun selectBroker(brokerType: String, apiKey: String = "", apiSecret: String = "", isSandbox: Boolean = true) {
        _selectedBroker.value = brokerType
        when (brokerType) {
            "Upstox Pro" -> {
                agent.broker = UpstoxBrokerAdapter(accessToken = apiKey, isSandbox = isSandbox)
                marketRepository.switchFeedProvider(useUpstox = true, token = apiKey, sandbox = isSandbox)
            }
            "Zerodha Kite" -> {
                agent.broker = ZerodhaKiteAdapter(apiKey = apiKey, requestToken = apiSecret)
                marketRepository.switchFeedProvider(useUpstox = false)
            }
            "Angel One" -> {
                agent.broker = AngelOneAdapter(apiKey = apiKey, clientCode = apiSecret)
                marketRepository.switchFeedProvider(useUpstox = false)
            }
            else -> {
                agent.broker = PaperTradingBroker(portfolio.value.availableCash)
                marketRepository.switchFeedProvider(useUpstox = false)
            }
        }
    }
}

class TradingViewModelFactory(
    private val application: Application
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(TradingViewModel::class.java)) {
            val db = QuantDatabase.getInstance(application)
            val localStore = HistoricalDataLocalStore(db.historicalDataDao())
            val repo = MarketRepository(localStore = localStore)
            val modelStore = MlModelStore(File(application.filesDir, "quantai_rf_model.json"))
            return TradingViewModel(
                marketRepository = repo,
                modelStore = modelStore
            ) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
    }
}

