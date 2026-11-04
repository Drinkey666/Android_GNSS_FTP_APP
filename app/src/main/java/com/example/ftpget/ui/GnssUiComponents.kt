package com.example.ftpget.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.ftpget.GnssViewPalette

internal enum class ActionStyle { Primary, Tonal, Outline, Quiet }

@Composable
internal fun AppAction(action: UiAction, modifier: Modifier = Modifier, style: ActionStyle = ActionStyle.Outline) {
    val size = modifier.heightIn(min = 48.dp)
    when (style) {
        ActionStyle.Primary -> Button({ action.click() }, size,
            enabled = action.isEnabled, shape = MaterialTheme.shapes.small) { Text(action.text) }
        ActionStyle.Tonal -> FilledTonalButton({ action.click() }, size,
            enabled = action.isEnabled, shape = MaterialTheme.shapes.small) { Text(action.text) }
        ActionStyle.Outline -> OutlinedButton({ action.click() }, size,
            enabled = action.isEnabled, shape = MaterialTheme.shapes.small) { Text(action.text) }
        ActionStyle.Quiet -> TextButton({ action.click() }, size,
            enabled = action.isEnabled) { Text(action.text) }
    }
}

@Composable
internal fun ScreenScroll(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState())
        .padding(horizontal = GnssSpacing.screen, vertical = GnssSpacing.lg),
        verticalArrangement = Arrangement.spacedBy(GnssSpacing.lg)) {
        content()
    }
}

@Composable
internal fun SectionHeading(@StringRes title: Int, subtitle: String? = null) {
    Column(verticalArrangement = Arrangement.spacedBy(GnssSpacing.xs)) {
        Text(stringResource(title), style = MaterialTheme.typography.titleMedium)
        if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun EmptyState(@StringRes message: Int) {
    Text(stringResource(message), modifier = Modifier.fillMaxWidth().padding(vertical = GnssSpacing.xl),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
internal fun statusColor(message: String): Color = when {
    listOf("失败", "错误", "异常", "无法").any(message::contains) -> MaterialTheme.colorScheme.error
    message.contains("正在") || message.contains("等待") -> MaterialTheme.colorScheme.onSurfaceVariant
    listOf("成功", "完成").any(message::contains) -> MaterialTheme.colorScheme.secondary
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

@Composable
internal fun DetailText(value: String, modifier: Modifier = Modifier, maxLines: Int = Int.MAX_VALUE) {
    Text(value, modifier = modifier, style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = maxLines, overflow = TextOverflow.Ellipsis)
}

@Composable
internal fun NumericText(value: String, modifier: Modifier = Modifier, prominent: Boolean = false) {
    Text(value, modifier = modifier,
        style = if (prominent) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.titleMedium,
        fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

@Composable
internal fun viewPalette(): GnssViewPalette {
    val colors = MaterialTheme.colorScheme
    val dark = LocalGnssDark.current
    return GnssViewPalette(
        dark = dark,
        background = colors.surface.toArgb(),
        foreground = colors.onSurface.toArgb(),
        muted = colors.onSurfaceVariant.toArgb(),
        grid = colors.outlineVariant.toArgb(),
        header = colors.surfaceVariant.toArgb(),
        alternate = colors.background.toArgb(),
        success = colors.secondary.toArgb(),
        satelliteText = if (dark) colors.background.toArgb() else colors.surface.toArgb(),
    )
}
