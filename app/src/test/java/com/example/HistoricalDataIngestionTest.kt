package com.example

import com.example.data.model.AdjustmentPolicy
import com.example.data.model.Candle
import com.example.data.model.DataOrigin
import com.example.data.model.DataQualityThresholds
import com.example.data.model.DecisionType
import com.example.data.model.Exchange
import com.example.data.model.FeedStatus
import com.example.data.model.HistoricalDatasetMetadata
import com.example.data.model.MarketInstrument
import com.example.data.model.ResearchQualityStatus
import com.example.data.model.RiskSettings
import com.example.data.model.VerificationStatus
import com.example.data.repository.HistoricalCsvImporter
import com.example.data.repository.MarketRepository
import com.example.engine.backtest.BacktestEngine
import com.example.engine.broker.CostModelConfig
import com.example.engine.broker.IndianStatutoryLevies
import com.example.engine.ml.QuantMlEngine
import com.example.engine.strategy.EnsembleDecisionEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import kotlin.math.sin

class HistoricalDataIngestionTest {

    @Test
    fun `sample template parses cleanly with IMPORTED_CSV_UNVERIFIED provenance and SHA256 datasetId`() {
        val res = HistoricalCsvImporter.parseAndValidateText(
            csvText = HistoricalCsvImporter.SAMPLE_CSV_TEMPLATE,
            defaultSymbol = "RELIANCE",
            timeframe = "1m",
            sourceName = "SampleTemplate.csv"
        )
        assertTrue(res.success)
        assertEquals(1, res.importedDatasets.size)
        val ds = res.importedDatasets.first()
        val meta = ds.metadata!!
        assertEquals("RELIANCE", ds.symbol)
        assertEquals("1m", ds.timeframe)
        assertEquals(3, ds.acceptedCount)
        assertEquals(0, ds.rejectedRowCount)
        assertEquals(0, ds.duplicateCount)
        assertEquals(0, ds.missingCandleCount)
        assertTrue(ds.candles.all { it.feedStatus == FeedStatus.HISTORICAL })
        assertEquals(DataOrigin.IMPORTED_CSV, meta.sourceType)
        assertEquals(VerificationStatus.IMPORTED_CSV_UNVERIFIED, meta.verificationStatus)
        assertEquals("Asia/Kolkata", meta.timezone)
        assertEquals(AdjustmentPolicy.UNKNOWN_UNVERIFIED, meta.adjustmentPolicy)
        assertEquals(64, meta.sha256Checksum.length)
        assertTrue(meta.datasetId.startsWith("RELIANCE_1m_"))
    }

