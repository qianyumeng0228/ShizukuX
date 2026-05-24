package af.shizuku.manager.activitylog

import android.os.Bundle
import af.shizuku.core.ui.AppBarFragmentActivity
import androidx.fragment.app.Fragment

class ActivityLogActivity : AppBarFragmentActivity() {
    override fun createFragment(): Fragment = ActivityLogFragment()
}
