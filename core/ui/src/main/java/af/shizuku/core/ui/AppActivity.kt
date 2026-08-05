package af.shizuku.core.ui

import android.animation.AnimatorListenerAdapter
import android.animation.ObjectAnimator
import android.content.res.Resources.Theme
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.Window
import android.view.animation.LinearInterpolator
import androidx.activity.enableEdgeToEdge
import rikka.material.app.MaterialActivity

abstract class AppActivity : MaterialActivity() {

    companion object {
        // recreate() doesn't go through the normal Intent/ActivityOptions handshake that
        // drives a scene transition, so the incoming instance's enter transition never gets
        // played/completed and its content stays invisible - a black screen until the user
        // backs out. setWindowAnimations(0) in recreateWithoutTransition() only suppresses
        // the legacy window-animation style, not FEATURE_ACTIVITY_TRANSITIONS, so this flag is
        // the only thing that actually skips content transitions on relaunch. Protected (not
        // private) because AppBarActivity sets its own MaterialSharedAxis transitions and must
        // honor the same suppression - see AppBarActivity.onCreate().
        @JvmStatic
        protected var suppressTransitionOnCreate = false

        // A snapshot of the outgoing UI, captured just before a themed recreate() and shown as a
        // full-screen overlay on the incoming instance, then crossfaded out. This masks the brief
        // black frame the window shows while recreate() tears down and rebuilds the surface, so a
        // theme/accent/night-mode change fades smoothly instead of flashing black. Static so it
        // survives across the recreate() (which keeps the process alive).
        @JvmStatic
        private var recreateSnapshot: Bitmap? = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        if (!suppressTransitionOnCreate) {
            try {
                window.requestFeature(Window.FEATURE_ACTIVITY_TRANSITIONS)
                window.enterTransition = android.transition.Explode()
                window.exitTransition = android.transition.Explode()
            } catch (_: Exception) {
            }
        }
        suppressTransitionOnCreate = false
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
    }

    override fun onPostCreate(savedInstanceState: Bundle?) {
        super.onPostCreate(savedInstanceState)
        // Runs after the subclass's setContentView/setContent but before the first frame is drawn,
        // so the crossfade overlay is in place before any black frame can show.
        val snapshot = recreateSnapshot ?: return
        recreateSnapshot = null

        val decorView = window.decorView
        val drawable = BitmapDrawable(resources, snapshot)
        try {
            // Add to the decor view's overlay, which paints above ALL view content including
            // hardware-rendered (RenderNode-backed) Compose layers. Adding as a sibling inside
            // android.R.id.content doesn't work for Compose activities — Compose's hardware
            // rendering composites above software-canvas siblings regardless of z-order.
            drawable.setBounds(0, 0, decorView.width, decorView.height)
            decorView.overlay.add(drawable)
            decorView.post {
                // ObjectAnimator instead of View.animate(): we're fading a Drawable, not a View.
                ObjectAnimator.ofInt(drawable, "alpha", 255, 0).apply {
                    duration = 220L
                    interpolator = LinearInterpolator()
                    addListener(object : AnimatorListenerAdapter() {
                        override fun onAnimationEnd(animation: android.animation.Animator) {
                            decorView.overlay.remove(drawable)
                            if (!snapshot.isRecycled) snapshot.recycle()
                        }
                    })
                    start()
                }
            }
        } catch (_: Throwable) {
            decorView.overlay.remove(drawable)
            if (!snapshot.isRecycled) snapshot.recycle()
        }
    }

    override fun computeUserThemeKey(): String {
        return ThemeDelegateManager.getDelegate().getThemeKey(this)
    }

    override fun onApplyUserThemeResource(theme: Theme, isDecorView: Boolean) {
        ThemeDelegateManager.getDelegate().onApplyUserThemeResource(this, theme, isDecorView)
    }

    override fun onSupportNavigateUp(): Boolean {
        if (!super.onSupportNavigateUp()) {
            finish()
        }
        return true
    }

    /**
     * [recreate] tears down and rebuilds the window; combined with the Explode
     * enter/exit transitions requested in onCreate, the incoming instance's enter
     * transition never resolves (recreate() skips the ActivityOptions handshake that
     * normally drives it), leaving the screen black until the user backs out. Suppressing
     * the transitions on the next onCreate, plus disabling the legacy window animation,
     * avoids the stuck-black screen; capturing a snapshot to crossfade over the rebuild
     * (see [onPostCreate]) hides the brief black flash the surface swap otherwise shows.
     *
     * On API 26+ the snapshot is captured via [PixelCopy] which properly captures
     * hardware-accelerated (RenderNode) content — Compose UI included. [recreate] is
     * deferred until the capture completes (typically < 1 frame / ~16ms).
     */
    fun recreateWithoutTransition() {
        suppressTransitionOnCreate = true
        window.setWindowAnimations(0)
        recreateSnapshot?.let { if (!it.isRecycled) it.recycle() }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val view = window.decorView
            if (view.width > 0 && view.height > 0) {
                val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
                android.view.PixelCopy.request(window, bitmap, { result ->
                    recreateSnapshot = if (result == android.view.PixelCopy.SUCCESS) {
                        bitmap
                    } else {
                        bitmap.recycle()
                        null
                    }
                    recreate()
                }, Handler(Looper.getMainLooper()))
                return
            }
            // View not laid out yet (shouldn't happen mid-session, but fall through)
        } else {
            recreateSnapshot = captureWindowSnapshot()
        }
        recreate()
    }

    private fun captureWindowSnapshot(): Bitmap? {
        return try {
            val view: View = window.decorView
            if (view.width <= 0 || view.height <= 0) return null
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            bitmap
        } catch (_: Throwable) {
            null
        }
    }
}
