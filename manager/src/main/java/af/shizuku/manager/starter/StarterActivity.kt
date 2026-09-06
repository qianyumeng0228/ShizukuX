package af.shizuku.manager.starter

import android.Manifest.permission.WRITE_SECURE_SETTINGS
import android.annotation.SuppressLint
import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.viewModels
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.topjohnwu.superuser.CallbackList
import com.topjohnwu.superuser.Shell
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SSLProtocolException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import af.shizuku.manager.AppConstants.EXTRA
import af.shizuku.manager.R
import af.shizuku.manager.ShizukuSettings
import af.shizuku.manager.adb.AdbClient
import af.shizuku.manager.adb.AdbKey
import af.shizuku.manager.adb.AdbKeyException
import af.shizuku.manager.adb.AdbMdns
import af.shizuku.manager.adb.AdbPairingService
import af.shizuku.manager.adb.AdbStarter
import af.shizuku.manager.adb.PreferenceAdbKeyStore
import af.shizuku.manager.database.ActivityLogManager
import af.shizuku.manager.utils.EnvironmentUtils
import af.shizuku.manager.utils.HapticUtils
import af.shizuku.manager.utils.IconStyleHelper
import af.shizuku.manager.utils.SettingsPage
import af.shizuku.manager.utils.ShizukuStateMachine
import af.shizuku.core.ui.AppBarActivity
import af.shizuku.manager.databinding.StarterActivityBinding
import timber.log.Timber

private class NotRootedException : Exception()

enum class StepStatus { PENDING, RUNNING, COMPLETED, ERROR, WARNING }

data class StarterStep(
    val id: String,
    val titleRes: Int,
    val status: StepStatus = StepStatus.PENDING,
    val description: String = "",
    val needsUserAction: Boolean = false
)

class StarterActivity : AppBarActivity() {

    companion object {
        const val EXTRA_IS_SYSTEM = "$EXTRA.IS_SYSTEM"
        const val EXTRA_IS_ROOT = "$EXTRA.IS_ROOT"
        const val EXTRA_PORT = "$EXTRA.PORT"

        /** True while a StarterActivity exists; AdbPairingService uses it to avoid double-launch. */
        @Volatile
        var isActive: Boolean = false
            private set
    }

    private val viewModel: ViewModel by viewModels()
    private lateinit var binding: StarterActivityBinding
    private var logVisible = false
    private var startPending = false

