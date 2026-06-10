package af.shizuku.core.ui.compose

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp

enum class ButtonSize {
    Medium, Large
}

@Composable
fun Button(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    size: ButtonSize = ButtonSize.Medium,
    shape: Shape = MaterialTheme.shapes.large,
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
    leadingIcon: @Composable (() -> Unit)? = null,
    trailingIcon: @Composable (() -> Unit)? = null,
    content: @Composable RowScope.() -> Unit
) {
    val minHeight = when (size) {
        ButtonSize.Medium -> 48.dp
        ButtonSize.Large -> 72.dp
    }
    
    androidx.compose.material3.Button(
        onClick = onClick,
        modifier = modifier.defaultMinSize(minHeight = minHeight),
        enabled = enabled,
        shape = shape,
        interactionSource = interactionSource,
        contentPadding = contentPadding
    ) {
        if (leadingIcon != null) {
            leadingIcon()
        }
        content()
        if (trailingIcon != null) {
            trailingIcon()
        }
    }
}
