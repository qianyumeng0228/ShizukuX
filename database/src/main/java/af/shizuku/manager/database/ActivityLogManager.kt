package af.shizuku.manager.database

import android.content.Context
import timber.log.Timber
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
    
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    
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
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Error saving log")
            }
        }
    }
    
    private fun cleanupOldRecords() {
        if (isCleaningUp.getAndSet(true)) return
        
        scope.launch {
            try {
                dao?.deleteExcess(retentionCount)
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
}