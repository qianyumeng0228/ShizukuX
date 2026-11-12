package af.shizuku.manager.apps

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Index served from `qianyumeng0228/shizukux-apps` (`apps.json`).
 * Every field beyond [id] is defaulted so a third-party index still parses.
 */
@Serializable
data class AppStoreIndex(
    val name: String = "",
    val schemaVersion: Int = 1,
    val updatedAt: Long? = null,
    val apps: List<AppStoreEntry> = emptyList()
)

@Serializable
data class AppStoreEntry(
    val id: String,
    val name: String = "",
    val nameZh: String? = null,
    val packageName: String = "",
    val versionName: String? = null,
    val versionCode: Long? = null,
    val author: String? = null,
    val description: String? = null,
    val descriptionZh: String? = null,
    val downloadUrl: String = "",
    val fallbackUrl: String? = null,
    val sizeBytes: Long? = null,
    val sha256: String? = null,
    val minSdk: Int? = null,
    val tags: List<String> = emptyList(),
    val lastUpdated: Long? = null,
    val sourceRepo: String? = null,
    val iconUrl: String? = null
) {
    val displayName: String get() = name.ifBlank { packageName.ifBlank { id } }

    fun localizedName(preferChinese: Boolean): String =
        if (preferChinese) nameZh?.takeIf { it.isNotBlank() } ?: displayName else displayName

    fun localizedDescription(preferChinese: Boolean): String? =
        if (preferChinese) descriptionZh?.takeIf { it.isNotBlank() } ?: description else description

    val formattedSize: String?
        get() = sizeBytes?.takeIf { it > 0 }?.let {
            val kb = it / 1024.0
            if (kb < 1024.0) "%.1f KB".format(kb) else "%.2f MB".format(kb / 1024.0)
        }
}

data class AppStoreUiState(
    val loading: Boolean = false,
    val entries: List<AppStoreEntry> = emptyList(),
    val installedVersions: Map<String, Long> = emptyMap(),
    val downloadingId: String? = null,
    val progress: Float? = null,
    val installingId: String? = null,
    val error: String? = null,
    val sourceHost: String = "",
    val loaded: Boolean = false
) {
    fun isInstalled(entry: AppStoreEntry): Boolean =
        installedVersions.containsKey(entry.packageName)

    fun hasUpdate(entry: AppStoreEntry): Boolean {
        val local = installedVersions[entry.packageName] ?: return false
        val remote = entry.versionCode ?: return false
        return remote > local
    }
}

/**
 * Network access for the app store — same mirror ladder as the module store, because the two
 * endpoints sit behind the same mainland network problems.
 */
object AppStoreClient {

    private const val OWNER = "qianyumeng0228"
    private const val REPO = "shizukux-apps"
    private const val BRANCH = "main"

    private const val RAW_BASE = "https://raw.githubusercontent.com/$OWNER/$REPO/$BRANCH"
    private const val JS_BASE = "https://cdn.jsdelivr.net/gh/$OWNER/$REPO@$BRANCH"
    private const val GH_PROXY = "https://gh-proxy.com/"

    const val SUBMIT_URL = "https://github.com/$OWNER/$REPO/issues/new"
    const val REPO_URL = "https://github.com/$OWNER/$REPO"

    private const val MAX_APK_BYTES = 512L * 1024L * 1024L
    private const val INDEX_CONNECT_TIMEOUT_MS = 6_000
    private const val INDEX_READ_TIMEOUT_MS = 8_000

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    data class Source(val name: String, val host: String, val indexUrl: String)

    val sources: List<Source> = listOf(
        Source("gh-proxy mirror", "gh-proxy.com", GH_PROXY + "$RAW_BASE/apps.json"),
        Source("jsDelivr CDN", "cdn.jsdelivr.net", "$JS_BASE/apps.json"),
        Source("GitHub raw", "raw.githubusercontent.com", "$RAW_BASE/apps.json"),
        Source("GitHub", "github.com", "https://github.com/$OWNER/$REPO/raw/$BRANCH/apps.json")
    )

    data class FetchedIndex(val index: AppStoreIndex, val source: Source)

    suspend fun fetchIndex(): FetchedIndex = withContext(Dispatchers.IO) {
        val results = coroutineScope {
            sources.map { source ->
                async(Dispatchers.IO) { runCatching { fetchDocument(source) } }
            }.awaitAll()
        }
        val reachable = results.mapNotNull { it.getOrNull() }
        if (reachable.isEmpty()) {
            val failure = results.firstNotNullOfOrNull { it.exceptionOrNull() }
            throw (failure as? Exception) ?: IOException("所有应用商店镜像都不可达")
        }
        val anchor = reachable.maxByOrNull { it.index.apps.size }!!
        val newest = reachable.flatMap { it.index.apps }
            .groupBy { it.packageName.ifBlank { it.id } }
            .mapValues { (_, list) -> list.maxByOrNull { it.versionCode ?: 0 }!! }
        val ordered = anchor.index.apps.map { newest.getValue(it.packageName.ifBlank { it.id }) } +
            newest.filterKeys { key -> anchor.index.apps.none { it.packageName.ifBlank { it.id } == key } }.values
        FetchedIndex(anchor.index.copy(apps = ordered), anchor.source)
    }