    private val pairingReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == AdbPairingService.ACTION_PAIRING_SUCCEEDED) {
                val port = intent.getIntExtra(AdbPairingService.EXTRA_PORT, 0)
                Timber.tag("StarterActivity").i("Pairing succeeded broadcast, port=%d", port)
                viewModel.onPairingSucceeded(port)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        isActive = true

        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setHomeAsUpIndicator(R.drawable.ic_close_24)

        binding = StarterActivityBinding.inflate(layoutInflater, rootView, true)

        val isRoot = intent.getBooleanExtra(EXTRA_IS_ROOT, false)
        if (!isRoot) af.shizuku.manager.adb.LocalNetworkPermission.request(this)

        binding.header.apply {
            headerIcon.setImageResource(if (isRoot) R.drawable.ic_root_24 else R.drawable.ic_adb_24)
            IconStyleHelper.applyToCardIcon(
                headerIcon, headerIcon.drawable, if (isRoot) "home_start_root" else "home_start_adb"
            )
            headerIcon.transitionName = "icon_wireless_adb"
            headerTitle.setText(if (isRoot) R.string.home_root_title else R.string.home_adb_title)
        }

        binding.cancelButton.setOnClickListener {
            when {
                viewModel.completed.value == true -> finish()
                viewModel.hasError() -> viewModel.retry()
                else -> {
                    viewModel.cancel()
                    finish()
                }
            }
        }

        binding.logButton.setOnClickListener {
            logVisible = !logVisible
            binding.text1.isVisible = logVisible
            binding.logButton.setText(if (logVisible) R.string.starter_btn_hide_log else R.string.starter_btn_open_log)
            if (logVisible) binding.scrollView.post {
                binding.scrollView.scrollTo(0, binding.scrollView.bottom)
            }
        }

        // Pairing success is delivered via broadcast (the service may outlive this activity).
        val filter = IntentFilter(AdbPairingService.ACTION_PAIRING_SUCCEEDED)
        ContextCompat.registerReceiver(
            this, pairingReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED
        )

        viewModel.steps.observe(this) { renderSteps(it) }
        viewModel.output.observe(this) { sb ->
            binding.text1.text = sb.toString()
            if (logVisible) binding.scrollView.post {
                binding.scrollView.scrollTo(0, binding.scrollView.bottom)
            }
            if (sb.toString().contains(getString(R.string.starter_service_started))) {
                HapticUtils.success(binding.root)
            }
        }
        viewModel.completed.observe(this) { done ->
            if (done == true) {
                binding.progressIndicator.isGone = true
                binding.cancelButton.setText(R.string.starter_done)
                binding.cancelButton.setOnClickListener { finish() }
                // Auto close after a short pause so the user can see the green checkmark.
                binding.root.postDelayed({ if (!isFinishing) finish() }, 2500)
            }
        }
        viewModel.errorEvent.observe(this) { error ->
            if (error == null || isFinishing) return@observe
            binding.progressIndicator.isGone = true
            binding.logButton.isVisible = viewModel.hasLog()
            var message = 0
            when (error) {
                is AdbKeyException -> message = R.string.adb_error_key_store
                is NotRootedException -> message = R.string.start_with_root_failed
                is SocketTimeoutException -> message = R.string.cannot_connect_port
                is ConnectException -> message = R.string.cannot_connect_port
                is SSLProtocolException -> message = R.string.adb_pair_required
                is TimeoutException -> message = R.string.adb_error_timeout
            }
            val dialogMessage = if (message != 0) message else R.string.adb_error_generic
            MaterialAlertDialogBuilder(this)
                .setMessage(dialogMessage)
                .setPositiveButton(android.R.string.ok) { _, _ -> finish() }
                .setNegativeButton(R.string.starter_retry) { _, _ ->
                    binding.progressIndicator.isVisible = true
                    viewModel.retry()
                }
                .show()
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus || viewModel.started) return
        // Android 16+ gates mDNS discovery and the ADB sockets behind a local-network
        // permission. The request (fired in onCreate for non-root flows) shows a separate
        // window, so a start here would run before the user answers. Wait for
        // onRequestPermissionsResult instead of starting without the permission. Root and
        // Samsung-system flows don't need it.
        val isRoot = intent.getBooleanExtra(EXTRA_IS_ROOT, false)
        val isSystem = intent.getBooleanExtra(EXTRA_IS_SYSTEM, false)
        if (!isRoot && !isSystem && !af.shizuku.manager.adb.LocalNetworkPermission.granted(this)) {
            startPending = true
            return
        }
        doStart()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != af.shizuku.manager.adb.LocalNetworkPermission.REQUEST_CODE || !startPending) return
        startPending = false
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            doStart()
        } else {
            // Without the local-network permission neither mDNS discovery nor the ADB
            // pairing/connect sockets work — the flow cannot proceed.
            MaterialAlertDialogBuilder(this)
                .setMessage(R.string.starter_local_network_permission_needed)
                .setPositiveButton(android.R.string.ok) { _, _ -> finish() }
                .show()
        }
    }

    private fun doStart() {
        val port = intent.getIntExtra(EXTRA_PORT, 0)
        viewModel.start(
            intent.getBooleanExtra(EXTRA_IS_ROOT, false),
            intent.getBooleanExtra(EXTRA_IS_SYSTEM, false),
            port
        )
    }

    override fun onDestroy() {
        super.onDestroy()
        isActive = false
        try {
            unregisterReceiver(pairingReceiver)
        } catch (_: IllegalArgumentException) {
        }
    }

    private fun renderSteps(steps: List<StarterStep>) {
        val container = binding.stepsContainer
        container.removeAllViews()
        steps.forEachIndexed { index, step ->
            val item = layoutInflater.inflate(R.layout.item_starter_step, container, false)
            val title: TextView = item.findViewById(R.id.stepTitle)
            title.text = getString(step.titleRes)
            val desc: TextView = item.findViewById(R.id.stepDescription)
            desc.isGone = step.description.isEmpty()
            desc.text = step.description

            val circle: View = item.findViewById(R.id.statusCircle)
            val icon: android.widget.ImageView = item.findViewById(R.id.statusIcon)
            val progress: android.widget.ProgressBar = item.findViewById(R.id.statusProgress)
            val connector: View = item.findViewById(R.id.connectorLine)

            when (step.status) {
                StepStatus.PENDING -> {
                    circle.setBackgroundResource(R.drawable.starter_step_circle_pending)
                    icon.isGone = true
                    progress.isGone = true
                }
                StepStatus.RUNNING -> {
                    circle.setBackgroundResource(R.drawable.starter_step_circle_running)
                    icon.isGone = true
                    progress.isVisible = true
                }
                StepStatus.COMPLETED -> {
                    circle.setBackgroundResource(R.drawable.starter_step_circle_completed)
                    icon.isVisible = true
                    icon.setImageResource(R.drawable.ic_check_24)
                    icon.setColorFilter(Color.WHITE)
                    progress.isGone = true
                }
                StepStatus.ERROR -> {
                    circle.setBackgroundResource(R.drawable.starter_step_circle_error)
                    icon.isVisible = true
                    icon.setImageResource(R.drawable.ic_close_24)
                    icon.setColorFilter(Color.WHITE)
                    progress.isGone = true
                }
                StepStatus.WARNING -> {
                    circle.setBackgroundResource(R.drawable.starter_step_circle_pending)
                    icon.isVisible = true
                    icon.setImageResource(R.drawable.ic_warning_24)
                    icon.setColorFilter(Color.WHITE)
                    progress.isGone = true
                }
            }
            connector.visibility = if (index == steps.lastIndex) View.GONE else View.VISIBLE

            if (step.needsUserAction && step.status == StepStatus.RUNNING) {
                renderActionButtons(item, step)
            }
            container.addView(item)
        }
        binding.scrollView.post {
            binding.scrollView.scrollTo(0, binding.scrollView.bottom)
        }
    }

    private fun renderActionButtons(item: View, step: StarterStep) {
        val container = item.findViewById<LinearLayout>(R.id.stepActionContainer)
        container.isVisible = true

        fun addButton(textRes: Int, onClick: () -> Unit) {
            val button = MaterialButton(this)
            button.setText(textRes)
            button.isAllCaps = false
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.topMargin = (8 * resources.displayMetrics.density).toInt()
            button.layoutParams = lp
            button.setOnClickListener { onClick() }
            container.addView(button)
        }

        when (step.id) {
            "enable_wireless" -> {
                addButton(R.string.starter_step_enable_wireless_btn) {
                    openWirelessDebuggingSettings()
                }
                addButton(R.string.starter_step_enable_wireless_done) {
                    viewModel.continueAfterSetup()
                }
            }
            "pairing" -> {
                addButton(R.string.starter_step_enable_wireless_btn) {
                    openWirelessDebuggingSettings()
                }
            }
        }
    }

    private fun openWirelessDebuggingSettings() {
        try {
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    putExtra(":settings:fragment_args_key", "toggle_adb_wireless")
                }
            )
        } catch (_: Exception) {
            SettingsPage.Developer.Options.launch(this)
        }
    }
}

