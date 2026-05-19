package af.shizuku.common.util;

import android.app.UiModeManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.os.Build;
import android.os.SystemProperties;

public class EnvironmentUtils {

    public static boolean isWatch(Context context) {
        return (context.getSystemService(UiModeManager.class).getCurrentModeType()
                == Configuration.UI_MODE_TYPE_WATCH);
    }

    public static boolean isTelevision(Context context) {
        return (context.getSystemService(UiModeManager.class).getCurrentModeType()
                == Configuration.UI_MODE_TYPE_TELEVISION ||
                context.getPackageManager().hasSystemFeature(PackageManager.FEATURE_LEANBACK));
    }

    public static boolean isTlsSupported(Context context) {
        if (isTelevision(context))
            return Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU;
        else 
            return Build.VERSION.SDK_INT >= Build.VERSION_CODES.R;
    }

    public static int getFullSdkVersion() {
        if (Build.VERSION.SDK_INT >= 36) {
            try {
                java.lang.reflect.Field field = Build.VERSION.class.getField("SDK_INT_FULL");
                return field.getInt(null);
            } catch (Exception e) {
                return Build.VERSION.SDK_INT * 100;
            }
        } else {
            return Build.VERSION.SDK_INT * 100;
        }
    }

    public static boolean isSamsung() {
        return Build.MANUFACTURER.equalsIgnoreCase("samsung");
    }

    public static int getOneUiVersion() {
        if (!isSamsung()) return -1;
        try {
            java.lang.reflect.Field field = Build.VERSION.class.getDeclaredField("SEM_PLATFORM_INT");
            return ((int) field.get(null) - 90000) / 10000;
        } catch (Exception e) {
            if (Build.VERSION.SDK_INT >= 36) return 8;
            return -1;
        }
    }

    public static boolean isOneUi8() {
        return isSamsung() && getOneUiVersion() >= 8;
    }

    public static boolean isOppo() {
        return Build.MANUFACTURER.equalsIgnoreCase("oppo") || Build.MANUFACTURER.equalsIgnoreCase("realme");
    }

    public static boolean isOnePlus() {
        return Build.MANUFACTURER.equalsIgnoreCase("oneplus");
    }

    public static boolean isXiaomi() {
        return Build.MANUFACTURER.equalsIgnoreCase("xiaomi") || Build.MANUFACTURER.equalsIgnoreCase("redmi") || Build.MANUFACTURER.equalsIgnoreCase("poco");
    }

    public static boolean isTCL() {
        return Build.MANUFACTURER.equalsIgnoreCase("tcl");
    }

    public static boolean isDeX(Context context) {
        Configuration config = context.getResources().getConfiguration();
        try {
            java.lang.reflect.Field field = config.getClass().getField("semDesktopModeEnabled");
            return field.getInt(config) == 1;
        } catch (Exception e) {
            return (config.uiMode & Configuration.UI_MODE_TYPE_MASK) == Configuration.UI_MODE_TYPE_DESK;
        }
    }

    public static boolean isSecondaryUser() {
        try {
            return !android.os.Process.myUserHandle().toString().contains("{0}");
        } catch (Exception e) {
            return false;
        }
    }

    public static int getAdbTcpPort() {
        int port = SystemProperties.getInt("service.adb.tcp.port", -1);
        if (port == -1) port = SystemProperties.getInt("persist.adb.tcp.port", -1);
        return port;
    }
}
