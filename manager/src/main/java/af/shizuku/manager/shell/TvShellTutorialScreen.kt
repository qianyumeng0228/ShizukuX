@file:OptIn(ExperimentalTvMaterial3Api::class)

package af.shizuku.manager.shell

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
import af.shizuku.manager.ui.compose.htmlToPlainText

@Composable
fun TvShellTutorialScreen(
    onNavigateUp: () -> Unit,
    shName: String,
    dexName: String,
    onExportFiles: () -> Unit,
    onViewHelp: () -> Unit
) {
    Row(modifier = Modifier.fillMaxSize()) {

        Column(
            modifier = Modifier
                .padding(32.dp)
                .size(width = 300.dp, height = 500.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            TvText(
                text = stringResource(R.string.home_terminal_title),
                style = TvMaterialTheme.typography.headlineMedium,
                color = TvMaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(bottom = 24.dp)
            )

            TvMenuButton(
                icon = R.drawable.ic_arrow_back_24,
                label = android.R.string.cancel,
                onClick = onNavigateUp
            )

            TvMenuButton(
                icon = R.drawable.ic_help_outline_24dp,
                label = R.string.home_adb_button_view_help,
                onClick = onViewHelp
            )
        }


        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxSize(),
            contentPadding = PaddingValues(32.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            item {
                TvTutorialStepCard(
                    number = 1,
                    title = htmlToPlainText(stringResource(R.string.terminal_tutorial_1, shName, dexName)),
                    body = stringResource(R.string.terminal_tutorial_1_description)
                ) {
                    TvButton(onClick = onExportFiles) {
                        TvText(stringResource(R.string.terminal_export_files))
                    }
                }
            }
            item {
                TvTutorialStepCard(
                    number = 2,
                    title = htmlToPlainText(stringResource(R.string.terminal_tutorial_2, shName)),
                    body = htmlToPlainText(stringResource(
                        R.string.terminal_tutorial_2_description,
                        "Termux", "PKG", "com.termux", "com.termux"
                    ))
                )
            }
            item {
                TvTutorialStepCard(
                    number = 3,
                    title = htmlToPlainText(stringResource(R.string.terminal_tutorial_3, "sh $shName")),
                    body = htmlToPlainText(stringResource(R.string.terminal_tutorial_3_description, shName, "PATH"))
                )
            }
        }
    }
}

@Composable
private fun TvTutorialStepCard(
    number: Int,
    title: String,
    body: String,
    content: @Composable () -> Unit = {}
) {
    val alpha = if (isSystemInDarkTheme()) 0.3f else 0.12f
    TvSurface(
        onClick = {},
        modifier = Modifier.fillMaxWidth(),
        shape = TvClickableSurfaceDefaults.shape(shape = TvMaterialTheme.shapes.large),
        colors = TvClickableSurfaceDefaults.colors(
            containerColor = TvMaterialTheme.colorScheme.surfaceVariant.copy(alpha = alpha),
            focusedContainerColor = TvMaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(
            modifier = Modifier.padding(24.dp),
            horizontalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            TvText(
                text = number.toString(),
                style = TvMaterialTheme.typography.headlineLarge,
                color = TvMaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold
            )
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TvText(
                    text = title,
                    style = TvMaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                TvText(
                    text = body,
                    style = TvMaterialTheme.typography.bodyMedium,
                    color = TvMaterialTheme.colorScheme.onSurfaceVariant
                )
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
    val alpha = if (isSystemInDarkTheme()) 0.5f else 0.2f
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
