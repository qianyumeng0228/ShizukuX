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
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
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
    val bridgePermitted = module.enabled &&
        ModuleSettings.canExposeWebBridge(module) &&
        (module.declaresShellBridge || trusted)

    val entry = module.webRoot?.let { dir ->
        dir.resolve("index.html").takeIf { it.isFile }
            ?: dir.resolve("index.htm").takeIf { it.isFile }
    }

    val subtitle = when {
        !bridgePermitted -> stringResourceCompat(R.string.modules_webui_readonly)
        webNetworkAllowed -> stringResourceCompat(R.string.modules_webui_bridge_network)
        else -> stringResourceCompat(R.string.modules_webui_bridge)
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
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer
                )
            )
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (entry == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) {
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
                            if (bridgePermitted) {
                                addJavascriptInterface(ModuleJsBridge(module, this), "Shizuku")
                            }
                            loadUrl(Uri.fromFile(entry).toString())
                        }
                    }
                )
            }
        }
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
