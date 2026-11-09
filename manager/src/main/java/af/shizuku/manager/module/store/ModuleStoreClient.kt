package af.shizuku.manager.module.store

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Network access for the module store.
 *
 * The official catalog lives at `qianyumeng0228/shizukux-modules`. In mainland networks
 * `cdn.jsdelivr.net` and `raw.githubusercontent.com` are frequently reset, so a gh-proxy
 * mirror is tried first; the CDN and raw endpoints stay as fallbacks and are what a fork
 * would point at directly.
 */
object ModuleStoreClient {

    private const val OWNER = "qianyumeng0228"
    private const val REPO = "shizukux-modules"
    private const val BRANCH = "main"

    private const val RAW_BASE = "https://raw.githubusercontent.com/$OWNER/$REPO/$BRANCH"
    private const val JS_BASE = "https://cdn.jsdelivr.net/gh/$OWNER/$REPO@$BRANCH"
    private const val GH_PROXY = "https://gh-proxy.com/"

    const val MAX_ZIP_BYTES = 32L * 1024L * 1024L
    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 30_000

    // The catalog is a few hundred bytes, so a slow or blocked endpoint must not hold the
    // screen on "loading": every source is asked in parallel and a dead mirror is written off
    // quickly instead of being waited on.
    private const val INDEX_CONNECT_TIMEOUT_MS = 6_000
    private const val INDEX_READ_TIMEOUT_MS = 8_000

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    data class Source(val name: String, val host: String, val indexUrl: String)

    val sources: List<Source> = listOf(
        Source(
            name = "gh-proxy mirror",
            host = "gh-proxy.com",
            indexUrl = GH_PROXY + "$RAW_BASE/modules.json"
        ),
        Source(
            name = "jsDelivr CDN",
            host = "cdn.jsdelivr.net",
            indexUrl = "$JS_BASE/modules.json"
        ),
        Source(
            name = "GitHub raw",
            host = "raw.githubusercontent.com",
            indexUrl = "$RAW_BASE/modules.json"
        ),
        Source(
            name = "GitHub",
            host = "github.com",
            indexUrl = "https://github.com/$OWNER/$REPO/raw/$BRANCH/modules.json"
        )
    )

    data class FetchedIndex(
        val index: ModuleStoreIndex,
        val source: Source
    )

    /**
     * Fetches the index from every source concurrently and merges the results by module id,
     * keeping the highest `versionCode` seen for each id.
     *
     * Sequential first-success fallback is not enough here: a mirror can answer HTTP 200 with
     * an edge-cached copy of an older catalog, which would silently pin the store to a stale
     * version. Asking all sources and taking the newest entry means one stale mirror cannot
     * hide a freshly published module.
     */
    suspend fun fetchIndex(): FetchedIndex = withContext(Dispatchers.IO) {
        val results = coroutineScope {
            sources.map { source ->
                async(Dispatchers.IO) {
                    runCatching {
                        val body = httpGet(cacheBusted(source.indexUrl))
                        val index = json.decodeFromString(ModuleStoreIndex.serializer(), body)
                        if (index.modules.isEmpty()) {
                            throw IOException("Catalog at ${source.host} lists no modules.")
                        }
                        FetchedIndex(index, source)
                    }
                }
            }.awaitAll()
        }

        val reachable = results.mapNotNull { it.getOrNull() }
        if (reachable.isEmpty()) {
            val failure = results.firstNotNullOfOrNull { it.exceptionOrNull() }
            throw (failure as? Exception) ?: IOException("No module catalog source reachable.")
        }

        val newest = reachable
            .flatMap { it.index.modules }
            .groupBy { it.id }
            .mapValues { (_, candidates) -> candidates.maxByOrNull { it.versionCode ?: 0 }!! }

        // Keep the ordering and metadata of the catalog that carries the most versions overall.
        val anchor = reachable.maxByOrNull { it.index.modules.sumOf { m -> m.versionCode ?: 0 } }!!
        val ordered = anchor.index.modules.map { newest.getValue(it.id) } +
            newest.filterKeys { id -> anchor.index.modules.none { it.id == id } }.values

        FetchedIndex(anchor.index.copy(modules = ordered), anchor.source)
    }

