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
import com.example.data.model.AdjustmentPolicy
import com.example.data.model.Candle
import com.example.data.model.DataOrigin
import com.example.data.model.FeedStatus
import com.example.data.model.HistoricalDatasetMetadata
import com.example.data.model.VerificationStatus
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
    val warningsJoined: String,
    val datasetId: String = "",
    val sourceType: String = DataOrigin.IMPORTED_CSV.name,
    val verificationStatus: String = VerificationStatus.IMPORTED_CSV_UNVERIFIED.name,
    val sha256Checksum: String = "",
    val timezone: String = "Asia/Kolkata",
    val adjustmentPolicy: String = AdjustmentPolicy.UNKNOWN_UNVERIFIED.name,
    val holidaySkippedCount: Int = 0,
    val corporateActionFlagsJoined: String = ""
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
        candles.chunked(500).forEach { chunk ->
            insertCandles(chunk)
        }
    }

    @Transaction
    suspend fun replaceDatasetsBatch(
        items: List<Pair<HistoricalDatasetMetaEntity, List<HistoricalCandleEntity>>>
    ) {
        for ((meta, candles) in items) {
            replaceDataset(meta.symbol, meta.timeframe, meta, candles)
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
    version = 2,
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
                )
                    .fallbackToDestructiveMigration(dropAllTables = true)
                    .build()
                    .also { INSTANCE = it }
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
        val (metaEntity, candleEntities) = toEntities(metadata, candles)
        dao.replaceDataset(metadata.symbol, metadata.timeframe, metaEntity, candleEntities)
    }

    suspend fun saveDatasetsBatch(datasets: List<Pair<HistoricalDatasetMetadata, List<Candle>>>) {
        val entities = datasets.map { (meta, candles) -> toEntities(meta, candles) }
        dao.replaceDatasetsBatch(entities)
    }

    private fun toEntities(
        metadata: HistoricalDatasetMetadata,
        candles: List<Candle>
    ): Pair<HistoricalDatasetMetaEntity, List<HistoricalCandleEntity>> {
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
            warningsJoined = metadata.warnings.joinToString("\n"),
            datasetId = metadata.datasetId,
            sourceType = metadata.sourceType.name,
            verificationStatus = metadata.verificationStatus.name,
            sha256Checksum = metadata.sha256Checksum,
            timezone = metadata.timezone,
            adjustmentPolicy = metadata.adjustmentPolicy.name,
            holidaySkippedCount = metadata.holidaySkippedCount,
            corporateActionFlagsJoined = metadata.corporateActionFlags.joinToString("\n")
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
        return metaEntity to candleEntities
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
                warnings = if (m.warningsJoined.isBlank()) emptyList() else m.warningsJoined.split("\n"),
                datasetId = m.datasetId,
                sourceType = runCatching { DataOrigin.valueOf(m.sourceType) }.getOrDefault(DataOrigin.IMPORTED_CSV),
                verificationStatus = runCatching { VerificationStatus.valueOf(m.verificationStatus) }
                    .getOrDefault(VerificationStatus.IMPORTED_CSV_UNVERIFIED),
                sha256Checksum = m.sha256Checksum,
                timezone = m.timezone.ifBlank { "Asia/Kolkata" },
                adjustmentPolicy = runCatching { AdjustmentPolicy.valueOf(m.adjustmentPolicy) }
                    .getOrDefault(AdjustmentPolicy.UNKNOWN_UNVERIFIED),
                holidaySkippedCount = m.holidaySkippedCount,
                corporateActionFlags = if (m.corporateActionFlagsJoined.isBlank()) emptyList() else m.corporateActionFlagsJoined.split("\n")
            )
            meta to candles
        }
    }

    suspend fun deleteDataset(symbol: String, timeframe: String) {
        dao.deleteDataset(symbol, timeframe)
    }
}