    @Test
    fun `parses valid multi-timeframe formats, 12-digit epoch ms, NSE holidays, and corporate action discontinuities`() {
        // Out-of-order 5m candles using ISO-8601 +05:30 and standard timestamps
        val csv = """
            timestamp,open,high,low,close,volume
            2025-11-10T09:25:00+05:30,1275.00,1278.00,1274.00,1277.50,82000
            2025-11-10 09:15:00,1270.00,1274.50,1268.25,1273.10,145200
            2025-11-10 09:20:00,1273.10,1276.00,1272.00,1275.00,98450
        """.trimIndent()

        val res = HistoricalCsvImporter.parseAndValidateText(
            csvText = csv,
            defaultSymbol = "RELIANCE",
            timeframe = "5m",
            sourceName = "reliance_5m.csv"
        )
        assertTrue(res.success)
        val ds = res.importedDatasets.first()
        assertEquals(3, ds.acceptedCount)
        assertEquals(0, ds.missingCandleCount)
        assertTrue("Expected chronological sort", ds.candles[0].timestamp < ds.candles[1].timestamp)
        assertTrue("Expected chronological sort", ds.candles[1].timestamp < ds.candles[2].timestamp)
        assertTrue("Should warn that input was unsorted", ds.warnings.any { it.contains("unsorted", ignoreCase = true) })

        // Verify 1D format with NSE holiday skipping (2025-08-15 is Independence Day Friday)
        val holidayDailyCsv = """
            date;open;high;low;close;volume
            14-Aug-2025;4100.0;4150.0;4090.0;4140.0;1200000
            18-Aug-2025;4145.0;4180.0;4130.0;4175.0;1350000
        """.trimIndent()
        val holidayRes = HistoricalCsvImporter.parseAndValidateText(
            csvText = holidayDailyCsv,
            defaultSymbol = "TCS",
            timeframe = "1D",
            sourceName = "tcs_holiday_daily.csv"
        )
        assertTrue(holidayRes.success)
        val holidayMeta = holidayRes.importedDatasets.first().metadata!!
        assertEquals(0, holidayMeta.missingCandleCount)
        assertEquals(1, holidayMeta.holidaySkippedCount)

        // Verify 12-digit epoch milliseconds (1999-01-01T00:00:00Z = 915148800000L) & corporate action >= 22% jump flag
        val epochAndSplitCsv = """
            timestamp,open,high,low,close,volume
            915148800000,1000.0,1020.0,990.0,1000.0,500000
            915235200000,500.0,510.0,495.0,505.0,1000000
        """.trimIndent()
        val splitRes = HistoricalCsvImporter.parseAndValidateText(
            csvText = epochAndSplitCsv,
            defaultSymbol = "INFY",
            timeframe = "1D",
            sourceName = "infy_unadj.csv"
        )
        assertTrue(splitRes.success)
        val splitDs = splitRes.importedDatasets.first()
        assertEquals(915148800000L, splitDs.candles[0].timestamp)
        assertEquals(1000.0, splitDs.candles[0].close, 1e-9)
        assertEquals(500.0, splitDs.candles[1].open, 1e-9)
        assertTrue("Expected corporate action jump flag without altering prices", splitDs.metadata!!.corporateActionFlags.isNotEmpty())
    }

    @Test
    fun `rejects invalid OHLCV rows, negative values, bad timestamps, and duplicates without silent modification`() {
        val csv = """
            timestamp,open,high,low,close,volume
            2025-11-10 09:15:00,1270.00,1274.50,1268.25,1273.10,145200
            bad_date_row,1273.10,1275.00,1272.00,1274.00,90000
            2025-11-10 09:16:00,-1273.10,1275.00,1272.00,1274.00,90000
            2025-11-10 09:17:00,1273.10,1274.00,1272.00,1276.00,90000
            2025-11-10 09:18:00,1273.10,1275.00,1274.00,1273.50,90000
            2025-11-10 09:19:00,1273.10,1275.00,1272.00,1274.00,-500
            2025-11-10 09:15:00,1271.00,1279.00,1269.00,1278.00,200000
            2025-11-10 09:20:00,1273.10,1276.50,1271.50,1275.80,110000
        """.trimIndent()

        val res = HistoricalCsvImporter.parseAndValidateText(
            csvText = csv,
            defaultSymbol = "RELIANCE",
            timeframe = "1m",
            sourceName = "dirty_feed.csv"
        )
        assertTrue(res.success)
        assertEquals(8, res.totalRowsRead)
        assertEquals(2, res.totalAcceptedCandles)
        assertEquals(6, res.totalRejectedRows)
        assertEquals(1, res.totalDuplicates)
        assertEquals(4, res.totalMissingCandles)

        val accepted = res.importedDatasets.first().candles
        assertEquals(1270.00, accepted[0].open, 1e-9)
        assertEquals(145200L, accepted[0].volume)
        assertEquals(1275.80, accepted[1].close, 1e-9)
    }

