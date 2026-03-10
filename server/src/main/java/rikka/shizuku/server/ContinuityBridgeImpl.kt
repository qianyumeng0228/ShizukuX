package rikka.shizuku.server

import android.os.Bundle
import android.os.IBinder
import android.os.RemoteException
import moe.shizuku.server.IContinuityBridge

class ContinuityBridgeImpl : IContinuityBridge.Stub() {
    override fun syncData(targetDeviceId: String?, key: String?, data: Bundle?): Boolean {
        if (targetDeviceId == null || key == null || data == null) return false
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("log", "-t", "ContinuityBridge", "Syncing $key to $targetDeviceId"))
            process.waitFor() == 0
        } catch (e: Exception) {
            false
        }
    }

    override fun registerContinuityListener(listener: IBinder?) {
        try {
            listener?.linkToDeath({ /* cleanup */ }, 0)
        } catch (e: RemoteException) {
            e.printStackTrace()
        }
    }

    override fun listEligibleDevices(): List<String> {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("settings", "get", "global", "continuity_devices"))
            val reader = process.inputStream.bufferedReader()
            val output = reader.readLine()
            process.waitFor()
            if (output != null && output != "null" && output.isNotBlank()) {
                output.split(",")
            } else {
                emptyList()
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    override fun requestHandoff(targetDeviceId: String?, taskState: Bundle?): Boolean {
        if (targetDeviceId == null || taskState == null) return false
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("log", "-t", "ContinuityBridge", "Handoff to $targetDeviceId"))
            process.waitFor() == 0
        } catch (e: Exception) {
            false
        }
    }
}
