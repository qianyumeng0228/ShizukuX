package af.shizuku.manager.automation

import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.chip.Chip
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import af.shizuku.manager.R
import af.shizuku.manager.ShizukuSettings
import af.shizuku.manager.databinding.ActivityAppProfilesBinding
import af.shizuku.manager.ktx.themeColor
import af.shizuku.manager.utils.HapticUtils

/**
 * Per-app Binder Firewall automation profiles.
 *
 * Each app can be set to one of three states:
 *  - DEFAULT: no override — the user's global Binder Firewall setting applies
 *  - BLOCK:   binder_firewall=true when this app is in the foreground (restricts Shizuku access)
 *  - ALLOW:   binder_firewall=false when this app is in the foreground (permits Shizuku access)
 *
 * Profiles are stored in the JSON format that [AppSpecificProfileRule] reads, e.g.
 *   {"com.pkg": {"binder_firewall": true}}
 * Apps with DEFAULT state have no entry in the JSON.
 */
class AppProfilesActivity : AppCompatActivity() {

    enum class ProfileState { DEFAULT, BLOCK, ALLOW }

    data class AppEntry(
        val label: String,
        val packageName: String,
        val appInfo: android.content.pm.ApplicationInfo,
        var state: ProfileState
    )

    private lateinit var binding: ActivityAppProfilesBinding
    private val profiles = mutableMapOf<String, ProfileState>()
    private var allApps: List<AppEntry> = emptyList()
    private var filteredApps: List<AppEntry> = emptyList()
    private var adapter: ProfileAdapter? = null
    private var searchQuery = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAppProfilesBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            title = getString(R.string.app_profiles_title)
        }

        profiles.putAll(loadProfilesFromJson())

        binding.recycler.layoutManager = LinearLayoutManager(this)

        lifecycleScope.launch {
            binding.progress.visibility = View.VISIBLE
            allApps = withContext(Dispatchers.IO) { loadApps() }
            binding.progress.visibility = View.GONE
            adapter = ProfileAdapter()
            binding.recycler.adapter = adapter
            applyFilter(searchQuery)
        }

        var debounceJob: Job? = null
        binding.searchEditText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                debounceJob?.cancel()
                debounceJob = lifecycleScope.launch {
                    delay(200)
                    applyFilter(s?.toString() ?: "")
                }
            }
        })
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    override fun onStop() {
        super.onStop()
        saveProfilesToJson()
        // Auto-start/stop AutomationService based on current rules.
        val svcIntent = Intent(this, AutomationService::class.java)
        if (ShizukuSettings.hasAnyAutomationRulesConfigured()) {
            startService(svcIntent)
        } else {
            stopService(svcIntent)
        }
    }

    private fun loadApps(): List<AppEntry> {
        val pm = packageManager
        val self = packageName
        return pm.getInstalledApplications(PackageManager.GET_META_DATA)
            .asSequence()
            .filter { it.packageName != self }
            .map { info ->
                val label = pm.getApplicationLabel(info).toString()
                val state = profiles[info.packageName] ?: ProfileState.DEFAULT
                AppEntry(label, info.packageName, info, state)
            }
            .sortedWith(
                compareBy(
                    { it.state == ProfileState.DEFAULT }, // configured apps float to top
                    { it.label.lowercase() }
                )
            )
            .toList()
    }

    private fun applyFilter(query: String) {
        searchQuery = query
        val newList = if (query.isEmpty()) {
            allApps
        } else {
            allApps.filter {
                it.label.contains(query, ignoreCase = true) ||
                    it.packageName.contains(query, ignoreCase = true)
            }
        }
        val old = filteredApps
        filteredApps = newList
        binding.emptyText.visibility = if (newList.isEmpty()) View.VISIBLE else View.GONE
        adapter?.let { a ->
            val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
                override fun getOldListSize() = old.size
                override fun getNewListSize() = newList.size
                override fun areItemsTheSame(op: Int, np: Int) =
                    old[op].packageName == newList[np].packageName
                override fun areContentsTheSame(op: Int, np: Int) =
                    old[op].state == newList[np].state
            })
            diff.dispatchUpdatesTo(a)
        }
    }

    private fun loadProfilesFromJson(): Map<String, ProfileState> {
        val result = mutableMapOf<String, ProfileState>()
        try {
            val json = JSONObject(ShizukuSettings.getAutomationAppProfilesJson())
            json.keys().forEach { pkg ->
                val obj = json.optJSONObject(pkg) ?: return@forEach
                val fw = obj.optBoolean("binder_firewall", false)
                result[pkg] = if (fw) ProfileState.BLOCK else ProfileState.ALLOW
            }
        } catch (_: Exception) {}
        return result
    }

    private fun saveProfilesToJson() {
        val json = JSONObject()
        profiles.forEach { (pkg, state) ->
            if (state != ProfileState.DEFAULT) {
                json.put(pkg, JSONObject().apply {
                    put("binder_firewall", state == ProfileState.BLOCK)
                })
            }
        }
        ShizukuSettings.setAutomationAppProfilesJson(json.toString())
    }

    private fun chipColorsForState(state: ProfileState): Pair<Int, Int> = when (state) {
        ProfileState.DEFAULT -> Pair(
            themeColor(com.google.android.material.R.attr.colorSurfaceVariant),
            themeColor(com.google.android.material.R.attr.colorOnSurfaceVariant)
        )
        ProfileState.BLOCK -> Pair(
            themeColor(com.google.android.material.R.attr.colorErrorContainer),
            themeColor(com.google.android.material.R.attr.colorOnErrorContainer)
        )
        ProfileState.ALLOW -> Pair(
            themeColor(com.google.android.material.R.attr.colorSecondaryContainer),
            themeColor(com.google.android.material.R.attr.colorOnSecondaryContainer)
        )
    }

    inner class ProfileAdapter : RecyclerView.Adapter<ProfileAdapter.VH>() {

        inner class VH(view: View) : RecyclerView.ViewHolder(view) {
            val icon: ImageView = view.findViewById(R.id.icon)
            val name: TextView = view.findViewById(R.id.app_name)
            val pkg: TextView = view.findViewById(R.id.package_name)
            val chip: Chip = view.findViewById(R.id.profile_chip)
            var iconJob: Job? = null
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_app_profile, parent, false)
            return VH(view)
        }

        override fun getItemCount() = filteredApps.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val app = filteredApps[position]
            holder.name.text = app.label
            holder.pkg.text = app.packageName

            holder.iconJob?.cancel()
            holder.icon.setImageDrawable(null)
            holder.iconJob = af.shizuku.manager.utils.AppIconCache.loadIconBitmapAsync(
                holder.itemView.context, app.appInfo, app.appInfo.uid / 100000, holder.icon
            )

            bindChip(holder.chip, app)
        }

        override fun onViewRecycled(holder: VH) {
            super.onViewRecycled(holder)
            holder.iconJob?.cancel()
        }

        private fun bindChip(chip: Chip, app: AppEntry) {
            chip.text = when (app.state) {
                ProfileState.DEFAULT -> chip.context.getString(R.string.app_profile_state_default)
                ProfileState.BLOCK -> chip.context.getString(R.string.app_profile_state_block)
                ProfileState.ALLOW -> chip.context.getString(R.string.app_profile_state_allow)
            }
            val (bg, fg) = chipColorsForState(app.state)
            chip.chipBackgroundColor = ColorStateList.valueOf(bg)
            chip.setTextColor(fg)

            chip.setOnClickListener {
                val newState = when (app.state) {
                    ProfileState.DEFAULT -> ProfileState.BLOCK
                    ProfileState.BLOCK -> ProfileState.ALLOW
                    ProfileState.ALLOW -> ProfileState.DEFAULT
                }
                app.state = newState
                if (newState == ProfileState.DEFAULT) {
                    profiles.remove(app.packageName)
                } else {
                    profiles[app.packageName] = newState
                }
                bindChip(chip, app)
                HapticUtils.tick(chip)

                // Re-sort so configured apps remain at the top of the list.
                lifecycleScope.launch {
                    val sorted = allApps.sortedWith(
                        compareBy(
                            { it.state == ProfileState.DEFAULT },
                            { it.label.lowercase() }
                        )
                    )
                    allApps = sorted
                    applyFilter(searchQuery)
                }
            }
        }
    }
}
