package com.example.ftpget.ui

import android.os.SystemClock
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.ftpget.GnssDataTableView
import com.example.ftpget.R
import kotlinx.coroutines.delay
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogPage(
    state: MainUiState,
    measurements: GnssDataTableView? = null,
    modifier: Modifier = Modifier,
) {
    var showProductDetails by rememberSaveable { mutableStateOf(false) }
    var showActivityDetails by rememberSaveable { mutableStateOf(false) }
    var showObservations by rememberSaveable { mutableStateOf(false) }
    var showCaptureOptions by rememberSaveable { mutableStateOf(false) }
    var showExports by rememberSaveable { mutableStateOf(false) }
    val palette = viewPalette()

    ScreenScroll(modifier) {
        CaptureSection(state, showCaptureOptions) { showCaptureOptions = !showCaptureOptions }
        ProductsSection(state, showProductDetails) { showProductDetails = !showProductDetails }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        FilesSection(state) { showExports = true }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        ActivitySection(state, showActivityDetails) { showActivityDetails = !showActivityDetails }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Column(verticalArrangement = Arrangement.spacedBy(GnssSpacing.sm)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SectionHeading(R.string.section_measurements, state.tvMeasurementSummary.text)
                TextButton(onClick = { showObservations = !showObservations }) {
                    Text(stringResource(if (showObservations) R.string.hide_observations
                    else R.string.show_observations))
                }
            }
            if (showObservations) {
                if (state.measurementCount == 0 || measurements == null) {
                    EmptyState(R.string.preview_plot)
                } else {
                    AndroidView(
                        factory = { measurements },
                        update = { it.setPalette(palette) },
                        modifier = Modifier.fillMaxWidth()
                            .height((44 + 45 * state.measurementCount).dp),
                    )
                }
                DetailText(stringResource(R.string.measurement_hint))
            }
        }
    }
    if (showExports) {
        ModalBottomSheet(onDismissRequest = { showExports = false }) {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(horizontal = GnssSpacing.screen)
                .padding(bottom = GnssSpacing.xxl),
                verticalArrangement = Arrangement.spacedBy(GnssSpacing.sm)) {
                Text(stringResource(R.string.more_file_actions),
                    style = MaterialTheme.typography.titleLarge)
                FileActionRow(state.btnChooseExportDirectory)
                FileActionRow(state.btnExportLatest)
                FileActionRow(state.btnExportProducts)
                DetailText(state.tvExportDirectory.text)
            }
        }
    }
}

@Composable
private fun CaptureSection(state: MainUiState, optionsExpanded: Boolean, onToggleOptions: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(GnssSpacing.sm),
        horizontalAlignment = Alignment.End) {
        Surface(
            color = MaterialTheme.colorScheme.primaryContainer,
            shape = MaterialTheme.shapes.medium,
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(GnssSpacing.lg),
                verticalArrangement = Arrangement.spacedBy(GnssSpacing.md),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(R.string.section_capture),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                    Text(
                        state.tvStatus.text,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(stringResource(R.string.label_duration),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.72f))
                CaptureClock(state.chronometer)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(GnssSpacing.sm),
                ) {
                    AppAction(
                        state.btnStart,
                        modifier = Modifier.weight(1f),
                        style = if (state.btnStop.isEnabled) ActionStyle.Outline else ActionStyle.Primary,
                    )
                    AppAction(
                        state.btnStop,
                        modifier = Modifier.weight(1f),
                        style = if (state.btnStop.isEnabled) ActionStyle.Primary else ActionStyle.Outline,
                    )
                }
            }
        }
        TextButton(onClick = onToggleOptions) {
            Text(stringResource(if (optionsExpanded) R.string.show_less else R.string.capture_options))
        }
        if (optionsExpanded) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = state.captureCodePhaseRepair,
                    onCheckedChange = { state.captureCodePhaseRepair = it },
                    enabled = state.btnStart.isEnabled)
                Column {
                    Text(stringResource(R.string.capture_phase_repair),
                        style = MaterialTheme.typography.bodyMedium)
                    DetailText(stringResource(R.string.capture_phase_hint))
                }
            }
        }
    }
}

