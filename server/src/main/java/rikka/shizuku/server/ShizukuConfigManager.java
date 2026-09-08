package rikka.shizuku.server;

import static rikka.shizuku.server.ServerConstants.PERMISSION;

import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.system.ErrnoException;
import android.system.Os;
import android.util.AtomicFile;

import androidx.annotation.Nullable;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import kotlin.collections.ArraysKt;
import af.shizuku.common.compat.Android17Compat;
import af.shizuku.common.compat.InstalledPackagesCompat;
import rikka.hidden.compat.UserManagerApis;
import rikka.shizuku.server.ktx.HandlerKt;

public class ShizukuConfigManager extends ConfigManager {

    private static final Gson GSON_IN = new GsonBuilder()
            .create();
    private static final Gson GSON_OUT = new GsonBuilder()
            .setVersion(ShizukuConfig.LATEST_VERSION)
            .create();

    // Write delay is a coalescing window for non-permission changes (constructor repairs,
    // removals). Permission grant/revoke bypasses it entirely and writes synchronously in
    // update() - a 10s window meant a just-granted app lost its authorization if the server
    // process was killed by the system before the delayed write fired (reported: ShizukuX
    // killed -> gkd authorization lost).
    private static final long WRITE_DELAY = 1000;

    private static final File FILE = getConfigFile();
    private static final AtomicFile ATOMIC_FILE = new AtomicFile(FILE);

    // Secondary copy written on every save (dual-write). The primary file lives under
    // /data/user_de/0/com.android.shell/ when available; this shell-writable backup survives
    // cases where the primary is wiped (e.g. app data reset of com.android.shell, or a bad
    // primary file) so authorizations can still be recovered on next server start.
    private static final File BACKUP_FILE = new File("/data/local/tmp/shizuku.json.bak");

    private static File getConfigFile() {
        File shellFile = new File("/data/user_de/0/com.android.shell/shizuku.json");
        if (shellFile.exists()) {
            return shellFile;
        }
        try {
            File parent = shellFile.getParentFile();
            if (parent != null && parent.exists() && parent.canWrite()) {
                return shellFile;
            }
        } catch (Throwable ignored) {}
        return new File("/data/local/tmp/shizuku.json");
    }

    public static ShizukuConfig load() {
        FileInputStream stream;
        try {
            stream = ATOMIC_FILE.openRead();
        } catch (FileNotFoundException e) {
            LOGGER.i("no existing config file " + ATOMIC_FILE.getBaseFile() + "; trying backup");
            try {
                stream = new FileInputStream(BACKUP_FILE);
            } catch (FileNotFoundException e2) {
                LOGGER.i("no backup config either; starting empty");
                return new ShizukuConfig();
            }
        }

        ShizukuConfig config = null;
        try {
            config = GSON_IN.fromJson(new InputStreamReader(stream), ShizukuConfig.class);
        } catch (Throwable tr) {
            LOGGER.e(tr, "load config");
        } finally {
            try {
                stream.close();
            } catch (IOException e) {
                LOGGER.w("failed to close: " + e);
            }
        }
        if (config != null) return config;
        return new ShizukuConfig();
    }

    public static void write(ShizukuConfig config) {
        synchronized (ATOMIC_FILE) {
            FileOutputStream stream;
            try {
                stream = ATOMIC_FILE.startWrite();
            } catch (IOException e) {
                LOGGER.e("failed to write state: " + e);
                return;
            }

            try {
                String json = GSON_OUT.toJson(config);
                stream.write(json.getBytes());

                ATOMIC_FILE.finishWrite(stream);

                // Closing #419: the config file was previously world-readable/writable.
                // Root bypasses DAC checks entirely, so it doesn't need explicit bits here;
                // only shell needs explicit access, granted via group ownership. No "other"
                // access is needed, so we restrict to owner+group read/write only.
                File file = ATOMIC_FILE.getBaseFile();
                if (file.exists()) {
                    try {
                        Os.chmod(file.getAbsolutePath(), 0660);
                        Os.chown(file.getAbsolutePath(), -1, android.os.Process.SHELL_UID);
                    } catch (ErrnoException e) {
                        LOGGER.w("failed to set permissions on " + file.getAbsolutePath() + ": " + e);
                    }
                }
                LOGGER.v("config saved to " + file.getAbsolutePath());

                writeBackup(json);
            } catch (Throwable tr) {
                LOGGER.e(tr, "can't save %s, restoring backup.", ATOMIC_FILE.getBaseFile());
                ATOMIC_FILE.failWrite(stream);
            }
        }
    }

