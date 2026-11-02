@file:OptIn(ExperimentalTvMaterial3Api::class)

package af.shizuku.manager.adb

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button as TvButton
import androidx.tv.material3.ClickableSurfaceDefaults as TvClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme as TvMaterialTheme
import androidx.tv.material3.Surface as TvSurface
import androidx.tv.material3.Text as TvText
import af.shizuku.manager.R
import af.shizuku.manager.ui.compose.ShizukuIcon
import rikka.compatibility.DeviceCompatibility

@Composable
fun TvAdbPairingTutorialScreen(
    onNavigateUp: () -> Unit,
    notificationEnabled: Boolean,
    onOpenNotificationSettings: () -> Unit,
    onOpenDeveloperOptions: () -> Unit,
    onFinish: () -> Unit
) {
    Row(modifier = Modifier.fillMaxSize()) {

        Column(
            modifier = Modifier
                .padding(32.dp)
                .size(width = 300.dp, height = 500.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            TvText(
                text = stringResource(R.string.adb_pairing_tutorial_title),
                style = TvMaterialTheme.typography.headlineMedium,
                color = TvMaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(bottom = 24.dp)
            )

            TvMenuButton(
                icon = R.drawable.ic_arrow_back_24,
                label = android.R.string.cancel,
                onClick = onNavigateUp
            )
        }


        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxSize(),
            contentPadding = PaddingValues(32.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            if (notificationEnabled) {
                item {
                    TvTutorialStepCard(
                        icon = R.drawable.ic_outline_notifications_active_24,
                        title = stringResource(R.string.notification_channel_adb_pairing),
                        body = stringResource(R.string.adb_pairing_tutorial_content_notification)
                    )
                }
                item {
                    TvTutorialStepCard(
                        icon = R.drawable.ic_help_outline_24dp,
                        title = stringResource(R.string.home_local_network_title),
                        body = stringResource(R.string.adb_pairing_tutorial_content_network) +
                                "\n\n" +
                                stringResource(R.string.adb_pairing_tutorial_content_network_limation_not_foreground)
                    )
                }
            } else {
                item {
                    TvTutorialStepCard(
                        icon = R.drawable.ic_outline_info_24,
                        title = stringResource(R.string.notification_settings),
                        body = stringResource(R.string.adb_pairing_tutorial_content_notification_blocked),
                        danger = true
                    ) {
                        TvButton(onClick = onOpenNotificationSettings) {
                            TvText(stringResource(R.string.notification_settings))
                        }
                    }
                }
            }

            if (DeviceCompatibility.isMiui()) {
                item {
                    TvTutorialStepCard(
                        icon = R.drawable.ic_warning_24,
                        title = "MIUI",
                        body = stringResource(R.string.adb_pairing_tutorial_content_miui) +
                                "\n\n" +
                                stringResource(R.string.adb_pairing_tutorial_content_miui_2),
                        danger = true
                    )
                }
            }

            if (notificationEnabled) {
                item {
                    TvTutorialStepCard(
                        number = 1,
                        title = stringResource(R.string.adb_pairing_tutorial_content_steps),
                        body = stringResource(R.string.adb_pairing_tutorial_content_left_is_clickable),
                    ) {
                        TvButton(onClick = onOpenDeveloperOptions) {
                            TvText(stringResource(R.string.development_settings))
                        }
                    }
                }
                item {
                    TvTutorialStepCard(
                        number = 2,
                        title = stringResource(R.string.adb_pairing_tutorial_content_enter_pairing_code),
                        body = stringResource(R.string.adb_pairing_tutorial_content_notification)
                    )
                }
                item {
                    TvTutorialStepCard(
                        number = 3,
                        title = stringResource(R.string.adb_pairing_tutorial_content_finish)
                    ) {
                        TvButton(onClick = onFinish) {
                            TvText(stringResource(android.R.string.ok))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TvTutorialStepCard(
    number: Int? = null,
    icon: Int? = null,
    title: String,
    body: String? = null,
    danger: Boolean = false,
    content: @Composable () -> Unit = {}
) {
    val alpha = if (isSystemInDarkTheme()) 0.3f else 0.12f
    TvSurface(
        onClick = {},
        modifier = Modifier.fillMaxWidth(),
        shape = TvClickableSurfaceDefaults.shape(shape = TvMaterialTheme.shapes.large),
        colors = TvClickableSurfaceDefaults.colors(
            containerColor = if (danger) {
                TvMaterialTheme.colorScheme.errorContainer.copy(alpha = alpha)
            } else {
                TvMaterialTheme.colorScheme.surfaceVariant.copy(alpha = alpha)
            },
            focusedContainerColor = if (danger) {
                TvMaterialTheme.colorScheme.errorContainer
            } else {
                TvMaterialTheme.colorScheme.surfaceVariant
            }
        )
    ) {
        Row(
            modifier = Modifier.padding(24.dp),
            horizontalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            if (number != null) {
                TvText(
                    text = number.toString(),
                    style = TvMaterialTheme.typography.headlineLarge,
                    color = TvMaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold
                )
            } else if (icon != null) {
                ShizukuIcon(
                    icon = icon,
                    modifier = Modifier.size(48.dp),
                    tint = if (danger) TvMaterialTheme.colorScheme.error else TvMaterialTheme.colorScheme.primary
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TvText(
                    text = title,
                    style = TvMaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                if (body != null) {
                    TvText(
                        text = body,
                        style = TvMaterialTheme.typography.bodyMedium,
                        color = if (danger) TvMaterialTheme.colorScheme.onErrorContainer else TvMaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                content()
            }
        }
    }
}

@Composable
private fun TvMenuButton(
    icon: Int,
    label: Int,
    onClick: () -> Unit
) {
    val alpha = if (isSystemInDarkTheme()) 0.3f else 0.12f
    TvSurface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = TvClickableSurfaceDefaults.shape(shape = TvMaterialTheme.shapes.medium),
        colors = TvClickableSurfaceDefaults.colors(
            containerColor = TvMaterialTheme.colorScheme.surfaceVariant.copy(alpha = alpha),
            focusedContainerColor = TvMaterialTheme.colorScheme.primaryContainer
        )
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            ShizukuIcon(icon = icon, modifier = Modifier.size(24.dp))
            TvText(text = stringResource(label), style = TvMaterialTheme.typography.labelLarge)
        }
    }
}
