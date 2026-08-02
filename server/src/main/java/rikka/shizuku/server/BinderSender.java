package rikka.shizuku.server;

import static android.app.ActivityManagerHidden.UID_OBSERVER_ACTIVE;
import static android.app.ActivityManagerHidden.UID_OBSERVER_CACHED;
import static android.app.ActivityManagerHidden.UID_OBSERVER_GONE;
import static android.app.ActivityManagerHidden.UID_OBSERVER_IDLE;

import android.app.ActivityManagerHidden;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.RemoteException;
import android.text.TextUtils;

import androidx.annotation.RequiresApi;

import java.util.ArrayList;
import java.util.List;

import kotlin.collections.ArraysKt;
import rikka.hidden.compat.ActivityManagerApis;
import rikka.hidden.compat.PackageManagerApis;
import rikka.hidden.compat.UserManagerApis;
import af.shizuku.common.compat.Android17Compat;
import af.shizuku.common.compat.InstalledPackagesCompat;
import rikka.hidden.compat.adapter.ProcessObserverAdapter;
import rikka.hidden.compat.adapter.UidObserverAdapter;
import rikka.shizuku.server.util.HandlerUtil;
import rikka.shizuku.server.util.Logger;

public class BinderSender {

    private static final Logger LOGGER = new Logger("BinderSender");

    private static final String PERMISSION_MANAGER = "af.shizuku.plus.permission.MANAGER";
    private static final String PERMISSION = ServerConstants.PERMISSION;
    private static final String PERMISSION_LEGACY = ServerConstants.PERMISSION_LEGACY;
    private static final String PERMISSION_ORIGINAL = ServerConstants.PERMISSION_ORIGINAL;

    private static ShizukuService sShizukuService;

    private static class ProcessObserver extends ProcessObserverAdapter {

        private static final List<Integer> PID_LIST = new ArrayList<>();

        @Override
        public void onForegroundActivitiesChanged(int pid, int uid, boolean foregroundActivities) throws RemoteException {
            LOGGER.d("onForegroundActivitiesChanged: pid=%d, uid=%d, foregroundActivities=%s", pid, uid, foregroundActivities ? "true" : "false");

            synchronized (PID_LIST) {
                if (PID_LIST.contains(pid) || !foregroundActivities) {
                    return;
                }
                PID_LIST.add(pid);
            }

            if (!sendBinder(uid, pid)) {
                // Delivery to a Shizuku client failed - most often because the app's ContentProvider
                // wasn't published yet when this foreground event fired (a startup race). Drop the pid
                // so a later foreground/state-change event retries, instead of caching the failure for
                // the whole process lifetime and leaving the app permanently "not registered" (#319).
                synchronized (PID_LIST) {
                    PID_LIST.remove(Integer.valueOf(pid));
                }
            }
        }

        @Override
        public void onProcessDied(int pid, int uid) {
            LOGGER.d("onProcessDied: pid=%d, uid=%d", pid, uid);

            synchronized (PID_LIST) {
                int index = PID_LIST.indexOf(pid);
                if (index != -1) {
                    PID_LIST.remove(index);
                }
            }
        }

        @Override
        public void onProcessStateChanged(int pid, int uid, int procState) throws RemoteException {
            LOGGER.d("onProcessStateChanged: pid=%d, uid=%d, procState=%d", pid, uid, procState);

            synchronized (PID_LIST) {
                if (PID_LIST.contains(pid)) {
                    return;
                }
                PID_LIST.add(pid);
            }

            if (!sendBinder(uid, pid)) {
                // See onForegroundActivitiesChanged: don't cache a failed delivery forever (#319).
                synchronized (PID_LIST) {
                    PID_LIST.remove(Integer.valueOf(pid));
                }
            }
        }
    }

    @RequiresApi(api = Build.VERSION_CODES.N)
    private static class UidObserver extends UidObserverAdapter {

        private static final List<Integer> UID_LIST = new ArrayList<>();

