package af.shizuku.manager.ktx

import io.sentry.Sentry
import timber.log.Timber

/**
 * 统一处理“必须捕获但不能静默”的场景。
 * 替换裸 catch {} —— 每个失败都有日志和 Sentry 上报。
 *
 * Usage:
 * ```
 * // before
 * try { ShizukuXAPI.ApkPatcher.restoreOriginal(pkg) } catch (_: Exception) {}
 *
 * // after
 * SafeExecutor.guardUnit("BackupRestore") { ShizukuXAPI.ApkPatcher.restoreOriginal(pkg) }
 * ```
 */
object SafeExecutor {

    /**
     * Runs [block], returning [fallback] on any throwable while logging to Timber
     * and reporting the failure to Sentry.
     */
    inline fun <T> guard(
        tag: String,
        fallback: T,
        crossinline block: () -> T
    ): T = try {
        block()
    } catch (e: Throwable) {
        Timber.tag(tag).w(e, "guarded operation failed: ${e.message}")
        Sentry.captureException(e)
        fallback
    }

    /**
     * Runs [block]; any throwable is logged to Timber and reported to Sentry.
     */
    inline fun guardUnit(tag: String, crossinline block: () -> Unit) {
        try {
            block()
        } catch (e: Throwable) {
            Timber.tag(tag).w(e, "guarded operation failed: ${e.message}")
            Sentry.captureException(e)
        }
    }
}
