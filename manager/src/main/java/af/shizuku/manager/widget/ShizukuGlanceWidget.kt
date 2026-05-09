package af.shizuku.manager.widget

import android.content.Context
import android.graphics.Color
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
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.text.FontWeight
import af.shizuku.manager.MainActivity
import af.shizuku.manager.R
import af.shizuku.manager.starter.StarterActivity
import af.shizuku.manager.utils.ShizukuStateMachine
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

class ShizukuGlanceWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent {
            Content(context)
        }
    }

    @Composable
    private fun Content(context: Context) {
        val state = ShizukuStateMachine.get()
        val isRunning = state == ShizukuStateMachine.State.RUNNING

        val bgColor = ColorProvider(
            day = ComposeColor(0xFFFFFBFE),
            night = ComposeColor(0xFF1C1B1F)
        )
        val containerColor = if (isRunning) {
            ColorProvider(day = ComposeColor(0xFFEADDFF), night = ComposeColor(0xFF4F378B))
        } else {
            ColorProvider(day = ComposeColor(0xFFFFDAD6), night = ComposeColor(0xFF93000A))
        }
        val iconTint = if (isRunning) {
            ColorProvider(day = ComposeColor(0xFF21005D), night = ComposeColor(0xFFFFD8E4))
        } else {
            ColorProvider(day = ComposeColor(0xFF410002), night = ComposeColor(0xFFFFB4AB))
        }
        val titleColor = ColorProvider(
            day = ComposeColor(0xFF1C1B1F),
            night = ComposeColor(0xFFE6E1E5)
        )
        val subtitleColor = ColorProvider(
            day = ComposeColor(0xFF49454F),
            night = ComposeColor(0xFFCAC4D0)
        )

        Row(
            modifier = GlanceModifier
                .fillMaxSize()
                .background(bgColor)
                .padding(16.dp)
                .clickable(actionStartActivity<MainActivity>()),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = GlanceModifier
                    .size(48.dp)
                    .background(containerColor)
                    .padding(10.dp),
                contentAlignment = Alignment.Center
            ) {
                Image(
                    provider = ImageProvider(
                        if (isRunning) R.drawable.ic_server_ok_24 else R.drawable.ic_server_error_24
                    ),
                    contentDescription = null,
                    colorFilter = ColorFilter.tint(iconTint)
                )
            }

            Spacer(modifier = GlanceModifier.width(16.dp))

            Column(modifier = GlanceModifier.defaultWeight()) {
                Text(
                    text = "ShizukuX",
                    style = TextStyle(
                        color = titleColor,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium
                    )
                )
                Text(
                    text = if (isRunning) "Running" else "Stopped",
                    style = TextStyle(color = subtitleColor, fontSize = 14.sp)
                )
            }

            if (!isRunning) {
                Box(
                    modifier = GlanceModifier
                        .size(48.dp)
                        .background(
                            ColorProvider(
                                day = ComposeColor(0xFFE8DEF8),
                                night = ComposeColor(0xFF4A4458)
                            )
                        )
                        .padding(10.dp)
                        .clickable(actionStartActivity<StarterActivity>()),
                    contentAlignment = Alignment.Center
                ) {
                    Image(
                        provider = ImageProvider(R.drawable.ic_server_start_24),
                        contentDescription = "Start",
                        colorFilter = ColorFilter.tint(
                            ColorProvider(
                                day = ComposeColor(0xFF1D192B),
                                night = ComposeColor(0xFFCCC2DC)
                            )
                        )
                    )
                }
            }
        }
    }
}
