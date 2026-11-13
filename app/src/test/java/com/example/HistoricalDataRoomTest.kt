package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.HistoricalDataLocalStore
import com.example.data.local.QuantDatabase
import com.example.data.model.FeedStatus
import com.example.data.repository.HistoricalCsvImporter
import com.example.data.repository.MarketRepository
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun `room local store persists, reloads into repository, and deletes historical datasets`() = runBlocking {
        val importRes = HistoricalCsvImporter.parseAndValidateText(
            csvText = HistoricalCsvImporter.SAMPLE_CSV_TEMPLATE,
            defaultSymbol = "HDFCBANK",
            timeframe = "1m",
            sourceName = "hdfcbank_sample.csv"
        )
        assertTrue(importRes.success)
        val ds = importRes.importedDatasets.first()

        val repo1 = MarketRepository(localStore = localStore)
        repo1.persistAndIngestHistoricalDataset(ds.metadata!!, ds.candles)

        // Fresh MarketRepository simulates cold app restart reloading from Room
        val repo2 = MarketRepository(localStore = localStore)
        repo2.loadPersistedHistoricalDatasets()
        repo2.refreshProvenance("HDFCBANK", "1m")

        assertEquals(FeedStatus.HISTORICAL, repo2.feedStatus.value)
        assertTrue(repo2.isUsingHistoricalData("HDFCBANK", "1m"))
        val reloadedCandles = repo2.getCandles("HDFCBANK", "1m")
        assertEquals(3, reloadedCandles.size)
        assertEquals(1273.10, reloadedCandles.first().close, 1e-9)

        // Delete dataset and verify provenance reverts to SIMULATED
        repo2.deleteHistoricalDataset("HDFCBANK", "1m")
        assertFalse(repo2.isUsingHistoricalData("HDFCBANK", "1m"))
        assertEquals(FeedStatus.SIMULATED, repo2.feedStatus.value)
    }
}
