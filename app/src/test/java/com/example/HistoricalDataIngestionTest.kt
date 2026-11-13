package com.example

import com.example.data.model.Candle
import com.example.data.model.FeedStatus
import com.example.data.model.MarketInstrument
import com.example.data.model.Exchange
import com.example.data.model.RiskSettings
import com.example.data.repository.HistoricalCsvImporter
import com.example.data.repository.MarketRepository
import com.example.engine.backtest.BacktestEngine
import com.example.engine.ml.QuantMlEngine
import com.example.engine.strategy.EnsembleDecisionEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import kotlin.math.sin

class HistoricalDataIngestionTest {

    @Test
    fun `sample template parses cleanly with zero errors`() {
        val res = HistoricalCsvImporter.parseAndValidateText(
            csvText = HistoricalCsvImporter.SAMPLE_CSV_TEMPLATE,
            defaultSymbol = "RELIANCE",
            timeframe = "1m",
            sourceName = "SampleTemplate.csv"
        )
        assertTrue(res.success)
        assertEquals(1, res.importedDatasets.size)
        val ds = res.importedDatasets.first()
        assertEquals("RELIANCE", ds.symbol)
        assertEquals("1m", ds.timeframe)
        assertEquals(3, ds.acceptedCount)
        assertEquals(0, ds.rejectedRowCount)
        assertEquals(0, ds.duplicateCount)
        assertEquals(0, ds.missingCandleCount)
        assertTrue(ds.candles.all { it.feedStatus == FeedStatus.HISTORICAL })
    }

    @Test
    fun `parses valid multi-timeframe formats and sorts unsorted rows chronologically`() {
        // Out-of-order 5m candles using ISO-8601 +05:30, epoch seconds, and standard timestamp
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

        // Verify 1D format with NSE dd-MMM-yyyy and semicolon delimiter + skipped weekday detection
        val dailyCsv = """
            date;open;high;low;close;volume
            10-Nov-2025;4100.0;4150.0;4090.0;4140.0;1200000
            13-Nov-2025;4145.0;4180.0;4130.0;4175.0;1350000
        """.trimIndent()
        val dailyRes = HistoricalCsvImporter.parseAndValidateText(
            csvText = dailyCsv,
            defaultSymbol = "TCS",
            timeframe = "1D",
            sourceName = "tcs_daily.csv"
        )
        assertTrue(dailyRes.success)
        val dailyDs = dailyRes.importedDatasets.first()
        assertEquals(2, dailyDs.acceptedCount)
        // Nov 11 (Tue) and Nov 12 (Wed) are 2 weekdays strictly between Mon Nov 10 and Thu Nov 13
        assertEquals(2, dailyDs.missingCandleCount)
        assertTrue(dailyDs.warnings.any { it.contains("2 missing weekday", ignoreCase = true) })
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
        // 09:16..09:19 missing (4 gaps between 09:15 and 09:20)
        assertEquals(4, res.totalMissingCandles)

        val accepted = res.importedDatasets.first().candles
        // Verify original values were NOT silently altered
        assertEquals(1270.00, accepted[0].open, 1e-9)
        assertEquals(145200L, accepted[0].volume)
        assertEquals(1275.80, accepted[1].close, 1e-9)
    }

    @Test
    fun `multi-symbol CSV isolates datasets cleanly and market repository isolates from simulated feed`() {
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
        assertEquals(2, wiproDs.acceptedCount)
        assertEquals(1, infyDs.acceptedCount)

        val repo = MarketRepository()
        // Reject simulated candles passed into ingestHistoricalCandles
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

        // Ingest real WIPRO & INFY historical datasets
        repo.ingestHistoricalCandles("WIPRO", wiproDs.candles, "1m", "multi_nse.csv", wiproDs.metadata)
        repo.ingestHistoricalCandles("INFY", infyDs.candles, "1m", "multi_nse.csv", infyDs.metadata)

        assertTrue("WIPRO should be dynamically registered", repo.instruments.value.any { it.symbol == "WIPRO" })
        repo.refreshProvenance("WIPRO", "1m")
        assertEquals(FeedStatus.HISTORICAL, repo.feedStatus.value)
        assertEquals(2, repo.getCandles("WIPRO", "1m").size)

        // Ensure tickMarket() does not contaminate historical datasets
        repo.tickMarket()
        assertEquals(2, repo.getCandles("WIPRO", "1m").size)
        assertEquals(564.0, repo.getCandles("WIPRO", "1m").last().close, 1e-9)

        // Switching to RELIANCE (no historical CSV imported) stays SIMULATED
        repo.refreshProvenance("RELIANCE", "1m")
        assertEquals(FeedStatus.SIMULATED, repo.feedStatus.value)
    }

