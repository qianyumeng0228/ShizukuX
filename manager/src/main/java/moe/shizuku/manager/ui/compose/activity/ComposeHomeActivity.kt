package moe.shizuku.manager.ui.compose.activity

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.lifecycle.ViewModel
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import moe.shizuku.manager.Helps
import moe.shizuku.manager.ShizukuSettings
import moe.shizuku.manager.adb.AdbPairingService
import moe.shizuku.manager.home.HomeActivity
import moe.shizuku.manager.home.showAccessibilityDialog
import moe.shizuku.manager.home.WadbStarter
import moe.shizuku.manager.management.AppsViewModel
import moe.shizuku.manager.settings.SettingsActivity
import moe.shizuku.manager.ui.compose.screens.HomeScreen
import moe.shizuku.manager.ui.compose.theme.ShizukuPlusTheme
import moe.shizuku.manager.utils.CustomTabsHelper
import moe.shizuku.manager.utils.EnvironmentUtils
import moe.shizuku.manager.utils.UserHandleCompat
import rikka.shizuku.Shizuku

class ComposeHomeActivity : ComponentActivity() {

    companion object {
        const val EXTRA_SHOW_PAIRING_DIALOG = "show_pairing_dialog"
        const val EXTRA_START_SERVICE_VIA_WADB = "start_service_via_wadb"
    }

    private val homeViewModel: HomeViewModel by viewModels()
    private val appsViewModel: AppsViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        appsViewModel.load()
        
        handleIntent(intent)

        setContent {
            val isDarkTheme = when (ShizukuSettings.getNightMode()) {
                androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES -> true
                androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO -> false
                else -> isSystemInDarkTheme()
            }
            
            val uiState by homeViewModel.uiState.collectAsState()
            val appsState by appsViewModel.grantedCount.observeAsState()
            
            ShizukuPlusTheme(
                darkTheme = isDarkTheme,
                dynamicColor = true
            ) {
                HomeScreen(
                    isServiceRunning = uiState.isServiceRunning,
                    serviceVersion = uiState.serviceVersion,
                    serviceMode = uiState.serviceMode,
                    grantedCount = appsState?.data ?: 0,
                    adbPermission = uiState.adbPermission,
                    isPrimaryUser = uiState.isPrimaryUser,
                    isRooted = uiState.isRooted,
                    isWadbAvailable = uiState.isWadbAvailable,
                    cardOrder = uiState.cardOrder,
                    hiddenCards = uiState.hiddenCards,
                    onStartServiceClick = {
                        startActivity(Intent(this, moe.shizuku.manager.starter.StarterActivity::class.java))
                    },
                    onSettingsClick = {
                        startActivity(Intent(this, SettingsActivity::class.java))
                    },
                    onAppsClick = {
                        startActivity(Intent(this, moe.shizuku.manager.management.ApplicationManagementActivity::class.java))
                    },
                    onAdbClick = {
                        WadbStarter.start(this, lifecycleScope)
                    },
                    onPairClick = {
                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                            WadbStarter.pair(this)
                        }
                    },
                    onGuideClick = {
                        CustomTabsHelper.launchUrlOrCopy(this, Helps.ADB_ANDROID11.get())
                    },
                    onTerminalClick = {
                        startActivity(Intent(this, moe.shizuku.manager.shell.ShellTutorialActivity::class.java))
                    },
                    onAutomationClick = {
                        // Navigate to automation
                    },
                    onLearnMoreClick = {
                        Helps.openUrl(this, Helps.getHelpUrl(null))
                    },
                    onActivityLogClick = {
                        startActivity(Intent(this, moe.shizuku.manager.settings.ActivityLogActivity::class.java))
                    },
                    onRestoreHiddenCards = {
                        ShizukuSettings.setHiddenHomeCards(emptySet())
                        homeViewModel.reload()
                    }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        intent?.let {
            val showDialog = it.getBooleanExtra(HomeActivity.EXTRA_SHOW_PAIRING_DIALOG, false)
            if (showDialog) showAccessibilityDialog()

            val startWadb = it.getBooleanExtra(HomeActivity.EXTRA_START_SERVICE_VIA_WADB, false)
            if (startWadb) {
                val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                nm.cancel(AdbPairingService.NOTIFICATION_ID)
                WadbStarter.start(this, lifecycleScope)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        homeViewModel.reload()
        appsViewModel.load()
        ShizukuSettings.syncAllPlusFeaturesToServer()
    }
}

class HomeViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState

    init {
        Shizuku.addBinderReceivedListenerSticky { updateServiceStatus() }
        Shizuku.addBinderDeadListener { updateServiceStatus() }
        updateServiceStatus()
    }

    fun reload() {
        updateServiceStatus()
    }

    private fun updateServiceStatus() {
        viewModelScope.launch {
            try {
                val isRunning = Shizuku.pingBinder()
                val version = if (isRunning) {
                    val v = Shizuku.getVersion()
                    val p = try { rikka.shizuku.ShizukuApiConstants.SERVER_PATCH_VERSION } catch (e: Exception) { 0 }
                    "$v.$p"
                } else {
                    null
                }
                
                val mode = if (isRunning) {
                    try {
                        val uid = Shizuku.getUid()
                        if (uid == 0) "Root" else "ADB"
                    } catch (e: Exception) {
                        "Unknown"
                    }
                } else {
                    null
                }

                val adbPermission = isRunning && try {
                    Shizuku.checkSelfPermission("android.permission.INTERACT_ACROSS_USERS_FULL") == android.content.pm.PackageManager.PERMISSION_GRANTED
                } catch (e: Exception) {
                    false
                }

                if (isRunning) {
                    ShizukuSettings.syncAllPlusFeaturesToServer()
                }

                _uiState.value = HomeUiState(
                    isServiceRunning = isRunning,
                    serviceVersion = version,
                    serviceMode = mode,
                    adbPermission = adbPermission,
                    isPrimaryUser = UserHandleCompat.myUserId() == 0,
                    isRooted = EnvironmentUtils.isRooted(),
                    isWadbAvailable = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R || EnvironmentUtils.getAdbTcpPort() > 0,
                    cardOrder = (ShizukuSettings.getCardOrder() ?: "2,3,4,5,8,6").split(",").mapNotNull { it.trim().toLongOrNull() },
                    hiddenCards = ShizukuSettings.getHiddenHomeCards()
                )
            } catch (e: Exception) {
                _uiState.value = HomeUiState(
                    isServiceRunning = false,
                    isPrimaryUser = UserHandleCompat.myUserId() == 0,
                    isRooted = EnvironmentUtils.isRooted(),
                    cardOrder = (ShizukuSettings.getCardOrder() ?: "2,3,4,5,8,6").split(",").mapNotNull { it.trim().toLongOrNull() },
                    hiddenCards = ShizukuSettings.getHiddenHomeCards()
                )
            }
        }
    }
}

data class HomeUiState(
    val isServiceRunning: Boolean = false,
    val serviceVersion: String? = null,
    val serviceMode: String? = null,
    val adbPermission: Boolean = false,
    val isPrimaryUser: Boolean = true,
    val isRooted: Boolean = false,
    val isWadbAvailable: Boolean = false,
    val cardOrder: List<Long> = listOf(2, 3, 4, 5, 8, 6),
    val hiddenCards: Set<String> = emptySet()
)
