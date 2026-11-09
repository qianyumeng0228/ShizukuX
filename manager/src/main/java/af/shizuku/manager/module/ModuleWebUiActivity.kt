package af.shizuku.manager.module

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import af.shizuku.manager.R
import af.shizuku.manager.ShizukuSettings
import af.shizuku.manager.app.ThemeHelper
import af.shizuku.manager.ui.compose.ShizukuIcon
import java.io.File

/**
 * WebUI host for a module's local web root.
 *
 * The shell bridge is injected exactly when the module is enabled, is either trusted or
 * declares `usesShellBridge=true`, and the access mode allows a WebUI bridge. This is the
 * gate third-party modules written for Shevery/Nightzuku expect: their pages call
 * `window.Shizuku.exec(...)` and degrade to "bridge not found" when it is absent.
 *
 * Network stays blocked unless the module is trusted or the access mode explicitly allows
 * WebUI networking; file:// pages only gain universal access under the same condition, so an
 * untrusted page cannot read across origins.
 */
class ModuleWebUiActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val moduleId = intent.getStringExtra(EXTRA_MODULE_ID).orEmpty()
        val module = runCatching {
            AdbModuleManager.readModule(AdbModuleManager.modulesRoot(this).resolve(moduleId))
        }.getOrNull()

        if (module == null) {
            finish()
            return
        }

        setContent {
            af.shizuku.core.ui.compose.AppTheme(
                darkTheme = isSystemInDarkTheme(),
                isBlackNightTheme = ThemeHelper.isBlackNightTheme(this),
                isOneUi = ShizukuSettings.isOneUiThemeEnabled(),
                forceGlass = ShizukuSettings.isGlassUi()
            ) {
                WebUiScreen(
                    module = module,
                    onBack = { onBackPressedDispatcher.onBackPressed() }
                )
            }
        }
    }

    companion object {
        private const val EXTRA_MODULE_ID = "module_id"

        fun intent(context: Context, module: AdbModule): Intent {
            return Intent(context, ModuleWebUiActivity::class.java)
                .putExtra(EXTRA_MODULE_ID, module.id)
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun WebUiScreen(module: AdbModule, onBack: () -> Unit) {
    val trusted = ModuleSettings.isModuleTrusted(module.id)
    val webNetworkAllowed = ModuleSettings.canUseWebNetwork(module)
    // The bridge is injected whenever the module may own one, and each call is then allowed or
    // refused by the access mode. Injecting it under Safe is deliberate: a page without the
    // bridge cannot tell "this manager has no shell" from "this manager refused", and a module
    // that legitimately needs Full access has no way to ask for it.
    val bridgeAvailable = module.enabled && (module.declaresShellBridge || trusted)

    var mode by remember { mutableStateOf(ModuleSettings.getAccessMode()) }
    val fullAccess = mode == ModuleSettings.AccessMode.FULL
    var showModeDialog by remember { mutableStateOf(false) }
    var webView by remember { mutableStateOf<WebView?>(null) }

    val entry = module.webRoot?.let { dir ->
        dir.resolve("index.html").takeIf { it.isFile }
            ?: dir.resolve("index.htm").takeIf { it.isFile }
    }

    val subtitle = when {
        !bridgeAvailable -> stringResourceCompat(R.string.modules_webui_readonly)
        webNetworkAllowed -> stringResourceCompat(R.string.modules_webui_bridge_network)
        else -> stringResourceCompat(R.string.modules_webui_bridge)
    }

    fun applyMode(candidate: ModuleSettings.AccessMode) {
        ModuleSettings.setAccessMode(candidate)
        mode = candidate
        // The bridge checks the mode per call, so a reload is enough to take effect.
        webView?.reload()
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = module.name.ifBlank { stringResourceCompat(R.string.modules_open_webui) },
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.tertiary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        ShizukuIcon(
                            icon = R.drawable.ic_arrow_back_24,
                            contentDescription = stringResourceCompat(R.string.action_back)
                        )
                    }
                },
                actions = {
                    TextButton(onClick = { showModeDialog = true }) {
                        Text(stringResourceCompat(R.string.modules_webui_access_short))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer
                )
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (bridgeAvailable && !fullAccess) {
                Surface(
                    color = MaterialTheme.colorScheme.tertiaryContainer,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResourceCompat(R.string.modules_webui_needs_full),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onTertiaryContainer,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = { applyMode(ModuleSettings.AccessMode.FULL) }) {
                            Text(stringResourceCompat(R.string.modules_webui_enable_full))
                        }
                    }
                }
            }
            Box(modifier = Modifier.fillMaxSize()) {
                if (entry == null) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            text = stringResourceCompat(R.string.modules_webui_missing),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { context ->
                            WebView(context).apply {
                                webView = this
                                settings.javaScriptEnabled = true
                                settings.domStorageEnabled = true
                                settings.allowFileAccess = true
                                settings.allowContentAccess = false
                                settings.blockNetworkLoads = !webNetworkAllowed
                                settings.allowFileAccessFromFileURLs = trusted
                                settings.allowUniversalAccessFromFileURLs = trusted
                                settings.cacheMode = WebSettings.LOAD_DEFAULT
                                settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                                settings.setSupportZoom(true)
                                CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
                                webViewClient = LocalModuleWebViewClient(module)
                                if (bridgeAvailable) {
                                    addJavascriptInterface(
                                        ModuleJsBridge(module, this) {
                                            // Called from the page's JS thread: hop to the UI thread
                                            // before touching Compose state.
                                            post { showModeDialog = true }
                                            true
                                        },
                                        "Shizuku"
                                    )
                                }
                                loadUrl(Uri.fromFile(entry).toString())
                            }
                        }
                    )
                }
            }
        }
    }

    if (showModeDialog) {
        AlertDialog(
            onDismissRequest = { showModeDialog = false },
            title = { Text(stringResourceCompat(R.string.modules_access_mode)) },
            text = {
                Column {
                    ModuleSettings.AccessMode.entries.forEach { candidate ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    showModeDialog = false
                                    applyMode(candidate)
                                }
                                .padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(selected = candidate == mode, onClick = null)
                            Text(
                                text = stringResourceCompat(candidate.labelRes),
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(start = 12.dp)
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showModeDialog = false }) {
                    Text(stringResourceCompat(android.R.string.cancel))
                }
            }
        )
    }
}

/**
 * Keeps `file:` navigation inside the module's web root and hands http(s) links to the
 * system browser instead of letting a module page drive the in-app WebView.
 */
private class LocalModuleWebViewClient(private val module: AdbModule) : WebViewClient() {

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        val uri = request.url
        val scheme = uri.scheme?.lowercase()
        if (scheme == "http" || scheme == "https") {
            runCatching {
                val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                view.context.startActivity(intent)
            }
            return true
        }
        if (scheme == "file") {
            return !isInsideWebRoot(uri.path.orEmpty())
        }
        return true
    }

    private fun isInsideWebRoot(path: String): Boolean {
        val root = module.webRoot ?: return false
        return runCatching {
            val rootPath = root.canonicalPath
            val targetPath = File(path).canonicalPath
            targetPath == rootPath || targetPath.startsWith(rootPath + File.separator)
        }.getOrDefault(false)
    }
}

@Composable
private fun stringResourceCompat(id: Int): String = androidx.compose.ui.res.stringResource(id)
