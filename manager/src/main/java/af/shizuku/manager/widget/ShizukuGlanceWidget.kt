package af.shizuku.manager.widget

import android.content.Context
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.provideContent
import androidx.glance.layout.*
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import androidx.glance.ColorFilter
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.material3.ColorProviders
import androidx.glance.text.FontWeight
import af.shizuku.manager.MainActivity
import af.shizuku.manager.R
import af.shizuku.manager.starter.StarterActivity
import af.shizuku.manager.utils.ShizukuStateMachine
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.appwidget.updateAll

class ShizukuGlanceWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent {
            GlanceTheme {
                Content(context)
            }
        }
    }

    @Composable
    private fun Content(context: Context) {
        val state = ShizukuStateMachine.get()
        val isRunning = state == ShizukuStateMachine.State.RUNNING

        Row(
            modifier = GlanceModifier
                .fillMaxSize()
                .background(GlanceTheme.colors.surface)
                .padding(16.dp)
                .clickable(actionStartActivity<MainActivity>()),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Status Icon
            Box(
                modifier = GlanceModifier
                    .size(48.dp)
                    .background(if (isRunning) GlanceTheme.colors.primaryContainer else GlanceTheme.colors.errorContainer)
                    .padding(10.dp),
                contentAlignment = Alignment.Center
            ) {
                Image(
                    provider = ImageProvider(if (isRunning) R.drawable.ic_server_ok_24 else R.drawable.ic_server_error_24),
                    contentDescription = null,
                    colorFilter = ColorFilter.tint(if (isRunning) GlanceTheme.colors.onPrimaryContainer else GlanceTheme.colors.onErrorContainer)
                )
            }

            Spacer(modifier = GlanceModifier.width(16.dp))

            Column(modifier = GlanceModifier.defaultWeight()) {
                Text(
                    text = "ShizukuX",
                    style = TextStyle(
                        color = GlanceTheme.colors.onSurface,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium
                    )
                )
                Text(
                    text = if (isRunning) "Running" else "Stopped",
                    style = TextStyle(
                        color = GlanceTheme.colors.onSurfaceVariant,
                        fontSize = 14.sp
                    )
                )
            }

            if (!isRunning) {
                Box(
                    modifier = GlanceModifier
                        .size(48.dp)
                        .background(GlanceTheme.colors.secondaryContainer)
                        .padding(10.dp)
                        .clickable(actionStartActivity<StarterActivity>()),
                    contentAlignment = Alignment.Center
                ) {
                    Image(
                        provider = ImageProvider(R.drawable.ic_server_start_24),
                        contentDescription = "Start",
                        colorFilter = ColorFilter.tint(GlanceTheme.colors.onSecondaryContainer)
                    )
                }
            }
        }
    }
}

/**
 * Simple Material 3 Theme wrapper for Glance
 */
@Composable
fun GlanceTheme(content: @Composable () -> Unit) {
    androidx.glance.material3.GlanceTheme(
        colors = ColorProviders(
            light = androidx.compose.material3.lightColorScheme(),
            dark = androidx.compose.material3.darkColorScheme()
        ),
        content = content
    )
}
