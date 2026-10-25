package rikka.shizuku.server.api;

import android.content.AttributionSource;
import android.content.IContentProvider;
import android.os.Build;
import android.os.Bundle;
import android.os.RemoteException;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import rikka.shizuku.server.util.OsUtils;

public class IContentProviderUtils {

    public static Bundle callCompat(@NonNull IContentProvider provider, @Nullable String callingPkg, @Nullable String authority, @Nullable String method, @Nullable String arg, @Nullable Bundle extras) throws RemoteException {
        Bundle result;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // The AttributionSource must describe the *shell* process itself (uid from
            // OsUtils.getUid()); on Android 12+ the system cross-checks the package against
            // the uid. Default the package to "com.android.shell" when the caller passed
            // none, instead of asserting the shell uid is some arbitrary client package
            // (audit slice4 P3-13).
            String attributionPackage = callingPkg != null ? callingPkg : "com.android.shell";
            result = provider.call((new AttributionSource.Builder(OsUtils.getUid())).setPackageName(attributionPackage).build(), authority, method, arg, extras);
        } else if (Build.VERSION.SDK_INT >= 30) {
            result = provider.call(callingPkg, (String) null, authority, method, arg, extras);
        } else if (Build.VERSION.SDK_INT >= 29) {
            result = provider.call(callingPkg, authority, method, arg, extras);
        } else {
            result = provider.call(callingPkg, method, arg, extras);
        }

        return result;
    }
}
