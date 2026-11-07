package af.shizuku.manager.home

import af.shizuku.manager.R
import af.shizuku.manager.databinding.HomeItemContainerBinding
import af.shizuku.manager.databinding.HomeShizukuCompanionBinding
import af.shizuku.manager.migration.MigrationHelper
import af.shizuku.manager.utils.IconStyleHelper
import af.shizuku.manager.utils.StockShizukuCompat
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.topjohnwu.superuser.Shell
import rikka.core.content.asActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rikka.recyclerview.BaseViewHolder
import rikka.recyclerview.BaseViewHolder.Creator
import rikka.shizuku.Shizuku
import timber.log.Timber

class ShizukuCompanionViewHolder(
    private val binding: HomeShizukuCompanionBinding,
    private val containerBinding: HomeItemContainerBinding,
    private val scope: CoroutineScope,
    private val homeModel: HomeViewModel,
) : BaseViewHolder<Pair<Boolean, Boolean>>(containerBinding.root) {

    companion object {
        fun creator(scope: CoroutineScope, homeModel: HomeViewModel): Creator<Pair<Boolean, Boolean>> {
            return Creator { inflater: LayoutInflater, parent: ViewGroup? ->
                val outer = HomeItemContainerBinding.inflate(inflater, parent, false)
                val inner = HomeShizukuCompanionBinding.inflate(inflater, outer.cardContent, true)
                ShizukuCompanionViewHolder(inner, outer, scope, homeModel)
            }
        }
    }

    /**
     * Runs [cmd] via Shizuku if available, falling back to root. Returns true on success.
     * Centralizes the Shizuku-then-root fallback so install/disable don't duplicate it.
     */
    private suspend fun runPrivilegedCommand(cmd: String): Boolean = withContext(Dispatchers.IO) {
        // Callers launch via HomeActivity's Main-dispatched coroutine scope - process.waitFor()
        // and Shell.exec() are both blocking IPC/subprocess waits, so without this dispatch
        // they'd block the main thread for the duration of the command (ANR - SHIZUKUPLUS-7H/7P).
        if (Shizuku.pingBinder()) {
            var process: Process? = null
            try {
                process = Shizuku.newProcess(arrayOf("sh", "-c", cmd), null, null)
                process.waitFor() == 0
            } catch (e: Exception) {
                false
            } finally {
                // waitFor() can throw if the binder dies mid-command; without the finally the
                // process handle (and its pipes) leaks on that path.
                try { process?.destroy() } catch (_: Exception) {}
            }
        } else if (MigrationHelper.isRootAvailable()) {
            try {
                Shell.cmd(cmd).exec().isSuccess
            } catch (e: Exception) {
                false
            }
        } else {
            false
        }
    }

    private val originalIcon = binding.icon.drawable

    /**
     * True when the package currently occupying [StockShizukuCompat.PACKAGE] is our own compat hub
     * (not genuine stock Shizuku). Only then may the uninstall button remove it directly: forcing
     * `pm uninstall` on a real stock Shizuku install would destroy another app's data and the
     * user's running service, so that path keeps opening the system uninstall page.
     */
    private var uninstallHubDirectly = false

    init {
        containerBinding.root.setOnLongClickListener {
            HomeEditMode.enter()
            true
        }
        containerBinding.dragHandle.apply {
            setOnTouchListener { _, event ->
                if (event.action == MotionEvent.ACTION_DOWN) {
                    HomeEditMode.startDragCallback?.invoke(
                        this@ShizukuCompanionViewHolder
                    )
                }
                false
            }
            setOnLongClickListener {
                HomeEditMode.enter()
                true
            }
        }

        binding.button1.setOnClickListener { v ->
            af.shizuku.manager.utils.HapticUtils.tap(v)
            val companionInstalled = data?.first ?: false
            if (companionInstalled) {
                setBusy(v.context, R.string.companion_action_disabling)
                scope.launch {
                    val success = runPrivilegedCommand("pm disable-user --user 0 ${StockShizukuCompat.PACKAGE}")
                    withContext(Dispatchers.Main) {
                        Toast.makeText(
                            v.context,
                            if (success) R.string.companion_disable_success else R.string.companion_disable_failure,
                            Toast.LENGTH_SHORT
                        ).show()
                        homeModel.reload()
                    }
                }
            } else if (v.context.packageName == StockShizukuCompat.PACKAGE) {
                // The dropin flavor's own applicationId IS StockShizukuCompat.PACKAGE, and the compat
                // shim APK targets that same package with the same signing key — installing it here
                // would silently pm-install the shim stub over this running app (#334). HomeAdapter
                // shouldn't route dropin builds into this branch (isCompatAppInstalled() now treats
                // self as already occupying the role), but this is the hard stop that actually
                // prevents the destructive install regardless of how this click was reached.
                Toast.makeText(v.context, R.string.compat_hub_install_fail, Toast.LENGTH_SHORT).show()
            } else if (StockShizukuCompat.isPackageOccupiedByDifferentSigner(v.context)) {
                // #412: pm install -r always fails with INSTALL_FAILED_UPDATE_INCOMPATIBLE here
                // (opaquely, before this check existed) - something else, e.g. genuine stock
                // Shizuku, already occupies moe.shizuku.privileged.api with a different cert.
                Toast.makeText(v.context, R.string.compat_hub_install_signature_conflict, Toast.LENGTH_LONG).show()
            } else {
                // Must be on external storage, not the app's private cache/files dir: `pm install`
                // runs via a shell process spawned by Shizuku.newProcess (UID 2000) or root, neither
                // of which can read /data/user/0/<pkg>/... due to per-app UID sandboxing on internal
                // storage. getExternalFilesDir is readable by shell/root and is the same location
                // UpdateManager/UpdateInstaller already use successfully for APK installs.
                val dir = v.context.getExternalFilesDir(null) ?: v.context.cacheDir ?: v.context.filesDir ?: return@setOnClickListener
                val tmpApk = java.io.File(dir, "compat.apk")

                setBusy(v.context, R.string.compat_hub_installing)
                scope.launch {
                    val extracted = withContext(Dispatchers.IO) {
                        try {
                            v.context.assets.open("compat.apk").use { input ->
                                tmpApk.outputStream().use { output ->
                                    input.copyTo(output)
                                }
                            }
                            true
                        } catch (e: Exception) {
                            false
                        }
                    }
                    if (!extracted) {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(v.context, R.string.compat_hub_install_fail, Toast.LENGTH_SHORT).show()
                            homeModel.reload()
                        }
                        return@launch
                    }

                    val tmpPath = "/data/local/tmp/compat.apk"
                    // Wired straight to a Boolean previously (#412 "compat hub not installing" had
                    // zero diagnostic output) - pm install's actual stderr (e.g.
                    // INSTALL_FAILED_UPDATE_INCOMPATIBLE from a signing-cert mismatch if something
                    // else already occupies moe.shizuku.privileged.api) is now logged so a failure
                    // is self-diagnosing from logcat/Sentry instead of a bare "failed" toast.
                    val installCmd = "cp '${tmpApk.absolutePath}' '$tmpPath' && chmod 644 '$tmpPath' && pm install -r '$tmpPath' 2>&1; echo EXIT:$?; rm -f '$tmpPath'"
                    val installOutput = withContext(Dispatchers.IO) {
                        if (Shizuku.pingBinder()) {
                            try {
                                val process = Shizuku.newProcess(arrayOf("sh", "-c", installCmd), null, null)
                                try {
                                    process.inputStream.bufferedReader().readText().also { process.waitFor() }
                                } finally {
                                    try { process.destroy() } catch (_: Exception) {}
                                }
                            } catch (e: Exception) {
                                e.message ?: "unknown error"
                            }
                        } else if (MigrationHelper.isRootAvailable()) {
                            try {
                                Shell.cmd(installCmd).exec().out.joinToString("\n")
                            } catch (e: Exception) {
                                e.message ?: "unknown error"
                            }
                        } else {
                            "no privileged access available"
                        }
                    }
                    val success = installOutput.contains("EXIT:0")
                    if (!success) {
                        Timber.tag("ShizukuCompanion").e("compat hub install failed: %s", installOutput.take(1000))
                    }
                    try {
                        tmpApk.delete()
                    } catch (e: Exception) {
                        // ignore
                    }
                    withContext(Dispatchers.Main) {
                        val messageRes = when {
                            success -> R.string.compat_hub_install_success
                            installOutput.contains("INSTALL_FAILED_INSUFFICIENT_STORAGE") ->
                                R.string.compat_hub_install_fail_storage
                            installOutput.contains("INSTALL_FAILED_NO_MATCHING_ABIS") ->
                                R.string.compat_hub_install_fail_abi
                            else -> R.string.compat_hub_install_fail
                        }
                        Toast.makeText(v.context, messageRes, Toast.LENGTH_SHORT).show()
                        homeModel.reload()
                    }
                }
            }
        }
        binding.button2.setOnClickListener { v ->
            af.shizuku.manager.utils.HapticUtils.tap(v)
            if (uninstallHubDirectly) {
                val activity = v.context.asActivity<android.app.Activity>()
                if (activity == null) {
                    // Never fail silently: if the host context chain has no Activity the dialog
                    // cannot be shown, so surface the failure instead of swallowing the tap.
                    Toast.makeText(v.context, R.string.compat_hub_uninstall_failure, Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                MaterialAlertDialogBuilder(activity)
                    .setTitle(R.string.compat_hub_uninstall_confirm_title)
                    .setMessage(R.string.compat_hub_uninstall_confirm_message)
                    .setPositiveButton(R.string.compat_hub_uninstall_confirm_action) { _, _ ->
                        val context = v.context
                        setHubUninstallBusy(context)
                        scope.launch {
                            // The hub is a SEPARATE package installed by us via pm; Android never
                            // uninstalls it together with ShizukuX. Removing it here (through
                            // Shizuku/root) is the only in-app path that actually deletes it -
                            // ACTION_DELETE only opens the system page and, once ShizukuX itself
                            // is gone, this card no longer exists to offer either.
                            val success = runPrivilegedCommand(
                                "pm uninstall --user 0 ${StockShizukuCompat.PACKAGE}"
                            )
                            withContext(Dispatchers.Main) {
                                Toast.makeText(
                                    context,
                                    if (success) R.string.compat_hub_uninstall_success
                                    else R.string.compat_hub_uninstall_failure,
                                    Toast.LENGTH_SHORT
                                ).show()
                                binding.button2.isEnabled = true
                                binding.button2.setText(R.string.compat_hub_uninstall_btn)
                                homeModel.reload()
                            }
                        }
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
                return@setOnClickListener
            }
            val intent = Intent(Intent.ACTION_DELETE)
            intent.data = Uri.parse("package:${StockShizukuCompat.PACKAGE}")
            v.context.startActivity(intent)
        }
    }

    /** Prevents a double tap from firing two pm uninstall calls while the first one runs. */
    private fun setHubUninstallBusy(context: Context) {
        binding.button2.isEnabled = false
        binding.button2.setText(R.string.compat_hub_uninstalling)
    }

    /** Disables the action button and shows a busy label so a slow pm call can't be double-tapped. */
    private fun setBusy(context: Context, labelRes: Int) {
        binding.button1.isEnabled = false
        binding.button1.text = context.getString(labelRes)
    }

    override fun onBind() {
        val companionInstalled = data?.first ?: false
        val compatHubInstalled = data?.second ?: false

        if (compatHubInstalled && !companionInstalled) {
            binding.title.setText(R.string.compat_hub_installed_title)
            binding.text1.setText(R.string.compat_hub_installed_desc)
            binding.button1.visibility = View.GONE
            // Compat Hub installed with no stock Shizuku conflict: the only useful action is
            // removing the leftover hub, so surface button2 (direct pm uninstall) here.
            // Drop-in builds ARE moe.shizuku.privileged.api (no separate hub) — hide the
            // button there so it never offers to uninstall the running app itself.
            val selfIsHub = binding.root.context.packageName == StockShizukuCompat.PACKAGE
            // A real hub (not the dropin build, whose own id IS the stock package) can be removed
            // in place; a genuine stock Shizuku must not be force-uninstalled from here.
            uninstallHubDirectly = !selfIsHub
            binding.button2.setText(R.string.compat_hub_uninstall_btn)
            binding.button2.visibility = if (selfIsHub) View.GONE else View.VISIBLE
        } else if (companionInstalled) {
            uninstallHubDirectly = false
            binding.title.setText(R.string.companion_conflict_title)
            binding.text1.setText(R.string.companion_conflict_description)
            binding.button1.setText(R.string.companion_action_disable)
            binding.button1.isEnabled = true
            binding.button1.visibility = View.VISIBLE
            binding.button2.visibility = View.VISIBLE
        } else {
            uninstallHubDirectly = false
            binding.title.setText(R.string.compat_hub_missing_title)
            binding.text1.setText(R.string.compat_hub_missing_desc)
            binding.button1.setText(R.string.compat_hub_install_btn)
            binding.button1.isEnabled = true
            binding.button1.visibility = View.VISIBLE
            binding.button2.visibility = View.GONE
        }
        HomeEditMode.applyOverlay(containerBinding)
        IconStyleHelper.applyToCardIcon(binding.icon, originalIcon, "home_shizuku_companion")
    }
}
