package af.shizuku.manager.module

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import moe.shizuku.server.IShizukuService
import rikka.shizuku.Shizuku
import java.io.BufferedInputStream
import java.io.File
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile

/**
 * ADB module manager — install / enable / disable / delete / execute ZIP modules
 * (local variant, ported from Nightzuku's AdbModuleManager, 501 lines).
 *
 * Enforces all 8 safety constraints through [ModuleValidator]:
 *   1 module.prop required + size cap | 2 path-traversal / symlink protection |
 *   3 ZIP-bomb byte cap | 4 entry-count cap | 5 script size cap |
 *   6 execution timeout | 7 output cap | 8 command review (see ModuleCommandReview).
 */
object AdbModuleManager {

    private const val MODULES_DIR = "adb_modules"
    private const val DISABLE_FILE = "disable"

    @Volatile private var servicesStartedForBinder = false
    private val idRegex = ModuleProp.MODULE_ID_REGEX
    private val installMutexes = ConcurrentHashMap<String, Mutex>()

    fun cleanupStagingDirs(context: Context) {
        modulesRoot(context).listFiles { file ->
            file.isDirectory && file.name.startsWith(".") &&
                (file.name.endsWith(".installing") || file.name.endsWith(".backup"))
        }?.forEach { it.deleteRecursively() }
    }

    fun modulesRoot(context: Context): File {
        val filesDir = context.filesDir
        filesDir.parentFile?.apply {
            setReadable(true, false)
            setExecutable(true, false)
        }
        filesDir.apply {
            mkdirs()
            setReadable(true, false)
            setExecutable(true, false)
        }
        return File(filesDir, MODULES_DIR).apply {
            mkdirs()
            setReadable(true, false)
            setExecutable(true, false)
        }
    }

    suspend fun listModules(context: Context): List<AdbModule> = withContext(Dispatchers.IO) {
        modulesRoot(context)
            .listFiles { file -> file.isDirectory && !file.name.startsWith(".") }
            ?.mapNotNull(::readModule)
            ?.sortedWith(compareBy<AdbModule> { !it.enabled }.thenBy { it.name.lowercase(Locale.ROOT) })
            .orEmpty()
    }

    suspend fun inspectModuleHealth(module: AdbModule): ModuleHealthStatus = withContext(Dispatchers.IO) {
        val issues = mutableListOf<String>()
        val dir = module.directory

        if (!dir.isDirectory) {
            issues += "Module directory is missing"
        } else {
            val propFile = dir.resolve("module.prop")
            if (!propFile.isFile || !propFile.canRead()) {
                issues += "Corrupted or unreadable module.prop"
            } else {
                val props = ModuleProp.parseFile(propFile)
                if (props["id"].isNullOrBlank()) {
                    issues += "module.prop missing required id field"
                }
            }
            markScriptsExecutable(dir)
        }

        module.actionScript?.let { script ->
            if (!script.isFile) {
                issues += "Missing action.sh"
            } else if (script.length() == 0L) {
                issues += "Empty action.sh script"
            } else if (script.length() > ModuleValidator.MAX_SCRIPT_BYTES) {
                issues += "action.sh exceeds size limit (${script.length() / 1024} KB)"
            }
        }

        module.serviceScript?.let { script ->
            if (!script.isFile) {
                issues += "Missing service.sh"
            } else if (script.length() == 0L) {
                issues += "Empty service.sh script"
            } else if (script.length() > ModuleValidator.MAX_SCRIPT_BYTES) {
                issues += "service.sh exceeds size limit (${script.length() / 1024} KB)"
            }
        }

        module.webRoot?.let { webDir ->
            val indexHtml = webDir.resolve("index.html").takeIf { it.isFile }
                ?: webDir.resolve("index.htm").takeIf { it.isFile }
            if (indexHtml == null || indexHtml.length() == 0L) {
                issues += "Corrupted WebUI (missing index.html)"
            }
        }

        val lastActionExit = module.lastActionExitCode
        val lastServiceExit = module.lastServiceExitCode

        if (lastServiceExit != null && lastServiceExit != 0) {
            issues += "service.sh exited with code $lastServiceExit"
        }

        ModuleHealthStatus(
            moduleId = module.id,
            healthy = issues.isEmpty(),
            lastActionExitCode = lastActionExit,
            lastServiceExitCode = lastServiceExit,
            issues = issues
        )
    }

