package com.rosan.dhizuku.server_api;

import android.annotation.SuppressLint;
import android.content.Context;

import com.rosan.app_process.AppProcess;

/**
 * Hosts the Dhizuku UserService machinery.
 * <p>
 * {@link AppProcess.None} means this object itself does not own a child process: the
 * per-client Service still runs in its own app_process because isolatedServiceBinder()
 * goes through NewProcessReceiver. Local calls (i.e. nothing) stay in the provider.
 */
public class DhizukuProcess extends AppProcess.None {
    private DhizukuProcess() {
        init();
    }

    public Context getContext() {
        return this.mContext;
    }

    @SuppressLint("StaticFieldLeak")
    private static final DhizukuProcess mInstance = new DhizukuProcess();

    public static DhizukuProcess get() {
        return mInstance;
    }
}
