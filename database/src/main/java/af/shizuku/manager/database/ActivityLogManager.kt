package af.shizuku.manager.database

import timber.log.Timber

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Date
import java.util.LinkedList
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Data class representing an activity log record.
 */
data class ActivityLogRecord(
    val timestamp: Long = System.currentTimeMillis(),
    val appName: String,
    val packageName: String,
    val action: String
)

/**
 * Interface for ActivityLogManager settings to decouple it from the main app module.
 */
interface ActivityLogSettings {
    fun isActivityLogEnabled(): Boolean
    fun getWatchdog(): Boolean
    fun getActivityLogRetention(): Int
    fun setActivityLogRetention(count: Int)
    fun showNotification(appName: String, action: String)
}

/**
 * Manager for activity logs with Room database persistence.
 */
object ActivityLogManager {
    private const val TAG = "ActivityLogManager"
    
    private val records = Collections.synchronizedList(LinkedList<ActivityLogRecord>())
    
    private var database: ActivityLogDatabase? = null
    private var dao: ActivityLogDao? = null
    
    private val isResettingDatabase = AtomicBoolean(false)
    
    private val exceptionHandler = kotlinx.coroutines.CoroutineExceptionHandler { _, exception ->
        handleDatabaseError(exception)
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + exceptionHandler)
    
    private val isInitialized = AtomicBoolean(false)
    private val isCleaningUp = AtomicBoolean(false)
    
    private val _logs = MutableStateFlow<List<ActivityLogRecord>>(emptyList())
    val logs: StateFlow<List<ActivityLogRecord>> = _logs.asStateFlow()
    
    private var retentionCount = 100
    private var appContext: Context? = null
    private var settings: ActivityLogSettings? = null
    
    fun initialize(context: Context, settings: ActivityLogSettings) {
        if (isInitialized.getAndSet(true)) {
            return
        }
        appContext = context.applicationContext
        this.settings = settings

        scope.launch {
            try {
                val dbFile = context.getDatabasePath("shizuku_activity_logs.db")
                if (dbFile.parentFile?.exists() != true) {
                    dbFile.parentFile?.mkdirs()
                    delay(50)
                }

                database = ActivityLogDatabase.getInstance(context)
                dao = database?.activityLogDao()

                retentionCount = settings.getActivityLogRetention()

                loadFromDatabase()
                cleanupOldRecords()

                Timber.tag(TAG).d("ActivityLogManager initialized")
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Failed to initialize ActivityLogManager")
            }
        }
    }
    
    private fun loadFromDatabase() {
        if (dao == null) return

        scope.launch {
            var retryCount = 0
            while (retryCount < 3) {
                try {
                    dao!!.getAll().collect { dbLogs ->
                        synchronized(records) {
                            records.clear()
                            dbLogs.forEach { log ->
                                records.add(
                                    ActivityLogRecord(
                                        timestamp = log.timestamp,
                                        appName = log.appName,
                                        packageName = log.packageName,
                                        action = log.action
                                    )
                                )
                            }
                            _logs.value = records.toList()
                        }
                    }
                    return@launch
                } catch (e: Exception) {
                    retryCount++
                    delay(500)
                    if (retryCount >= 3) {
                        handleDatabaseError(e)
                    }
                }
            }
        }
    }
    
    fun log(appName: String, packageName: String, action: String) {
        val s = settings ?: return
        if (!s.isActivityLogEnabled()) return
        if (!isInitialized.get()) return
        
        if (s.getWatchdog()) {
            s.showNotification(appName, action)
        }
        
        val record = ActivityLogRecord(
            timestamp = System.currentTimeMillis(),
            appName = appName,
            packageName = packageName,
            action = action
        )
        
        synchronized(records) {
            if (records.size >= retentionCount) {
                records.removeLast()
            }
            records.add(0, record)
            _logs.value = records.toList()
        }
        
        saveToDatabase(record)
        
        if (!isCleaningUp.get()) {
            cleanupOldRecords()
        }
    }
    
    private fun saveToDatabase(record: ActivityLogRecord) {
        val d = dao ?: return
        scope.launch {
            try {
                val roomLog = ActivityLogRoom(
                    timestamp = record.timestamp,
                    appName = record.appName,
                    packageName = record.packageName,
                    action = record.action
                )
                d.insert(roomLog)
            } catch (e: android.database.sqlite.SQLiteCantOpenDatabaseException) {
                Timber.tag(TAG).w("Error saving log: SQLiteCantOpenDatabaseException")
                handleDatabaseError(e)
            } catch (e: android.database.sqlite.SQLiteDatabaseCorruptException) {
                Timber.tag(TAG).w("Error saving log: SQLiteDatabaseCorruptException")
                handleDatabaseError(e)
            } catch (e: Exception) {
                Timber.tag(TAG).w(e, "Error saving log")
            }
        }
    }
    
