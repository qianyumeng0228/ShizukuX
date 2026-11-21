package af.shizuku.manager.update

import android.content.pm.PackageManager
import android.os.Build
import android.util.Xml
import timber.log.Timber
import io.sentry.Sentry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.SSLException
import kotlinx.coroutines.withContext
import af.shizuku.manager.BuildConfig
import af.shizuku.manager.ShizukuApplication
import af.shizuku.manager.utils.ProjectLinks
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

object UpdateChecker {

    private const val TAG = "UpdateChecker"
    private const val RELEASES_URL = ProjectLinks.API_RELEASES
    private const val LATEST_URL = "$RELEASES_URL/latest"
    // Second-choice version probe. Gitee's API is reachable from mainland China without a token,
    // which is exactly what GitHub's api.github.com often is not.
    private const val GITEE_RELEASES_URL = ProjectLinks.API_RELEASES_GITEE
    private const val GITEE_LATEST_URL = "$GITEE_RELEASES_URL/latest"
    // Fallback: GitHub's Atom feed is served from github.com CDN — different IP range
    // than api.github.com, so routing issues specific to that host don't affect it.
    private const val ATOM_URL = ProjectLinks.RELEASES_ATOM
    private const val CONNECT_TIMEOUT_MS = 5_000
    private const val READ_TIMEOUT_MS = 8_000
    private const val RETRY_DELAY_MS = 2_000L

    data class UpdateInfo(
        val versionName: String,
        val versionCode: Int,
        val releaseNotes: String,
        val downloadUrls: List<String>,
        val publishedAt: String,
        val isPrerelease: Boolean,
        // Byte size of the asset as reported by GitHub. Used by mirrors to reject a same-named
        // but different file (e.g. a Gitee <name>.apk.zip upload, or a stale COS object).
        val expectedSize: Long = -1,
        // True when only the Atom fallback succeeded — no direct APK URL available.
        val requiresManualDownload: Boolean = false
    )

    sealed class CheckResult {
        data class UpdateAvailable(val info: UpdateInfo) : CheckResult()
        object UpToDate : CheckResult()
        object NetworkError : CheckResult()
    }

    /**
     * Check for an update with a three-tier probe.
     *
     * Probe order:
     *   1. GitHub API (up to 2 attempts, 2 s apart) — authoritative: it carries the prerelease
     *      flag, the asset byte size (the relay uses it to reject a wrong file) and a direct URL.
     *   2. Gitee API v5 — same release data served from a mainland host, anonymous, but without
     *      asset sizes. Used only when GitHub is unreachable, so the app still notices new
     *      versions on networks where api.github.com is blocked or timing out.
     *   3. GitHub Atom feed (stable channel only) — can tell that an update exists but gives no
     *      download URL, so the user is sent to the Releases page.
     *
     * Whichever probe answered, the download list is built the same way
     * (Gitee → CNB → JihuLab → GitHub → CF → COS): detection and delivery stay decoupled.
     */
    suspend fun checkForUpdate(channel: String = "stable"): CheckResult = withContext(Dispatchers.IO) {
        val transaction = Sentry.startTransaction("UpdateCheck", "check_for_update")
        Sentry.getSpan()?.setTag("channel", channel)

        try {
            for (attempt in 0 until 2) {
                if (attempt > 0) delay(RETRY_DELAY_MS)
                val span = transaction.startChild("github_api", "attempt_$attempt")
                try {
                    val result = checkViaApi(channel)
                    span.finish(io.sentry.SpanStatus.OK)
                    return@withContext result
                } catch (e: Exception) {
                    span.throwable = e
                    span.finish(io.sentry.SpanStatus.INTERNAL_ERROR)
                    if (e.isNetworkError()) {
                        Timber.tag(TAG).w("GitHub probe attempt ${attempt + 1} failed (network): ${e.message}")
                    } else {
                        Sentry.captureException(e)
                        return@withContext CheckResult.NetworkError
                    }
                }
            }

            // GitHub is unreachable — probe Gitee before degrading to a link-only answer.
            val giteeSpan = transaction.startChild("gitee_api", "fallback")
            try {
                val result = checkViaGitee(channel)
                giteeSpan.finish(io.sentry.SpanStatus.OK)
                Timber.tag(TAG).i("Update probed via Gitee fallback")
                return@withContext result
            } catch (e: Exception) {
                giteeSpan.throwable = e
                giteeSpan.finish(io.sentry.SpanStatus.INTERNAL_ERROR)
                Timber.tag(TAG).w(e, "Gitee probe failed")
            }

            if (isPrereleaseChannel(channel)) {
                Timber.tag(TAG).w("Both API probes unreachable; skipping Atom fallback for pre-release channel")
                return@withContext CheckResult.NetworkError
            }

            Timber.tag(TAG).w("Both API probes unreachable, trying Atom feed fallback")
            val fallbackSpan = transaction.startChild("atom_feed", "fallback")
            try {
                val fallback = checkViaAtomFeed()
                fallbackSpan.finish(io.sentry.SpanStatus.OK)
                if (fallback != null) return@withContext CheckResult.UpdateAvailable(fallback)
            } catch (e: Exception) {
                fallbackSpan.throwable = e
                fallbackSpan.finish(io.sentry.SpanStatus.INTERNAL_ERROR)
                Timber.tag(TAG).w(e, "Atom feed fallback also failed")
            }

            CheckResult.NetworkError
        } finally {
            transaction.finish()
        }
    }

