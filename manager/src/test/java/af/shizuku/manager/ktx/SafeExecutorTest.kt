package af.shizuku.manager.ktx

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * Pure-logic tests for SafeExecutor.
 *
 * Sentry and Timber are not initialised in the unit-test JVM, so their calls
 * inside the guards are no-ops — no mocking is required to exercise the
 * fallback/swallow semantics of the guards.
 */
class SafeExecutorTest {

    @Test
    fun `guard returns fallback when block throws`() {
        val result = SafeExecutor.guard("SafeExecutorTest", 42) {
            throw IllegalStateException("boom")
        }
        assertEquals(42, result)
    }

    @Test
    fun `guard returns block result on success`() {
        val result = SafeExecutor.guard("SafeExecutorTest", -1) { 7 }
        assertEquals(7, result)
    }

    @Test
    fun `guardUnit swallows the throwable and completes`() {
        var completed = false
        SafeExecutor.guardUnit("SafeExecutorTest") {
            throw IllegalStateException("boom")
        }
        completed = true
        assertEquals(true, completed)
    }

    @Test
    fun `guardUnit runs the block when it succeeds`() {
        var ran = false
        SafeExecutor.guardUnit("SafeExecutorTest") { ran = true }
        assertEquals(true, ran)
        assertFalse("block must not have been skipped", !ran)
    }
}
