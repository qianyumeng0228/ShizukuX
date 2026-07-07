package af.shizuku.manager.utils

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Handler
import android.os.Looper
import io.sentry.Attachment
import io.sentry.EventProcessor
import io.sentry.Hint
import io.sentry.SentryEvent
import timber.log.Timber
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Attaches a screenshot only for crashes that look UI-rendering/theme related, instead of
 * globally via SentryOptions.isAttachScreenshot (which was turned off entirely - see
 * ShizukuApplication's initializeSentryEarly - after it turned out to be one of the largest
 * drivers of the Sentry attachment quota being exhausted: ~89% of all attachment traffic over
 * the last 90 days was rejected as over-quota). This targets the same class of bug that
 * prompted the ask (e.g. the black-screen recreate() transition bug fixed earlier) without
 * paying the attachment cost on every single event.
 *
 * Mirrors Sentry's own internal ScreenshotEventProcessor pattern (Hint.setScreenshot), just
 * gated by an allowlist check first. Every step is defensive - if anything here fails or is
 * slow, the crash event still goes out unmodified rather than being blocked or lost.
 */
class SelectiveScreenshotEventProcessor : EventProcessor {

    companion object {
        private const val MAIN_THREAD_CAPTURE_TIMEOUT_MS = 200L
        private const val MAX_SCREENSHOT_WIDTH_PX = 480
        private const val JPEG_QUALITY = 60

        // Stack frames touching any of these packages/classes point at UI-rendering, theming,
        // or Home-card rendering code - the areas that produced the black-screen class of bug.
        private val UI_RELATED_PACKAGE_PREFIXES = listOf(
            "af.shizuku.core.ui.",
            "af.shizuku.manager.home.",
            "af.shizuku.manager.utils.ThemeDelegateImpl",
            "af.shizuku.manager.utils.IconStyleHelper",
            "af.shizuku.manager.settings.",
        )

        private val UI_EXCEPTION_TYPES = listOf(
            "android.view.InflateException",
            "android.view.WindowManager\$BadTokenException",
            "android.content.res.Resources\$NotFoundException",
            "android.util.AndroidRuntimeException",
        )

        private fun isUiRenderingRelated(throwable: Throwable?): Boolean {
            var current = throwable
            var depth = 0
            while (current != null && depth < 8) {
                val className = current.javaClass.name
                if (UI_EXCEPTION_TYPES.any { className == it }) return true
                if (current.stackTrace.any { frame ->
                        UI_RELATED_PACKAGE_PREFIXES.any { prefix -> frame.className.startsWith(prefix) }
                    }) {
                    return true
                }
                current = current.cause
                depth++
            }
            return false
        }
    }

    override fun process(event: SentryEvent, hint: Hint): SentryEvent {
        try {
            if (!isUiRenderingRelated(event.throwable)) return event
            val screenshot = captureForegroundActivityScreenshot() ?: return event
            hint.setScreenshot(Attachment(screenshot, "screenshot.jpg", "image/jpeg", false))
        } catch (e: Throwable) {
            Timber.w(e, "SelectiveScreenshotEventProcessor: failed to attach screenshot")
        }
        return event
    }

    private fun captureForegroundActivityScreenshot(): ByteArray? {
        val activity = ForegroundActivityTracker.currentActivity ?: return null
        val latch = CountDownLatch(1)
        var bytes: ByteArray? = null

        Handler(Looper.getMainLooper()).post {
            try {
                val view = activity.window?.decorView
                if (view != null && view.width > 0 && view.height > 0) {
                    val scale = (MAX_SCREENSHOT_WIDTH_PX.toFloat() / view.width).coerceAtMost(1f)
                    val width = (view.width * scale).toInt().coerceAtLeast(1)
                    val height = (view.height * scale).toInt().coerceAtLeast(1)
                    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                    val canvas = Canvas(bitmap)
                    canvas.scale(scale, scale)
                    view.draw(canvas)
                    ByteArrayOutputStream().use { stream ->
                        bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, stream)
                        bytes = stream.toByteArray()
                    }
                    bitmap.recycle()
                }
            } catch (e: Throwable) {
                Timber.w(e, "SelectiveScreenshotEventProcessor: capture failed")
            } finally {
                latch.countDown()
            }
        }

        // Uncaught-exception capture already happens off the main thread, so this can safely
        // block briefly; if the main thread doesn't get to it in time (e.g. it's the thread
        // that crashed), give up rather than delay/lose the crash report itself.
        if (!latch.await(MAIN_THREAD_CAPTURE_TIMEOUT_MS, TimeUnit.MILLISECONDS)) return null
        return bytes
    }
}