    suspend fun install(context: Context, uri: Uri): AdbModule = withContext(Dispatchers.IO) {
        cleanupStagingDirs(context)
        val temp = File.createTempFile("module-", ".zip", context.cacheDir)
        try {
            context.contentResolver.openInputStream(uri).use { input ->
                requireNotNull(input) { "Unable to open module ZIP." }
                temp.outputStream().use { output ->
                    ModuleValidator.copyStreamBounded(input, output, ModuleValidator.MAX_EXTRACTED_BYTES)
                }
            }

            ZipFile(temp).use { zip ->
                val allEntries = zip.entries().asSequence().toList()
                val propEntry = allEntries.firstOrNull {
                    !it.isDirectory && it.name.replace('\\', '/').trim('/') == "module.prop"
                } ?: allEntries
                    .filter { !it.isDirectory && it.name.replace('\\', '/').trim('/').endsWith("/module.prop") }
                    .minByOrNull { it.name.count { ch -> ch == '/' || ch == '\\' } }
                    ?: error("module.prop is missing.")

                val propPath = propEntry.name.replace('\\', '/').trim('/')
                val rootPrefix = if (propPath == "module.prop") {
                    ""
                } else {
                    propPath.removeSuffix("module.prop")
                }

                val propBytes = java.io.ByteArrayOutputStream()
                zip.getInputStream(propEntry).use { propStream ->
                    ModuleValidator.copyStreamBounded(propStream, propBytes, ModuleValidator.MAX_PROP_BYTES)
                }
                val props = ModuleProp.parse(propBytes.toString(Charsets.UTF_8.name()))
                val id = props["id"]?.trim().orEmpty()
                require(idRegex.matches(id)) { "Invalid module id: $id" }

                installMutexes.getOrPut(id) { Mutex() }.withLock {
                    val modulesDir = modulesRoot(context)

                    val target = File(modulesDir, id)
                    val staging = File(modulesDir, ".$id.installing")
                    val backup = File(modulesDir, ".$id.backup")
                    staging.deleteRecursively()
                    backup.deleteRecursively()
                    staging.mkdirs()

                    var entryCount = 0
                    var extractedBytes = 0L
                    allEntries.forEach { entry ->
                        val normalizedName = entry.name.replace('\\', '/').trim('/')
                        if (normalizedName.isBlank()) return@forEach
                        val relativeName = if (rootPrefix.isNotEmpty()) {
                            if (!normalizedName.startsWith(rootPrefix)) return@forEach
                            normalizedName.removePrefix(rootPrefix).trimStart('/')
                        } else {
                            normalizedName
                        }
                        if (relativeName.isBlank()) return@forEach

                        entryCount++
                        ModuleValidator.checkEntryCount(entryCount)
                        val cleanName = ModuleValidator.cleanRelativePath(relativeName)
                        val outFile = File(staging, cleanName)
                        ModuleValidator.ensureInside(staging, outFile)
                        if (entry.isDirectory) {
                            outFile.mkdirs()
                        } else {
                            outFile.parentFile?.mkdirs()
                            val remainingBudget = (ModuleValidator.MAX_EXTRACTED_BYTES - extractedBytes).coerceAtLeast(0L)
                            BufferedInputStream(zip.getInputStream(entry)).use { input ->
                                outFile.outputStream().use { output ->
                                    extractedBytes += ModuleValidator.copyStreamBounded(input, output, remainingBudget)
                                }
                            }
                        }
                    }

                    // Preserve existing .source.prop and disabled state across updates
                    val existingSourceProp = target.resolve(".source.prop")
                    val stagingSourceProp = staging.resolve(".source.prop")
                    if (existingSourceProp.isFile && !stagingSourceProp.exists()) {
                        runCatching { existingSourceProp.copyTo(stagingSourceProp, overwrite = false) }
                    }
                    val existingDisable = target.resolve(DISABLE_FILE)
                    val stagingDisable = staging.resolve(DISABLE_FILE)
                    if (existingDisable.exists() && !stagingDisable.exists()) {
                        runCatching { existingDisable.copyTo(stagingDisable, overwrite = false) }
                    }

                    markScriptsExecutable(staging)
                    // Verify staging is a valid readable module before touching existing target
                    checkNotNull(readModule(staging)) { "Staged module is unreadable." }

                    val hadExistingTarget = target.exists()
                    if (hadExistingTarget) {
                        check(target.renameTo(backup)) { "Unable to backup existing module before update." }
                    }

                    try {
                        if (!staging.renameTo(target)) {
                            error("Unable to move module into storage.")
                        }
                        markScriptsExecutable(target)
                        val installed = readModule(target) ?: error("Installed module is unreadable.")
                        backup.deleteRecursively()
                        // P1-5: module install as a Sentry breadcrumb.
                        try {
                            io.sentry.Sentry.addBreadcrumb(
                                io.sentry.Breadcrumb().apply {
                                    category = "module"
                                    message = "module installed: ${installed.id} v${installed.version ?: "?"}"
                                }
                            )
                        } catch (_: Exception) {}
                        installed
                    } catch (t: Throwable) {
                        target.deleteRecursively()
                        if (hadExistingTarget && backup.exists()) {
                            backup.renameTo(target)
                        }
                        staging.deleteRecursively()
                        throw t
                    }
                }
            }
        } finally {
            temp.delete()
        }
    }

