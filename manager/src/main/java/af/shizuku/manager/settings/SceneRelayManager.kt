package af.shizuku.manager.settings

import android.content.Context
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import af.shizuku.manager.R
import af.shizuku.manager.authorization.AuthorizationManager

/**
 * External relay authorization for apps that do not declare Shizuku permissions themselves.
 *
 * Currently only Scene (com.omarea.vtools) is supported: ShizukuX acts as a middle-man, running
 * Scene's official activation script (up.sh) through a Shizuku shell process so that scene-daemon
 * goes resident and Scene gains its ADB mode. The same entry point will be reused by future
 * relayed apps (see ExternalRelayActivity).
 */
object SceneRelayManager {

    /** Scene's official package; the middle-man scheme only works against it (paths in up.sh are bound to this package name). */
    private const val SCENE_PACKAGE = "com.omarea.vtools"

    /**
     * Activate Scene's ADB mode via Shizuku middle-man.
     *
     * @param context host context (Activity or Fragment).
     * @param scope   coroutine scope tied to the caller's lifecycle.
     */
    fun startSceneAdbActivation(context: Context, scope: CoroutineScope) {
        android.util.Log.w("SceneRelay", "startSceneAdbActivation called")

        try {
            android.util.Log.w("SceneRelay", "pingBinder=${Shizuku.pingBinder()} binder=${Shizuku.getBinder()}")
            try {
                android.util.Log.w("SceneRelay", "getUid=${Shizuku.getUid()}")
            } catch (e: Throwable) {
                android.util.Log.w("SceneRelay", "getUid threw: ${e.javaClass.simpleName}: ${e.message}")
            }
            try {
                android.util.Log.w("SceneRelay", "getVersion=${Shizuku.getVersion()}")
            } catch (e: Throwable) {
                android.util.Log.w("SceneRelay", "getVersion threw: ${e.javaClass.simpleName}: ${e.message}")
            }
        } catch (e: Throwable) {
            android.util.Log.w("SceneRelay", "binder introspection failed: $e")
        }

        if (!Shizuku.pingBinder()) {
            Toast.makeText(context, R.string.scene_relay_shizuku_not_running, Toast.LENGTH_LONG).show()
            return
        }
        val sceneInstalled = try {
            context.packageManager.getPackageInfo(SCENE_PACKAGE, 0)
            true
        } catch (e: android.content.pm.PackageManager.NameNotFoundException) {
            false
        }
        if (!sceneInstalled) {
            Toast.makeText(context, R.string.scene_relay_scene_not_installed, Toast.LENGTH_LONG).show()
            return
        }

        val upScript = "/data/local/tmp/up.sh"
        Toast.makeText(context, R.string.scene_relay_activating, Toast.LENGTH_SHORT).show()
        scope.launch(Dispatchers.IO) {
            try {
                // Middle-man core: create a shell-level process (inherits the service UID, i.e.
                // shell/ADB on a non-root start) and have it run Scene's official up.sh. The
                // script copies scene-daemon to /data/local/tmp and leaves it running in the
                // background, which is what actually grants Scene its ADB permission.
                android.util.Log.w("SceneRelay", "about to call newProcess, pingBinder=${Shizuku.pingBinder()}")
                val process = try {
                    Shizuku.newProcess(
                        arrayOf("/system/bin/sh", "-c", "/system/bin/sh $upScript"),
                        null,
                        // cwd 设为脚本所在目录：up.sh 内部用相对路径 ./busybox，且需避免
                        // Runtime.exec 按 PATH 找到 APEX 受限 sush（不支持 [[ ]] 等语法）导致 syntax error。
                        "/data/local/tmp"
                    )
                } catch (e: Throwable) {
                    android.util.Log.w("SceneRelay", "newProcess threw: ${e.javaClass.simpleName}: ${e.message}", e)
                    throw e
                }
                android.util.Log.w("SceneRelay", "newProcess returned: $process")
                val stdout = process.inputStream.bufferedReader().use { it.readText() }
                val stderr = process.errorStream.bufferedReader().use { it.readText() }
                process.waitFor()
                val output = (stdout + stderr).trim()
                android.util.Log.w("SceneRelay", "up.sh output: $output")
                val ok = stdout.contains("Scene-Daemon OK")

                // Verify the daemon actually went resident (the real source of ADB permission).
                var daemonPid = ""
                try {
                    val verify = Shizuku.newProcess(
                        arrayOf("sh", "-c", "pgrep -f scene-daemon"), null, null
                    )
                    val vOut = verify.inputStream.bufferedReader().use { it.readText() }
                    verify.waitFor()
                    daemonPid = vOut.trim()
                } catch (_: Exception) {
                }

                val activated = ok || daemonPid.isNotEmpty()

                // 激活成功后，将 Scene 加入 ShizukuX 已授权应用列表（更新服务端权限 flags）。
                var sceneGranted = false
                if (activated) {
                    try {
                        val appInfo = context.packageManager.getApplicationInfo(SCENE_PACKAGE, 0)
                        val sceneUid = appInfo.uid
                        if (sceneUid > 0) {
                            AuthorizationManager.grant(SCENE_PACKAGE, sceneUid)
                            sceneGranted = AuthorizationManager.granted(SCENE_PACKAGE, sceneUid)
                            android.util.Log.w("SceneRelay", "grant scene uid=$sceneUid granted=$sceneGranted")
                        }
                    } catch (e: Throwable) {
                        android.util.Log.w("SceneRelay", "grant scene failed: ${e.message}")
                    }
                }

                withContext(Dispatchers.Main) {
                    val sb = StringBuilder()
                    sb.append(
                        if (activated) {
                            if (daemonPid.isNotEmpty()) {
                                context.getString(R.string.scene_relay_daemon_running, daemonPid)
                            } else {
                                context.getString(R.string.scene_relay_success)
                            }
                        } else {
                            context.getString(R.string.scene_relay_failed)
                        }
                    )
                    if (activated && sceneGranted) {
                        sb.append("\n").append(context.getString(R.string.scene_relay_granted))
                    } else if (activated) {
                        sb.append("\n").append(context.getString(R.string.scene_relay_grant_failed))
                    }
                    if (output.isNotEmpty()) {
                        sb.append("\n\n").append(output)
                    }
                    MaterialAlertDialogBuilder(context)
                        .setTitle(R.string.scene_relay_result_title)
                        .setMessage(sb.toString())
                        .setPositiveButton(android.R.string.ok, null)
                        .show()
                }
            } catch (e: Exception) {
                android.util.Log.w("SceneRelay", "startSceneAdbActivation catch: ${e.javaClass.simpleName}: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    MaterialAlertDialogBuilder(context)
                        .setTitle(R.string.scene_relay_result_title)
                        .setMessage(context.getString(R.string.scene_relay_failed) + "\n\n" + (e.message ?: e.javaClass.simpleName))
                        .setPositiveButton(android.R.string.ok, null)
                        .show()
                }
            }
        }
    }
}