class ViewModel(application: Application) : AndroidViewModel(application) {

    private val appContext = getApplication<Application>().applicationContext

    private val _steps = MutableLiveData<List<StarterStep>>()
    val steps: LiveData<List<StarterStep>> = _steps

    private val _output = MutableLiveData<StringBuilder>()
    val output: LiveData<StringBuilder> = _output

    private val _completed = MutableLiveData<Boolean>(false)
    val completed: LiveData<Boolean> = _completed

    private val _errorEvent = MutableLiveData<Throwable?>()
    val errorEvent: LiveData<Throwable?> = _errorEvent

    var started = false
        private set

    private var flowJob: Job? = null
    private var waitingForPairing = false
    private var waitingForWireless = false

    private fun setSteps(steps: List<StarterStep>) {
        _steps.value = steps
    }

    private fun updateStep(id: String, status: StepStatus, description: String) {
        val list = _steps.value?.toMutableList() ?: return
        val index = list.indexOfFirst { it.id == id }
        if (index >= 0) {
            list[index] = list[index].copy(status = status, description = description)
            _steps.value = list
        }
    }

    private fun insertStep(index: Int, step: StarterStep) {
        val list = _steps.value?.toMutableList() ?: return
        val safe = index.coerceIn(0, list.size)
        list.add(safe, step)
        _steps.value = list
    }

