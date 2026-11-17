package af.shizuku.manager.management

import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageInfo
import android.view.View

/**
 * What an app row needs from whoever is showing it.
 *
 * Before the apps page could be hosted anywhere, a row found its host by casting the context to
 * `ApplicationManagementActivity` (and to its `Callbacks`). That cast silently returned null once
 * the page moved into the single container, which killed the two things a row cannot do itself:
 * the "hidden" action in the long-press menu and the TalkBack equivalents of the swipe gestures.
 * An interface the host implements keeps both working without the row needing to know which
 * activity it lives in.
 */
interface AppRowHost {
    /**
     * Whether the list is currently filtered to hidden apps. The long-press hide entry flips to
     * "restore" under that filter, so a row has to know which wording applies before it builds
     * the menu instead of after the tap. Only the container's app list can be in that state;
     * every other host answers false.
     */
    val filterIsHidden: Boolean
        get() = false
    fun onHideApp(packageName: String)
    fun onRowSwipe(item: PackageInfo, action: String, itemView: View)
}

/** Walks the context chain: a row's context is usually the RecyclerView's wrapper, not the host. */
fun Context.findAppRowHost(): AppRowHost? {
    var c: Context? = this
    while (c is ContextWrapper) {
        if (c is AppRowHost) return c
        c = c.baseContext
    }
    return c as? AppRowHost
}