    @Test
    fun `multi-symbol CSV fails atomically when one symbol fails all rows, and valid multi-symbol isolates provenance`() {
        val partialBadMultiCsv = """
            symbol,timestamp,open,high,low,close,volume
            WIPRO.NS,2025-11-10 09:15:00,560.0,563.0,559.0,562.0,100000
            TCS,2025-11-10 09:15:00,-4100.0,4150.0,4090.0,4140.0,50000
        """.trimIndent()
        val atomicFailRes = HistoricalCsvImporter.parseAndValidateText(
            csvText = partialBadMultiCsv,
            defaultSymbol = "RELIANCE",
            timeframe = "1m",
            sourceName = "atomic_fail.csv",
            useSymbolColumnIfPresent = true,
            atomicMultiSymbol = true
        )
        assertFalse("Multi-symbol batch must fail atomically if any symbol has 0 valid rows", atomicFailRes.success)
        assertTrue(atomicFailRes.importedDatasets.isEmpty())

        val csv = """
            symbol,timestamp,open,high,low,close,volume
            WIPRO.NS,2025-11-10 09:15:00,560.0,563.0,559.0,562.0,100000
            WIPRO.NS,2025-11-10 09:16:00,562.0,564.5,561.0,564.0,95000
            INFY,2025-11-10 09:15:00,1840.0,1845.0,1838.0,1844.0,120000
        """.trimIndent()

        val res = HistoricalCsvImporter.parseAndValidateText(
            csvText = csv,
            defaultSymbol = "RELIANCE",
            timeframe = "1m",
            sourceName = "multi_nse.csv",
            useSymbolColumnIfPresent = true
        )
        assertTrue(res.success)
        assertEquals(2, res.importedDatasets.size)
        val wiproDs = res.importedDatasets.first { it.symbol == "WIPRO" }
        val infyDs = res.importedDatasets.first { it.symbol == "INFY" }

        val repo = MarketRepository()
        try {
            repo.ingestHistoricalCandles(
                symbol = "RELIANCE",
                candles = listOf(Candle(1700000000000L, 100.0, 102.0, 99.0, 101.0, 1000L, FeedStatus.SIMULATED)),
                timeframe = "1m",
                sourceName = "BadFake"
            )
            fail("Expected IllegalArgumentException when attempting to ingest SIMULATED candles as HISTORICAL")
        } catch (_: IllegalArgumentException) {
            // Expected strict provenance rejection
        }

        repo.ingestHistoricalCandles("WIPRO", wiproDs.candles, "1m", "multi_nse.csv", wiproDs.metadata)
        repo.ingestHistoricalCandles("INFY", infyDs.candles, "1m", "multi_nse.csv", infyDs.metadata)

        assertTrue("WIPRO should be dynamically registered", repo.instruments.value.any { it.symbol == "WIPRO" })
        repo.refreshProvenance("WIPRO", "1m")
        assertEquals(FeedStatus.HISTORICAL, repo.feedStatus.value)
        assertEquals(VerificationStatus.IMPORTED_CSV_UNVERIFIED, repo.verificationStatus.value)
        assertEquals(2, repo.getCandles("WIPRO", "1m").size)

        // Ensure tickMarket() does not contaminate historical datasets or override verification status
        repo.tickMarket()
        assertEquals(2, repo.getCandles("WIPRO", "1m").size)
        assertEquals(564.0, repo.getCandles("WIPRO", "1m").last().close, 1e-9)
        assertEquals(VerificationStatus.IMPORTED_CSV_UNVERIFIED, repo.verificationStatus.value)

        // Switching to RELIANCE (no historical CSV imported) stays SIMULATED
        repo.refreshProvenance("RELIANCE", "1m")
        assertEquals(FeedStatus.SIMULATED, repo.feedStatus.value)
        assertEquals(VerificationStatus.SIMULATED_NOT_REAL, repo.verificationStatus.value)
    }