    private fun fetchDocument(source: Source): FetchedIndex {
        val body = httpGet(cacheBust(source.indexUrl))
        val index = json.decodeFromString(AppStoreIndex.serializer(), body)
        return FetchedIndex(index, source)
    }

    suspend fun download(
        context: Context,
        entry: AppStoreEntry,
        onProgress: (Float?) -> Unit = {}
    ): File = withContext(Dispatchers.IO) {
        val candidates = buildList {
            if (entry.downloadUrl.isNotBlank()) add(entry.downloadUrl)
            entry.fallbackUrl?.takeIf { it.isNotBlank() }?.let { add(it) }
        }.flatMap { expandCandidates(it) }.distinct()
        if (candidates.isEmpty()) throw IOException("该条目没有下载地址")

        val dir = File(context.cacheDir, "app_store").apply { mkdirs() }
        val target = File(dir, "${entry.packageName}-${entry.versionCode ?: 0}.apk")

        var lastError: Exception? = null
        for (url in candidates) {
            val temp = File(dir, "${entry.packageName}.part")
            runCatching { temp.delete() }
            try {
                downloadTo(url, temp, entry.sizeBytes, onProgress)
                verify(entry, temp)
                if (target.exists()) target.delete()
                if (!temp.renameTo(target)) {
                    temp.copyTo(target, overwrite = true)
                    temp.delete()
                }
                return@withContext target
            } catch (e: Exception) {
                lastError = e
                runCatching { temp.delete() }
            }
        }
        throw lastError ?: IOException("下载失败")
    }

    private fun expandCandidates(url: String): List<String> {
        val rawUrl = if (url.startsWith("$JS_BASE/")) "$RAW_BASE/" + url.removePrefix("$JS_BASE/") else url
        return if (rawUrl.startsWith("https://raw.githubusercontent.com/") ||
            rawUrl.startsWith("https://github.com/")
        ) {
            listOf(GH_PROXY + rawUrl, rawUrl)
        } else {
            listOf(rawUrl)
        }
    }

    private fun verify(entry: AppStoreEntry, file: File) {
        if (!file.isFile || file.length() == 0L) throw IOException("下载到的文件为空")
        if (file.length() > MAX_APK_BYTES) throw IOException("安装包超过 512MB")
        entry.sizeBytes?.let { declared ->
            if (declared > 0 && file.length() != declared) {
                throw IOException("大小不匹配：期望 $declared 字节，实际 ${file.length()} 字节")
            }
        }
        entry.sha256?.takeIf { it.isNotBlank() }?.let { expected ->
            val actual = sha256(file)
            if (!actual.equals(expected, ignoreCase = true)) throw IOException("SHA-256 校验失败")
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun httpGet(url: String): String {
        val connection = open(url, INDEX_CONNECT_TIMEOUT_MS, INDEX_READ_TIMEOUT_MS)
        try {
            val code = connection.responseCode
            if (code !in 200..299) throw IOException("HTTP $code from ${connection.url.host}")
            return connection.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
        } finally {
            connection.disconnect()
        }
    }

    private fun downloadTo(
        url: String,
        destination: File,
        declaredSize: Long?,
        onProgress: (Float?) -> Unit
    ) {
        val connection = open(url, INDEX_CONNECT_TIMEOUT_MS, 60_000)
        try {
            val code = connection.responseCode
            if (code !in 200..299) throw IOException("HTTP $code from ${connection.url.host}")
            val total = connection.getHeaderField("Content-Length")?.toLongOrNull()?.takeIf { it > 0 }
                ?: declaredSize?.takeIf { it > 0 }
            var written = 0L
            connection.inputStream.use { input ->
                destination.outputStream().use { output ->
                    val buffer = ByteArray(8192)
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        written += read
                        if (written > MAX_APK_BYTES) throw IOException("安装包超过 512MB")
                        output.write(buffer, 0, read)
                        onProgress(total?.let { (written.toFloat() / it).coerceIn(0f, 1f) })
                    }
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun open(url: String, connectMs: Int, readMs: Int): HttpURLConnection {
        return (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = connectMs
            readTimeout = readMs
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "ShizukuX-AppStore")
        }
    }

    private fun cacheBust(url: String): String =
        if (url.contains("?")) "$url&t=${System.currentTimeMillis()}" else "$url?t=${System.currentTimeMillis()}"
}
