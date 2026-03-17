package moe.shizuku.manager.utils

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.graphics.drawable.AdaptiveIconDrawable
import android.os.Build
import android.widget.ImageView
import androidx.collection.LruCache
import kotlinx.coroutines.*
import me.zhanghai.android.appiconloader.AppIconLoader
import moe.shizuku.manager.R
import rikka.core.util.BuildUtils
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

object AppIconCache {

    private class AppIconLruCache constructor(maxSize: Int) : LruCache<Triple<String, Int, Int>, Bitmap>(maxSize) {
        override fun sizeOf(key: Triple<String, Int, Int>, bitmap: Bitmap): Int {
            return bitmap.byteCount / 1024
        }
    }

    private val lruCache: LruCache<Triple<String, Int, Int>, Bitmap>
    private val labelCache = LruCache<String, String>(500)

    // Initialize dispatcher directly, as objects don't have init blocks in the same way as classes.
    private val backgroundExecutor: Executor = Executors.newFixedThreadPool(1.coerceAtLeast(Runtime.getRuntime().availableProcessors() / 2))
    private val dispatcher: CoroutineDispatcher = backgroundExecutor.asCoroutineDispatcher()

    // Use a dedicated scope for background tasks tied to the object's lifecycle.
    // SupervisorJob allows child jobs to fail without cancelling siblings.
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)

    // Lazily initialize app icon loaders per size.
    private val appIconLoaders = mutableMapOf<Int, AppIconLoader>()

    init {
        // Initialize app icon lru cache
        val maxMemory = Runtime.getRuntime().maxMemory() / 1024
        val availableCacheSize = (maxMemory / 4).toInt()
        lruCache = AppIconLruCache(availableCacheSize)
    }

    // Expose the dispatcher if needed elsewhere, or keep internal. For now, keep it internal.
    // fun dispatcher(): CoroutineDispatcher { return dispatcher }

    private fun get(packageName: String, userId: Int, size: Int): Bitmap? {
        return lruCache[Triple(packageName, userId, size)]
    }

    private fun put(packageName: String, userId: Int, size: Int, bitmap: Bitmap) {
        if (get(packageName, userId, size) == null) {
            lruCache.put(Triple(packageName, userId, size), bitmap)
        }
    }

    private fun remove(packageName: String, userId: Int, size: Int) {
        lruCache.remove(Triple(packageName, userId, size))
    }

    fun getLabel(context: Context, info: ApplicationInfo): String {
        val cached = labelCache[info.packageName]
        if (cached != null) return cached
        val label = info.loadLabel(context.packageManager).toString()
        labelCache.put(info.packageName, label)
        return label
    }

    @SuppressLint("NewApi")
    fun getOrLoadBitmap(context: Context, info: ApplicationInfo, userId: Int, size: Int): Bitmap? {
        val cachedBitmap = get(info.packageName, userId, size)
        if (cachedBitmap != null) {
            return cachedBitmap
        }
        var loader = appIconLoaders[size]
        if (loader == null) {
            // Use BuildUtils.atLeast30 to check API level for AdaptiveIconDrawable
            val shrinkNonAdaptiveIcons = BuildUtils.atLeast30 && context.applicationInfo.loadIcon(context.packageManager) is AdaptiveIconDrawable
            loader = AppIconLoader(size, shrinkNonAdaptiveIcons, context)
            appIconLoaders[size] = loader
        }
        val bitmap = try {
            withContext(dispatcher) {
                loader.loadIcon(info, false)
            }
        } catch (e: CancellationException) {
            // do nothing if canceled
            null
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to load icon for ${info.packageName}", e)
            null
        }
        put(info.packageName, userId, size, bitmap)
        return bitmap
    }

    @JvmStatic
    fun loadIconBitmapAsync(context: Context,
                            info: ApplicationInfo, userId: Int,
                            view: ImageView): Job {
        return scope.launch {
            val size = view.measuredWidth.let { if (it > 0) it else context.resources.getDimensionPixelSize(R.dimen.default_app_icon_size) }
            val cachedBitmap = get(info.packageName, userId, size)
            if (cachedBitmap != null) {
                withContext(Dispatchers.Main) {
                    view.setImageBitmap(cachedBitmap)
                }
                return@launch
            }
            
            if (Build.VERSION.SDK_INT >= 26) {
                view.setImageResource(R.drawable.ic_default_app_icon)
            } else {
                view.setImageDrawable(null)
            }

            val bitmap = try {
                withContext(dispatcher) {
                    getOrLoadBitmap(context, info, userId, size)
                }
            } catch (e: CancellationException) {
                // do nothing if canceled
                null
            } catch (e: Throwable) {
                Log.e(TAG, "Failed to load icon for ${info.packageName}", e)
                null
            }

            if (bitmap != null) {
                withContext(Dispatchers.Main) {
                    view.setImageBitmap(bitmap)
                }
            } else {
                if (Build.VERSION.SDK_INT >= 26) {
                    view.setImageResource(R.drawable.ic_default_app_icon)
                } else {
                    view.setImageDrawable(null)
                }
            }
        }
    }
}