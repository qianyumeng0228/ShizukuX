package af.shizuku.manager.module

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.webkit.WebView
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

/**
 * Read-only WebUI viewer for a module's local web root.
 *
 * Network access is fully blocked and the Shizuku shell bridge is intentionally NOT injected:
 * ShizukuX does not ship a ModuleJsBridge yet, so a page that calls `window.Shizuku` must fail
 * loudly rather than silently pretend to work. This screen therefore only renders the module's
 * own static UI, and says so in the header.
 */
class ModuleWebUiActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val moduleName = intent.getStringExtra(EXTRA_NAME).orEmpty()
        val entryPath = intent.getStringExtra(EXTRA_ENTRY).orEmpty()

        setContent {
            af.shizuku.core.ui.compose.AppTheme(
                darkTheme = isSystemInDarkTheme(),
                isBlackNightTheme = ThemeHelper.isBlackNightTheme(this),
                isOneUi = ShizukuSettings.isOneUiThemeEnabled(),
                forceGlass = ShizukuSettings.isGlassUi()
            ) {
                WebUiScreen(
                    moduleName = moduleName,
                    entryPath = entryPath,
                    onBack = { onBackPressedDispatcher.onBackPressed() }
                )
            }
        }
    }

    companion object {
        private const val EXTRA_NAME = "module_name"
        private const val EXTRA_ENTRY = "module_entry"

        fun intent(context: Context, module: AdbModule): Intent {
            val entry = module.webRoot?.let { dir ->
                dir.resolve("index.html").takeIf { it.isFile }
                    ?: dir.resolve("index.htm").takeIf { it.isFile }
            }
            return Intent(context, ModuleWebUiActivity::class.java)
                .putExtra(EXTRA_NAME, module.name)
                .putExtra(EXTRA_ENTRY, entry?.absolutePath.orEmpty())
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun WebUiScreen(moduleName: String, entryPath: String, onBack: () -> Unit) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = moduleName.ifBlank { stringResourceCompat(R.string.modules_open_webui) },
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = stringResourceCompat(R.string.modules_webui_readonly),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.tertiary,
                            maxLines = 1
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
            if (entryPath.isBlank()) {
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
                            settings.blockNetworkLoads = true
                            settings.allowFileAccessFromFileURLs = false
                            settings.allowUniversalAccessFromFileURLs = false
                            settings.setSupportZoom(true)
                        }
                    },
                    update = { view ->
                        if (view.url == null) view.loadUrl("file://$entryPath")
                    }
                )
            }
        }
    }
}

@Composable
private fun stringResourceCompat(id: Int): String = androidx.compose.ui.res.stringResource(id)