    /**
     * Downloads [entry] into the app cache and verifies the declared size and SHA-256.
     * The returned file is fed straight into `AdbModuleManager.install` as a `file://` Uri.
     */
    suspend fun download(
        context: Context,
        entry: ModuleStoreEntry,
        onProgress: (Float?) -> Unit = {}
    ): File = withContext(Dispatchers.IO) {
        val urls = buildList {
            if (entry.downloadUrl.isNotBlank()) add(entry.downloadUrl)
            entry.fallbackUrl?.takeIf { it.isNotBlank() }?.let { add(it) }
            if (entry.downloadUrl.isBlank() && entry.fallbackUrl.isNullOrBlank()) {
                // Last resort: the conventional path inside the official repo.
                add("$JS_BASE/modules/${entry.id}-${entry.version}.zip")
            }
        }.flatMap { expandCandidates(it) }.distinct()

        val dir = File(context.cacheDir, "module_store").apply { mkdirs() }
        val target = File(dir, "${entry.id}-${entry.version ?: "unknown"}.zip")

        var lastError: Exception? = null
        for ((position, url) in urls.withIndex()) {
            val temp = File(dir, "${entry.id}.part")
            temp.delete()
            try {
                httpDownload(url, temp, entry.sizeBytes, onProgress)
                verify(entry, temp)
                if (target.exists()) target.delete()
                if (!temp.renameTo(target)) {
                    temp.copyTo(target, overwrite = true)
                    temp.delete()
                }
                return@withContext target
            } catch (e: Exception) {
                lastError = e
                temp.delete()
                if (position == urls.lastIndex) break
            }
        }
        throw lastError ?: IOException("Download failed.")
    }

    /**
     * Expands one catalog URL into reachable candidates: an official jsDelivr link is first
     * rewritten to its raw path (the proxy cannot serve the CDN form), then the proxy is tried
     * before the direct URL so a mainland network still gets the package.
     */
    private fun expandCandidates(url: String): List<String> {
        val rawUrl = when {
            url.startsWith("$JS_BASE/") -> "$RAW_BASE/" + url.removePrefix("$JS_BASE/")
            else -> url
        }
        return when {
            rawUrl.startsWith("https://raw.githubusercontent.com/") ||
                rawUrl.startsWith("https://github.com/") ->
                listOf(GH_PROXY + rawUrl, rawUrl)
            else -> listOf(rawUrl)
        }
    }

    private fun verify(entry: ModuleStoreEntry, file: File) {
        if (!file.isFile || file.length() == 0L) throw IOException("Downloaded file is empty.")
        if (file.length() > MAX_ZIP_BYTES) throw IOException("Module package is too large.")
        entry.sizeBytes?.let { declared ->
            if (declared > 0 && file.length() != declared) {
                throw IOException("Size mismatch: expected $declared bytes, got ${file.length()}.")
            }
        }
        entry.sha256?.takeIf { it.isNotBlank() }?.let { expected ->
            val actual = MessageDigest.getInstance("SHA-256").run {
                file.inputStream().use { input ->
                    val buf = ByteArray(8192)
                    while (true) {
                        val read = input.read(buf)
                        if (read <= 0) break
                        update(buf, 0, read)
                    }
                }
                digest().joinToString("") { "%02x".format(it) }
            }
            if (!actual.equals(expected, ignoreCase = true)) {
                throw IOException("Checksum mismatch for ${entry.id}.")
            }
        }
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

    private fun httpDownload(
        url: String,
        destination: File,
        declaredSize: Long?,
        onProgress: (Float?) -> Unit
    ) {
        val connection = open(url)
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
                        if (written > MAX_ZIP_BYTES) throw IOException("Module package is too large.")
                        output.write(buffer, 0, read)
                        onProgress(total?.let { (written.toFloat() / it).coerceIn(0f, 1f) })
                    }
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    /**
     * gh-proxy and jsDelivr both cache edge responses; a timestamp query makes every refresh
     * read the current catalog instead of a copy cached minutes ago. GitHub raw ignores the
     * extra parameter, so the same URL shape works on all sources.
     */
    private fun cacheBusted(url: String): String {
        val separator = if (url.contains('?')) "&" else "?"
        return url + separator + "t=" + System.currentTimeMillis()
    }

    private fun open(
        url: String,
        connectTimeoutMs: Int = CONNECT_TIMEOUT_MS,
        readTimeoutMs: Int = READ_TIMEOUT_MS
    ): HttpURLConnection {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.connectTimeout = connectTimeoutMs
        connection.readTimeout = readTimeoutMs
        connection.instanceFollowRedirects = true
        connection.useCaches = false
        connection.setRequestProperty("User-Agent", "ShizukuX-ModuleStore/1.0")
        connection.setRequestProperty("Accept", "application/json, application/zip, */*")
        connection.setRequestProperty("Cache-Control", "no-cache")
        return connection
    }

    /** Keeps the cache from growing forever after repeated installs. */
    fun cleanupOldDownloads(context: Context) {
        val dir = File(context.cacheDir, "module_store")
        if (!dir.isDirectory) return
        val cutoff = System.currentTimeMillis() - 24 * 60 * 60 * 1000L
        dir.listFiles()?.forEach { file ->
            if (file.isFile && file.lastModified() < cutoff) file.delete()
        }
    }
}
