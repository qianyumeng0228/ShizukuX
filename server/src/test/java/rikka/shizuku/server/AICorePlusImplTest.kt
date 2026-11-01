package rikka.shizuku.server

import af.shizuku.server.IAIAutomationBridge
import android.util.Log
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class AICorePlusImplTest {

    private lateinit var aiCoreExtraImpl: AICorePlusImpl
    private lateinit var serviceMock: ShizukuService
    private lateinit var bridgeMock: IAIAutomationBridge

    @BeforeEach
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.e(any(), any(), any()) } returns 0
        every { Log.d(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0

        val clientManagerMock = mockk<ShizukuClientManager>(relaxed = true)
        serviceMock = mockk<ShizukuService>(relaxed = true)
        every { serviceMock.isExtraFeatureEnabled(any()) } returns true
        aiCoreExtraImpl = AICorePlusImpl(clientManagerMock, serviceMock)
        bridgeMock = mockk<IAIAutomationBridge>(relaxed = true)
    }

    @AfterEach
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `simulateTouch returns true when automation bridge succeeds`() {
        every { bridgeMock.simulateTouch(100.0f, 200.0f) } returns true
        aiCoreExtraImpl.setAutomationBridge(bridgeMock)

        val result = aiCoreExtraImpl.simulateTouch(100.0f, 200.0f)

        assertTrue(result, "simulateTouch should return true when the bridge reports success")
        verify { bridgeMock.simulateTouch(100.0f, 200.0f) }
    }

    @Test
    fun `simulateTouch returns false when automation bridge throws`() {
        every { bridgeMock.simulateTouch(100.0f, 200.0f) } throws IllegalStateException("bridge failure")
        aiCoreExtraImpl.setAutomationBridge(bridgeMock)

        val result = aiCoreExtraImpl.simulateTouch(100.0f, 200.0f)

        assertFalse(result, "simulateTouch should return false when the bridge throws")
    }

    @Test
    fun `simulateTouch returns false when experimental features are disabled`() {
        every { serviceMock.isExtraFeatureEnabled("ai_core_experimental") } returns false
        aiCoreExtraImpl.setAutomationBridge(bridgeMock)

        val result = aiCoreExtraImpl.simulateTouch(100.0f, 200.0f)

        assertFalse(result, "simulateTouch should return false when experimental features are disabled")
        verify(exactly = 0) { bridgeMock.simulateTouch(any(), any()) }
    }

    @Test
    fun `simulateSwipe returns true when automation bridge succeeds`() {
        every { bridgeMock.simulateSwipe(0.0f, 0.0f, 500.0f, 500.0f, 300) } returns true
        aiCoreExtraImpl.setAutomationBridge(bridgeMock)

        val result = aiCoreExtraImpl.simulateSwipe(0.0f, 0.0f, 500.0f, 500.0f, 300)

        assertTrue(result, "simulateSwipe should return true when the bridge reports success")
        verify { bridgeMock.simulateSwipe(0.0f, 0.0f, 500.0f, 500.0f, 300) }
    }
}
