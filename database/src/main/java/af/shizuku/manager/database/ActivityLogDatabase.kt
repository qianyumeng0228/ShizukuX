package af.shizuku.manager.database

import timber.log.Timber

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Room database for activity logs.
 * 
 * This database stores activity log entries for tracking application actions.
 */
@Database(
    entities = [ActivityLogRoom::class],
    version = 1,
    exportSchema = false
)
abstract class ActivityLogDatabase : RoomDatabase() {

    /**
     * Get the DAO for activity logs.
     */
    abstract fun activityLogDao(): ActivityLogDao

    companion object {
        private const val DATABASE_NAME = "shizuku_activity_logs.db"

        @Volatile
        private var instance: ActivityLogDatabase? = null

        private val lock = ReentrantLock()

        /**
         * Get the singleton instance of the database.
         * 
         * @param context Application context.
         * @return The database instance.
         */
        fun getInstance(context: Context): ActivityLogDatabase {
            return instance ?: lock.withLock {
                instance ?: buildDatabase(context).also { instance = it }
            }
        }

        private fun buildDatabase(context: Context): ActivityLogDatabase {
            val storageContext = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                context.createDeviceProtectedStorageContext()
            } else {
                context
            }
            val dbFile = storageContext.getDatabasePath(DATABASE_NAME)
            val parent = dbFile.parentFile
            if (parent != null && !parent.exists()) {
                parent.mkdirs()
            }
            return Room.databaseBuilder(
                storageContext,
                ActivityLogDatabase::class.java,
                dbFile.absolutePath
            )
                .fallbackToDestructiveMigration()
                .build()
        }

        /**
         * Reset the singleton instance (useful for testing).
         */
        fun resetInstance() {
            lock.withLock {
                instance?.close()
                instance = null
            }
        }
    }
}
