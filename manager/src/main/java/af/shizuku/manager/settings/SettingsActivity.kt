package af.shizuku.manager.settings

import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.appcompat.widget.SearchView
import androidx.fragment.app.Fragment
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import af.shizuku.manager.R
import af.shizuku.core.ui.AppBarFragmentActivity

class SettingsActivity : AppBarFragmentActivity(), PreferenceFragmentCompat.OnPreferenceStartFragmentCallback {

    private lateinit var searchOverlayContainer: FrameLayout
    private lateinit var searchResultsList: RecyclerView
    private lateinit var noResultsText: TextView
    private lateinit var searchAdapter: SearchResultsAdapter
    private var isSearchActive = false

    override fun createFragment(): Fragment = SettingsFragment()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Listen for back stack changes to update title
        supportFragmentManager.addOnBackStackChangedListener {
            if (supportFragmentManager.backStackEntryCount == 0) {
                supportActionBar?.setTitle(R.string.settings_title)
            }
        }

        setupSearchOverlay()
    }

    private fun setupSearchOverlay() {
        val root = findViewById<ViewGroup>(android.R.id.content)
        
        // Create search container
        searchOverlayContainer = FrameLayout(this).apply {
            id = View.generateViewId()
            visibility = View.GONE
            val typedValue = android.util.TypedValue()
            theme.resolveAttribute(android.R.attr.windowBackground, typedValue, true)
            if (typedValue.resourceId != 0) {
                setBackgroundResource(typedValue.resourceId)
            } else {
                setBackgroundColor(typedValue.data)
            }
        }

        // Add RecyclerView for search results
        searchResultsList = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@SettingsActivity)
            val density = resources.displayMetrics.density
            val cardMarginPx = (16 * density).toInt()
            val contentPaddingPx = (8 * density).toInt()
            setPadding(cardMarginPx + contentPaddingPx, (16 * density).toInt(), cardMarginPx + contentPaddingPx, (16 * density).toInt())
            clipToPadding = false
            addItemDecoration(object : af.shizuku.manager.widget.M3ECardItemDecoration(this@SettingsActivity) {
                override fun isHeader(view: View): Boolean = false
            })
        }

        // Add 'No Results' Text View
        noResultsText = TextView(this).apply {
            text = "No settings found"
            textAlignment = View.TEXT_ALIGNMENT_CENTER
            textSize = 18f
            setTextColor(getColor(R.color.system_accent1_400))
            visibility = View.GONE
            val padding = (32 * resources.displayMetrics.density).toInt()
            setPadding(padding, padding, padding, padding)
        }

        searchOverlayContainer.addView(searchResultsList, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        searchOverlayContainer.addView(noResultsText, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))

        // Position it right below the toolbar
        val params = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT).apply {
            topMargin = (56 * resources.displayMetrics.density).toInt() // toolbar standard height
        }
        root.addView(searchOverlayContainer, params)

        searchAdapter = SearchResultsAdapter { item ->
            navigateToSetting(item)
        }
        searchResultsList.adapter = searchAdapter

        // Pre-initialize Settings Index
        SettingsSearchEngine.init(this)
    }

    private fun navigateToSetting(item: SettingsSearchEngine.SettingItem) {
        // Exit search view before navigating
        isSearchActive = false
        searchOverlayContainer.animate().alpha(0f).setDuration(150).withEndAction {
            searchOverlayContainer.visibility = View.GONE
        }.start()

        val fragment = supportFragmentManager.fragmentFactory.instantiate(classLoader, item.fragmentClass)
        fragment.arguments = Bundle().apply {
            putString("highlight_key", item.key)
        }

        supportFragmentManager.beginTransaction()
            .setReorderingAllowed(true)
            .replace(R.id.fragment_container, fragment)
            .addToBackStack(null)
            .commit()

        supportActionBar?.title = item.title
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.settings_menu, menu)
        val searchItem = menu.findItem(R.id.action_search) ?: return super.onCreateOptionsMenu(menu)
        val searchView = searchItem.actionView as? SearchView ?: return super.onCreateOptionsMenu(menu)

        searchView.queryHint = "Search settings…"
        
        searchItem.setOnActionExpandListener(object : MenuItem.OnActionExpandListener {
            override fun onMenuItemActionExpand(item: MenuItem): Boolean {
                isSearchActive = true
                searchOverlayContainer.visibility = View.VISIBLE
                searchOverlayContainer.alpha = 0f
                searchOverlayContainer.animate().alpha(1f).setDuration(250).start()
                return true
            }

            override fun onMenuItemActionCollapse(item: MenuItem): Boolean {
                isSearchActive = false
                searchOverlayContainer.animate().alpha(0f).setDuration(200).withEndAction {
                    searchOverlayContainer.visibility = View.GONE
                }.start()
                return true
            }
        })

        searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?): Boolean {
                return false
            }

            override fun onQueryTextChange(newText: String?): Boolean {
                if (newText.isNullOrBlank()) {
                    searchAdapter.updateItems(emptyList())
                    noResultsText.visibility = View.GONE
                } else {
                    val results = SettingsSearchEngine.search(this@SettingsActivity, newText)
                    searchAdapter.updateItems(results)
                    noResultsText.visibility = if (results.isEmpty()) View.VISIBLE else View.GONE
                }
                return true
            }
        })

        return true
    }

    override fun onPreferenceStartFragment(caller: PreferenceFragmentCompat, pref: Preference): Boolean {
        val fragmentName = pref.fragment ?: return false
        val fragment = supportFragmentManager.fragmentFactory.instantiate(classLoader, fragmentName)
        fragment.arguments = pref.extras

        supportFragmentManager.beginTransaction()
            .setReorderingAllowed(true)
            .replace(R.id.fragment_container, fragment)
            .addToBackStack(null)
            .commit()

        supportActionBar?.title = pref.title
        return true
    }

    override fun onSupportNavigateUp(): Boolean {
        if (supportFragmentManager.backStackEntryCount > 0) {
            supportFragmentManager.popBackStack()
            return true
        }
        return super.onSupportNavigateUp()
    }

    // Inner Adapter Class for Search Results
    private class SearchResultsAdapter(
        private val onClick: (SettingsSearchEngine.SettingItem) -> Unit
    ) : RecyclerView.Adapter<SearchResultViewHolder>() {

        private var items = listOf<SettingsSearchEngine.SettingItem>()

        fun updateItems(newItems: List<SettingsSearchEngine.SettingItem>) {
            items = newItems
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SearchResultViewHolder {
            val view = android.view.LayoutInflater.from(parent.context)
                .inflate(R.layout.item_settings_search_result, parent, false)
            return SearchResultViewHolder(view)
        }

        override fun onBindViewHolder(holder: SearchResultViewHolder, position: Int) {
            val item = items[position]
            holder.bind(item, onClick)
        }

        override fun getItemCount(): Int = items.size
    }

    private class SearchResultViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val categoryText = view.findViewById<TextView>(R.id.search_result_category)
        private val titleText = view.findViewById<TextView>(R.id.search_result_title)
        private val summaryText = view.findViewById<TextView>(R.id.search_result_summary)

        fun bind(item: SettingsSearchEngine.SettingItem, onClick: (SettingsSearchEngine.SettingItem) -> Unit) {
            categoryText.text = item.category ?: "Settings"
            titleText.text = item.title
            
            if (item.summary.isNullOrEmpty()) {
                summaryText.visibility = View.GONE
            } else {
                summaryText.visibility = View.VISIBLE
                summaryText.text = item.summary
            }

            itemView.setOnClickListener { onClick(item) }
        }
    }
}