    private fun checkViaApi(channel: String): CheckResult {
        val json: JSONObject = if (isPrereleaseChannel(channel)) {
            val arr = fetchJson("$RELEASES_URL?per_page=10") as? JSONArray
                ?: throw IOException("unexpected releases payload")
            (0 until arr.length())
                .map { arr.getJSONObject(it) }
                .firstOrNull {
                    it.optBoolean("prerelease", false) && !it.optBoolean("draft", false)
                }
                ?: return CheckResult.UpToDate
        } else {
            fetchJson(LATEST_URL) as? JSONObject ?: throw IOException("unexpected latest payload")
        }

        val tagName = json.getString("tag_name")
        val versionName = tagName.removePrefix("v")
        val isPrerelease = json.optBoolean("prerelease", false)
        val releaseNotes = json.optString("body", "")
        val publishedAt = json.optString("published_at", "")

        val assets = json.getJSONArray("assets")
        val isDropIn = BuildConfig.APPLICATION_ID == "moe.shizuku.privileged.api"
        // The OPPO package is not a separate flavor: it is the Drop-In APK re-signed with the
        // AOSP testkey, so package name alone cannot tell it apart. Read our own signing
        // certificate; a normal Drop-In must never be offered an update that would fail with
        // INSTALL_FAILED_UPDATE_INCOMPATIBLE.
        val isOppoTestKey = isDropIn && isOppoTestKeyBuild()
        val apkAssets = (0 until assets.length())
            .map { assets.getJSONObject(it) }
            .filter { it.getString("name").endsWith(".apk", ignoreCase = true) }
        val assetNames = apkAssets.map { it.getString("name") }

        // Release assets are named ShizukuX-<Variant>-v<version>.apk:
        //   ShizukuX-v13.7.0.k2017.apk            → standard
        //   ShizukuX-Drop-In-v13.7.0.k2017.apk    → drop-in (moe.shizuku.privileged.api)
        //   ShizukuX-OPPO-Drop-In-v13.7.0.k2017.apk → OPPO drop-in (AOSP testkey)
        //   ShizukuX-13.7.0.k2017-oppo-testkey.apk → legacy OPPO testkey spelling
        //   ShizukuX-Compat-Hub-v13.7.0.k2017.apk → compat hub
        // The OPPO branch is checked first: both OPPO names contain "drop-in", so the old
        // isDropInAsset() alone would hand a normal Drop-In update to a testkey install.
        val selectedName = selectAssetName(assetNames, isDropIn, isOppoTestKey)
        val targetAsset = apkAssets.firstOrNull { it.getString("name") == selectedName }
        val targetAssetName = selectedName.orEmpty()
        val githubUrl = targetAsset?.optString("browser_download_url").orEmpty()
        val assetSize = targetAsset?.optLong("size", -1L) ?: -1L
        val downloadUrls = if (targetAsset != null) {
            buildDownloadUrls(tagName, targetAssetName, githubUrl)
        } else {
            emptyList()
        }
        // No matching variant in this release: still report that a newer version exists, but
        // send the user to the release page instead of downloading an APK we cannot identify.
        val requiresManualDownload = downloadUrls.isEmpty()

        val versionCode = parseVersionCode(versionName)

        return if (isNewerVersion(versionName, BuildConfig.VERSION_NAME)) {
            Timber.tag(TAG).d("Update available: $versionName (channel=$channel, current=${BuildConfig.VERSION_NAME})")
            CheckResult.UpdateAvailable(
                UpdateInfo(
                    versionName, versionCode, releaseNotes, downloadUrls, publishedAt,
                    isPrerelease, assetSize, requiresManualDownload
                )
            )
        } else {
            Timber.tag(TAG).d("Already on latest ($channel): ${BuildConfig.VERSION_NAME}")
            CheckResult.UpToDate
        }
    }