@Composable
private fun ProductsSection(
    state: MainUiState,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(GnssSpacing.sm)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SectionHeading(R.string.section_products)
            AppAction(state.btnDownload, style = ActionStyle.Outline)
        }
        DetailText(state.tvDownloadDate.text)
        if (state.downloadProgressBar.isIndeterminate) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        } else {
            LinearProgressIndicator(
                progress = { state.downloadProgressBar.progress.coerceIn(0, 100) / 100f },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                state.tvDownloadStage.text,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall,
                color = statusColor(state.tvDownloadStage.text),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                state.tvDownloadPercent.text,
                style = MaterialTheme.typography.labelMedium,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onToggle) {
                Text(stringResource(if (expanded) R.string.show_less else R.string.show_more))
            }
        }
        if (expanded) DetailText(state.tvDownloadFile.text)
    }
}

@Composable
private fun FilesSection(state: MainUiState, onMoreFiles: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(GnssSpacing.sm)) {
        SectionHeading(R.string.section_files)
        AppAction(state.btnConvert, Modifier.fillMaxWidth(), ActionStyle.Primary)
        DetailText(state.tvFilePath.text)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onMoreFiles) { Text(stringResource(R.string.more_file_actions)) }
        }
    }
}

@Composable
private fun FileActionRow(action: UiAction) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth()
                .heightIn(min = 48.dp)
                .clickable(enabled = action.isEnabled, role = Role.Button) { action.click() }
                .padding(vertical = GnssSpacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                action.text,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
                color = if (action.isEnabled) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
            )
            Icon(
                painter = painterResource(R.drawable.ic_chevron_right_24),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(
                    alpha = if (action.isEnabled) 1f else 0.38f),
            )
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

@Composable
private fun ActivitySection(
    state: MainUiState,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(GnssSpacing.sm)) {
        SectionHeading(R.string.latest_activity)
        Text(
            state.tvCaptureMessage.text,
            style = MaterialTheme.typography.bodyMedium,
            color = statusColor(state.tvCaptureMessage.text),
        )
        Text(
            state.tvDownloadFile.text,
            style = MaterialTheme.typography.bodySmall,
            color = statusColor(state.tvDownloadFile.text),
            maxLines = if (expanded) Int.MAX_VALUE else 3,
            overflow = TextOverflow.Ellipsis,
        )
        if (expanded && state.tvDownloadLog.text.isNotBlank()) {
            SelectionContainer {
                Text(state.tvDownloadLog.text,
                    modifier = Modifier.heightIn(max = 280.dp).verticalScroll(rememberScrollState()),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace)
            }
        }
        if (state.tvDownloadFile.text.length > 120 || state.tvDownloadLog.text.isNotBlank()) {
            TextButton(onClick = onToggle) {
                Text(stringResource(if (expanded) R.string.show_less else R.string.show_more))
            }
        }
    }
}

@Composable
private fun CaptureClock(state: UiChronometer) {
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(state.isRunning, state.base) {
        while (state.isRunning) {
            now = SystemClock.elapsedRealtime()
            delay(1000)
        }
    }
    val elapsed = if (state.isRunning) (now - state.base).coerceAtLeast(0L)
    else state.stoppedElapsed
    val seconds = elapsed / 1000
    Text(
        "%02d:%02d:%02d".format(Locale.US, seconds / 3600, seconds / 60 % 60, seconds % 60),
        style = MaterialTheme.typography.displaySmall,
        fontFamily = FontFamily.Monospace,
        color = MaterialTheme.colorScheme.onPrimaryContainer,
    )
}

@Preview(showBackground = true, name = "LOG · Light")
@Composable private fun LogLightPreview() {
    GnssTheme { LogPage(remember { MainUiState().apply { btnStop.isEnabled = false } }) }
}

@Preview(showBackground = true, name = "LOG · Dark")
@Composable private fun LogDarkPreview() {
    GnssTheme(darkTheme = true) {
        LogPage(remember { MainUiState().apply { btnStop.isEnabled = false } })
    }
}
