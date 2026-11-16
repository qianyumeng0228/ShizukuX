package af.shizuku.manager.module.store

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import af.shizuku.manager.module.ModuleProp
import af.shizuku.manager.module.ModuleValidator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** A module ZIP that passed every local check and is ready to be submitted. */
data class ModuleUploadCandidate(
    val localFile: File,
    val fileName: String,
    val sizeBytes: Long,
    val sha256: String,
    val id: String,
    val name: String,
    val version: String?,
    val versionCode: Long?,
    val author: String?,
    val description: String?,
    val usesShellBridge: Boolean,
    val minSdk: Int?,
    val nameZh: String? = null,
    val descriptionZh: String? = null,
    val tags: List<String> = emptyList(),
    /** Raw module.prop text, kept so [ModuleUploadClient.applyDraft] can rewrite it in place. */
    val propText: String = ""
) {
    /** Human-readable size, same formatting as the store rows. */
    val formattedSize: String
        get() {
            val kb = sizeBytes / 1024.0
            return if (kb < 1024.0) "%.1f KB".format(kb) else "%.1f MB".format(kb / 1024.0)
        }
}

/**
 * The store-facing fields the author may edit on the upload sheet. Everything here is copied
 * from module.prop on pick, then written back into the archive on submit so the catalog and the
 * installed module cannot disagree.
 */
data class ModuleDraft(
    val name: String = "",
    val nameZh: String = "",
    val author: String = "",
    val version: String = "",
    val versionCode: String = "",
    val description: String = "",
    val descriptionZh: String = "",
    val tags: String = ""
) {
    val tagList: List<String>
        get() = tags.split(',', '，', '、', ' ')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .take(MAX_TAGS)

    companion object {
        const val MAX_TAGS = 6

        fun of(candidate: ModuleUploadCandidate) = ModuleDraft(
            name = candidate.name,
            nameZh = candidate.nameZh.orEmpty(),
            author = candidate.author.orEmpty(),
            version = candidate.version.orEmpty(),
            versionCode = candidate.versionCode?.toString().orEmpty(),
            description = candidate.description.orEmpty(),
            descriptionZh = candidate.descriptionZh.orEmpty(),
            tags = candidate.tags.joinToString("、")
        )
    }
}

/** State for the upload sheet. Kept next to the client so both live in one place. */
data class ModuleUploadUiState(
    val visible: Boolean = false,
    val inspecting: Boolean = false,
    val candidate: ModuleUploadCandidate? = null,
    /** Editable store-facing fields; non-null exactly while a candidate is loaded. */
    val draft: ModuleDraft? = null,
    val submitting: Boolean = false,
    val message: String? = null,
    val error: String? = null,
    val done: Boolean = false
) {
    val busy: Boolean get() = inspecting || submitting
}

/**
 * Local validation + network submission for user-contributed modules.
 *
 * Everything that can be checked without a server happens here first, with the same rules the
 * installer enforces ([ModuleValidator]): byte cap, entry count, path traversal, module.prop
 * size and id grammar. A rejected ZIP never reaches the network, so the submit endpoint only
 * has to re-check what a hostile client could lie about.
 */
object ModuleUploadClient {

    const val MAX_BYTES = ModuleStoreClient.MAX_ZIP_BYTES

    /**
     * Submission endpoint. Empty means "no backend configured": the sheet then degrades to
     * exporting the manifest so the module can be submitted by hand, instead of dead-ending on
     * a button that can only fail.
     *
     * Contract (see `tools/module-submit-worker`): multipart/form-data with a `meta` JSON part
     * and a `file` part; a JSON reply `{"ok":true,"message":"..."}` or `{"ok":false,"error":...}`.
     */
    const val ENDPOINT = "https://submit.zanzhuwang.cc.cd/submit"

    private val json = Json { ignoreUnknownKeys = true; isLenient = true; prettyPrint = true }

    /**
     * Copies the picked document into the app cache while enforcing the size budget and
     * computing SHA-256, then validates the archive structure and reads `module.prop`.
     */
    suspend fun inspect(context: Context, uri: Uri): ModuleUploadCandidate = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val displayName = queryDisplayName(context, uri) ?: "module.zip"

        val dir = File(context.cacheDir, "module-upload").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val temp = File(dir, "upload.zip")

        val digest = MessageDigest.getInstance("SHA-256")
        val total = resolver.openInputStream(uri)?.use { input ->
            temp.outputStream().use { output -> copyAndHash(input, output, digest) }
        } ?: throw IOException("Cannot open the selected file.")

        val (props, propText) = readModuleProps(temp)
        val id = props["id"].orEmpty()
        require(ModuleProp.isValidModuleId(id)) {
            "module.prop 的 id 不合法（需字母开头，仅含字母、数字、. _ -）"
        }

