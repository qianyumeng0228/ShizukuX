package af.shizuku.manager.settings

import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import android.util.AttributeSet
import androidx.core.view.ViewCompat
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceManager
import androidx.preference.PreferenceViewHolder
import androidx.preference.TwoStatePreference
import af.shizuku.manager.R

class CollapsiblePreferenceCategory @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : PreferenceCategory(context, attrs) {

    private var expanded = false
    private var isAnimating = false
    private var lastToggleStart = 0L
    var onExpansionChanged: ((Boolean) -> Unit)? = null

    private var defaultExpanded = false
    private var collapsible = true

    // Keys of children that are conditionally unavailable (e.g. hidden by an OWNER fragment
    // based on OS version or another setting). Such children must stay hidden even when the
    // category is expanded, and the collapse toggle must not resurrect them.
    private val unavailableChildKeys = mutableSetOf<String>()

    // Re-evaluated on each shared-prefs change while attached: when a dependency switch flips,
    // dependents must appear/disappear immediately rather than waiting for a manual expand cycle.
    private var prefsListener: SharedPreferences.OnSharedPreferenceChangeListener? = null

    init {
        layoutResource = R.layout.collapsible_preference_category_card

        val a = context.obtainStyledAttributes(attrs, intArrayOf(android.R.attr.defaultValue, R.attr.collapsible))
        defaultExpanded = a.getBoolean(0, false)
        collapsible = a.getBoolean(1, true)
        a.recycle()

        // A non-collapsible category (e.g. a top-level nav menu) still needs the M3E card-grouping
        // treatment from isHeader()/tag, but must never hide its own entries - there'd be no other
        // way back to them.
        expanded = if (collapsible) defaultExpanded else true
        isSelectable = collapsible
    }

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)

        holder.itemView.tag = "category_header"

        val arrow = holder.findViewById(R.id.category_arrow)
        if (!collapsible) {
            arrow?.visibility = android.view.View.GONE
            holder.itemView.setOnClickListener(null)
            holder.itemView.isClickable = false
            return
        }

        // Cancel any in-flight animator before snapping to current state on rebind —
        // otherwise a running ViewPropertyAnimator takes priority over the direct rotation setter
        // and can leave the arrow pointing the wrong way for the actual expanded state.
        arrow?.animate()?.cancel()
        // Self-heal: RecyclerView rebinds can cancel the arrow animation via the line above.
        // ViewPropertyAnimator.cancel() normally runs withEndAction, but when the cancel races
        // with the animation's start frame it is not guaranteed; a stale isAnimating=true would
        // then swallow every later click (the reported "second tap does nothing"). Force-release
        // here so the guard below only ever throttles genuine double-taps.
        isAnimating = false
        arrow?.rotation = if (expanded) 180f else 0f
        updateExpandedStateDescription(holder.itemView)

        holder.itemView.setOnClickListener {
            // Guard against fast double-taps: a second tap before the arrow finishes rotating
            // would flip `expanded` twice and leave the arrow snapped to the wrong angle. The
            // time window makes the guard self-healing - if an animation is ever stuck (cancelled
            // without withEndAction), clicks resume once 400 ms has passed instead of being
            // swallowed forever.
            if (isAnimating && SystemClock.elapsedRealtime() - lastToggleStart < 400L) return@setOnClickListener
            expanded = !expanded
            if (shouldPersist()) persistBoolean(expanded)
            // Set the flag synchronously instead of in withStartAction so the double-tap guard
            // is active from the instant of the tap, not from the animation's first frame.
            isAnimating = true
            lastToggleStart = SystemClock.elapsedRealtime()
            // Animate arrow with M3E spring-style motion
            arrow?.animate()
                ?.rotation(if (expanded) 180f else 0f)
                ?.setDuration(af.shizuku.manager.ShizukuSettings.scaledAnimationDuration(300))
                ?.setInterpolator(android.view.animation.OvershootInterpolator(0.8f))
                ?.withEndAction { isAnimating = false }
                ?.start()
            // updateChildren() already notifies the adapter per child via Preference.setVisible();
            // an additional notifyChanged() here used to schedule a rebind of this same header
            // ViewHolder mid-animation, which snapped the arrow's rotation back and forth against
            // the running ViewPropertyAnimator and could leave the RecyclerView's sync pass
            // needing a second click to fully settle on the expanded child list.
            updateChildren()
            updateExpandedStateDescription(holder.itemView)
            onExpansionChanged?.invoke(expanded)
        }
    }

    /** The rotating arrow is the only visual cue this header is an expand/collapse toggle -
     *  TalkBack users get neither that affordance nor a state change announcement without this. */
    private fun updateExpandedStateDescription(itemView: android.view.View) {
        ViewCompat.setStateDescription(
            itemView,
            itemView.context.getString(
                if (expanded) R.string.accessibility_state_expanded else R.string.accessibility_state_collapsed
            )
        )
    }

    fun isExpanded() = expanded

    fun setExpanded(expanded: Boolean) {
        if (this.expanded != expanded) {
            this.expanded = expanded
            if (shouldPersist()) persistBoolean(expanded)
            updateChildren()
            onExpansionChanged?.invoke(expanded)
            notifyChanged()
        }
    }

    /**
     * Declare whether a child should participate in the expand/collapse cycle. A child marked
     * unavailable stays hidden regardless of the expanded state. Owners should call this instead
     * of setting the child's [Preference.isVisible] directly, so the collapse toggle does not
     * override the condition.
     */
    fun setChildAvailable(key: String, available: Boolean) {
        val changed = if (available) unavailableChildKeys.remove(key) else unavailableChildKeys.add(key)
        if (changed) updateChildren()
    }

    private fun updateChildren() {
        for (i in 0 until preferenceCount) {
            val child = getPreference(i)
            child.isVisible = expanded &&
                !unavailableChildKeys.contains(child.key) &&
                dependencySatisfied(child)
        }
    }

    /** androidx treats `android:dependency` as disabling (greying) the dependent, not hiding it.
     *  Inside a collapsible card we instead hide dependents whose dependency is not met, so
     *  expanding never resurrects a row belonging to a disabled switch. This mirrors the
     *  framework bookkeeping: child.mDependencyMet == !dependency.shouldDisableDependents(),
     *  where a TwoStatePreference disables dependents when unchecked and a plain Preference
     *  when disabled.
     *
     *  The shared-prefs value is authoritative rather than dep.isChecked because the owning
     *  fragment's onPreferenceChangeListener persists the new value to SharedPreferences
     *  BEFORE SwitchPreferenceCompat.setChecked() updates mChecked. Our own
     *  OnSharedPreferenceChangeListener then fires with dep.isChecked still holding the OLD
     *  value; the second persist (inside setChecked) writes an unchanged value and does not
     *  notify again, so reading mChecked would freeze the dependent in the WRONG visibility
     *  (inverted dependency behavior). Reading the already-updated SP value avoids the race.
     *  The in-memory Preference is still consulted when the key was never persisted. */
    private fun dependencySatisfied(child: Preference): Boolean {
        val depKey = child.dependency ?: return true
        val sp = preferenceManager?.sharedPreferences
        if (sp != null && sp.contains(depKey)) {
            return sp.getBoolean(depKey, false)
        }
        var dep: Preference? = findPreference<Preference>(depKey)
        if (dep == null) {
            dep = preferenceManager?.preferenceScreen?.findPreference<Preference>(depKey)
        }
        val resolved = dep ?: return true
        return if (resolved is TwoStatePreference) resolved.isChecked else resolved.isEnabled
    }

    private fun childDependencyKeys(): Set<String> {
        val keys = mutableSetOf<String>()
        for (i in 0 until preferenceCount) {
            getPreference(i).dependency?.let { keys.add(it) }
        }
        return keys
    }

    override fun onAttachedToHierarchy(preferenceManager: PreferenceManager) {
        super.onAttachedToHierarchy(preferenceManager)
        // Restore persisted state if we have a key, otherwise use defaultValue. A non-collapsible
        // category always stays expanded regardless, even if a key is added later.
        if (collapsible && shouldPersist()) {
            expanded = getPersistedBoolean(defaultExpanded)
        }
        updateChildren()
        // React to dependency switches toggling while expanded (see field comment). Observing
        // shared-prefs writes — instead of setOnPreferenceChangeListener — never clobbers the
        // fragment's own listeners on the same switch.
        if (prefsListener == null) {
            val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
                if (key != null && childDependencyKeys().contains(key)) {
                    updateChildren()
                }
            }
            prefsListener = listener
            preferenceManager.sharedPreferences?.registerOnSharedPreferenceChangeListener(listener)
        }
    }

    override fun onDetached() {
        super.onDetached()
        prefsListener?.let { preferenceManager?.sharedPreferences?.unregisterOnSharedPreferenceChangeListener(it) }
        prefsListener = null
    }

    override fun addPreference(preference: Preference): Boolean {
        val result = super.addPreference(preference)
        if (result) {
            preference.isVisible = expanded &&
                !unavailableChildKeys.contains(preference.key) &&
                dependencySatisfied(preference)
        }
        return result
    }
}
