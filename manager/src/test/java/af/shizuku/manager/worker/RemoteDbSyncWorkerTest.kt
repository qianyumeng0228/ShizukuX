package af.shizuku.manager.worker

import af.shizuku.manager.ShizukuSettings
import af.shizuku.manager.database.AppContextManager
import android.content.Context
import androidx.work.ListenableWorker.Result
import androidx.work.WorkerParameters
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * 遗留基建修复：原实现为 Kotest FunSpec（io.kotest），在
 * JUnit5-platform + vintage 混合环境下 runner 挂起导致全量测试卡死。
 * 重写为 JUnit4（与 SafeExecutorTest 同风格），语义不变：
 * 缓存新鲜 → success；网络异常 → retry。
 */
class RemoteDbSyncWorkerTest {

    private val context: Context = mockk(relaxed = true)
    private val workerParams: WorkerParameters = mockk(relaxed = true)

    @Before
    fun setUp() {
        mockkStatic(ShizukuSettings::class)
        mockkObject(AppContextManager)
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `doWork returns success when cache is fresh`() {
        // Recent timestamp → skip fetch
        every { ShizukuSettings.getLastDbUpdate() } returns System.currentTimeMillis() - 1000L

        val worker = RemoteDbSyncWorker(context, workerParams)
        val result = runBlocking { worker.doWork() }

        assertEquals(Result.success(), result)
    }

    @Test
    fun `doWork returns retry when fetch throws exception`() {
        every { ShizukuSettings.getLastDbUpdate() } returns 0L

        // Network failure injected via the doFetch seam (mockkConstructor hangs JDK 21).
        val worker = object : RemoteDbSyncWorker(context, workerParams) {
            override fun doFetch(urlString: String): String? =
                throw RuntimeException("Simulated network error")
        }
        val result = runBlocking { worker.doWork() }

        assertEquals(Result.retry(), result)
    }
}
