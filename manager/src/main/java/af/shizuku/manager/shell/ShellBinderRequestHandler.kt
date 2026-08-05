package af.shizuku.manager.shell

import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.Parcel
import timber.log.Timber
import af.shizuku.manager.utils.Logger.LOGGER
import rikka.shizuku.Shizuku
import af.shizuku.manager.ShizukuSettings

object ShellBinderRequestHandler {

    /**
     * Handles a REQUEST_BINDER broadcast directly (auth-token fast path).
     * Extracts the callback binder from the intent extras and delivers the Shizuku binder to it.
     * Not used by the notification consent flow — that path calls [deliverBinder] directly with
     * a pre-taken binder from [PendingConsentStore].
     */
    fun handleRequest(context: Context, intent: Intent, requireAuth: Boolean = false): Boolean {
        if (intent.action != "rikka.shizuku.intent.action.REQUEST_BINDER" &&
            intent.action != "${context.packageName}.intent.action.REQUEST_BINDER") {
            return false
        }

        if (requireAuth) {
            val rawToken = intent.getStringExtra("auth")
            val authToken = if (rawToken != null) af.shizuku.manager.utils.IntentCrypto.decrypt(rawToken) else null
            val expectedToken = ShizukuSettings.getAuthToken()
            // Constant-time compare: this gates handing the live Shizuku binder to the caller, so a
            // length/early-exit-dependent compare would leak a timing side-channel on the token.
            if (authToken == null ||
                !java.security.MessageDigest.isEqual(authToken.toByteArray(), expectedToken.toByteArray())
            ) {
                return false
            }
        }

        val callbackBinder = intent.getBundleExtra("data")?.getBinder("binder") ?: return false
        return deliverBinder(context, callbackBinder)
    }

    /**
     * Delivers the Shizuku server binder to [callbackBinder] (the rish process's receiver).
     * The callback binder is already resolved by the caller — either taken from
     * [PendingConsentStore] (notification consent path) or extracted directly from intent extras
     * (auth-token fast path). This avoids any store interaction and is safe to call even if the
     * store entry was already consumed.
     */
    fun deliverBinder(context: Context, callbackBinder: IBinder): Boolean {
        val shizukuBinder = try {
            Shizuku.getBinder()
        } catch (e: Exception) {
            LOGGER.w(e, "getBinder failed")
            return false
        }
        if (shizukuBinder == null) {
            LOGGER.w("shizuku binder is null")
            return false
        }

        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            // Must match ShizukuShellLoader.receiverBinder.onTransact exactly: it reads
            // readStrongBinder() then readString() with no enforceInterface() call, so a
            // leading writeInterfaceToken() here gets misread as the binder slot and an
            // omitted sourceDir NPEs the client at onBinderReceived's sourceDir.substring().
            data.writeStrongBinder(shizukuBinder)
            data.writeString(context.applicationInfo.sourceDir)
            callbackBinder.transact(IBinder.FIRST_CALL_TRANSACTION, data, reply, IBinder.FLAG_ONEWAY)
            return true
        } catch (e: Exception) {
            LOGGER.w(e, "transact")
            return false
        } finally {
            data.recycle()
            reply.recycle()
        }
    }
}
