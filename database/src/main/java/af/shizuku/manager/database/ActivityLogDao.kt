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
    fun insert(log: ActivityLogRoom): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertAll(logs: List<ActivityLogRoom>)

    @Query("SELECT * FROM activity_logs ORDER BY timestamp DESC")
    fun getAll(): Flow<List<ActivityLogRoom>>

    @Query("SELECT * FROM activity_logs ORDER BY timestamp DESC LIMIT :limit")
    fun getLimited(limit: Int): Flow<List<ActivityLogRoom>>

    @Query("DELETE FROM activity_logs")
    fun clear()

    /**
     * Delete activity logs except for the most recent ones.
     */
    @Query("DELETE FROM activity_logs WHERE id NOT IN (SELECT id FROM (SELECT id FROM activity_logs ORDER BY timestamp DESC, id DESC LIMIT :limit))")
    fun deleteExcess(limit: Int): Int

    /**
     * Delete activity logs older than the specified timestamp.
     */
    @Query("DELETE FROM activity_logs WHERE timestamp < :timestamp")
    fun deleteOlderThan(timestamp: Long): Int

    @Query("SELECT COUNT(*) FROM activity_logs")
    fun getCount(): Int

    @Delete
    fun delete(log: ActivityLogRoom)

    @Query("SELECT * FROM activity_logs ORDER BY timestamp ASC LIMIT 1")
    fun getOldest(): ActivityLogRoom?
}
