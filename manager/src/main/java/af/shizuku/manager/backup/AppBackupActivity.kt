package af.shizuku.manager.backup

import android.net.Uri
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.snackbar.Snackbar
import af.shizuku.core.ui.AppBarActivity
import af.shizuku.manager.R
import af.shizuku.manager.ShizukuSettings
import af.shizuku.manager.databinding.ActivityAppBackupBinding
import kotlinx.coroutines.launch

class AppBackupActivity : AppBarActivity() {

    private val viewModel: BackupViewModel by viewModels()
    private lateinit var binding: ActivityAppBackupBinding
    private lateinit var adapter: BackupAdapter

    private val directoryPicker = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri != null) {
            contentResolver.takePersistableUriPermission(
                uri,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            ShizukuSettings.setExportDirUri(uri.toString())
            com.google.android.material.snackbar.Snackbar.make(
                rootView,
                getString(R.string.backup_export_dir_set, uri.lastPathSegment ?: ""),
                com.google.android.material.snackbar.Snackbar.LENGTH_LONG
            ).show()
        }
    }

    private fun getSafUri(): Uri? {
        return ShizukuSettings.getExportDirUri()?.let { uriStr ->
            val uri = Uri.parse(uriStr)
            val hasWritePermission = contentResolver.persistedUriPermissions.any {
                it.uri == uri && it.isWritePermission
            }
            if (hasWritePermission) uri else null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityAppBackupBinding.inflate(layoutInflater, rootView, false)
        setContentView(binding.root)

        supportActionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            title = getString(R.string.home_backup_title)
        }

        adapter = BackupAdapter()
        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = adapter

        adapter.onBackupClick = { entry ->
            val safUri = getSafUri()
            if (safUri != null) {
                viewModel.backupAppData(entry, safTreeUri = safUri)
            } else {
                val outputDir = getExternalFilesDir(null) ?: filesDir
                viewModel.backupAppData(entry, outputDir = outputDir)
            }
        }
        adapter.onFreezeClick = { entry ->
            viewModel.toggleFreeze(entry)
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect { state ->
                    when (state) {
                        is BackupViewModel.UiState.Loading -> showLoading()
                        is BackupViewModel.UiState.Loaded -> {
                            showContent()
                            adapter.submitList(state.apps)
                        }
                        is BackupViewModel.UiState.Error -> showError(state.msg)
                        is BackupViewModel.UiState.ServiceNotRunning -> showError(
                            getString(R.string.home_status_service_not_running, getString(R.string.app_name))
                        )
                    }
                }
            }
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.busyPackages.collect { busy ->
                    adapter.setBusy(busy)
                }
            }
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.events.collect { event ->
                    when (event) {
                        is BackupViewModel.BackupEvent.BackupComplete ->
                            Snackbar.make(
                                rootView,
                                getString(R.string.backup_app_complete, event.pkg, event.path),
                                Snackbar.LENGTH_LONG
                            ).show()
                        is BackupViewModel.BackupEvent.FreezeChanged -> {
                            val msg = if (event.nowFrozen) R.string.backup_freeze_success else R.string.backup_unfreeze_success
                            Snackbar.make(rootView, msg, Snackbar.LENGTH_SHORT).show()
                        }
                        is BackupViewModel.BackupEvent.Failure ->
                            Snackbar.make(rootView, event.msg, Snackbar.LENGTH_LONG).show()
                    }
                }
            }
        }

        viewModel.loadApps()
    }

    private fun showLoading() {
        binding.progressBar.visibility = View.VISIBLE
        binding.recyclerView.visibility = View.GONE
        binding.errorText.visibility = View.GONE
    }

    private fun showContent() {
        binding.progressBar.visibility = View.GONE
        binding.recyclerView.visibility = View.VISIBLE
        binding.errorText.visibility = View.GONE
    }

    private fun showError(msg: String) {
        binding.progressBar.visibility = View.GONE
        binding.recyclerView.visibility = View.GONE
        binding.errorText.visibility = View.VISIBLE
        binding.errorText.text = msg
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, MENU_CHOOSE_DIR, 0, R.string.backup_choose_export_dir)
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> { finish(); true }
            MENU_CHOOSE_DIR -> { directoryPicker.launch(null); true }
            else -> super.onOptionsItemSelected(item)
        }
    }

    companion object {
        private const val MENU_CHOOSE_DIR = 1001
    }
}
