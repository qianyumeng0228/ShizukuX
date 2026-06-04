package af.shizuku.starter

import android.content.IContentProvider
import android.os.*
import android.util.Log
import af.shizuku.api.BinderContainer
import af.shizuku.starter.util.IContentProviderCompat
import kotlinx.coroutines.*
import rikka.hidden.compat.ActivityManagerApis
import rikka.shizuku.ShizukuApiConstants
import rikka.shizuku.server.UserService
import java.util.*

object ServiceStarter {

    private const val TAG = "ShizukuServiceStarter"
    private const val EXTRA_BINDER = "af.shizuku.plus.api.intent.extra.BINDER"

    val DEBUG_ARGS: String by lazy {
        val sdk = Build.VERSION.SDK_INT
        when {
            sdk >= 30 -> "-Xcompiler-option --debuggable -XjdwpProvider:adbconnection -XjdwpOptions:suspend=n,server=y"
            sdk >= 28 -> "-Xcompiler-option --debuggable -XjdwpProvider:internal -XjdwpOptions:transport=dt_android_adb,suspend=n,server=y"
            else -> "-Xcompiler-option --debuggable -agentlib:jdwp=transport=dt_android_adb,suspend=n,server=y"
        }
    }

    private const val USER_SERVICE_CMD_FORMAT = "(CLASSPATH='%s' %s%s /system/bin " +
            "--nice-name='%s' af.shizuku.starter.ServiceStarter " +
            "--token='%s' --package='%s' --class='%s' --uid=%d%s)&"

    @JvmStatic
    @Volatile
    private var shizukuBinder: IBinder? = null

    @JvmStatic
    fun commandForUserService(
        appProcess: String,
        managerApkPath: String,
        token: String,
        packageName: String,
        classname: String,
        processNameSuffix: String,
        callingUid: Int,
        debug: Boolean
    ): String {
        // Sanitize single quotes to prevent shell injection escapes
        val safePackageName = packageName.replace("'", "'\\''")
        val safeClassname = classname.replace("'", "'\\''")
        val safeProcessNameSuffix = processNameSuffix.replace("'", "'\\''")
        
        val processName = "$safePackageName:$safeProcessNameSuffix"
        return String.format(
            Locale.ENGLISH, USER_SERVICE_CMD_FORMAT,
            managerApkPath, appProcess, if (debug) " $DEBUG_ARGS" else "",
            processName,
            token, safePackageName, safeClassname, callingUid, if (debug) " --debug-name=$processName" else ""
        )
    }

    @JvmStatic
    fun main(args: Array<String>) {
        if (Looper.getMainLooper() == null) {
            Looper.prepareMainLooper()
        }

        UserService.setTag(TAG)
        val result = UserService.create(args)

        if (result == null) {
            System.exit(1)
            return
        }

        val service = result.first
        val token = result.second

        runBlocking {
            if (!sendBinder(service, token)) {
                System.exit(1)
            }
        }

        Looper.loop()
        System.exit(0)

        Log.i(TAG, "service exited")
    }

    private suspend fun sendBinder(binder: IBinder, token: String, retry: Boolean = true): Boolean = withContext(Dispatchers.IO) {
        val packageName = "af.shizuku.plus.api"
        val name = "$packageName.shizuku"
        val userId = 0
        var provider: IContentProvider? = null

        try {
            provider = ActivityManagerApis.getContentProviderExternal(name, userId, null, name)
            if (provider == null) {
                Log.e(TAG, "provider is null $name $userId")
                return@withContext false
            }
            if (!provider.asBinder().pingBinder()) {
                Log.e(TAG, "provider is dead $name $userId")

                if (retry) {
                    ActivityManagerApis.forceStopPackageNoThrow(packageName, userId)
                    Log.e(TAG, "kill $packageName in user $userId and try again")
                    delay(1000)
                    return@withContext sendBinder(binder, token, false)
                }
                return@withContext false
            }

            if (!retry) {
                Log.e(TAG, "retry works")
            }

            val extra = Bundle().apply {
                putParcelable(EXTRA_BINDER, BinderContainer(binder))
                putString(ShizukuApiConstants.USER_SERVICE_ARG_TOKEN, token)
            }

            val reply = IContentProviderCompat.call(provider, null, null, name, "sendUserService", null, extra)

            if (reply != null) {
                reply.classLoader = BinderContainer::class.java.classLoader

                Log.i(TAG, "send binder to $packageName in user $userId")
                val container = reply.getParcelable<BinderContainer>(EXTRA_BINDER)

                if (container?.binder != null && container.binder.pingBinder()) {
                    shizukuBinder = container.binder
                    shizukuBinder?.linkToDeath({
                        Log.i(TAG, "exiting...")
                        System.exit(0)
                    }, 0)
                    return@withContext true
                } else {
                    Log.w(TAG, "server binder not received")
                }
            }

            return@withContext false
        } catch (tr: Throwable) {
            Log.e(TAG, "failed send binder to $packageName in user $userId", tr)
            return@withContext false
        } finally {
            if (provider != null) {
                try {
                    ActivityManagerApis.removeContentProviderExternal(name, null)
                } catch (tr: Throwable) {
                    Log.w(TAG, "removeContentProviderExternal", tr)
                }
            }
        }
    }
}