        ModuleUploadCandidate(
            localFile = temp,
            fileName = displayName,
            sizeBytes = total,
            sha256 = digest.digest().joinToString("") { "%02x".format(it) },
            id = id,
            name = props["name"].orEmpty().ifBlank { id },
            version = props["version"]?.takeIf { it.isNotBlank() },
            versionCode = props["versionCode"]?.toLongOrNull(),
            author = props["author"]?.takeIf { it.isNotBlank() },
            description = props["description"]?.takeIf { it.isNotBlank() },
            usesShellBridge = props["usesShellBridge"]?.toBoolean() == true ||
                props["declaresShellBridge"]?.toBoolean() == true,
            minSdk = props["minSdk"]?.toIntOrNull(),
            nameZh = props["nameZh"]?.takeIf { it.isNotBlank() },
            descriptionZh = props["descriptionZh"]?.takeIf { it.isNotBlank() },
            tags = props["tags"]?.split(',', '，', '、')
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() }
                ?.take(ModuleDraft.MAX_TAGS)
                ?: emptyList(),
            propText = propText
        )
    }

    /** The manifest this submission would publish, shown on the export path. */
    fun manifestJson(candidate: ModuleUploadCandidate): String = json.encodeToString(
        ModuleSubmitMeta.serializer(),
        ModuleSubmitMeta(
            id = candidate.id,
            name = candidate.name,
            nameZh = candidate.nameZh,
            version = candidate.version,
            versionCode = candidate.versionCode,
            author = candidate.author,
            description = candidate.description,
            descriptionZh = candidate.descriptionZh,
            tags = candidate.tags,
            usesShellBridge = candidate.usesShellBridge,
            minSdk = candidate.minSdk,
            sizeBytes = candidate.sizeBytes,
            sha256 = candidate.sha256
        )
    )

    /**
     * Writes the edited fields back into `module.prop` and repacks the archive, so the module the
     * user installs shows exactly what the store shows. Hash and size are recomputed because the
     * bytes changed.
     */
    suspend fun applyDraft(
        candidate: ModuleUploadCandidate,
        draft: ModuleDraft
    ): ModuleUploadCandidate = withContext(Dispatchers.IO) {
        val name = draft.name.trim().ifBlank { candidate.name }
        val version = draft.version.trim().removePrefix("v").removePrefix("V")
        require(version.isNotEmpty()) { "版本号不能为空" }
        require(Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,31}$").matches(version)) {
            "版本号只能用字母、数字、. _ -，且不超过 32 个字符"
        }
        val code = draft.versionCode.trim().toLongOrNull()
        require(code == null || code > 0) { "版本码需为正整数" }

        val prop = rewriteProp(
            original = candidate.propText,
            values = linkedMapOf(
                "name" to name,
                "nameZh" to draft.nameZh.trim(),
                "version" to version,
                "versionCode" to code?.toString().orEmpty(),
                "author" to draft.author.trim(),
                "description" to draft.description.trim(),
                "descriptionZh" to draft.descriptionZh.trim(),
                "tags" to draft.tagList.joinToString(",")
            )
        )

        val dir = candidate.localFile.parentFile ?: throw IOException("临时目录不可用")
        val edited = File(dir, "upload-edited.zip")
        if (edited.exists()) edited.delete()

        var replaced = false
        ZipInputStream(candidate.localFile.inputStream().buffered()).use { input ->
            ZipOutputStream(edited.outputStream().buffered()).use { output ->
                while (true) {
                    val entry = input.nextEntry ?: break
                    if (entry.isDirectory) {
                        input.closeEntry()
                        continue
                    }
                    val clean = ModuleValidator.cleanRelativePath(entry.name)
                    val isProp = clean == "module.prop" || clean.endsWith("/module.prop")
                    output.putNextEntry(ZipEntry(entry.name).apply { time = entry.time })
                    if (isProp && !replaced) {
                        replaced = true
                        output.write(prop.toByteArray(Charsets.UTF_8))
                    } else {
                        input.copyTo(output)
                    }
                    output.closeEntry()
                    input.closeEntry()
                }
            }
        }
        require(replaced) { "ZIP 里没有 module.prop" }

        val digest = MessageDigest.getInstance("SHA-256")
        edited.inputStream().use { stream ->
            val buffer = ByteArray(8192)
            while (true) {
                val read = stream.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }

        candidate.copy(
            localFile = edited,
            sizeBytes = edited.length(),
            sha256 = digest.digest().joinToString("") { "%02x".format(it) },
            name = name,
            nameZh = draft.nameZh.trim().ifBlank { null },
            descriptionZh = draft.descriptionZh.trim().ifBlank { null },
            author = draft.author.trim().ifBlank { null },
            version = version,
            versionCode = code,
            description = draft.description.trim().ifBlank { null },
            tags = draft.tagList,
            propText = prop
        )
    }

    /**
     * Rewrites known keys of module.prop and keeps every other line as-is; a blank value removes
     * the key, and keys the archive did not carry are appended.
     */
    private fun rewriteProp(original: String, values: Map<String, String>): String {
        val kept = mutableListOf<String>()
        val seen = mutableSetOf<String>()
        for (line in original.lines()) {
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                kept.add(line)
                continue
            }
            val key = trimmed.substringBefore('=', "").trim()
            if (key.isEmpty() || key !in values) {
                kept.add(line)
                continue
            }
            seen.add(key)
            val value = values[key].orEmpty()
            if (value.isNotBlank()) kept.add("$key=$value")
        }
        // Appended keys must sit on the last real line; the trailing blank produced by the
        // file's final newline would otherwise split the prop into two blocks.
        while (kept.isNotEmpty() && kept.last().isBlank()) kept.removeAt(kept.lastIndex)
        for ((key, value) in values) {
            if (key in seen || value.isBlank()) continue
            kept.add("$key=$value")
        }
        return kept.joinToString("\n") + "\n"
    }

    /**
     * Posts the ZIP + manifest. Throws with the server's message when the backend rejects it,
     * so the caller can surface the real reason instead of a generic failure.
     */
    suspend fun submit(candidate: ModuleUploadCandidate): String = withContext(Dispatchers.IO) {
        if (ENDPOINT.isBlank()) throw IOException("尚未配置模块提交服务器")
        val boundary = "SXModule" + System.currentTimeMillis()
        val meta = manifestJson(candidate)
        val connection = (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 15_000
            readTimeout = 60_000
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            setRequestProperty("User-Agent", "ShizukuX-ModuleUpload/1.0")
        }
        try {
            connection.outputStream.use { out ->
                writeField(out, boundary, "meta", meta)
                writeFile(out, boundary, "file", candidate.fileName, candidate.localFile)
                out.write("--$boundary--\r\n".toByteArray(Charsets.UTF_8))
                out.flush()
            }
            val code = connection.responseCode
            val body = (if (code in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                throw IOException(body.ifBlank { "服务器返回 HTTP $code" }.take(400))
            }
            val reply = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
            val ok = reply?.get("ok")?.toString()?.toBooleanStrictOrNull()
                ?: (code in 200..299)
            if (!ok) throw IOException(body.ifBlank { "服务器拒绝了这次提交" }.take(400))
            body
        } finally {
            connection.disconnect()
        }
    }

    // -- internals ----------------------------------------------------------

    private fun copyAndHash(input: InputStream, output: OutputStream, digest: MessageDigest): Long {
        val buffer = ByteArray(8192)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            if (read == 0) continue
            total += read
            require(total <= MAX_BYTES) {
                "模块 ZIP 超过上限 ${MAX_BYTES / (1024 * 1024)} MB"
            }
            digest.update(buffer, 0, read)
            output.write(buffer, 0, read)
        }
        return total
    }

    /**
     * Walks the archive applying the installer's structural rules and returns the root
     * `module.prop` entries. Only `module.prop` is read into memory (capped), so a ZIP bomb
     * cannot blow the heap here.
     */
    private fun readModuleProps(file: File): Pair<Map<String, String>, String> {
        var entries = 0
        var propText: String? = null
        ZipInputStream(file.inputStream().buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.isDirectory) {
                    zip.closeEntry()
                    continue
                }
                entries++
                ModuleValidator.checkEntryCount(entries)
                val clean = ModuleValidator.cleanRelativePath(entry.name)
                if (propText == null && (clean == "module.prop" || clean.endsWith("/module.prop"))) {
                    propText = readCapped(zip, ModuleValidator.MAX_PROP_BYTES)
                }
                zip.closeEntry()
            }
        }
        val raw = propText ?: throw IllegalArgumentException("ZIP 里没有 module.prop")
        return ModuleProp.parse(raw) to raw
    }

    private fun readCapped(input: InputStream, maxBytes: Long): String {
        val bytes = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(1024)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            require(total <= maxBytes) { "module.prop 超过 $maxBytes 字节上限" }
            bytes.write(buffer, 0, read)
        }
        return bytes.toString("UTF-8")
    }

    private fun queryDisplayName(context: Context, uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
    }.getOrNull()

    private fun writeField(out: OutputStream, boundary: String, name: String, value: String) {
        out.write(
            ("--$boundary\r\nContent-Disposition: form-data; name=\"$name\"\r\n" +
                "Content-Type: application/json; charset=utf-8\r\n\r\n").toByteArray(Charsets.UTF_8)
        )
        out.write(value.toByteArray(Charsets.UTF_8))
        out.write("\r\n".toByteArray(Charsets.UTF_8))
    }

    private fun writeFile(
        out: OutputStream,
        boundary: String,
        field: String,
        fileName: String,
        file: File
    ) {
        val safeName = fileName.replace("\"", "'").replace("\r", "").replace("\n", "")
        out.write(
            ("--$boundary\r\nContent-Disposition: form-data; name=\"$field\"; " +
                "filename=\"$safeName\"\r\nContent-Type: application/zip\r\n\r\n").toByteArray(Charsets.UTF_8)
        )
        file.inputStream().use { it.copyTo(out) }
        out.write("\r\n".toByteArray(Charsets.UTF_8))
    }
}

@kotlinx.serialization.Serializable
internal data class ModuleSubmitMeta(
    val id: String,
    val name: String,
    val nameZh: String? = null,
    val version: String?,
    val versionCode: Long?,
    val author: String?,
    val description: String?,
    val descriptionZh: String? = null,
    val tags: List<String> = emptyList(),
    val usesShellBridge: Boolean,
    val minSdk: Int?,
    val sizeBytes: Long,
    val sha256: String
)
