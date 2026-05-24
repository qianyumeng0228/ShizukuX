package rikka.shizuku.server

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.hardware.HardwareBuffer
import android.os.Bundle
import android.os.IBinder
import android.os.ServiceManager
import android.util.Log
import af.shizuku.server.IAICorePlus

/**
 * Implementation of AICorePlus using Android's SurfaceControl and AI framework APIs.
 */
class AICorePlusImpl(
    private val clientManager: ShizukuClientManager,
    private val service: ShizukuService
) : IAICorePlus.Stub() {
    companion object {
        private const val TAG = "AICorePlusImpl"
    }

    private var automationBridge: af.shizuku.server.IAIAutomationBridge? = null

    fun setAutomationBridge(bridge: af.shizuku.server.IAIAutomationBridge?) {
        this.automationBridge = bridge
    }

    private fun checkExperimental(): Boolean {
        if (!service.isPlusFeatureEnabled("ai_core_experimental")) {
            Log.w(TAG, "Experimental AI Core features are disabled.")
            return false
        }
        return true
    }

    override fun getPixelColor(x: Int, y: Int): Int {
        if (!checkExperimental()) return Color.TRANSPARENT
        return try {
            val bridge = automationBridge
            if (bridge != null) {
                return bridge.getPixelColor(x, y)
            }
            getPixelColorViaSurfaceControl(x, y)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get pixel color", e)
            Color.TRANSPARENT
        }
    }

    private fun getPixelColorViaSurfaceControl(x: Int, y: Int): Int {
        return try {
            val displayToken = getDisplayToken() ?: return Color.TRANSPARENT
            val screenshotMethod = Class.forName("android.view.SurfaceControl")
                .getMethod("screenshot", IBinder::class.java, Rect::class.java, Int::class.java, Int::class.java)

            val crop = Rect(x, y, x + 1, y + 1)
            val bitmap = screenshotMethod.invoke(null, displayToken, crop, 1, 1) as? Bitmap
            val color = bitmap?.getPixel(0, 0) ?: Color.TRANSPARENT
            bitmap?.recycle()
            color
        } catch (e: Exception) {
            Color.TRANSPARENT
        }
    }

    override fun scheduleNPULoad(taskData: Bundle?): Bundle? {
        if (!service.isPlusFeatureEnabled("ai_core_master") || !service.isPlusFeatureEnabled("npu_acceleration")) return null
        if (taskData == null) return null
        
        setNpuPowerMode(2) // High Performance
        
        return try {
            val taskType = taskData.getString("task_type", "INFERENCE")
            Log.d(TAG, "Scheduling NPU task: type=$taskType")
            
            val response = Bundle()
            response.putBoolean("success", true)
            response
        } catch (e: Exception) {
            Log.e(TAG, "Failed to schedule NPU task", e)
            null
        }
    }

    private fun setNpuPowerMode(mode: Int) {
        try {
            android.provider.Settings.System.putInt(service.contentResolver, "processing_speed", mode)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set NPU power mode", e)
        }
    }

    override fun captureLayer(layerId: Int): Bitmap? {
        if (!checkExperimental()) return null
        return try {
            val bridge = automationBridge
            if (bridge != null) {
                return bridge.captureLayer(layerId)
            }
            captureLayerViaSurfaceControl(layerId)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to capture layer", e)
            null
        }
    }

    private fun captureLayerViaSurfaceControl(layerId: Int): Bitmap? {
        return try {
            val displayToken = getDisplayToken() ?: return null
            val screenshotMethod = Class.forName("android.view.SurfaceControl")
                .getMethod("screenshot", IBinder::class.java, Rect::class.java, Int::class.java, Int::class.java)
            screenshotMethod.invoke(null, displayToken, null, 0, 0) as? Bitmap
        } catch (e: Exception) {
            null
        }
    }

    override fun getSystemContext(): Bundle? {
        val bundle = Bundle()
        bundle.putString("ai_core_version", "2.0")
        bundle.putBoolean("npu_available", true)
        bundle.putString("android_version", android.os.Build.VERSION.RELEASE)
        bundle.putInt("sdk_int", android.os.Build.VERSION.SDK_INT)
        return bundle
    }

    override fun simulateTouch(x: Float, y: Float): Boolean {
        if (!checkExperimental()) return false
        return try {
            val bridge = automationBridge
            if (bridge != null) {
                return bridge.simulateTouch(x, y)
            }
            val process = Runtime.getRuntime().exec(arrayOf("input", "tap", x.toString(), y.toString()))
            process.waitFor() == 0
        } catch (e: Exception) {
            false
        }
    }

    override fun simulateSwipe(x1: Float, y1: Float, x2: Float, y2: Float, duration: Int): Boolean {
        if (!checkExperimental()) return false
        return try {
            val bridge = automationBridge
            if (bridge != null) {
                return bridge.simulateSwipe(x1, y1, x2, y2, duration)
            }
            val process = Runtime.getRuntime().exec(arrayOf("input", "swipe", x1.toString(), y1.toString(), x2.toString(), y2.toString(), duration.toString()))
            process.waitFor() == 0
        } catch (e: Exception) {
            false
        }
    }

    override fun simulateText(text: String?): Boolean {
        if (!checkExperimental() || text == null) return false
        return try {
            val bridge = automationBridge
            if (bridge != null) {
                return bridge.simulateText(text)
            }
            val process = Runtime.getRuntime().exec(arrayOf("input", "text", text))
            process.waitFor() == 0
        } catch (e: Exception) {
            false
        }
    }

    override fun getWindowHierarchy(): String? {
        if (!service.isPlusFeatureEnabled("ai_core_master") || !service.isPlusFeatureEnabled("native_window_crawler")) return ""
        return try {
            automationBridge?.windowHierarchy ?: ""
        } catch (e: Exception) {
            ""
        }
    }

    override fun getServerStats(): Bundle? {
        val bundle = Bundle()
        bundle.putInt("client_count", clientManager.clientCount)
        bundle.putLong("mem_total", Runtime.getRuntime().totalMemory())
        return bundle
    }

    private fun getDisplayToken(): IBinder? {
        return try {
            val displayTokenMethod = Class.forName("android.view.SurfaceControl")
                .getMethod("getInternalDisplayToken")
            displayTokenMethod.invoke(null) as? IBinder
        } catch (e: Exception) {
            null
        }
    }
}
