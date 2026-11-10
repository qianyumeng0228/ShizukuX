package af.shizuku.manager.backup

import android.app.Activity
import android.view.Gravity
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import af.shizuku.manager.R
import af.shizuku.manager.files.FileManager

/**
 * In-app directory chooser for the backup root.
 *
 * The system picker (`ACTION_OPEN_DOCUMENT_TREE`) cannot offer `/data`, `/data/adb` or any
 * root-only path — it is a DocumentsProvider browser over shared storage, and Android 11+ even
 * refuses `Android/data` and `Android/obb` ("为保护您的隐私，请选择其他文件夹"). That left the
 * user staring at a greyed-out "使用此文件夹" inside `Android` and unable to pick the folder the
 * backups were meant for. Reading directories through Shizuku (with a root retry, as the file
 * browser already does) gives a picker that can actually reach those paths.
 */
object BackupDirPicker {

    /** Places a user actually stores backups, in the order they are offered. */
    private val QUICK_PATHS = listOf("/", "/sdcard", "/data", "/data/adb", "/data/local/tmp")

    fun show(activity: Activity, current: String?, onPick: (String) -> Unit) {
        var path = current?.takeIf { it.startsWith("/") } ?: "/sdcard"

        val density = activity.resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()

        val pathView = TextView(activity).apply {
            text = path
            textSize = 13f
            setPadding(dp(20), dp(10), dp(20), dp(4))
        }
        val hintView = TextView(activity).apply {
            setText(R.string.backup_dir_root_hint)
            textSize = 11f
            setPadding(dp(20), 0, dp(20), dp(8))
            setTextColor(0x99111111.toInt())
        }
        val quickRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(16), 0, dp(16), dp(8))
            gravity = Gravity.START
        }
        val listView = ListView(activity)
        val adapter = ArrayAdapter<String>(activity, android.R.layout.simple_list_item_1)
        listView.adapter = adapter
        val names = mutableListOf<String>()
        val targets = mutableListOf<String>()

        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            addView(pathView)
            addView(hintView)
            addView(quickRow)
            addView(
                listView,
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(360))
            )
        }

        // The listing is a binder call into Shizuku and, for root-only paths, a second one through
        // `su`; running it on the main thread would freeze the dialog the moment the user taps /data.
        fun load() {
            pathView.text = path
            names.clear()
            targets.clear()
            adapter.clear()
            adapter.notifyDataSetChanged()
            Thread {
                val listing = try {
                    FileManager.list(path)
                } catch (t: Throwable) {
                    null
                }
                // A null listing has two causes that look nothing alike to the user: no such
                // directory (the default target is only created by the first backup) versus no
                // permission. Ask before labelling it.
                val exists = if (listing == null) {
                    try {
                        RootBackup.isDir(path)
                    } catch (t: Throwable) {
                        null
                    }
                } else {
                    null
                }
                activity.runOnUiThread {
                    names.clear()
                    targets.clear()
                    if (listing == null) {
                        names.add(
                            activity.getString(
                                if (exists == false) R.string.backup_dir_missing
                                else R.string.backup_dir_unreadable
                            )
                        )
                        targets.add("")
                    } else {
                        parentOf(path)?.let {
                            names.add("..")
                            targets.add(it)
                        }
                        listing.filter { it.directory }.forEach {
                            names.add(it.name)
                            targets.add(it.path)
                        }
                        if (names.isEmpty()) {
                            names.add(activity.getString(R.string.backup_dir_empty))
                            targets.add("")
                        }
                    }
                    adapter.clear()
                    adapter.addAll(names)
                    adapter.notifyDataSetChanged()
                }
            }.start()
        }

        for (quick in QUICK_PATHS) {
            quickRow.addView(TextView(activity).apply {
                text = quick
                textSize = 12f
                setPadding(dp(10), dp(6), dp(10), dp(6))
                setOnClickListener {
                    if (path != quick) {
                        path = quick
                        load()
                    }
                }
            })
        }

        listView.setOnItemClickListener { _, _, position, _ ->
            val target = targets.getOrNull(position).orEmpty()
            if (target.isNotEmpty() && target != path) {
                path = target
                load()
            }
        }

        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.backup_pick_dir_title)
            .setView(container)
            .setPositiveButton(R.string.backup_dir_use_this) { _, _ -> onPick(path) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()

        load()
    }

    private fun parentOf(path: String): String? {
        if (path == "/" || path.isEmpty()) return null
        val trimmed = path.trimEnd('/')
        val index = trimmed.lastIndexOf('/')
        return if (index <= 0) "/" else trimmed.substring(0, index)
    }
}
