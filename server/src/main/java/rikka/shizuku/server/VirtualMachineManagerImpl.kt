package rikka.shizuku.server

import android.os.Bundle
import android.os.RemoteException
import moe.shizuku.server.IVirtualMachineManager

import java.io.BufferedReader
import java.io.InputStreamReader

class VirtualMachineManagerImpl : IVirtualMachineManager.Stub() {
    
    private fun exec(vararg cmd: String): Boolean {
        return try {
            val process = Runtime.getRuntime().exec(cmd)
            process.waitFor() == 0
        } catch (e: Exception) {
            false
        }
    }

    override fun create(name: String?, config: Bundle?): Boolean {
        if (name == null) return false
        return exec("vm", "create", name)
    }

    override fun start(name: String?): Boolean {
        if (name == null) return false
        return exec("vm", "run", name)
    }

    override fun stop(name: String?): Boolean {
        if (name == null) return false
        return exec("vm", "stop", name)
    }

    override fun delete(name: String?): Boolean {
        if (name == null) return false
        return exec("vm", "delete", name)
    }

    override fun getStatus(name: String?): String {
        if (name == null) return "Unknown"
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("vm", "info", name))
            val output = process.inputStream.bufferedReader().readText()
            process.waitFor()
            if (output.contains("running", ignoreCase = true)) "Running" else "Stopped"
        } catch (e: Exception) {
            "Unknown"
        }
    }

    override fun list(): List<String> {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("vm", "list"))
            val lines = process.inputStream.bufferedReader().readLines()
            process.waitFor()
            lines.filter { it.isNotBlank() }
        } catch (e: Exception) {
            emptyList()
        }
    }
}
