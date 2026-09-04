package af.shizuku.manager.settings

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import af.shizuku.core.ui.AppBarActivity
import af.shizuku.manager.R
import af.shizuku.manager.database.DhizukuAppManager
import af.shizuku.manager.widget.DhizukuAppsAdapter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * Dhizuku app management screen (ported from dhizuku's AppManagementPage).
 *
 * Lists apps that request the Dhizuku API permission, with an "allow access to Dhizuku"
 * switch and a block/unblock button per app. Authorizations are stored in
 * [DhizukuAppManager]'s Room database and enforced by DhizukuProvider.
 */
class DhizukuAppManagementActivity : AppBarActivity() {

    companion object {
        /**
         * Chinese ROMs (MIUI/HyperOS) gate the ability to enumerate installed apps behind this
         * runtime-granted "get installed apps" special access (in addition to QUERY_ALL_PACKAGES).
         * On stock AOSP this permission doesn't exist and the request is skipped.
         */
        private const val PERMISSION_GET_INSTALLED_APPS = "com.android.permission.GET_INSTALLED_APPS"
        private const val REQUEST_GET_INSTALLED_APPS = 100
    }

    private lateinit var recyclerView: RecyclerView
    private lateinit var emptyView: View
    private lateinit var adapter: DhizukuAppsAdapter

    private val packageReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            refresh()
        }
    }

    override fun getLayoutId(): Int = R.layout.dhizuku_app_management_activity

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.dhizuku_app_management_title)

        recyclerView = findViewById(R.id.recycler_view)
        emptyView = findViewById(R.id.empty_view)

        recyclerView.layoutManager = LinearLayoutManager(this)
        ViewCompat.setOnApplyWindowInsetsListener(recyclerView) { view, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            view.setPadding(bars.left, view.paddingTop, bars.right, view.paddingBottom)
            insets
        }

        adapter = DhizukuAppsAdapter(this) { refresh() }
        recyclerView.adapter = adapter

        // Load list off the main thread
        refresh()

        requestGetInstalledAppsPermissionIfNeeded()

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addDataScheme("package")
        }
        ContextCompat.registerReceiver(this, packageReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    private fun refresh() {
        lifecycleScope.launch {
            val items = withContext(Dispatchers.IO) {
                DhizukuAppManager.ensureInitialized(applicationContext)
                DhizukuAppsAdapter.collectItems(applicationContext)
            }
            if (isFinishing || isDestroyed) return@launch
            adapter.setItems(items)
            val isEmpty = items.isEmpty()
            recyclerView.visibility = if (isEmpty) View.GONE else View.VISIBLE
            emptyView.visibility = if (isEmpty) View.VISIBLE else View.GONE
        }
    }

    /**
     * On MIUI/HyperOS the app can only enumerate installed packages once the "get installed
     * apps" runtime permission is granted. Ask for it right away; when it's granted (or already
     * granted) refresh() re-runs so the Dhizuku client list populates.
     */
    private fun requestGetInstalledAppsPermissionIfNeeded() {
        try {
            packageManager.getPermissionInfo(PERMISSION_GET_INSTALLED_APPS, 0)
        } catch (e: Exception) {
            // Permission doesn't exist on stock AOSP / non-Chinese ROMs - nothing to request.
            Timber.d("GET_INSTALLED_APPS not available: %s", e.javaClass.simpleName)
            return
        }
        val granted = ContextCompat.checkSelfPermission(this, PERMISSION_GET_INSTALLED_APPS) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(PERMISSION_GET_INSTALLED_APPS),
                REQUEST_GET_INSTALLED_APPS
            )
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_GET_INSTALLED_APPS) {
            refresh()
        }
    }

    override fun onDestroy() {
        try {
            unregisterReceiver(packageReceiver)
        } catch (e: Exception) {
            Timber.w(e, "unregisterReceiver failed")
        }
        super.onDestroy()
    }

    override fun onOptionsItemSelected(item: android.view.MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }
}
