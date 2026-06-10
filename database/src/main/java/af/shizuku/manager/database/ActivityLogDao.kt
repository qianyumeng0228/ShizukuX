package af.shizuku.manager.database

import timber.log.Timber

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * Data Access Object for activity logs.
 */
@Dao
interface ActivityLogDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(log: ActivityLogRoom): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(logs: List<ActivityLogRoom>): List<Long>

    @Query("SELECT * FROM activity_logs ORDER BY timestamp DESC")
    fun getAll(): Flow<List<ActivityLogRoom>>

    @Query("SELECT * FROM activity_logs ORDER BY timestamp DESC LIMIT :limit")
    fun getLimited(limit: Int): Flow<List<ActivityLogRoom>>

    @Query("DELETE FROM activity_logs")
    suspend fun clear(): Int

    /**
     * Delete activity logs except for the most recent ones.
     */
    @Query("DELETE FROM activity_logs WHERE id NOT IN (SELECT id FROM (SELECT id FROM activity_logs ORDER BY timestamp DESC, id DESC LIMIT :limit))")
    suspend fun deleteExcess(limit: Int): Int

    /**
     * Delete activity logs older than the specified timestamp.
     */
    @Query("DELETE FROM activity_logs WHERE timestamp < :timestamp")
    suspend fun deleteOlderThan(timestamp: Long): Int

    @Query("SELECT COUNT(*) FROM activity_logs")
    suspend fun getCount(): Int

    @Delete
    suspend fun delete(log: ActivityLogRoom): Int

    @Query("SELECT * FROM activity_logs ORDER BY timestamp ASC LIMIT 1")
    suspend fun getOldest(): ActivityLogRoom?
}
