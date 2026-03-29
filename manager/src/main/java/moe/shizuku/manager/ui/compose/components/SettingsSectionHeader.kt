package moe.shizuku.manager.ui.compose.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * M3-compliant Settings Section Header
 * 
 * Follows Material Design 3 guidelines:
 * - Typography: labelLarge (11dp, medium weight)
 * - Color: onSurfaceVariant with emphasis
 * - Padding: 16dp horizontal, 8dp vertical spacing
 */
@Composable
fun SettingsSectionHeader(
    title: String,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(vertical = 4.dp)
        )
    }
}

/**
 * Vertical spacer for M3 8dp grid system
 */
@Composable
fun SettingsSpacer(
    height: Int = 8 // M3 grid unit
) {
    Spacer(modifier = Modifier.padding(top = height.dp))
}
