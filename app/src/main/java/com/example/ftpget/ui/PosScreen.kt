package com.example.ftpget.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.ftpget.R
import java.util.Locale

@Composable
fun PosPage(state: MainUiState, modifier: Modifier = Modifier) {
    val fix = state.positionFix
    ScreenScroll(modifier) {
        DevicePositionSection(fix)
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Column(verticalArrangement = Arrangement.spacedBy(GnssSpacing.md)) {
            SectionHeading(R.string.section_ppp, stringResource(R.string.ppp_source_note))
            PppResult(state.tvPppResult.text)
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                if (maxWidth >= 440.dp) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(GnssSpacing.sm)) {
                        AppAction(state.btnLivePpp, Modifier.weight(1f), ActionStyle.Primary)
                        AppAction(state.btnRunPpp, Modifier.weight(1f), ActionStyle.Outline)
                    }
                } else {
                    Column(Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(GnssSpacing.sm)) {
                        AppAction(state.btnLivePpp, Modifier.fillMaxWidth(), ActionStyle.Primary)
                        AppAction(state.btnRunPpp, Modifier.fillMaxWidth(), ActionStyle.Outline)
                    }
                }
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        ReferenceErrorSection(state)
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        ReferencePositionSection(state)
    }
}

@Composable
private fun DevicePositionSection(fix: PositionFix?) {
    Column(verticalArrangement = Arrangement.spacedBy(GnssSpacing.md)) {
        SectionHeading(R.string.section_position, stringResource(R.string.location_note))
        PositionStatus(fix)
        val latitude = fix?.let { "%.6f°".format(Locale.US, it.latitude) } ?: "—"
        val longitude = fix?.let { "%.6f°".format(Locale.US, it.longitude) } ?: "—"
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            if (maxWidth >= 560.dp) {
                Row(horizontalArrangement = Arrangement.spacedBy(GnssSpacing.xl)) {
                    CoordinateField(R.string.label_latitude, latitude, Modifier.weight(1f))
                    CoordinateField(R.string.label_longitude, longitude, Modifier.weight(1f))
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(GnssSpacing.md)) {
                    CoordinateField(R.string.label_latitude, latitude)
                    CoordinateField(R.string.label_longitude, longitude)
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(GnssSpacing.xl)) {
            PositionMetric(R.string.label_altitude,
                fix?.let { "%.1f m".format(Locale.US, it.altitudeMeters) } ?: "—",
                Modifier.weight(1f))
            PositionMetric(R.string.label_accuracy,
                fix?.let { "±%.1f m".format(Locale.US, it.accuracyMeters) } ?: "—",
                Modifier.weight(1f))
        }
    }
}

@Composable
private fun PositionStatus(fix: PositionFix?) {
    val statusColor = if (fix == null) MaterialTheme.colorScheme.onSurfaceVariant
        else MaterialTheme.colorScheme.secondary
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(6.dp).background(statusColor, CircleShape))
        Spacer(Modifier.width(GnssSpacing.sm))
        Text(
            stringResource(if (fix == null) R.string.no_fix else R.string.fix_available),
            style = MaterialTheme.typography.bodySmall,
            color = statusColor,
        )
        if (fix != null) {
            Spacer(Modifier.width(GnssSpacing.md))
            Text(
                stringResource(R.string.position_accuracy_status, fix.accuracyMeters),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun CoordinateField(label: Int, value: String, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier) {
        val compact = maxWidth < 320.dp
        Column(verticalArrangement = Arrangement.spacedBy(GnssSpacing.xs)) {
            Text(
                stringResource(label),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                value,
                style = if (compact) MaterialTheme.typography.titleLarge
                    else MaterialTheme.typography.headlineSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
            )
        }
    }
}

@Composable
private fun PositionMetric(label: Int, value: String, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(GnssSpacing.xs)) {
        Text(
            stringResource(label),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(value, style = MaterialTheme.typography.titleMedium, fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun PppResult(result: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.medium,
    ) {
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
            Box(Modifier.width(3.dp).fillMaxHeight().background(MaterialTheme.colorScheme.primary))
            Column(
                Modifier.weight(1f).padding(horizontal = GnssSpacing.md, vertical = GnssSpacing.md),
                verticalArrangement = Arrangement.spacedBy(GnssSpacing.xs),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.ppp_latest_result),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.weight(1f))
                    Box(Modifier.size(6.dp).background(statusColor(result), CircleShape))
                }
                Text(
                    result,
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = FontFamily.Monospace,
                    color = if (statusColor(result) == MaterialTheme.colorScheme.error)
                        MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

@Preview(showBackground = true, name = "POS · Light")
@Composable private fun PosLightPreview() {
    val state = remember { MainUiState().apply {
        positionFix = PositionFix(39.904200, 116.407398, 48.2, 2.0f)
        tvPppResult.text = "GPST 12:36:18  ·  Q 1  ·  14 SV\n39.904200°, 116.407398°"
    } }
    GnssTheme { PosPage(state) }
}

@Preview(showBackground = true, name = "POS · Dark")
@Composable private fun PosDarkPreview() {
    val state = remember { MainUiState().apply {
        positionFix = PositionFix(39.904200, 116.407398, 48.2, 2.0f)
        tvPppResult.text = "GPST 12:36:18  ·  Q 1  ·  14 SV\n39.904200°, 116.407398°"
    } }
    GnssTheme(darkTheme = true) { PosPage(state) }
}