    /** Accumulated log; the live value is the same StringBuilder instance so a postValue
     *  from any thread never loses content (each observer read is a full snapshot). */
    private val sb = StringBuilder()

    /** Thread-safe log sink: startAdb/waitForBinder/root callbacks run on IO threads. */
    private fun log(line: String) {
        sb.appendLine(line)
        _output.postValue(sb)
    }

    private fun setError(error: Throwable) {
        _errorEvent.value = error
        // Mark any RUNNING step as ERROR so the timeline shows where it failed.
        val list = _steps.value?.toMutableList() ?: return
        var changed = false
        for (i in list.indices) {
            if (list[i].status == StepStatus.RUNNING) {
                list[i] = list[i].copy(
                    status = StepStatus.ERROR,
                    description = error.message ?: ""
                )
                changed = true
            }
        }
        if (changed) _steps.value = list
    }

    fun hasError(): Boolean = _errorEvent.value != null

    fun hasLog(): Boolean = sb.isNotEmpty()

    fun start(root: Boolean, isSystem: Boolean, port: Int) {
        if (started) return
        started = true
        flowJob?.cancel()
        flowJob = viewModelScope.launch {
            if (root) runRootFlow()
            else if (isSystem) runSystemFlow()
            else runAdbFlow(port)
        }
    }

    fun retry() {
        _errorEvent.value = null
        _completed.value = false
        started = true
        flowJob?.cancel()
        flowJob = viewModelScope.launch {
            val last = lastStart ?: return@launch
            if (last.first) runRootFlow()
            else if (last.second) runSystemFlow()
            else runAdbFlow(last.third)
        }
    }

    private var lastStart: Triple<Boolean, Boolean, Int>? = null

    fun cancel() {
        flowJob?.cancel()
    }

    /** Called when the pairing service reports success (broadcast). */
    fun onPairingSucceeded(port: Int) {
        if (!waitingForPairing) return
        waitingForPairing = false
        updateStep("pairing", StepStatus.COMPLETED, appContext.getString(R.string.starter_step_pairing_done))
        if (port in 1..65535) {
            viewModelScope.launch { startServiceWithPort(port) }
        }
    }

    /** Called when the user finishes enabling wireless debugging manually. */
    fun continueAfterSetup() {
        if (!waitingForWireless) return
        waitingForWireless = false
        // Reset to port detection and retry the whole ADB flow.
        started = true
        flowJob?.cancel()
        flowJob = viewModelScope.launch { runAdbFlow(null) }
    }

    // ---------------------------------------------------------------- ADB flow

