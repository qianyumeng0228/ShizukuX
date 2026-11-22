package af.shizuku.core.ui.compose

import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import af.shizuku.core.ui.R

/**
 * The selected wallpaper is shared across every glass host.
 *
 * It deliberately lives in core-ui instead of reading the manager preference from Compose:
 * manager Activities that do not re-create themselves on a wallpaper change (the shell) then still
 * recompose immediately, and core-ui stays free of a dependency on the manager module.
 */
object GlassWallpaperState {
    var style by mutableStateOf("apple")
        private set

    fun update(value: String) {
        if (style != value) style = value
    }
}

private fun wallpaperResourceName(style: String): String = when (style) {
    "apple" -> "liquid_glass_apple"
    "white_miku" -> "wallpaper_bg_light"
    "black_miku" -> "wallpaper_bg_dark"
    else -> "liquid_glass_reference"
}

private fun resolveWallpaperResource(context: Context, style: String): Int {
    val name = wallpaperResourceName(style)
    val id = context.resources.getIdentifier(name, "drawable", context.packageName)
    return if (id != 0) id else R.drawable.liquid_glass_reference
}

/**
 * Draws the active wallpaper once. `Context.packageName` is the manager package on device, so the
 * manager-owned Apple artwork is picked up without core-ui having to know manager's R class.
 */
@Composable
fun GlassWallpaper(modifier: Modifier = Modifier.fillMaxSize()) {
    val context = LocalContext.current
    val style = GlassWallpaperState.style
    val resId = remember(context, style) { resolveWallpaperResource(context, style) }
    // PERF: the wallpaper is recorded into a full-screen backdrop layer and sampled every
    // scroll frame. Decoding it at half resolution keeps the mist artwork identical at 1080p
    // while cutting the texture/fill cost of that offscreen layer.
    val wallpaper = remember(context, resId) {
        BitmapFactory.decodeResource(
            context.resources,
            resId,
            BitmapFactory.Options().apply { inSampleSize = 2 }
        )?.asImageBitmap()
    }
    if (wallpaper != null) {
        Image(
            bitmap = wallpaper,
            contentDescription = null,
            modifier = modifier,
            contentScale = ContentScale.Crop
        )
    } else {
        Image(
            painter = painterResource(resId),
            contentDescription = null,
            modifier = modifier,
            contentScale = ContentScale.Crop
        )
    }
}
