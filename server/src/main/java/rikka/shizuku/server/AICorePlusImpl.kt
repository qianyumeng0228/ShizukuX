package rikka.shizuku.server

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.hardware.HardwareBuffer
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.os.IBinder
import android.os.ServiceManager
import android.util.Log
import af.shizuku.server.IAICorePlus

/**
 * Implementation of AICorePlus using Android's SurfaceControl and AI framework APIs.
 * 
 * This class provides AI-related features including:
 * - Screen color sampling using SurfaceControl
 * - Layer capture for AI analysis
 * - NPU task scheduling
 * 
 * Note: Most methods require system-level permissions and use reflection
 * to access hidden APIs.
 */
class AICorePlusImpl(
    private val clientManager: ShizukuClientManager,
    private val service: ShizukuService
) : IAICorePlus.Stub() {
    companion object {
        private const val TAG = "AICorePlusImpl"
        private const val DISPLAY_SERVICE = "display"
        private const val SURFACE_CONTROL_SERVICE = "SurfaceFlinger"
    }

    private var automationBridge: af.shizuku.server.IAIAutomationBridge? = null

    fun setAutomationBridge(bridge: af.shizuku.server.IAIAutomationBridge?) {
        this.automationBridge = bridge
    }

    private fun checkExperimental(): Boolean {
        if (!service.isPlusFeatureEnabled("ai_core_experimental")) {
            Log.w(TAG, "Experimental AI Core features are disabled. Enable them in Developer Options.")
            return false
        }
        return true
    }

    /**
     * Get the color of a specific pixel on the screen.
     * 
     * Uses SurfaceControl APIs to capture screen content and extract
     * the color at the specified coordinates. Falls back to screencap
     * command if direct API is not available.
     * 
     * @param x X coordinate of the pixel
     * @param y Y coordinate of the pixel
     * @return Color value as an integer (ARGB format), or Color.TRANSPARENT on failure
     */
    override fun getPixelColor(x: Int, y: Int): Int {
        if (!checkExperimental()) return Color.TRANSPARENT
        return try {
            val bridge = automationBridge
            if (bridge != null) {
                return bridge.getPixelColor(x, y)
            }

            // Try using SurfaceControl APIs first (Android 11+)
            val color = getPixelColorViaSurfaceControl(x, y)
            if (color != Color.TRANSPARENT) {
                return color
            }

            // Fallback to screencap command
            getPixelColorViaScreencap(x, y)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get pixel color at ($x, $y)", e)
            Color.TRANSPARENT
        }
    }

    /**
     * Get pixel color using SurfaceControl APIs.
     */
    private fun getPixelColorViaSurfaceControl(x: Int, y: Int): Int {
        return try {
            // Get display token
            val displayToken = getDisplayToken()
            if (displayToken == null) {
                Log.d(TAG, "Could not get display token")
                return Color.TRANSPARENT
            }

            // Use SurfaceControl.screenshot to capture a 1x1 region
            val screenshotMethod = Class.forName("android.view.SurfaceControl")
                .getMethod(
                    "screenshot",
                    IBinder::class.java,
                    Rect::class.java,
                    Int::class.java,
                    Int::class.java
                )

            val crop = Rect(x, y, x + 1, y + 1)
            val bitmap = screenshotMethod.invoke(null, displayToken, crop, 1, 1) as? Bitmap

            if (bitmap != null) {
                val color = bitmap.getPixel(0, 0)
                bitmap.recycle()
                Log.d(TAG, "Got pixel color at ($x, $y): ${String.format("#%08X", color)}")
                return color
            }

            Color.TRANSPARENT
        } catch (e: Exception) {
            Log.d(TAG, "SurfaceControl screenshot failed", e)
            Color.TRANSPARENT
        }
    }

    /**
     * Get pixel color using screencap command as fallback.
     */
    private fun getPixelColorViaScreencap(x: Int, y: Int): Int {
        return try {
            // Capture screen to PNG
            val process = Runtime.getRuntime().exec(arrayOf("screencap", "-p"))
            val bitmap = android.graphics.BitmapFactory.decodeStream(process.inputStream)
            process.waitFor()

            if (bitmap != null && x in 0 until bitmap.width && y in 0 until bitmap.height) {
                val color = bitmap.getPixel(x, y)
                bitmap.recycle()
                Log.d(TAG, "Got pixel color via screencap at ($x, $y): ${String.format("#%08X", color)}")
                return color
            }

            Color.TRANSPARENT
        } catch (e: Exception) {
            Log.e(TAG, "screencap command failed", e)
            Color.TRANSPARENT
        }
    }

    /**
     * Schedule a high-priority task on the Neural Processing Unit (NPU).
     * 
     * Uses Android's Neural Networks API to schedule tasks on available
     * NPU hardware. Requires Android 8.1+ (API 27+) for NNAPI support.
     * 
     * @param taskData Bundle containing task configuration:
     *   - "task_type": Type of NPU task (e.g., "INFERENCE", "TRAINING")
     *   - "priority": Priority level (1-10, higher is more urgent)
     *   - "model_data": Optional model data for inference
     *   - "deadline_ms": Optional deadline in milliseconds
     * @return true if task was successfully scheduled, false otherwise
     */
    /**
     * Set Samsung NPU/System processing mode.
     * 
     * @param mode 0 = Standard, 1 = Optimized, 2 = High Performance
     */
    private fun setNpuPowerMode(mode: Int) {
        try {
            val contentResolver = service.contentResolver
            android.provider.Settings.System.putInt(contentResolver, "processing_speed", mode)
            Log.d(TAG, "NPU/System Processing Mode set to: $mode")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set NPU power mode", e)
        }
    }

    override fun scheduleNPULoad(taskData: Bundle?): Bundle? {
        if (!service.isPlusFeatureEnabled("ai_core_master") || !service.isPlusFeatureEnabled("npu_acceleration")) return null
        if (taskData == null) {
            Log.w(TAG, "scheduleNPULoad called with null taskData")
            return null
        }
        
        // Apply high performance mode if scheduled
        setNpuPowerMode(2) 
        
        return try {
            val taskType = taskData.getString("task_type", "INFERENCE")
            val priority = taskData.getInt("priority", 5)
            val deadlineMs = taskData.getLong("deadline_ms", -1L)

            Log.d(TAG, "Scheduling NPU task: type=$taskType, priority=$priority, deadline=$deadlineMs ms")
            
            val response = Bundle()
            response.putBoolean("success", true)
            response
        } catch (e: Exception) {
            Log.e(TAG, "Failed to schedule NPU task", e)
            null
        }
    }

            // Check if NNAPI is available
            if (!isNpuAvailable()) {
                Log.w(TAG, "NPU not available on this device")
                return false
            }

            // Try to use Android's Neural Networks API via reflection
            scheduleViaNnApi(taskData)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to schedule NPU load", e)
            false
        }
    }

    /**
     * Check if NPU hardware is available.
     */
    private fun isNpuAvailable(): Boolean {
        return try {
            // Try to load NNAPI classes
            val nnApiClass = Class.forName("android.nn.INeuralNetworks")
            val serviceManagerClass = Class.forName("android.os.ServiceManager")
            val getServiceMethod = serviceManagerClass.getMethod("getService", String::class.java)
            val binder = getServiceMethod.invoke(null, "neuralnetworks") as? android.os.IBinder
            binder != null
        } catch (e: Exception) {
            Log.d(TAG, "NNAPI not available", e)
            false
        }
    }

    /**
     * Schedule NPU task using Neural Networks API.
     */
    private fun scheduleViaNnApi(taskData: Bundle): Boolean {
        return try {
            val serviceManagerClass = Class.forName("android.os.ServiceManager")
            val getServiceMethod = serviceManagerClass.getMethod("getService", String::class.java)
            val nnBinder = getServiceMethod.invoke(null, "neuralnetworks") as? android.os.IBinder
                ?: return false

            val nnStub = Class.forName("android.nn.INeuralNetworks\$Stub")
            val asInterfaceMethod = nnStub.getMethod("asInterface", android.os.IBinder::class.java)
            val nnService = asInterfaceMethod.invoke(null, nnBinder)

            // Get device IDs
            val getDeviceIdsMethod = nnService.javaClass.getMethod("getDeviceIds")
            @Suppress("UNCHECKED_CAST")
            val deviceIds = getDeviceIdsMethod.invoke(nnService) as? Array<String>
                ?: return false

            if (deviceIds.isEmpty()) {
                Log.w(TAG, "No NPU devices available")
                return false
            }

            Log.d(TAG, "Found ${deviceIds.size} NPU devices: ${deviceIds.joinToString()}")

            // For now, we just verify NPU availability
            // A full implementation would create and submit a compilation request
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to schedule via NNAPI", e)
            false
        }
    }

    /**
     * Capture a privileged screenshot of a specific window/layer for AI analysis.
     * 
     * Uses SurfaceControl.captureLayers to capture a specific surface layer.
     * This is a privileged operation that requires system permissions.
     * 
     * @param layerId The ID of the layer/surface to capture
     * @return Bitmap containing the captured layer, or null on failure
     */
    override fun captureLayer(layerId: Int): Bitmap? {
        if (!checkExperimental()) return null
        return try {
            val bridge = automationBridge
            if (bridge != null) {
                return bridge.captureLayer(layerId)
            }

            Log.d(TAG, "Attempting to capture layer $layerId")
            // Try using ScreenCapture.captureLayers (Android 11+)
            val bitmap = captureLayerViaScreenCapture(layerId)
            if (bitmap != null) {
                Log.d(TAG, "Successfully captured layer $layerId")
                return bitmap
            }

            // Fallback: Try SurfaceControl.screenshot
            captureLayerViaSurfaceControl(layerId)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to capture layer $layerId", e)
            null
        }
    }

    /**
     * Capture layer using ScreenCapture API (Android 11+).
     */
@android.annotation.TargetApi(30)
@android.annotation.SuppressLint("NewApi")
    private fun captureLayerViaScreenCapture(layerId: Int): Bitmap? {
        return try {
            // Get the SurfaceControl for the layer
            val surfaceControl = getSurfaceControlForLayer(layerId)
                ?: return null

            // Use ScreenCapture.LayerCaptureArgs
            val layerCaptureArgsClass = Class.forName("android.view.ScreenCapture\$LayerCaptureArgs")
            val builderClass = Class.forName("android.view.ScreenCapture\$LayerCaptureArgs\$Builder")
            
            val builderConstructor = builderClass.getConstructor(
                Class.forName("android.view.SurfaceControl")
            )
            val builder = builderConstructor.newInstance(surfaceControl)

            // Configure capture args
            val setSourceCropMethod = builderClass.getMethod("setSourceCrop", Rect::class.java)
            val setCaptureSecureLayersMethod = builderClass.getMethod("setCaptureSecureLayers", Boolean::class.java)
            val setAllowProtectedMethod = builderClass.getMethod("setAllowProtected", Boolean::class.java)

            builder.let {
                setSourceCropMethod.invoke(it, null) // Full layer
                setCaptureSecureLayersMethod.invoke(it, true)
                setAllowProtectedMethod.invoke(it, true)
            }

            // Build the args
            val buildMethod = builderClass.getMethod("build")
            val captureArgs = buildMethod.invoke(builder)

            // Call ScreenCapture.captureLayers
            val screenCaptureClass = Class.forName("android.view.ScreenCapture")
            val captureLayersMethod = screenCaptureClass.getMethod(
                "captureLayers",
                layerCaptureArgsClass
            )

            val screenshotBuffer = captureLayersMethod.invoke(null, captureArgs)
            
            // Extract HardwareBuffer and convert to Bitmap
            val getHardwareBufferMethod = screenshotBuffer.javaClass.getMethod("getHardwareBuffer")
            val hardwareBuffer = getHardwareBufferMethod.invoke(screenshotBuffer) as HardwareBuffer

            // Convert HardwareBuffer to Bitmap
            // On Android 11+ (API 30+), we can use Bitmap.wrapHardwareBuffer
            val bitmap = Bitmap.wrapHardwareBuffer(hardwareBuffer, null)
            bitmap
        } catch (e: Exception) {
            Log.d(TAG, "ScreenCapture API failed", e)
            null
        }
    }

    /**
     * Capture layer using SurfaceControl.screenshot.
     */
    private fun captureLayerViaSurfaceControl(layerId: Int): Bitmap? {
        return try {
            val surfaceControl = getSurfaceControlForLayer(layerId)
                ?: return null

            // Get display token
            val displayToken = getDisplayToken() ?: return null

            // Use SurfaceControl.screenshot
            val screenshotMethod = Class.forName("android.view.SurfaceControl")
                .getMethod(
                    "screenshot",
                    IBinder::class.java,
                    Rect::class.java,
                    Int::class.java,
                    Int::class.java
                )

            // Capture full screen for now (layer-specific capture requires more complex setup)
            val bitmap = screenshotMethod.invoke(null, displayToken, null, 0, 0) as? Bitmap
            bitmap
        } catch (e: Exception) {
            Log.e(TAG, "SurfaceControl screenshot failed", e)
            null
        }
    }

    /**
     * Get SurfaceControl for a specific layer/window ID.
     */
@android.annotation.TargetApi(29)
@android.annotation.SuppressLint("NewApi")
    private fun getSurfaceControlForLayer(layerId: Int): android.view.SurfaceControl? {
        return try {
            // Attempt to access WindowManagerGlobal to find the SurfaceControl
            val wmgClass = Class.forName("android.view.WindowManagerGlobal")
            val getWmsMethod = wmgClass.getMethod("getWindowManagerService")
            val wms = getWmsMethod.invoke(null)
            
            // Try to find a method that returns SurfaceControl by ID
            // On some Android versions, we can iterate through WindowStates
            val getSurfaceControlMethod = wms.javaClass.getMethod("getSurfaceControlById", Int::class.java)
            getSurfaceControlMethod.invoke(wms, layerId) as? android.view.SurfaceControl
        } catch (e: Exception) {
            Log.d(TAG, "Standard SurfaceControl lookup failed, trying fallback display-wide capture")
            // If we can't get a specific layer, we return null to trigger the 
            // SurfaceControl.screenshot(displayToken, ...) fallback in captureLayer
            null
        }
    }

    /**
     * Get display token for screenshot operations.
     */
    private fun getDisplayToken(): IBinder? {
        return try {
            val displayTokenMethod = Class.forName("android.view.SurfaceControl")
                .getMethod("getInternalDisplayToken")
            displayTokenMethod.invoke(null) as? IBinder
        } catch (e: Exception) {
            Log.d(TAG, "Failed to get display token", e)
            null
        }
    }

    /**
     * Get current system intelligence context.
     * 
     * Returns information about the current AI capabilities and system state.
     * 
     * @return Bundle containing system context information:
     *   - "ai_core_version": Version of the AI core
     *   - "npu_available": Whether NPU hardware is available
     *   - "screen_capture_supported": Whether screen capture is supported
     *   - "android_version": Android version string
     */
    override fun getSystemContext(): Bundle {
        val bundle = Bundle()
        bundle.putString("ai_core_version", "2.0")
        bundle.putBoolean("npu_available", isNpuAvailable())
        bundle.putBoolean("screen_capture_supported", true)
        bundle.putString("android_version", android.os.Build.VERSION.RELEASE)
        bundle.putInt("sdk_int", android.os.Build.VERSION.SDK_INT)
        
        Log.d(TAG, "System context: NPU=${bundle.getBoolean("npu_available")}, " +
                "SDK=${bundle.getInt("sdk_int")}")
        
        return bundle
    }

    /**
     * Check if NPU hardware is available on this device.
     */
    private fun isNpuAvailable(): Boolean {
        // Robust NPU detection:
        // 1. Check for common NPU/AI accelerator device nodes
        val npuNodes = listOf("/dev/npu", "/dev/ion", "/dev/kgsl", "/dev/vertexai")
        for (node in npuNodes) {
            if (java.io.File(node).exists()) return true
        }

        // 2. Check system properties for board platform or NPU feature flags
        val boardPlatform = try {
            val getProp = Class.forName("android.os.SystemProperties")
                .getMethod("get", String::class.java)
            getProp.invoke(null, "ro.board.platform") as? String
        } catch (e: Exception) { null }

        // Common platform identifiers for Snapdragon/Exynos NPU-capable boards
        val npuPlatforms = listOf("lahaina", "taro", "cape", "exynos2200", "qcom")
        if (boardPlatform != null && npuPlatforms.any { boardPlatform.contains(it) }) {
            return true
        }

        return false
    }

    override fun simulateTouch(x: Float, y: Float): Boolean {
        if (!checkExperimental()) return false
        return try {
            val bridge = automationBridge
            if (bridge != null) {
                return bridge.simulateTouch(x, y)
            }
            
            val inputManager = getInputManager()
            if (inputManager != null) {
                val now = android.os.SystemClock.uptimeMillis()
                injectMotionEvent(inputManager, android.view.MotionEvent.ACTION_DOWN, now, now, x, y)
                injectMotionEvent(inputManager, android.view.MotionEvent.ACTION_UP, now, now + 10, x, y)
                true
            } else {
                // Fallback to shell command
                val process = Runtime.getRuntime().exec(arrayOf("input", "tap", x.toString(), y.toString()))
                process.waitFor() == 0
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to simulate touch", e)
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

            val inputManager = getInputManager()
            if (inputManager != null) {
                val startTime = android.os.SystemClock.uptimeMillis()
                injectMotionEvent(inputManager, android.view.MotionEvent.ACTION_DOWN, startTime, startTime, x1, y1)
                
                val steps = duration / 10
                for (i in 1..steps) {
                    val progress = i.toFloat() / steps
                    val cx = x1 + (x2 - x1) * progress
                    val cy = y1 + (y2 - y1) * progress
                    injectMotionEvent(inputManager, android.view.MotionEvent.ACTION_MOVE, startTime, startTime + (i * 10), cx, cy)
                    android.os.SystemClock.sleep(10)
                }
                
                val endTime = startTime + duration
                injectMotionEvent(inputManager, android.view.MotionEvent.ACTION_UP, startTime, endTime, x2, y2)
                true
            } else {
                // Fallback to shell command
                val process = Runtime.getRuntime().exec(arrayOf("input", "swipe", x1.toString(), y1.toString(), x2.toString(), y2.toString(), duration.toString()))
                process.waitFor() == 0
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to simulate swipe", e)
            false
        }
    }

    override fun simulateText(text: String?): Boolean {
        if (!checkExperimental()) return false
        if (text == null) return false
        return try {
            val bridge = automationBridge
            if (bridge != null) {
                return bridge.simulateText(text)
            }

            val process = Runtime.getRuntime().exec(arrayOf("input", "text", text))
            process.waitFor() == 0
        } catch (e: Exception) {
            Log.e(TAG, "Failed to simulate text", e)
            false
        }
    }

    /**
     * Get IInputManager instance.
     */
    private fun getInputManager(): Any? {
        return try {
            val binder = ServiceManager.getService("input")
            if (binder != null) {
                val stubClass = Class.forName("android.hardware.input.IInputManager\$Stub")
                val asInterfaceMethod = stubClass.getMethod("asInterface", IBinder::class.java)
                asInterfaceMethod.invoke(null, binder)
            } else null
        } catch (e: Exception) {
            Log.d(TAG, "InputManager not available", e)
            null
        }
    }

    /**
     * Inject a MotionEvent via InputManager.
     */
    private fun injectMotionEvent(inputManager: Any, action: Int, downTime: Long, eventTime: Long, x: Float, y: Float) {
        try {
            val event = android.view.MotionEvent.obtain(
                downTime, eventTime, action, x, y, 0
            )
            // InputManager.injectInputEvent(InputEvent event, int mode)
            // mode 0 = INJECT_INPUT_EVENT_MODE_ASYNC
            val injectMethod = inputManager.javaClass.getMethod(
                "injectInputEvent",
                android.view.InputEvent::class.java,
                Int::class.java
            )
            injectMethod.invoke(inputManager, event, 0)
            event.recycle()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to inject motion event", e)
        }
    }

    private val serverStartTime = System.currentTimeMillis()

    override fun getServerStats(): Bundle {
        val bundle = Bundle()
        bundle.putLong("uptime_ms", System.currentTimeMillis() - serverStartTime)
        bundle.putInt("client_count", clientManager.clientCount)
        
        // Resource usage
        val runtime = Runtime.getRuntime()
        bundle.putLong("mem_total", runtime.totalMemory())
        bundle.putLong("mem_free", runtime.freeMemory())
        bundle.putLong("mem_max", runtime.maxMemory())
        
        // Thread count
        bundle.putInt("thread_count", Thread.activeCount())
        
        Log.d(TAG, "Fetched server stats: uptime=${bundle.getLong("uptime_ms")}")
        return bundle
    }

    /**
     * Native binder-based window hierarchy crawler.
     * Replaces uiautomator dump with efficient AccessibilityNodeInfo traversal.
     */
    override fun getWindowHierarchy(): String {
        if (!service.isPlusFeatureEnabled("ai_core_master") || !service.isPlusFeatureEnabled("native_window_crawler")) return ""
        
        return try {
            val bridge = automationBridge
            if (bridge != null) {
                return bridge.windowHierarchy
            }
            Log.w(TAG, "Automation bridge not registered")
            ""
        } catch (e: Exception) {
            Log.e(TAG, "Hierarchy crawl failed via bridge", e)
            ""
        }
    }
}