    suspend fun setEnabled(module: AdbModule, enabled: Boolean) = withContext(Dispatchers.IO) {
        val marker = module.directory.resolve(DISABLE_FILE)
        if (enabled) {
            marker.delete()
        } else {
            marker.writeText("disabled\n")
        }
    }

    suspend fun delete(module: AdbModule) = withContext(Dispatchers.IO) {
        ModuleSettings.setModuleTrusted(module.id, false)
        module.directory.deleteRecursively()
    }

    suspend fun runAction(module: AdbModule): ModuleActionResult = withContext(Dispatchers.IO) {
        check(ModuleSettings.canRunAction(module)) { "action.sh is blocked by module access policy." }
        val script = module.actionScript?.takeIf { it.isFile } ?: error("This module has no action.sh.")
        runModuleScript(module, script, module.lastActionLog)
    }

    suspend fun runService(module: AdbModule): ModuleActionResult = withContext(Dispatchers.IO) {
        check(ModuleSettings.canRunService(module)) { "service.sh is blocked by module access policy." }
        check(ModuleSettings.canRunBackground(module)) { "Background actions are disabled." }
        val script = module.serviceScript?.takeIf { it.isFile } ?: error("This module has no service.sh.")
        runModuleScript(module, script, module.lastServiceLog)
    }

    suspend fun runEnabledServicesIfAllowed(
        context: Context,
        force: Boolean = false
    ): List<Pair<AdbModule, ModuleActionResult>> = withContext(Dispatchers.IO) {
        if (!force && servicesStartedForBinder) return@withContext emptyList()
        if (!Shizuku.pingBinder()) return@withContext emptyList()

        servicesStartedForBinder = true
        listModules(context)
            .filter { it.enabled && it.hasService && ModuleSettings.canRunService(it) && ModuleSettings.canRunBackground(it) }
            .mapNotNull { module ->
                runCatching { module to runService(module) }.getOrNull()
            }
    }

    fun resetServiceRunGuard() {
        servicesStartedForBinder = false
    }

    private fun runModuleScript(module: AdbModule, script: File, logFile: File): ModuleActionResult {
        check(module.enabled) { "Module is disabled." }
        module.logsDir.mkdirs()

        if (script.length() > ModuleValidator.MAX_SCRIPT_BYTES) {
            return ModuleActionResult(
                exitCode = -1,
                stdout = "",
                stderr = "Script too large: ${script.length()} bytes (max ${ModuleValidator.MAX_SCRIPT_BYTES})"
            )
        }

        val scriptContent = try {
            script.readText(Charsets.UTF_8)
        } catch (e: Exception) {
            return ModuleActionResult(exitCode = -1, stdout = "", stderr = "Cannot read script: ${e.message}")
        }

        val binder = Shizuku.getBinder() ?: error("Shizuku service is not running.")
        val service = IShizukuService.Stub.asInterface(binder)
        val env = ModuleRuntimeUtils.buildModuleEnvironment(module, module.directory)
        val wrappedScript = "cd \"\$MODDIR\" 2>/dev/null || true\n$scriptContent"
        val remote = service.newProcess(
            arrayOf("sh", "-c", wrappedScript),
            env,
            "/data/local/tmp"
        )

        runCatching { ParcelFileDescriptor.AutoCloseOutputStream(remote.getOutputStream()).close() }
        val stdoutPfd = remote.getInputStream()
        val stderrPfd = remote.getErrorStream()
        var stdout = ""
        var stderr = ""
        val stdoutThread = Thread {
            try {
                stdout = ModuleRuntimeUtils.readStreamTail(stdoutPfd, ModuleValidator.MAX_OUTPUT_CHARS)
            } catch (_: Exception) {
            }
        }
        val stderrThread = Thread {
            try {
                stderr = ModuleRuntimeUtils.readStreamTail(stderrPfd, ModuleValidator.MAX_OUTPUT_CHARS)
            } catch (_: Exception) {
            }
        }
        stdoutThread.start()
        stderrThread.start()
        val finished = remote.waitForTimeout(ModuleValidator.MAX_SCRIPT_SECONDS, TimeUnit.SECONDS.name)
        val exitCode = if (finished) {
            remote.exitValue()
        } else {
            remote.destroy()
            runCatching { stdoutPfd.close() }
            runCatching { stderrPfd.close() }
            124
        }
        stdoutThread.join(500)
        if (stdoutThread.isAlive) {
            runCatching { stdoutPfd.close() }
            stdoutThread.join(250)
        }
        stderrThread.join(500)
        if (stderrThread.isAlive) {
            runCatching { stderrPfd.close() }
            stderrThread.join(250)
        }
        val result = ModuleActionResult(
            exitCode = exitCode,
            stdout = stdout,
            stderr = stderr
        )
        writeLastLog(logFile, module, script, result, finished)
        // P1-5: every module script run as a Sentry breadcrumb (roadmap P1-5 #3).
        try {
            io.sentry.Sentry.addBreadcrumb(
                io.sentry.Breadcrumb().apply {
                    category = "module"
                    message = "module script run: ${module.id} ${script.name} exit=$exitCode finished=$finished"
                }
            )
        } catch (_: Exception) {}
        return result
    }

