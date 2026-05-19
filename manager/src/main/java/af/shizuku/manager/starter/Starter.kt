package af.shizuku.manager.starter

import android.content.Context
import androidx.lifecycle.asFlow
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import af.shizuku.manager.R
import af.shizuku.manager.utils.ShizukuStateMachine

/**
 * Starter object for launching Shizuku service
 * Uses appContext from ShizukuApplication
 */
object Starter {

    private var context: Context? = null
    
    private fun getContext(): Context {
        return context ?: throw IllegalStateException("Context not initialized")
    }
    
    fun initialize(context: Context) {
        this.context = context.applicationContext
    }

    private val starterFile: File
        get() = File(getContext().applicationInfo.nativeLibraryDir, "libshizuku.so")

    val userCommand: String
        get() = starterFile.absolutePath

    val adbCommand: String
        get() = "adb shell $userCommand"

    val internalCommand: String
        get() = "$userCommand --apk=${getContext().applicationInfo.sourceDir}"

    val serviceStartedMessage: String
        get() = getContext().getString(R.string.starter_service_started)

    suspend fun waitForBinder(log: ((String) -> Unit)? = null) {
        if (ShizukuStateMachine.isRunning()) {
            log?.invoke(serviceStartedMessage)
            return
        }
        log?.invoke("\n" + getContext().getString(R.string.starter_waiting))
        val t0 = System.currentTimeMillis()
        
        // Use withTimeout to prevent infinite hanging and trigger the error UI in StarterActivity
        withTimeout(20_000) {
            ShizukuStateMachine.asFlow()
                .first { it == ShizukuStateMachine.State.RUNNING }
        }
        
        val elapsed = (System.currentTimeMillis() - t0) / 1000.0
        log?.invoke("Connected in ${String.format("%.1f", elapsed)}s")
        log?.invoke(serviceStartedMessage)
    }
}
