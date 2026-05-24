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
            
            val cmd = arrayOf("sh", "-c", "cat \"$path\" > /proc/self/fd/${writeSide.fd}")
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
}