    /**
     * Builds the multi-source download list for one release asset, in relay order.
     *
     * Order: Gitee → CNB → JihuLab → GitHub → Cloudflare → COS. UpdateManager tries each entry
     * in turn and only moves on when the previous one actually failed.
     *   Gitee           — release mirror, anonymous; serves from foruda.gitee.com in mainland.
     *                     Gitee's scanner sometimes stores an asset as `<name>.apk.zip` (a zip
     *                     wrapper around the real APK); those extra names are queued right after
     *                     the canonical one and UpdateManager unwraps them before installing.
     *   CNB             — 国内托管，只有 cnb.cool 主域可匿名下载（api.cnb.cool 会 401）。
     *   JihuLab         — 极狐 GitLab Generic Package Registry，匿名 200。
     *   GitHub          — original source (direct URL passed in, or rebuilt from the tag).
     *   Cloudflare      — GitHub reverse proxy; needs /<owner>/<repo>/releases/download/<tag>/<asset>.
     *   COS             — flat asset name on the bound custom domain (cos.shizukux.xyz).
     */
    private fun buildDownloadUrls(
        tagName: String,
        assetName: String,
        githubUrl: String,
        extraGiteeNames: List<String> = emptyList()
    ): List<String> = buildList {
        add("${ProjectLinks.MIRROR_GITEE}/$tagName/$assetName")
        extraGiteeNames.forEach { add("${ProjectLinks.MIRROR_GITEE}/$tagName/$it") }
        add("${ProjectLinks.MIRROR_CNB}/$tagName/$assetName")
        add("${ProjectLinks.MIRROR_JIHULAB}/$tagName/$assetName")
        add(
            githubUrl.takeIf { it.isNotBlank() }
                ?: "${ProjectLinks.REPOSITORY}/releases/download/$tagName/$assetName"
        )
        add("${ProjectLinks.MIRROR_CF}/$tagName/$assetName")
        add("${ProjectLinks.MIRROR_COS}/$assetName")
    }