    fun readModule(directory: File): AdbModule? {
        val propsFile = directory.resolve("module.prop")
        if (!propsFile.isFile) return null
        val props = ModuleProp.parseFile(propsFile)
        val id = props["id"]?.takeIf { idRegex.matches(it) } ?: return null
        val name = props["name"]?.takeIf { it.isNotBlank() } ?: id
        val resolvedWebRoot = findFirstExistingDir(
            directory,
            props["webui"],
            "webroot",
            "webui",
            "web"
        )
        val resolvedAction = findFirstExistingFile(directory, props["action"], "action.sh", "common/action.sh")
        val resolvedService = findFirstExistingFile(directory, props["service"], "service.sh", "common/service.sh")
        return AdbModule(
            id = id,
            name = name,
            version = props["version"]?.takeIf { it.isNotBlank() },
            versionCode = props["versionCode"]?.toLongOrNull(),
            author = props["author"]?.takeIf { it.isNotBlank() },
            description = props["description"]?.takeIf { it.isNotBlank() },
            directory = directory,
            banner = findFirstExistingFile(
                directory,
                props["banner"],
                "banner.png",
                "banner.jpg",
                "banner.jpeg",
                "banner.webp"
            ),
            webRoot = resolvedWebRoot,
            declaresShellBridge = props["usesShellBridge"]?.toBooleanStrictOrNull()
                ?: props["shellBridge"]?.toBooleanStrictOrNull()
                ?: false,
            actionScript = resolvedAction,
            serviceScript = resolvedService,
            logsDir = directory.resolve("logs"),
            sizeBytes = directory.walkTopDown()
                .filter { it.isFile }
                .sumOf { it.length() },
            enabled = !directory.resolve(DISABLE_FILE).exists()
        )
    }

    private fun markScriptsExecutable(directory: File) {
        directory.walkTopDown().forEach { file ->
            file.setReadable(true, false)
            if (file.isDirectory || file.extension.equals("sh", ignoreCase = true)) {
                file.setExecutable(true, false)
            }
        }
    }

    private fun writeLastLog(
        logFile: File,
        module: AdbModule,
        script: File,
        result: ModuleActionResult,
        finished: Boolean
    ) {
        logFile.parentFile?.mkdirs()
        logFile.writeText(
            buildString {
                appendLine("module=${module.id}")
                appendLine("script=${script.name}")
                appendLine("finished=$finished")
                appendLine("exitCode=${result.exitCode}")
                appendLine("mode=${ModuleSettings.getAccessMode().value}")
                appendLine("trusted=${ModuleSettings.isModuleTrusted(module.id)}")
                appendLine()
                appendLine("[stdout]")
                appendLine(result.stdout.trim())
                appendLine()
                appendLine("[stderr]")
                appendLine(result.stderr.trim())
            }
        )
    }

    private fun findFirstExistingFile(directory: File, vararg paths: String?): File? {
        return paths.asSequence()
            .filterNotNull()
            .map { it.replace('\\', '/').trim().trim('/') }
            .filter { it.isNotBlank() }
            .mapNotNull { rel ->
                val candidate = directory.resolve(rel)
                runCatching {
                    ModuleValidator.ensureInside(directory, candidate)
                    candidate.takeIf { it.isFile }
                }.getOrNull()
            }
            .firstOrNull()
    }

    private fun findFirstExistingDir(directory: File, vararg paths: String?): File? {
        return paths.asSequence()
            .filterNotNull()
            .map { it.replace('\\', '/').trim().trim('/') }
            .filter { it.isNotBlank() }
            .mapNotNull { rel ->
                val candidate = directory.resolve(rel)
                runCatching {
                    ModuleValidator.ensureInside(directory, candidate)
                    candidate.takeIf { it.isDirectory }
                }.getOrNull()
            }
            .firstOrNull()
    }
}
