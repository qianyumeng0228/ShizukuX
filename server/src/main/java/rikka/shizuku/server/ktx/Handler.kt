package rikka.shizuku.server.ktx

import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher

val mainHandler by lazy {
    Handler(Looper.getMainLooper())
}

private val workerThread by lazy(LazyThreadSafetyMode.NONE) {
    HandlerThread("Worker").apply { start() }
}

val workerHandler by lazy {
    Handler(workerThread.looper)
}

val serverScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
val workerDispatcher = workerThread.looper.asCoroutineDispatcher()