    private fun cleanupOldRecords() {
        if (isCleaningUp.getAndSet(true)) return
        
        scope.launch {
            try {
                dao?.deleteExcess(retentionCount)
            } catch (e: Exception) {
                handleDatabaseError(e)
            } finally {
                isCleaningUp.set(false)
            }
        }
    }
    
    fun getRecords(): List<ActivityLogRecord> = synchronized(records) {
        records.toList()
    }
    
    fun clear() {
        synchronized(records) {
            records.clear()
            _logs.value = emptyList()
        }
        
        scope.launch {
            try {
                dao?.clear()
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Error clearing logs")
            }
        }
    }
    
    fun updateRetentionCount(count: Int) {
        val newRetention = count.coerceIn(10, 1000)
        retentionCount = newRetention
        settings?.setActivityLogRetention(newRetention)
        cleanupOldRecords()
    }
    
    fun getRetentionCount(): Int = retentionCount
    
    suspend fun exportToJson(directory: File, filename: String? = null): File? = withContext(Dispatchers.IO) {
        try {
            val logs = dao?.getAll()?.first() ?: emptyList()
            if (logs.isEmpty()) return@withContext null
            
            val exportFile = File(directory, filename ?: "activity_logs_${getTimestampFilename()}.json")
            FileWriter(exportFile).use { writer ->
                writer.appendLine("[")
                logs.forEachIndexed { index, log ->
                    writer.appendLine("  {")
                    writer.appendLine("    \"timestamp\": ${log.timestamp},")
                    writer.appendLine("    \"appName\": \"${escapeJson(log.appName)}\",")
                    writer.appendLine("    \"packageName\": \"${escapeJson(log.packageName)}\",")
                    writer.appendLine("    \"action\": \"${escapeJson(log.action)}\"")
                    writer.appendLine("  }${if (index < logs.size - 1) "," else ""}")
                }
                writer.appendLine("]")
            }
            exportFile
        } catch (e: Exception) {
            null
        }
    }

    private fun getTimestampFilename(): String {
        return SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
    }
    
    private fun escapeJson(str: String): String {
        return str.replace("\\", "\\\\").replace("\"", "\\\"")
    }

    private fun handleDatabaseError(e: Throwable) {
        val context = appContext ?: return
        
        val isDbError = e is android.database.sqlite.SQLiteCantOpenDatabaseException ||
                e is android.database.sqlite.SQLiteDatabaseCorruptException ||
                e.cause is android.database.sqlite.SQLiteCantOpenDatabaseException ||
                e.cause is android.database.sqlite.SQLiteDatabaseCorruptException
                
        if (!isDbError || isResettingDatabase.getAndSet(true)) return
        
        scope.launch {
            try {
                Timber.tag(TAG).w("Autofixing corrupted database: \${e.message}")
                
                ActivityLogDatabase.resetInstance()
                database = null
                dao = null
                
                val dbFile = context.getDatabasePath("shizuku_activity_logs.db")
                listOf(
                    dbFile,
                    File(dbFile.path + "-shm"),
                    File(dbFile.path + "-wal")
                ).forEach { file ->
                    if (file.exists()) file.delete()
                }
                
                if (dbFile.parentFile?.exists() != true) {
                    dbFile.parentFile?.mkdirs()
                }
                
                database = ActivityLogDatabase.getInstance(context)
                dao = database?.activityLogDao()
                
                settings?.showNotification("System Warning", "Activity log database was reset due to corruption")
                
                val recoveryRecord = ActivityLogRecord(
                    appName = "System",
                    packageName = context.packageName,
                    action = "Database autofixed after corruption/open error"
                )
                
                synchronized(records) {
                    records.add(0, recoveryRecord)
                    _logs.value = records.toList()
                }
                
                dao?.insert(ActivityLogRoom(
                    timestamp = recoveryRecord.timestamp,
                    appName = recoveryRecord.appName,
                    packageName = recoveryRecord.packageName,
                    action = recoveryRecord.action
                ))
            } catch (resetError: Exception) {
                Timber.tag(TAG).e(resetError, "Failed to autofix database")
            } finally {
                isResettingDatabase.set(false)
            }
        }
    }
}