    @Test
    fun `end to end historical CSV ML training prevents cross-symbol leakage and runs unseen test backtest`() {
        val barCount = 240
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
            // Deterministic oscillating market profile to produce UP/DOWN/SIDEWAYS samples
            val delta = sin(i / 6.0) * 4.5 + (if (i % 7 == 0) 1.2 else -0.4)
            val open = price
            val close = (open + delta).coerceAtLeast(100.0)
            val high = maxOf(open, close) + 2.2
            val low = minOf(open, close) - 2.2
            val vol = 95000L + (i * 450L)
            csvBuilder.append("$ts,${String.format(Locale.US, "%.2f", open)},${String.format(Locale.US, "%.2f", high)},${String.format(Locale.US, "%.2f", low)},${String.format(Locale.US, "%.2f", close)},$vol\n")
            price = close
            baseCal.add(java.util.Calendar.MINUTE, 5)
        }

        val importRes = HistoricalCsvImporter.parseAndValidateText(
            csvText = csvBuilder.toString(),
            defaultSymbol = "RELIANCE",
            timeframe = "5m",
            sourceName = "nse_reliance_5m_historical.csv"
        )
        assertTrue(importRes.success)
        val ds = importRes.importedDatasets.first()
        assertEquals(barCount, ds.acceptedCount)

        val repo = MarketRepository()
        repo.ingestHistoricalCandles(
            symbol = ds.symbol,
            candles = ds.candles,
            timeframe = ds.timeframe,
            sourceName = ds.metadata!!.sourceName,
            metadata = ds.metadata
        )
        val storedCandles = repo.getCandles("RELIANCE", "5m")
        assertEquals(barCount, storedCandles.size)
        assertTrue(storedCandles.all { it.feedStatus == FeedStatus.HISTORICAL })

        val mlEngine = QuantMlEngine()
        val trainOutcome = mlEngine.trainAndValidate(
            candles = storedCandles,
            symbol = "RELIANCE",
            timeframe = "5m",
            forwardHorizon = 5,
            seed = 42L
        )
        assertNotNull(trainOutcome)
        val rep = trainOutcome!!.report
        assertFalse("Imported CSV must be flagged non-synthetic", rep.isSyntheticData)
        assertTrue("testStartCandleIndex must point inside series", rep.testStartCandleIndex >= 25)

        val decisionEngine = EnsembleDecisionEngine(mlEngine = mlEngine)
        // Cross-symbol check: when RELIANCE model is loaded, evaluating TCS must NOT apply RELIANCE ML prediction
        val tcsInst = MarketInstrument("TCS", "TCS", Exchange.NSE, true, 4100.0, 4090.0, 4120.0, 4080.0, 0.2, 50000L, feedStatus = FeedStatus.SIMULATED)
        val tcsSig = decisionEngine.evaluate(tcsInst, repo.getCandles("TCS", "5m"), RiskSettings(), 500000.0)
        assertTrue("TCS must not use RELIANCE-trained ML model", tcsSig.mlPrediction == null || !tcsSig.mlPrediction!!.isTrained)

        // Backtest strictly on Unseen Test Split
        val backtestEngine = BacktestEngine(decisionEngine = decisionEngine)
        val oosBt = backtestEngine.runBacktest(
            symbol = "RELIANCE",
            candles = storedCandles,
            initialCapital = 500000.0,
            riskSettings = RiskSettings(),
            timeframe = "5m",
            sourceName = ds.metadata!!.sourceName,
            startBarIndex = rep.testStartCandleIndex,
            evaluationScope = "Unseen Test Split Only (bars ${rep.testStartCandleIndex}..${storedCandles.size - 1})"
        )

        assertEquals(FeedStatus.HISTORICAL, oosBt.feedStatus)
        assertEquals("5m", oosBt.timeframe)
        assertEquals(barCount, oosBt.candleCount)
        assertEquals(barCount - rep.testStartCandleIndex, oosBt.evaluatedBarCount)
        assertNotNull("Backtest should attach matching ML validation report", oosBt.mlValidationReport)

        println("=== PHASE2_HISTORICAL_BACKTEST_REPORT ===")
        println("symbol=${oosBt.symbol} tf=${oosBt.timeframe} feedStatus=${oosBt.feedStatus} source=${oosBt.sourceName}")
        println("totalBars=${oosBt.candleCount} evaluatedBars=${oosBt.evaluatedBarCount} scope=${oosBt.evaluationScope}")
        println("mlTrain=${rep.trainSamples} mlVal=${rep.validationSamples} mlTest=${rep.testSamples} purged=${rep.purgedSamples}")
        println("mlTestAcc=${rep.testAccuracy} majBase=${rep.baselineMajorityAccuracy} momBase=${rep.baselineMomentumAccuracy} macroF1=${rep.macroF1}")
        println("confusionMatrix=${rep.confusionMatrix}")
        println("trades=${oosBt.totalTrades} winRate=${oosBt.winRatePercent}% pf=${oosBt.profitFactor} expectancy=${oosBt.expectancy} sharpe=${oosBt.sharpeRatio} maxDD=${oosBt.maxDrawdownPercent}% netProfit=${oosBt.netProfit}")
        println("=== END_PHASE2_REPORT ===")
    }
}
