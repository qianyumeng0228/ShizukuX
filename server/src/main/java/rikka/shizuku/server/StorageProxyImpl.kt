package rikka.shizuku.server

import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.RemoteException
import af.shizuku.server.IStorageProxy
import rikka.shizuku.server.util.InputValidationUtils
import java.io.File

class StorageProxyImpl : IStorageProxy.Stub() {
    override fun openFile(path: String?, mode: Int): ParcelFileDescriptor? {
        if (!InputValidationUtils.isSafePath(path)) return null
        return try {
            val file = File(path!!)
            
            // Standard open
            try {
                return ParcelFileDescriptor.open(file, mode)
            } catch (e: Exception) {
                // Android 16+ / OneUI 8+ may require manual descriptor passing for /Android/data
                if (android.os.Build.VERSION.SDK_INT >= 36 && path.contains("/Android/data")) {
                    return openViaShellFallback(path, mode)
                }
                throw e
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun openViaShellFallback(path: String, mode: Int): ParcelFileDescriptor? {
        // Implementation for OneUI 8+ fallback using raw shell redirections 
        // to handle stricter storage access protection.
        // We create a pipe and 'cat' the file into it from a shell process 
        // that might have better namespace access on some restricted Samsung builds.
        return try {
            val pipe = ParcelFileDescriptor.createPipe()
            val readSide = pipe[0]
            val writeSide = pipe[1]
            
            // Pass path as a positional arg ($1), not interpolated into the script text,
            // so shell metacharacters in path can't break out of the quoted argument.
            val cmd = arrayOf("sh", "-c", "cat \"\$1\" > /proc/self/fd/${writeSide.fd}", "sh", path)
            Runtime.getRuntime().exec(cmd)
            
            // The shell process will exit once cat is done. 
            // We return the read side of the pipe.
            readSide
        } catch (e: Exception) {
            null
        }
    }

    override fun exists(path: String?): Boolean {
        if (!InputValidationUtils.isSafePath(path)) return false
        return File(path!!).exists()
    }

    override fun delete(path: String?): Boolean {
        if (!InputValidationUtils.isSafePath(path)) return false
        return try {
            File(path!!).delete()
        } catch (e: Exception) {
            false
        }
    }

    override fun listFiles(path: String?): List<String> {
        if (!InputValidationUtils.isSafePath(path)) return emptyList()
        return File(path!!).list()?.toList() ?: emptyList()
    }

    override fun getFileInfo(path: String?): Bundle {
        val bundle = Bundle()
        if (InputValidationUtils.isSafePath(path)) {
            val file = File(path!!)
            if (file.exists()) {
                bundle.putBoolean("exists", true)
                bundle.putLong("size", file.length())
                bundle.putLong("lastModified", file.lastModified())
                bundle.putBoolean("isDirectory", file.isDirectory)
            } else {
                bundle.putBoolean("exists", false)
            }
        } else {
            bundle.putBoolean("exists", false)
        }
        return bundle
    }

    override fun mkdir(path: String?): Boolean {
        if (!InputValidationUtils.isSafePath(path)) return false
        return try {
            File(path!!).mkdirs()
        } catch (e: Exception) {
            false
        }
    }

    private val serverUid: Int = android.os.Process.myUid()

    private fun extractPackageName(path: String): String? {
        val parts = path.split("/")
        // /data/data/<pkg>/... or /data/user/0/<pkg>/...
        val idx = parts.indexOfFirst { it == "data" && parts.getOrNull(parts.indexOf(it) + 1) in listOf("data", "user") }
        if (idx >= 0) {
            if (parts.getOrNull(idx + 1) == "data") return parts.getOrNull(idx + 2)
            if (parts.getOrNull(idx + 1) == "user") return parts.getOrNull(idx + 3)
        }
        return null
    }

    private fun openViaShellPipe(cmd: Array<String>): ParcelFileDescriptor? {
        return try {
            val pipe = ParcelFileDescriptor.createPipe()
            val readSide = pipe[0]
            val writeSide = pipe[1]
            val process = Runtime.getRuntime().exec(cmd)
            // Redirect process stdout to write side of pipe
            val out = process.inputStream
            Thread {
                try {
                    val buf = ByteArray(8192)
                    var read: Int
                    val fos = ParcelFileDescriptor.AutoCloseOutputStream(writeSide)
                    while (out.read(buf).also { read = it } != -1) {
                        fos.write(buf, 0, read)
                    }
                    fos.close()
                } catch (_: Exception) {}
            }.start()
            readSide
        } catch (e: Exception) {
            null
        }
    }

    override fun copyFile(srcPath: String?, destPath: String?): Boolean {
        if (!InputValidationUtils.isSafePath(srcPath) || !InputValidationUtils.isSafePath(destPath)) return false
        return try {
            File(srcPath!!).inputStream().use { src ->
                File(destPath!!).outputStream().use { dst -> src.copyTo(dst) }
            }
            true
        } catch (_: Exception) {
            if (serverUid == 2000 &&
                (srcPath!!.startsWith("/data/data/") || srcPath.startsWith("/data/user/"))) {
                val pkg = extractPackageName(srcPath) ?: return false
                return try {
                    Runtime.getRuntime().exec(arrayOf("run-as", pkg, "cp", srcPath, destPath!!))
                        .waitFor() == 0
                } catch (_: Exception) { false }
            }
            false
        }
    }

    override fun openContentUri(contentUri: String?): ParcelFileDescriptor? {
        if (contentUri.isNullOrBlank()) return null
        if (!contentUri.startsWith("content://")) return null
        return openViaShellPipe(arrayOf("content", "read", "--uri", contentUri))
    }

    override fun tarDirectory(dirPath: String?, packageContext: String?): ParcelFileDescriptor? {
        if (!InputValidationUtils.isSafePath(dirPath)) return null
        val dir = dirPath!!
        return if (!packageContext.isNullOrBlank() && serverUid == 2000 &&
            (dir.startsWith("/data/data/") || dir.startsWith("/data/user/"))) {
            openViaShellPipe(arrayOf("run-as", packageContext, "tar", "-czf", "-", "-C", dir, "."))
        } else {
            openViaShellPipe(arrayOf("tar", "-czf", "-", "-C", dir, "."))
        }
    }
}