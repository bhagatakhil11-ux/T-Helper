package com.example

import com.example.data.model.Candle
import com.example.data.model.DecisionType
import com.example.data.model.Exchange
import com.example.data.model.MarketInstrument
import com.example.data.model.Order
import com.example.data.model.OrderSide
import com.example.data.model.OrderStatus
import com.example.data.model.OrderType
import com.example.data.model.Portfolio
import com.example.data.model.Position
import com.example.data.model.RiskSettings
import com.example.data.repository.MarketSessionDetector
import com.example.engine.backtest.BacktestEngine
import com.example.engine.broker.IndianStatutoryLevies
import com.example.engine.broker.PaperTradingBroker
import com.example.engine.indicators.CandlestickPattern
import com.example.engine.indicators.TechnicalIndicators
import com.example.engine.ml.QuantMlEngine
import com.example.engine.risk.RiskManagementEngine
import com.example.engine.strategy.EnsembleDecisionEngine
import com.example.data.model.FeedStatus
import com.example.data.model.MlMovementClass
import com.example.data.repository.MarketRepository
import com.example.engine.ml.MlModelStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs

class QuantTradingEngineTest {

    private fun generateMockCandles(trend: Double, count: Int = 45): List<Candle> {
        val list = mutableListOf<Candle>()
        var price = 1000.0
        val now = System.currentTimeMillis()

        for (i in 0 until count) {
            val open = price
            val close = open + trend
            val high = maxOf(open, close) + 2.0
            val low = minOf(open, close) - 2.0
            list.add(Candle(now - (count - i) * 60000L, open, high, low, close, 50000L))
            price = close
        }
        return list
    }

    @Test
    fun testRsiCalculation() {
        val uptrendCandles = generateMockCandles(trend = 5.0, count = 30)
        val rsiUptrend = TechnicalIndicators.calculateRSI(uptrendCandles, 14)
        assertTrue("RSI on steady uptrend should be overbought (> 65), was $rsiUptrend", rsiUptrend > 65.0)

        val downtrendCandles = generateMockCandles(trend = -5.0, count = 30)
        val rsiDowntrend = TechnicalIndicators.calculateRSI(downtrendCandles, 14)
        assertTrue("RSI on steady downtrend should be oversold (< 35), was $rsiDowntrend", rsiDowntrend < 35.0)
    }

    @Test
    fun testBollingerBands() {
        val candles = generateMockCandles(trend = 1.0, count = 30)
        val bb = TechnicalIndicators.calculateBollingerBands(candles, period = 20, k = 2.0)
        assertTrue("Upper band should be strictly greater than middle", bb.upper > bb.middle)
        assertTrue("Middle band should be strictly greater than lower", bb.middle > bb.lower)
        assertTrue("Bandwidth should be positive", bb.bandwidth > 0.0)
    }

    @Test
    fun testSupportResistanceAndRegime() {
        val candles = generateMockCandles(trend = 2.0, count = 35)
        val (support, resistance) = TechnicalIndicators.calculateSupportResistance(candles, lookback = 20)
        assertTrue("Resistance should exceed support", resistance >= support)

        val snapshot = TechnicalIndicators.computeSnapshot(candles)
        val regime = TechnicalIndicators.detectMarketRegime(candles, snapshot)
        assertNotNull(regime)
        assertTrue(regime.isNotBlank())
    }

    @Test
    fun testUntrainedMlEngineReportsHonestState() {
        val mlEngine = QuantMlEngine()
        assertFalse("Fresh QuantMlEngine must not pretend to be trained", mlEngine.isTrained)

        val candles = generateMockCandles(trend = 3.0, count = 40)
        val features = mlEngine.extractFeatures(candles)
        assertNotNull("Features must be successfully extracted from historical candles", features)
        assertEquals(8, features!!.toArray().size)

        val prediction = mlEngine.predict(candles)
        assertNotNull("ML engine must output valid placeholder prediction", prediction)
        assertFalse("Prediction before training must have isTrained=false", prediction!!.isTrained)
        assertEquals(0, prediction.trainingSampleCount)
        assertEquals(0.0, prediction.outOfSampleAccuracy, 1e-9)

        val probSum = prediction.probUpward + prediction.probDownward + prediction.probSideways
        assertTrue("Probabilities must sum to 1.0, was $probSum", abs(probSum - 1.0) < 1e-6)
    }

    @Test
    fun testCausalFeatureParityZeroLookAhead() {
        val repo = MarketRepository()
        val fullCandles = repo.getCandles("RELIANCE", "1m")
        assertTrue("MarketRepository should initialize 400 candles", fullCandles.size >= 400)

        val mlEngine = QuantMlEngine()
        // Wait until warm-up stabilizes (after index 40 both MACD signal and Wilder RSI/ATR match)
        // Even from index 25, causal series and point-in-time extractFeatures must agree closely;
        // check indices 26..80 where prefix length matches exact TechnicalIndicators calculation.
        val dataset = mlEngine.generateDataset(fullCandles, forwardHorizon = 5)
        assertEquals(fullCandles.size - 25 - 5, dataset.size)

        for (sample in dataset.take(30)) {
            val idx = sample.candleIndex
            val pointInTime = mlEngine.extractFeatures(fullCandles.take(idx + 1), idx)!!
            val arrPoint = pointInTime.toArray()
            for (f in arrPoint.indices) {
                val diff = abs(sample.features[f] - arrPoint[f])
                assertTrue(
                    "Causal mismatch at index=$idx feature=$f: batch=${sample.features[f]} vs prefix=${arrPoint[f]}",
                    diff < 1e-6
                )
            }
        }
    }

