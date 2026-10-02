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
    val metadataJson: String
)

@Dao
interface RecordDao {
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

    @Query("SELECT * FROM records WHERE type = 'location' AND timestamp >= :fromMs AND timestamp <= :toMs ORDER BY timestamp ASC")
    suspend fun locationsBetween(fromMs: Long, toMs: Long): List<RecordEntity>

    @Query("DELETE FROM records WHERE type = 'location' AND timestamp < :cutoffMs")
    suspend fun deleteLocationsOlderThan(cutoffMs: Long)
}

@Database(entities = [RecordEntity::class], version = 1)
abstract class LocalDb : RoomDatabase() {
    abstract fun dao(): RecordDao

    companion object {
        @Volatile private var instance: LocalDb? = null

        fun get(context: Context): LocalDb = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, LocalDb::class.java, "phonebridge.db")
                .build().also { instance = it }
        }
    }
}