    /**
     * Second-tier probe: Gitee API v5. Same release metadata as GitHub (tag, body, prerelease
     * flag, asset names) but no asset byte sizes, so [UpdateInfo.expectedSize] is left at -1
     * and the relay's size guard is skipped for this result.
     *
     * The list endpoint is ordered by release id, not by date, so the pre-release channel
     * picks the newest candidate by version stamp instead of taking the first row.
     */
    private fun checkViaGitee(channel: String): CheckResult {
        val isDropIn = BuildConfig.APPLICATION_ID == "moe.shizuku.privileged.api"
        val isOppoTestKey = isDropIn && isOppoTestKeyBuild()
        val json: JSONObject = if (isPrereleaseChannel(channel)) {
            val arr = fetchJson("$GITEE_RELEASES_URL?per_page=20&page=1", accept = "application/json") as? JSONArray
                ?: throw IOException("unexpected gitee releases payload")
            val candidates = (0 until arr.length())
                .map { arr.getJSONObject(it) }
                .filter { it.optBoolean("prerelease", false) }
            var best: JSONObject? = null
            for (candidate in candidates) {
                val tag = candidate.optString("tag_name")
                if (tag.isBlank()) continue
                if (best == null || isNewerVersion(tag.removePrefix("v"), best.optString("tag_name").removePrefix("v"))) {
                    best = candidate
                }
            }
            best ?: return CheckResult.UpToDate
        } else {
            fetchJson(GITEE_LATEST_URL, accept = "application/json") as? JSONObject
                ?: throw IOException("unexpected gitee latest payload")
        }

        val tagName = json.optString("tag_name")
        if (tagName.isBlank()) throw IOException("gitee release without tag_name")
        val versionName = tagName.removePrefix("v")
        val releaseNotes = json.optString("body", "")
        val publishedAt = json.optString("created_at", "")
        val isPrerelease = json.optBoolean("prerelease", false)

        val assets = json.optJSONArray("assets") ?: JSONArray()
        val names = (0 until assets.length())
            .map { assets.getJSONObject(it).optString("name") }
            .filter { it.endsWith(".apk", true) || it.endsWith(".apk.zip", true) }

        val targetBase = selectAssetName(
            names.map { baseAssetName(it) },
            isDropIn,
            isOppoTestKey
        )
        val giteeZipNames = if (targetBase != null) {
            names.filter { it.equals("$targetBase.zip", ignoreCase = true) }
        } else {
            emptyList()
        }
        val downloadUrls = if (targetBase != null) {
            buildDownloadUrls(tagName, targetBase, "", giteeZipNames)
        } else {
            emptyList()
        }

        return if (isNewerVersion(versionName, BuildConfig.VERSION_NAME)) {
            Timber.tag(TAG).d("Update available via Gitee: $versionName (current=${BuildConfig.VERSION_NAME})")
            CheckResult.UpdateAvailable(
                UpdateInfo(
                    versionName, parseVersionCode(versionName), releaseNotes, downloadUrls,
                    publishedAt, isPrerelease, -1L, downloadUrls.isEmpty()
                )
            )
        } else {
            Timber.tag(TAG).d("Already on latest (Gitee): ${BuildConfig.VERSION_NAME}")
            CheckResult.UpToDate
        }
    }

    /** Strips Gitee's `.zip` wrapper suffix: `x.apk.zip` → `x.apk`, anything else unchanged. */
    private fun baseAssetName(name: String): String =
        if (name.endsWith(".zip", ignoreCase = true)) name.dropLast(4) else name

    /**
     * Fetches the release notes body for a specific tag (e.g. "v13.6.0.r2162") — used by the
     * in-app changelog dialog to show what changed in the version the user just updated to,
     * as opposed to [checkForUpdate]'s "latest" which may have moved on by the time they open
     * the app. Returns null on any failure (offline, tag not found, etc.) so callers can fall
     * back to a generic message instead of failing the whole dialog.
     */
    suspend fun fetchReleaseNotesForTag(tag: String): String? = withContext(Dispatchers.IO) {
        try {
            val json = fetchJson("$RELEASES_URL/tags/$tag") as? JSONObject ?: return@withContext null
            json.optString("body", "").takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Failed to fetch release notes for tag $tag")
            null
        }
    }

    /**
     * Reads GitHub's public Atom feed as a last-resort fallback.
     * Served from github.com CDN — a different network path than api.github.com.
     * Can tell us whether an update exists but cannot provide a direct APK URL.
     */
    private fun checkViaAtomFeed(): UpdateInfo? {
        val connection = (URL(ATOM_URL).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            setRequestProperty("User-Agent", "ShizukuX/${BuildConfig.VERSION_NAME}")
        }
        try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) return null

            val parser = Xml.newPullParser()
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            parser.setInput(connection.inputStream, null)

