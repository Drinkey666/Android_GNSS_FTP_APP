package com.example.ftpget.ui

import android.view.Gravity
import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.ftpget.GnssDataTableView
import com.example.ftpget.GnssTableColumn
import com.example.ftpget.GnssTableRow
import com.example.ftpget.GnssViewPalette
import com.example.ftpget.R
import com.example.ftpget.SatelliteDisplay
import com.example.ftpget.SignalBarView
import com.example.ftpget.SkyPlotView
import com.example.ftpget.satelliteColor

private val viewModes = listOf(R.string.view_mode_sky, R.string.view_mode_signal, R.string.view_mode_table)

/** The Activity owns all GNSS views and live updates; this page only arranges them. */
@Composable
fun ViewPage(
    state: MainUiState,
    skyPlot: SkyPlotView? = null,
    signalBars: SignalBarView? = null,
    satellites: GnssDataTableView? = null,
    modifier: Modifier = Modifier,
    initialMode: Int = 0,
) {
    var mode by rememberSaveable { mutableIntStateOf(initialMode) }
    val palette = viewPalette()

    BoxWithConstraints(modifier.fillMaxSize()) {
        if (maxWidth > maxHeight) {
            val availableHeight = maxHeight
            Row(
                modifier = Modifier.fillMaxSize().padding(
                    horizontal = GnssSpacing.xl,
                    vertical = GnssSpacing.lg,
                ),
                horizontalArrangement = Arrangement.spacedBy(GnssSpacing.xxl),
            ) {
                Column(
                    modifier = Modifier.width(216.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(GnssSpacing.xl),
                ) {
                    SignalOverview(state, compact = true)
                    ViewModeSelector(mode) { mode = it }
                }
                Column(
                    modifier = Modifier.weight(1f).fillMaxSize().verticalScroll(rememberScrollState()),
                ) {
                    ViewModeContent(
                        mode = mode,
                        state = state,
                        skyPlot = skyPlot,
                        signalBars = signalBars,
                        satellites = satellites,
                        palette = palette,
                        maxPlotHeight = (availableHeight - GnssSpacing.xxl).coerceAtLeast(160.dp),
                        onSignalClick = { mode = 1 },
                    )
                }
            }
        } else {
            Column(
                modifier = Modifier.fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = GnssSpacing.screen, vertical = GnssSpacing.xl),
                verticalArrangement = Arrangement.spacedBy(GnssSpacing.xl),
            ) {
                SignalOverview(state)
                ViewModeSelector(mode) { mode = it }
                ViewModeContent(
                    mode = mode,
                    state = state,
                    skyPlot = skyPlot,
                    signalBars = signalBars,
                    satellites = satellites,
                    palette = palette,
                    onSignalClick = { mode = 1 },
                )
                Spacer(Modifier.height(GnssSpacing.xs))
            }
        }
    }
}

@Composable
private fun SignalOverview(state: MainUiState, compact: Boolean = false) {
    Column(verticalArrangement = Arrangement.spacedBy(GnssSpacing.sm)) {
        Text(
            text = stringResource(R.string.signal_overview),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
        )
        if (compact) {
            Column(verticalArrangement = Arrangement.spacedBy(GnssSpacing.sm)) {
                SignalCount(state.satelliteCount, R.string.sat_visible_label)
                SignalCount(state.usedSatelliteCount, R.string.sat_used_label)
            }
        } else {
            Row(
                horizontalArrangement = Arrangement.spacedBy(GnssSpacing.xxl),
                verticalAlignment = Alignment.Bottom,
            ) {
                SignalCount(state.satelliteCount, R.string.sat_visible_label)
                SignalCount(state.usedSatelliteCount, R.string.sat_used_label)
            }
        }
    }
}

@Composable
private fun SignalCount(value: Int, @StringRes label: Int) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(GnssSpacing.sm),
        verticalAlignment = Alignment.Bottom,
    ) {
        Text(
            text = value.toString(),
            style = MaterialTheme.typography.headlineMedium,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = stringResource(label),
            modifier = Modifier.padding(bottom = GnssSpacing.xs),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ViewModeSelector(mode: Int, onSelect: (Int) -> Unit) {
    SecondaryTabRow(
        selectedTabIndex = mode,
        containerColor = Color.Transparent,
        contentColor = MaterialTheme.colorScheme.primary,
    ) {
        viewModes.forEachIndexed { index, label ->
            Tab(
                selected = mode == index,
                onClick = { onSelect(index) },
                text = { Text(stringResource(label), style = MaterialTheme.typography.labelLarge) },
            )
        }
    }
}

@Composable
private fun ViewModeContent(
    mode: Int,
    state: MainUiState,
    skyPlot: SkyPlotView?,
    signalBars: SignalBarView?,
    satellites: GnssDataTableView?,
    palette: GnssViewPalette,
    maxPlotHeight: Dp = 420.dp,
    onSignalClick: () -> Unit,
) {
    when (mode) {
        0 -> SkyContent(state, skyPlot, palette, maxPlotHeight, onSignalClick)
        1 -> SignalContent(state, signalBars, palette, maxPlotHeight)
        else -> TableContent(state, satellites, palette)
    }
}

@Composable
private fun SkyContent(
    state: MainUiState,
    skyPlot: SkyPlotView?,
    palette: GnssViewPalette,
    maxPlotHeight: Dp,
    onSignalClick: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(GnssSpacing.md)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(R.string.sky_distribution), style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(R.string.sky_north),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        PlotFrame(maxPlotHeight) { size ->
            if (skyPlot == null || state.satelliteCount == 0) {
                PlotPlaceholder(size)
            } else {
                AndroidView(
                    factory = { skyPlot },
                    update = { it.setPalette(palette) },
                    modifier = Modifier.size(size),
                )
            }
        }
        SatelliteLegend(palette.dark)
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(R.string.section_signal), style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = onSignalClick) {
                Text(stringResource(R.string.view_signal_access))
            }
        }
    }
}

