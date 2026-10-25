package rikka.shizuku.server;

import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.util.Log;
import java.io.IOException;
import moe.shizuku.server.IRemoteProcess;

/**
 * Adapter that presents an already-completed "cat over pipe" as an IRemoteProcess.
 *
 * Contract (audit slice4 P2-5 / P3-11): the wrapped PFD belongs to a producer that has
 * already exited by construction, so the process is *not alive* and {@link #waitFor()} /
 * {@link #exitValue()} return the baked exit code — callers must not treat {@link #alive()}
 * == false as "the child crashed mid-stream". There is no stdin (getOutputStream()==null)
 * and no separate error stream; {@link #destroy()} closes the read-side PFD.
 */
public class ProxyRemoteProcess extends IRemoteProcess.Stub {
    private static final String TAG = "ProxyRemoteProcess";
    private final ParcelFileDescriptor pfd;
    private final int exitValue;

    public ProxyRemoteProcess(ParcelFileDescriptor pfd, int exitValue) {
        this.pfd = pfd;
        this.exitValue = exitValue;
    }

    @Override
    public ParcelFileDescriptor getOutputStream() {
        return null;
    }

    @Override
    public ParcelFileDescriptor getInputStream() {
        return pfd;
    }

    @Override
    public ParcelFileDescriptor getErrorStream() {
        return null;
    }

    @Override
    public int waitFor() {
        return exitValue;
    }

    @Override
    public int exitValue() {
        return exitValue;
    }

    @Override
    public void destroy() {
        try {
            if (pfd != null) pfd.close();
        } catch (IOException e) {
            Log.w(TAG, "Failed to close ParcelFileDescriptor in destroy()", e);
        }
    }

    @Override
    public boolean alive() {
        return false;
    }

    @Override
    public boolean waitForTimeout(long timeout, String unitName) {
        return true;
    }
}
