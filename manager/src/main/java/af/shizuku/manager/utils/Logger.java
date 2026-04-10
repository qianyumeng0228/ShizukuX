package af.shizuku.manager.utils;

import android.util.Log;
import timber.log.Timber;

import java.util.Locale;

public class Logger {

    public static final Logger LOGGER = new Logger("ShizukuManager");

    private String TAG;

    public Logger(String TAG) {
        this.TAG = TAG;
    }

    public boolean isLoggable(String tag, int level) {
        return true;
    }

    public void v(String msg) {
        if (isLoggable(TAG, Log.VERBOSE)) {
            Timber.tag(TAG).v(msg);
        }
    }

    public void v(String fmt, Object... args) {
        if (isLoggable(TAG, Log.VERBOSE)) {
            Timber.tag(TAG).v(args, String.format(Locale.ENGLISH, fmt));
        }
    }

    public void v(String msg, Throwable tr) {
        if (isLoggable(TAG, Log.VERBOSE)) {
            Timber.tag(TAG).v(tr, msg);
        }
    }

    public void d(String msg) {
        if (isLoggable(TAG, Log.DEBUG)) {
            Timber.tag(TAG).d(msg);
        }
    }

    public void d(String fmt, Object... args) {
        if (isLoggable(TAG, Log.DEBUG)) {
            Timber.tag(TAG).d(args, String.format(Locale.ENGLISH, fmt));
        }
    }

    public void d(String msg, Throwable tr) {
        if (isLoggable(TAG, Log.DEBUG)) {
            Timber.tag(TAG).d(tr, msg);
        }
    }

    public void i(String msg) {
        if (isLoggable(TAG, Log.INFO)) {
            Timber.tag(TAG).i(msg);
        }
    }

    public void i(String fmt, Object... args) {
        if (isLoggable(TAG, Log.INFO)) {
            Timber.tag(TAG).i(args, String.format(Locale.ENGLISH, fmt));
        }
    }

    public void i(String msg, Throwable tr) {
        if (isLoggable(TAG, Log.INFO)) {
            Timber.tag(TAG).i(tr, msg);
        }
    }

    public void w(String msg) {
        if (isLoggable(TAG, Log.WARN)) {
            Timber.tag(TAG).w(msg);
        }
    }

    public void w(String fmt, Object... args) {
        if (isLoggable(TAG, Log.WARN)) {
            Timber.tag(TAG).w(args, String.format(Locale.ENGLISH, fmt));
        }
    }

    public void w(Throwable tr, String fmt, Object... args) {
        if (isLoggable(TAG, Log.WARN)) {
            Timber.tag(TAG).w(args, String.format(Locale.ENGLISH, fmt), tr);
        }
    }

    public void w(String msg, Throwable tr) {
        if (isLoggable(TAG, Log.WARN)) {
            Timber.tag(TAG).w(tr, msg);
        }
    }

    public void e(String msg) {
        if (isLoggable(TAG, Log.ERROR)) {
            Timber.tag(TAG).e(msg);
        }
    }

    public void e(String fmt, Object... args) {
        if (isLoggable(TAG, Log.ERROR)) {
            Timber.tag(TAG).e(args, String.format(Locale.ENGLISH, fmt));
        }
    }

    public void e(String msg, Throwable tr) {
        if (isLoggable(TAG, Log.ERROR)) {
            Timber.tag(TAG).e(tr, msg);
        }
    }

    public void e(Throwable tr, String fmt, Object... args) {
        if (isLoggable(TAG, Log.ERROR)) {
            Timber.tag(TAG).e(args, String.format(Locale.ENGLISH, fmt), tr);
        }
    }
}
