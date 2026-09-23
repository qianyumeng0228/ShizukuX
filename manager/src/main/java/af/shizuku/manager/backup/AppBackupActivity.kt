package af.shizuku.manager.backup

import android.net.Uri
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.widget.SearchView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
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
    private var includeSystem = false
    private var backupAllItem: MenuItem? = null

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
        adapter.onRestoreClick = { entry ->
            val safUri = getSafUri()
            if (safUri == null) {
                MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.backup_restore_title)
                    .setMessage(R.string.backup_restore_no_dir)
                    .setPositiveButton(R.string.backup_choose_export_dir) { _, _ ->
                        directoryPicker.launch(null)
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            } else {
                showRestoreConfirmation(listOf(entry), safUri)
            }
        }
        adapter.onItemLongClick = { entry ->
            if (!adapter.isInSelectionMode()) {
                adapter.enterSelectionMode(entry.packageName)
                updateSelectionTitle()
                invalidateOptionsMenu()
            }
        }
        adapter.onItemClick = { entry ->
            if (adapter.isInSelectionMode()) {
                adapter.toggleSelection(entry.packageName)
                updateSelectionTitle()
                if (adapter.getSelectedCount() == 0) {
                    exitSelectionMode()
                }
            }
        }
        adapter.onSelectionChanged = { updateSelectionTitle() }

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
                viewModel.batchRunning.collect { running ->
                    backupAllItem?.isEnabled = !running
                    binding.batchProgressPanel.visibility = if (running) View.VISIBLE else View.GONE
                }
            }
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.batchProgress.collect { progress ->
                    if (progress.total > 0) {
                        val pct = if (progress.total > 0) (progress.current * 100 / progress.total) else 0
                        binding.batchProgressBar.progress = pct
                        binding.batchProgressText.text = getString(
                            R.string.backup_batch_progress,
                            progress.current, progress.total, progress.currentPkg
                        )
                    }
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
                        is BackupViewModel.BackupEvent.BatchComplete ->
                            Snackbar.make(
                                rootView,
                                getString(R.string.backup_batch_complete, event.succeeded, event.failed, event.path),
                                Snackbar.LENGTH_LONG
                            ).show()
                        is BackupViewModel.BackupEvent.RestoreComplete -> {
                            val msg = if (event.success) R.string.backup_restore_success
                                      else R.string.backup_restore_failed
                            Snackbar.make(rootView, getString(msg, event.pkg), Snackbar.LENGTH_SHORT).show()
                        }
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

        viewModel.loadApps(includeSystem)
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
        if (adapter.isInSelectionMode()) {
            menu.add(0, MENU_RESTORE, 0, R.string.backup_restore_selected)
                .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
            menu.add(0, MENU_SELECT_ALL, 1, R.string.backup_select_all)
                .setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
            menu.add(0, MENU_CANCEL, 2, android.R.string.cancel)
                .setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
            return true
        }

        menuInflater.inflate(R.menu.app_backup_menu, menu)
        menu.findItem(R.id.action_show_system)?.isChecked = includeSystem
        backupAllItem = menu.findItem(R.id.action_backup_all)

        // Add choose export directory as a dynamic menu item
        menu.add(0, MENU_CHOOSE_DIR, 100, R.string.backup_choose_export_dir)
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)

        val searchItem = menu.findItem(R.id.action_search)
        val searchView = searchItem?.actionView as? SearchView
        searchView?.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?) = true
            override fun onQueryTextChange(newText: String?): Boolean {
                viewModel.setQuery(newText.orEmpty())
                return true
            }
        })
        searchItem?.setOnActionExpandListener(object : MenuItem.OnActionExpandListener {
            override fun onMenuItemActionExpand(item: MenuItem) = true
            override fun onMenuItemActionCollapse(item: MenuItem): Boolean {
                viewModel.setQuery("")
                return true
            }
        })

        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> {
                if (adapter.isInSelectionMode()) { exitSelectionMode(); true }
                else { finish(); true }
            }
            MENU_RESTORE -> { startRestoreSelected(); true }
            MENU_SELECT_ALL -> { adapter.selectAll(); updateSelectionTitle(); true }
            MENU_CANCEL -> { exitSelectionMode(); true }
            R.id.action_backup_all -> {
                val safUri = getSafUri()
                if (safUri != null) {
                    viewModel.backupAll(safTreeUri = safUri)
                } else {
                    viewModel.backupAll(outputDir = getExternalFilesDir(null) ?: filesDir)
                }
                true
            }
            R.id.action_show_system -> {
                includeSystem = !includeSystem
                item.isChecked = includeSystem
                viewModel.loadApps(includeSystem)
                true
            }
            MENU_CHOOSE_DIR -> { directoryPicker.launch(null); true }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun updateSelectionTitle() {
        if (adapter.isInSelectionMode()) {
            supportActionBar?.title = getString(R.string.backup_selected_count, adapter.getSelectedCount())
        } else {
            supportActionBar?.title = getString(R.string.home_backup_title)
        }
    }

    private fun exitSelectionMode() {
        adapter.exitSelectionMode()
        updateSelectionTitle()
        invalidateOptionsMenu()
    }

    private fun startRestoreSelected() {
        val selectedPkgs = adapter.getSelectedPackages()
        if (selectedPkgs.isEmpty()) return

        val safUri = getSafUri()
        if (safUri == null) {
            // No SAF directory configured — prompt user to choose one first
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.backup_restore_title)
                .setMessage(R.string.backup_restore_no_dir)
                .setPositiveButton(R.string.backup_choose_export_dir) { _, _ ->
                    directoryPicker.launch(null)
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
            return
        }

        // Get AppEntry objects for selected packages
        val state = viewModel.state.value
        val selectedEntries = if (state is BackupViewModel.UiState.Loaded) {
            state.apps.filter { it.packageName in selectedPkgs }
        } else emptyList()

        if (selectedEntries.isEmpty()) {
            Snackbar.make(rootView, R.string.backup_restore_no_apps, Snackbar.LENGTH_SHORT).show()
            return
        }

        showRestoreConfirmation(selectedEntries, safUri)
    }

    /**
     * Shows the restore confirmation dialog for one or more apps.
     * Includes restore scope selection (internal data checkbox) and auto-backup notice.
     * On confirm, starts the restore via viewModel.restoreAll().
     */
    private fun showRestoreConfirmation(entries: List<BackupViewModel.AppEntry>, safUri: android.net.Uri) {
        val pkgList = entries.joinToString("\n") { "• ${it.label} (${it.packageName})" }
        val includeInternalCheckbox = android.widget.CheckBox(this).apply {
            text = getString(R.string.backup_restore_include_internal)
            isChecked = true
            setPadding(0, (16 * resources.displayMetrics.density).toInt(), 0, 0)
        }
        val dialogView = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding((24 * resources.displayMetrics.density).toInt(), 0,
                (24 * resources.displayMetrics.density).toInt(), 0)
            addView(includeInternalCheckbox)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.backup_restore_confirm_title)
            .setMessage(getString(R.string.backup_restore_confirm_msg, entries.size, pkgList) + "\n\n" + getString(R.string.backup_restore_auto_backup_notice))
            .setView(dialogView)
            .setPositiveButton(R.string.backup_restore_action) { _, _ ->
                viewModel.restoreAll(entries, safUri, includeInternalCheckbox.isChecked)
                if (adapter.isInSelectionMode()) exitSelectionMode()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (adapter.isInSelectionMode()) {
            exitSelectionMode()
        } else {
            super.onBackPressed()
        }
    }

    companion object {
        private const val MENU_CHOOSE_DIR = 1001
        private const val MENU_RESTORE = 1002
        private const val MENU_SELECT_ALL = 1003
        private const val MENU_CANCEL = 1004
    }
}