    @SuppressLint("MissingPermission")
    private suspend fun runAdbFlow(intentPort: Int?) {
        clearStaleStartingState()
        waitingForPairing = false
        waitingForWireless = false
        lastStart = Triple(false, false, intentPort ?: 0)
        setSteps(
            listOf(
                StarterStep("detect_port", R.string.starter_step_detect_port),
                StarterStep("detect_pairing", R.string.starter_step_detect_pairing),
                StarterStep("start_service", R.string.starter_step_start_service),
                StarterStep("wait_binder", R.string.starter_step_wait_binder),
                StarterStep("complete", R.string.starter_step_complete)
            )
        )

        // Step 1: detect a usable ADB port.
        updateStep("detect_port", StepStatus.RUNNING, appContext.getString(R.string.starter_step_detect_port_running))
        val detection = detectPort(intentPort)
        if (detection == null) {
            // No wireless-debugging port at all: ask the user to enable it.
            updateStep("detect_port", StepStatus.WARNING, appContext.getString(R.string.starter_step_no_port))
            waitingForWireless = true
            insertStep(0, StarterStep(
                "enable_wireless",
                R.string.starter_step_enable_wireless,
                StepStatus.RUNNING,
                appContext.getString(R.string.starter_step_enable_wireless_hint),
                needsUserAction = true
            ))
            tryAutoEnableWirelessDebugging()
            return
        }
        val (port, paired) = detection
        updateStep("detect_port", StepStatus.COMPLETED, appContext.getString(R.string.starter_step_port_found, port))

        // Step 2: check pairing.
        updateStep("detect_pairing", StepStatus.RUNNING, appContext.getString(R.string.starter_step_detect_pairing_running))
        if (!paired) {
            updateStep("detect_pairing", StepStatus.COMPLETED, appContext.getString(R.string.starter_step_need_pairing))
            waitingForPairing = true
            insertStep(2, StarterStep(
                "pairing",
                R.string.starter_step_pairing,
                StepStatus.RUNNING,
                appContext.getString(R.string.starter_step_pairing_hint),
                needsUserAction = true
            ))
            startPairingService()
            return
        }
        updateStep("detect_pairing", StepStatus.COMPLETED, appContext.getString(R.string.starter_step_paired))
        startServiceWithPort(port)
    }

    /**
     * A failed start can leave the state machine stuck in STARTING forever (update() keeps
     * STARTING sticky and failure paths never reset it). That deadlocks every later "start"
     * attempt that checks the state. If the binder isn't actually alive, settle back to
     * STOPPED so a fresh attempt can proceed.
     */
    private suspend fun clearStaleStartingState() {
        if (ShizukuStateMachine.get() != ShizukuStateMachine.State.STARTING) return
        val alive = withContext(Dispatchers.IO) {
            try {
                rikka.shizuku.Shizuku.pingBinder()
            } catch (e: Exception) {
                false
            }
        }
        if (!alive) {
            ShizukuStateMachine.set(ShizukuStateMachine.State.STOPPED)
        }
    }

