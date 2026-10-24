package rikka.shizuku.server

import android.os.Process
import af.shizuku.server.IActivityManagerExtra
import rikka.hidden.compat.ActivityManagerApis
import af.shizuku.common.util.UserHandleCompat
import rikka.shizuku.server.util.ShellExecutor

class ActivityManagerPlusImpl : IActivityManagerExtra.Stub() {
    override fun deepForceStop(packageName: String?): Boolean {
        if (packageName == null) return false
        try {
            ActivityManagerApis.forceStopPackageNoThrow(packageName, UserHandleCompat.getUserId(Process.myUid()))
            return true
        } catch (e: Exception) {
            return false
        }
    }

    override fun setAppStandbyBucket(packageName: String?, bucket: Int): Boolean {
        if (packageName == null) return false
        val bucketStr = when (bucket) {
            10 -> "active"
            20 -> "working_set"
            30 -> "frequent"
            40 -> "rare"
            45 -> "restricted"
            50 -> "restricted"
            else -> bucket.toString()
        }
        return ShellExecutor.execBool("am", "set-standby-bucket", packageName, bucketStr)
    }

    override fun killAllBackgroundProcesses(): Boolean {
        return ShellExecutor.execBool("am", "kill-all")
    }

    override fun freezeApp(packageName: String?): Boolean {
        if (packageName == null) return false
        // Use pm disable-user which works for shell on user 0
        return ShellExecutor.execBool("pm", "disable-user", "--user", "0", packageName)
    }

    override fun unfreezeApp(packageName: String?): Boolean {
        if (packageName == null) return false
        return ShellExecutor.execBool("pm", "enable", "--user", "0", packageName)
    }

    override fun isAppFrozen(packageName: String?): Boolean {
        if (packageName == null) return false
        return ShellExecutor.exec("pm", "list", "packages", "-d", packageName).contains(packageName)
    }

    override fun setAppProcessLimit(limit: Int) {
        // This setting is internal to ActivityManagerService but can be set via 'am' on some builds
        // Fallback to global setting if command fails
        ShellExecutor.execCode("settings", "put", "global", "max_phantom_processes", limit.toString())
        ShellExecutor.execCode("am", "set-process-limit", limit.toString())
    }

    override fun getRunningProcesses(): List<String> {
        ShellExecutor.execCode("am", "stack", "list") // Dummy check for am access
        // Use 'ps' which is more reliable for raw listing via shell
        return ShellExecutor.exec("ps", "-A", "-o", "NAME,RSS,PID").lines()
    }

    override fun clearAppCache(packageName: String?): Boolean {
        if (packageName == null) return false
        // pm trim-caches trims to a target free space, but we use pm clear for absolute clearing
        // Note: pm clear wipes data AND cache. For just cache, we can use pm trim-caches with a huge value.
        return ShellExecutor.execBool("pm", "trim-caches", "4096G")
    }

    override fun clearAppData(packageName: String?): Boolean {
        if (packageName == null) return false
        return ShellExecutor.execBool("pm", "clear", "--user", "0", packageName)
    }
}