        @Override
        public void onUidActive(int uid) throws RemoteException {
            LOGGER.d("onUidCachedChanged: uid=%d", uid);

            uidStarts(uid);
        }

        @Override
        public void onUidCachedChanged(int uid, boolean cached) throws RemoteException {
            LOGGER.d("onUidCachedChanged: uid=%d, cached=%s", uid, Boolean.toString(cached));

            if (!cached) {
                uidStarts(uid);
            }
        }

        @Override
        public void onUidIdle(int uid, boolean disabled) throws RemoteException {
            LOGGER.d("onUidIdle: uid=%d, disabled=%s", uid, Boolean.toString(disabled));

            uidStarts(uid);
        }

        @Override
        public void onUidGone(int uid, boolean disabled) throws RemoteException {
            LOGGER.d("onUidGone: uid=%d, disabled=%s", uid, Boolean.toString(disabled));

            uidGone(uid);
        }

        private void uidStarts(int uid) throws RemoteException {
            synchronized (UID_LIST) {
                if (UID_LIST.contains(uid)) {
                    LOGGER.v("Uid %d already starts", uid);
                    return;
                }
                UID_LIST.add(uid);
                LOGGER.v("Uid %d starts", uid);
            }

            if (!sendBinder(uid, -1)) {
                // Don't cache a failed delivery forever - let a later uid event retry (#319).
                synchronized (UID_LIST) {
                    UID_LIST.remove(Integer.valueOf(uid));
                }
            }
        }

        private void uidGone(int uid) {
            synchronized (UID_LIST) {
                int index = UID_LIST.indexOf(uid);
                if (index != -1) {
                    UID_LIST.remove(index);
                    LOGGER.v("Uid %d dead", uid);
                }
            }
        }
    }

    /**
     * @return {@code true} if this uid/pid can be remembered as handled (either it's not a Shizuku
     * client, or a binder was successfully delivered); {@code false} only when a Shizuku client was
     * found but delivery failed, so the caller should let a later observer event retry (#319).
     */
    private static boolean sendBinder(int uid, int pid) throws RemoteException {
        List<String> packages = PackageManagerApis.getPackagesForUidNoThrow(uid);
        if (packages.isEmpty())
            return true;

        LOGGER.d("sendBinder to uid %d: packages=%s", uid, TextUtils.join(", ", packages));

        int userId = uid / 100000;
        for (String packageName : packages) {
            PackageInfo pi = Android17Compat.getPackageInfo(packageName, PackageManager.GET_PERMISSIONS, userId);
            if (pi == null || pi.requestedPermissions == null)
                continue;

            if (ArraysKt.contains(pi.requestedPermissions, PERMISSION_MANAGER)) {
                boolean granted = false;
                try {
                    if (pid == -1)
                        granted = Android17Compat.checkPermission(PERMISSION_MANAGER, uid) == PackageManager.PERMISSION_GRANTED;
                    else
                        granted = ActivityManagerApis.checkPermission(PERMISSION_MANAGER, pid, uid) == PackageManager.PERMISSION_GRANTED;
                } catch (Throwable e) {
                    LOGGER.e("checkPermission failed for manager");
                }

                if (granted) {
                    // sendBinderToManager has its own kill-and-retry path, so treat it as handled.
                    ShizukuService.sendBinderToManager(sShizukuService, userId);
                    return true;
                }
            } else if (ArraysKt.contains(pi.requestedPermissions, PERMISSION) ||
                       ArraysKt.contains(pi.requestedPermissions, PERMISSION_LEGACY) ||
                       ArraysKt.contains(pi.requestedPermissions, PERMISSION_ORIGINAL)) {
                return ShizukuService.sendBinderToUserApp(sShizukuService, packageName, userId);
            }
        }
        return true;
    }

