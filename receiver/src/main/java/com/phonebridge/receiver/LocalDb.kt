package com.phonebridge.receiver

import androidx.room.*
import android.content.Context
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "records")
data class RecordEntity(
    @PrimaryKey val id: String,
    val type: String,
    val timestamp: Long,
    val source: String,
    val title: String,
    val body: String,
    val metadataJson: String,
    @ColumnInfo(defaultValue = "''") val pairId: String = ""
)

@Dao
interface RecordDao {
    @Query("DELETE FROM records WHERE pairId = :historyId")
    suspend fun deleteDevice(historyId: String)

    @Query("SELECT * FROM records WHERE pairId = :historyId ORDER BY timestamp DESC LIMIT 1")
    suspend fun lastRecord(historyId: String): RecordEntity?
    @Query("SELECT * FROM records WHERE pairId = :pairId ORDER BY timestamp DESC")
    suspend fun forPair(pairId: String): List<RecordEntity>

    @Query("DELETE FROM records WHERE pairId = :pairId AND type = 'alert'")
    suspend fun clearAlerts(pairId: String)

    @Query("SELECT * FROM records ORDER BY timestamp DESC")
    fun observeAll(): Flow<List<RecordEntity>>

    @Query("SELECT * FROM records ORDER BY timestamp DESC")
    suspend fun all(): List<RecordEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(r: RecordEntity)

    @Query("SELECT * FROM records WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): RecordEntity?

    @Query("DELETE FROM records WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM records WHERE type = :type")
    suspend fun deleteByType(type: String)

    @Query("SELECT * FROM records WHERE pairId = :pairId AND type = 'location' AND timestamp >= :fromMs AND timestamp <= :toMs ORDER BY timestamp ASC")
    suspend fun locationsBetween(fromMs: Long, toMs: Long, pairId: String): List<RecordEntity>

    @Query("DELETE FROM records WHERE type = 'location' AND timestamp < :cutoffMs")
    suspend fun deleteLocationsOlderThan(cutoffMs: Long)
}

@Database(entities = [RecordEntity::class], version = 2, exportSchema = false)
abstract class LocalDb : RoomDatabase() {
    abstract fun dao(): RecordDao

    companion object {
        val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE records ADD COLUMN pairId TEXT NOT NULL DEFAULT ''")
            }
        }
        @Volatile private var instance: LocalDb? = null

        fun get(context: Context): LocalDb = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, LocalDb::class.java, "phonebridge.db")
                .addMigrations(MIGRATION_1_2)
                .build().also { instance = it }
        }
    }
}