    /** Auto-enables wireless debugging when we hold WRITE_SECURE_SETTINGS (post-grant state). */
    private suspend fun tryAutoEnableWirelessDebugging() {
        val context = appContext
        if (context.checkSelfPermission(WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED && isWifiConnected()) {
            runCatching {
                val cr = context.contentResolver
                Settings.Global.putInt(cr, Settings.Global.ADB_ENABLED, 1)
                Settings.Global.putLong(cr, "adb_allowed_connection_time", 0L)
                Settings.Global.putInt(cr, "adb_wifi_enabled", 1)
            }
            updateStep("enable_wireless", StepStatus.COMPLETED, appContext.getString(R.string.starter_step_enable_wireless_auto))
            delay(1200)
            continueAfterSetup()
        }
    }

    private fun isWifiConnected(): Boolean {
        val cm = appContext.getSystemService(ConnectivityManager::class.java) ?: return false
        return cm.allNetworks.any { network ->
            cm.getNetworkCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        }
    }

    /**
     * Returns (port, alreadyPaired) or null when no wireless-debugging port is reachable.
     * Order: explicit intent port → system property port → last used port → mDNS discovery.
     */
    private suspend fun detectPort(intentPort: Int?): Pair<Int, Boolean>? {
        val p = intentPort
        if (p != null && p in 1..65535) {
            return Pair(p, canConnect(p))
        }
        val sysPropPort = EnvironmentUtils.getAdbTcpPort()
        val lastPort = ShizukuSettings.getLastPort()
        for (p in listOf(sysPropPort, lastPort).distinct()) {
            if (p in 1..65535) {
                if (canConnect(p)) return Pair(p, true)
            }
        }
        // Wireless debugging may still be on with a random TLS port — discover it via mDNS.
        val discovered: Int = discoverAdbPort() ?: 0
        if (discovered in 1..65535) {
            return Pair(discovered, canConnect(discovered))
        }
        return null
    }

    private suspend fun canConnect(port: Int): Boolean = withContext(Dispatchers.IO) {
        try {
            val key = AdbKey(PreferenceAdbKeyStore(ShizukuSettings.getPreferences()), "shizukux")
            AdbClient("127.0.0.1", port, key).use { it.connect() }
            true
        } catch (e: Exception) {
            false
        }
    }

    private suspend fun discoverAdbPort(): Int? {
        // AdbMdns.start() can throw (e.g. SecurityException when local-network permission
        // was revoked after the flow started) — degrade to "no port" instead of crashing.
        return try {
            withTimeoutOrNull(10000) {
                suspendCancellableCoroutine { cont ->
                    val done = AtomicBoolean(false)
                    var mdns: AdbMdns? = null
                    mdns = AdbMdns(appContext, AdbMdns.TLS_CONNECT) { port ->
                        if (port in 1..65535 && done.compareAndSet(false, true)) {
                            mdns?.stop()
                            // The coroutine may have been cancelled (user closed the screen);
                            // resume on a cancelled continuation throws — swallow it.
                            runCatching { cont.resume(port) }
                        }
                    }
                    mdns?.start()
                    cont.invokeOnCancellation {
                        if (done.compareAndSet(false, true)) mdns?.stop()
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Timber.tag("StarterActivity").w(e, "mDNS discovery failed")
            null
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun startPairingService() {
        val intent = AdbPairingService.startIntent(appContext)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                appContext.startForegroundService(intent)
            } else {
                appContext.startService(intent)
            }
            updateStep("pairing", StepStatus.RUNNING, appContext.getString(R.string.starter_waiting_pairing))
        } catch (e: Throwable) {
            Timber.e("startPairingService", e)
            setError(e)
        }
    }

    private suspend fun startServiceWithPort(port: Int) {
        if (port !in 1..65535) {
            setError(IllegalArgumentException("Invalid port: $port"))
            return
        }
        updateStep("start_service", StepStatus.RUNNING, appContext.getString(R.string.starter_step_connecting))
        try {
            AdbStarter.startAdb(appContext, port) { log(it) }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            updateStep("start_service", StepStatus.ERROR, e.message ?: "")
            setError(e)
            return
        }
        updateStep("start_service", StepStatus.COMPLETED, appContext.getString(R.string.starter_step_start_service_done))

        updateStep("wait_binder", StepStatus.RUNNING, appContext.getString(R.string.starter_step_wait_binder_running))
        try {
            Starter.waitForBinder { log(it) }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            updateStep("wait_binder", StepStatus.ERROR, e.message ?: "")
            setError(e)
            return
        }
        updateStep("wait_binder", StepStatus.COMPLETED, "")
        markCompleted()
    }

    private fun markCompleted() {
        val list = _steps.value?.toMutableList() ?: return
        for (i in list.indices) {
            if (list[i].status != StepStatus.COMPLETED && list[i].status != StepStatus.WARNING) {
                list[i] = list[i].copy(
                    status = StepStatus.COMPLETED,
                    description = if (list[i].id == "complete") {
                        appContext.getString(R.string.starter_step_complete_desc)
                    } else {
                        ""
                    }
                )
            }
        }
        _steps.value = list
        _completed.value = true
    }

    // ---------------------------------------------------------------- Root flow

    private suspend fun runRootFlow() {
        clearStaleStartingState()
        waitingForPairing = false
        waitingForWireless = false
        lastStart = Triple(true, false, 0)
        setSteps(
            listOf(
                StarterStep("check_root", R.string.starter_step_check_root),
                StarterStep("start_root", R.string.starter_step_start_root),
                StarterStep("wait_binder", R.string.starter_step_wait_binder),
                StarterStep("complete", R.string.starter_step_complete)
            )
        )

        updateStep("check_root", StepStatus.RUNNING, appContext.getString(R.string.starter_step_check_root_running))
        val rooted = withContext(Dispatchers.IO) {
            if (Shell.getShell().isRoot) true
            else {
                Shell.getCachedShell()?.close()
                Shell.getShell().isRoot
            }
        }
        if (!rooted) {
            updateStep("check_root", StepStatus.ERROR, "")
            setError(NotRootedException())
            return
        }
        updateStep("check_root", StepStatus.COMPLETED, appContext.getString(R.string.starter_step_check_root_done))

        updateStep("start_root", StepStatus.RUNNING, appContext.getString(R.string.starter_step_start_root_running))
        try {
            startRoot()
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            updateStep("start_root", StepStatus.ERROR, e.message ?: "")
            setError(e)
            return
        }
        updateStep("start_root", StepStatus.COMPLETED, appContext.getString(R.string.starter_step_start_root_done))

        updateStep("wait_binder", StepStatus.RUNNING, appContext.getString(R.string.starter_step_wait_binder_running))
        try {
            Starter.waitForBinder { log(it) }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            updateStep("wait_binder", StepStatus.ERROR, e.message ?: "")
            setError(e)
            return
        }
        updateStep("wait_binder", StepStatus.COMPLETED, "")
        markCompleted()
    }

    private suspend fun startRoot() {
        withContext(Dispatchers.IO) {
            val customSu = ShizukuSettings.getCustomRootSuPath()
            if (customSu.isNotBlank()) {
                startRootWithCustomSu(customSu)
            } else {
                startRootWithLibsu()
            }
        }
    }

    private suspend fun startRootWithLibsu() {
        if (!Shell.getShell().isRoot) {
            Shell.getCachedShell()?.close()
            if (!Shell.getShell().isRoot) {
                Shell.getCachedShell()?.close()
                throw NotRootedException()
            }
        }
        ShizukuStateMachine.set(ShizukuStateMachine.State.STARTING)
        suspendCancellableCoroutine { cont ->
            Shell.cmd(Starter.internalCommand)
                .to(object : CallbackList<String?>() {
                    override fun onAddElement(s: String?) { s?.let { log(it) } }
                })
                .submit {
                    if (cont.isActive) {
                        if (it.isSuccess) {
                            ShizukuStateMachine.update()
                            ActivityLogManager.log("Shizuku", appContext.packageName, "Service started via root")
                            cont.resume(Unit)
                        } else {
                            cont.resumeWithException(Exception("Failed to start with root"))
                        }
                    }
                }
        }
    }

    private suspend fun startRootWithCustomSu(suPath: String) {
        val suFile = java.io.File(suPath)
        if (!suFile.exists()) {
            log("Custom SU path does not exist: $suPath\n")
            throw NotRootedException()
        }
        if (!suFile.canExecute()) {
            log("Custom SU path is not executable, attempting chmod +x...\n")
            suFile.setExecutable(true, false)
        }
        ShizukuStateMachine.set(ShizukuStateMachine.State.STARTING)

        val strategies = listOf(
            Triple("su -c [command]", { cmd: String -> arrayOf(suPath, "-c", cmd) }, true),
            Triple("su [command] (no -c)", { cmd: String -> arrayOf(suPath, cmd) }, false),
            Triple("sh -c [su -c command]", { cmd: String ->
                arrayOf("/system/bin/sh", "-c", "$suPath -c '$cmd'")
            }, true),
            Triple("su -c command (raw)", { cmd: String -> arrayOf(suPath, "-c", Starter.internalCommand) }, true)
        )

        var lastError: Exception? = null
        for ((strategyName, cmdBuilder, _) in strategies) {
            log("\n--- Trying SU strategy: $strategyName ---\n")
            try {
                val cmdArray = cmdBuilder(Starter.internalCommand)
                val process = ProcessBuilder(*cmdArray)
                    .redirectErrorStream(true)
                    .start()

                val output = StringBuilder()
                val reader = java.io.BufferedReader(java.io.InputStreamReader(process.inputStream))
                val drain = Thread({
                    reader.forEachLine { line ->
                        output.appendLine(line)
                        log("  $line")
                    }
                }, "root-su-output")
                drain.isDaemon = true
                drain.start()

                val finished = process.waitFor(30, java.util.concurrent.TimeUnit.SECONDS)
                if (!finished) {
                    process.destroy()
                    lastError = Exception("Timed out starting with root (strategy: $strategyName, su: $suPath)")
                    continue
                }

                val exitCode = process.exitValue()
                log("Strategy exit code: $exitCode\n")
                if (exitCode == 0) {
                    log("\n=== SU strategy succeeded: $strategyName ===\n")
                    ShizukuStateMachine.update()
                    ActivityLogManager.log("Shizuku", appContext.packageName,
                        "Service started via root (custom su: $suPath, strategy: $strategyName)")
                    return
                }
                lastError = Exception("Failed to start with root (exit $exitCode, strategy: $strategyName, su: $suPath)")
            } catch (e: Exception) {
                log("Strategy exception: ${e.javaClass.simpleName}: ${e.message}\n")
                lastError = e
            }
        }
        throw lastError ?: Exception("All SU invocation strategies failed for: $suPath")
    }

    // ---------------------------------------------------------------- System flow

    private suspend fun runSystemFlow() {
        lastStart = Triple(false, true, 0)
        setSteps(
            listOf(
                StarterStep("start_system", R.string.starter_step_start_root),
                StarterStep("wait_binder", R.string.starter_step_wait_binder),
                StarterStep("complete", R.string.starter_step_complete)
            )
        )

        if (!ShizukuSettings.isSamsungSystemUidEscalationEnabled()) {
            log("Samsung System UID Escalation is disabled for security reasons.\n")
            log("Enable it in Developer Settings to use this experimental feature.\n\n")
            updateStep("start_system", StepStatus.ERROR, "")
            setError(Exception("Samsung System UID Escalation disabled"))
            return
        }

        updateStep("start_system", StepStatus.RUNNING, appContext.getString(R.string.starter_step_system_running))
        withContext(Dispatchers.IO) {
            try {
                val intent = Intent().apply {
                    setClassName("com.sdet.fotaagent", "com.sdet.fotaagent.Main")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                appContext.startActivity(intent)

                val mIntent = Intent("com.sdet.fotaagent.intent.CP_FILE")
                mIntent.putExtra("CP_FILE", "/data")
                mIntent.putExtra("CP_LOC", "; " + appContext.applicationInfo.nativeLibraryDir
                        + "/libshizuku.so" + "; am force-stop com.sdet.fotaagent")
                kotlinx.coroutines.delay(1000)
                appContext.sendBroadcast(mIntent)
                log("FOTA command broadcast sent!\n\n")
            } catch (e: Exception) {
                log(e.message ?: "FOTA escalation failed")
                throw e
            }
        }
        updateStep("start_system", StepStatus.COMPLETED, "")

        updateStep("wait_binder", StepStatus.RUNNING, appContext.getString(R.string.starter_step_wait_binder_running))
        try {
            Starter.waitForBinder { log(it) }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            updateStep("wait_binder", StepStatus.ERROR, e.message ?: "")
            setError(e)
            return
        }
        updateStep("wait_binder", StepStatus.COMPLETED, "")
        markCompleted()
    }
}
