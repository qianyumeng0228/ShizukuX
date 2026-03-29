package moe.shizuku.manager.ui.compose.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * M3-compliant Settings List Item
 * 
 * Follows Material Design 3 guidelines:
 * - Shape: ShapeDefaults.Medium (12dp corner radius)
 * - Icon: 24dp from Material Icons Extended
 * - Padding: 16dp internal, 8dp between items
 * - Colors: onSurfaceVariant for icons, onSurface for text
 */
@Composable
fun SettingsListItem(
    title: String,
    subtitle: String? = null,
    leadingIcon: ImageVector,
    trailingContent: @Composable () -> Unit = {},
    onClick: (() -> Unit)? = null,
    enabled: Boolean = true
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (onClick != null && enabled) {
                    Modifier.clickable(onClick = onClick)
                } else {
                    Modifier
                }
            ),
        colors = CardDefaults.cardColors(
            containerColor = if (enabled) {
                MaterialTheme.colorScheme.surfaceVariant
            } else {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            }
        ),
        shape = MaterialTheme.shapes.medium, // 12dp corner radius (M3 ShapeDefaults.Medium)
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp), // M3 standard internal padding
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Leading Icon - 24dp from Material Icons
            Icon(
                imageVector = leadingIcon,
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = if (enabled) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                }
            )
            
            Spacer(modifier = Modifier.width(16.dp)) // 16dp gap (M3 grid)
            
            // Title and Subtitle
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (enabled) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    },
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                
                if (subtitle != null) {
                    Spacer(modifier = Modifier.padding(top = 4.dp))
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (enabled) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                        },
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            
            Spacer(modifier = Modifier.width(16.dp)) // 16dp gap (M3 grid)
            
            // Trailing Content (Switch, Arrow, etc.)
            trailingContent()
        }
    }
}

/**
 * Settings List Item with Switch
 */
@Composable
fun SettingsListItemWithSwitch(
    title: String,
    subtitle: String? = null,
    leadingIcon: ImageVector,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true
) {
    SettingsListItem(
        title = title,
        subtitle = subtitle,
        leadingIcon = leadingIcon,
        trailingContent = {
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                enabled = enabled
            )
        },
        onClick = { onCheckedChange(!checked) },
        enabled = enabled
    )
}

/**
 * Settings List Item with Navigation Arrow
 */
@Composable
fun SettingsListItemWithNavigation(
    title: String,
    subtitle: String? = null,
    leadingIcon: ImageVector,
    onClick: () -> Unit,
    enabled: Boolean = true
) {
    SettingsListItem(
        title = title,
        subtitle = subtitle,
        leadingIcon = leadingIcon,
        trailingContent = {
            Icon(
                imageVector = androidx.compose.material.icons.Icons.Outlined.ChevronRight,
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        },
        onClick = onClick,
        enabled = enabled
    )
}
