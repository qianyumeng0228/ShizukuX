package rikka.shizuku.server

import android.os.Bundle
import af.shizuku.server.IDisplayTunerExtra
import rikka.shizuku.server.util.ShellExecutor

class DisplayTunerExtraImpl : IDisplayTunerExtra.Stub() {

    override fun setDisplaySize(width: Int, height: Int): Boolean {
        if (width <= 0 || height <= 0) return resetDisplaySize()
        return ShellExecutor.execBool("wm", "size", "${width}x${height}")
    }

    override fun resetDisplaySize(): Boolean = ShellExecutor.execBool("wm", "size", "reset")

    override fun setDisplayDensity(dpi: Int): Boolean {
        if (dpi <= 0) return resetDisplayDensity()
        return ShellExecutor.execBool("wm", "density", dpi.toString())
    }

    override fun resetDisplayDensity(): Boolean = ShellExecutor.execBool("wm", "density", "reset")

    override fun getDisplaySize(): Bundle {
        val output = ShellExecutor.exec("wm", "size")
        val bundle = Bundle()
        var hasOverride = false
        for (line in output.lines()) {
            val lower = line.lowercase()
            val rawPair = line.substringAfterLast(":").trim()
            val parts = rawPair.split("x")
            if (parts.size != 2) continue
            val w = parts[0].trim().toIntOrNull() ?: continue
            val h = parts[1].trim().toIntOrNull() ?: continue
            when {
                lower.startsWith("physical") -> {
                    bundle.putInt("physical_width", w)
                    bundle.putInt("physical_height", h)
                    if (!bundle.containsKey("width")) {
                        bundle.putInt("width", w)
                        bundle.putInt("height", h)
                    }
                }
                lower.startsWith("override") -> {
                    bundle.putInt("width", w)
                    bundle.putInt("height", h)
                    hasOverride = true
                }
            }
        }
        bundle.putInt("has_override", if (hasOverride) 1 else 0)
        return bundle
    }

    override fun getDisplayDensity(): Int {
        val output = ShellExecutor.exec("wm", "density")
        var density = -1
        for (line in output.lines()) {
            val lower = line.lowercase()
            val value = line.substringAfterLast(":").trim().toIntOrNull() ?: continue
            if (lower.startsWith("physical") && density == -1) density = value
            if (lower.startsWith("override")) density = value
        }
        return density
    }

    override fun getPhysicalDensity(): Int {
        val output = ShellExecutor.exec("wm", "density")
        for (line in output.lines()) {
            if (line.lowercase().startsWith("physical")) {
                return line.substringAfterLast(":").trim().toIntOrNull() ?: -1
            }
        }
        return -1
    }
}