    private static void writeBackup(String json) {
        try {
            FileOutputStream b = new FileOutputStream(BACKUP_FILE);
            try {
                b.write(json.getBytes());
            } finally {
                b.close();
            }
            try {
                Os.chmod(BACKUP_FILE.getAbsolutePath(), 0660);
                Os.chown(BACKUP_FILE.getAbsolutePath(), -1, android.os.Process.SHELL_UID);
            } catch (ErrnoException e) {
                LOGGER.w("failed to set permissions on backup config: " + e);
            }
            LOGGER.v("backup config saved to " + BACKUP_FILE.getAbsolutePath());
        } catch (Throwable tr) {
            LOGGER.w(tr, "failed to write backup config");
        }
    }

    private final Runnable mWriteRunner = new Runnable() {

        @Override
        public void run() {
            write(config);
        }
    };

    private final ShizukuConfig config;

    public ShizukuConfigManager() {
        this.config = load();

        boolean changed = false;

        if (config.packages == null) {
            config.packages = new ArrayList<>();
            changed = true;
        }

        Map<Integer, List<String>> packagesByUid = new HashMap<>();
        List<PackageInfo> allPackages = new ArrayList<>();

        for (int userId : UserManagerApis.getUserIdsNoThrow()) {
            for (PackageInfo pi : InstalledPackagesCompat.getInstalledPackagesNoThrow(PackageManager.GET_PERMISSIONS | PackageManager.MATCH_ALL, userId)) {
                if (pi == null || pi.applicationInfo == null) continue;
                allPackages.add(pi);

                List<String> list = packagesByUid.get(pi.applicationInfo.uid);
                if (list == null) {
                    list = new ArrayList<>();
                    packagesByUid.put(pi.applicationInfo.uid, list);
                }
                list.add(pi.packageName);
            }
        }

        for (ShizukuConfig.PackageEntry entry : new ArrayList<>(config.packages)) {
            if (entry.packages == null) {
                entry.packages = new ArrayList<>();
            }

            List<String> packages = packagesByUid.get(entry.uid);
            if (packages == null || packages.isEmpty()) {
                // Previously this pruned the entry ("uid has gone"). That turned transient
                // package-list query gaps (server-side enumeration is reflective on Android 17+,
                // and per-user queries can be incomplete right after an install/update) into
                // permanent authorization loss: the next restart would never re-grant an app the
                // user had already allowed. Keeping the entry is safe: if the app is truly gone,
                // it just stays dormant (UI filters it out); if it is reinstalled with the same
                // uid, the authorization comes back automatically.
                LOGGER.w("uid %d not found in current package list; keeping config entry to preserve authorization", entry.uid);
                continue;
            }

            if (entry.packages.isEmpty()) {
                // Entries created via the plain toggle path (updateFlagsForUid) used to be
                // written with no package names at all - that's missing data, not evidence this
                // uid's packages changed. Treating it as "changed" pruned a still-valid
                // authorization on every server restart, and separately made getApplications()
                // exclude the package from the authorized list on the very next refresh (its
                // membership check on this same empty list always fails). Backfill from the
                // live package list instead.
                LOGGER.i("backfilling empty packages list for uid %d from current package manager state", entry.uid);
                entry.packages.addAll(packages);
                changed = true;
                continue;
            }

            boolean packagesChanged = true;

            for (String packageName : entry.packages) {
                if (packages.contains(packageName)) {
                    packagesChanged = false;
                    break;
                }
            }

            final int rawSize = entry.packages.size();
            Set<String> s = new LinkedHashSet<>(entry.packages);
            entry.packages.clear();
            entry.packages.addAll(s);
            final int shrunkSize = entry.packages.size();
            if (shrunkSize < rawSize) {
                LOGGER.w("entry.packages has duplicate! Shrunk. (%d -> %d)", rawSize, shrunkSize);
            }

            if (packagesChanged) {
                // Same rationale as above: a package-list mismatch after an app update or a
                // partial query is not evidence the user revoked the grant, so keep the entry.
                LOGGER.w("uid %d package list changed; keeping config entry to preserve authorization", entry.uid);
            }
        }

        for (PackageInfo pi : allPackages) {
            if (pi.requestedPermissions == null) {
                continue;
            }

                String activePerm = null;
                if (ArraysKt.contains(pi.requestedPermissions, PERMISSION)) activePerm = PERMISSION;
                else if (ArraysKt.contains(pi.requestedPermissions, ServerConstants.PERMISSION_LEGACY)) activePerm = ServerConstants.PERMISSION_LEGACY;
                else if (ArraysKt.contains(pi.requestedPermissions, ServerConstants.PERMISSION_ORIGINAL)) activePerm = ServerConstants.PERMISSION_ORIGINAL;

                if (activePerm == null) continue;

                int uid = pi.applicationInfo.uid;
                boolean allowed;
                try {
                    allowed = Android17Compat.checkPermission(activePerm, uid) == PackageManager.PERMISSION_GRANTED;
                } catch (Throwable e) {
                    LOGGER.w("checkPermission");
                    continue;
                }

                if (allowed) {
                    List<String> packages = new ArrayList<>();
                    packages.add(pi.packageName);
                    updateLocked(uid, packages, ConfigManager.MASK_PERMISSION, ConfigManager.FLAG_ALLOWED);
                    changed = true;
                }
        }

        // Always persist once on start: (1) re-applies any constructor repairs, (2) refreshes
        // the dual-write backup so recovery always has a fresh copy even when nothing changed.
        write(config);
    }