    @Test
    fun `deterministic 240-bar test fixture preserves TEST_FIXTURE provenance, exact purge bounds, and prevents cross-context or stale leakage`() {
        val barCount = 240
        val csvText = buildOscillatingFixtureCsv(barCount = barCount, intervalMinutes = 5)

        val importRes = HistoricalCsvImporter.parseAndValidateText(
            csvText = csvText,
            defaultSymbol = "RELIANCE",
            timeframe = "5m",
            sourceName = "synthetic_oscillating_fixture_240.csv",
            sourceType = DataOrigin.TEST_FIXTURE,
            verificationStatus = VerificationStatus.TEST_FIXTURE_NOT_MARKET_DATA
        )
        assertTrue(importRes.success)
        val ds = importRes.importedDatasets.first()
        val meta = ds.metadata!!
        assertEquals(barCount, ds.acceptedCount)
        assertEquals(DataOrigin.TEST_FIXTURE, meta.sourceType)
        assertEquals(VerificationStatus.TEST_FIXTURE_NOT_MARKET_DATA, meta.verificationStatus)
        assertTrue("TEST_FIXTURE must stay FeedStatus.SIMULATED", ds.candles.all { it.feedStatus == FeedStatus.SIMULATED })

        val repo = MarketRepository()
        repo.ingestHistoricalCandles(
            symbol = ds.symbol,
            candles = ds.candles,
            timeframe = ds.timeframe,
            sourceName = meta.sourceName,
            metadata = meta
        )
        repo.refreshProvenance("RELIANCE", "5m")
        assertFalse("TEST_FIXTURE must return isUsingHistoricalData=false", repo.isUsingHistoricalData("RELIANCE", "5m"))
        assertEquals(FeedStatus.SIMULATED, repo.feedStatus.value)
        assertEquals(VerificationStatus.TEST_FIXTURE_NOT_MARKET_DATA, repo.verificationStatus.value)

        val storedCandles = repo.getCandles("RELIANCE", "5m")
        assertEquals(barCount, storedCandles.size)

        val mlEngine = QuantMlEngine()
        val trainOutcome = mlEngine.trainAndValidate(
            candles = storedCandles,
            symbol = "RELIANCE",
            timeframe = "5m",
            forwardHorizon = 5,
            seed = 42L,
            datasetMeta = meta
        )
        assertNotNull(trainOutcome)
        val rep = trainOutcome!!.report

        // Provenance & research quality assertions
        assertTrue("TEST_FIXTURE must always report isSyntheticData=true", rep.isSyntheticData)
        assertEquals(DataOrigin.TEST_FIXTURE, rep.sourceType)
        assertEquals(VerificationStatus.TEST_FIXTURE_NOT_MARKET_DATA, rep.verificationStatus)
        assertEquals(meta.datasetId, rep.datasetId)
        assertEquals(meta.sha256Checksum, rep.datasetChecksum)
        assertEquals(QuantMlEngine.FEATURE_VERSION, rep.featureVersion)
        assertEquals(ResearchQualityStatus.LOW_CONFIDENCE_INSUFFICIENT_DATA, rep.researchQualityStatus)
        assertEquals(15000, rep.researchMinRequiredBars)

        // Exact split & purge boundary verification for 240 candles (indices 0..239):
        // Labeled samples = 210 (candle indices 25..234)
        assertEquals(210, rep.totalSamples)
        assertEquals(126, rep.trainSamples)       // samples 0..125 -> candles 25..150
        assertEquals(42, rep.validationSamples)   // samples 131..172 -> candles 156..197
        assertEquals(32, rep.testSamples)         // samples 178..209 -> candles 203..234
        assertEquals(10, rep.purgedSamples)       // purge1 (151..155) + purge2 (198..202)
        assertEquals(203, rep.testStartCandleIndex)

        val decisionEngine = EnsembleDecisionEngine(mlEngine = mlEngine)

        // Cross-symbol check
        val tcsInst = MarketInstrument("TCS", "TCS", Exchange.NSE, true, 4100.0, 4090.0, 4120.0, 4080.0, 0.2, 50000L, feedStatus = FeedStatus.SIMULATED)
        val tcsSig = decisionEngine.evaluate(tcsInst, repo.getCandles("TCS", "5m"), RiskSettings(), 500000.0, timeframe = "5m")
        assertTrue("TCS must not use RELIANCE-trained ML model", tcsSig.mlPrediction == null || !tcsSig.mlPrediction!!.isTrained)

        // Cross-timeframe check (RELIANCE 5m model must NOT apply to RELIANCE 1m)
        assertFalse(mlEngine.isModelValidFor("RELIANCE", "1m", meta))
        val wrongTfPred = mlEngine.predictForContext(storedCandles, "RELIANCE", "1m", meta)
        assertNotNull(wrongTfPred)
        assertFalse("Cross-timeframe inference must be blocked", wrongTfPred!!.isTrained)

        // Stale dataset checksum / replacement check
        val mutatedMeta = meta.copy(
            datasetId = "RELIANCE_5m_different00",
            sha256Checksum = "0".repeat(64)
        )
        assertFalse("Model bound to old checksum must not validate on mutated dataset", mlEngine.isModelValidFor("RELIANCE", "5m", mutatedMeta))
        assertFalse(mlEngine.predictForContext(storedCandles, "RELIANCE", "5m", mutatedMeta)!!.isTrained)

        // Backtest strictly on Unseen Test Split (bars 203..239 -> 37 evaluated bars)
        val backtestEngine = BacktestEngine(decisionEngine = decisionEngine)
        val oosBt = backtestEngine.runBacktest(
            symbol = "RELIANCE",
            candles = storedCandles,
            initialCapital = 500000.0,
            riskSettings = RiskSettings(),
            timeframe = "5m",
            sourceName = meta.sourceName,
            startBarIndex = rep.testStartCandleIndex,
            evaluationScope = "Fixed Holdout Unseen Test Split (bars ${rep.testStartCandleIndex}..${storedCandles.size - 1})",
            datasetMeta = meta
        )

        assertEquals(FeedStatus.SIMULATED, oosBt.feedStatus)
        assertEquals(DataOrigin.TEST_FIXTURE, oosBt.sourceType)
        assertEquals(VerificationStatus.TEST_FIXTURE_NOT_MARKET_DATA, oosBt.verificationStatus)
        assertEquals("5m", oosBt.timeframe)
        assertEquals(barCount, oosBt.candleCount)
        assertEquals(37, oosBt.evaluatedBarCount)
        assertEquals(meta.datasetId, oosBt.datasetId)
        assertEquals(ResearchQualityStatus.LOW_CONFIDENCE_INSUFFICIENT_DATA, oosBt.researchQualityStatus)
        assertNotNull("Backtest should attach matching ML validation report", oosBt.mlValidationReport)
        if (oosBt.totalTrades < 5) {
            assertNull("Sharpe ratio must be null/N/A when totalTrades < 5", oosBt.sharpeRatio)
            assertTrue(oosBt.sharpeStatusReason.contains("N/A"))
        }

        // Verify invalidation clears model completely
        mlEngine.invalidateModel()
        assertFalse(mlEngine.isTrained)
        assertNull(mlEngine.getValidationReport())

        println("=== PHASE2_1_TEST_FIXTURE_REPORT ===")
        println("symbol=${oosBt.symbol} tf=${oosBt.timeframe} origin=${oosBt.sourceType} verification=${oosBt.verificationStatus} datasetId=${oosBt.datasetId}")
        println("totalBars=${oosBt.candleCount} evaluatedBars=${oosBt.evaluatedBarCount} testStartIdx=${rep.testStartCandleIndex} scope=${oosBt.evaluationScope}")
        println("mlTrain=${rep.trainSamples} mlVal=${rep.validationSamples} mlTest=${rep.testSamples} purged=${rep.purgedSamples}")
        println("mlTestAcc=${rep.testAccuracy} majBase=${rep.baselineMajorityAccuracy} momBase=${rep.baselineMomentumAccuracy} macroF1=${rep.macroF1} logLoss=${rep.testLogLoss} brier=${rep.testBrierScore}")
        println("classMetrics=${rep.classMetrics}")
        println("confusionMatrix=${rep.confusionMatrix}")
        println("trades=${oosBt.totalTrades} winRate=${oosBt.winRatePercent}% pf=${oosBt.profitFactor} expectancy=${oosBt.expectancy} sharpe=${oosBt.sharpeRatio} (${oosBt.sharpeStatusReason}) maxDD=${oosBt.maxDrawdownPercent}% netProfit=${oosBt.netProfit}")
        println("=== END_PHASE2_1_REPORT ===")
    }

