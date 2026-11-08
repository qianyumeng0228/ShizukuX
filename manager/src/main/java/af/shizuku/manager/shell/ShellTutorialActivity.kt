package af.shizuku.manager.shell

import af.shizuku.core.ui.AppBarActivity
import af.shizuku.manager.R
import af.shizuku.manager.databinding.TerminalTutorialActivityBinding
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import rikka.insets.*
import timber.log.Timber
import kotlin.math.roundToInt

class ShellTutorialActivity : AppBarActivity() {

    // The terminal guide is one of the five peer destinations behind the glass tab bar. It used to
    // show the app-bar up arrow whenever it was opened from outside the tab bar, which reads as
    // "you drilled into a sub page" even though the page has its own tab.
    override val showsUpNavigation = false

    private lateinit var binding: TerminalTutorialActivityBinding

    private val openDocumentsTree =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree: Uri? ->
            if (tree == null) return@registerForActivityResult

            lifecycleScope.launch {
                val (successCount, totalCount) = exportTerminalAssets(this@ShellTutorialActivity, tree)

                if (isFinishing || isDestroyed) return@launch
                val toastMsg = if (successCount == totalCount) {
                    getString(R.string.shell_export_success)
                } else if (successCount == 0) {
                    getString(R.string.shell_export_failed)
                } else {
                    getString(R.string.shell_export_partial, successCount, totalCount)
                }
                Toast.makeText(
                    this@ShellTutorialActivity,
                    toastMsg,
                    if (successCount == totalCount) Toast.LENGTH_SHORT else Toast.LENGTH_LONG
                ).show()
            }
        }

    override fun getLayoutId() = R.layout.terminal_tutorial_activity

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = TerminalTutorialActivityBinding.bind(rootView)

        binding.content.apply {
            setInitialPadding(
                initialPaddingLeft,
                initialPaddingTop + (resources.displayMetrics.density * 8).roundToInt(),
                initialPaddingRight,
                // 116dp keeps the last step card scrollable clear of the floating tab bar.
                initialPaddingBottom + (resources.displayMetrics.density * 116).roundToInt()
            )
        }

        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        buildTerminalPage(
            context = this,
            binding = binding,
            onPickExportDir = {
                try {
                    openDocumentsTree.launch(null)
                } catch (e: android.content.ActivityNotFoundException) {
                    Timber.tag("ShellTutorial").w("No file picker available on this device: ${e.message}")
                    Toast.makeText(this, R.string.no_file_picker, Toast.LENGTH_LONG).show()
                }
            }
        )

        af.shizuku.manager.utils.GlassTabBar.attach(this, rootView, af.shizuku.manager.utils.GlassTabs.INDEX_TERMINAL)
    }
}
