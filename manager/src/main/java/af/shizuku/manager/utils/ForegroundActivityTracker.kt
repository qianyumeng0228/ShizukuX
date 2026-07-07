package af.shizuku.manager.utils

import android.app.Activity
import android.app.Application
import android.os.Bundle
import java.lang.ref.WeakReference

/**
 * Tracks the currently-resumed Activity via a weak reference, so code that runs off the UI
 * layer (e.g. a Sentry EventProcessor capturing a crash on a background thread) can still find
 * "what was on screen" without needing its own plumbing through every Activity.
 */
object ForegroundActivityTracker {
    private var current: WeakReference<Activity>? = null

    val currentActivity: Activity?
        get() = current?.get()

    fun register(application: Application) {
        application.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) {
                current = WeakReference(activity)
            }

            override fun onActivityPaused(activity: Activity) {
                if (current?.get() === activity) current = null
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityStarted(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
    }
}
