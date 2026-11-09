package af.shizuku.manager.management

import android.app.Application
import android.content.Context
import android.content.pm.PackageInfo
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger
import af.shizuku.manager.authorization.AuthorizationManager
import rikka.lifecycle.Resource

enum class SortOrder { NAME_ASC, LAST_INSTALLED, LAST_UPDATED }
enum class FilterState { ALL, GRANTED, DENIED, HIDDEN }

class AppsViewModel(application: Application) : AndroidViewModel(application) {

    private val appContext = getApplication<Application>().applicationContext
    private val prefs = appContext.getSharedPreferences("app_management_prefs", Context.MODE_PRIVATE)

    private var rawPackages: List<PackageInfo> = emptyList()

    // Monotonic token so overlapping load() passes don't clobber each other (see load()).
    private val loadGeneration = AtomicInteger(0)
    val hiddenPackages: MutableSet<String> =
        (prefs.getStringSet(KEY_HIDDEN, emptySet()) ?: emptySet()).toMutableSet()

    var sortOrder: SortOrder = SortOrder.valueOf(
        prefs.getString(KEY_SORT, SortOrder.NAME_ASC.name) ?: SortOrder.NAME_ASC.name
    )
        private set

    var filterState: FilterState = FilterState.ALL
        private set

    var searchQuery: String = ""
        private set

    private val _packages = MutableLiveData<Resource<List<PackageInfo>>>()
    val packages = _packages as LiveData<Resource<List<PackageInfo>>>

    private val _grantedCount = MutableLiveData<Resource<Int>>()
    val grantedCount = _grantedCount as LiveData<Resource<Int>>

    fun setSortOrder(order: SortOrder) {
        sortOrder = order
        prefs.edit().putString(KEY_SORT, order.name).apply()
        applyFiltersAndSort()
    }

    fun setFilter(state: FilterState) {
        filterState = state
        applyFiltersAndSort()
    }

    fun setSearch(query: String) {
        searchQuery = query
        applyFiltersAndSort()
    }

    fun hidePackage(packageName: String) {
        hiddenPackages.add(packageName)
        prefs.edit().putStringSet(KEY_HIDDEN, hiddenPackages.toSet()).apply()
        applyFiltersAndSort()
    }

    fun unhidePackage(packageName: String) {
        hiddenPackages.remove(packageName)
        prefs.edit().putStringSet(KEY_HIDDEN, hiddenPackages.toSet()).apply()
        applyFiltersAndSort()
    }

    fun refresh() {
        load()
    }

    fun load(onlyCount: Boolean = false) {
        val myGeneration = loadGeneration.incrementAndGet()
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val allPackages = AuthorizationManager.getPackages()

                var granted = 0
                for (pi in allPackages) {
                    val appInfo = pi.applicationInfo ?: continue
                    if (AuthorizationManager.granted(pi.packageName, appInfo.uid)) granted++
                }

                // A newer load() started after this pass began, so its data is fresher — don't let
                // this (possibly stale) pass overwrite it. Overlapping loads are routine here: the
                // RUNNING state listener and onResume() both call load(), so without this guard an
                // earlier pass that computed 0 (server not ready yet, or a just-applied grant not
                // yet propagated) could post AFTER the correct count and wrongly show "0 authorized
                // apps" on the home screen until the list is reopened (issue #317).
                if (myGeneration != loadGeneration.get()) return@launch

                rawPackages = allPackages
                if (!onlyCount || filterState != FilterState.ALL) {
                    applyFiltersAndSort()
                }
                _grantedCount.postValue(Resource.success(granted))

                // A restart that has only just reported RUNNING asks this pass to count before
                // the server has loaded its authorization table: getPackages() answers, granted()
                // still says no, and this pass posts 0. Nothing loads again until the next status
                // transition, so the home card kept reading "已授权 0 个应用" (measured: 0 for 35s+
                // after a heal, back to 4 only on the following restart, while
                // /data/local/tmp/shizuku.json still listed every granted uid). One deferred
                // re-read once the table is surely loaded closes that window; a genuinely empty
                // grant list simply re-posts 0.
                if (granted == 0) {
                    // The permission table can trail the RUNNING signal by several seconds, so a
                    // single deferred read is not enough (measured: a 2.5s re-read still answered
                    // 0 on a fast heal while a slow one already answered 4). Re-read a few times
                    // and stop at the first non-zero answer.
                    for (attempt in 1..3) {
                        delay(3_000)
                        if (myGeneration != loadGeneration.get()) return@launch
                        var again = 0
                        for (pi in allPackages) {
                            val appInfo = pi.applicationInfo ?: continue
                            if (AuthorizationManager.granted(pi.packageName, appInfo.uid)) again++
                        }
                        if (myGeneration != loadGeneration.get()) return@launch
                        _grantedCount.postValue(Resource.success(again))
                        if (again > 0) break
                    }
                }
            } catch (e: CancellationException) {
                // ignore
            } catch (e: Throwable) {
                android.util.Log.e("AppsVM", "load failed", e)
                _packages.postValue(Resource.error(e, null))
                _grantedCount.postValue(Resource.error(e, 0))
            }
        }
    }

    private fun applyFiltersAndSort() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val pm = appContext.packageManager
                var list = rawPackages.filter { pi ->
                    if (filterState == FilterState.HIDDEN) {
                        if (pi.packageName !in hiddenPackages) return@filter false
                    } else {
                        if (pi.packageName in hiddenPackages) return@filter false
                    }

                    val appInfo = pi.applicationInfo
                    val label = appInfo?.loadLabel(pm)?.toString() ?: ""
                    val matchesSearch = searchQuery.isBlank() ||
                        label.contains(searchQuery, ignoreCase = true) ||
                        pi.packageName.contains(searchQuery, ignoreCase = true)
                    val matchesFilter = when (filterState) {
                        FilterState.ALL, FilterState.HIDDEN -> true
                        FilterState.GRANTED -> appInfo != null && runCatching {
                            AuthorizationManager.granted(pi.packageName, appInfo.uid)
                        }.getOrDefault(false)
                        FilterState.DENIED -> {
                            val isGranted = appInfo != null && runCatching {
                                AuthorizationManager.granted(pi.packageName, appInfo.uid)
                            }.getOrDefault(false)
                            // If it's not granted, it's considered denied/pending in this view
                            !isGranted
                        }
                    }
                    matchesSearch && matchesFilter
                }
                list = when (sortOrder) {
                    SortOrder.NAME_ASC -> list.sortedBy {
                        it.applicationInfo?.loadLabel(pm)?.toString()?.lowercase() ?: it.packageName
                    }
                    SortOrder.LAST_INSTALLED -> list.sortedByDescending { it.firstInstallTime }
                    SortOrder.LAST_UPDATED -> list.sortedByDescending { it.lastUpdateTime }
                }
                _packages.postValue(Resource.success(list))
            } catch (e: Throwable) {
                _packages.postValue(Resource.error(e, null))
            }
        }
    }

    companion object {
        private const val KEY_HIDDEN = "hidden_packages"
        private const val KEY_SORT = "app_list_sort_order"
    }
}