    @Test
    fun `backtest executes pending entry at next bar open, triggers gap-down stop loss at open, tracks unrealized drawdown, and returns null Sharpe on insufficient trades`() {
        // Build 36 candles (>= 35 min guard): bars 0..33 close at 1000.0, bar 34 gaps up to Open=1100.0,
        // bar 35 gaps down to Open=900.0 (below SL) forcing conservative gap-open exit.
        val baseTs = 1762746300000L // 2025-11-10 09:15 IST
        val candles = mutableListOf<Candle>()
        for (i in 0..33) {
            candles.add(Candle(baseTs + i * 60_000L, 1000.0, 1005.0, 995.0, 1000.0, 100_000L, FeedStatus.HISTORICAL))
        }
        // Bar 34: opens +100 INR higher than bar 33 close (1000 -> 1100), low stays above SL
        candles.add(Candle(baseTs + 34 * 60_000L, 1100.0, 1110.0, 1095.0, 1105.0, 200_000L, FeedStatus.HISTORICAL))
        // Bar 35: severe gap down open = 900.0 (< SL) -> must force gap-down SL exit at 900.0 open!
        candles.add(Candle(baseTs + 35 * 60_000L, 900.0, 920.0, 895.0, 910.0, 400_000L, FeedStatus.HISTORICAL))

        // Deterministic strategy that emits BUY strictly at bar 33 close (candles.size == 34)
        // Ensemble composite = (1.0 * 0.60) = 0.60 >= 0.42
        val buyAtBar33Strategy = object : com.example.engine.strategy.TradingStrategy {
            override val name = "Bar33Trigger"
            override val weight = 1.0
            override fun evaluate(
                symbol: String,
                candles: List<Candle>,
                snapshot: com.example.engine.indicators.IndicatorSnapshot,
                atr: Double
            ) = com.example.data.model.StrategyScore(
                strategyName = name,
                signal = if (candles.size == 34) DecisionType.BUY else DecisionType.HOLD,
                score = if (candles.size == 34) 1.0 else 0.0,
                weight = 1.0,
                confidence = if (candles.size == 34) 1.0 else 0.0,
                entryCondition = "Bar 33 trigger",
                exitCondition = "SL/TP",
                rationale = "Deterministic Next-Bar Open execution check"
            )
        }
        val btEngine = BacktestEngine(
            decisionEngine = EnsembleDecisionEngine(strategies = listOf(buyAtBar33Strategy))
        )
        val meta1 = HistoricalDatasetMetadata(
            symbol = "RELIANCE",
            timeframe = "1D",
            sourceName = "synthetic_gap_test",
            feedStatus = FeedStatus.SIMULATED,
            importedAt = 0L,
            candleCount = candles.size,
            startTimestamp = candles.first().timestamp,
            endTimestamp = candles.last().timestamp,
            datasetId = "REL_GAP",
            sourceType = DataOrigin.TEST_FIXTURE,
            verificationStatus = VerificationStatus.TEST_FIXTURE_NOT_MARKET_DATA,
            sha256Checksum = "a".repeat(64)
        )
        val result = btEngine.runBacktest(
            symbol = "RELIANCE",
            candles = candles,
            initialCapital = 500000.0,
            riskSettings = RiskSettings(),
            slippagePercent = 0.001, // 0.1%
            timeframe = "1D",
            startBarIndex = 25,
            datasetMeta = meta1
        )

        // Verify strict Next-Bar Open execution, gap-down exit, MTM drawdown, levies, & N/A Sharpe
        assertEquals("Exactly 1 closed trade expected", 1, result.totalTrades)
        assertNull("Sharpe must be null when evaluatedBars < 30 and totalTrades < 5", result.sharpeRatio)
        assertTrue(result.sharpeStatusReason.contains("N/A"))
        assertTrue("Expected positive slippage deduction", result.totalSlippageDeducted > 0.0)
        assertTrue("Expected positive statutory levy deduction", result.totalChargesDeducted > 0.0)
        assertTrue("Gap-down stop loss must realize loss, netProfit=${result.netProfit}", result.netProfit < 0.0)
        assertTrue("Max drawdown must exceed 0 on losing gap-down trade", result.maxDrawdownPercent > 0.0)

        // Proving bar[33].close was NOT used for entry: raise bar[34].open from 1100.0 to 1200.0.
        // Because sizing/SL/TP were computed at bar[33].close = 1000.0, raising bar[34].open by +100
        // deepens the fill-to-exit loss when exiting at bar[35].open = 900.0.
        val higherGapCandles = candles.toMutableList()
        higherGapCandles[34] = candles[34].copy(open = 1200.0, high = 1210.0, low = 1195.0, close = 1205.0)
        val higherGapResult = btEngine.runBacktest(
            symbol = "RELIANCE",
            candles = higherGapCandles,
            initialCapital = 500000.0,
            riskSettings = RiskSettings(),
            slippagePercent = 0.001,
            timeframe = "1D",
            startBarIndex = 25,
            datasetMeta = meta1.copy(datasetId = "REL_GAP_2", sha256Checksum = "b".repeat(64))
        )
        assertEquals(1, higherGapResult.totalTrades)
        assertTrue(
            "Entry must fill at bar[34].open (got netProfit ${result.netProfit} at open=1100 vs ${higherGapResult.netProfit} at open=1200)",
            higherGapResult.netProfit < result.netProfit
        )

        // Verify IndianStatutoryLevies differs cleanly between delivery (1D) and intraday modes
        val deliveryCost = IndianStatutoryLevies.calculate(100_000.0, isBuy = true, config = CostModelConfig(isIntraday = false))
        val intradayCost = IndianStatutoryLevies.calculate(100_000.0, isBuy = true, config = CostModelConfig(isIntraday = true))
        assertNotEquals("Delivery buy STT (0.1%) vs intraday buy STT (0%) must differ", deliveryCost.stt, intradayCost.stt, 1e-6)

        // Verify Research Quality Threshold check
        assertEquals(ResearchQualityStatus.LOW_CONFIDENCE_INSUFFICIENT_DATA, DataQualityThresholds.evaluateQuality(240, "1D"))
        assertEquals(ResearchQualityStatus.RESEARCH_GRADE, DataQualityThresholds.evaluateQuality(1500, "1D"))
    }

    private fun buildOscillatingFixtureCsv(barCount: Int, intervalMinutes: Int): String {
        val csvBuilder = StringBuilder("timestamp,open,high,low,close,volume\n")
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("Asia/Kolkata")
        }
        val baseCal = java.util.Calendar.getInstance(TimeZone.getTimeZone("Asia/Kolkata")).apply {
            set(2025, java.util.Calendar.NOVEMBER, 10, 9, 15, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }
        var price = 1270.0
        for (i in 0 until barCount) {
            val ts = fmt.format(baseCal.time)
            val delta = sin(i / 6.0) * 4.5 + (if (i % 7 == 0) 1.2 else -0.4)
            val open = price
            val close = (open + delta).coerceAtLeast(100.0)
            val high = maxOf(open, close) + 2.2
            val low = minOf(open, close) - 2.2
            val vol = 95000L + (i * 450L)
            csvBuilder.append("$ts,${String.format(Locale.US, "%.2f", open)},${String.format(Locale.US, "%.2f", high)},${String.format(Locale.US, "%.2f", low)},${String.format(Locale.US, "%.2f", close)},$vol\n")
            price = close
            baseCal.add(java.util.Calendar.MINUTE, intervalMinutes)
        }
        return csvBuilder.toString()
    }
}

