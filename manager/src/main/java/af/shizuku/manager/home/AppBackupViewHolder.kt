package af.shizuku.manager.home

import android.content.Intent
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import rikka.core.content.asActivity
import android.net.Uri
import af.shizuku.manager.R
import af.shizuku.manager.ShizukuSettings
import af.shizuku.manager.backup.AppBackupActivity
import af.shizuku.manager.backup.RootBackup
import af.shizuku.manager.databinding.HomeAppBackupItemBinding
import af.shizuku.manager.databinding.HomeItemContainerBinding
import af.shizuku.manager.ktx.startWithSceneTransition
import af.shizuku.manager.model.ServiceStatus
import af.shizuku.manager.utils.IconStyleHelper
import af.shizuku.manager.utils.MotionUtils.applySpringTouch
import rikka.recyclerview.BaseViewHolder
import rikka.recyclerview.BaseViewHolder.Creator

class AppBackupViewHolder(
    private val binding: HomeAppBackupItemBinding,
    private val containerBinding: HomeItemContainerBinding,
) : BaseViewHolder<ServiceStatus>(containerBinding.root), View.OnClickListener {

    companion object {
        val CREATOR = Creator<ServiceStatus> { inflater: LayoutInflater, parent: ViewGroup? ->
            val outer = HomeItemContainerBinding.inflate(inflater, parent, false)
            val inner = HomeAppBackupItemBinding.inflate(inflater, outer.cardContent, true)
            AppBackupViewHolder(inner, outer)
        }
    }

    init {
        containerBinding.root.setOnClickListener(this)
        containerBinding.root.applySpringTouch()
        containerBinding.root.setOnLongClickListener { HomeEditMode.enter(); true }
        containerBinding.dragHandle.apply {
            setOnTouchListener { _, event ->
                if (event.action == MotionEvent.ACTION_DOWN) HomeEditMode.startDragCallback?.invoke(this@AppBackupViewHolder)
                false
            }
            setOnLongClickListener { HomeEditMode.enter(); true }
        }
    }

    private val originalIcon = binding.icon.drawable

    private inline val title get() = binding.text1
    private inline val summary get() = binding.text2
    private inline val iconView get() = binding.icon

    override fun onBind() {
        val context = itemView.context
        HomeEditMode.applyOverlay(containerBinding)
        IconStyleHelper.applyToCardIcon(iconView, originalIcon, "home_app_backup")
        if (!data.isRunning) {
            itemView.isEnabled = false
            title.setText(R.string.home_backup_title)
            summary.text = context.getString(
                R.string.home_status_service_not_running,
                context.getString(R.string.app_name)
            )
        } else {
            itemView.isEnabled = true
            title.setText(R.string.home_backup_title)
            val exportDir = getConfiguredExportDir(context)
            if (exportDir != null) {
                summary.text = context.getString(R.string.home_backup_summary_saf, exportDir)
            } else {
                summary.setText(R.string.home_backup_summary)
            }
            // The root probe decides which of the two lines above is correct, and it must not run
            // on the main thread. Start it once and rebind the card when it answers, so the first
            // launch shows the real destination instead of a stale SAF label.
            if (!RootBackup.availableKnown()) {
                RootBackup.warmUp {
                    itemView.post {
                        (itemView.parent as? RecyclerView)?.adapter?.notifyDataSetChanged()
                    }
                }
            }
        }
    }

    private fun getConfiguredExportDir(context: android.content.Context): String? {
        // Root-backed directory wins: it is a real path (possibly under /data) and the card should
        // show where backups actually land rather than a leftover SAF label. Only a completed
        // probe counts — onBind runs on the main thread for every card, and starting `su` there
        // would stall the list.
        if (RootBackup.availableKnown()) {
            return ShizukuSettings.getBackupRootDir()?.takeIf { it.startsWith("/") }
                ?: RootBackup.DEFAULT_DIR
        }
        val uriStr = ShizukuSettings.getExportDirUri() ?: return null
        return try {
            val uri = Uri.parse(uriStr)
            val hasPermission = context.contentResolver.persistedUriPermissions.any {
                it.uri == uri && it.isWritePermission
            }
            if (hasPermission) uri.lastPathSegment ?: uriStr else null
        } catch (_: Exception) {
            null
        }
    }

    override fun onClick(v: View) {
        val activity = v.context.asActivity<android.app.Activity>() ?: return
        activity.startWithSceneTransition(
            Intent(activity, AppBackupActivity::class.java),
            iconView,
            "icon_backup"
        )
    }
}