    public static void register(ShizukuService shizukuService) {
        sShizukuService = shizukuService;

        try {
            ActivityManagerApis.registerProcessObserver(new ProcessObserver());
        } catch (Throwable tr) {
            LOGGER.e(tr, "registerProcessObserver");
        }

        if (Build.VERSION.SDK_INT >= 26) {
            int flags = UID_OBSERVER_GONE | UID_OBSERVER_IDLE | UID_OBSERVER_ACTIVE;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                flags |= UID_OBSERVER_CACHED;
            }
            try {
                ActivityManagerApis.registerUidObserver(new UidObserver(), flags,
                        ActivityManagerHidden.PROCESS_STATE_UNKNOWN,
                        null);
            } catch (Throwable tr) {
                LOGGER.e(tr, "registerUidObserver");
            }
        }

        // The observers above only fire on a *transition* (foreground, uid active/idle/cached,
        // process died). A client that was already running - and possibly already frozen - at
        // the moment the server (re)starts never produces one of those transitions on its own,
        // so it would otherwise sit waiting indefinitely for the next unrelated state change
        // (see #371). Do one delayed catch-up pass instead: HandlerUtil's main handler needs the
        // rest of ShizukuService's startup to finish setting it up first (setMainHandler runs
        // earlier in the same constructor), so defer slightly rather than run inline here.
        HandlerUtil.getMainHandler().postDelayed(BinderSender::catchUpAlreadyRunningClients, 2000);
    }

    // AOSP's ActivityManager.PROCESS_STATE_NONEXISTENT (frameworks/base ProcessStateEnum.aidl) -
    // used as an integer literal rather than a symbolic import since this exact constant name
    // isn't confirmed present in the dev.rikka.hidden:compat stub surface this module already
    // depends on for the sibling PROCESS_STATE_UNKNOWN constant above.
    private static final int PROCESS_STATE_NONEXISTENT = 20;

    /**
     * Re-delivers the Shizuku binder to every already-authorized client that's already running
     * (including cached/frozen) at server-start time, rather than relying solely on the next
     * foreground/uid-transition event to trigger it (#371). Deliberately checks each candidate's
     * live process state first via {@link ActivityManagerApis#getPackageProcessState} instead of
     * unconditionally calling {@link #sendBinder} for every package that declares the Shizuku
     * permission - the latter would resolve a ContentProvider that isn't published yet and can
     * force-start the app's process, turning a "catch up already-running clients" pass into
     * "launch every authorized app on every server boot."
     */
    private static void catchUpAlreadyRunningClients() {
        try {
            for (int userId : UserManagerApis.getUserIdsNoThrow()) {
                for (PackageInfo pi : InstalledPackagesCompat.getInstalledPackagesNoThrow(PackageManager.GET_PERMISSIONS, userId)) {
                    if (pi == null || pi.applicationInfo == null || pi.requestedPermissions == null) continue;

                    if (!ArraysKt.contains(pi.requestedPermissions, PERMISSION_MANAGER) &&
                            !ArraysKt.contains(pi.requestedPermissions, PERMISSION) &&
                            !ArraysKt.contains(pi.requestedPermissions, PERMISSION_LEGACY) &&
                            !ArraysKt.contains(pi.requestedPermissions, PERMISSION_ORIGINAL)) {
                        continue;
                    }

                    int state;
                    try {
                        state = ActivityManagerApis.getPackageProcessState(pi.packageName, userId, "com.android.shell");
                    } catch (Throwable e) {
                        // Can't confirm it's actually running - skip rather than risk force-starting it.
                        continue;
                    }
                    if (state < 0 || state >= PROCESS_STATE_NONEXISTENT) continue;

                    try {
                        sendBinder(pi.applicationInfo.uid, -1);
                    } catch (Throwable e) {
                        LOGGER.w(e, "catch-up sendBinder failed for uid %d", pi.applicationInfo.uid);
                    }
                }
            }
        } catch (Throwable tr) {
            LOGGER.e(tr, "catchUpAlreadyRunningClients failed");
        }
    }
}
