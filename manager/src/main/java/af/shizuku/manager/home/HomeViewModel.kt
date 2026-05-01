package af.shizuku.manager.home

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.annotation.Keep
import androidx.annotation.RequiresApi
import androidx.lifecycle.Observer
import com.airbnb.mvrx.Fail
import com.airbnb.mvrx.Loading
import com.airbnb.mvrx.MavericksViewModel
import com.airbnb.mvrx.MavericksViewModelFactory
import com.airbnb.mvrx.Success
import com.airbnb.mvrx.ViewModelContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import af.shizuku.manager.BuildConfig
import af.shizuku.manager.Manifest
import af.shizuku.manager.ShizukuSettings
import af.shizuku.manager.adb.AdbMdns
import af.shizuku.manager.model.ServiceStatus
import af.shizuku.manager.utils.EnvironmentUtils
import af.shizuku.manager.utils.Logger.LOGGER
import af.shizuku.manager.utils.SettingsHelper
import af.shizuku.manager.utils.ShizukuSystemApis
import rikka.shizuku.Shizuku

@Keep
class HomeViewModel(
    initialState: HomeState,
    private val appContext: Context
) : MavericksViewModel<HomeState>(initialState) {

    private var adbMdns: AdbMdns? = null

    init {
        reload()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && EnvironmentUtils.isTlsSupported()) {
            startAdbPortDiscovery()
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun startAdbPortDiscovery() {
        val observer = Observer<Int> { port ->
            setState { copy(discoveredAdbPort = port) }
        }
        adbMdns = AdbMdns(appContext, AdbMdns.TLS_CONNECT, observer).also { it.start() }
    }

    override fun onCleared() {
        super.onCleared()
        adbMdns?.stop()
    }

    fun reload() {
        setState { copy(serviceStatus = Loading()) }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val status = loadStatus()
                setState { copy(serviceStatus = Success(status)) }
            } catch (e: Exception) {
                LOGGER.e(e, "Failed to load Shizuku status")
                setState { copy(serviceStatus = Fail(e)) }
            }
        }
    }

    private fun loadStatus(): ServiceStatus {
        if (!Shizuku.pingBinder()) {
            LOGGER.d("Shizuku binder not available")
            return ServiceStatus()
        }

        val uid = Shizuku.getUid()
        val apiVersion = Shizuku.getVersion()
        val patchVersion = Shizuku.getServerPatchVersion().let { if (it < 0) 0 else it }
        val seContext = if (apiVersion >= 6) {
            try {
                Shizuku.getSELinuxContext()
            } catch (tr: Throwable) {
                LOGGER.w(tr, "getSELinuxContext")
                null
            }
        } else null
        
        val permissionTest = Shizuku.checkRemotePermission("android.permission.GRANT_RUNTIME_PERMISSIONS") == PackageManager.PERMISSION_GRANTED

        try {
            ShizukuSystemApis.checkPermission(Manifest.permission.API_V23, BuildConfig.APPLICATION_ID, 0)
        } catch (e: Exception) {
            LOGGER.w(e, "Permission check failed")
        }
        
        return ServiceStatus(uid, apiVersion, patchVersion, seContext, permissionTest)
    }

    fun checkBatteryOptimization() {
        if (EnvironmentUtils.isTelevision()) return
        if (!ShizukuSettings.getStartOnBoot(appContext) && !ShizukuSettings.getWatchdog()) return
        val isIgnoring = SettingsHelper.isIgnoringBatteryOptimizations(appContext)
        setState { copy(shouldShowBatteryOptimizationSnackbar = !isIgnoring) }
    }

    fun setEditMode(active: Boolean) {
        setState { copy(isEditMode = active) }
    }

    fun updateGrantedAppCount(count: Int) {
        setState { copy(grantedAppCount = count) }
    }

    @Keep
    companion object : com.airbnb.mvrx.MavericksViewModelFactory<HomeViewModel, HomeState> {
        override fun create(viewModelContext: com.airbnb.mvrx.ViewModelContext, state: HomeState): HomeViewModel {
            return HomeViewModel(state, viewModelContext.app())
        }
    }
}
