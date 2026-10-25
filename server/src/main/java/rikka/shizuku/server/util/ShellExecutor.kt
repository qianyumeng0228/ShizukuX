package rikka.shizuku.server.util

import android.util.Log
import java.io.InputStream
import java.util.concurrent.TimeUnit

object ShellExecutor {
    private const val TAG = "ShellExecutor"

    // Hard ceiling on any single shell call. Binder threads must not be parked
    // indefinitely by a wedged child (audit slice4 P1-2).
    private const val TIMEOUT_SECONDS = 45L

    /**
     * Start a daemon thread that drains [stream] by discarding everything read.
     * Stdout AND stderr are drained this way so a child that writes more than the
     * pipe buffer (~64 KB) can never block on write(2) and deadlock the caller
     * (audit slice4 P1-1). [out] optionally receives the bytes (used by exec()).
     */
    private fun startDrainThread(stream: InputStream, out: java.io.ByteArrayOutputStream? = null): Thread {
        val t = Thread {
            val buf = ByteArray(8192)
            try {
                while (true) {
                    val n = stream.read(buf)
                    if (n == -1) break
                    if (out != null) out.write(buf, 0, n)
                }
            } catch (_: Exception) {
            }
        }
        t.isDaemon = true
        t.start()
        return t
    }

    /** Wait for the process with a timeout; on timeout kill it forcibly and report failure. */
    private fun waitWithTimeout(proc: Process, args: Array<out String>, op: String): Boolean {
        if (proc.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) return true
        proc.destroyForcibly()
        Log.w(TAG, "$op timed out after ${TIMEOUT_SECONDS}s: ${args.joinToString(" ")}")
        return false
    }

    fun exec(vararg args: String): String = try {
        val proc = Runtime.getRuntime().exec(args)
        val stdout = java.io.ByteArrayOutputStream()
        val stdoutThread = startDrainThread(proc.inputStream, stdout)
        startDrainThread(proc.errorStream)
        try {
            if (!waitWithTimeout(proc, args, "exec")) {
                ""
            } else {
                stdoutThread.join(1000)
                stdout.toString().trim()
            }
        } finally {
            if (proc.isAlive) proc.destroy()
        }
    } catch (e: Exception) {
        Log.w(TAG, "exec failed: ${args.joinToString(" ")}", e); ""
    }

    fun execBool(vararg args: String): Boolean = try {
        val proc = Runtime.getRuntime().exec(args)
        startDrainThread(proc.inputStream)
        startDrainThread(proc.errorStream)
        try {
            if (!waitWithTimeout(proc, args, "execBool")) {
                false
            } else {
                proc.exitValue() == 0
            }
        } finally {
            if (proc.isAlive) proc.destroy()
        }
    } catch (e: Exception) {
        Log.w(TAG, "execBool failed: ${args.joinToString(" ")}", e); false
    }

    fun execCode(vararg args: String): Int = try {
        val proc = Runtime.getRuntime().exec(args)
        startDrainThread(proc.inputStream)
        startDrainThread(proc.errorStream)
        try {
            if (!waitWithTimeout(proc, args, "execCode")) {
                -1
            } else {
                proc.exitValue()
            }
        } finally {
            if (proc.isAlive) proc.destroy()
        }
    } catch (e: Exception) {
        Log.w(TAG, "execCode failed: ${args.joinToString(" ")}", e); -1
    }
}