    private void scheduleWriteLocked() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (HandlerKt.getWorkerHandler().hasCallbacks(mWriteRunner)) {
                return;
            }
        } else {
            HandlerKt.getWorkerHandler().removeCallbacks(mWriteRunner);
        }
        HandlerKt.getWorkerHandler().postDelayed(mWriteRunner, WRITE_DELAY);
    }

    private ShizukuConfig.PackageEntry findLocked(int uid) {
        for (ShizukuConfig.PackageEntry entry : config.packages) {
            if (uid == entry.uid) {
                return entry;
            }
        }
        return null;
    }

    @Nullable
    public ShizukuConfig.PackageEntry find(int uid) {
        synchronized (this) {
            return findLocked(uid);
        }
    }

    public List<Integer> getAllowedUids() {
        synchronized (this) {
            List<Integer> result = new ArrayList<>();
            for (ShizukuConfig.PackageEntry entry : config.packages) {
                if ((entry.flags & ConfigManager.FLAG_ALLOWED) != 0) {
                    result.add(entry.uid);
                }
            }
            return result;
        }
    }

    private void updateLocked(int uid, List<String> packages, int mask, int values) {
        ShizukuConfig.PackageEntry entry = findLocked(uid);
        if (entry == null) {
            entry = new ShizukuConfig.PackageEntry(uid, mask & values);
            config.packages.add(entry);
        } else {
            int newValue = (entry.flags & ~mask) | (mask & values);
            if (newValue == entry.flags) {
                return;
            }
            entry.flags = newValue;
        }
        if (packages != null) {
            for (String packageName : packages) {
                if (entry.packages.contains(packageName)) {
                    continue;
                }
                entry.packages.add(packageName);
            }
        }
        scheduleWriteLocked();
    }

    public void update(int uid, List<String> packages, int mask, int values) {
        synchronized (this) {
            updateLocked(uid, packages, mask, values);
            if ((mask & ConfigManager.MASK_PERMISSION) != 0) {
                // Permission grant/revoke is the critical case: write synchronously so a kill
                // right after the user taps "allow" cannot lose the authorization (10s delayed
                // write used to drop it when the process died inside the window).
                HandlerKt.getWorkerHandler().removeCallbacks(mWriteRunner);
                write(config);
            }
        }
    }

    private void removeLocked(int uid) {
        ShizukuConfig.PackageEntry entry = findLocked(uid);
        if (entry == null) {
            return;
        }
        config.packages.remove(entry);
        scheduleWriteLocked();
    }

    public void remove(int uid) {
        synchronized (this) {
            removeLocked(uid);
        }
    }
}