            var eventType = parser.eventType
            while (eventType != XmlPullParser.END_DOCUMENT) {
                if (eventType == XmlPullParser.START_TAG && parser.name == "link") {
                    val href = parser.getAttributeValue(null, "href") ?: ""
                    if (href.contains("/releases/tag/")) {
                        val tagName = href.substringAfterLast("/releases/tag/")
                        val versionName = tagName.removePrefix("v")
                        val versionCode = parseVersionCode(versionName)
                        if (isNewerVersion(versionName, BuildConfig.VERSION_NAME)) {
                            Timber.tag(TAG).d("Atom fallback: update available $versionName")
                            return UpdateInfo(
                                versionName = versionName,
                                versionCode = versionCode,
                                releaseNotes = "",
                                downloadUrls = emptyList(),
                                publishedAt = "",
                                isPrerelease = versionName.contains("beta", ignoreCase = true)
                                        || versionName.contains("alpha", ignoreCase = true),
                                requiresManualDownload = true
                            )
                        }
                        return null // First release entry checked — already up to date
                    }
                }
                eventType = parser.next()
            }
            return null
        } finally {
            connection.disconnect()
        }
    }

    private fun Exception.isNetworkError(): Boolean =
        this is UnknownHostException || this is SocketTimeoutException ||
        this is ConnectException || this is SSLException || this is IOException

    /**
     * Chooses exactly one release asset for the installed package.
     *
     * OPPO is checked first and is a strict branch: an OPPO/testkey install must never fall
     * back to the project-signed Drop-In. `INSTALL_FAILED_UPDATE_INCOMPATIBLE` is the result
     * of mixing those two, and the updater cannot repair a signature mismatch by retrying
     * another mirror.
     */
    internal fun selectAssetName(
        assetNames: List<String>,
        isDropIn: Boolean,
        isOppoTestKey: Boolean
    ): String? = assetNames.firstOrNull { name ->
        when {
            isOppoTestKey -> isOppoAsset(name)
            isDropIn -> isDropInAsset(name) && !isOppoAsset(name)
            else -> isStandardAsset(name)
        }
    }

    /** Any OPPO channel asset, including the legacy `-oppo-testkey` spelling. */
    private fun isOppoAsset(name: String): Boolean =
        name.lowercase().contains("oppo")

    /**
     * "Drop-In" contains a hyphen, so a plain `contains("dropin")` check misses it. All three
     * spellings are accepted in case the release workflow renames the asset again.
     */
    private fun isDropInAsset(name: String): Boolean =
        name.lowercase().let {
            it.contains("drop-in") || it.contains("dropin") || it.contains("drop_in")
        }

    /** Everything that is neither the drop-in nor the compat-hub asset. */
    private fun isStandardAsset(name: String): Boolean =
        name.lowercase().let {
            !it.contains("drop-in") && !it.contains("dropin") && !it.contains("drop_in") &&
                !it.contains("compat")
        }

    /**
     * True only for the AOSP-testkey-signed OPPO package. The package name is identical to
     * the normal Drop-In, so the signing certificate is the only stable discriminator:
     * AOSP's platform testkey carries `android@android.com` in its subject.
     */
    private fun isOppoTestKeyBuild(): Boolean = runCatching {
        val context = ShizukuApplication.appContext
        val certs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            context.packageManager
                .getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
                .signingInfo
                ?.apkContentsSigners
                .orEmpty()
        } else {
            @Suppress("DEPRECATION")
            context.packageManager
                .getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES)
                .signatures
                .orEmpty()
        }
        if (certs.isEmpty()) return@runCatching false
        val factory = CertificateFactory.getInstance("X.509")
        certs.any { cert ->
            val subject = runCatching {
                val parsed = factory.generateCertificate(
                    ByteArrayInputStream(cert.toByteArray())
                ) as X509Certificate
                parsed.subjectX500Principal.name
            }.getOrDefault("")
            subject.contains("android@android.com") || subject.contains("CN=Android")
        }
    }.getOrDefault(false)

    private fun isPrereleaseChannel(channel: String): Boolean =
        channel.equals("dev", ignoreCase = true) || channel.equals("beta", ignoreCase = true)

    private fun fetchJson(urlString: String, accept: String = "application/vnd.github.v3+json"): Any? {
        val connection = (URL(urlString).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            setRequestProperty("Accept", accept)
            setRequestProperty("User-Agent", "ShizukuX/${BuildConfig.VERSION_NAME}")
        }
        try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                // Not "no data": a 403 rate limit / 404 / 5xx must fail the attempt so the caller
                // retries and the Atom fallback gets a chance, instead of reporting "already up
                // to date" (and locking that answer in for 24 hours) when the API is unreachable.
                Timber.tag(TAG).w("HTTP ${connection.responseCode} from $urlString")
                throw IOException("HTTP ${connection.responseCode} from $urlString")
            }
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            return if (body.trimStart().startsWith("[")) JSONArray(body) else JSONObject(body)
        } finally {
            connection.disconnect()
        }
    }

    /** Extracts the build number from "13.6.0.r1488" → 1488, or "13.6.0.k2007" → 2007 */
    fun parseVersionCode(versionName: String): Int = try {
        """\.\b[kr](\d+)\b""".toRegex().find(versionName)?.groupValues?.get(1)?.toIntOrNull() ?: 0
    } catch (e: Exception) {
        0
    }

    /**
     * Parses a version stamp for *ordered* comparison: "13.7.0.k2002" →
     * VersionStamp(13, 7, 0, 'k', 2002). Comparison is decided by the numeric mainline
     * triple FIRST and only then by the k/r suffix, so a newer mainline (13.7.0) is never
     * reported as older than an old mainline (13.6.0.k2014) just because its suffix digit
     * happens to be smaller — the historical "downgrade" bug where users on 13.7.0 were
     * offered 13.6.0 as an update. Within the same mainline, k/k and r/r compare by digit;
     * a mixed k-vs-r pair is resolved by channel preference (stable r supersedes dev k)
     * because r and k counters are independent (13.6.0.r2002 was re-tagged from
     * 13.6.0.k2014 with the same versionCode). Falls back to the old digit-only comparison
     * when the triple cannot be parsed (e.g. plain "13.6.0").
     */
    private fun isNewerVersion(versionName: String, currentVersionName: String): Boolean {
        val candidate = parseVersionStamp(versionName)
        val current = parseVersionStamp(currentVersionName)
        if (candidate != null && current != null) {
            return candidate > current
        }
        return parseVersionCode(versionName) > parseVersionCode(currentVersionName)
    }

    private data class VersionStamp(
        val major: Int,
        val minor: Int,
        val patch: Int,
        val suffixType: Char?,
        val suffix: Int
    ) : Comparable<VersionStamp> {
        override fun compareTo(other: VersionStamp): Int {
            compareValues(major, other.major).let { if (it != 0) return it }
            compareValues(minor, other.minor).let { if (it != 0) return it }
            compareValues(patch, other.patch).let { if (it != 0) return it }
            val mine = suffixType
            val theirs = other.suffixType
            if (mine == null && theirs == null) return 0
            if (mine == null) return -1
            if (theirs == null) return 1
            if (mine == theirs) return suffix.compareTo(other.suffix)
            // Mixed k/r on the same mainline: a released build supersedes a dev build
            // regardless of the (independent) counters.
            return if (mine.equals('r', ignoreCase = true)) 1 else -1
        }
    }

    private fun parseVersionStamp(versionName: String): VersionStamp? = try {
        val triple = Regex("""(\d+)\.(\d+)\.(\d+)""").find(versionName) ?: return null
        val (major, minor, patch) = triple.destructured
        val suffix = Regex("""\.([kr])(\d+)""", RegexOption.IGNORE_CASE).find(versionName)
        VersionStamp(
            major.toInt(),
            minor.toInt(),
            patch.toInt(),
            suffix?.groupValues?.get(1)?.firstOrNull(),
            suffix?.groupValues?.get(2)?.toIntOrNull() ?: 0
        )
    } catch (e: Exception) {
        null
    }

    // ------------------------------------------------------------------------------------
    // Per-installed-version update content cache.
    // The About screen shows the release notes of the version actually installed (not the
    // latest release, which may have moved on). Notes are cached locally per version; a
    // background sync periodically re-checks the upstream release body for that exact tag and
    // refreshes the cache when it differs (e.g. the maintainer edited the notes after release).
    // ------------------------------------------------------------------------------------
    private const val CACHE_PREF_NAME = "update_content_cache"
    private const val CACHE_KEY_VERSION = "cached_version"
    private const val CACHE_KEY_CONTENT = "cached_content"
    private const val CACHE_KEY_TIME = "cached_time"
    private const val CACHE_REFRESH_INTERVAL_MS = 6 * 60 * 60 * 1000L // 6 h

    data class CachedUpdateContent(val version: String, val content: String, val cachedAt: Long)

    /** Extracts the installed version segment, e.g. "ShizukuX 13.6.0.k2007" → "13.6.0.k2007". */
    fun currentVersionPart(): String? =
        Regex("""\d+\.\d+\.\d+\.[kr]\d+""").find(BuildConfig.VERSION_NAME)?.value

    /** The GitHub Release tag for the installed version, e.g. "v13.6.0.k2007". */
    fun currentVersionTag(): String? = currentVersionPart()?.let { "v$it" }

    private fun cachePrefs(context: android.content.Context) =
        context.getSharedPreferences(CACHE_PREF_NAME, android.content.Context.MODE_PRIVATE)

    fun getCachedUpdateContent(context: android.content.Context): CachedUpdateContent? {
        val p = cachePrefs(context)
        val version = p.getString(CACHE_KEY_VERSION, null) ?: return null
        val content = p.getString(CACHE_KEY_CONTENT, null) ?: return null
        return CachedUpdateContent(version, content, p.getLong(CACHE_KEY_TIME, 0))
    }

    fun saveCachedUpdateContent(context: android.content.Context, version: String, content: String) {
        cachePrefs(context).edit()
            .putString(CACHE_KEY_VERSION, version)
            .putString(CACHE_KEY_CONTENT, content)
            .putLong(CACHE_KEY_TIME, System.currentTimeMillis())
            .apply()
    }

    /**
     * Background sniff: re-fetch the upstream release notes for the *installed* version and sync
     * the local cache when they differ. Does nothing while the cache is fresh (same version, less
     * than [CACHE_REFRESH_INTERVAL_MS] old).
     */
    suspend fun syncCachedUpdateContentIfNeeded(context: android.content.Context) {
        val version = currentVersionPart() ?: return
        val cached = getCachedUpdateContent(context)
        if (cached != null && cached.version == version &&
            System.currentTimeMillis() - cached.cachedAt < CACHE_REFRESH_INTERVAL_MS) return
        val fresh = fetchReleaseNotesForTag(currentVersionTag() ?: return) ?: return
        if (cached?.content != fresh) {
            saveCachedUpdateContent(context, version, fresh)
            Timber.tag(TAG).d("Synced cached update content for $version")
        }
    }

    /**
     * Returns the update content to show for the *installed* version: the fresh cache when
     * available, otherwise fetches upstream and refreshes the cache (falling back to an old cache
     * of the same version if the network is unavailable).
     */
    suspend fun getUpdateContentForCurrentVersion(context: android.content.Context): String? {
        val version = currentVersionPart() ?: return null
        val cached = getCachedUpdateContent(context)
        if (cached != null && cached.version == version &&
            System.currentTimeMillis() - cached.cachedAt < CACHE_REFRESH_INTERVAL_MS) {
            return cached.content
        }
        val fresh = fetchReleaseNotesForTag(currentVersionTag() ?: return null)
        val content = fresh?.takeIf { it.isNotBlank() }
            ?: cached?.takeIf { it.version == version }?.content
        if (fresh != null) saveCachedUpdateContent(context, version, fresh)
        return content
    }

    fun formatPublishedDate(dateString: String): String = try {
        val input = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }
        SimpleDateFormat("MMM dd, yyyy", Locale.getDefault()).format(input.parse(dateString) as Date)
    } catch (e: Exception) {
        dateString
    }
}