@Composable
private fun SignalContent(
    state: MainUiState,
    signalBars: SignalBarView?,
    palette: GnssViewPalette,
    maxPlotHeight: Dp,
) {
    val chartHeight = minOf(maxPlotHeight,
        (94 + 38 * state.signalSystemCount).coerceIn(136, 280).dp)
    Column(verticalArrangement = Arrangement.spacedBy(GnssSpacing.md)) {
        SectionHeading(R.string.section_signal, stringResource(R.string.signal_hint))
        Surface(
            shape = MaterialTheme.shapes.small,
            color = MaterialTheme.colorScheme.surface,
        ) {
            if (signalBars == null || state.satelliteCount == 0) {
                PlotPlaceholder(chartHeight)
            } else {
                AndroidView(
                    factory = { signalBars },
                    update = { it.setPalette(palette) },
                    modifier = Modifier.fillMaxWidth().height(chartHeight),
                )
            }
        }
        SatelliteLegend(palette.dark)
    }
}

@Composable
private fun TableContent(state: MainUiState, satellites: GnssDataTableView?, palette: GnssViewPalette) {
    Column(verticalArrangement = Arrangement.spacedBy(GnssSpacing.md)) {
        SectionHeading(R.string.section_satellites, stringResource(R.string.measurement_hint))
        Surface(
            shape = MaterialTheme.shapes.small,
            color = MaterialTheme.colorScheme.surface,
        ) {
            if (satellites == null || state.satelliteCount == 0) {
                PlotPlaceholder(200.dp)
            } else {
                AndroidView(
                    factory = { satellites },
                    update = { it.setPalette(palette) },
                    modifier = Modifier.fillMaxWidth()
                        .height((44 + 45 * state.satelliteCount).coerceAtLeast(200).dp),
                )
            }
        }
    }
}

@Composable
private fun PlotFrame(plotLimit: Dp, content: @Composable (Dp) -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        BoxWithConstraints(contentAlignment = Alignment.Center) {
            val size = minOf(maxWidth, plotLimit)
            content(size)
        }
    }
}

@Composable
private fun PlotPlaceholder(height: Dp) {
    Box(Modifier.fillMaxWidth().height(height), contentAlignment = Alignment.Center) {
        Text(
            stringResource(R.string.preview_plot),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SatelliteLegend(dark: Boolean) {
    val systems = listOf(
        "G" to R.string.system_gps,
        "C" to R.string.system_beidou,
        "E" to R.string.system_galileo,
        "R" to R.string.system_glonass,
    )
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(GnssSpacing.lg),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        systems.forEach { (system, label) ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(GnssSpacing.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier.size(8.dp).background(
                        Color(satelliteColor(system, dark)), CircleShape,
                    ),
                )
                Text(
                    stringResource(label),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private val previewSatellites = listOf(
    SatelliteDisplay("G", 8, 34f, 42f, 307f, true),
    SatelliteDisplay("G", 15, 29f, 37f, 225f, true),
    SatelliteDisplay("G", 27, 32f, 36f, 91f, true),
    SatelliteDisplay("C", 4, 25f, 77f, 10f, true),
    SatelliteDisplay("C", 27, 28f, 21f, 85f, false),
    SatelliteDisplay("E", 12, 35f, 57f, 43f, true),
    SatelliteDisplay("E", 24, 23f, 31f, 156f, false),
    SatelliteDisplay("R", 11, 24f, 51f, 274f, true),
)

@Composable
private fun ViewPreview(dark: Boolean, mode: Int = 0) {
    val context = LocalContext.current
    val sky = remember(context) {
        SkyPlotView(context).apply { updateSatellites(previewSatellites) }
    }
    val bars = remember(context) {
        SignalBarView(context).apply { updateSatellites(previewSatellites) }
    }
    val table = remember(context) {
        GnssDataTableView(context).apply {
            configure(listOf(
                GnssTableColumn("PRN", 60, Gravity.START),
                GnssTableColumn("C/N₀", 76),
                GnssTableColumn("仰角", 76),
                GnssTableColumn("定位", 68),
            ))
            updateRows(previewSatellites.map {
                GnssTableRow(listOf("${it.system}${it.svid}",
                    "${it.cn0.toInt()}", "${it.elevation.toInt()}°",
                    if (it.usedInFix) "是" else "否"), it.system, it.usedInFix)
            })
        }
    }
    val state = remember {
        MainUiState().apply {
            satelliteCount = previewSatellites.size
            usedSatelliteCount = previewSatellites.count { it.usedInFix }
            signalSystemCount = previewSatellites.map { it.system }.distinct().size
        }
    }
    GnssTheme(darkTheme = dark) {
        ViewPage(state = state, skyPlot = sky, signalBars = bars,
            satellites = table, initialMode = mode)
    }
}

@Preview(showBackground = true, name = "VIEW · Light")
@Composable private fun ViewLightPreview() = ViewPreview(dark = false)

@Preview(showBackground = true, name = "VIEW · Dark")
@Composable private fun ViewDarkPreview() = ViewPreview(dark = true)

@Preview(showBackground = true, name = "VIEW · Signal")
@Composable private fun ViewSignalPreview() = ViewPreview(dark = false, mode = 1)

@Preview(showBackground = true, name = "VIEW · Table")
@Composable private fun ViewTablePreview() = ViewPreview(dark = false, mode = 2)
