package com.phonebridge.receiver

import androidx.room.*

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
    suspend fun all(): List<RecordEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(r: RecordEntity)
}

@Database(entities = [RecordEntity::class], version = 1)
abstract class LocalDb : RoomDatabase() {
    abstract fun dao(): RecordDao
}
