package af.shizuku.manager.home

import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.ViewGroup
import af.shizuku.manager.R
import af.shizuku.manager.ShizukuSettings
import af.shizuku.manager.databinding.HomeItemContainerBinding
import af.shizuku.manager.databinding.HomeShizukuCompanionBinding
import af.shizuku.manager.utils.StockShizukuCompat
import android.content.Intent
import android.net.Uri
import android.view.View
import android.widget.Toast
import rikka.shizuku.Shizuku
import rikka.recyclerview.BaseViewHolder
import rikka.recyclerview.BaseViewHolder.Creator
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
class ShizukuCompanionViewHolder(
    private val binding: HomeShizukuCompanionBinding,
    private val containerBinding: HomeItemContainerBinding,
) : BaseViewHolder<Pair<Boolean, Boolean>>(containerBinding.root) {

    companion object {
        val CREATOR = Creator<Pair<Boolean, Boolean>> { inflater: LayoutInflater, parent: ViewGroup? ->
            val outer = HomeItemContainerBinding.inflate(inflater, parent, false)
            val inner = HomeShizukuCompanionBinding.inflate(inflater, outer.cardContent, true)
            ShizukuCompanionViewHolder(inner, outer)
        }
    }

    init {
        containerBinding.root.setOnLongClickListener { HomeEditMode.enter(); true }
        containerBinding.dragHandle.apply {
            setOnTouchListener { _, event ->
                if (event.action == MotionEvent.ACTION_DOWN) HomeEditMode.startDragCallback?.invoke(this@ShizukuCompanionViewHolder)
                false
            }
            setOnLongClickListener { HomeEditMode.enter(); true }
        }

        binding.button1.setOnClickListener { v ->
            val companionInstalled = data?.first ?: false
            if (companionInstalled) {
                kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                    val cmd = "pm disable-user --user 0 ${StockShizukuCompat.PACKAGE}"
                    if (Shizuku.pingBinder()) {
                        try {
                            val process = Shizuku.newProcess(arrayOf("sh", "-c", cmd), null, null)
                            val success = process.waitFor() == 0
                            process.destroy()
                            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                if (success) {
                                    Toast.makeText(v.context, R.string.companion_disable_success, Toast.LENGTH_SHORT).show()
                                } else {
                                    Toast.makeText(v.context, R.string.companion_disable_failure, Toast.LENGTH_SHORT).show()
                                }
                            }
                        } catch (e: Exception) {
                            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                Toast.makeText(v.context, R.string.companion_disable_failure, Toast.LENGTH_SHORT).show()
                            }
                        }
                    } else if (af.shizuku.manager.migration.MigrationHelper.isRootAvailable()) {
                        try {
                            val result = com.topjohnwu.superuser.Shell.cmd(cmd).exec()
                            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                if (result.isSuccess) {
                                    Toast.makeText(v.context, R.string.companion_disable_success, Toast.LENGTH_SHORT).show()
                                } else {
                                    Toast.makeText(v.context, R.string.companion_disable_failure, Toast.LENGTH_SHORT).show()
                                }
                            }
                        } catch (e: Exception) {
                            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                Toast.makeText(v.context, R.string.companion_disable_failure, Toast.LENGTH_SHORT).show()
                            }
                        }
                    } else {
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                            Toast.makeText(v.context, R.string.companion_disable_failure, Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            } else {
                // Must be on external storage, not the app's private cache/files dir: `pm install`
                // runs via a shell process spawned by Shizuku.newProcess (UID 2000) or root, neither
                // of which can read /data/user/0/<pkg>/... due to per-app UID sandboxing on internal
                // storage. getExternalFilesDir is readable by shell/root and is the same location
                // UpdateManager/UpdateInstaller already use successfully for APK installs.
                val dir = v.context.getExternalFilesDir(null) ?: v.context.cacheDir ?: v.context.filesDir ?: return@setOnClickListener
                val tmpApk = java.io.File(dir, "compat.apk")
                try {
                    v.context.assets.open("compat.apk").use { input ->
                        tmpApk.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                } catch(e: Exception) {
                    Toast.makeText(v.context, R.string.compat_hub_install_fail, Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                
                val cmd = "pm install -r ${tmpApk.absolutePath}"
                kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                    if (Shizuku.pingBinder()) {
                        try {
                            val process = Shizuku.newProcess(arrayOf("sh", "-c", cmd), null, null)
                            val success = process.waitFor() == 0
                            process.destroy()
                            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                if (success) {
                                    Toast.makeText(v.context, R.string.compat_hub_install_success, Toast.LENGTH_SHORT).show()
                                } else {
                                    Toast.makeText(v.context, R.string.compat_hub_install_fail, Toast.LENGTH_SHORT).show()
                                }
                            }
                        } catch (e: Exception) {
                            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                Toast.makeText(v.context, R.string.compat_hub_install_fail, Toast.LENGTH_SHORT).show()
                            }
                        }
                    } else if (af.shizuku.manager.migration.MigrationHelper.isRootAvailable()) {
                        try {
                            val result = com.topjohnwu.superuser.Shell.cmd(cmd).exec()
                            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                if (result.isSuccess) {
                                    Toast.makeText(v.context, R.string.compat_hub_install_success, Toast.LENGTH_SHORT).show()
                                } else {
                                    Toast.makeText(v.context, R.string.compat_hub_install_fail, Toast.LENGTH_SHORT).show()
                                }
                            }
                        } catch (e: Exception) {
                            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                Toast.makeText(v.context, R.string.compat_hub_install_fail, Toast.LENGTH_SHORT).show()
                            }
                        }
                    } else {
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                            Toast.makeText(v.context, R.string.compat_hub_install_fail, Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        }
        binding.button2.setOnClickListener { v ->
            val intent = Intent(Intent.ACTION_DELETE)
            intent.data = Uri.parse("package:${StockShizukuCompat.PACKAGE}")
            v.context.startActivity(intent)
        }
    }

    override fun onBind() {
        val companionInstalled = data?.first ?: false
        val compatHubInstalled = data?.second ?: false

        if (compatHubInstalled && !companionInstalled) {
            binding.title.setText(R.string.compat_hub_installed_title)
            binding.text1.setText(R.string.compat_hub_installed_desc)
            binding.button1.visibility = View.GONE
            binding.button2.visibility = View.GONE
        } else if (companionInstalled) {
            binding.title.setText(R.string.companion_conflict_title)
            binding.text1.setText(R.string.companion_conflict_description)
            binding.button1.setText(R.string.companion_action_disable)
            binding.button1.isEnabled = true
            binding.button1.visibility = View.VISIBLE
            binding.button2.visibility = View.VISIBLE
        } else {
            binding.title.setText(R.string.compat_hub_missing_title)
            binding.text1.setText(R.string.compat_hub_missing_desc)
            binding.button1.setText(R.string.compat_hub_install_btn)
            binding.button1.isEnabled = true
            binding.button1.visibility = View.VISIBLE
            binding.button2.visibility = View.GONE
        }
        HomeEditMode.applyOverlay(containerBinding)
    }
}
