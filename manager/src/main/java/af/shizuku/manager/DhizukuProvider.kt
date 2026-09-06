package af.shizuku.manager

import af.shizuku.manager.admin.DhizukuAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.Parcel
import android.os.RemoteException
import timber.log.Timber

/**
 * Dhizuku Provider — implements the official Dhizuku server protocol so that
 * third-party apps using the Dhizuku-API client library (e.g. Hail) can relay
 * system-service transactions through this app (which holds the Device Owner).
 *
 * Protocol reference: iamr0s/Dhizuku-API (dhizuku-server_api / dhizuku-api).
 *  - ContentResolver.call(method="client", extras with "client" IBinder)
 *  - returns Bundle["dhizuku_binder"] = IDhizuku Binder
 *  - IDhizuku.Stub transaction codes match the V2 AIDL interface
 *  - TRANSACT_CODE_REMOTE_BINDER = FIRST_CALL_TRANSACTION + 10,
 *    descriptor "com.rosan.dhizuku.server", relays arbitrary binder transact()
 *    calls through this process's identity.
 */
class DhizukuProvider : ContentProvider() {

    companion object {
        private const val TAG = "DhizukuProvider"

        // From com.rosan.dhizuku.shared.DhizukuVariables
        private const val PROVIDER_METHOD_CLIENT = "client"
        private const val EXTRA_CLIENT = "client"
        private const val PARAM_DHIZUKU_BINDER = "dhizuku_binder"
        private const val BINDER_DESCRIPTOR = "com.rosan.dhizuku.server"
        private const val AIDL_DESCRIPTOR = "com.rosan.dhizuku.aidl.IDhizuku"
        private const val TRANSACT_CODE_REMOTE_BINDER = Binder.FIRST_CALL_TRANSACTION + 10
        private const val SERVICE_VERSION_CODE = 7
    }

    // ------------------------------------------------------------------
    // Authorization
    // ------------------------------------------------------------------

    private fun isCallerAllowedToUseDhizuku(callingUid: Int): Boolean {
        if (callingUid == android.os.Process.myUid()) return true
        val ctx = context ?: return false

        af.shizuku.manager.database.DhizukuAppManager.ensureInitialized(ctx)
        val entity = af.shizuku.manager.database.DhizukuAppManager.findByUid(callingUid)
        if (entity != null) {
            if (!entity.allowApi || entity.blocked) return false
            val pkgName = ctx.packageManager.getPackagesForUid(callingUid)?.firstOrNull() ?: return false
            val currentSig = try {
                val info = ctx.packageManager.getPackageInfo(
                    pkgName, android.content.pm.PackageManager.GET_SIGNATURES
                )
                val signatures = info.signatures ?: return false
                if (signatures.isEmpty()) return false
                val md = java.security.MessageDigest.getInstance("SHA-256")
                md.update(signatures[0].toByteArray())
                md.digest().joinToString("") { String.format("%02x", it) }
            } catch (e: Exception) {
                null
            }
            return currentSig != null && currentSig == entity.signature
        }

        // Legacy fallback to Shizuku authorization
        val pkgName = ctx.packageManager.getPackagesForUid(callingUid)?.firstOrNull() ?: return false
        return af.shizuku.manager.authorization.AuthorizationManager.granted(pkgName, callingUid)
    }

    private fun enforceCallingPermission(func: String) {
        val callingUid = Binder.getCallingUid()
        val callingPid = Binder.getCallingPid()
        if (callingUid == android.os.Process.myUid()) return
        if (isCallerAllowedToUseDhizuku(callingUid)) return
        throw SecurityException(
            "Permission Denial: $func is not allowed from pid=$callingPid, uid=$callingUid"
        )
    }

    // ------------------------------------------------------------------
    // IDhizuku Binder implementation (V2 AIDL + remote-binder relay)
    // ------------------------------------------------------------------

