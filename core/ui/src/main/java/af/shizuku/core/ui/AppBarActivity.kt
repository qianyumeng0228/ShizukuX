package af.shizuku.core.ui

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import androidx.annotation.LayoutRes
import androidx.appcompat.widget.Toolbar
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.fragment.app.Fragment
import com.google.android.material.appbar.AppBarLayout
import com.google.android.material.transition.platform.MaterialSharedAxis
import rikka.core.ktx.unsafeLazy
import timber.log.Timber

abstract class AppBarActivity : AppActivity() {

    protected val rootView: ViewGroup by unsafeLazy {
        findViewById<View>(R.id.coordinator_root) as? ViewGroup
            ?: throw IllegalStateException("rootView not found - make sure layout contains coordinator_root")
    }

    protected var toolbarContainer: AppBarLayout? = null
    protected var toolbar: Toolbar? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        // Same recreate()-vs-content-transition hazard as AppActivity's Explode() transitions
        // (see its companion object comment) - must honor the same suppression flag or a
        // recreateWithoutTransition() call from any AppBarActivity subclass gets a black
        // screen stuck behind these MaterialSharedAxis transitions instead.
        if (!suppressTransitionOnCreate) {
            val axis = MaterialSharedAxis.X
            window.enterTransition = MaterialSharedAxis(axis, true)
            window.exitTransition = MaterialSharedAxis(axis, false)
            window.reenterTransition = MaterialSharedAxis(axis, false)
            window.returnTransition = MaterialSharedAxis(axis, true)
        } else {
            window.enterTransition = null
            window.exitTransition = null
            window.reenterTransition = null
            window.returnTransition = null
        }

        super.onCreate(savedInstanceState)
        super.setContentView(getLayoutId())
    }

    override fun onPostCreate(savedInstanceState: Bundle?) {
        super.onPostCreate(savedInstanceState)
        try {
            val container = findViewById<View>(R.id.toolbar_container) as? AppBarLayout
            val bar = findViewById<View>(R.id.toolbar) as? Toolbar

            if (container != null && bar != null) {
                toolbarContainer = container
                toolbar = bar
                setSupportActionBar(toolbar)

                // These screens are always reached from a parent page, and their onCreate calls
                // setDisplayHomeAsUpEnabled(true) *before* this method installs the toolbar - at
                // that point supportActionBar is still null, so the call silently did nothing and
                // the toolbar came up without a back arrow. Enable it here instead, now that the
                // ActionBar actually exists.
                if (showsUpNavigation) {
                    supportActionBar?.setDisplayHomeAsUpEnabled(true)
                }

                // Glass skin: the bar used to paint ?android:colorBackground, i.e. an opaque
                // near-black strip on top of an otherwise gradient page, so these XML pages read
                // as "glass cards on a foreign chrome". A downward-fading scrim keeps the title
                // and the search field readable (the list does scroll behind this bar) while the
                // page gradient still shows through.
                if (af.shizuku.core.ui.compose.isGlassTheme(this)) {
                    val night = (resources.configuration.uiMode and
                            android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                            android.content.res.Configuration.UI_MODE_NIGHT_YES
                    container.background = androidx.core.content.res.ResourcesCompat.getDrawable(
                        resources,
                        if (night) R.drawable.glass_appbar_scrim else R.drawable.glass_appbar_scrim_light,
                        theme
                    )
                    container.isLiftOnScroll = false
                }

                androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(container) { v, insets ->
                    val bars = insets.getInsets(
                        androidx.core.view.WindowInsetsCompat.Type.systemBars() or
                        androidx.core.view.WindowInsetsCompat.Type.displayCutout()
                    )
                    v.setPadding(bars.left, bars.top, bars.right, 0)
                    insets
                }
            } else {
                Timber.tag("AppBarActivity").w("Toolbar or container not found in layout.")
            }
        } catch (e: Exception) {
            Timber.tag("AppBarActivity").w(e, "Failed to initialize toolbar")
        }
    }

    @LayoutRes
    open fun getLayoutId(): Int {
        return R.layout.appbar_activity
    }

    /** Whether the toolbar should carry the standard back arrow. Off for root-level screens. */
    protected open val showsUpNavigation: Boolean = true

    override fun setContentView(layoutResID: Int) {
        val view = layoutInflater.inflate(layoutResID, rootView, false)
        setContentView(view)
    }

    override fun setContentView(view: View?) {
        setContentView(view, CoordinatorLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    override fun setContentView(view: View?, params: ViewGroup.LayoutParams?) {
        val p = if (params is CoordinatorLayout.LayoutParams) {
            params
        } else {
            CoordinatorLayout.LayoutParams(params ?: ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }

        if (p.behavior == null) {
            p.behavior = AppBarLayout.ScrollingViewBehavior()
        }

        rootView.addView(view, p)
    }

}

abstract class AppBarFragmentActivity : AppBarActivity() {

    abstract fun createFragment(): Fragment

    override fun getLayoutId(): Int = R.layout.appbar_fragment_activity

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .add(R.id.fragment_container, createFragment())
                .commit()
        }
    }

}
