package af.shizuku.manager.home

import android.os.Bundle
import android.view.MenuItem
import androidx.fragment.app.Fragment
import androidx.viewpager2.adapter.FragmentStateAdapter
import af.shizuku.manager.R
import af.shizuku.manager.databinding.ActivitySystemHubBinding
import af.shizuku.feature.activitylog.ActivityLogFragment
import com.google.android.material.tabs.TabLayoutMediator
import af.shizuku.core.ui.AppActivity
import af.shizuku.core.ui.AppBarActivity

class SystemHubActivity : AppActivity() {

    private lateinit var binding: ActivitySystemHubBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySystemHubBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "System Hub"

        val adapter = HubPagerAdapter(this)
        binding.viewPager.adapter = adapter

        TabLayoutMediator(binding.tabLayout, binding.viewPager) { tab, position ->
            tab.text = when (position) {
                0 -> "Metrics"
                1 -> "Activity Log"
                else -> null
            }
        }.attach()
    }

    private class HubPagerAdapter(activity: AppActivity) : FragmentStateAdapter(activity) {
        override fun getItemCount() = 2
        override fun createFragment(position: Int): Fragment = when (position) {
            0 -> ServerMetricsFragment()
            1 -> ActivityLogFragment()
            else -> throw IllegalStateException()
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }
}