    private inner class DhizukuServiceBinder : Binder() {

        private val dpm: DevicePolicyManager? by lazy {
            context?.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
        }

        private val adminComponent: ComponentName? by lazy {
            context?.let { ComponentName(it, DhizukuAdminReceiver::class.java) }
        }

        override fun getInterfaceDescriptor(): String = AIDL_DESCRIPTOR

        @Throws(RemoteException::class)
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (!ShizukuSettings.isDhizukuModeEnabled()) {
                Timber.tag(TAG).w("Dhizuku mode disabled, rejecting transact code=$code")
                return false
            }

            // Remote binder relay — the core Dhizuku feature.
            // Clients call Binder.transact(TRANSACT_CODE_REMOTE_BINDER, ...) with
            // descriptor BINDER_DESCRIPTOR to ask us to forward a transact() call
            // to another binder using *our* identity (Device Owner).
            if (code == TRANSACT_CODE_REMOTE_BINDER) {
                data.enforceInterface(BINDER_DESCRIPTOR)
                enforceCallingPermission("remote_transact")

                val targetBinder = data.readStrongBinder()
                val targetCode = data.readInt()
                val targetFlags = data.readInt()

                // Build a fresh Parcel containing only the target's payload.
                // The Binder driver serializes from position 0, so we must not
                // pass the original 'data' (its first token is our descriptor,
                // not the target's).
                val targetData = Parcel.obtain()
                return try {
                    val pos = data.dataPosition()
                    val avail = data.dataAvail()
                    if (avail > 0) {
                        targetData.appendFrom(data, pos, avail)
                    }
                    targetData.setDataPosition(0)
                    val result = targetBinder?.transact(targetCode, targetData, reply, targetFlags) ?: false
                    Timber.tag(TAG).d(
                        "remoteTransact: code=$targetCode result=$result " +
                        "callingUid=${Binder.getCallingUid()} " +
                        "targetDesc=${targetBinder?.interfaceDescriptor}"
                    )
                    result
                } catch (e: Exception) {
                    Timber.tag(TAG).w(e, "remoteTransact failed code=$targetCode")
                    false
                } finally {
                    targetData.recycle()
                }
            }

            // Standard AIDL methods (descriptor = AIDL_DESCRIPTOR)
            data.enforceInterface(AIDL_DESCRIPTOR)
            return when (code) {
                Binder.FIRST_CALL_TRANSACTION + 0 -> { // getVersionCode
                    reply?.writeNoException()
                    reply?.writeInt(SERVICE_VERSION_CODE)
                    true
                }
                Binder.FIRST_CALL_TRANSACTION + 1 -> { // getVersionName
                    reply?.writeNoException()
                    reply?.writeString(SERVICE_VERSION_CODE.toString())
                    true
                }
                Binder.FIRST_CALL_TRANSACTION + 2 -> { // isPermissionGranted
                    val granted = try {
                        enforceCallingPermission("isPermissionGranted")
                        true
                    } catch (e: SecurityException) {
                        false
                    }
                    reply?.writeNoException()
                    reply?.writeInt(if (granted) 1 else 0)
                    true
                }
                Binder.FIRST_CALL_TRANSACTION + 11 -> { // remoteProcess
                    // Full implementation requires the AppProcess (app_process) mechanism that
                    // the official Dhizuku uses to spawn privileged shell processes. ShizukuX
                    // does not ship that component; clients that never call newProcess()
                    // (e.g. Hail) are unaffected.
                    reply?.writeNoException()
                    reply?.writeStrongBinder(null)
                    true
                }
                Binder.FIRST_CALL_TRANSACTION + 12, // bindUserService
                Binder.FIRST_CALL_TRANSACTION + 13, // unbindUserService
                Binder.FIRST_CALL_TRANSACTION + 14 -> { // unbindUserServiceByConnection
                    // UserService hosting is not implemented. Accept the calls so that clients
                    // using Dhizuku API's start/stop/bindUserService helpers don't crash.
                    reply?.writeNoException()
                    true
                }
                Binder.FIRST_CALL_TRANSACTION + 15 -> { // getDelegatedScopes
                    val packageName = data.readString()
                    val scopes = getDelegatedScopes(packageName)
                    reply?.writeNoException()
                    reply?.writeStringArray(scopes)
                    true
                }
                Binder.FIRST_CALL_TRANSACTION + 16 -> { // setDelegatedScopes (void)
                    val packageName = data.readString()
                    val scopes = data.createStringArray()
                    setDelegatedScopes(packageName, scopes)
                    reply?.writeNoException()
                    true
                }
                else -> super.onTransact(code, data, reply, flags)
            }
        }

        // ------------------------------------------------------------------
        // Delegated scopes — how clients (e.g. Hail) obtain the power to call
        // DevicePolicyManager methods (setApplicationHidden / setPackagesSuspended)
        // directly with their own UID. The device owner grants DELEGATION_PACKAGE_ACCESS
        // to the client package via setDelegatedScopes; AOSP then accepts those calls
        // from the delegated package.
        // ------------------------------------------------------------------

        private fun getDelegatedScopes(packageName: String?): Array<String> {
            try {
                enforceCallingPermission("get_delegated_scopes")
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return emptyArray<String>()
                val manager = dpm ?: return emptyArray<String>()
                val admin = adminComponent ?: return emptyArray<String>()
                return manager.getDelegatedScopes(admin, packageName ?: "").toTypedArray()
            } catch (e: SecurityException) {
                throw e
            } catch (e: Exception) {
                Timber.tag(TAG).w(e, "getDelegatedScopes failed for pkg=$packageName")
                return emptyArray<String>()
            }
        }

        private fun setDelegatedScopes(packageName: String?, scopes: Array<out String>?) {
            enforceCallingPermission("set_delegated_scopes")
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val manager = dpm ?: return
            val admin = adminComponent ?: return
            try {
                manager.setDelegatedScopes(admin, packageName ?: "", scopes?.toList() ?: emptyList())
            } catch (e: Exception) {
                Timber.tag(TAG).w(e, "setDelegatedScopes failed for pkg=$packageName")
            }
        }
    }

    // ------------------------------------------------------------------
    // ContentProvider plumbing
    // ------------------------------------------------------------------

    override fun onCreate(): Boolean = true

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
                       selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(uri: Uri, values: ContentValues?, selection: String?,
                        selectionArgs: Array<out String>?): Int = 0

    /**
     * Official protocol: clients call ContentResolver.call(method="client", extras)
     * with their client Binder in extras["client"]. We return an IDhizuku Binder
     * in Bundle["dhizuku_binder"].
     */
    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        val callingUid = Binder.getCallingUid()
        Timber.tag(TAG).d("call() method=$method callingUid=$callingUid extras=$extras")

        if (method != PROVIDER_METHOD_CLIENT) {
            Timber.tag(TAG).d("Unknown method=$method, returning null")
            return null
        }
        if (extras == null) {
            Timber.tag(TAG).w("client call with null extras")
            return null
        }

        // Official client library always sends its IDhizukuClient binder under
        // the "client" key. We don't strictly need it for the relay feature, but
        // reading it confirms the client is using the correct protocol.
        val clientBinder: IBinder? = extras.getBinder(EXTRA_CLIENT)
        Timber.tag(TAG).d("client binder present=${clientBinder != null}")

        val bundle = Bundle()
        bundle.putBinder(PARAM_DHIZUKU_BINDER, DhizukuServiceBinder())
        Timber.tag(TAG).d("Returning DhizukuServiceBinder to uid=$callingUid")
        return bundle
    }
}
