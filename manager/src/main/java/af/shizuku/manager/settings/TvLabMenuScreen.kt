@file:OptIn(ExperimentalTvMaterial3Api::class)

package af.shizuku.manager.settings

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ClickableSurfaceDefaults as TvClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme as TvMaterialTheme
import androidx.tv.material3.Surface as TvSurface
import androidx.tv.material3.Text as TvText
import af.shizuku.manager.R
import af.shizuku.manager.ShizukuSettings
import af.shizuku.manager.ui.compose.ShizukuIcon

@Composable
fun TvLabMenuScreen(
    onNavigateUp: () -> Unit
) {
    val context = LocalContext.current
    var stealthEnabled by remember { mutableStateOf(ShizukuSettings.isStealthModeEnabled()) }
    var showStealthDialog by remember { mutableStateOf(false) }

    Row(modifier = Modifier.fillMaxSize().padding(32.dp)) {
        Column(
            modifier = Modifier
                .size(width = 300.dp, height = 500.dp)
                .padding(end = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            TvText(
                text = stringResource(R.string.lab_features_title),
                style = TvMaterialTheme.typography.headlineMedium,
                color = TvMaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(bottom = 16.dp)
            )

            TvLabButton(
                icon = R.drawable.ic_arrow_back_24,
                label = android.R.string.cancel,
                onClick = onNavigateUp
            )
        }

        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                TvText(
                    text = stringResource(R.string.lab_features_summary),
                    style = TvMaterialTheme.typography.titleMedium,
                    color = TvMaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
                )
            }

            item {
                TvLabToggleCard(
                    title = stringResource(R.string.settings_stealth_mode),
                    summary = stringResource(R.string.settings_stealth_mode_summary),
                    checked = stealthEnabled,
                    onToggle = {
                        if (!stealthEnabled) showStealthDialog = true
                        else {
                            stealthEnabled = false
                            ShizukuSettings.setStealthModeEnabled(false)
                        }
                    }
                )
            }
        }
    }

    if (showStealthDialog) {
        AlertDialog(
            onDismissRequest = { showStealthDialog = false },
            title = { TvText(stringResource(R.string.settings_enable_stealth_title)) },
            text = { TvText(stringResource(R.string.settings_enable_stealth_message)) },
            confirmButton = {
                TvSurface(
                    onClick = {
                        showStealthDialog = false
                        stealthEnabled = true
                        ShizukuSettings.setStealthModeEnabled(true)
                    },
                    shape = TvClickableSurfaceDefaults.shape(TvMaterialTheme.shapes.small),
                    colors = TvClickableSurfaceDefaults.colors(
                        containerColor = TvMaterialTheme.colorScheme.primaryContainer
                    )
                ) {
                    TvText(
                        text = stringResource(R.string.settings_enable_stealth_confirm),
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp)
                    )
                }
            },
            dismissButton = {
                TvSurface(
                    onClick = { showStealthDialog = false },
                    shape = TvClickableSurfaceDefaults.shape(TvMaterialTheme.shapes.small),
                    colors = TvClickableSurfaceDefaults.colors(
                        containerColor = TvMaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    TvText(
                        text = stringResource(android.R.string.cancel),
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp)
                    )
                }
            },
            containerColor = TvMaterialTheme.colorScheme.surfaceVariant,
            shape = TvMaterialTheme.shapes.extraLarge
        )
    }
}

@Composable
private fun TvLabToggleCard(
    title: String,
    summary: String,
    checked: Boolean,
    onToggle: () -> Unit
) {
    TvSurface(
        onClick = onToggle,
        modifier = Modifier.fillMaxWidth(),
        shape = TvClickableSurfaceDefaults.shape(TvMaterialTheme.shapes.large),
        colors = TvClickableSurfaceDefaults.colors(
            containerColor = if (checked) TvMaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
            else TvMaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
            focusedContainerColor = if (checked) TvMaterialTheme.colorScheme.primaryContainer
            else TvMaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(
            modifier = Modifier.padding(24.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            Column(modifier = Modifier.weight(1f)) {
                TvText(
                    text = title,
                    style = TvMaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                TvText(
                    text = summary,
                    style = TvMaterialTheme.typography.bodyMedium,
                    color = TvMaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            TvText(
                text = if (checked) "ON" else "OFF",
                style = TvMaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = if (checked) TvMaterialTheme.colorScheme.primary else TvMaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun TvLabActionCard(
    title: String,
    summary: String,
    onClick: () -> Unit
) {
    TvSurface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = TvClickableSurfaceDefaults.shape(TvMaterialTheme.shapes.large),
        colors = TvClickableSurfaceDefaults.colors(
            containerColor = TvMaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
            focusedContainerColor = TvMaterialTheme.colorScheme.primaryContainer
        )
    ) {
        Column(modifier = Modifier.padding(24.dp)) {
            TvText(
                text = title,
                style = TvMaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            TvText(
                text = summary,
                style = TvMaterialTheme.typography.bodyMedium,
                color = TvMaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun TvLabButton(
    icon: Int,
    label: Int,
    onClick: () -> Unit
) {
    TvSurface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = TvClickableSurfaceDefaults.shape(TvMaterialTheme.shapes.medium),
        colors = TvClickableSurfaceDefaults.colors(
            containerColor = TvMaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
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
