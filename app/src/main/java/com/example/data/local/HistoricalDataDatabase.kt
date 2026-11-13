package com.example.data.local

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import com.example.data.model.Candle
import com.example.data.model.FeedStatus
import com.example.data.model.HistoricalDatasetMetadata
import kotlinx.coroutines.flow.Flow

@Entity(
    tableName = "historical_candles",
    primaryKeys = ["symbol", "timeframe", "timestamp"],
    indices = [Index(value = ["symbol", "timeframe", "timestamp"])]
)
data class HistoricalCandleEntity(
    val symbol: String,
    val timeframe: String,
    val timestamp: Long,
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
    val volume: Long,
    val feedStatus: String
)

@Entity(
    tableName = "historical_dataset_meta",
    primaryKeys = ["symbol", "timeframe"]
)
data class HistoricalDatasetMetaEntity(
    val symbol: String,
    val timeframe: String,
    val sourceName: String,
    val feedStatus: String,
    val importedAt: Long,
    val candleCount: Int,
    val startTimestamp: Long,
    val endTimestamp: Long,
    val duplicateCount: Int,
    val invalidRowCount: Int,
    val missingCandleCount: Int,
    val warningsJoined: String
)

@Dao
interface HistoricalDataDao {
    @Query("SELECT * FROM historical_dataset_meta ORDER BY importedAt DESC")
    fun observeAllDatasetMeta(): Flow<List<HistoricalDatasetMetaEntity>>

    @Query("SELECT * FROM historical_dataset_meta ORDER BY importedAt DESC")
    suspend fun getAllDatasetMeta(): List<HistoricalDatasetMetaEntity>

    @Query("SELECT * FROM historical_candles WHERE symbol = :symbol AND timeframe = :timeframe ORDER BY timestamp ASC")
    suspend fun getCandles(symbol: String, timeframe: String): List<HistoricalCandleEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCandles(candles: List<HistoricalCandleEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMeta(meta: HistoricalDatasetMetaEntity)

    @Query("DELETE FROM historical_candles WHERE symbol = :symbol AND timeframe = :timeframe")
    suspend fun deleteCandles(symbol: String, timeframe: String)

    @Query("DELETE FROM historical_dataset_meta WHERE symbol = :symbol AND timeframe = :timeframe")
    suspend fun deleteMeta(symbol: String, timeframe: String)

    @Transaction
    suspend fun replaceDataset(
        symbol: String,
        timeframe: String,
        meta: HistoricalDatasetMetaEntity,
        candles: List<HistoricalCandleEntity>
    ) {
        deleteCandles(symbol, timeframe)
        deleteMeta(symbol, timeframe)
        upsertMeta(meta)
        // Insert in chunks to avoid SQLite bind parameter limits on large CSVs
        candles.chunked(500).forEach { chunk ->
            insertCandles(chunk)
        }
    }

    @Transaction
    suspend fun deleteDataset(symbol: String, timeframe: String) {
        deleteCandles(symbol, timeframe)
        deleteMeta(symbol, timeframe)
    }
}

@Database(
    entities = [HistoricalCandleEntity::class, HistoricalDatasetMetaEntity::class],
    version = 1,
    exportSchema = false
)
abstract class QuantDatabase : RoomDatabase() {
    abstract fun historicalDataDao(): HistoricalDataDao

    companion object {
        @Volatile
        private var INSTANCE: QuantDatabase? = null

        fun getInstance(context: Context): QuantDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    QuantDatabase::class.java,
                    "quantai_market_history.db"
                ).build().also { INSTANCE = it }
            }
        }
    }
}

/**
 * Repository-facing local persistence store wrapping HistoricalDataDao.
 */
class HistoricalDataLocalStore(
    private val dao: HistoricalDataDao
) {
    fun observeMetadata(): Flow<List<HistoricalDatasetMetaEntity>> = dao.observeAllDatasetMeta()

    suspend fun saveDataset(metadata: HistoricalDatasetMetadata, candles: List<Candle>) {
        val metaEntity = HistoricalDatasetMetaEntity(
            symbol = metadata.symbol,
            timeframe = metadata.timeframe,
            sourceName = metadata.sourceName,
            feedStatus = metadata.feedStatus.name,
            importedAt = metadata.importedAt,
            candleCount = candles.size,
            startTimestamp = metadata.startTimestamp,
            endTimestamp = metadata.endTimestamp,
            duplicateCount = metadata.duplicateCount,
            invalidRowCount = metadata.invalidRowCount,
            missingCandleCount = metadata.missingCandleCount,
            warningsJoined = metadata.warnings.joinToString("\n")
        )
        val candleEntities = candles.map { c ->
            HistoricalCandleEntity(
                symbol = metadata.symbol,
                timeframe = metadata.timeframe,
                timestamp = c.timestamp,
                open = c.open,
                high = c.high,
                low = c.low,
                close = c.close,
                volume = c.volume,
                feedStatus = c.feedStatus.name
            )
        }
        dao.replaceDataset(metadata.symbol, metadata.timeframe, metaEntity, candleEntities)
    }

    suspend fun loadAllDatasets(): List<Pair<HistoricalDatasetMetadata, List<Candle>>> {
        val metas = dao.getAllDatasetMeta()
        return metas.map { m ->
            val candles = dao.getCandles(m.symbol, m.timeframe).map { e ->
                Candle(
                    timestamp = e.timestamp,
                    open = e.open,
                    high = e.high,
                    low = e.low,
                    close = e.close,
                    volume = e.volume,
                    feedStatus = runCatching { FeedStatus.valueOf(e.feedStatus) }.getOrDefault(FeedStatus.HISTORICAL)
                )
            }
            val meta = HistoricalDatasetMetadata(
                symbol = m.symbol,
                timeframe = m.timeframe,
                sourceName = m.sourceName,
                feedStatus = runCatching { FeedStatus.valueOf(m.feedStatus) }.getOrDefault(FeedStatus.HISTORICAL),
                importedAt = m.importedAt,
                candleCount = candles.size,
                startTimestamp = m.startTimestamp,
                endTimestamp = m.endTimestamp,
                duplicateCount = m.duplicateCount,
                invalidRowCount = m.invalidRowCount,
                missingCandleCount = m.missingCandleCount,
                warnings = if (m.warningsJoined.isBlank()) emptyList() else m.warningsJoined.split("\n")
            )
            meta to candles
        }
    }

    suspend fun deleteDataset(symbol: String, timeframe: String) {
        dao.deleteDataset(symbol, timeframe)
    }
}
