package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.HistoricalDataLocalStore
import com.example.data.local.QuantDatabase
import com.example.data.model.AdjustmentPolicy
import com.example.data.model.DataOrigin
import com.example.data.model.FeedStatus
import com.example.data.model.VerificationStatus
import com.example.data.repository.HistoricalCsvImporter
import com.example.data.repository.MarketRepository
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class HistoricalDataRoomTest {

    private lateinit var db: QuantDatabase
    private lateinit var localStore: HistoricalDataLocalStore

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, QuantDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        localStore = HistoricalDataLocalStore(db.historicalDataDao())
    }

    @After
    fun teardown() {
        db.close()
    }

    @Test
    fun `room local store persists v2 provenance fields, batch commits, reloads on cold start, and deletes datasets`() = runBlocking {
        val multiCsv = """
            symbol,timestamp,open,high,low,close,volume
            HDFCBANK,2025-11-10 09:15:00,1640.00,1644.50,1638.00,1643.10,145200
            HDFCBANK,2025-11-10 09:16:00,1643.10,1645.80,1642.00,1645.00,98450
            INFY,2025-11-10 09:15:00,1840.00,1845.00,1838.00,1844.00,112000
        """.trimIndent()
        val importRes = HistoricalCsvImporter.parseAndValidateText(
            csvText = multiCsv,
            defaultSymbol = "HDFCBANK",
            timeframe = "1m",
            sourceName = "nse_multi_sample.csv",
            useSymbolColumnIfPresent = true
        )
        assertTrue(importRes.success)
        assertEquals(2, importRes.importedDatasets.size)

        val repo1 = MarketRepository(localStore = localStore)
        val batch = importRes.importedDatasets.mapNotNull { ds -> ds.metadata?.let { it to ds.candles } }
        repo1.persistAndIngestHistoricalBatch(batch)

        // Fresh MarketRepository simulates cold app restart reloading from Room
        val repo2 = MarketRepository(localStore = localStore)
        repo2.loadPersistedHistoricalDatasets()
        repo2.refreshProvenance("HDFCBANK", "1m")

        assertEquals(FeedStatus.HISTORICAL, repo2.feedStatus.value)
        assertEquals(VerificationStatus.IMPORTED_CSV_UNVERIFIED, repo2.verificationStatus.value)
        assertTrue(repo2.isUsingHistoricalData("HDFCBANK", "1m"))

        val reloadedMeta = repo2.getDatasetProvenance("HDFCBANK", "1m")
        assertNotNull(reloadedMeta)
        assertEquals(DataOrigin.IMPORTED_CSV, reloadedMeta!!.sourceType)
        assertEquals(VerificationStatus.IMPORTED_CSV_UNVERIFIED, reloadedMeta.verificationStatus)
        assertEquals("Asia/Kolkata", reloadedMeta.timezone)
        assertEquals(AdjustmentPolicy.UNKNOWN_UNVERIFIED, reloadedMeta.adjustmentPolicy)
        assertEquals(64, reloadedMeta.sha256Checksum.length)
        assertTrue(reloadedMeta.datasetId.startsWith("HDFCBANK_1m_"))

        val reloadedCandles = repo2.getCandles("HDFCBANK", "1m")
        assertEquals(2, reloadedCandles.size)
        assertEquals(1643.10, reloadedCandles.first().close, 1e-9)
        assertTrue(repo2.isUsingHistoricalData("INFY", "1m"))

        // Delete dataset and verify provenance reverts to SIMULATED
        repo2.deleteHistoricalDataset("HDFCBANK", "1m")
        assertFalse(repo2.isUsingHistoricalData("HDFCBANK", "1m"))
        assertEquals(FeedStatus.SIMULATED, repo2.feedStatus.value)
        assertEquals(VerificationStatus.SIMULATED_NOT_REAL, repo2.verificationStatus.value)
    }

    @Test
    fun `persisted ML model reloads cleanly for identical dataset and invalidates when dataset is replaced or deleted`() = runBlocking {
        // Build 200 daily bars for RELIANCE (170 labeled samples -> >= 20 test samples so gate passes)
        val csvBuilder = StringBuilder("timestamp,open,high,low,close,volume\n")
        var price = 1250.0
        val baseTs = 1735689600000L // 2025-01-01
        for (i in 0 until 200) {
            val delta = kotlin.math.sin(i / 5.0) * 12.0 + 0.8
            val open = price
            val close = (open + delta).coerceAtLeast(100.0)
            val high = maxOf(open, close) + 4.0
            val low = minOf(open, close) - 4.0
            csvBuilder.append("${baseTs + i * 86_400_000L},$open,$high,$low,$close,${100_000L + i * 500L}\n")
            price = close
        }

        val res = HistoricalCsvImporter.parseAndValidateText(
            csvText = csvBuilder.toString(),
            defaultSymbol = "RELIANCE",
            timeframe = "1D",
            sourceName = "reliance_fixture_120.csv",
            sourceType = DataOrigin.TEST_FIXTURE,
            verificationStatus = VerificationStatus.TEST_FIXTURE_NOT_MARKET_DATA
        )
        assertTrue(res.success)
        val ds = res.importedDatasets.first()

        val repo1 = MarketRepository(localStore = localStore)
        repo1.persistAndIngestHistoricalDataset(ds.metadata!!, ds.candles)

        val engine1 = com.example.engine.ml.QuantMlEngine()
        val outcome = engine1.trainAndValidate(
            candles = ds.candles,
            symbol = "RELIANCE",
            timeframe = "1D",
            datasetMeta = ds.metadata
        )
        assertNotNull(outcome)
        assertTrue("Expected test fixture RF to pass validation gate", outcome!!.deployed)

        val tempModelFile = java.io.File.createTempFile("quantai_room_binding_", ".json")
        try {
            val modelStore = com.example.engine.ml.MlModelStore(tempModelFile)
            assertTrue(modelStore.save(engine1.exportPersistedModel()!!))

            // 1. Cold restart with identical Room dataset -> model restores and binds cleanly
            val repoRestart = MarketRepository(localStore = localStore)
            repoRestart.loadPersistedHistoricalDatasets()
            val metaAfterRestart = repoRestart.getDatasetProvenance("RELIANCE", "1D")
            val reloadedEngine = com.example.engine.ml.QuantMlEngine()
            reloadedEngine.loadPersistedModel(modelStore.load()!!)
            assertTrue(
                "Persisted model must validate when Room dataset SHA-256 matches",
                reloadedEngine.isModelValidFor("RELIANCE", "1D", metaAfterRestart)
            )

            // 2. Replace dataset in Room with modified price -> persisted model MUST NOT validate
            val modifiedCsv = csvBuilder.toString().replaceFirst(",100000\n", ",999999\n")
            val modRes = HistoricalCsvImporter.parseAndValidateText(
                csvText = modifiedCsv,
                defaultSymbol = "RELIANCE",
                timeframe = "1D",
                sourceName = "reliance_fixture_120_v2.csv",
                sourceType = DataOrigin.TEST_FIXTURE,
                verificationStatus = VerificationStatus.TEST_FIXTURE_NOT_MARKET_DATA
            )
            val modDs = modRes.importedDatasets.first()
            repoRestart.persistAndIngestHistoricalDataset(modDs.metadata!!, modDs.candles)
            val updatedMeta = repoRestart.getDatasetProvenance("RELIANCE", "1D")
            assertFalse(
                "Stale model must fail binding check after underlying dataset is modified/replaced",
                reloadedEngine.isModelValidFor("RELIANCE", "1D", updatedMeta)
            )

            // 3. Delete dataset -> persisted model MUST NOT validate against null provenance
            repoRestart.deleteHistoricalDataset("RELIANCE", "1D")
            assertFalse(
                "Stale model must fail binding check after dataset is deleted",
                reloadedEngine.isModelValidFor("RELIANCE", "1D", repoRestart.getDatasetProvenance("RELIANCE", "1D"))
            )
        } finally {
            tempModelFile.delete()
        }
    }
}