    @Test
    fun testPurgedRandomForestTrainingValidationAndPersistence() {
        val repo = MarketRepository()
        val candles = repo.getCandles("RELIANCE", "1m")
        val mlEngine = QuantMlEngine()

        val startMs = System.currentTimeMillis()
        val outcome = mlEngine.trainAndValidate(
            candles = candles,
            symbol = "RELIANCE",
            timeframe = "1m",
            forwardHorizon = 5,
            seed = 42L
        )
        val elapsedMs = System.currentTimeMillis() - startMs

        assertNotNull("Training outcome must not be null for 400 candles", outcome)
        val rep = outcome!!.report

        assertTrue("MarketRepository candles are synthetic", rep.isSyntheticData)
        assertEquals(candles.size - 30, rep.totalSamples)
        assertEquals(10, rep.purgedSamples) // 2 purge gaps * forwardHorizon(5)
        assertEquals(rep.totalSamples, rep.trainSamples + rep.validationSamples + rep.testSamples + rep.purgedSamples)
        assertTrue("Walk-forward folds should execute", rep.walkForwardFolds >= 2)

        // Check feature importances sum to 1.0 (across all 8 features including ATR_Ratio)
        assertEquals(8, rep.featureImportances.size)
        val impSum = rep.featureImportances.values.sum()
        assertTrue("Feature importances must sum to 1.0, was $impSum", abs(impSum - 1.0) < 1e-6)

        // Check confusion matrix total equals testSamples
        val matrixSum = rep.confusionMatrix.sumOf { row -> row.sum() }
        assertEquals(rep.testSamples, matrixSum)

        // Print real observed validation output for verification report
        println("=== QUANTAI_ML_VERIFICATION_REPORT ===")
        println("symbol=${rep.trainedSymbol} tf=${rep.timeframe} synthetic=${rep.isSyntheticData} trainMs=$elapsedMs")
        println("samples total=${rep.totalSamples} train=${rep.trainSamples} val=${rep.validationSamples} test=${rep.testSamples} purged=${rep.purgedSamples}")
        println("trainClassCounts=${rep.trainClassCounts}")
        println("testClassCounts=${rep.testClassCounts}")
        println("testAccuracy=${rep.testAccuracy} majorityBaseline=${rep.baselineMajorityAccuracy} momentumBaseline=${rep.baselineMomentumAccuracy}")
        println("macroF1=${rep.macroF1} logLoss=${rep.testLogLoss} brier=${rep.testBrierScore} wfAcc=${rep.walkForwardAccuracy} wfFolds=${rep.walkForwardFolds}")
        println("confusionMatrix=${rep.confusionMatrix}")
        println("classMetrics=${rep.classMetrics}")
        println("featureImportances=${rep.featureImportances}")
        println("passedGate=${rep.passedValidationGate} reason=${rep.validationGateReason}")
        println("=== END_VERIFICATION_REPORT ===")

        // Test Moshi persistence round-trip if deployed
        if (outcome.deployed) {
            assertTrue(mlEngine.isTrained)
            val predBefore = mlEngine.predict(candles)!!
            assertTrue(predBefore.isTrained)

            val tempFile = File.createTempFile("quantai_rf_test_", ".json")
            try {
                val store = MlModelStore(tempFile)
                val persisted = mlEngine.exportPersistedModel()!!
                assertTrue("Model store must save JSON successfully", store.save(persisted))

                val loaded = store.load()
                assertNotNull("Model store must reload saved JSON", loaded)

                val restoredEngine = QuantMlEngine()
                restoredEngine.loadPersistedModel(loaded!!)
                assertTrue(restoredEngine.isTrained)

                val predAfter = restoredEngine.predict(candles)!!
                assertEquals(predBefore.probUpward, predAfter.probUpward, 1e-9)
                assertEquals(predBefore.probDownward, predAfter.probDownward, 1e-9)
                assertEquals(predBefore.probSideways, predAfter.probSideways, 1e-9)
                assertEquals(predBefore.predictedClass, predAfter.predictedClass)
            } finally {
                tempFile.delete()
            }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun testViewModelSharesTrainedMlEngineWithEnsembleAndBacktester() {
        val dispatcher = UnconfinedTestDispatcher()
        Dispatchers.setMain(dispatcher)
        try {
            val sharedMl = QuantMlEngine()
            val sharedDecision = EnsembleDecisionEngine(mlEngine = sharedMl)
            val sharedBacktest = BacktestEngine(decisionEngine = sharedDecision)
            val repo = MarketRepository()

            assertSame(sharedMl, sharedDecision.mlEngine)
            val candles = repo.getCandles("RELIANCE", "1m")
            val outcome = sharedMl.trainAndValidate(candles, symbol = "RELIANCE")
            assertNotNull(outcome)
            if (outcome!!.deployed) {
                val inst = repo.instruments.value.first { it.symbol == "RELIANCE" }
                val sig = sharedDecision.evaluate(inst, candles, RiskSettings(), 500000.0)
                assertNotNull(sig.mlPrediction)
                assertTrue("EnsembleDecisionEngine must reflect trained ML engine", sig.mlPrediction!!.isTrained)
            }
            // Verify backtest engine can run with shared decision engine without error
            val bt = sharedBacktest.runBacktest("RELIANCE", candles)
            assertNotNull(bt)
        } finally {
            Dispatchers.resetMain()
        }
    }

    private fun assertSame(expected: Any, actual: Any) {
        org.junit.Assert.assertSame(expected, actual)
    }

    @Test
    fun testBenchmarkIndexTradabilityGuard() {
        val decisionEngine = EnsembleDecisionEngine()
        val benchmarkIndex = MarketInstrument(
            symbol = "NIFTY 50",
            name = "Nifty 50 Benchmark Index",
            exchange = Exchange.NSE,
            isTradable = false, // Not tradable directly
            currentPrice = 24150.0,
            openPrice = 24000.0,
            high24h = 24200.0,
            low24h = 23950.0,
            changePercent = 0.5,
            volume = 1000000L
        )

        val candles = generateMockCandles(trend = 2.0, count = 35)
        val signal = decisionEngine.evaluate(benchmarkIndex, candles, RiskSettings(), 500000.0)

        assertEquals("Benchmark indices must produce NO_TRADE", DecisionType.NO_TRADE, signal.type)
        assertTrue("Explanation must mention Benchmark Index", signal.rationale.contains("Benchmark Index"))
    }

    @Test
    fun testRiskManagementCircuitBreakerAndConsecutiveLosses() {
        val riskEngine = RiskManagementEngine()

        // 1. Drawdown circuit breaker
        val breachedPortfolio = Portfolio(
            totalCapital = 500000.0,
            availableCash = 480000.0,
            startingDayCapital = 500000.0
        )
        val testOrder = Order(id = "test-1", symbol = "RELIANCE", side = OrderSide.BUY, type = OrderType.MARKET, quantity = 10, price = 1250.0)
        val validation = riskEngine.validateOrder(testOrder, breachedPortfolio, emptyList(), RiskSettings(maxDailyDrawdownPercent = 2.0))
        assertFalse("Daily drawdown breaker must reject orders when loss exceeds threshold", validation.isApproved)

        // 2. Consecutive losses cooldown
        val consecutiveLossPortfolio = Portfolio(
            consecutiveLossCount = 3,
            lastLossTimestamp = System.currentTimeMillis() - 60000L // 1 minute ago
        )
        val lossValidation = riskEngine.validateOrder(testOrder, consecutiveLossPortfolio, emptyList(), RiskSettings(maxConsecutiveLosses = 3))
        assertFalse("Consecutive losses cooldown must block new orders", lossValidation.isApproved)
        assertTrue(lossValidation.rejectionReason.contains("CONSECUTIVE LOSS COOLDOWN"))
    }

    @Test
    fun testPaperTradingExecutionAndDuplicatePrevention() = runBlocking {
        val paperBroker = PaperTradingBroker(simulatedCash = 500000.0)
        val buyOrder = Order(
            id = "ord-1",
            idempotencyKey = "IDEMP-KEY-101",
            symbol = "INFY",
            side = OrderSide.BUY,
            type = OrderType.MARKET,
            quantity = 10,
            price = 1800.0
        )

        val result = paperBroker.placeOrder(buyOrder, currentMarketPrice = 1800.0, bidPrice = 1799.95, askPrice = 1800.05)
        assertTrue("Paper order should be filled", result.isSuccess)

        val filled = result.getOrNull()!!
        assertEquals(OrderStatus.FILLED, filled.status)
        assertTrue("Statutory levies must be calculated and recorded", filled.statutoryCharges > 0.0)

        // Duplicate order submission with same idempotency key must be blocked!
        val duplicateResult = paperBroker.placeOrder(buyOrder, currentMarketPrice = 1800.0, bidPrice = 1799.95, askPrice = 1800.05)
        assertFalse("Duplicate order with same idempotency key must be rejected", duplicateResult.isSuccess)
    }

    @Test
    fun testUpgradedBacktestEngine() {
        val backtestEngine = BacktestEngine()
        val candles = generateMockCandles(trend = 1.5, count = 45)

        val result = backtestEngine.runBacktest(
            symbol = "RELIANCE",
            candles = candles,
            initialCapital = 500000.0,
            riskSettings = RiskSettings()
        )

        assertNotNull(result)
        assertTrue("Equity curve must be non-empty", result.equityCurve.isNotEmpty())
        assertTrue("Expectancy should be computed", result.expectancy != 0.0 || result.totalTrades == 0)
    }

    @Test
    fun testMarketSessionDetection() {
        val session = MarketSessionDetector.getCurrentSession()
        assertNotNull(session)
    }
}
