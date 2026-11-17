package af.shizuku.manager

import af.shizuku.manager.admin.DhizukuAdminReceiver
import af.shizuku.manager.settings.SettingsActivity
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import android.os.ParcelFileDescriptor
import android.os.RemoteException
import android.widget.Toast
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.rosan.dhizuku.aidl.IDhizukuLockTaskModeListener
import com.rosan.dhizuku.aidl.IDhizukuRemoteProcess
import com.rosan.dhizuku.aidl.IDhizukuUserServiceConnection
import com.rosan.dhizuku.api.DhizukuUserServiceArgs
import com.rosan.dhizuku.server_api.LockTaskModeListeners
import com.rosan.dhizuku.server_api.UserServiceConnections
import timber.log.Timber
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

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
        // Official server API is at version 8 (adds lock-task-mode listeners). Clients that
        // gate features on getVersionCode() must see the same value as the real Dhizuku.
        private const val SERVICE_VERSION_CODE = 8
        private const val SERVICE_VERSION_NAME = "8"

        // Ownership guard (upstream Dhizuku 2.13, ba49af1 + 29ad03e).
        private const val OWNERSHIP_BLOCKED_NOTIFICATION_ID = 1002
        private const val OWNERSHIP_BLOCKED_CHANNEL_ID = "ownership_blocked"
        private const val DPM_DESCRIPTOR = "android.app.admin.IDevicePolicyManager"

        /**
         * IDevicePolicyManager transactions that would drop or hand over our Device Owner
         * status. Resolved reflectively because the constants are hidden and their values
         * shift between platform releases. The flag says whether the call returns a boolean,
         * so a blocked transaction can still write a well-formed reply.
         */
        private val ownershipTransactions: Map<Int, Boolean> by lazy {
            runCatching {
                val stub = Class.forName("android.app.admin.IDevicePolicyManager\$Stub")
                listOf(
                    "TRANSACTION_transferOwnership" to false,
                    "TRANSACTION_clearDeviceOwner" to false,
                    "TRANSACTION_clearDeviceOwnerApp" to false,
                    "TRANSACTION_clearProfileOwner" to false,
                    "TRANSACTION_setDeviceOwner" to true,
                    "TRANSACTION_setProfileOwner" to true
                ).mapNotNull { (name, returnsBoolean) ->
                    runCatching {
                        stub.getDeclaredField(name).apply { isAccessible = true }.getInt(null)
                    }.getOrNull()?.let { it to returnsBoolean }
                }.toMap()
            }.onFailure {
                Timber.tag(TAG).w(it, "Unable to resolve ownership transaction codes")
            }.getOrDefault(emptyMap())
        }
    }

    /** One service instance per (uid, pid), dropped when the client binder dies. */
    private val services = HashMap<String, DhizukuServiceBinder>()

    // ------------------------------------------------------------------
    // Authorization
    // ------------------------------------------------------------------

    private fun isCallerAllowedToUseDhizuku(callingUid: Int): Boolean {
        if (callingUid == android.os.Process.myUid()) return true
        val ctx = context ?: return false

        // The user-facing "Dhizuku mode" switch is the master gate for third-party access.
        // It is checked here (not by failing the whole onTransact) so that unprivileged
        // protocol calls such as getVersionCode()/isPermissionGranted() still answer
        // coherently instead of leaving every caller with a half-broken binder.
        if (!ShizukuSettings.isDhizukuModeEnabled()) return false

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

    /**
     * Delegated scopes may only target the caller's own package. Without this gate a client
     * that passed the general Dhizuku check could grant (or strip) device-policy delegation
     * for arbitrary third-party packages. The owner app itself is exempt; a caller whose
     * package list cannot be resolved is passed through rather than broken.
     */
    private fun enforceOwnPackage(packageName: String?, func: String) {
        val callingUid = Binder.getCallingUid()
        if (callingUid == android.os.Process.myUid()) return
        val ctx = context ?: throw SecurityException("Permission Denial: $func has no context")
        val callerPackages = try {
            ctx.packageManager.getPackagesForUid(callingUid)
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "getPackagesForUid($callingUid) failed")
            null
        } ?: return
        if (packageName == null || callerPackages.none { it == packageName }) {
            throw SecurityException(
                "Permission Denial: $func may only target the caller's own package " +
                        "(target=$packageName, uid=$callingUid)"
            )
        }
    }

    /**
     * A UserService component must belong to the calling uid. Stricter than
     * [enforceOwnPackage] on purpose: this path makes us load and execute another package's
     * code, so an unresolvable package list is refused instead of passed through
     * (upstream 541f988 enforceComponentBelongsToCaller is fail-closed too).
     */
    private fun enforceComponentBelongsToCaller(component: ComponentName?, func: String) {
        if (component == null) throw SecurityException("Permission Denial: $func has no component")
        val callingUid = Binder.getCallingUid()
        if (callingUid == android.os.Process.myUid()) return
        val ctx = context ?: throw SecurityException("Permission Denial: $func has no context")
        val callerPackages = try {
            ctx.packageManager.getPackagesForUid(callingUid)
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "getPackagesForUid($callingUid) failed")
            null
        } ?: throw SecurityException(
            "Permission Denial: $func component=${component.flattenToShortString()} " +
                    "does not resolve to uid=$callingUid"
        )
        if (callerPackages.none { it == component.packageName }) {
            throw SecurityException(
                "Permission Denial: $func may only target the caller's own package " +
                        "(target=${component.packageName}, uid=$callingUid)"
            )
        }
    }

    // ------------------------------------------------------------------
    // Ownership guard
    // ------------------------------------------------------------------

    private fun isOwnershipChange(binder: IBinder?, code: Int): Boolean {
        if (binder == null) return false
        // The manager UI runs as our own uid; keep the activation / removal flows working.
        if (Binder.getCallingUid() == android.os.Process.myUid()) return false
        val descriptor = runCatching { binder.interfaceDescriptor }.getOrNull() ?: return false
        if (descriptor != DPM_DESCRIPTOR) return false
        return code in ownershipTransactions
    }

    /**
     * Empties the reply so the client sees a neutral result instead of an owner change.
     * Boolean-returning transactions get a "false" written back; void ones stay empty,
     * which the client's AIDL stub reads as success-with-nothing-changed.
     */
    private fun blockOwnershipChange(code: Int, reply: Parcel?) {
        reply?.apply {
            setDataPosition(0)
            setDataSize(0)
            if (ownershipTransactions[code] == true) writeInt(0)
        }
        val ctx = context ?: return
        Handler(Looper.getMainLooper()).post {
            runCatching {
                Toast.makeText(ctx, R.string.dhizuku_ownership_change_blocked, Toast.LENGTH_LONG).show()
            }
        }
        notifyOwnershipBlocked(ctx)
    }

    private fun notifyOwnershipBlocked(ctx: Context) {
        val manager = NotificationManagerCompat.from(ctx)
        val channel = NotificationChannelCompat.Builder(
            OWNERSHIP_BLOCKED_CHANNEL_ID,
            NotificationManager.IMPORTANCE_DEFAULT
        ).setName(ctx.getString(R.string.dhizuku_ownership_changes_channel)).build()
        manager.createNotificationChannel(channel)

        val contentIntent = PendingIntent.getActivity(
            ctx,
            0,
            Intent(ctx, SettingsActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(ctx, OWNERSHIP_BLOCKED_CHANNEL_ID)
            .setContentTitle(ctx.getString(R.string.dhizuku_ownership_change_blocked))
            .setContentText(ctx.getString(R.string.dhizuku_ownership_change_blocked_detail))
            .setSmallIcon(R.drawable.ic_admin_panel_settings_24)
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .build()
        runCatching { manager.notify(OWNERSHIP_BLOCKED_NOTIFICATION_ID, notification) }
    }

    // ------------------------------------------------------------------
    // IDhizuku Binder implementation (V2 AIDL + remote-binder relay)
    // ------------------------------------------------------------------

    private inner class DhizukuServiceBinder : Binder() {

        /** Remote processes handed to this client; destroyed once its binder dies. */
        private val processes = mutableListOf<RemoteProcessBinder>()

        fun trackProcess(process: RemoteProcessBinder) {
            synchronized(processes) { processes.add(process) }
        }

        fun destroyProcesses() {
            val snapshot = synchronized(processes) {
                val copy = processes.toList()
                processes.clear()
                copy
            }
            snapshot.forEach { runCatching { it.destroy() } }
        }

        private val dpm: DevicePolicyManager? by lazy {
            context?.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
        }

        private val adminComponent: ComponentName? by lazy {
            context?.let { ComponentName(it, DhizukuAdminReceiver::class.java) }
        }

        override fun getInterfaceDescriptor(): String = AIDL_DESCRIPTOR

        @Throws(RemoteException::class)
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            // NOTE: no blanket "Dhizuku mode off -> return false" here. Returning false from
            // onTransact leaves the caller with an empty reply parcel, so every AIDL call
            // (including isPermissionGranted) fails in an unspecified way and clients show
            // "Dhizuku 已激活但授权无效". Authorization is enforced per privileged call via
            // enforceCallingPermission(), which consults the mode switch.

            // Remote binder relay — the core Dhizuku feature.
            // Clients call Binder.transact(TRANSACT_CODE_REMOTE_BINDER, ...) with
            // descriptor BINDER_DESCRIPTOR to ask us to forward a transact() call
            // to another binder using *our* identity (Device Owner).
            if (code == TRANSACT_CODE_REMOTE_BINDER) {
                // Security gate first — must run before consuming any parcel data.
                // enforceCallingPermission() verifies caller identity (Dhizuku app DB with
                // signature check, falling back to Shizuku authorization) and throws
                // SecurityException on denial; it is a stricter equivalent of the upstream
                // isCallerAuthorized() gate.
                enforceCallingPermission("remote_transact")

                // Clients assemble this parcel with Parcel.writeInterfaceToken(), whose header
                // size is platform dependent: older releases prefix the string16 descriptor
                // with a single int32 strict-mode field (8 bytes), while Android 15+ prefixes
                // it with three int32 fields (12 bytes: strict policy, work source uid, tag).
                // A fixed readInt() + readString() therefore lands two fields short on newer
                // platforms, and the following readStrongBinder() consumes the descriptor
                // bytes as a binder — every relayed call then fails with an empty result.
                // Locate the token by its known value instead of assuming a header length,
                // which works for both layouts, then read the relay fields from the position
                // immediately after it. The value is matched, not enforced: the authorization
                // gate above remains the security boundary, and a mismatch simply aborts.
                var descriptorOffset = -1
                val scanLimit = minOf(data.dataSize(), 64)
                var scanPos = 0
                while (scanPos <= scanLimit - 4) {
                    data.setDataPosition(scanPos)
                    val candidate = try {
                        data.readString()
                    } catch (e: Exception) {
                        null
                    }
                    if (candidate == BINDER_DESCRIPTOR) {
                        descriptorOffset = scanPos
                        break
                    }
                    scanPos += 4
                }
                if (descriptorOffset < 0) {
                    Timber.tag(TAG).w("remoteTransact: interface token not found, relay aborted")
                    return false
                }
                Timber.tag(TAG).d(
                    "remoteTransact: descriptor=$BINDER_DESCRIPTOR offset=$descriptorOffset " +
                    "size=${data.dataSize()} pos=${data.dataPosition()}"
                )

                val targetBinder = data.readStrongBinder() ?: run {
                    Timber.tag(TAG).w("remoteTransact: target binder missing at pos=${data.dataPosition()}")
                    return false
                }
                val targetCode = data.readInt()
                val targetFlags = data.readInt()

                // Ownership guard. A client with Dhizuku access holds the device-owner
                // identity through us; letting it call transferOwnership / clearDeviceOwner
                // would silently disable every other client and the whole Dhizuku surface.
                // Off by default and switchable in the Device Owner settings page.
                if (!ShizukuSettings.isAllowOwnershipChanges() &&
                    isOwnershipChange(targetBinder, targetCode)
                ) {
                    Timber.tag(TAG).w(
                        "blocked ownership change code=$targetCode uid=${Binder.getCallingUid()}"
                    )
                    blockOwnershipChange(targetCode, reply)
                    return true
                }

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
                    reply?.writeString(SERVICE_VERSION_NAME)
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
                    // Official protocol: spawn a helper process on behalf of the authorized
                    // client. Returning a null binder (the previous behaviour) makes clients
                    // that use Dhizuku.newProcess() crash inside DhizukuRemoteProcess with
                    // "Attempt to invoke interface method 'IBinder IInterface.asBinder()'
                    // on a null object reference".
                    try {
                        val cmd = data.createStringArray()
                        val env = data.createStringArray()
                        val dir = data.readString()
                        enforceCallingPermission("remote_process")
                        if (cmd == null || cmd.isEmpty()) {
                            throw IllegalArgumentException("cmd is empty")
                        }
                        val builder = ProcessBuilder(cmd.toList())
                        if (!env.isNullOrEmpty()) {
                            val environment = builder.environment()
                            environment.clear()
                            for (entry in env) {
                                if (entry == null) continue
                                // Match upstream: drop anything after an embedded NUL so a
                                // client cannot smuggle a second assignment past the check.
                                val clean = entry.substringBefore('\u0000')
                                val eq = clean.indexOf('=')
                                if (eq <= 0) continue
                                environment[clean.substring(0, eq)] = clean.substring(eq + 1)
                            }
                        }
                        if (!dir.isNullOrEmpty()) builder.directory(java.io.File(dir))
                        val process = builder.start()
                        val remote = RemoteProcessBinder(process)
                        trackProcess(remote)
                        reply?.writeNoException()
                        reply?.writeStrongBinder(remote)
                        Timber.tag(TAG).d("remoteProcess started cmd=${cmd.joinToString(" ")}")
                        true
                    } catch (e: Exception) {
                        Timber.tag(TAG).w(e, "remoteProcess failed")
                        try {
                            reply?.writeException(e)
                        } catch (ignored: Exception) {
                        }
                        true
                    }
                }
                Binder.FIRST_CALL_TRANSACTION + 12 -> { // bindUserService
                    // Upstream 541f988 / be2d2dc: the client hosts a Service class of its
                    // own inside a detached process. The component must belong to the
                    // calling uid — otherwise any authorized client could ask us to load
                    // and run another package's code with Device Owner reach.
                    try {
                        val connection = IDhizukuUserServiceConnection.Stub
                            .asInterface(data.readStrongBinder())
                        // AIDL writes `in Bundle` with Parcel.writeTypedObject (an int32 marker
                        // followed by the Parcelable), not with writeBundle. Parcel.readBundle
                        // expects a leading length instead, so reading it that way consumes the
                        // marker (1) as the length and throws
                        // "Bundle length is not aligned by 4: 1". Mirror writeTypedObject.
                        val bundle = if (data.readInt() != 0) {
                            Bundle.CREATOR.createFromParcel(data)
                        } else null
                        enforceCallingPermission("bind_user_service")
                        requireNotNull(connection) { "connection is null" }
                        val args = DhizukuUserServiceArgs(bundle ?: Bundle())
                        enforceComponentBelongsToCaller(args.componentName, "bind_user_service")
                        UserServiceConnections.bind(
                            Binder.getCallingUid(), Binder.getCallingPid(), args, connection
                        )
                        reply?.writeNoException()
                    } catch (e: Exception) {
                        Timber.tag(TAG).w(e, "bindUserService failed")
                        try {
                            reply?.writeException(e)
                        } catch (ignored: Exception) {
                        }
                    }
                    true
                }
                Binder.FIRST_CALL_TRANSACTION + 13 -> { // unbindUserService
                    try {
                        // AIDL writes `in Bundle` with Parcel.writeTypedObject (an int32 marker
                        // followed by the Parcelable), not with writeBundle. Parcel.readBundle
                        // expects a leading length instead, so reading it that way consumes the
                        // marker (1) as the length and throws
                        // "Bundle length is not aligned by 4: 1". Mirror writeTypedObject.
                        val bundle = if (data.readInt() != 0) {
                            Bundle.CREATOR.createFromParcel(data)
                        } else null
                        enforceCallingPermission("unbind_user_service")
                        val args = DhizukuUserServiceArgs(bundle ?: Bundle())
                        enforceComponentBelongsToCaller(args.componentName, "unbind_user_service")
                        UserServiceConnections.unbind(
                            Binder.getCallingUid(), Binder.getCallingPid(), args
                        )
                        reply?.writeNoException()
                    } catch (e: Exception) {
                        Timber.tag(TAG).w(e, "unbindUserService failed")
                        try {
                            reply?.writeException(e)
                        } catch (ignored: Exception) {
                        }
                    }
                    true
                }
                Binder.FIRST_CALL_TRANSACTION + 14 -> { // unbindUserServiceByConnection
                    try {
                        val connection = IDhizukuUserServiceConnection.Stub
                            .asInterface(data.readStrongBinder())
                        // AIDL writes `in Bundle` with Parcel.writeTypedObject (an int32 marker
                        // followed by the Parcelable), not with writeBundle. Parcel.readBundle
                        // expects a leading length instead, so reading it that way consumes the
                        // marker (1) as the length and throws
                        // "Bundle length is not aligned by 4: 1". Mirror writeTypedObject.
                        val bundle = if (data.readInt() != 0) {
                            Bundle.CREATOR.createFromParcel(data)
                        } else null
                        enforceCallingPermission("unbind_user_service")
                        requireNotNull(connection) { "connection is null" }
                        val args = DhizukuUserServiceArgs(bundle ?: Bundle())
                        enforceComponentBelongsToCaller(args.componentName, "unbind_user_service")
                        UserServiceConnections.unbind(
                            Binder.getCallingUid(), Binder.getCallingPid(), args, connection
                        )
                        reply?.writeNoException()
                    } catch (e: Exception) {
                        Timber.tag(TAG).w(e, "unbindUserServiceByConnection failed")
                        try {
                            reply?.writeException(e)
                        } catch (ignored: Exception) {
                        }
                    }
                    true
                }
                Binder.FIRST_CALL_TRANSACTION + 17 -> { // addLockTaskModeListener
                    val listener = data.readStrongBinder()
                        ?.let { IDhizukuLockTaskModeListener.Stub.asInterface(it) }
                    enforceCallingPermission("lock_task_mode_listener")
                    if (listener != null) LockTaskModeListeners.add(listener)
                    reply?.writeNoException()
                    true
                }
                Binder.FIRST_CALL_TRANSACTION + 18 -> { // removeLockTaskModeListener
                    val listener = data.readStrongBinder()
                        ?.let { IDhizukuLockTaskModeListener.Stub.asInterface(it) }
                    enforceCallingPermission("lock_task_mode_listener")
                    if (listener != null) LockTaskModeListeners.remove(listener)
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
                enforceOwnPackage(packageName, "get_delegated_scopes")
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
            enforceOwnPackage(packageName, "set_delegated_scopes")
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

    /**
     * Wraps a local [Process] for the official IDhizukuRemoteProcess AIDL interface.
     *
     * Streams are bridged through [ParcelFileDescriptor] pipes exactly like the upstream
     * server_api RemoteProcess, so clients (DhizukuRemoteProcess on the client side) get
     * a live binder instead of null.
     */
    private class RemoteProcessBinder(private val process: Process) : IDhizukuRemoteProcess.Stub() {

        companion object {
            private val TRANSFER = Executors.newCachedThreadPool { runnable ->
                Thread(runnable, "DhizukuRemoteProcess-transfer").apply { isDaemon = true }
            }
        }

        private var outFd: ParcelFileDescriptor? = null
        private var inFd: ParcelFileDescriptor? = null
        private var errFd: ParcelFileDescriptor? = null

        override fun getOutputStream(): ParcelFileDescriptor = synchronized(this) {
            outFd ?: pipeTo(process.outputStream).also { outFd = it }
        }

        override fun getInputStream(): ParcelFileDescriptor = synchronized(this) {
            inFd ?: pipeFrom(process.inputStream).also { inFd = it }
        }

        override fun getErrorStream(): ParcelFileDescriptor = synchronized(this) {
            errFd ?: pipeFrom(process.errorStream).also { errFd = it }
        }

        override fun exitValue(): Int = process.exitValue()

        override fun destroy() {
            // Process.destroy() may throw IllegalThreadStateException on some vendor
            // implementations; never leak that back to the client as a binder exception.
            runCatching { process.destroy() }
        }

        override fun alive(): Boolean = try {
            process.exitValue()
            false
        } catch (e: IllegalThreadStateException) {
            true
        }

        override fun waitFor(): Int = try {
            process.waitFor()
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            -1
        }

        override fun waitForTimeout(timeout: Long, unit: String): Boolean {
            val timeUnit = runCatching { TimeUnit.valueOf(unit) }.getOrDefault(TimeUnit.MILLISECONDS)
            val startTime = System.nanoTime()
            var remaining = timeUnit.toNanos(timeout)
            do {
                try {
                    process.exitValue()
                    return true
                } catch (e: IllegalThreadStateException) {
                    if (remaining > 0) {
                        try {
                            Thread.sleep(Math.min(TimeUnit.NANOSECONDS.toMillis(remaining) + 1, 100))
                        } catch (e2: InterruptedException) {
                            Thread.currentThread().interrupt()
                            return false
                        }
                    }
                }
                remaining = timeUnit.toNanos(timeout) - (System.nanoTime() - startTime)
            } while (remaining > 0)
            return false
        }

        private fun pipeFrom(source: InputStream): ParcelFileDescriptor {
            val pipe = ParcelFileDescriptor.createPipe()
            TRANSFER.execute {
                try {
                    ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]).use { out -> source.copyTo(out) }
                } catch (e: Exception) {
                    Timber.tag(TAG).w(e, "remote process stdout/stderr transfer failed")
                } finally {
                    runCatching { source.close() }
                }
            }
            return pipe[0]
        }

        private fun pipeTo(target: OutputStream): ParcelFileDescriptor {
            val pipe = ParcelFileDescriptor.createPipe()
            TRANSFER.execute {
                try {
                    ParcelFileDescriptor.AutoCloseInputStream(pipe[0]).use { input -> input.copyTo(target) }
                } catch (e: Exception) {
                    Timber.tag(TAG).w(e, "remote process stdin transfer failed")
                } finally {
                    runCatching { target.close() }
                }
            }
            return pipe[1]
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

        // Upstream keeps one service per (uid, pid) and drops it when the client binder
        // dies — that is what scopes remote processes to the client instead of leaking
        // them for the lifetime of the provider. A call without a client binder (adb
        // `content call`, older integrations) still gets a throwaway instance so the
        // protocol stays diagnosable.
        if (clientBinder == null) {
            val bundle = Bundle()
            bundle.putBinder(PARAM_DHIZUKU_BINDER, DhizukuServiceBinder())
            Timber.tag(TAG).d("Returning uncached DhizukuServiceBinder to uid=$callingUid")
            return bundle
        }

        val key = "$callingUid:${Binder.getCallingPid()}"
        val service = synchronized(services) {
            services[key] ?: DhizukuServiceBinder().also { created ->
                services[key] = created
                try {
                    clientBinder.linkToDeath({
                        synchronized(services) {
                            if (services[key] === created) services.remove(key)
                        }
                        created.destroyProcesses()
                    }, 0)
                } catch (e: RemoteException) {
                    services.remove(key)
                    return null
                }
            }
        }

        val bundle = Bundle()
        bundle.putBinder(PARAM_DHIZUKU_BINDER, service)
        Timber.tag(TAG).d("Returning DhizukuServiceBinder to uid=$callingUid key=$key")
        return bundle
    }
}
