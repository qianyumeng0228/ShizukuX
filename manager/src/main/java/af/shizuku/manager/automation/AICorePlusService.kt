package af.shizuku.manager.automation

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import timber.log.Timber

/**
 * AICore+ Automation Bridge
 * Provides privileged UI hierarchy dumping and physical input simulation for AI automation.
 */
class AICorePlusService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        Timber.d("AICorePlusService connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        // This service primarily acts as a bridge for explicit commands,
        // but can optionally listen for specific UI transitions if needed.
    }

    override fun onInterrupt() {
        Timber.d("AICorePlusService interrupted")
    }

    /**
     * Dumps the current view hierarchy starting from the active window root.
     * @return XML string representation of the hierarchy.
     */
    fun dumpHierarchy(): String {
        val rootNode = rootInActiveWindow ?: return "<error>Root node unavailable</error>"
        val sb = java.lang.StringBuilder()
        buildXml(rootNode, sb, 0)
        return sb.toString()
    }

    private fun buildXml(node: AccessibilityNodeInfo, sb: StringBuilder, depth: Int) {
        val indent = "  ".repeat(depth)
        sb.append(indent).append("<node")
        sb.append(" class=\"").append(node.className).append("\"")
        if (!node.text.isNullOrEmpty()) sb.append(" text=\"").append(node.text).append("\"")
        if (!node.contentDescription.isNullOrEmpty()) sb.append(" content-desc=\"").append(node.contentDescription).append("\"")
        if (!node.viewIdResourceName.isNullOrEmpty()) sb.append(" resource-id=\"").append(node.viewIdResourceName).append("\"")
        sb.append(" checkable=\"").append(node.isCheckable).append("\"")
        sb.append(" checked=\"").append(node.isChecked).append("\"")
        sb.append(" clickable=\"").append(node.isClickable).append("\"")
        sb.append(" enabled=\"").append(node.isEnabled).append("\"")
        sb.append(" focusable=\"").append(node.isFocusable).append("\"")
        sb.append(" focused=\"").append(node.isFocused).append("\"")
        sb.append(" scrollable=\"").append(node.isScrollable).append("\"")
        sb.append(" long-clickable=\"").append(node.isLongClickable).append("\"")
        sb.append(" password=\"").append(node.isPassword).append("\"")
        sb.append(" selected=\"").append(node.isSelected).append("\"")
        sb.append(">\n")

        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            if (child != null) {
                buildXml(child, sb, depth + 1)
                child.recycle()
            }
        }
        sb.append(indent).append("</node>\n")
    }

    /**
     * Simulates a physical tap at the given coordinates.
     */
    fun performTap(x: Float, y: Float): Boolean {
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0, 50)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return dispatchGesture(gesture, null, null)
    }

    /**
     * Simulates a swipe between two coordinates.
     */
    fun performSwipe(startX: Float, startY: Float, endX: Float, endY: Float, durationMs: Long = 300): Boolean {
        val path = Path().apply {
            moveTo(startX, startY)
            lineTo(endX, endY)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return dispatchGesture(gesture, null, null)
    }
}
