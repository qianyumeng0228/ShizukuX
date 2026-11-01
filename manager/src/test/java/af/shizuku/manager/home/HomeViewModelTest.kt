package af.shizuku.manager.home

import af.shizuku.manager.ShizukuApplication
import af.shizuku.manager.utils.StockShizukuCompat
import com.airbnb.mvrx.Loading
import com.airbnb.mvrx.test.MavericksTestRule
import com.airbnb.mvrx.withState
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * P0-遗留基建修复：此前构造 HomeViewModel 时 ShizukuApplication.appContext
 * 未初始化（lateinit）导致 UninitializedPropertyAccessException。
 * 现 mockk 掉 appContext 与 StockShizukuCompat 检测链（依赖均已在 testImplementation）。
 */
class HomeViewModelTest {

    @get:Rule
    val mavericksTestRule = MavericksTestRule()

    @Before
    fun setUp() {
        // appContext lives on ShizukuApplication's companion (no @JvmStatic), so the
        // object itself must be mocked, not the class.
        mockkObject(ShizukuApplication.Companion)
        every { ShizukuApplication.appContext } returns mockk(relaxed = true)
        // StockShizukuCompat is a Kotlin object (not a class) — mockkObject, not mockkStatic.
        mockkObject(StockShizukuCompat)
        every { StockShizukuCompat.isStockShizukuInstalled(any()) } returns false
        every { StockShizukuCompat.isCompatAppInstalled(any()) } returns false
        every { StockShizukuCompat.isOriginalRunning() } returns false
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `initial state is Loading before the async reload settles`() {
        val viewModel = HomeViewModel(HomeState())

        // reload() sets Loading synchronously, then hops to Dispatchers.IO —
        // the Loading state is what a freshly constructed VM exposes.
        withState(viewModel) { state ->
            assertTrue(state.serviceStatus is Loading<*>)
        }
    }

    @Test
    fun `setEditMode updates state`() {
        val viewModel = HomeViewModel(HomeState())

        viewModel.setEditMode(true)

        withState(viewModel) { state ->
            assertEquals(true, state.isEditMode)
        }
    }
}
