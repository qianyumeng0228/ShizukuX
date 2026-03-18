package moe.shizuku.manager.database

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Index

/**
 * Room entity for storing activity logs.
 * 
 * @property id Auto-generated unique identifier for the log entry.
 * @property timestamp Unix timestamp in milliseconds when the action occurred.
 * @property appName Human-readable name of the application.
 * @property packageName Package name of the application.
 * @property action The action that was performed (e.g., "START", "STOP", "RESTART").
 */
@Entity(
    tableName = "activity_logs",
    indices = [
        Index(value = ["timestamp"]),
        Index(value = ["packageName"]),
        Index(value = ["action"])
    ]
)
data class ActivityLogRoom(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val timestamp: Long,
    val appName: String,
    val packageName: String,
    val action: String
)
