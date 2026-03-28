package moe.shizuku.manager.update

import android.util.Log
import io.sentry.Sentry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import moe.shizuku.manager.BuildConfig
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Checks for updates from GitHub Releases
 */
object UpdateChecker {

    private const val TAG = "UpdateChecker"
    private const val GITHUB_API_URL = "https://api.github.com/repos/thejaustin/ShizukuPlus/releases/latest"

    /**
     * Represents update information
     */
    data class UpdateInfo(
        val versionName: String,
        val versionCode: Int,
        val releaseNotes: String,
        val downloadUrl: String,
        val publishedAt: String,
        val isPrerelease: Boolean
    )

    /**
     * Check for updates from GitHub
     * @return UpdateInfo if update is available, null if current version is latest
     */
    suspend fun checkForUpdate(): UpdateInfo? = withContext(Dispatchers.IO) {
        try {
            val url = URL(GITHUB_API_URL)
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 10000
            connection.readTimeout = 10000
            connection.setRequestProperty("Accept", "application/vnd.github.v3+json")
            connection.setRequestProperty("User-Agent", "ShizukuPlus/${BuildConfig.VERSION_NAME}")

            val responseCode = connection.responseCode
            if (responseCode != HttpURLConnection.HTTP_OK) {
                Log.w(TAG, "Failed to fetch release info: HTTP $responseCode")
                return@withContext null
            }

            val response = connection.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(response)

            val versionName = json.getString("tag_name").removePrefix("v")
            val isPrerelease = json.getBoolean("prerelease")
            val releaseNotes = json.optString("body", "No release notes available")
            val downloadUrl = json.getJSONArray("assets")
                .getJSONObject(0)
                .getString("browser_download_url")
            val publishedAt = json.getString("published_at")

            // Parse version code from version name (e.g., "13.6.0.r1488-shizukuplus" -> 1488)
            val versionCode = parseVersionCode(versionName)
            val currentVersionCode = parseVersionCode(BuildConfig.VERSION_NAME)

            connection.disconnect()

            // Check if update is available
            if (versionCode > currentVersionCode) {
                Log.d(TAG, "Update available: $versionName (current: ${BuildConfig.VERSION_NAME})")
                return@withContext UpdateInfo(
                    versionName = versionName,
                    versionCode = versionCode,
                    releaseNotes = releaseNotes,
                    downloadUrl = downloadUrl,
                    publishedAt = publishedAt,
                    isPrerelease = isPrerelease
                )
            } else {
                Log.d(TAG, "Already on latest version: ${BuildConfig.VERSION_NAME}")
                return@withContext null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking for update", e)
            Sentry.captureException(e)
            return@withContext null
        }
    }

    /**
     * Parse version code from version name
     * Extracts the build number from version strings like "13.6.0.r1488-shizukuplus"
     */
    private fun parseVersionCode(versionName: String): Int {
        return try {
            // Pattern: X.Y.Z.rNNNN-suffix or similar
            val regex = """\.r(\d+)""".toRegex()
            val matchResult = regex.find(versionName)
            matchResult?.groupValues?.get(1)?.toIntOrNull() ?: 0
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse version code from: $versionName", e)
            0
        }
    }

    /**
     * Format the published date for display
     */
    fun formatPublishedDate(dateString: String): String {
        return try {
            val inputFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
            inputFormat.timeZone = TimeZone.getTimeZone("UTC")
            val date: Date = inputFormat.parse(dateString)

            val outputFormat = SimpleDateFormat("MMM dd, yyyy", Locale.getDefault())
            outputFormat.format(date)
        } catch (e: Exception) {
            dateString
        }
    }
}
